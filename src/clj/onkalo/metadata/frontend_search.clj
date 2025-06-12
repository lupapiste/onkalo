(ns onkalo.metadata.frontend-search
  (:require [clojure.string :as s]
            [clj-time.coerce :as c]
            [clj-time.format :as f]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [search-commons.geo-conversion :as geo]
            [onkalo.metadata.elastic-api :as ea]
            [lupapiste-commons.schema-utils :as schema-utils]))

(def s2-metadata-keys
  (set (map #(or (:k %) %) (keys tms/AsiakirjaMetaDataMap))))

(def result-limit 50)

(defn- process-result [{:keys [metadata] :as result}]
  (let [non-s2-keys (remove #(s2-metadata-keys %) (keys metadata))]
    (-> (merge result (select-keys metadata non-s2-keys))
        (assoc :metadata (->> (apply dissoc metadata non-s2-keys)
                              (schema-utils/coerce-metadata-to-schema tms/AsiakirjaMetaDataMap))
               :id (:fileId result)
               :source-system :onkalo
               :grouping-key (let [app-id (:applicationId metadata)]
                               (if-not (s/blank? app-id)
                                 app-id
                                 (first (:kuntalupatunnukset metadata))))
               :location (:location-etrs-tm35fin metadata))
        (dissoc :fileId :location-etrs-tm35fin))))

(defn- unparse-ts [ts]
  (when ts
    (f/unparse (:date-time-no-ms f/formatters) (c/from-long ts))))

(defn- coordinate-fields [shapes]
  (map (fn [shape]
         (->> (map geo/epsg3067->wgs84 shape)
              (map #(s/join "," %))
              (s/join ";")))
       shapes))

(defn search [elastic orgs {:keys [text fields timespan usage page type operation coordinates organization tokenize? deleted?]}]
  (let [start (System/currentTimeMillis)
        text (s/trim (or text ""))
        from (* result-limit page)
        selected-orgs (if (s/blank? organization) orgs (filter #(= % (keyword organization)) orgs))
        search-fields (cond-> {:search-source "onkalo"}
                              (and (empty? fields) (not (s/blank? text))) (assoc :all text)
                              (:address fields) (assoc :address text)
                              (:attachment.label.contents fields) (assoc :contents text)
                              (:applicant fields) (assoc :applicants text)
                              (:handler fields) (assoc :kasittelija text)
                              (:designer fields) (assoc :suunnittelijat text)
                              (:propertyId fields) (assoc :propertyId text)
                              (:foreman fields) (assoc :foremen text)
                              (:projectDescription fields) (assoc :projectDescription text)
                              (:from timespan) (assoc :paatospvm [(str "gte:" (unparse-ts (:from timespan)))])
                              (:to timespan) (update :paatospvm #(conj % (str "lte:" (unparse-ts (:to timespan)))))
                              (:closed-from timespan) (assoc :closed [(str "gte:" (unparse-ts (:closed-from timespan)))])
                              (:closed-to timespan) (update :closed #(conj % (str "lte:" (unparse-ts (:closed-to timespan)))))
                              (:tyomaasta-vastaava fields) (assoc :tyomaasta-vastaava text)
                              usage (assoc :kayttotarkoitukset usage)
                              (seq type) (assoc :type (s/join "." (map name type)))
                              operation (assoc :operations (name operation))
                              (seq coordinates) (assoc :shape (coordinate-fields coordinates))
                              tokenize? (assoc :tokenize 1)
                              deleted? (assoc :deleted 1))
        {:keys [results meta]} (ea/find-documents elastic selected-orgs search-fields from result-limit false)]
    {:status 200
     :body {:results (map process-result results)
            :has-more? (:moreResultsAvailable meta)
            :took (float (/ (- (System/currentTimeMillis) start) 1000))}}))
