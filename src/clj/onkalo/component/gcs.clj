(ns onkalo.component.gcs
  (:require [com.stuartsierra.component :as component]
            [onkalo.boundary.object-storage :as os]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre])
  (:import [com.google.auth.oauth2 ServiceAccountCredentials GoogleCredentials]
           [com.google.cloud.storage StorageOptions StorageOptions$Builder StorageClass BlobId Storage
                                     Storage$BucketGetOption BucketInfo Storage$BucketTargetOption BlobInfo
                                     Storage$BlobTargetOption Blob Blob$BlobSourceOption StorageException]
           [java.io IOException InputStream File ByteArrayOutputStream]
           [java.nio.file Files]
           [java.nio.channels Channels]))

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
  ([storage region storage-class organization bucket-prefix bucket-suffix]
   (get-bucket storage region storage-class organization bucket-prefix bucket-suffix false))
  ([storage region storage-class organization bucket-prefix bucket-suffix preview?]
   (let [bucket-fn (if preview? os/org-preview-bucket os/org-bucket)
         bucket    (str bucket-prefix "-" (bucket-fn organization bucket-suffix))]
     (locking bucket-lock
       (create-bucket-if-not-exists storage region storage-class bucket))
     bucket)))

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

(defrecord GCS [storage region storage-class bucket-prefix bucket-suffix]
  component/Lifecycle
  (start [this]
    this)
  (stop [this]
    this)

  os/ObjectStorage
  (upload [_ organization id file-data]
    (upload-file-or-is-or-bytes storage
                                (get-bucket storage region storage-class organization bucket-prefix bucket-suffix)
                                id
                                file-data))
  (upload-preview [_ organization id file-data]
    (upload-file-or-is-or-bytes storage
                                (get-bucket storage region storage-class organization bucket-prefix bucket-suffix true)
                                id
                                file-data))
  (download [_ organization id]
    (get-object storage
                (get-bucket storage region storage-class organization bucket-prefix bucket-suffix)
                id))
  (download-preview [_ organization id]
    (get-object storage
                (get-bucket storage region storage-class organization bucket-prefix bucket-suffix true)
                id))
  (object-exists? [_ organization id]
    (does-object-exist? storage
                        (get-bucket storage region storage-class organization bucket-prefix bucket-suffix)
                        id))
  (org-bucket-name [_ organization preview?]
    (get-bucket storage region storage-class organization bucket-prefix bucket-suffix preview?)))

(defn gcs-component [{:keys [service-account-file project region storage-class bucket-prefix bucket-suffix]}]
  (->GCS (create-client service-account-file project)
         region
         storage-class
         bucket-prefix
         bucket-suffix))
