(ns onkalo.util.auth-test
  (:require [clojure.test :refer :all]
            [onkalo.metadata.elastic-api]
            [onkalo.storage.document]
            [onkalo.util.auth :as auth]))

(def mock-request {})
(def mock-lupapiste-api nil)


(deftest authorize-user
  (testing "returns error response"
    (testing "if authorization header is invalid"
      (is (= (auth/authorize-user {:headers {:authorization "beef"}} [] identity)
             auth/unauthorized)))
    (testing "if id is not found"
      (with-redefs-fn {#'auth/decode-authorization-header (fn [_] {:id "id" :key "key"})}
        #(is (= (auth/authorize-user mock-request [{:app-id "not-the-id-provided" :key "key"}] identity)
                auth/unauthorized))))
    (testing "if key does not match"
      (with-redefs-fn {#'auth/decode-authorization-header (fn [_] {:id "id" :key "key"})}
        #(is (= (auth/authorize-user mock-request [{:app-id "id" :key "not-key"}] identity)
                auth/unauthorized))))
    (testing "if predicate does not match"
      (with-redefs-fn {#'auth/decode-authorization-header (fn [_] {:id "id" :key "key"})}
        #(is (= (auth/authorize-user mock-request
                                   [{:app-id "id" :app-key "key" :favorite-color :blue}]
                                   (fn [entry ] (= (:favorite-color entry) :red)))
                auth/unauthorized)))))

  (testing "adds api-user to request when authorization is successful"
    (with-redefs-fn {#'auth/decode-authorization-header (fn [_] {:id "api-user" :key "key"})}
      #(is (= (-> (auth/authorize-user mock-request
                                       [{:app-id "api-user" :app-key "key" :docstore-only? true}]
                                       :docstore-only?)
                  :onkalo/api-user)
              "api-user")))))

(deftest validate-organizations
  (testing "returns error response"
    (testing "when valid organizations cannot be fetched"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/docstore-enabled-orgs (fn [_] nil)}
        #(is (= (-> (auth/validate-organizations mock-request mock-lupapiste-api)
                    :status)
                500))))
    (testing "when some of the provided organizations are not Docstore enabled"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/docstore-enabled-orgs (fn [_] ["123-R" "000-R"])}
        #(is (= (-> (auth/validate-organizations (assoc-in mock-request
                                                           [:params :organization]
                                                           ["123-R" "999-R"])
                                                 mock-lupapiste-api)
                    :status)
                403)))))
  (testing "on success returns request"
    (testing "with all organizations if none were provided"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/docstore-enabled-orgs (fn [_] ["123-R" "000-R"])}
        #(is (= (-> (auth/validate-organizations mock-request
                                                 mock-lupapiste-api)
                    :onkalo/docstore-orgs)
                ["123-R" "000-R"]))))
    (testing "with the provided organizations"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/docstore-enabled-orgs (fn [_] ["123-R" "000-R" "999-R"])}
        #(do (is (= (-> (auth/validate-organizations (assoc-in mock-request
                                                               [:params :organization]
                                                               "123-R")
                                                     mock-lupapiste-api)
                        :onkalo/docstore-orgs)
                    ["123-R"]))
             (is (= (-> (auth/validate-organizations (assoc-in mock-request
                                                               [:params :organization]
                                                               ["123-R" "000-R"])
                                                     mock-lupapiste-api)
                        :onkalo/docstore-orgs)
                    ["123-R" "000-R"])))))))

(deftest validate-municipalities
  (testing "returns error response"
    (testing "when Docterminal municipalities cannot be fetched"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/municipalities->organizations (fn [_ _] [])}
        #(is (= (-> (auth/validate-municipalities mock-request mock-lupapiste-api)
                    :status)
                500))))
    (testing "when provided organizations do not match the provided municipalities"
      (with-redefs-fn {#'onkalo.util.lupapiste-api/municipalities->organizations (fn [_ _] [{:id "123-R"}])}
        #(is (= (-> (auth/validate-municipalities (assoc-in mock-request
                                                            [:params]
                                                            {:organization "000-R"
                                                             :municipality "irrelevant"})
                                                  mock-lupapiste-api)
                    :status)
                403))))))

(deftest stop-on-error
  (let [error {:body {:error "error"}}]
    (testing "stops on error"
      (is (= (auth/stop-on-error {} (constantly error))
             error))
      (is (= (auth/stop-on-error {} (constantly error) (constantly :reached-the-end))
             error))
      (is (= (auth/stop-on-error {} (constantly :this-succeeds) (constantly error) (constantly :reached-the-end))
             error)))
    (testing "threads the first argument through all given functions"
      (is (= (auth/stop-on-error 0 inc inc dec inc)
             2))
      (is (= (auth/stop-on-error :no-functions)
             :no-functions)))))
