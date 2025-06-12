(ns onkalo.metadata.metadata-util
  (:require [clojure.pprint :as pp]
            [onkalo.domain :as d]
            [pandect.algo.sha256 :as sha256]
            [schema.core :as s]
            [clojure.tools.logging :as log])
  (:import [java.util Date]))

(defn un-delete-when-called-for [old-metadata new-metadata updated-metadata]
  (if (and (:deleted old-metadata)
           (:deletion-explanation new-metadata)
           (not (inst? (:deleted new-metadata))))
    (dissoc updated-metadata :deleted :deletion-explanation)
    updated-metadata))

(defn map-flattener [a-map]
  (let [path-recursively (fn helper [path-acc a-map]
                           (mapcat (fn [[k v]]
                                     (let [path (conj path-acc k)]
                                       (if (map? v)
                                         (helper path v)
                                         [[path v]])))
                                   a-map))]
    (path-recursively [] a-map)))

(defn- edn-encode
  "Accepts any clojure value, returns EDN encoded string."
  [data]
  (binding [*print-length* nil]
    (pr-str data)))

(defn- print-val [v]
  (cond
    (or (sequential? v) (map? v)) (edn-encode v)

    (or (inst? v) (nil? v)) v

    :else (str v)))

(defn- old-metadata-key [k]
  (if (= k :location)
    :location-etrs-tm35fin
    k))

(def es-value-max-length 32766)

(defn- value-as-valid-length-string [document-id path v]
  (let [processed-value (print-val v)]
    (if (and (string? processed-value)
             (> (count (.getBytes processed-value)) es-value-max-length))
      (do (log/error "Value in" document-id (pr-str path) "is too long to store in Elasticsearch.")
          "<Value not stored because it exceeds maximum length>")
      processed-value)))

(defn- modify-event [document-id user old-metadata ks v & [deletion-explanation]]
  (let [modified (Date.)
        user {:userId    (:id user)
              :username  (:username user)
              :firstName (:firstName user)
              :lastName  (:lastName user)}
        event {:modified modified
               :user user
               :field ks
               :old-val (value-as-valid-length-string document-id ks (get-in old-metadata (map old-metadata-key ks)))
               :new-val (value-as-valid-length-string document-id ks v)}]
    (s/validate d/history-event (if deletion-explanation (assoc event :deletion-explanation deletion-explanation) event))))

(defn add-archive-edit-history
  "Adds archiving event to history array.
   Takes old and already merged and sanitized updated-metadata."
  [document-id user old-metadata new-metadata]
  (let [history (:history old-metadata)
        ;; Remove :location-wgs84 from history, as it may contain large polygons and the point is anyway just
        ;; converted from location-etrs-tm35fin if modified
        new-events (map-flattener (dissoc new-metadata :deletion-explanation :location-wgs84))
        compared-metadata (remove nil? (for [[ks v] new-events]
                                         (let [old-val (get-in old-metadata ks)]
                                           (when (not= old-val v)
                                             (modify-event document-id
                                                           user
                                                           old-metadata
                                                           ks
                                                           v
                                                           (:deletion-explanation new-metadata))))))]
    (assoc new-metadata :history (concat history compared-metadata))))

(def sha256 sha256/sha256)
