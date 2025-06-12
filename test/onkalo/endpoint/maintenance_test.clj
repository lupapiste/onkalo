(ns onkalo.endpoint.maintenance-test
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [ring.middleware.defaults :as defaults]
            [ring.mock.request :as mock]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.endpoint.maintenance :refer :all]
            [test-util.endpoint-commons :as tuec]))

(def ok-response
  {:status 200
   :headers {}
   :body "Mass update onkalo metadata is now running. Check log to see results."})

(def error-response
  {:status 400
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body "\":{\"meta-updates\":\"missing-required-key\"},\""})

(def handler (-> (maintenance-api {:api-keys tuec/api-keys})
                 (defaults/wrap-defaults (meta-merge defaults/api-defaults {:responses {:content-types false}}))))

(defn decode-body [response]
  (with-open [is (:body response)]
    (-> (assoc response :body (-> (last (s/split (slurp is) #"errors"))
                                  (s/split #"type")
                                  (first)))
        (dissoc :muuntaja/format :compojure.api.meta/serializable?))))

(deftest mass-update-validates-parameters
  (is (= (decode-body (handler (-> (mock/request :post "/internal/mass-update")
                                   (mock/json-body {:organization     "753-R"
                                                    :attachment-types ["osapuolet.cv"]}))))
         error-response)))

(deftest mass-update-succeed-with-valid-parameters
  (is (=  (handler (-> (mock/request :post "/internal/mass-update")
                       (mock/json-body {:organization "753-R"
                                        :attachment-types ["osapuolet.cv"]
                                        :meta-updates {:myyntipalvelu true}})))
         ok-response)))

(deftest mass-update-change-with-building-ids
  (is (= (handler (-> (mock/request :post "/internal/mass-update")
                      (mock/json-body {:organization "186-R"
                                       :building-ids ["bid1"]
                                       :meta-updates {:myyntipalvelu false}})))
          ok-response)))

(deftest mass-update-change-with-national-building-ids
  (is (= (handler (-> (mock/request :post "/internal/mass-update")
                      (mock/json-body {:organization "186-R"
                                       :national-building-ids ["national-bid1"]
                                       :meta-updates {:myyntipalvelu false}})))
          ok-response)))

(deftest mass-update-change-with-addresses
  (is (= (handler (-> (mock/request :post "/internal/mass-update")
                      (mock/json-body {:organization "186-R"
                                       :addresses ["Seutulantie 10"]
                                       :meta-updates {:myyntipalvelu false}})))
          ok-response)))

(deftest mass-update-change-with-filenames
  (is (= (handler (-> (mock/request :post "/internal/mass-update")
                      (mock/json-body {:organization "186-R"
                                       :filenames ["asemapiirros.pdf"]
                                       :meta-updates {:myyntipalvelu false}})))
          ok-response)))
