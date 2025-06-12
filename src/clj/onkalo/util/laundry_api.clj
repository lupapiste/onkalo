(ns onkalo.util.laundry-api
  (:require [clj-http.client :as http]
            [lupapiste-pubsub.bootstrap :as pubsub-bootstrap]
            [schema-tools.core :as st]
            [schema.core :as sc :refer [defschema]]
            [taoensso.timbre :as timbre])
  (:import [clojure.lang ExceptionInfo]
           [com.google.api.gax.core CredentialsProvider GoogleCredentialsProvider]
           [com.google.auth.oauth2 IdTokenCredentials IdTokenProvider ServiceAccountCredentials]
           [java.util Collection]))

(set! *warn-on-reflection* true)

(defschema Laundry
  (st/merge
    {:host             sc/Str
     ;; False: Both Onkalo and Laundry instances within GCP
     ;; True: Onkalo outside GCP (e.g. local instance) but invoking
     ;; Laundry in GCP.
     :iap-oauth-client sc/Bool}
    (st/optional-keys {:iap-oauth-client-id  sc/Str
                       :service-account-file sc/Str})))

(def ^String iam-scope "https://www.googleapis.com/auth/iam")
(def ^Collection scopes [iam-scope])

(defn- credentials-provider
  ^CredentialsProvider [{:keys [service-account-file]}]
  (if service-account-file
    (pubsub-bootstrap/fixed-credentials-provider service-account-file)
    (-> (GoogleCredentialsProvider/newBuilder)
        (.setScopesToApply ["https://www.googleapis.com/auth/cloud-platform"])
        (.build))))

(defn- get-id-token-provider
  ^IdTokenProvider [laundry]
  (let [^IdTokenProvider credentials (-> ^ServiceAccountCredentials (.getCredentials (credentials-provider laundry))
                                         (.createScoped scopes))]
    (when (nil? credentials)
      (timbre/error "Could not load credentials for IAP Bearer Authorization"))
    (when-not (instance? IdTokenProvider credentials)
      (timbre/errorf "Expected credentials that can provide id tokens, got %s instead"
                     (-> credentials (.getClass) (.getName))))
    credentials))

(defonce ^:private credentials-cache (atom {}))

(defn- get-credentials
  ^IdTokenCredentials [{:keys [iap-oauth-client-id] :as laundry}]
  (or (get @credentials-cache iap-oauth-client-id)
      (let [credentials (-> (IdTokenCredentials/newBuilder)
                            (.setIdTokenProvider (get-id-token-provider laundry))
                            (.setTargetAudience iap-oauth-client-id)
                            (.build))]
        (swap! credentials-cache assoc iap-oauth-client-id credentials)
        credentials)))

(defn- get-id-token
  [laundry]
  (let [credentials (get-credentials laundry)]
    (.refreshIfExpired credentials)
    (some-> credentials
            (.getIdToken)
            (.getTokenValue))))

(defn- oauth-token-if-required
  "IAP token needed if the target laundry instance is within GCP and we are outside.
  Calls within GCP do not need the token."
  [{:keys [iap-oauth-client iap-oauth-client-id] :as laundry}]
  (when (and iap-oauth-client iap-oauth-client-id)
    {:oauth-token (get-id-token laundry)}))

(def max-dimension
  "National archives specifies a maximum resolution of 65535 x 65535."
  65535)

(defn- do-check-image-properties!
  [{:keys [host] :as laundry} bucket object-id]
  (try
    {:properties (-> (http/post (str host "/api/get-image-properties")
                                (merge {:form-params  {:bucket     bucket
                                                       :object-key object-id}
                                        :content-type :transit+json
                                        :accept       :transit+json
                                        :as           :transit+json}
                                       (oauth-token-if-required laundry)))
                     :body)}
    (catch ExceptionInfo e
      {:error (ex-data e)})))

(defschema VerifyArgs
  {:bucket                sc/Str
   :object-id             sc/Str
   :expected-content-type sc/Str})

(sc/defn verify-image-archivable!
  "Verify (using laundry) that given object is an image matching the content type
  and has valid properties wrt. archiving. Blocks.
  Returns true if image was valid."
  {:always-validate true}
  [laundry :- Laundry
   {:keys [bucket object-id expected-content-type]} :- VerifyArgs]
  (timbre/infof "Verifying if object %s is %s" object-id expected-content-type)
  (let [{:keys [error properties]} (do-check-image-properties! laundry bucket object-id)]
    (boolean
      (cond
        error      (let [{:keys [status body]} error]
                     (timbre/warnf "Failed to verify file is image, status: %d, error: %s" status body)
                     false)
        properties (let [{:keys [width height content-type]} properties
                         valid?                              (and width height content-type
                                                                  (<= 1 width max-dimension)
                                                                  (<= 1 height max-dimension)
                                                                  (= content-type expected-content-type))]
                     (when-not valid?
                       (timbre/warnf "Invalid image. Content-type %s, w: %d, h:%d" content-type width height))
                     valid?)))))
