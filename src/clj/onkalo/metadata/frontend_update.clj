(ns onkalo.metadata.frontend-update
  (:require [onkalo.metadata.elastic-api :as ea]
            [meta-merge.core :refer [meta-merge]]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [schema.core :as s]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [clojure.java.io :as io]
            [onkalo.metadata.xmp-processor :as xmp]
            [taoensso.timbre :as timbre]
            [lupapiste-commons.schema-utils :as schema-utils]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [onkalo.domain :as d]
            [onkalo.metadata.metadata-util :as md-util]
            [onkalo.util.lupapiste-api :as lp-api]
            [search-commons.geo-conversion :as geo]
            [clojure.string :as str]
            [onkalo.boundary.object-storage :as os]
            [clojure.set :as set])
  (:import [java.io File]
           [java.util Date]
           [clojure.lang ExceptionInfo]))

(defn- sanitize-fields [metadata]
  (->> metadata
       (map (fn [[k v]]
              (cond
                (map? v) [k (sanitize-fields v)]
                (or (d/field-editable? k)
                    (d/field-editable-via-api? k)) [k v])))
       (into {})))

(defn- calculate-end-date [decision-date years]
  (-> (c/from-date decision-date)
      (t/plus (t/years years))
      (.toDate)))

(defn- update-end-dates [{{:keys [arkistointi pituus]} :sailytysaika
                          :keys  [salassapitoaika julkisuusluokka paatospvm] :as metadata}]
  (cond-> metadata
          (= arkistointi :määräajan) (assoc-in [:sailytysaika :retention-period-end]
                                               (calculate-end-date paatospvm pituus))
          (not= julkisuusluokka :julkinen) (assoc :security-period-end
                                                  (calculate-end-date paatospvm salassapitoaika))))

(defn- is->tmp-file [is id]
  (let [file (File/createTempFile id ".tmp")]
    (with-open [is (io/input-stream is)]
      (io/copy is file))
    file))

(defn- prevent-permanent-retention-modification [{:keys [sailytysaika]} new-metadata]
  (if (= :ikuisesti (:arkistointi sailytysaika))
    (assoc new-metadata :sailytysaika sailytysaika)
    new-metadata))

(defn- return-metadata [metadata]
  {:metadata                     (tms/sanitize-metadata metadata)
   :contents                     (:contents metadata)
   :type                         (:type metadata)
   :address                      (:address metadata)
   :propertyId                   (:propertyId metadata)
   :projectDescription           (:projectDescription metadata)
   :kasittelija                  (:kasittelija metadata)
   :permit-expired               (:permit-expired metadata)
   :permit-expired-date          (:permit-expired-date metadata)
   :demolished                   (:demolished metadata)
   :demolished-date              (:demolished-date metadata)
   :nationalBuildingIds          (:nationalBuildingIds metadata)
   :buildingIds                  (:buildingIds metadata)
   :paatospvm                    (:paatospvm metadata)
   :location                     (vec (:location-etrs-tm35fin metadata))
   :location-wgs84               (:location-wgs84 metadata)
   :kuntalupatunnukset           (:kuntalupatunnukset metadata)
   :kuntalupatunnukset-muutossyy (:kuntalupatunnukset-muutossyy metadata)})

(defn- prepare-coordinates [{:keys [location] :as metadata}]
  (if location
    (-> metadata
        (dissoc :location)
        (assoc :location-etrs-tm35fin location)
        (assoc :location-wgs84 [{:type "point"
                                 :coordinates (geo/epsg3067->wgs84 location)}]))
    metadata))

(defn- prepare-for-meta-merge
  "Marks array fields to be replaced completely, instead of the default behavior to concat arrays"
  [metadata]
  (reduce (fn [md k]
            (if (k md)
              (update md k with-meta {:replace true})
              md))
          metadata
          [:location-etrs-tm35fin
           :location-wgs84
           :nationalBuildingIds
           :buildingIds]))

(defn- prepare-new-metadata [new-metadata]
  (->> new-metadata
       sanitize-fields
       prepare-coordinates
       (schema-utils/coerce-metadata-to-schema ams/validation-schema-for-onkalo-update-metadata)
       prepare-for-meta-merge))

(defn validate-old-to-new-values! [old-metadata new-metadata]
  (cond
    (and (:kuntalupatunnukset new-metadata)
         (not= (:kuntalupatunnukset old-metadata)
               (:kuntalupatunnukset new-metadata))
         (str/blank? (:kuntalupatunnukset-muutossyy new-metadata))) (throw (Exception. "Kuntalupatunnukset-muutossyy required when changing kuntalupatunnukset"))))

