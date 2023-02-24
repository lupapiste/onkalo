(ns onkalo.maintenance.metadata-mass-update
  (:require [clojure.core.async :as async]
            [onkalo.endpoint.documents :refer [batchrun-user]]
            [onkalo.metadata.frontend-update :as fu]
            [qbits.spandex :as s]
            [taoensso.timbre :as timbre]))

(defn mass-update
  "Updates documents metadata for given organization and attachment types. Updates will match
   documents where existing metadata is different thant given metadata.
   For example:
   {myyntipalvelu :  true, nakyvyys : julkinen} will update all documents where one or both values
                                                are different. Sets given values.
   {myyntipalvelu : true} will update documents where myyntipalvelu is false and sets it to true,
                          don't depends on nakyvyys value.
   {nakyvyys : julkinen} updates documents where nakyvyys is not julkinen and sets it to julkinen,
                         don't depends on myyntipalvelu value."
  [{:keys [elastic] :as config} updates]
  (timbre/info "Searching index for documents to update")
  (async/go
    (try
      (let [{:keys [es-client write-alias]} elastic
            myyntipalvelu (if (some? (-> updates :meta-updates :myyntipalvelu))
                            [(not (-> updates :meta-updates :myyntipalvelu))]
                            ["true" "false"])
            nakyvyys      (map name
                               (disj #{:julkinen :viranomainen :asiakas-ja-viranomainen}
                                     (-> updates :meta-updates :nakyvyys)))
            match-count   (if (= (count (:meta-updates updates)) 1) 2 1)
            must          (->> (conj [{:term {:organization (:organization updates)}}]
                                        (when (:attachment-types updates)
                                          {:terms {:metadata.type (:attachment-types updates)}})
                                        (when (:building-ids updates)
                                          {:terms {:metadata.buildingIds (:building-ids updates)}})
                                        (when (:national-building-ids updates)
                                          {:terms {:metadata.nationalBuildingIds (:national-building-ids updates)}})
                                        (when (:addresses updates)
                                          {:terms {:metadata.address (:addresses updates)}})
                                        (when (:filenames updates)
                                          {:terms {:metadata.tiedostonimi (:filenames updates)}})
                                        (when (:tosFunction updates)
                                          {:term {:metadata.tosFunction.code (:tosFunction updates)}}))
                               (remove nil?))
            ch            (s/scroll-chan es-client
                                         {:url  [write-alias :_search]
                                          :body {:query {:bool {:must   must
                                                                :should [{:terms {:metadata.myyntipalvelu myyntipalvelu}}
                                                                         {:terms {:metadata.nakyvyys nakyvyys}}]
                                                                :minimum_should_match match-count}}}})
            updated       (atom 0)]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{document :_source} (-> page :body :hits :hits)]
              (fu/update-metadata config (:fileId document) (:meta-updates updates) batchrun-user (:organization updates))
              (timbre/info "Updating metadata for document " (:fileId document))
              (swap! updated inc))
            (recur)))
        (timbre/info "Updated" @updated "documents."))
      (catch Throwable t
        (timbre/error t "Error occurred during update documents.")))))
