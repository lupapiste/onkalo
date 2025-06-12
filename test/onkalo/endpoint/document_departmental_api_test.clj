(ns onkalo.endpoint.document-departmental-api-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.endpoint.document-departmental-api :refer :all]
            [onkalo.metadata.elastic-api]
            [onkalo.routing :refer :all]
            [onkalo.storage.document]
            [onkalo.util.document-api-util :refer [HeavyStoreResponse]]
            [onkalo.util.auth]
            [ring.middleware.defaults :as defaults]
            [ring.mock.request :as mock]
            [schema-generators.generators :as g]
            [test-util.endpoint-commons :refer :all]))

(def elastic-search-response
  {:meta    {:moreResultsAvailable false
             :from                 0
             :limit                30
             :count                1}
   :results [(g/generate HeavyStoreResponse)]})

(def empty-search-response
  {:meta    {:moreResultsAvailable false
             :from                 0
             :limit                30
             :count                0}
   :results []})

(def metadata-response (g/generate HeavyStoreResponse))

(def ok-response-with-body-map
  {:status  200
   :headers json-type
   :body    elastic-search-response})

(def ok-response
  {:status  200
   :headers json-type
   :body    (json/generate-string elastic-search-response)})

(def empty-response-with-body-map
  {:status  200
   :headers json-type
   :body    empty-search-response})

(def empty-response
  {:status  200
   :headers json-type
   :body    (json/generate-string empty-search-response)})

(def ok-metadata-response
  {:status  200
   :headers json-type
   :body    (json/generate-string metadata-response)})

(def error-response
  {:status  500
   :headers json-type})

(def forbidden-response
  {:status  403
   :headers json-type
   :body    "{\"error\":\"No access to organization 186-R\"}"})

(def forbidden-response-2
  {:status  403
   :headers json-type
   :body    "{\"error\":\"Document is not public\"}"})

(def unallowed-response
  {:status  403
   :headers json-type
   :body    "{\"error\":\"Document type is not allowed\"}"})

(def not-found-response
  {:status  404
   :headers json-type
   :body    "{\"error\":\"Document not found\"}"})

(def unauthorized-organization-response
  {:status  401
   :headers json-type
   :body    "{\"error\":\"Unauthorized organization\"}"})

(def handler (-> (document-departmental-api {:api-keys api-keys})
                 (defaults/wrap-defaults (meta-merge defaults/api-defaults
                                                     {:responses {:content-types false}}))))

(defn endpoint [name]
  (str api-v2-root "/document-departmental/documents/" name))

(defn api-call [method endpoint-name & params]
  (let [params (apply hash-map params)
        ep     (endpoint endpoint-name)
        req    (if (= method :post)
                 (mock/json-body (mock/request method ep) params)
                 (mock/request method ep params))]
    (-> req
        (mock/header "Authorization" docdepartmental-user)
        handler
        decode-body)))

(def shape "20.0,60.0;20.0,67.0;27.0,67.0;27.0,60.0;20.0,60.0")
(def geojson {"type"        "MultiPolygon"
              "coordinates" [[[[26.9 62.9] [26.9 63.1] [27.1 63.1] [27.1 62.9] [26.9 62.9]]]]})

