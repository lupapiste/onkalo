(ns onkalo.maintenance.contents-text-fixer
  (:require [taoensso.timbre :as timbre]
            [qbits.spandex :as s]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [onkalo.boundary.elastic-document-api :as esd]))

(defn- remove-extra-text [original-text]
  (let [variations ["Dokumentin tyypit:-"
                    "Dokumentin tyypit: -"
                    "Dokumentin tyypit:"]]
    (->> (reduce #(str/replace %1 %2 "")
                 original-text
                 variations)
         str/trim)))

(defn remove-extra-text-for-186-r [{{:keys [es-client write-alias] :as elastic} :elastic} _]
  (timbre/info "Checking index for documents with silly contents text")
  (async/go
    (try
      (let [ch (s/scroll-chan es-client
                              {:url  [write-alias :_search]
                               :body {:query {:bool {:must [{:term {:organization "186-R"}}
                                                            {:wildcard {:metadata.contents "*Dokumentin tyypit*"}}]}}}})
            updates (atom 0)]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{es-id :_id} (-> page :body :hits :hits)]
              ; Re-fetch and check to make sure document should still be updated
              (when-let [{:keys [metadata] :as document} (-> (esd/get-by-id elastic es-id nil) :body :_source)]
                (when (str/includes? (:contents metadata) "Dokumentin tyypit")
                  (timbre/info "Fixing text for document id" es-id)
                  (->> (update-in document [:metadata :contents] remove-extra-text)
                       (esd/put elastic es-id))
                  (swap! updates inc))))
            (recur)))
        (timbre/info "Text fixed in" @updates "documents."))
      (catch Throwable t
        (timbre/error t "Error occurred during document contents fixing")))))
