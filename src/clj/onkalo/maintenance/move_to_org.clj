(ns onkalo.maintenance.move-to-org
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [onkalo.boundary.elastic-document-api :as esd]
            [onkalo.boundary.object-storage :as os]
            [onkalo.component.elastic :as elastic-component]
            [onkalo.metadata.elastic-api :as ea]
            [qbits.spandex :as s]
            [schema-tools.core :as st]
            [schema.core :as sc]
            [taoensso.timbre :as timbre])
  (:import [java.util Date]))

(def page->hits (comp :hits :hits :body))

(def hit->file-id
  "The file ID of a document."
  (comp :fileId :_source))

(defn- page->id+file-id
  [page]
  (->> page
       page->hits
       (map (juxt :_id hit->file-id))
       (into {})))

(defn- page->file-id+id
  [page]
  (->> page
       page->hits
       (map (juxt hit->file-id :_id))
       (into {})))

(defn- check-for-conflicts
  "Check if source organization page refers to any `fileId`s in the destination organization.
  Returns a set of conflicting `fileId`s."
  [es-client es-index destination-organization src-page]
  (let [id+file-id     (page->id+file-id src-page)
        ;; Note that we only check `fileId`s! We assume that assigning the org prefix to conflicting
        ;; documents is sufficient to guarantee unique public id for all eternity
        dest-page      (when (seq id+file-id)
                         (s/request es-client
                                    {:url  [es-index :_search]
                                     :body {:query {:bool {:must [{:term {:organization destination-organization}}
                                                                  {:terms {:fileId (vals id+file-id)}}]}}}}))
        conflict-count (-> dest-page page->hits count)]
    (if (zero? conflict-count)
      #{}
      (let [file-id+id (page->file-id+id dest-page)
            conflicts  (->> id+file-id
                            (keep (fn [[id fileId]]
                                    (when-let [their-id (get file-id+id fileId)]
                                      [id their-id fileId])))
                            (into []))]
        (doseq [[our-id their-id fileId] conflicts]
          (timbre/warnf "conflicts: both %s and %s have fileId %s" our-id their-id fileId))
        (->> conflicts
             (map (fn [[_ _ fileId]] fileId))
             set)))))

(defn es-id->public-id
  [org bucket-suffix es-id]
  (let [prefix     (str (os/org-bucket org bucket-suffix) "-")
        prefix-len (count prefix)]
    (when-not (and (string? es-id)
                   (str/starts-with? es-id prefix)
                   (< prefix-len (count es-id)))
      (throw (ex-info "Failed to parse public-id" {:es-id  es-id
                                                   :prefix prefix})))
    (subs es-id (count prefix))))

(defn- now []
  (Date.))

(defn- record-in-history
  [document old-org new-org old-id new-id]
  (let [modified (now)]
    (update-in document [:metadata :history] (fnil into []) [{:modified modified
                                                              :field    "organization"
                                                              :old-val  old-org
                                                              :new-val  new-org}
                                                             {:modified modified
                                                              :field    "id"
                                                              :old-val  old-id
                                                              :new-val  new-id}])))

(defn- generate-id
  []
  (str (random-uuid)))

(sc/defschema Config
  (st/open-schema
    {:elastic (st/open-schema
                {:es-client   sc/Any
                 :write-alias sc/Str})
     :storage (st/open-schema
                {:bucket-suffix sc/Str})}))

(def NotBlankStr (sc/constrained sc/Str (complement str/blank?) 'not-blank))

(sc/defschema Args
  {:source-organization       NotBlankStr
   :destination-organization  NotBlankStr
   (sc/optional-key :dry-run) sc/Bool})

(sc/defn move-to-org
  "Move documents from one organization to another but does not move the corresponding files in GCS.
  Returns a channel, which will produce a map describing the results once finished."
  {:always-validate true}
  [{{:keys [es-client write-alias]} :elastic
    {:keys [bucket-suffix]}         :storage
    :keys                           [elastic]} :- Config
   {:keys [source-organization
           destination-organization
           dry-run]} :- Args]
  (timbre/infof "Moving documents from %s to %s %s"
                source-organization destination-organization (if dry-run "(dry-run)" ""))
  (async/go
    (let [*conflicted   (atom {})
          *assigned-cnt (atom 0)]
      (try
        (let [ch (s/scroll-chan es-client
                                {:url  [write-alias :_search]
                                 :body {:query {:term {:organization source-organization}}}})]
          (loop []
            (when-let [page (async/<! ch)]
              (when (zero? (-> page page->hits count))
                (timbre/warn "Found no documents in" source-organization))
              (let [conflicting (check-for-conflicts es-client write-alias destination-organization page)]
                (doseq [{document :_source old-id :_id} (page->hits page)]
                  (let [;; We cannot use `fileId` directly, as this document might have been previously
                        ;; moved from another org and thus might have a different public id. In such case we
                        ;; don't want to accidentally forget about it.
                        current-public-id (es-id->public-id source-organization bucket-suffix old-id)
                        conflict?         (contains? conflicting current-public-id)
                        ;; Replace the existing ID with a new one if a conflict occurs
                        new-public-id     (if conflict? (generate-id) current-public-id)
                        ;; If a conflict occurs, the fileId within the ES ID will differ
                        ;; from the one within the document!
                        new-id            (ea/es-id (os/org-bucket destination-organization bucket-suffix) new-public-id)
                        ;; We do NOT alter the bucket or fileId fields!
                        document          (-> document
                                              (assoc :organization destination-organization)
                                              (record-in-history source-organization destination-organization old-id new-id))]
                    (when conflict?
                      (timbre/logf :warn "Replacing public id %s with %s" current-public-id new-public-id)
                      (swap! *conflicted assoc current-public-id new-public-id))
                    (timbre/logf :info "Replacing %s with %s" old-id new-id)
                    (when-not dry-run
                      (esd/put elastic new-id document)
                      (esd/delete elastic old-id)))
                  (swap! *assigned-cnt inc)))
              (recur)))
          (when-not dry-run
            (elastic-component/refresh es-client write-alias))
          (timbre/infof "Assigned %d documents with %d conflicts"
                        @*assigned-cnt
                        (count @*conflicted)))
        (catch Throwable t
          (timbre/error t "Error occurred when assigning documents")))
      {:assigned-cnt @*assigned-cnt
       :conflicted   @*conflicted})))


(comment
  (move-to-org
    (deref (requiring-resolve 'reloaded.repl/system))
    {:source-organization      "753-R"
     :destination-organization "123-R"
     :dry-run                  true})
  ;; Or alternatively e.g.
  ;; curl -d '{"source-organization": "753-R", "destination-organization": "123-R", "dry-run":true}' \
  ;; -H "Content-Type: application/json" -X POST http://localhost:8012/internal/run-maintenance/move-to-org
  )
