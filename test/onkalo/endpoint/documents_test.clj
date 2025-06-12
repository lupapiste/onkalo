(ns onkalo.endpoint.documents-test
  (:require [clojure.test :refer :all]
            [onkalo.endpoint.documents :refer :all]
            [ring.mock.request :as mock]
            [ring.middleware.defaults :as defaults]
            [onkalo.metadata.elastic-api]
            [meta-merge.core :refer [meta-merge]]
            [test-util.endpoint-commons :refer :all]))

(def ok-response
  {:status 200
   :headers {}
   :body {:results ["foo"]}})

(def error-response
  {:status 500
   :body {:error "Could not find organizations for document store API"}})

(def unauthorized-response
  {:status 401
   :body   {:error "Invalid app id or key"}})

(def handler (-> (documents {:api-keys api-keys})
                 (defaults/wrap-defaults (meta-merge defaults/api-defaults {:responses {:content-types false}}))))

(deftest authentication-must-be-provided
  (let [get-routes ["/onkalo/documents"
                    "/onkalo/documents/foobar"
                    "/onkalo/documents/foobar/preview"
                    "/onkalo/public-api/documents"
                    "/onkalo/public-api/documents/foobar"
                    "/onkalo/public-api/documents/foobar/preview"]]
    (doseq [r get-routes]
      (is (= (handler (mock/request :get r))
             unauthorized-response)
          (str r " should be unauthorized")))
    (is (= (handler (mock/request :put "/onkalo/documents/foobar"))
           unauthorized-response))))

(deftest authorized-user-gets-access
  (mock-elastic
    #(is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "753-R"})
                         (mock/header "Authorization" authorized-user)))
            ok-response))))

(deftest authorized-user-gets-access-to-all-by-modification
  (mock-elastic
    #(is (= (handler (-> (mock/request :get "/onkalo/documents/by-modification")
                         (mock/header "Authorization" authorized-user)))
            ok-response))))

(deftest user-with-organization-white-list-can-access-only-those
  (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "753-R"})
                      (mock/header "Authorization" specific-org-user)))
         unauthorized-response))
  (is (= (handler (-> (mock/request :get "/onkalo/documents/by-modification")
                      (mock/header "Authorization" specific-org-user)))
         unauthorized-response))
  (mock-elastic
    #(do (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "186-R"})
                             (mock/header "Authorization" specific-org-user)))
                ok-response))
         (is (= (handler (-> (mock/request :get "/onkalo/documents/by-modification-date" {:organization "186-R"})
                             (mock/header "Authorization" specific-org-user)))
                ok-response)))))

(deftest organization-with-public-only-can-only-access-public-api
  (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "753-R"})
                      (mock/header "Authorization" public-api-user)))
         unauthorized-response))
  (with-redefs-fn {#'onkalo.metadata.elastic-api/find-public-documents (fn [& _] {:results ["foo"]})}
    #(is (= (handler (-> (mock/request :get "/onkalo/public-api/documents" {:organization "753-R"})
                         (mock/header "Authorization" public-api-user)))
            ok-response))))

(deftest organization-with-public-only-can-access-public-search-api
  (with-redefs-fn {#'onkalo.metadata.elastic-api/find-documents (fn [_ _ _ _ _ public-only? _ _]
                                                                  (when public-only? {:results ["foo"]}))}
    #(is (= (handler (-> (mock/request :get "/onkalo/public-api/documents/search" {:organization "753-R"})
                         (mock/header "Authorization" public-api-user)))
            ok-response))))

