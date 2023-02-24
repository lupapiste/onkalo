(ns onkalo.util.auth
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.set :as set]
            [clojure.string :as str]
            [onkalo.boundary.object-storage :as os]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.util.lupapiste-api :as la]
            [potpuri.core :as p])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def unauthorized
  {:status 401
   :body   {:error "Invalid app id or key"}})

(defn decode-authorization-header [{{:strs [authorization]} :headers}]
  (when-let [base-64-str (and authorization (second (str/split authorization #" ")))]
    (let [bytes   (.getBytes base-64-str StandardCharsets/UTF_8)
          decoder (Base64/getDecoder)
          [provided-id provided-key] (-> (String. ^bytes (.decode decoder ^bytes bytes) StandardCharsets/UTF_8)
                                         (str/split #":" 2))]
      {:id provided-id :key provided-key})))

(defn sequentialize [x]
  (if (sequential? x) x [x]))

(defn authorize-user
  "If authorization is successful, returns the given request with :onkalo/api-user
  containing the given id. Otherwise returns error response. Authorization is successful
  if the request's basic auth is well formed, the corresponding app-id and app-key can
  be found in api-keys and the api-key entry satisfies the predicate p."
  [request api-keys p]
  (if-let [{:keys [id key]} (decode-authorization-header request)]
    (if (some (fn [{:keys [app-id app-key] :as api-key}]
                    (and (= app-id id) (= app-key key) (p api-key)))
                  api-keys)
          (assoc request :onkalo/api-user id)
      unauthorized)
    unauthorized))

(defn- request-organization [request]
  (-> request :params :organization))

(defn- organizations-for-request [request docstore-organizations]
  (let [organization          (request-organization request)
        request-organizations (sequentialize organization)]
    (cond (not organization)
          docstore-organizations

          (set/subset? (set request-organizations)
                       (set docstore-organizations))
          request-organizations

          :else nil)))

(defn validate-organizations
  "If request contains valid organizations, returns the given request with
  :onkalo/docstore-orgs which contains the organizations for the context of
  the request. Otherwise returns error response. If no organizations are
  provided, the context encompasses all docstore enabled organizations.
  Otherwise the organizations provided in the request are used. These must all
  be Docstore enabled organizations. Returns internal server error response if
  Docstore enabled organizations cannot be fetched."
  [request lupapiste-api]
  (if-let [docstore-orgs (seq (la/docstore-enabled-orgs lupapiste-api))]
    (if-let [organizations (organizations-for-request request docstore-orgs)]
      (assoc request :onkalo/docstore-orgs organizations)
      {:status 403
       :body   {:error (str "No access to organization " (request-organization request))}})
    {:status 500
     :body   {:error "Could not find organizations for document store API"}}))

(defn stop-on-error [request & functions]
  (reduce (fn [req f]
            (if (-> req :body :error)
              req
              (f req)))
          request
          functions))

(defn wrap-docstore-auth [{:keys [api-keys lupapiste-api]}]
  (fn [handler]
    (fn [request]
      (stop-on-error request
                     #(authorize-user % api-keys :docstore-only?)
                     #(validate-organizations % lupapiste-api)
                     handler))))

(defn municipalities->organizations [lupapiste-api municipalities]
  (some->> municipalities
           sequentialize
           (la/municipalities->organizations lupapiste-api)
           (map :id)
           (remove nil?)
           seq))

(defn validate-municipalities
  "Validates that the municipalities and organizations provided as request
  parameters are valid. Returns request on success, otherwise error response."
  [request lupapiste-api]
  (let [{:keys [municipality organization]} (:params request)]
    (if-let [docterminal-orgs (municipalities->organizations lupapiste-api
                                                             municipality)]
      (cond (not organization)
            (assoc request :onkalo/docterminal-orgs docterminal-orgs)

            (set/subset? (set (sequentialize organization))
                         (set docterminal-orgs))
            request

            :else
            {:status 403
             :body   {:error (str "No access to organization " organization)}})
      {:status 500
       :body   {:error "Could not find organizations for document terminal API"}})))

(defn wrap-docterminal-auth [{:keys [api-keys lupapiste-api]}]
  (fn [handler]
    (fn [request]
      (stop-on-error request
                     #(authorize-user % api-keys :docterminal-only?)
                     #(validate-municipalities % lupapiste-api)
                     handler))))

(defn- parse-date [date-str]
  (when date-str
    (try (tf/parse (:date-time tf/formatters) date-str)
         (catch Exception _))))

(defn for-public-document [bucket-suffix organization elastic id download-fn]
  (cond
    (not organization)
    {:status 400
     :body   {:error "Missing organization"}}
    (sequential? organization)
    {:status 400
     :body   {:error "A single organization must be provided as a query parameter"}}
    :else
    (let [bucket       (os/org-bucket organization bucket-suffix)
          {{:keys [security-period-end julkisuusluokka nakyvyys
                   myyntipalvelu henkilotiedot deleted]} :metadata
           :as                                           elastic-data} (ea/get-document elastic bucket id)
          sec-end-date (parse-date security-period-end)]
      (if (and julkisuusluokka
               (not deleted))
        (if (and
              myyntipalvelu
              (= "julkinen" nakyvyys)
              (or (= "julkinen" julkisuusluokka) (t/after? (t/now) sec-end-date))
              (not= "sisaltaa-arkaluonteisia" henkilotiedot))
          (download-fn elastic-data)
          {:status 403
           :body   {:error "Document is not public"}})

        {:status 404
         :body   {:error "Document not found"}}))))

(defn- allowed-type-for-organization? [lupapiste-api organization doc-type]
  (contains? (set (la/allowed-terminal-document-types-for-organization lupapiste-api organization))
             doc-type))

(defn for-docterminal-document
  [{:keys [lupapiste-api]} bucket-suffix organization elastic id download-fn]
  (cond
    (not organization)
    {:status 400
     :body   {:error "Missing organization"}}
    (sequential? organization)
    {:status 400
     :body   {:error "A single organization must be provided as a query parameter"}}
    :else
    (let [bucket       (os/org-bucket organization bucket-suffix)
          {{:keys    [security-period-end julkisuusluokka nakyvyys henkilotiedot deleted]
            doc-type :type} :metadata} (ea/get-document elastic bucket id)
          sec-end-date (parse-date security-period-end)]
      (cond (not (allowed-type-for-organization? lupapiste-api organization doc-type))
            {:status 403
             :body   {:error "Document type is not allowed"}}

            (or (not julkisuusluokka)
                deleted)
            {:status 404
             :body   {:error "Document not found"}}

            (and (= "julkinen" nakyvyys)
                 (or (= "julkinen" julkisuusluokka) (t/after? (t/now) sec-end-date))
                 (not= "sisaltaa-arkaluonteisia" henkilotiedot))
            (download-fn)

            :else
            {:status 403
             :body   {:error "Document is not public"}}))))

(defn only-authorized-lupapiste-user [req-org {:keys [docstore-only? docterminal-only? docdepartmental-only?
                                                      public-only? read-only? organizations] :as api-key}]
  (and (not public-only?)
       (not docstore-only?)
       (not docterminal-only?)
       (not docdepartmental-only?)
       (not read-only?)
       (if organizations
         (contains? (set organizations) req-org)
         true)))

(defn wrap-lupapiste-auth [{:keys [api-keys]}]
  (fn [handler]
    (fn [request]
      (let [req-org (request-organization request)]
        (stop-on-error request
                       #(authorize-user % api-keys (partial only-authorized-lupapiste-user req-org))
                       handler)))))

(defn wrap-session-user-auth [handler]
  (fn [request]
    (let [archivist-orgs (->> request
                              :session
                              :user
                              :orgAuthz
                              (p/filter-vals #(contains? % :archivist))
                              keys
                              (map name)
                              set)
          req-org (request-organization request)]
      (if (contains? archivist-orgs req-org)
        (handler request)
        {:status 403
         :body   {:error "Forbidden"}}))))

;;
;; Department terminal (departmental)
;;

(defn wrap-docdepartmental-auth [{:keys [api-keys]}]
  (fn [handler]
    (fn [request]
      (stop-on-error request
                     #(authorize-user % api-keys :docdepartmental-only?)
                     handler))))

(defn- error-response [status & msg]
  {:status status
   :body   {:error (apply str msg)}})

(defn- missing-document? [bucket-suffix elastic organization id]
  (let [bucket (os/org-bucket organization bucket-suffix)
        {:keys [julkisuusluokka
                deleted]} (:metadata (ea/get-document elastic bucket id))]
    (boolean (or (not julkisuusluokka) deleted))))

(defn- allowed-departmental-type-for-organization? [lupapiste-api organization doc-type]
  (contains? (set (la/allowed-departmental-document-types-for-organization lupapiste-api organization))
             doc-type))

(defn for-docdepartmental-document
  [{:keys [lupapiste-api]} bucket-suffix elastic {:keys [id organization access-full]} download-fn]
  (let [bucket       (os/org-bucket organization bucket-suffix)
        {{doc-type :type} :metadata} (ea/get-document elastic bucket id)]
    (cond
      (not (allowed-departmental-type-for-organization? lupapiste-api organization doc-type))
      (error-response 403 "Document type is not allowed")

      (not organization)
      (error-response 400 "Bad or missing organization")

      (not (contains? (set access-full) organization))
      (error-response 401 "Unauthorized organization")

      (missing-document? bucket-suffix elastic organization id)
      (error-response 404 "Document not found")

      :else (download-fn)))

  )
