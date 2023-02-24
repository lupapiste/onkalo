(ns onkalo.endpoint.buildings-api
  "Endpoint(s) for updating document metadata for buildings"
  (:require [compojure.api.exception :as ex]
            [compojure.api.sweet :refer :all]
            [onkalo.endpoint.documents :refer [batchrun-user]]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.frontend-update :as fu]
            [onkalo.routing :as routing]
            [onkalo.util.auth :as auth]
            [onkalo.util.document-api-util :as dau]
            [ring.util.http-response :refer :all]
            [schema.core :as s]
            [schema-tools.core :as st]))

(def BuildingUpdate
  {:id s/Str
   :search   {:national-building-id s/Str}
   :metadata (st/optional-keys {:myyntipalvelu s/Bool
                                :julkisuusluokka s/Str
                                :nakyvyys s/Str
                                :salassapitoaika s/Int
                                :salassapitoperuste s/Str
                                :suojaustaso s/Str
                                :turvallisuusluokka s/Str
                                :kayttajaryhma s/Str
                                :kayttajaryhmakuvaus s/Str})})

(def HttpStatus s/Int)
(def FileId s/Str)

(def DocumentResult
  {HttpStatus [FileId]})

(def BuildingUpdateResult
  (merge BuildingUpdate
         {:results DocumentResult}))

(def UpdateBuildingsResponse
  {:organization s/Str
   :results [BuildingUpdateResult]})

(def publicity-secret-defaults
  {:salassapitoperuste "-"
   :salassapitoaika 1
   :suojaustaso "ei-luokiteltu"
   :turvallisuusluokka "ei-turvallisuusluokkaluokiteltu"
   :kayttajaryhma "viranomaisryhma"
   :kayttajaryhmakuvaus "muokkausoikeus"})

(defn fetch-doc-ids [elastic organization {:keys [search] :as building-update}]
  (let [search-from 0
        search-limit 5000
        publicity-param nil
        result-fields ["fileId"]
        sort false
        find-result (ea/find-documents elastic
                                       [organization]
                                       {:nationalBuildingIds (:national-building-id search)}
                                       search-from
                                       search-limit
                                       publicity-param
                                       result-fields
                                       sort)
        file-ids (->> find-result
                      :results
                      (map :fileId))]
    (assoc building-update :file-ids file-ids)))

(defn update-doc! [config organization metadata file-id]
  (let [update-result (fu/update-metadata config file-id metadata batchrun-user organization)]
    {file-id (or (:status update-result) 500)}))

(defn secret? [julkisuusluokka]
  (contains? #{"salainen" "osittain-salassapidettava"} julkisuusluokka))

(defn with-defaults [{:keys [julkisuusluokka] :as metadata}]
  (cond->>  metadata
    (secret? julkisuusluokka) (merge publicity-secret-defaults)))

(defn group-by-status
  "Turns {<file-id> <status>}
   into  {<status> [file-id]}"
  [docs-with-status]
  (->> docs-with-status
       (group-by val)
       (map (fn [[status entries]] [status (map first entries)]))
       (into {})))

(defn update-buildings! [organization building-updates {:keys [elastic] :as config}]
  (let [buildings-with-docs (map (partial fetch-doc-ids elastic organization) building-updates)
        update-results      (for [{:keys [file-ids] :as building-with-docs} buildings-with-docs
                                  :let [metadata       (-> building-with-docs :metadata with-defaults)
                                        docs-by-status (->> file-ids
                                                            (pmap (partial update-doc! config organization metadata))
                                                            (apply merge)
                                                            (group-by-status))]]
                              (-> building-with-docs
                                  (dissoc :file-ids)
                                  (assoc :results docs-by-status)))]
    {:organization organization
     :results      update-results}))

(defn status-for [building-update-results]
  (let [statuses (set (mapcat (comp keys :results) building-update-results))]
    (cond
      (empty? statuses)        200 ;; No documents found to be updated
      (contains? statuses 200) 200 ;; Has at least one succesfull document update.
      (= 1 (count statuses))   (first statuses)
      :else                    500)))

(defn buildings-api [config]
  (api
    {:swagger
     {:ui   (str routing/api-v2-root "/buildings/api-docs")
      :spec (str routing/api-v2-root "/buildings/api-docs/swagger.json")
      :data {:info                {:title       "Onkalo API for building documents metadata"
                                   :version     "2.0.0"
                                   :description "This API provides endpoint(s) for document
                                   updating metadata for (all) documents for a building
                                   identified by the national building id (vtjprt)."}
             :tags                [{:name        "buildings"
                                    :description "buildings endpoints"}]
             :securityDefinitions {:login {:type "basic"}}}}
     :exceptions {:handlers {::ex/response-validation (dau/with-logging ex/response-validation-handler :error)}}}

    (context (str routing/api-v2-root "/buildings") []
      :tags ["buildings"]
      :middleware [(auth/wrap-lupapiste-auth config)]

      (POST "/update-buildings" {}
            :body-params [organization :- s/Str
                          updates :- [BuildingUpdate]]
        :return UpdateBuildingsResponse
        :summary "Update metadata for given buildings"
        :description "Update metadata for given buildings"

        (do
          (let [res (update-buildings! organization updates config)
                status (status-for (:results res))]
            {:body res
             :status status}))))))
