(ns test-util.test-storage
  "Mock GCS component for tests, which stores files in memory."
  (:require [onkalo.component.gcs :refer [->GCS]])
  (:import [com.google.cloud.storage Storage BlobId]
           [com.google.cloud.storage.contrib.nio.testing LocalStorageHelper]))

(set! *warn-on-reflection* true)

(defn in-memory-storage
  "Creates an in-memory storage implementation for tests."
  [{:keys [bucket-prefix bucket-suffix]}]
  (->GCS
    (.getService (LocalStorageHelper/customOptions false))
    nil
    nil
    bucket-prefix
    bucket-suffix
    ;; LocalStorageHelper does not support creating buckets,
    ;; so instruct the GCS component to assume buckets always
    ;; exist
    false))

(defn object-exists?
  [^Storage storage bucket id]
  (->> (BlobId/of bucket id)
       (.get storage)
       boolean))
