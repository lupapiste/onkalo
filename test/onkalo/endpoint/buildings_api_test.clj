(ns onkalo.endpoint.buildings-api-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer :all]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.endpoint.buildings-api :as sut]
            [onkalo.routing :as routing]
            [ring.middleware.defaults :as defaults]
            [ring.mock.request :as mock]
            [test-util.endpoint-commons :as util]))

(def unauthorized-response
  {:status 401
   :body   {:error "Invalid app id or key"}
   :headers {"Content-Type" "application/json; charset=utf-8"}})

(defn endpoint [name]
  (str routing/api-v2-root "/buildings/" name))

(defn decode-body [response]
  (with-open [is (:body response)]
    (let [body (json/parse-string (slurp is) true)]
      (-> (assoc response :body body)
          (dissoc :muuntaja/format :compojure.api.meta/serializable?)))))

(def handler (-> (sut/buildings-api {:api-keys util/api-keys})
                 (defaults/wrap-defaults (meta-merge defaults/api-defaults {:responses {:content-types false}}))))

(defn api-call [method endpoint-name & [{:keys [params user]}]]
  (let [params (apply hash-map params)
        ep     (endpoint endpoint-name)
        req    (mock/json-body (mock/request method ep) params)]
    (-> req
        (mock/header "Authorization" user)
        handler
        decode-body)))

(def updates
  [{"id" "string"
    "search" {"national-building-id" "182736459F"}
    "metadata" {"myyntipalvelu" true}}])

(defn mock-update-buildings! [organization building-updates db-config]
  (let [update-results (for [building-update building-updates]
                         (merge building-update
                                {:results {200 ["file-id-1" "file-id-2"]}}))]
    {:organization organization
     :results update-results}))

(deftest authorization-test
  (with-redefs
    [onkalo.endpoint.buildings-api/update-buildings! mock-update-buildings!]

    (testing "/update-buildings"

      (testing "authorized for"
        (testing "authorized user"
          (is (= 200
                 (:status (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                              :user util/authorized-user}))))))
      (testing "unauthorized for"

        (testing "doc-departmental user"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/docdepartmental-user}))))
        (testing "docterminal user"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/docterminal-user}))))

        (testing "docstore user"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/docstore-user}))))

        (testing "public user"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/public-api-user}))))

        (testing "read-only user"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/reader-api-user}))))

        (testing "specific org user when org not in allowed org"
          (is (= unauthorized-response
                 (api-call :post "update-buildings" {:params [:organization "092-R" :updates updates]
                                                     :user util/specific-org-user}))))))))