(defn- override-meta-merge
  "Meta merge merges the values no matter what.
   Sometimes we want to use our values as they are without merging to existing values."
  [fields new-metadata merged-metadata]
  (merge merged-metadata (select-keys new-metadata fields)))

(defn update-metadata [{{:keys [bucket-suffix]} :storage :keys [elastic] :as config}
                       ^String id
                       new-metadata
                       user
                       organization]
  (try
    (let [bucket (os/org-bucket organization bucket-suffix)]
      (if-let [old-data (ea/get-document elastic bucket id)]
        (let [old-metadata (->> (-> (:metadata old-data)
                                    (dissoc :location-docstore) ; Internal field
                                    (assoc :organization organization))
                                (schema-utils/coerce-metadata-to-schema ams/validation-schema-for-onkalo-update-metadata))
              prepared-metadata (prepare-new-metadata new-metadata)
              merged-metadata (->> (meta-merge old-metadata prepared-metadata)
                                   (override-meta-merge [:kuntalupatunnukset] new-metadata)
                                   (md-util/un-delete-when-called-for old-metadata new-metadata))]
          (validate-old-to-new-values! old-metadata prepared-metadata)
          (if (not= old-metadata merged-metadata)
            (let [updated-metadata (->> merged-metadata
                                        (md-util/add-archive-edit-history (str bucket "/" id) user old-metadata)
                                        (prevent-permanent-retention-modification old-metadata)
                                        (update-end-dates)
                                        (tms/remove-conditional-keys)
                                        schema-utils/remove-blank-keys
                                        ;; Old documents might have a type that is no longer in use,
                                        ;; so we don't validate the type here
                                        (s/validate ams/validation-schema-for-onkalo-update-metadata))
                  return-metadata       (return-metadata updated-metadata)
                  {:keys [content-fn
                          content-type]} (os/download (os/storage-from-es-doc config old-data) organization id)
                  tempfile         (is->tmp-file (content-fn) id)
                  sha256-sum       (md-util/sha256 tempfile)
                  new-storage      (os/default-storage config)]
              (xmp/write-metadata-to-file! tempfile updated-metadata content-type)
              (ea/persist-document-to-index elastic
                                            bucket
                                            id
                                            sha256-sum
                                            content-type
                                            (os/default-storage-id config)
                                            updated-metadata)
              (os/upload new-storage organization id {:content tempfile
                                                      :content-type content-type})
              (timbre/debug "Object with id" id "stored in bucket" bucket "with updated metadata")
              (io/delete-file tempfile :silently)
              {:status 200 :body (if (:deleted updated-metadata)
                                   (assoc return-metadata :deleted (:deleted updated-metadata)
                                          :deletion-explanation (:deletion-explanation updated-metadata))
                                   return-metadata)})
            (do
              (timbre/debug "Ignoring empty metadata update for object id" id)
              {:status 200 :body (return-metadata merged-metadata)})))
        {:status 404
         :body {:error (str "Document with id " id " was not found in the metadata index.")}}))
    (catch ExceptionInfo ex
      (timbre/error ex "Error adding object with id" id "to object storage. Metadata in Elasticsearch may now be inconsistent with XMP metadata in file.")
      (select-keys (ex-data ex) [:status :body :headers]))
    (catch Exception e
      (timbre/error e "Invalid metadata or unknown error")
      {:status 400
       :body "Invalid metadata"})))

(def supported-query-keys #{:kuntalupatunnukset
                            :propertyId
                            :applicationId
                            :tiedostonimi})

(defn find-and-update-metadata [{:keys [elastic] :as config}
                                search-params
                                new-metadata
                                user
                                organization]
  (if-not (set/subset? (set (keys search-params)) supported-query-keys)
    {:status 400
     :body (str "Only the following query keys are supported: " supported-query-keys)}
    (let [results (-> (ea/find-documents elastic organization
                                         (assoc search-params
                                                :tokenize true)
                                         0
                                         1000
                                         false
                                         [:fileId]
                                         nil)
                      :results)
          msg     (str "Updated " (count results) " documents matching query " (pr-str search-params) ": "
                       (pr-str (mapv :fileId results)))]
      (->> results
           (map (fn [{:keys [fileId]}]
                  (let [resp (update-metadata config fileId new-metadata user organization)]
                    (when-not (= (:status resp) 200)
                      (throw (Exception. "Invalid metadata or unknown error"))))))
           dorun)
      (timbre/info msg)
      {:status 200
       :body   msg})))

