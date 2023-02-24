(ns onkalo.maintenance.regenerate-previews
  (:require [qbits.spandex :as s]
            [taoensso.timbre :as timbre]
            [clojure.core.async :as async]
            [onkalo.boundary.preview :as preview-boundary]))

(defn generate-previews-for-all-documents [{{:keys [es-client read-alias]} :elastic :keys [pubsub gcs]} _]
  (let [counter (atom 0)
        ch      (s/scroll-chan es-client
                               {:url  [read-alias :_search]
                                :body {:query   {:match_all {}}
                                       :size    1000
                                       :_source ["fileId" "organization"]}
                                :ttl  "5m"})]
    (timbre/info "Sending preview generation messages for all documents")
    (async/go
      (try
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{{:keys [organization fileId]} :_source} (-> page :body :hits :hits)]
              (preview-boundary/generate-preview pubsub gcs organization fileId)
              (swap! counter inc))
            (timbre/info "Preview generation messages sent:" @counter)
            (recur)))
        (timbre/info "All preview generation messages sent")
        (catch Throwable t
          (timbre/error t "Error occurred during preview regeneration"))))))
