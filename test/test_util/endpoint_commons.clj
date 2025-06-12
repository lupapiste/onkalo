(ns test-util.endpoint-commons
  (:require [clojure.test :refer :all])
  (:import [java.io InputStream]))

(def authorized-user "Basic Zm9vOmJhcg==")

(def specific-org-user "Basic c3BlY2lmaWM6YmFy")

(def public-api-user "Basic cHVibGljOmJhcg==")

(def reader-api-user "Basic cmVhZGVyOmJhcg==")

(def docstore-user "Basic ZG9jc3RvcmU6MTIzNDU2Nzg=")

(def docstore-non-billing-user "Basic ZG9jdW1lbnRfc3RvcmVfdGVzdDoxMjM0NTY3OA==")

(def docterminal-user "Basic ZG9jdGVybWluYWw6MjM0NTY3ODk=")

(def docdepartmental-user "Basic ZG9jZGVwYXJ0bWVudGFsOjM0NTY3ODkw")

(def api-keys [{:app-id  "foo"
                :app-key "bar"}
               {:app-id       "public"
                :app-key      "bar"
                :public-only? true}
               {:app-id        "specific"
                :app-key       "bar"
                :organizations ["186-R"]}
               {:app-id        "reader"
                :app-key       "bar"
                :read-only?    true
                :organizations ["186-R" "753-R"]}
               {:app-id         "docstore"
                :app-key        "12345678"
                :docstore-only? true}
               {:app-id            "docterminal"
                :app-key           "23456789"
                :docterminal-only? true}
               {:app-id         "document_store_test"
                :app-key        "12345678"
                :docstore-only? true}
               {:app-id                "docdepartmental"
                :app-key               "34567890"
                :docdepartmental-only? true}])

(def json-type {"Content-Type" "application/json; charset=utf-8"})

(def mock-elastic
  (partial with-redefs-fn {#'onkalo.metadata.elastic-api/find-documents (fn [& _] {:results ["foo"]})
                           #'onkalo.metadata.elastic-api/all-documents-ascending-by-modification (fn [& _] {:results ["foo"]})
                           #'onkalo.metadata.elastic-api/find-all-documents (fn [& _] {:results ["foo"]})}))

(defn decode-body [{:keys [body] :as response}]
  (-> (if (instance? InputStream body)
        (with-open [is (:body response)]
          (assoc response :body (slurp is)))
        response)
      (dissoc :compojure.api.meta/serializable?)))
