(ns onkalo.component.gcs
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [onkalo.boundary.object-storage :as os]
            [taoensso.timbre :as timbre])
  (:import [com.google.auth.oauth2 GoogleCredentials ServiceAccountCredentials]
           [com.google.cloud.storage Blob
                                     Blob$BlobSourceOption
                                     BlobId
                                     BlobInfo
                                     BucketInfo
                                     Storage
                                     Storage$BlobSourceOption
                                     Storage$BlobTargetOption
                                     Storage$BucketGetOption
                                     Storage$BucketTargetOption
                                     StorageClass
                                     StorageException
                                     StorageOptions
                                     StorageOptions$Builder]
           [java.io ByteArrayOutputStream File IOException InputStream]
           [java.nio.channels Channels]
           [java.nio.file Files]))

(defn- make-credentials [service-account-file]
  (try
    (if service-account-file
      (with-open [is (io/input-stream service-account-file)]
        (ServiceAccountCredentials/fromStream is))
      (GoogleCredentials/getApplicationDefault))
    (catch IOException e
      (throw (Exception. (str (.getMessage e)
                              "You may also set gcs.service-account-file path/to/file.json in properties. "
                              "Storage will not be currently available."))))))

(defn- create-client [service-account-file project]
  (when-let [credentials (make-credentials service-account-file)]
    (let [storage (-> (StorageOptions/newBuilder)
                      ^StorageOptions$Builder (.setCredentials credentials)
                      ^StorageOptions$Builder (.setProjectId project)
                      (.build)
                      (.getService))]
      storage)))

(defonce existing-buckets (atom #{}))

(def bucket-lock (Object.))

(defn- bucket-exists? [storage bucket]
  (or (contains? @existing-buckets bucket)
      (try
        (when (.get storage bucket (into-array Storage$BucketGetOption []))
          (swap! existing-buckets conj bucket)
          true)
        (catch Exception e
          (timbre/error e "Could not check if GCP bucket" bucket "exists")
          (throw e)))))

(defn- create-bucket-if-not-exists [storage region storage-class bucket-name]
  (when-not (bucket-exists? storage bucket-name)
    (let [storage-class (or (some-> storage-class
                                    (StorageClass/valueOf))
                            StorageClass/STANDARD)]
      (timbre/infof "Creating Cloud Storage bucket %s to region %s with storage class %s" bucket-name region storage-class)
      (.create storage
               ^BucketInfo
               (-> (BucketInfo/newBuilder bucket-name)
                   (.setStorageClass storage-class)
                   (.setLocation region)
                   (.setVersioningEnabled true)
                   (.build))
               (into-array Storage$BucketTargetOption [])))))

(defn get-bucket
  [storage region storage-class bucket-prefix create-bucket-if-missing? logical-bucket]
  {:pre [(string? logical-bucket) (not (str/blank? logical-bucket))]}
  (let [bucket (str bucket-prefix "-" logical-bucket)]
    (when create-bucket-if-missing?
      (locking bucket-lock
        (create-bucket-if-not-exists storage region storage-class bucket)))
    bucket))

(defn- upload-file-or-is-or-bytes [^Storage storage bucket object-key {:keys [content-type content]}]
  (try

    (let [blob         (BlobId/of bucket object-key)
          ;; GCP API prefers to work with a byte array, so that it can be retried
          byte-content (cond
                         (instance? File content)
                         (Files/readAllBytes (.toPath content))

                         (instance? InputStream content)
                         (let [bos (ByteArrayOutputStream.)]
                           (with-open [is content]
                             (io/copy is bos))
                           (.toByteArray bos))

                         :else
                         content)]
      (.create storage
               (-> (BlobInfo/newBuilder blob)
                   (.setContentType content-type)
                   (.build))
               ^bytes byte-content
               (into-array Storage$BlobTargetOption []))
      (timbre/debug "Object" object-key "uploaded to GCS bucket" bucket)
      {:length (alength byte-content)})
    (catch Exception ex
      (let [msg (str "Error occurred when trying to add object" object-key "to GCS bucket" bucket)]
        (timbre/error ex msg)
        (throw (ex-info msg {:status 500
                             :body (.getMessage ex)
                             :headers {"Content-Type" "text/plain"}}))))))

(defn- get-object [^Storage storage bucket object-key]
  (let [blob-id (BlobId/of bucket object-key)]
    (try
      (if-let [blob ^Blob (.get storage blob-id)]
        {:content-fn   (fn [] (-> (.reader blob (into-array Blob$BlobSourceOption []))
                                  (Channels/newInputStream)))
         :content-type (.getContentType blob)}
        (let [msg (str "Tried to retrieve non-existing " object-key " from GCS bucket " bucket)]
          (timbre/warn msg)
          (throw (ex-info msg {:status  404
                               :body    (str object-key " not found")
                               :headers {"Content-Type" "text/plain"}}))))
      (catch StorageException ex
        (let [msg (str "Error occurred when trying to retrieve" object-key "from GCS bucket" bucket)]
          (timbre/error ex msg)
          (throw (ex-info msg {:status 500
                               :body (.getMessage ex)
                               :headers {"Content-Type" "text/plain"}})))))))

(defn- does-object-exist? [^Storage storage bucket object-key]
  (->> (BlobId/of bucket object-key)
       (.get storage)
       boolean))

(defn- blob-source-options
  ^"[Lcom.google.cloud.storage.Storage$BlobSourceOption;" [& options]
  (into-array Storage$BlobSourceOption (keep identity options)))

(defn- delete-object
  "https://github.com/googleapis/java-storage/blob/v2.22.3/samples/snippets/src/main/java/com/example/storage/object/DeleteObject.java"
  [^Storage storage bucket id]
  (timbre/infof "Deleting object %s from GCS bucket %s" id bucket)
  (if-let [blob (.get storage (BlobId/of bucket id))]
    (let [generation (.getGeneration blob)]
      (.delete storage bucket id (blob-source-options (when generation
                                                        (Storage$BlobSourceOption/generationMatch generation))))
      (timbre/debugf "Object %s deleted from GCS bucket %s" id bucket))
    (let [msg (format "Object %s not found in GCS bucket %s!" id bucket)]
      (timbre/error msg)
      (throw (ex-info msg {:status  404
                           :body    (format "%s not found" id)
                           :headers {"Content-Type" "text/plain"}})))))

(defrecord GCS [storage region storage-class bucket-prefix bucket-suffix create-bucket-if-missing?]
  component/Lifecycle
  (start [this]
    this)
  (stop [this]
    this)

  os/ObjectStorage
  (upload [this bucket id file-data]
    (upload-file-or-is-or-bytes storage (os/org-bucket-name this bucket) id file-data))
  (download [this bucket id]
    (get-object storage (os/org-bucket-name this bucket) id))
  (object-exists? [this bucket id]
    (does-object-exist? storage (os/org-bucket-name this bucket) id))
  (org-bucket-name [_ bucket]
    (get-bucket storage region storage-class bucket-prefix create-bucket-if-missing? bucket))
  (temp-bucket-name [_]
    (get-bucket storage region "STANDARD" bucket-prefix create-bucket-if-missing? (str "temp-" bucket-suffix)))
  (upload-temp-file [this id file-data]
    (upload-file-or-is-or-bytes storage (os/temp-bucket-name this) id file-data))
  (delete-temp-file [this id]
    (delete-object storage (os/temp-bucket-name this) id)))

(defn gcs-component [{:keys [service-account-file project region storage-class bucket-prefix bucket-suffix]}]
  (->GCS (create-client service-account-file project)
         region
         storage-class
         bucket-prefix
         bucket-suffix
         true))