(defn- mass-operation-for-document
  [config user update-to-lupapiste-check update-to-lp-fn undo-to-elastic-fn new-metadata document]
  (let [{:keys [application-id
                doc-id
                org-id]}        document
        result-from-elastic     (update-metadata config doc-id new-metadata user org-id)
        update-to-elastic-ok?   (-> result-from-elastic :status (= 200))
        update-to-lupapiste?    (and (update-to-lupapiste-check document) update-to-elastic-ok?)
        result-from-lupapiste   (when update-to-lupapiste? (update-to-lp-fn document))
        update-to-lupapiste-ok? (-> result-from-lupapiste :status (= 200))
        success?                (if update-to-lupapiste?
                                  (and update-to-elastic-ok? update-to-lupapiste-ok?)
                                  update-to-elastic-ok?)]
    (when (not update-to-elastic-ok?)
      (timbre/warn "Failed to update elastic for document" doc-id "of" application-id
                   ", status" (-> result-from-elastic :status)))
    (when (and update-to-elastic-ok? update-to-lupapiste? (not update-to-lupapiste-ok?))
      (undo-to-elastic-fn document result-from-lupapiste))
    {:doc-id doc-id :success? success? :updated-metadata (if success? (:body result-from-elastic) nil)}))

(defn- undo-status-change-to-elastic
  [config user new-metadata {:keys [doc-id org-id application-id]} result-from-lupapiste]
  (let [deleted-status  (:deleted new-metadata)
        cancel-metadata {:deleted (if (inst? deleted-status) nil (Date.))
                         :deletion-explanation (:body result-from-lupapiste)}]
    (timbre/error "Posting" (if (inst? deleted-status) "undo-archiving" "re-archiving")
                  "to lupapiste for document" doc-id
                  "of application" application-id
                  "failed with message" (:body result-from-lupapiste))
    (update-metadata config doc-id cancel-metadata user org-id)))

(defn doc-in-correct-state [op {:keys [deleted]}]
  (if (= op :redo-archiving)
    (some? deleted)
    (nil? deleted)))

(defn- update-deletion-to-lupapiste? [doc]
  (and (when (:application-id doc) (re-matches #"LP.*" (:application-id doc)))
       (->> doc :type (keyword) (#{:case-file :hakemus}) (not))))

(defn change-archiving-status
  [{:keys [lupapiste-api] :as config} documents {:keys [operation deletion-explanation]} user]
  (let [operation-ok?                (#{:undo-archiving :redo-archiving} operation)
        new-metadata                 {:deleted (if (= operation :undo-archiving) (.toDate (t/now)) nil)
                                      :deletion-explanation deletion-explanation}
        operation-filtered-documents (filter (partial doc-in-correct-state operation) documents)
        update-to-lp-fn              (partial lp-api/update-attachment-status-change-to-lupapiste lupapiste-api new-metadata)
        undo-to-elastic-fn           (partial undo-status-change-to-elastic config user new-metadata)
        results                      (when operation-ok? (pmap (partial mass-operation-for-document
                                                                        config
                                                                        user
                                                                        update-deletion-to-lupapiste?
                                                                        update-to-lp-fn
                                                                        undo-to-elastic-fn
                                                                        new-metadata)
                                                               operation-filtered-documents))
        success?                     (every? :success? results)]
    (cond
      success? {:status 200 :body results}

      (not operation-ok?)
      (do
        (timbre/warn "Invalid operation" operation "requested for documents"
                     (->> documents
                          (map (juxt :application-id :doc-id))
                          (map #(str/join " / " %))
                          (str/join ", ")))
        {:status 400 :body "Bad Request"})

      :else
      (do
        (timbre/warn "change-archiving-status errors" results)
        {:status 500 :body results}))))

(defn mass-update-metadata [config documents {:keys [operation metadata]} user]
  (let [operation-ok?                 (#{:mass-update-metadata} operation)
        update-to-lp-fn               (constantly false)
        update-deletion-to-lupapiste? (constantly false)
        undo-to-elastic-fn            (constantly nil)
        results                       (when operation-ok? (pmap (partial mass-operation-for-document
                                                                         config
                                                                         user
                                                                         update-deletion-to-lupapiste?
                                                                         update-to-lp-fn
                                                                         undo-to-elastic-fn
                                                                         metadata)
                                                                documents))
        success?                     (every? :success? results)]
    (cond
      success? {:status 200 :body results}

      (not operation-ok?)
      (do
        (timbre/warn "Invalid operation" operation "requested for documents"
                     (->> documents
                          (map (juxt :application-id :doc-id))
                          (map #(str/join " / " %))
                          (str/join ", ")))
        {:status 400 :body "Bad Request"})

      :else
      (do
        (timbre/warn "mass-update-metadata errors" results)
        {:status 500 :body results}))))
