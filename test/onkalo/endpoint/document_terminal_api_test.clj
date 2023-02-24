(ns onkalo.endpoint.document-terminal-api-test
  (:require [clojure.test :refer :all]
            [onkalo.endpoint.document-terminal-api :refer :all]
            [ring.mock.request :as mock]
            [ring.middleware.defaults :as defaults]
            [onkalo.metadata.elastic-api]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.routing :refer :all]
            [onkalo.util.document-api-util :refer [HeavyStoreResponse]]
            [test-util.endpoint-commons :refer :all]
            [schema-generators.generators :as g]
            [cheshire.core :as json])
  (:import [java.io InputStream]))

(def elastic-search-response
  {:meta {:moreResultsAvailable false
          :from                 0
          :limit                30
          :count                1}
   :results [(g/generate HeavyStoreResponse)]})

(def empty-search-response
  {:meta {:moreResultsAvailable false
          :from                 0
          :limit                30
          :count                0}
   :results []})

(def metadata-response (g/generate HeavyStoreResponse))

(def ok-response-with-body-map
  {:status  200
   :body    elastic-search-response})

(def ok-response
  {:status  200
   :headers json-type
   :body    (json/generate-string elastic-search-response)})

(def empty-response-with-body-map
  {:status  200
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
  {:status 500
   :headers json-type
   :body "{\"error\":\"Could not find organizations for document terminal API\"}"})

(def forbidden-response
  {:status 403
   :headers json-type
   :body "{\"error\":\"No access to organization 186-R\"}"})

(def forbidden-response-2
  {:status 403
   :headers json-type
   :body "{\"error\":\"Document is not public\"}"})

(def unallowed-response
  {:status 403
   :headers json-type
   :body "{\"error\":\"Document type is not allowed\"}"})

(def not-found-response
  {:status 404
   :headers json-type
   :body "{\"error\":\"Document not found\"}"})

(def handler (-> (document-terminal-api {:api-keys api-keys})
                 (defaults/wrap-defaults (meta-merge defaults/api-defaults {:responses {:content-types false}}))))

(defn endpoint [name]
  (str api-v2-root "/document-terminal/documents/" name))

(deftest docterminal-user-needs-to-specify-municipalities-for-search
  (with-redefs-fn
    {#'onkalo.metadata.elastic-api/find-documents                                 (fn [_ organization _ _ _ _ _ _]
                                                                                    (when (#{["753-R"]} organization)
                                                                                      elastic-search-response))
     #'onkalo.util.lupapiste-api/allowed-terminal-document-types-for-organization (fn [_ _]
                                                                                    ["allowed-type"])
     #'onkalo.util.lupapiste-api/municipalities->organizations                    (fn [_ municipalities]
                                                                                    (when (= municipalities ["753"])
                                                                                      [{:id "753-R"}]))}
    #(do
       ;; No municipality is provided
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "search"))
                                        (mock/header "Authorization" docterminal-user))))
              error-response))
       ;; In this test, municipality code 753 corresponds to organization 753-R
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "search") {:shape "20.0,60.0;20.0,67.0;27.0,67.0;27.0,60.0;20.0,60.0"
                                                                                :municipality ["753"]})
                                        (mock/header "Authorization" docterminal-user))))
              ok-response)))))

(deftest only-allowed-documents-returned-by-docterminal-search
  ;; This test confirms that the allowed types provided by allowed-document-types-for-organization
  ;; are passed to find-from-elastic
  (with-redefs-fn
    {#'onkalo.util.document-api-util/find-from-elastic                            (fn [_ _ {[[_ [doc-type]]] :allowed-document-types} _ _ _ _ _]
                                                                                    (if (= doc-type "found-type")
                                                                                      ok-response-with-body-map
                                                                                      empty-response-with-body-map))
     #'onkalo.util.lupapiste-api/allowed-terminal-document-types-for-organization (fn [_ org]
                                                                                    (if (= org "753-R")
                                                                                      ["found-type"]
                                                                                      ["not-found-type"]))
     #'onkalo.util.lupapiste-api/municipalities->organizations                    (fn [_ municipalities]
                                                                                    ({["753"] [{:id "753-R"}]
                                                                                      ["186"] [{:id "186-R"}]}
                                                                                     municipalities))}
    #(do
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "search") {:shape "20.0,60.0;20.0,67.0;27.0,67.0;27.0,60.0;20.0,60.0"
                                                                                :municipality ["753"]})
                                        (mock/header "Authorization" docterminal-user))))
              ok-response))
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "search") {:shape "20.0,60.0;20.0,67.0;27.0,67.0;27.0,60.0;20.0,60.0"
                                                                                :municipality ["186"]})
                                        (mock/header "Authorization" docterminal-user))))
              empty-response)))))

(deftest docterminal-user-cant-access-anything-if-no-municipalities-from-api
  (with-redefs-fn
    {#'onkalo.util.lupapiste-api/municipalities->organizations                    (fn [_ _] [])
     #'onkalo.util.lupapiste-api/allowed-terminal-document-types-for-organization (fn [_ _]
                                                                           ["allowed-type"])}
    #(do
       ;; The given municipality codes do not have matching organizations
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "search") {:shape "20.0,60.0;20.0,67.0;27.0,67.0;27.0,60.0;20.0,60.0"
                                                                                :municipality ["123" "234"]})
                                        (mock/header "Authorization" docterminal-user))))
              error-response)))))

