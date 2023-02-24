(ns onkalo.maintenance.elastic-id-fixer
  (:require [taoensso.timbre :as timbre]
            [qbits.spandex :as s]
            [clojure.core.async :as async]))

(defn fix-duplicate-documents [{{:keys [es-client write-alias]} :elastic} {:keys [organization]}]
  (timbre/info "Checking index for duplicate documents")
  (async/go
    (try
      (let [ch (s/scroll-chan es-client
                              {:url  [write-alias :_search]
                               :body {:query (if organization
                                               {:term {:organization organization}}
                                               {:match_all {}})}})
            storings (atom 0)
            removals (atom 0)]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{document :_source es-id :_id} (-> page :body :hits :hits)
                    :let [file-id (:fileId document)
                          bucket (:bucket document)
                          correct-es-id (str bucket "-" file-id)]
                    :when (and file-id
                               (not= correct-es-id es-id))]
              (let [duplicates (-> (s/request es-client {:url    [write-alias :_search]
                                                         :method :get
                                                         :body   {:query {:term {:fileId file-id}}}})
                                   :body
                                   :hits
                                   :hits)]
                (let [most-recent (->> duplicates
                                       (sort-by #(-> % :_source :modified))
                                       last
                                       :_source)
                      ids-to-remove (->> duplicates
                                         (map :_id)
                                         (filter #(not= % correct-es-id)))]
                  ; Put the most recently modified version in the index with the correct id
                  (timbre/info "Storing the latest from" (count duplicates) "duplicates as id" correct-es-id)
                  (s/request es-client {:url    [write-alias :_doc correct-es-id]
                                        :method :put
                                        :body   most-recent})
                  (swap! storings inc)
                  ; Remove the other versions
                  (timbre/info "Removing duplicates:" (vec ids-to-remove))
                  (doseq [id-to-remove ids-to-remove]
                    (s/request es-client {:url    [write-alias :_doc id-to-remove]
                                          :method :delete})
                    (swap! removals inc)))
                ; Refresh index
                (s/request es-client {:url    [write-alias :_refresh]
                                      :method :post})))
            (recur)))
        (timbre/info "Duplicates fixed with" @storings "documents updated and" @removals "documents removed."))
      (catch Throwable t
        (timbre/error t "Error occurred during duplicate fixing")))))
