(ns onkalo.endpoint.maintenance
  (:require [compojure.api.sweet :refer :all]
            [compojure.api.exception :as ex]
            [onkalo.util.document-api-util :as dau]
            [onkalo.maintenance.elastic-id-fixer :as elastic-id-fixer]
            [onkalo.maintenance.contents-text-fixer :as contents-fixer]
            [onkalo.maintenance.continue-migration :as continue-migration]
            [onkalo.maintenance.metadata-mass-update :as mass-updater]
            [onkalo.maintenance.property-id-fixer :as property-id-fixer]
            [onkalo.maintenance.move-files-to-gcs :as move-files-to-gcs]
            [onkalo.maintenance.regenerate-previews :as regenerate-previews]
            [schema.core :as s]))

(def maintenance-jobs
  {"fix-duplicate-documents"     elastic-id-fixer/fix-duplicate-documents
   "fix-contents-text-for-186-r" contents-fixer/remove-extra-text-for-186-r
   "fix-property-ids"            property-id-fixer/reformat-property-ids
   "continue-migration"          continue-migration/continue-migration
   "move-files-to-gcs"           move-files-to-gcs/move-files
   "regenerate-previews"         regenerate-previews/generate-previews-for-all-documents})

(s/defschema Jobs (apply s/enum (keys maintenance-jobs)))

(s/defschema Updates
  {:organization                           s/Str
   (s/optional-key :attachment-types)      [s/Str]
   (s/optional-key :building-ids)          [s/Str]
   (s/optional-key :national-building-ids) [s/Str]
   (s/optional-key :addresses)             [s/Str]
   (s/optional-key :filenames)             [s/Str]
   :meta-updates                           {(s/optional-key :myyntipalvelu) s/Bool
                                            (s/optional-key :nakyvyys)      (s/enum :julkinen
                                                                                    :viranomainen
                                                                                    :asiakas-ja-viranomainen)}
   (s/optional-key :tosFunction)           s/Str})

(defn maintenance-api [system]
  (api
    {:exceptions {:handlers {::ex/response-validation (dau/with-logging ex/response-validation-handler :error)}}}
    (context "/internal" []
      (POST "/run-maintenance/:job-id" {body-params :body-params}
        :coercion nil
        :path-params [job-id :- Jobs]
        :summary "Runs the specified maintenance job."
        (let [job-fn (get maintenance-jobs job-id)]
          (try
            (job-fn system body-params)
            {:status 200
             :body   (str "Job " job-id " now running. Check logs to see progress.")}
            (catch Exception e
              {:status 400
               :body   (.getMessage e)}))))
      (POST "/mass-update" []
        :body [updates Updates]
        (mass-updater/mass-update system updates)
        {:status 200
         :body   (str "Mass update onkalo metadata is now running. Check log to see results.")}))))
