(ns onkalo.endpoint.reporting
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clojure.string :as str]
            [compojure.api.core :as compojure-api-core]
            [compojure.api.exception :as ex]
            [compojure.api.sweet :as compojure-api :refer [GET routes]]
            [onkalo.metadata.elastic-aggregate :as esa]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.elastic-scroll :as escr]
            [onkalo.routing :as routing]
            [onkalo.util.auth :as auth]
            [onkalo.util.document-api-util :as dau]
            [ring.middleware.session :as ring-session]))

(def header-row (str (->> ["Organization" "Documents" "Lupapiste applications" "Municipal permit IDs" "Earliest" "Latest"]
                          (str/join ";"))
                     "\n"))

(defn reporting-api [{:keys [elastic session-store] :as config}]
  (-> (compojure-api/api
        {:exceptions {:handlers {::ex/response-validation (dau/with-logging ex/response-validation-handler :error)}}}
        (compojure-api/context (str routing/root "/reporting") []
          (GET "/totals.csv" []
            :summary "Returns a report containing total document numbers"
            (let [*docs     (future (esa/documents-per-organization elastic))
                  *apps     (future (esa/lupapiste-apps-per-organization elastic))
                  *municips (future (esa/municipal-ids-per-organization elastic))
                  filename  (str (tf/unparse (:basic-date tf/formatters) (t/now)) "-onkalo-totals.csv")
                  csv       (->> @*docs
                                 (map (fn [{:keys [organization count earliest latest]}]
                                        (str (->> [organization
                                                   count
                                                   (get @*apps organization)
                                                   (get @*municips organization)
                                                   earliest
                                                   latest]
                                                  (str/join ";"))
                                             "\n")))
                                 (sort)
                                 (cons header-row))]
              {:status  200
               :body    csv
               :headers {"Content-Type"         "text/csv"
                         "Content-Disposition", (str "attachment;filename=" filename)}}))

          (->> (GET "/applications" [organization start-date end-date]
                 :summary "Returns a list of the organization's applications added to Onkalo in the given time window"
                 {:status 200
                  :body   (ea/find-all-applications elastic organization start-date end-date)})
               (compojure-api-core/route-middleware [(auth/wrap-lupapiste-auth config)]))

          (->> (routes
                 (GET "/imported_documents.csv" [organization]
                   :summary "Returns a list of the organization's documents imported to Onkalo outside Lupapiste"
                   {:status  200
                    :headers {"Content-Type"         "text/csv"
                              "Content-Disposition", (str "attachment;filename=onkalo-imported-" organization ".csv")}
                    :body    (escr/find-imported-documents-for-organization config organization)})

                 (GET "/all_documents.csv" [organization]
                   :summary "Returns a list of the organization's documents"
                   {:status  200
                    :headers {"Content-Type"         "text/csv"
                              "Content-Disposition", (str "attachment;filename=onkalo-all-" organization ".csv")}
                    :body    (escr/find-all-documents-for-organization config organization)}))
               (compojure-api-core/route-middleware [auth/wrap-session-user-auth]))))
      (ring-session/wrap-session {:store session-store})))
