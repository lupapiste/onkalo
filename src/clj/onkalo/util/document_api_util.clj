(ns onkalo.util.document-api-util
  (:require [onkalo.metadata.elastic-api :as ea]
            [ring.util.response :refer :all]
            [schema.core :as s]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]))

(def SearchResponseMetaSchema
  {:moreResultsAvailable s/Bool
   :from                 Long
   :limit                Long
   :count                Long})

(defn find-from-elastic [elastic organization params search-from search-limit publicity-param source-fields sort]
  (if-not organization
    (-> (response {:error "Missing organization"})
        (status 400))
    (let [search-params (dissoc params :organization :search-from :search-limit)
          results (ea/find-documents elastic
                                     organization
                                     search-params
                                     search-from
                                     search-limit
                                     publicity-param
                                     source-fields
                                     sort)]
      (cond-> (response results)
              (:error results) (status 400)))))

(defn with-logging
  "Wrap compojure-api exception-handler a function which will log the
  exception message with given log-level."
  ([handler] (with-logging handler :error))
  ([handler log-level]
   {:pre [(#{:trace :debug :info :warn :error :fatal} log-level)]}
   (fn [^Exception e data req]
     (let [{:keys [request response]} (ex-data e)
           error-map (merge {:message (.getMessage e)
                             :upstream-response response}
                            (select-keys request [:query-params :query-string :uri]))]
       (timbre/log log-level error-map))
     (handler e data req))))

;; Schemas for document store and document terminal APIs

(def CoordinatePair #"\d{1,2}\.\d+,\d{1,2}\.\d+")

(def valid-pair! (s/validator [CoordinatePair]))

(defn validate-shape-parameters! [{:keys [shape shape-docstore]}]
  (when-let [shape (or shape shape-docstore)]
    (->> shape
         (map #(str/split % #";"))
         (map valid-pair!)
         dorun)))

(def HeavyStoreResponse
  {:fileId s/Str
   :contentType s/Str
   :organization s/Str
   (s/optional-key :modified) s/Str
   :metadata {:address s/Str
              (s/optional-key :propertyId) s/Str
              :type s/Str
              :municipality s/Str
              (s/optional-key :applicationId) s/Str
              :kuntalupatunnukset [s/Str]
              :location-etrs-tm35fin [s/Num]
              :location-wgs84 [{:type s/Str
                                :coordinates [s/Any]}]
              (s/optional-key :nationalBuildingIds) [s/Str]
              (s/optional-key :buildingIds) [s/Str]
              (s/optional-key :contents) s/Str
              (s/optional-key :paatospvm) s/Str
              :arkistointipvm s/Str
              :kayttotarkoitukset [s/Str]
              :julkisuusluokka s/Str
              :nakyvyys s/Str
              :myyntipalvelu s/Bool
              :henkilotiedot s/Str
              :tiedostonimi s/Str}})

(def LightStoreResponse
  {:fileId s/Str
   :organization s/Str
   :metadata {:location-etrs-tm35fin [s/Num]
              :location-wgs84 [{:type s/Str
                                :coordinates [s/Any]}]}})

(defn schema->source-fields [key-prefix schema]
  (reduce (fn [acc [k v]]
            (let [field-name (str key-prefix (name (or (:k k) k)))]
              (if (map? v)
                (concat acc (schema->source-fields (str field-name ".") v))
                (conj acc field-name))))
          []
          schema))

(def heavy-response-fields
  (schema->source-fields nil HeavyStoreResponse))

(def light-response-fields
  (schema->source-fields nil LightStoreResponse))

(defn ->upper-case [val]
  (if (and val (string? val))
    (clojure.string/upper-case val)
    val))