(deftest search-vs-access-full
  (with-redefs
    [onkalo.metadata.elastic-api/find-documents (fn [_ organizations _ _ _ _ _ _]
                                                  (if ((set organizations) "753-R")
                                                    elastic-search-response
                                                    empty-search-response))]
    (testing "No organizations"
      (doseq [args [[:get "search" :shape shape]
                    [:post "search" :geojson geojson]
                    [:get "search-location-id" :shape shape]
                    [:post "search-location-id" :geojson geojson]]]
        (let [{:keys [status body]} (apply api-call args)]
          (is (= status 400))
          (is (re-find #"\{\"access-full\":\"missing-required-key\"\}" body)))))

    (testing "Organization"
      (is (= (api-call :get "search" :shape shape :access-full ["753-R"])
             ok-response))
      (is (= (api-call :get "search" :shape shape :access-full ["foo" "753-R" "bar"])
             ok-response))
      (is (= (api-call :get "search" :shape shape :access-full "753-R")
             ok-response))
      (is (= (api-call :get "search" :shape shape :access-full ["092-R"])
             empty-response))
      (is (= (api-call :get "search" :shape shape :access-full "092-R")
             empty-response))
      (is (= (api-call :post "search" :geojson geojson :access-full ["753-R"])
             ok-response)))

    (testing "Shape parameter"
      (is (= (api-call :get "search" :access-full ["753-R"])
             ok-response))
      (let [{:keys [status body]} (api-call :get "search" :shape "bad shape"
                                            :access-full ["753-R"])]
        (is (= 400 status))
        (is (re-find #"Shape must be a single closed polygon, with the coordinates given" body))))))

(deftest file-vs-access-full
  (with-redefs-fn
    {#'onkalo.util.document-api-util/find-from-elastic                                (fn [_ _ {[[_ [doc-type]]] :allowed-document-types} _ _ _ _ _]
                                                                                        (if (= doc-type "found-type")
                                                                                          ok-response-with-body-map
                                                                                          empty-response-with-body-map))
     #'onkalo.util.lupapiste-api/allowed-departmental-document-types-for-organization (fn [_ _]
                                                                                        ["allowed-type"])
     #'onkalo.storage.document/document-metadata                                      (fn [_ id _ _ _]
                                                                                        {:status 200
                                                                                         :body   (assoc metadata-response
                                                                                                        :fileId id)})
     #'onkalo.util.auth/missing-document?                                             (constantly false)
     #'onkalo.storage.document/get-document                                           (fn [_ id _ _]
                                                                                        {:status 200 :body (str "document: " id)})
     #'onkalo.storage.document/get-preview                                            (fn [_ id _]
                                                                                        {:status 200 :body (str "preview: " id)})
     #'onkalo.metadata.elastic-api/get-document                                       (fn [_ _ id]
                                                                                        (let [base-response {:metadata {:julkisuusluokka "julkinen"
                                                                                                                        :nakyvyys        "julkinen"
                                                                                                                        :myyntipalvelu   true
                                                                                                                        :henkilotiedot   "ei-sisalla"
                                                                                                                        :type            "allowed-type"}}]
                                                                                          (case id
                                                                                            "document-id" base-response
                                                                                            "unallowed-type" (assoc-in base-response [:metadata :type] "unallowed-type"))))
     #'onkalo.util.lupapiste-api/municipalities->organizations                        (fn [_ _] [{:id "753-R"}])}
    #(do
       (testing "File download"
         (is (= (api-call :get "document-id" :organization "foo" :access-full ["bar"])
                unauthorized-organization-response))
         (is (= (api-call :get "document-id" :organization "753-R" :access-full ["753-R"])
                {:status  200
                 :headers {}
                 :body    "document: document-id"})))

       (testing "File preview"
         (is (= (api-call :get "document-id/preview" :organization "foo" :access-full ["bar"])
                unauthorized-organization-response))
         (is (= (api-call :get "document-id/preview" :organization "753-R" :access-full ["753-R"])
                {:status  200
                 :headers {}
                 :body    "preview: document-id"})))

       (testing "File metadata")
       (is (= (api-call :get "document-id/metadata" :organization "foo" :access-full ["bar"])
              unauthorized-organization-response))
       (is (= (api-call :get "document-id/metadata" :organization "753-R" :access-full ["753-R"])
              {:status  200
               :headers json-type
               :body    (json/generate-string (assoc metadata-response :fileId "document-id"))}))

       (testing "File unallowed type"
         (is (= (api-call :get "unallowed-type" :organization "753-R" :access-full ["753-R"])
                unallowed-response))))))