; Commented out because the API is disabled
(deftest organization-with-public-only-or-docstore-only-can-only-access-public-documents
  (let [public-ids ["public" "previously-secret" "secret4"]
        secret-ids ["secret1" "secret2" "secret3" "secret5"]
        forbidden-response {:status 403 :headers {} :body {:error "Document is not public"}}
        not-found-response {:status 404 :headers {} :body {:error "Document not found"}}
        ok-response2 {:status 200 :body "data" :headers {"Content-Type" "text/plain; charset=utf-8"}}]
    (with-redefs-fn {#'onkalo.storage.document/get-document            (fn [& _] {:status 200
                                                                     :body                "data"
                                                                     :headers             {"Content-Type" "text/plain"}})
                     #'onkalo.util.lupapiste-api/docstore-enabled-orgs (fn [_] ["753-R" "186-R"])
                     #'onkalo.metadata.elastic-api/get-document        (fn [_ _ id]
                                                                  (let [base-response {:metadata {:julkisuusluokka "julkinen"
                                                                                                  :nakyvyys "julkinen"
                                                                                                  :myyntipalvelu true
                                                                                                  :henkilotiedot "ei-sisalla"}}]
                                                                    (case id
                                                                      "public" base-response
                                                                      "secret1" (assoc-in base-response [:metadata :nakyvyys] "viranomainen")
                                                                      "secret2" (assoc-in base-response [:metadata :myyntipalvelu] false)
                                                                      "secret3" (meta-merge base-response {:metadata {:julkisuusluokka "salainen"
                                                                                                                      :security-period-end "9999-01-01T12:00:00.000Z"}})
                                                                      "secret4" (assoc-in base-response [:metadata :henkilotiedot] "sisaltaa")
                                                                      "secret5" (assoc-in base-response [:metadata :henkilotiedot] "sisaltaa-arkaluonteisia")
                                                                      "deleted" (assoc-in base-response [:metadata :deleted] "2018-03-26T20:44:10.885Z")
                                                                      "previously-secret"  (meta-merge base-response {:metadata {:julkisuusluokka "salainen"
                                                                                                                                 :security-period-end "2016-01-01T12:00:00.000Z"}}))))
                     #'onkalo.util.log-api-usage/log-api-usage         (fn [_ _ api-user _ _] (is (= api-user "public")))}
      #(do (doseq [id public-ids]
             (is (= (handler (-> (mock/request :get (str "/onkalo/public-api/documents/" id) {:organization "753-R"})
                                 (mock/header "Authorization" public-api-user)))
                    ok-response2)))
           (doseq [id secret-ids]
             (is (= (handler (-> (mock/request :get (str "/onkalo/public-api/documents/" id) {:organization "753-R"})
                                 (mock/header "Authorization" public-api-user)))
                    forbidden-response)))

           (let [id "deleted"]
             (is (= (handler (-> (mock/request :get (str "/onkalo/public-api/documents/" id) {:organization "753-R"})
                                 (mock/header "Authorization" public-api-user)))
                    not-found-response)))))))

(deftest read-only-user-can-read-documents
  (mock-elastic
    #(is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "753-R"})
                         (mock/header "Authorization" reader-api-user)))
            ok-response))))

(deftest read-only-user-cannot-upload-documents
  (doseq [user [reader-api-user docstore-user]]
    (is (= (handler (-> (mock/request :put "/onkalo/documents/gsdg60" {:organization "753-R"})
                        (mock/header "Authorization" user)))
           unauthorized-response))))

(deftest write-user-can-upload-documents
  (with-redefs-fn {#'onkalo.storage.document/upload-document (fn [& _] ok-response)}
    #(do (is (= (handler (-> (mock/request :put "/onkalo/documents/gsdg60")
                             (merge {:multipart-params {"metadata" "{\"organization\":\"753-R\"}"}})
                             (mock/header "Authorization" authorized-user)))
                ok-response))
         (is (= (handler (-> (mock/request :put "/onkalo/documents/gsdg60")
                             (merge {:multipart-params {"metadata" "{\"organization\":\"186-R\"}"}})
                             (mock/header "Authorization" specific-org-user)))
                ok-response)))))

(deftest multiple-organizations-can-be-used
  (with-redefs-fn {#'onkalo.metadata.elastic-api/find-documents (fn [_ organization _ _ _ _ _ _]
                                                                  (when (and (sequential? organization)
                                                                             (contains? (set organization) "186-R"))
                                                                    {:results ["foo"]}))}
    #(do
       (is (= (handler (-> (mock/request :get "/onkalo/public-api/documents/search" {:organization ["186-R" "753-R"]})
                           (mock/header "Authorization" reader-api-user)))
              ok-response))
       (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization ["186-R" "753-R"]})
                           (mock/header "Authorization" authorized-user)))
              ok-response))
       (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization ["186-R" "753-R"]})
                           (mock/header "Authorization" specific-org-user)))
              unauthorized-response))
       (is (= (handler (-> (mock/request :get "/onkalo/public-api/documents/search" {:organization ["186-R" "753-R" "999-R"]})
                           (mock/header "Authorization" reader-api-user)))
              unauthorized-response)))))

(deftest organization-is-required-in-search
  (is (= (handler (-> (mock/request :get "/onkalo/documents" {})
                      (mock/header "Authorization" authorized-user)))
         {:status 400
          :headers {}
          :body {:error "Missing organization"}})))

(deftest docstore-or-docterminal-users-cant-access-documents-api
  (doseq [user [docstore-user docterminal-user]]
    (is (= (handler (-> (mock/request :get "/onkalo/documents" {:organization "753-R"})
                        (mock/header "Authorization" user)))
           unauthorized-response))
    (is (= (handler (-> (mock/request :get "/onkalo/public-api/documents/search" {:organization "753-R"})
                        (mock/header "Authorization" user)))
           unauthorized-response))))
