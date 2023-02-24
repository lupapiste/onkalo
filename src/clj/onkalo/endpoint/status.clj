(ns onkalo.endpoint.status
  (:require [cheshire.core :as json]
            [compojure.core :refer [GET context]]
            [onkalo.boundary.elastic-document-api :as esd]
            [onkalo.routing :as routing]
            [ring.util.response :as resp]
            [taoensso.timbre :as timbre]))

(defn health-response [status body]
  {:status  status
   :headers {"Content-Type" "text/plain"}
   :body    body})

(defn status [build-info {:keys [elastic]}]
  (context routing/root []
    (GET "/status" []
      (-> (json/generate-string (or build-info {:build "development"}))
          resp/response
          (resp/content-type "application/json")))
    (GET "/health" []
      (try
        (let [{{:keys [value relation]} :total
               :keys                    [hits]} (-> (esd/search elastic
                                                                {:_source ["fileId" "organization" "bucket"]
                                                                 :from    0
                                                                 :size    1})
                                                    :body
                                                    :hits)
              sample-doc (-> hits first :_source)]
          (if (and (> value 0)
                   (every? string? ((juxt :fileId :organization :bucket) sample-doc)))
            (health-response 200 (str "Status OK. Total document count "
                                      (if (= "eq" relation)
                                        "is "
                                        "is at least ")
                                      value
                                      "."))
            (do (timbre/error "Elasticsearch test request produced unexpected document:" (pr-str sample-doc))
                (health-response 500 "Test request produced unexpected result, system is not functional."))))
        (catch Exception e
          (timbre/error e "Elasticsearch request failed in status check")
          (health-response 500 (str "Test request to search index failed: " (.getMessage e))))))))
