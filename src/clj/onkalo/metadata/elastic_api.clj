(ns onkalo.metadata.elastic-api
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [cljts.geom :as geom]
            [clojure.string :as s]
            [clojure.walk :refer [postwalk]]
            [onkalo.boundary.elastic-document-api :as esd]
            [onkalo.component.elastic :refer [searchable-properties limiting-properties]]
            [search-commons.utils :as utils]
            [taoensso.timbre :as timbre])
  (:import [com.vividsolutions.jts.geom Polygon]))

(defn- stringify [v]
  (cond
    (sequential? v) (map stringify v)
    (keyword? v) (name v)
    :else v))

(defn- stringify-values [m]
  (let [f (fn [[k v]] [k (stringify v)])]
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(def date-parser
  (tf/formatter
    (t/time-zone-for-id "Europe/Helsinki")
    "yyyy-MM-dd"
    "yyyy-MM-ddZ"
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
    "yyyy-MM-dd'T'HH:mm:ssZ"))

(defn- parse-date [date-str]
  (when date-str
    (try (->> (tf/parse date-parser date-str)
              (tf/unparse (:date-time tf/formatters)))
         (catch Exception _))))

(defn- parse-int [v]
  (when v
    (if (integer? v)
      v
      (try (Integer/parseInt v)
           (catch NumberFormatException _)))))

(defn- parse-geo-point [v]
  (let [points (->> (s/split v #",")
                    (map parse-double)
                    (remove nil?))]
    (when (= (count points) 2)
      points)))

(defn- coerce-value [type v]
  (when type
    (case type
      "double" (parse-double v)
      "geo_shape" {:type "point"
                   :coordinates (parse-geo-point v)}
      "date" (parse-date v)
      "lowercase" (str "*" (s/lower-case v) "*")
      "standard" v
      (str "*" v "*"))))

(defn- key-and-type [[k {:keys [analyzer type properties]}] & [prefix]]
  (if properties
    (apply merge (map #(key-and-type % (name k)) properties))
    (let [new-key (if prefix (str prefix "." (name k)) (name k))]
      {new-key (or analyzer type)})))

(def key-type-map
  (->> (map key-and-type (merge searchable-properties limiting-properties))
       (into {})))

(defn wildcard-query [field value-type v]
  (when-let [value (coerce-value value-type v)]
    {:wildcard {(str "metadata." field) value}}))

(defn match-query [field value]
  {:match_phrase_prefix {(str "metadata." field) {:query value
                                                  :max_expansions 10}}})

(defn range-query [field value-type values]
  (let [param-map (->> values
                       (map (fn [combined]
                              (let [[prefix v] (s/split combined #":" 2)
                                    value (coerce-value value-type v)]
                                (when value
                                  [prefix value]))))
                       (remove nil?)
                       (into {}))]
    (when (seq param-map)
      {:range {(str "metadata." field) param-map}})))

(defn- term-query [k val-or-vals]
  (if (sequential? val-or-vals)
    {:terms {k val-or-vals}}
    {:term {k val-or-vals}}))

(defn- filter-coord-duplicates [coordinates]
  (reduce
    (fn [coords c]
      (if-not (= (last coords) c)
        (concat coords [c])
        coords))
    []
    coordinates))

(defn- shape-query [k v]
  (let [str-shapes (if (sequential? v) v [v])
        shapes (->> str-shapes
                    (map (fn [shape] [(->> (s/split shape #";")
                                           (map parse-geo-point)
                                           filter-coord-duplicates)])))]
    {:geo_shape {k {:shape {:type "multipolygon"
                            :coordinates shapes}}}}))

(defn- geojson-shape-query [k geojson-map]
  {:geo_shape {k {:shape geojson-map}}})

(defn- value-to-seq [v tokenize]
  (cond
    (sequential? v) v
    (and tokenize (string? v)) (->> (re-seq #"\"[^\"]+\"|[\S]+" v)
                                    (map #(s/replace % "\"" "")))
    :else [v]))

(defn property-id-query [values]
  (->> values
       (map (fn [v] (let [formatted-value (or (utils/zero-padded-property-id v)
                                              (utils/property-id-regexp-string [v]))]
                      (when formatted-value {:regexp {"metadata.propertyId" formatted-value}}))))
       (remove nil?)))

(defn address-ends-with-number? [value]
  (some? (re-matches #".+\s[0-9]+\S*" value)))

(defn address-query [key value-type value]
  (if (address-ends-with-number? value)
    (term-query (str "metadata." (name key)) (s/lower-case value))
    (wildcard-query key value-type value)))

(defn- attachment-type-query [values]
  (let [k "metadata.type"]
    {:bool
     {:should (map (fn [v]
                     (if (s/includes? v ".")
                       (term-query k v)
                       {:wildcard {k (str v "*")}}))
                   values)
      :minimum_should_match 1}}))

(defn param->query [[k v] tokenize & [use-terms?]]
  (let [key        (name k)
        exists?    (= v "_exists_")
        value-type (get key-type-map key)
        values     (value-to-seq v tokenize)
        range?     #(re-matches #"(lt|gt)e?:.*" %)]
    (cond
      exists?                   {:exists {:field (str "metadata." key)}}
      (= k :shape)              (shape-query :metadata.location-wgs84 v)
      (= k :shape-docstore)     (shape-query :metadata.location-docstore v)
      (= k :geojson)            (geojson-shape-query :metadata.location-wgs84 v)
      (= k :geojson-docstore)   (geojson-shape-query :metadata.location-docstore v)
      (= k :propertyId)         (property-id-query values)
      (= k :projectDescription) (map #(match-query key %) values)
      (= k :address)            (map #(address-query key value-type %) values)
      (= k :deleted)            {:exists {:field "metadata.deleted"}}
      (some range? values)      (range-query key value-type values)
      (= k :type)               (attachment-type-query values)
      (= k :fileId)             {:wildcard {:fileId v}}
      use-terms?                (term-query (str "metadata." (name k)) v)
      :else                     (map #(wildcard-query key value-type %) values))))

(defn- should-queries [params]
  (->> (if (some (fn [[k _]] (= k :all)) params)
         (map #(param->query [% (:all params)] (:tokenize params))
              (conj (keys searchable-properties) :applicationId :fileId))
         (map #(param->query % (:tokenize params)) (select-keys params (keys searchable-properties))))
       (flatten)
       (remove nil?)))

(defn- must-queries [params]
  (->> (conj (keys limiting-properties) :shape :shape-docstore :geojson :geojson-docstore :fileId)
       (select-keys params)
       (map #(param->query % (:tokenize params) :use-terms?))
       (flatten)
       (remove nil?)))

(defn- valid-polygon? [coordinates]
  (try
    (let [c-objs (map (fn [coord] (geom/c (first coord) (second coord))) coordinates)
          linear-ring (geom/linear-ring c-objs)
          polygon (geom/polygon linear-ring [])]
      (.isValid ^Polygon polygon))
    (catch Exception e
      (timbre/warn "Polygon validation error"))))

(defn- clean-and-validate-drawing [drawings]
  (doall
    (map
      (fn [{:keys [coordinates type]}]
        (let [lc-type (s/lower-case type)
              filtered-coords (case lc-type
                                "linestring" (filter-coord-duplicates coordinates)
                                "polygon" (map filter-coord-duplicates coordinates)
                                "multipolygon" (map
                                                 (fn [polygon]
                                                   (map filter-coord-duplicates polygon))
                                                 coordinates)
                                coordinates)
              multipolygon (case lc-type
                             "multipolygon" coordinates
                             "polygon" [coordinates]
                             [])]
          (doseq [polygon multipolygon]
            (when-not (valid-polygon? (first polygon))
              (throw (IllegalArgumentException. (str "Polygon " polygon " is not valid.")))))
          {:type type :coordinates filtered-coords}))
      drawings)))

(defn process-metadata-for-insert
  "Combines location-wgs84, drawing-wgs84 and adds location-docstore metadata.
   Note that a missing location-wgs84 key is handled upstream at `onkalo.storage.document/add-missing-coordinates`"
  [{:keys [location-wgs84 drawing-wgs84] :as md}]
  (let [metadata (dissoc md :organization)]
    (cond
      (and (:type (first location-wgs84)) (nil? drawing-wgs84))
      ; Location-wgs84 is GeoJSON (e.g. from front-end update)
      (->> location-wgs84
           (filter #(.equalsIgnoreCase "point" (:type %)))
           first
           (assoc metadata :location-docstore))

      (and (:type (first location-wgs84)) (seq drawing-wgs84))
      (throw
        (IllegalArgumentException. "Both GeoJSON location-wgs84 and drawing-wgs84 present in metadata."))

      :else
      (-> metadata
          (dissoc :drawing-wgs84)
          (assoc :location-docstore {:type "point" :coordinates location-wgs84})
          (cond->
            (seq location-wgs84) (assoc :location-wgs84 [{:type "point" :coordinates location-wgs84}])
            (seq drawing-wgs84) (update :location-wgs84 concat (clean-and-validate-drawing drawing-wgs84)))))))

(defn persist-document-to-index [elastic bucket id sha256-sum content-type storage {:keys [organization] :as metadata}]
  (let [es-id (str bucket "-" id)
        document (->> {:source       "lupapiste"
                       :bucket       bucket
                       :fileId       id
                       :sha256       sha256-sum
                       :contentType  content-type
                       :organization organization
                       :modified     (tf/unparse (:date-time tf/formatters) (t/now))
                       :storage      storage
                       :metadata     (process-metadata-for-insert metadata)}
                      (stringify-values))
        resp (esd/put elastic es-id document)
        actual-id (-> resp :body :_id)]
    (timbre/debug "Document metadata with id" actual-id "persisted to Elasticsearch")
    actual-id))

(defn- source-without-bucket [hit]
  (dissoc (:_source hit) :bucket))

(defn- source-with-id [hit]
  (-> (:_source hit)
      (assoc :elasticId (:_id hit))))

(defn- find-from-es [elastic query sort from size source-fields post-processing-fn]
  (let [results (->> (esd/search elastic
                                 {:query query
                                  :_source (or source-fields [])
                                  :sort sort
                                  :from from
                                  :size (inc size)})
                     :body
                     :hits
                     :hits
                     (map post-processing-fn))]
    {:meta {:moreResultsAvailable (> (count results) size)
            :from from
            :limit size
            :count (min size (count results))}
     :results (take size results)}))

(defn public-only-filter
  "Filters documents for public document searches, docstore and docterminal. If this filter is called, the argument
   publicity-param can be :public-only? or :docterminal. Docterminal is allowed to see also documents that are not
   available for Lupapiste Kauppa."
  [publicity-param]
  (let [now-str (tf/unparse (:date-time tf/formatters) (t/now))
        must-terms [(term-query :metadata.nakyvyys "julkinen")
                    {:terms {:metadata.henkilotiedot ["ei-sisalla" "sisaltaa"]}}]]
    {:bool {:must (if (not= :docterminal publicity-param)
                    (conj must-terms (term-query :metadata.myyntipalvelu true))
                    must-terms)
            :should [(term-query :metadata.julkisuusluokka "julkinen")
                     {:range {:metadata.security-period-end {:lte now-str}}}]
            :minimum_should_match 1}}))

(defn- allowed-document-types-filter [allowed-doc-types]
  {:bool {:should (mapv (fn [[org org-doc-types]]
                          {:bool {:must [{:term {:organization org}}
                                         {:terms {"metadata.type" org-doc-types}}]}})
                        allowed-doc-types)}})

(defn- allowed-municipalities-filter [allowed-municipalities]
  {:bool {:should (param->query [:municipality allowed-municipalities] nil)}})

(defn find-documents
  "Search for documents from Elasticsearch index.
   Omitting `source-fields` returns all fields.
   `sort` can be a map, `false` for no sorting and `nil` for default of applicationId asc, paatospvm desc"
  ([elastic organization params from size publicity-param]
    (find-documents elastic organization params from size publicity-param nil))
  ([elastic organization params from size publicity-param source-fields]
   (find-documents elastic organization params from size publicity-param source-fields nil))
  ([elastic
    organization
    {:keys [allowed-document-types allowed-municipalities] :as params}
    from
    size
    publicity-param
    source-fields
    sort]
    (let [size (or (parse-int size) 30)
          from (or (parse-int from) 0)
          ;; Note that when outside filter context and with must- or
          ;; filter-queries present, should queries only affect the
          ;; document's score. Thus, a document can appear in the
          ;; result set even if none of the should queries match.
          should (should-queries params)
          filter (cond-> (conj (must-queries params) (term-query :organization organization))
                   publicity-param (conj (public-only-filter publicity-param))
                   allowed-document-types (conj (allowed-document-types-filter allowed-document-types))
                   allowed-municipalities (conj (allowed-municipalities-filter allowed-municipalities)))
          query {:bool (cond-> {:filter filter}
                               (not= "onkalo" (:search-source params)) (merge {:must_not [{:exists {:field "metadata.deleted"}}]})
                               (seq should) (merge {:should should
                                                    :minimum_should_match 1}))}
          sort  (cond
                  (map? sort) sort
                  (false? sort) {}
                  :else {:metadata.applicationId {:order "asc"}
                         :metadata.paatospvm {:order "desc"}})]
      (try
        (find-from-es elastic query sort from size source-fields source-without-bucket)
        (catch Throwable t
          (let [data {:es-response (:body (ex-data t))
                      :type        ::elastic-error
                      :query       query}]
            (timbre/error t "Elasticsearch search failed" (:es-response data))
            (throw (ex-info "Elasticsearch search failed" data))))))))

(defn get-document
  ([elastic bucket id]
   (get-document elastic bucket id nil))
  ([elastic bucket id source-fields]
   (let [source-fields (when (some? source-fields)
                         (conj source-fields "storage"))]
     (or (-> (esd/get-by-id elastic (str bucket "-" id) source-fields)
             :source
             (dissoc :bucket))
         ;; Tackle the cases where some original documents might not have the file id as their es document id
         (->> (find-from-es elastic
                            {:bool {:must [(term-query :bucket bucket)
                                           (term-query :fileId id)]}}
                            {}
                            0
                            1
                            source-fields
                            source-without-bucket)
              :results
              first)))))

(defn delete-document [elastic bucket id]
  (esd/delete elastic (str bucket "-" id)))

(defn- find-modified-documents [elastic modified-after modified-before from size order organization publicity-param post-processing-fn]
  (let [size (or (parse-int size) 1000)
        from (or (parse-int from) 0)
        mod-after-str (or (parse-date modified-after)
                          (tf/unparse (:date-time tf/formatters) (t/minus (t/now) (t/days 1))))
        mod-before-str (when modified-before
                         (parse-date modified-before))
        filter (cond-> [{:range {:modified (cond-> {:gte mod-after-str}
                                                   mod-before-str (assoc :lte mod-before-str))}}]
                       organization (conj (term-query :organization organization))
                       publicity-param (conj (public-only-filter publicity-param)))
        query {:bool (cond-> {:filter filter}
                             publicity-param (merge {:must_not [{:exists {:field "metadata.deleted"}}]}))}
        sort {:modified {:order order}}]
    (find-from-es elastic query sort from size nil post-processing-fn)))

(defn all-documents-ascending-by-modification [elastic modified-since from size]
  (find-modified-documents  elastic modified-since nil from size "asc" nil false source-with-id))

(defn find-public-documents [elastic organization modified-since from size]
  (find-modified-documents elastic modified-since nil from size "desc" organization :public-only? source-without-bucket))

(defn find-all-documents [elastic organization modified-since modified-before from size]
  (find-modified-documents elastic modified-since modified-before from size "desc" organization false source-without-bucket))

(defn find-all-applications
  "Returns an aggregate of all the unique combinations of Lupapiste ID, backend ID, property ID and archivist"
  [elastic organization start-date end-date]
  (let [source (fn [k path] {k {:terms {:field path :missing_bucket true}}})]
    (loop [results []
           params  {:size  0 ; ignore hits, return just the aggregation results for performance
                    :query {:bool {:filter [{:term {:organization organization}}
                                            {:range {:metadata.arkistointipvm {:gte (parse-date start-date)
                                                                               :lte (parse-date end-date)}}}]}}
                    :aggs  {:results {:composite {:size    50000
                                                  :sources [(source :application-id "metadata.applicationId")
                                                            (source :backend-id "metadata.kuntalupatunnukset")
                                                            (source :property-id "metadata.propertyId")
                                                            (source :archiver "metadata.arkistoija.username")]}}}}]
      (let [batch-response              (esd/search elastic params)
            {:keys [buckets after_key]} (get-in batch-response [:body :aggregations :results])]
        (if (seq buckets)
          (recur (concat results (map :key buckets))
                 (assoc-in params [:aggs :results :composite :after] after_key))
          results)))))

(defn refresh-index [elastic]
  (esd/refresh elastic))