(deftest organization-must-correspond-to-municipalities
  (with-redefs-fn
    {#'onkalo.util.auth/for-docterminal-document                                  (fn [_ _ organization _ _ _]
                                                                                    (when (#{"753-R"} organization)
                                                                                      {:status 200
                                                                                       :body   metadata-response}))
     #'onkalo.util.lupapiste-api/allowed-terminal-document-types-for-organization (fn [_ _]
                                                                                    ["allowed-type"])
     #'onkalo.util.lupapiste-api/municipalities->organizations                    (fn [_ municipalities]
                                                                                    (when (= municipalities ["753"])
                                                                                      [{:id "753-R"}]))}
    #(do
       ;; The provided organization does not match  any of the provided municipalities
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "1234/metadata") {:organization "186-R"
                                                                                       :municipality ["753"]})
                                        (mock/header "Authorization" docterminal-user))))
              forbidden-response))

       ;; The provided organization matches: 753-R -> 753
       (is (= (decode-body (handler (-> (mock/request :get (endpoint "1234/metadata") {:organization "753-R"
                                                                                       :municipality ["753"]})
                                        (mock/header "Authorization" docterminal-user))))
              ok-metadata-response)))))

(deftest organization-with-public-only-or-docterminal-only-can-only-access-public-documents
  (let [public-ids ["public" "previously-secret" "secret3"]
        secret-ids ["secret1" "secret2" "secret4"]
        unallowed-type "unallowed-type"
        ok-response2 {:status 200 :body "data" :headers {"Content-Type" "text/plain; charset=utf-8"}}]
    (with-redefs-fn {#'onkalo.storage.document/get-document                                       (fn [& _] {:status  200
                                                                                                             :body    "data"
                                                                                                             :headers {"Content-Type" "text/plain"}})
                     #'onkalo.util.lupapiste-api/allowed-terminal-document-types-for-organization (fn [_ _]
                                                                                                    ["allowed-type"])
                     #'onkalo.util.lupapiste-api/municipalities->organizations                    (fn [_ _] [{:id "753-R"}])
                     #'onkalo.metadata.elastic-api/get-document                                   (fn [_ _ id]
                                                                                                    (let [base-response {:metadata {:julkisuusluokka "julkinen"
                                                                                                                                    :nakyvyys        "julkinen"
                                                                                                                                    :myyntipalvelu   true
                                                                                                                                    :henkilotiedot   "ei-sisalla"
                                                                                                                                    :type            "allowed-type"}}]
                                                                                                      (case id
                                                                                                        "public" base-response
                                                                                                        "secret1" (assoc-in base-response [:metadata :nakyvyys] "viranomainen")
                                                                                                        "secret2" (meta-merge base-response {:metadata {:julkisuusluokka     "salainen"
                                                                                                                                                        :security-period-end "9999-01-01T12:00:00.000Z"}})
                                                                                                        "secret3" (assoc-in base-response [:metadata :henkilotiedot] "sisaltaa")
                                                                                                        "secret4" (assoc-in base-response [:metadata :henkilotiedot] "sisaltaa-arkaluonteisia")
                                                                                                        "previously-secret" (meta-merge base-response {:metadata {:julkisuusluokka     "salainen"
                                                                                                                                                                  :security-period-end "2016-01-01T12:00:00.000Z"}})
                                                                                                        "unallowed-type" (assoc-in base-response [:metadata :type] "unallowed-type")
                                                                                                        "deleted" (assoc-in base-response [:metadata :deleted] "2018-03-26T20:44:10.885Z")
                                                                                                        "ei-myyntipalvelussa" (assoc-in base-response [:metadata :myyntipalvelu] false))))}
      #(do (doseq [id public-ids]
             (is (= (decode-body (handler (-> (mock/request :get (endpoint id) {:organization "753-R"
                                                                                :municipality ["753"]})
                                              (mock/header "Authorization" docterminal-user))))
                    ok-response2)))
         (doseq [id secret-ids]
           (is (= (decode-body (handler (-> (mock/request :get (endpoint id) {:organization "753-R"
                                                                              :municipality ["753"]})
                                            (mock/header "Authorization" docterminal-user))))
                  forbidden-response-2)))
         (is (= (decode-body (handler (-> (mock/request :get (endpoint unallowed-type) {:organization "753-R"
                                                                                        :municipality ["753"]})
                                          (mock/header "Authorization" docterminal-user))))
                unallowed-response))

         (let [id "deleted"]
           (is (= (decode-body (handler (-> (mock/request :get (endpoint id) {:organization "753-R"
                                                                              :municipality ["753"]})
                                            (mock/header "Authorization" docterminal-user))))
                  not-found-response)))

         (let [id "ei-myyntipalvelussa"]
           (is (= (decode-body (handler (-> (mock/request :get (endpoint id) {:organization "753-R"
                                                                              :municipality ["753"]})
                                            (mock/header "Authorization" docterminal-user))))
                  ok-response2)))))))
