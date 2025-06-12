(ns onkalo.endpoint.documents
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [compojure.core :refer [DELETE GET POST PUT context routes]]
            [onkalo.boundary.object-storage :as os]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.frontend-update :as fu]
            [onkalo.metadata.narc-export :as narc-export]
            [onkalo.routing :as routing]
            [onkalo.storage.document :as document]
            [onkalo.util.auth :as auth]
            [onkalo.util.document-api-util :as dau]
            [onkalo.util.log-api-usage :as lau]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.util.response :refer [content-type resource-response response status]]))

(def read-only? (atom false))

(def batchrun-user {:username "lupapiste"
                    :firstName "Lupapiste"
                    :lastName "Eräajo"})

(defn- wrap-api-authorization [handler {:keys [api-keys]} include-secret?]
  (fn [request]
    (let [{:keys [id key]} (auth/decode-authorization-header request)
          organization (or (-> request :params :organization)
                           (-> request :body-params :organization)
                           (some-> request
                                   :multipart-params
                                   (get "metadata")
                                   (json/parse-string true)
                                   :organization))
          req-orgs (if (sequential? organization) organization [organization])]
      (if (seq (filter
                 (fn [{:keys [app-id app-key public-only? docstore-only? docterminal-only? docdepartmental-only? organizations]}]
                   (and (= app-id id)
                        (= app-key key)
                        (or (not include-secret?)
                            (not public-only?))
                        (not docstore-only?)
                        (not docterminal-only?)
                        (not docdepartmental-only?)
                        (or (empty? organizations)
                            (set/subset? (set req-orgs) (set organizations)))))
                 api-keys))
        (handler (assoc request :onkalo/api-user id))
        auth/unauthorized))))

(defn- wrap-api-writer [handler api-keys]
  (fn [request]
    (if @read-only?
      {:status 503
       :body {:error "Service is temporarily in read-only mode, please try again in a moment."}}
      (let [{:keys [id key]} (auth/decode-authorization-header request)]
        (if-let [user (->> api-keys
                           (filter (fn [{:keys [app-id app-key read-only? docstore-only?]}]
                                     (and (= app-id id)
                                          (= app-key key)
                                          (not read-only?)
                                          (not docstore-only?))))
                           first)]
          (handler (assoc request :write-user user))
          auth/unauthorized)))))

(defn- build-doc-for-deletion [{:keys [elastic] {:keys [bucket-suffix]} :storage} id org]
  (when-let [doc (ea/get-document elastic (os/org-bucket org bucket-suffix) id)]
    (let [{:keys [metadata organization fileId]} doc]
      {:org-id organization
       :doc-id fileId
       :file-id fileId
       :application-id (:applicationId metadata)
       :filename (:tiedostonimi metadata)
       :type (:type metadata)
       :deleted (:deleted metadata)})))

(defn documents [{{:keys [bucket-suffix]} :storage :keys [elastic api-keys mq topics] :as config}]
  (routes
    (context (str routing/root "/documents") []
      (-> (routes
            (GET "/" [organization search-from search-limit :as {params :params}]
              (dau/find-from-elastic elastic organization params search-from search-limit false nil nil))
            ; This first by-modification is for internal use (hiidenkirnu) only as it returns docs from all organizations
            (GET "/by-modification" [from limit modified-since]
              (response (ea/all-documents-ascending-by-modification elastic modified-since from limit)))
            ; This can be provided to external parties, limited by organization id
            (GET "/by-modification-date" [organization from limit modified-since modified-before]
              (if-not organization
                (-> (response {:error "Missing organization"})
                    (status 400))
                (let [results (ea/find-all-documents elastic organization modified-since modified-before from limit)]
                  (response results))))
            (GET "/narc-export/:application-id" [application-id organization]
              (if-not organization
                (-> (response {:error "Missing organization"})
                    (status 400))
                (narc-export/sahke2-case-file config organization application-id)))
            (GET "/:id" [id organization]
              (document/get-document config id organization :attachment))
            (GET "/:id/preview" [id organization]
              (document/get-preview config id organization))
            (GET "/:id/exists" [id organization]
              (document/document-metadata config id organization :exists-only))
            (GET "/:id/metadata" [id organization]
              (document/document-metadata config id organization false))
            (-> (routes
                  (POST "/update-metadata-by-query" {{:keys [query organization metadata]} :body-params}
                    (if (and (map? query) (not-empty query) organization (map? metadata) (not-empty metadata))
                      (fu/find-and-update-metadata config query metadata batchrun-user organization)
                      (-> (response {:error "Organization, query and metadata map must be provided."})
                          (status 400))))
                  (PUT "/:id" request
                    (document/upload-document config request))
                  (PUT "/:id/update-metadata" [id :as {{:keys [organization metadata]} :body-params}]
                    (if (and organization (map? metadata) (not-empty metadata))
                      (fu/update-metadata config id metadata batchrun-user organization)
                      (-> (response {:error "Organization and metadata map must be provided."})
                          (status 400))))
                  (DELETE "/:id" [id organization]
                    (if organization
                      (if-let [document (build-doc-for-deletion config id organization)]
                        (if (-> (fu/change-archiving-status config
                                                            [document]
                                                            {:operation :undo-archiving
                                                             :deletion-explanation "Lupapisteen korjauseräajon poistama"}
                                                            batchrun-user)
                                :body
                                first
                                :success?)
                          (response {:success "Document marked successfully as deleted"})
                          (-> (response {:error "Could not mark document as deleted"})
                              (status 400)))
                        (-> (response {:error "Document not found"})
                            (status 404)))
                      (-> (response {:error "Missing organization"})
                          (status 400)))))
                (wrap-api-writer api-keys)))
          (wrap-api-authorization config true)
          (wrap-multipart-params {:fallback-encoding "UTF-8"})))
    (context (str routing/root "/public-api/documents") []
      (-> (routes
            (GET "/" [organization from limit modified-since]
              (if-not organization
                (-> (response {:error "Missing organization"})
                    (status 400))
                (let [results (ea/find-public-documents elastic organization modified-since from limit)]
                  (response results))))
            (GET "/search" [organization search-from search-limit :as {params :params}]
              (dau/find-from-elastic elastic organization params search-from search-limit :public-only? nil nil))
            (GET "/:id" [id organization :as {api-user :onkalo/api-user}]
              (auth/for-public-document
                bucket-suffix organization elastic id
                (fn [elastic-data]
                  (let [response (document/get-document config id organization :attachment)]
                    (when (= (:status response) 200)
                      (lau/log-api-usage mq (:api-usage topics) api-user "" elastic-data))
                    response))))
            (GET "/:id/preview" [id organization]
              (auth/for-public-document
                bucket-suffix organization elastic id
                (fn [_]
                  (document/get-preview config id organization))))
            (GET "/:id/exists" [id organization]
              (auth/for-public-document
                bucket-suffix organization elastic id
                (fn [_]
                  (document/document-metadata config id organization :exists-only))))
            (GET "/:id/metadata" [id organization]
              (auth/for-public-document
                bucket-suffix organization elastic id
                (fn [_]
                  (document/document-metadata config id organization false)))))
          (wrap-api-authorization config false)))
    (GET (str routing/root "/schemas/sahke2-case-file/2016/6/1") []
      (-> (resource-response "onkalo-case-file-schema-2016-06-01.xsd" {:root "public"})
          (content-type "text/xml")))
    (GET "/internal/lock" []
      (reset! read-only? true)
      (response "ok"))
    (GET "/internal/unlock" []
      (reset! read-only? false)
      (response "ok"))))
