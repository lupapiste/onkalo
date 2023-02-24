(ns onkalo.storage.document
  (:require [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [lupapiste-commons.schema-utils :as schema-utils]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.boundary.object-storage :as os]
            [onkalo.boundary.preview :as preview-boundary]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.metadata-util :as md-util]
            [onkalo.metadata.tos-access :as tos-access]
            [onkalo.metadata.xmp-processor :as xmp]
            [ring.util.response :refer [response status]]
            [schema.core :as s]
            [schema.utils :as su]
            [search-commons.geo-conversion :as geo]
            [taoensso.timbre :as timbre])
  (:import [clojure.lang ExceptionInfo]
           [com.fasterxml.jackson.core JsonParseException]
           [com.lowagie.text.exceptions InvalidPdfException]
           [com.sun.org.apache.xerces.internal.impl.io MalformedByteSequenceException]
           [java.io File]
           [java.text ParseException]
           [java.util Date]
           [org.apache.commons.imaging ImageReadException]
           [org.xml.sax SAXParseException]
           [schema.utils ValidationError]))

(json-gen/add-encoder ValidationError
                      (fn [c jsonGenerator]
                        (.writeString jsonGenerator (str (su/validation-error-explain c)))))

(defn- validate-special-dates [{:keys [julkisuusluokka] {:keys [arkistointi]} :sailytysaika :as metadata}]
  (-> (cond-> {s/Keyword s/Any}
              (= :määräajan arkistointi) (merge {:sailytysaika {:retention-period-end s/Inst
                                                                :pituus s/Int
                                                                :arkistointi s/Keyword
                                                                :perustelu s/Str}})
              (#{:osittain-salassapidettava :salainen} julkisuusluokka) (merge {:security-period-end s/Inst}))
      (s/check metadata)))

(defn- add-missing-coordinates
  "Prioritizes location-etrs-tm35fin because that is the one the user can see and edit.
   It seems that in some cases both locations were given but referred to different positions on the map;
   prioritizing location-etrs-tm35fin ensures that the document's erroneous location is at least visible to the user."
  [{wgs84 :location-wgs84 etrs-tm35fin :location-etrs-tm35fin :as metadata}]
  (cond-> metadata
          (and wgs84 (not etrs-tm35fin)) (assoc :location-etrs-tm35fin (geo/wgs84->epsg3067 wgs84))
          (some? etrs-tm35fin)           (assoc :location-wgs84 (geo/epsg3067->wgs84 etrs-tm35fin))))

(defn- change-applicant-to-list
  "Changes single applicant to applicants list for compatibility with previous metadata spec"
  [{:keys [applicant applicants] :as metadata}]
  (if (and (string? applicant) (not (seq? applicants)))
    (-> (assoc metadata :applicants [applicant])
        (dissoc :applicant))
    metadata))

(defn- foreman-operation? [metadata]
  (some #{"tyonjohtajan-nimeaminen-v2"} (:operations metadata)))

(defn- prepare-foreman-metadata [metadata]
  (if (foreman-operation? metadata)
    (dissoc metadata :lupapvm :paatospvm :paatoksentekija)
    metadata))


(defn- contains-invalid-utf? [text]
  (when text
    (->> (.getBytes text "UTF-8")
         vec
         (partition 3 1)
         (some #(= (vec %) [-17 -65 -67])))))

(defn- check-for-invalid-characters [md]
  (->> [:contents :address :projectDescription]
       (filter #(contains-invalid-utf? (get md %)))
       (map (fn [k]
              (timbre/warn "Metadata for file" (:tiedostonimi md) "contains invalid UTF-8 in key" k ":" (k md))
              [k "string contains invalid UTF-8 characters"]))
       (into {})))

(defn- invalid-coordinates? [{:keys [location-wgs84]}]
  (when (seq location-wgs84)
    (let [[_ y] location-wgs84]
      (or (neg? y) (> y 75)))))

(defn- validate-metadata [parsed-metadata tos-metadata]
  (if (map? parsed-metadata)
    (try
      (let [metadata (->> (assoc (meta-merge parsed-metadata tos-metadata) :arkistointipvm (Date.) :tila :arkistoitu)
                          (add-missing-coordinates)
                          (change-applicant-to-list)
                          (prepare-foreman-metadata)
                          schema-utils/remove-blank-keys
                          (schema-utils/coerce-metadata-to-schema ams/full-document-metadata))
            errors (-> (s/check ams/full-document-metadata metadata)
                       (merge (validate-special-dates metadata))
                       (merge (check-for-invalid-characters metadata)))]
        {:metadata metadata :errors errors})
      (catch ParseException e
        (timbre/warn e "Could not parse metadata: " parsed-metadata)
        {:errors (str "Could not parse a date string in the metadata: " (.getMessage e))})
      (catch NumberFormatException e
        (timbre/warn e "Could not parse metadata: " parsed-metadata)
        {:errors (str "Could not parse a number in the metadata: " (.getMessage e))})
      (catch Throwable t
        (timbre/error t "Could not parse metadata: " parsed-metadata)
        {:errors (str "An error occurred when parsing document metadata: " (.getMessage t))}))
    {:errors "Invalid metadata format"}))

(def valid-content-types #{"application/pdf" "image/tiff" "text/xml" "application/xml"})

(defn bad-request [errors]
  (timbre/error "Invalid upload request data, errors:" errors)
  {:status 400
   :body {:errors errors}})

(defn unprocessable [errors]
  (timbre/error "Could not complete upload request, errors:" errors)
  {:status 422
   :body {:errors errors}})

(defn forbidden [user organization]
  (timbre/error "User" user "upload for organization" organization "was denied by organization whitelist.")
  {:status 403
   :body {:errors (str "Organization " organization  " is not allowed for this API key.")}})

(defn- parse-error-response [ex {:keys [tiedostonimi]} content-type]
  (let [msg (str "Could not parse file " tiedostonimi " as " content-type)]
    (timbre/warn ex msg)
    (bad-request msg)))

(defn metadata-with-history [document-id metadata object-metadata]
  (let [old-metadata-keys (-> object-metadata :metadata (keys))
        old-metadata      (->> object-metadata
                               :metadata
                               (schema-utils/coerce-metadata-to-schema
                                 lupapiste-commons.archive-metadata-schema/full-document-metadata))
        new-metadata      (-> metadata
                              (select-keys old-metadata-keys)
                              (ea/process-metadata-for-insert)
                              (assoc :deletion-explanation "Re-upload from Lupapiste"
                                     :deleted nil))]
    (->> (md-util/add-archive-edit-history document-id (:arkistoija metadata) old-metadata new-metadata)
         :history
         (assoc metadata :history))))

(defn placeholder-image-is []
  (io/input-stream (io/resource "no-preview-available.jpg")))

(defn- generate-preview
  ([config organization id]
   (generate-preview config organization id nil))
  ([{:keys [pubsub gcs]} organization id content-type]
  (if (and content-type
           (contains? #{"text/xml" "application/xml"} content-type))
    (os/upload-preview gcs organization id {:content      (placeholder-image-is)
                                            :content-type "image/jpeg"})
    (preview-boundary/generate-preview pubsub gcs organization id))))

(defn- upload-valid-doc! [{{:keys [bucket-suffix]} :storage :keys [elastic] :as config}
                          organization
                          id
                          overwrite
                          ^File tempfile
                          content-type
                          metadata]
  (let [bucket (os/org-bucket organization bucket-suffix)
        es-doc (ea/get-document elastic bucket id)
        object-exists? (and es-doc
                            (os/object-exists? (os/storage-from-es-doc config es-doc) organization id))
        updated-metadata (if (and es-doc (-> es-doc :metadata :deleted))
                           (metadata-with-history (str bucket "/" id) metadata es-doc)
                           metadata)
        storage (os/default-storage config)]
    (if (or (not object-exists?)
            (-> es-doc :metadata :deleted)
            overwrite)
      (try
        (xmp/write-metadata-to-file! tempfile updated-metadata content-type)
        (os/upload storage organization id {:content tempfile
                                            :content-type content-type})
        (ea/persist-document-to-index elastic
                                      bucket
                                      id
                                      (md-util/sha256 tempfile)
                                      content-type
                                      (os/default-storage-id config)
                                      updated-metadata)
        (generate-preview config organization id content-type)
        (response "OK")

        (catch ExceptionInfo ex
          (select-keys (ex-data ex) [:status :body :headers]))

        (catch ImageReadException ex
          (parse-error-response ex updated-metadata content-type))

        (catch InvalidPdfException ex
          (parse-error-response ex updated-metadata content-type))

        (catch SAXParseException ex
          (timbre/warn ex "Could not parse XMP metadata in"
                       (:tiedostonimi updated-metadata) "id" id "from application" (:applicationId updated-metadata))
          (bad-request (str "Could not parse the existing XMP metadata embedded in the file: " (.getMessage ex))))

        (catch MalformedByteSequenceException ex
          (timbre/warn ex "Could not parse XMP metadata in"
                       (:tiedostonimi updated-metadata) "id" id "from application" (:applicationId updated-metadata))
          (bad-request (str "XMP metadata embedded in the file is not valid UTF-8: " (.getMessage ex))))

        (catch Throwable t
          (timbre/error t "Unexpected error occurred when processing file"
                        (:tiedostonimi updated-metadata) "id" id "from application" (:applicationId updated-metadata))
          {:status 500
           :body {:errors (str "Unexpected error occurred: " (.getMessage t))}}))
      (do
        (timbre/warn "Object with id" id "in bucket" bucket "already exists and no overwrite was requested.")
        (-> (response (str "Object with id " id " already exists and no overwrite was requested."))
            (status 409))))))

(defn upload-document
  [{:keys [toj] :as config}
   {{{:keys [tempfile content-type]} "file" md "metadata"} :multipart-params
    {:keys [id overwrite useTosMetadata]} :params
    {:keys [organizations] :as user} :write-user}]
  (try
    (let [{:keys [organization tosFunction type]
           :as parsed-metadata}             (json/parse-string md true)
          use-tos-metadata?                 (= "true" useTosMetadata)
          _                                 (timbre/info "Validating and uploading" id "for organization" organization ", app-id:" (:app-id user))
          {:keys [tos-metadata tos-errors]} (when use-tos-metadata?
                                              (tos-access/tos-metadata toj organization (:code tosFunction) type))
          {:keys [metadata errors]}         (validate-metadata parsed-metadata tos-metadata)
          forbidden-org?                    (and (seq organizations)
                                                 (not-any? #{organization} organizations))
          archival?                         (-> metadata :sailytysaika :arkistointi)
          invalid-content-type?             (nil? (valid-content-types content-type))]
      (cond
        (and use-tos-metadata?
             (seq tos-errors))          (unprocessable {:tos tos-errors})
        (seq errors)                    (bad-request {:metadata errors})
        forbidden-org?                  (forbidden user organization)
        (nil? id)                       (bad-request {:content "Missing object id"})
        (string/includes? id " ")       (bad-request {:content "Object id must not contain spaces"})
        (nil? tempfile)                 (bad-request {:content "Missing content"})
        (nil? content-type)             (bad-request {:content "Missing content-type for content"})
        invalid-content-type?           (bad-request {:content (str "Content-type " content-type " is not allowed for archiving.")})
        (zero? (.length tempfile))      (bad-request {:content "Content length is zero"})
        (= :ei archival?)               (unprocessable {:metadata "Metadata sets document to not-archived, sailytysaika.arkistointi = ei"})
        (invalid-coordinates? metadata) (bad-request {:content "Coordinates seem to be invalid. Check x and y order and CRS in use."})
        :else                           (upload-valid-doc! config organization id overwrite tempfile content-type metadata)))
    (catch JsonParseException _
      (bad-request {:metadata "Invalid metadata format"}))
    (finally
      (when tempfile
        (io/delete-file tempfile :silently)))))

(defn- get-doc-from-bucket
  [{{:keys [bucket-suffix]} :storage :keys [elastic] :as config}
   id
   organization
   download-fn
   http-response-format]
  (if-not organization
    (-> (response {:error "Missing organization"})
        (status 400))
    (try
      (let [bucket    (os/org-bucket organization bucket-suffix)
            es-doc    (ea/get-document elastic bucket id)
            _         (when-not es-doc
                        (throw (ex-info (str "Document " bucket "/" id " not found")
                                        {:status 404
                                         :body   (str "Document " id " not found")})))
            file-data (download-fn (os/storage-from-es-doc config es-doc) organization id)]
        (if http-response-format
          (cond-> {:status          200
                   :body            ((:content-fn file-data))
                   :headers         {"Content-Type" (:content-type file-data)}
                   ; Disable processing
                   :muuntaja/format nil}
            (= http-response-format :attachment) (assoc-in [:headers "Content-Disposition"]
                                                           (str "attachment;filename="
                                                                (-> es-doc :metadata :tiedostonimi))))
          file-data))
      (catch ExceptionInfo ex
        (if http-response-format
          (-> (select-keys (ex-data ex) [:status :body :headers])
              (assoc-in [:headers "content-type"] "text/plain"))
          (throw ex))))))

(defn get-document
  "Returns the given file id from object storage, either as a map like:
   {:content-fn (fn [_] input-stream)
    :content-type \"application/pdf\"}
   or optionally as a Ring response map when `http-response-format` is `:inline` or `:attachment`."
  ([config id organization]
   (get-document config id organization nil))
  ([config id organization http-response-format]
   (get-doc-from-bucket config id organization os/download http-response-format)))

(defn get-preview [config id organization]
  (let [resp (get-doc-from-bucket config id organization os/download-preview :inline)]
    (if (= 404 (:status resp))
      ;; Add missing image to preview generation queue
      (do (generate-preview config organization id)
          {:status          200
           :body            (placeholder-image-is)
           :headers         {"Content-Type"        "image/jpeg"
                             "Content-Disposition" "inline"}
           :muuntaja/format nil})
      resp)))

(defn document-metadata
  ([config id organization exists-only?]
   (document-metadata config id organization exists-only? nil))
  ([{{:keys [bucket-suffix]} :storage :keys [elastic] :as config} id organization exists-only? source-fields]
   (if-not organization
     (-> (response {:error "Missing organization"})
         (status 400))
     (let [bucket (os/org-bucket organization bucket-suffix)
           es-doc (ea/get-document elastic bucket id source-fields)]
       (if (and es-doc
                (os/object-exists? (os/storage-from-es-doc config es-doc) organization id))
         {:status 200
          :body (if exists-only?
                  {:exists true}
                  (dissoc es-doc :storage))}
         {:status 404
          :body {:exists false}})))))
