(ns onkalo.endpoint.user-interface
  (:require [compojure.core :refer [GET POST routes wrap-routes context]]
            [compojure.route :as route]
            [ring.util.response :refer [content-type resource-response response redirect]]
            [onkalo.metadata.frontend-search :as search]
            [onkalo.routing :as routing]
            [ring.middleware.session :refer [wrap-session]]
            [search-commons.authorization :as authorization]
            [search-commons.municipality-coords :as coords]
            [clojure.string :as s]
            [onkalo.storage.document :as document]
            [onkalo.endpoint.mass-download :as md]
            [lupapiste-commons.shared-utils :refer [dissoc-in]]
            [onkalo.metadata.frontend-update :as fu]
            [lupapiste-commons.route-utils :as ru]
            [lupapiste-commons.operations :as operations]
            [taoensso.timbre :as timbre]
            [cognitect.transit :as transit])
  (:import (org.apache.commons.io IOUtils)
           (java.nio.charset StandardCharsets)))

(defn- get-organizations [session]
  (->> (get-in session [:user :orgAuthz])
       (filter (fn [[_ v]] (:archivist v)))
       (map #(name (key %)))))

(def unauthorized {:status 401
                   :body   "Unauthorized"})

(def bad-request {:status 400
                  :body   "Bad Request"})

(def authorized-roles
  #{:archivist})

(defn- guess-municipality [organizations]
  (try (-> (first organizations)
           (s/split #"-")
           (first)
           (Integer/parseInt))
       (catch Exception _)))

(defn- with-valid-org [download-fn session org-id]
  (if ((set (get-organizations session)) org-id)
    (download-fn org-id)
    unauthorized))

(defn- user-and-config-map [session frontend-config]
  (let [organizations (get-organizations session)
        coordinates (coords/center-coordinates (guess-municipality organizations))
        response-map {:user   (-> (select-keys (:user session) [:firstName :lastName])
                                  (merge {:default-map-coordinates coordinates}))
                      :config {:cdn-host           (:cdn-host frontend-config)
                               :lupapiste-enabled? false
                               :onkalo-enabled?    true}}]
    (response response-map)))

(defn- mass-operation-auths? [documents session-orgs]
  (let [allowed-org? (fn [result] (or (contains? session-orgs (:org-id result))
                                      (timbre/error "User not authorized for document"
                                                    (:doc-id result) "," (:filename result)
                                                    "with file-id"
                                                    (:file-id result)
                                                    "in organization"
                                                    (:org-id result))))]
    (every? allowed-org? documents)))

(defn- document-routes [{:keys [elastic frontend] :as config}]
  (routes (POST "/search-onkalo" {params :body-params session :session}
            (if (:page params)
              (search/search elastic (get-organizations session) params)
              bad-request))
          (POST "/update-metadata/:org-id/:doc-id" {{:keys [org-id doc-id]} :params metadata :body-params session :session}
            (if (-> session :user :impersonating)
              unauthorized
              (-> (partial fu/update-metadata config doc-id metadata (:user session))
                  (with-valid-org session org-id))))
          (GET "/download/:org-id/:doc-id" {{:keys [org-id doc-id]} :params session :session}
            (with-valid-org #(document/get-document config doc-id % :attachment) session org-id))
          (POST "/mass-download" {:keys [params session]}
            (if-let [docs-json-transit (:docs params)]
              (with-open [is (IOUtils/toInputStream ^String docs-json-transit StandardCharsets/UTF_8)]
                (let [reader (transit/reader is :json)
                      documents (transit/read reader)
                      session-orgs (set (get-organizations session))
                      authorized? (mass-operation-auths? documents session-orgs)]
                  (cond
                    (> (count documents) 200) bad-request
                    authorized? (md/get-files config documents)
                    :else unauthorized)))
              bad-request))
          (POST "/change-archiving-status" {{:keys [docs] :as params} :body-params session :session}
            (let [session-orgs (set (get-organizations session))
                  authorized? (mass-operation-auths? docs session-orgs)]
              (cond
                (-> session :user :impersonating) unauthorized
                authorized? (fu/change-archiving-status config docs params (:user session))
                :else unauthorized)))
          (POST "/mass-update-metadata" {{:keys [docs] :as params} :body-params session :session}
                (let [session-orgs (set (get-organizations session))
                      authorized? (mass-operation-auths? docs session-orgs)]
                  (cond
                    (-> session :user :impersonating) unauthorized
                    authorized? (fu/mass-update-metadata config docs params (:user session))
                    :else unauthorized)))
          (GET "/view/:org-id/:doc-id" {{:keys [org-id doc-id]} :params session :session}
            (with-valid-org #(document/get-document config doc-id % :inline) session org-id))
          (GET "/preview/:org-id/:doc-id" {{:keys [org-id doc-id]} :params session :session}
            (with-valid-org (partial document/get-preview config doc-id) session org-id))
          (GET "/operations" []
            (response (concat operations/r-operations operations/ya-operations operations/ymp-operations)))
          (GET "/user-and-config" {session :session}
            (user-and-config-map session frontend))))

(defn- i18n-route [translations]
  (GET "/i18n/:lang" [lang]
    (response (get translations (keyword lang) {}))))

(defn ui-routes [build-info {{translations :data} :translations :keys [session-store] :as config}]
  (-> (routes
        (GET "/" []
          (redirect routing/root))
        (-> (GET routing/root []
              (ru/process-index-response build-info))
            (authorization/wrap-user-authorization translations authorized-roles "/onkalo"))
        (-> (context routing/ui-root []
              (i18n-route translations)
              (document-routes config)
              (route/resources "/" {:mime-types {"js" "text/javascript; charset=utf-8"}}))
            (authorization/wrap-user-authorization translations authorized-roles)))
      (wrap-session {:store session-store})))
