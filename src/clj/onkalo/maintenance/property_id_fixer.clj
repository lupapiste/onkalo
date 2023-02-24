(ns onkalo.maintenance.property-id-fixer
  (:require [taoensso.timbre :as timbre]
            [onkalo.boundary.elastic-document-api :as esd]
            [clojure.string :as str]
            [clojure.core.async :as async]
            [qbits.spandex :as s]))

(def property-id-pattern
  "Regex for property id human readable format"
  #"^(?:(\d{1,3})-)?(\d{1,3})-(\d{1,4})-(\d{1,4})$")

(defn fix-property-id [current-property-id municipality-number]
  (if-let [property-id-parts (->> current-property-id
                                  str/trim
                                  (re-matches property-id-pattern)
                                  rest
                                  seq)]
    (let [integers (->> property-id-parts
                        (remove nil?)
                        (map #(Integer/parseInt % 10)))]
      (if (= (count integers) 3)
        (str municipality-number (apply format "%03d%04d%04d" integers))
        (apply format "%03d%03d%04d%04d" integers)))
    (do (timbre/warn "Unrecognized property id" current-property-id)
        current-property-id)))

(defn reformat-property-ids [{{:keys [es-client write-alias] :as elastic} :elastic} _]
  (timbre/info "Checking index for documents with silly contents text")
  (async/go
    (try
      (let [ch      (s/scroll-chan es-client
                                   {:url  [write-alias :_search]
                                    :body {:query {:wildcard {:metadata.propertyId "*-*"}}}})
            updates (atom 0)]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{es-id :_id} (-> page :body :hits :hits)]
              ; Re-fetch and check to make sure document should still be updated
              (when-let [{:keys [metadata] :as document} (-> (esd/get-by-id elastic es-id nil) :body :_source)]
                (when (str/includes? (:propertyId metadata) "-")
                  (timbre/info "Fixing property id for document id" es-id)
                  (->> (update-in document [:metadata :propertyId] fix-property-id (:municipality metadata))
                       (esd/put elastic es-id))
                  (swap! updates inc))))
            (recur)))
        (timbre/info "Property id fixed in" @updates "documents."))
      (catch Throwable t
        (timbre/error t "Error occurred during document contents fixing")))))
