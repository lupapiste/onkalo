(ns onkalo.boundary.object-storage
  (:require [clojure.string :as str]))

(defprotocol ObjectStorage
  (upload [client logical-bucket id file-data])
  (download [client logical-bucket id])
  (object-exists? [client logical-bucket id])
  (org-bucket-name [client bucket]
    "Given a logical bucket, returns the physical bucket name in storage")
  (temp-bucket-name [client]
    "Returns the physical name of the global shared bucket for temporary files")
  (upload-temp-file [client id file-data]
    "Upload a file to the temp bucket")
  (delete-temp-file [client id]
    "Delete object from the physical temporary file bucket.
     Throws if object does not exist."))

(def preview-suffix "-preview")

(defn org-bucket
  "Return logical bucket name for organization"
  ^String [organization bucket-suffix]
  (str "arkisto-" (str/lower-case organization) "-" bucket-suffix))

(defn org-bucket->org-preview-bucket
  [bucket]
  (str bucket preview-suffix))

(defn org-preview-bucket
  "Return logical preview bucket name for organization"
  [organization bucket-suffix]
  (-> (org-bucket organization bucket-suffix)
      org-bucket->org-preview-bucket))

(defn default-storage-id [config-map]
  (-> config-map :storage :default-storage-id))

(defn default-storage [config-map]
  (get config-map (default-storage-id config-map)))
