(ns onkalo.boundary.object-storage
  (:require [clojure.string :as str]))

(defprotocol ObjectStorage
  (upload [client organization id file-data])
  (upload-preview [client organization id file-data])
  (download [client organization id])
  (download-preview [client organization id])
  (object-exists? [client organization id])
  (org-bucket-name [client organization preview?]))

(def preview-suffix "-preview")

(defn ^String org-bucket [organization bucket-suffix]
  (str "arkisto-" (str/lower-case organization) "-" bucket-suffix))

(defn org-preview-bucket [organization bucket-suffix]
  (org-bucket organization (str bucket-suffix preview-suffix)))

(defn storage-from-es-doc [{:keys [gcs]} es-document]
  (if (= (:storage es-document) "gcs")
    gcs
    (throw (Exception. (str "ES document  " (:id es-document)
                            " has unsupported storage "
                            (:storage es-document))))))

(defn default-storage-id [config-map]
  (-> config-map :storage :default-storage-id))

(defn default-storage [config-map]
  (get config-map (default-storage-id config-map)))
