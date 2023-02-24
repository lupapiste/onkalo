(ns onkalo.maintenance.continue-migration
  (:require [taoensso.timbre :as timbre]
            [qbits.spandex :as s]
            [clojure.core.async :as async]
            [onkalo.component.elastic :as elastic-component]
            [onkalo.metadata.elastic-migrations :as migrations]))

(defn continue-migration [{{:keys [es-client read-alias current-index]} :elastic} {:keys [source-index]}]
  (when-not (string? source-index)
    (throw (IllegalArgumentException. "source-index must be set")))
  (timbre/info "Continuing migration by copying missing documents from" source-index "to" current-index)
  (async/go
    (try
      (let [ch     (s/scroll-chan es-client
                                  {:url  [source-index :_search]
                                   :body {:query {:match_all {}}}})
            copied (atom 0)]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{document :_source es-id :_id} (-> page :body :hits :hits)
                    :let [exists-in-new? (try (s/request es-client {:url    [current-index :_doc es-id]
                                                                    :method :head})
                                              (catch Throwable t
                                                (if (= 404 (:status (ex-data t)))
                                                  false
                                                  (throw t))))]
                    :when (not exists-in-new?)]
              (let [updated-doc (migrations/migrate-document elastic-component/index-version document)]
                (elastic-component/put! es-client current-index es-id updated-doc)
                (timbre/info "Document copied to current index:" es-id)
                (swap! copied inc)))
            (recur)))
        ; Refresh index
        (elastic-component/refresh es-client current-index)
        (elastic-component/update-settings! es-client current-index {:refresh_interval "1s"})
        ; Set read-alias to new
        (elastic-component/update-aliases! es-client [{:remove {:index source-index :alias read-alias}}
                                                      {:add {:index current-index :alias read-alias}}])
        (timbre/info @copied "missing documents were copied from" source-index "to" current-index))
      (catch Throwable t
        (timbre/error t "Error occurred during continue-migration")))))
