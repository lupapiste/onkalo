(ns onkalo.maintenance.move-files-to-gcs
  (:require [taoensso.timbre :as timbre]
            [clojure.core.async :as async]
            [qbits.spandex :as s]
            [onkalo.boundary.elastic-document-api :as esd]
            [onkalo.component.gcs :as gcs]
            [onkalo.boundary.object-storage :as os]
            [lupapiste-commons.threads :as threads])
  (:import [com.google.cloud.storage BlobId Storage$CopyRequest]
           [java.io InputStream]
           [org.apache.commons.io IOUtils]))

(defonce thread-pool (threads/threadpool 32 "move-to-gcs-worker"))

(defn- copy-file-from-ceph [{:keys [s3 gcs]} organization id preview?]
  (let [download-fn (if preview? os/download-preview os/download)
        upload-fn   (if preview? os/upload-preview os/upload)
        {:keys [content-fn content-type]} (download-fn s3 organization id)]
    (timbre/info (str "Copying " organization " / " id (when preview? " (preview) ") "from Ceph to GCS"))
    (upload-fn gcs organization id {:content      (with-open [is (content-fn)]
                                                    (IOUtils/toByteArray ^InputStream is))
                                    :content-type content-type})))

(defn- move-file-to-operative-gcs [{{:keys [storage region storage-class bucket-prefix bucket-suffix]} :gcs
                                    :keys                                                              [backup-gcs]
                                    :as                                                                config}
                                   bucket
                                   id
                                   organization]
  (let [backup-blob-id (BlobId/of (:bucket backup-gcs) (str bucket "/" id))
        target-bucket  (gcs/get-bucket storage region storage-class organization bucket-prefix bucket-suffix)
        target-blob-id (BlobId/of target-bucket id)]
    (if (.get storage backup-blob-id) ; Object is in GCS backup bucket
      (-> (.copy storage (-> (Storage$CopyRequest/newBuilder)
                             (.setSource backup-blob-id)
                             (.setTarget target-blob-id)
                             (.build)))
          (.getResult))
      ;; Missing from backup
      (copy-file-from-ceph config organization id false))
    (try
      ;; Preview is never in backup
      (copy-file-from-ceph config organization id true)
      (catch Exception _
        ;; Missing preview is not critical
        (timbre/warn "Could not copy preview image for" id "from Ceph")))))

(defn move-files [{{:keys [es-client write-alias] :as elastic} :elastic :as config} _]
  (timbre/info "Moving files from Ceph / GCS backup to GCS active storage")
  (try
    (let [query     {:query {:bool {:should               [{:term {:storage "s3"}}
                                                           {:bool {:must_not {:exists {:field "storage"}}}}]
                                    :minimum_should_match 1}}}
          doc-count (-> (s/request es-client {:url    [write-alias :_count]
                                              :method :get
                                              :body   query})
                        :body
                        :count)
          updates   (atom 0)
          ch        (s/scroll-chan es-client {:url  [write-alias :_search]
                                              :body (assoc query
                                                           :size 1000
                                                           :_source false)
                                              :ttl  "5m"})]
      (timbre/info "Total documents to move:" doc-count)
      (async/go
        (->> (loop [thread-list []]
               (if-let [page (async/<! ch)]
                 (->> page
                      :body
                      :hits
                      :hits
                      (mapv (fn [{es-id :_id}]
                              (threads/submit
                                thread-pool
                                (try
                                  (when-let [{:keys [storage
                                                     organization
                                                     bucket
                                                     fileId]} (-> (esd/get-by-id elastic
                                                                                 es-id
                                                                                 ["storage" "bucket" "fileId" "organization"])
                                                                  :body
                                                                  :_source)]
                                    (when (or (nil? storage) (= storage "s3"))
                                      (move-file-to-operative-gcs config bucket fileId organization)
                                      (s/request es-client {:url    [write-alias :_update es-id]
                                                            :method :post
                                                            :body   {:doc {:storage "gcs"}}})
                                      (timbre/info "Moved document id" es-id "[" (swap! updates inc) "/" doc-count "]")))
                                  (catch Exception e
                                    (timbre/error e "Failed to move" es-id "to GCS."))))))
                      (concat thread-list)
                      (recur))
                 thread-list))
             threads/wait-for-threads)
        (timbre/info "Total documents moved:" @updates "/" doc-count)))
    (catch Throwable t
      (timbre/error t "Error occurred when trying to move documents to GCS"))))
