(ns onkalo.component.elastic
  (:require [qbits.spandex :as s]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [onkalo.metadata.elastic-migrations :as migrations]
            [clojure.core.async :as async])
  (:import [org.elasticsearch.client RestClient]))

(def index-version 29) ;; Upgrade version for automatic reindex. Changing existing fields requires additional migration code.

(def max-result-window 1000000) ; Default for ES in only 10000. Large window consumes memory and performance, but seems to work for us.

(def user-data-properties {:properties {:userId    {:type "keyword" :normalizer "lowercase"}
                                        :username  {:type "keyword" :normalizer "lowercase"}
                                        :firstName {:type "keyword" :normalizer "lowercase"}
                                        :lastName  {:type "keyword" :normalizer "lowercase"}}})

(def searchable-properties
  {:contents              {:type "keyword" :normalizer "lowercase"}
   :size                  {:type "keyword"}
   :scale                 {:type "keyword"}
   :buildingIds           {:type "keyword"}
   :nationalBuildingIds   {:type "keyword"}
   :propertyId            {:type "keyword"}
   :projectDescription    {:type "text" :analyzer "standard"}
   :applicants            {:type "keyword" :normalizer "lowercase"}
   :tosFunction           {:properties {:name {:type "keyword" :normalizer "lowercase"}
                                        :code {:type "keyword"}}}
   :address               {:type "keyword" :normalizer "lowercase"}
   :municipality          {:type "keyword" :normalizer "lowercase"}
   :postinumero           {:type "keyword"}
   :kuntalupatunnukset    {:type "keyword"}
   :lupapvm               {:type "date"}
   :paatoksentekija       {:type "keyword" :normalizer "lowercase"}
   :tiedostonimi          {:type "keyword"}
   :kasittelija           user-data-properties
   :arkistoija            user-data-properties
   :arkistointipvm        {:type "date"}
   :suunnittelijat        {:type "keyword" :normalizer "lowercase"}
   :foremen               {:type "keyword" :normalizer "lowercase"}
   :tyomaasta-vastaava    {:type "keyword" :normalizer "lowercase"}
   :versio                {:type "keyword"}
   :kylanumero            {:type "keyword"}
   :kylanimi              {:properties {:fi {:type "keyword" :normalizer "lowercase"}
                                        :sv {:type "keyword" :normalizer "lowercase"}}}})

(def non-searchable-properties
  {:location-etrs-tm35fin        {:type "double"}
   :ramLink                      {:type "keyword"}
   :history                      {:properties {:modified {:type "date"}
                                               :user     user-data-properties
                                               :field    {:type "keyword" :index false}
                                               :old-val  {:type "keyword" :index false}
                                               :new-val  {:type "keyword" :index false}}}
   :deletion-explanation         {:type "keyword"}
   :permit-expired               {:type "boolean"}
   :permit-expired-date          {:type "date"}
   :demolished                   {:type "boolean"}
   :demolished-date              {:type "date"}
   :kuntalupatunnukset-muutossyy {:type "keyword"}
   ;; :organization has been erroneously duplicated under the :metadata key through frontend-updates / mass-updates
   ;; It can be removed from mapping if all documents are migrated so that metadata.organization is removed from them.
   :organization                 {:type "keyword"}})

(def limiting-properties
  {:location-wgs84     {:type "geo_shape"}
   :location-docstore  {:type "geo_shape"}
   :type               {:type "keyword" :normalizer "lowercase"}
   :paatospvm          {:type "date"}
   :jattopvm           {:type "date"}
   :closed             {:type "date"}
   :operations         {:type "keyword" :normalizer "lowercase"}
   :kayttotarkoitukset {:type "keyword" :normalizer "lowercase"}
   :deleted            {:type "date"}
   :propertyId         {:type "keyword"}
   :rakennusluokat     {:type "keyword" :normalizer "lowercase"}
   :kuntalupatunnukset {:type "keyword"}
   :applicationId      {:type "keyword"}})

(def sahke2-properties
  {:julkisuusluokka       {:type "keyword"}
   :salassapitoaika       {:type "integer"}
   :salassapitoperuste    {:type "keyword"}
   :turvallisuusluokka    {:type "keyword"}
   :suojaustaso           {:type "keyword"}
   :kayttajaryhma         {:type "keyword"}
   :kayttajaryhmakuvaus   {:type "keyword"}
   :sailytysaika          {:properties {:arkistointi          {:type "keyword"}
                                        :perustelu            {:type "keyword"}
                                        :laskentaperuste      {:type "keyword"}
                                        :pituus               {:type "integer"}
                                        :retention-period-end {:type "date"}}}
   :henkilotiedot         {:type "keyword"}
   :kieli                 {:type "keyword"}
   :tila                  {:type "keyword"}
   :myyntipalvelu         {:type "boolean"}
   :nakyvyys              {:type "keyword"}
   :security-period-end   {:type "date"}})

(def document-index-mapping
  {:properties {:source       {:type "keyword"}
                :fileId       {:type "keyword"}
                :sha256       {:type "keyword"}
                :organization {:type "keyword"}
                :bucket       {:type "keyword"}
                :contentType  {:type "keyword"}
                :modified     {:type "date"}
                :storage      {:type "keyword"}
                :metadata     {:properties (merge searchable-properties
                                                  non-searchable-properties
                                                  limiting-properties
                                                  sahke2-properties)}}
   :dynamic    "strict"})

(def es-settings
  {:analysis {:normalizer {:lowercase {:type      "custom"
                                       :filter    ["lowercase"]}}}})

(defn- version->index [index-name version]
  (str index-name "-v" version))

(defn- index-exists? [c idx]
  (try
    (s/request c {:url [idx]
                  :method :head})
    true
    (catch Exception e
      false)))

(defn create-index! [c idx aliases shards replicas]
  (s/request c {:url [idx]
                :method :put
                :body (cond-> {:mappings document-index-mapping
                               :settings (merge es-settings
                                                {:index {:number_of_shards shards
                                                         :number_of_replicas replicas
                                                         :max_result_window max-result-window}})}
                              aliases (assoc :aliases aliases))}))

(defn update-aliases! [c actions]
  (try (s/request c {:url [:_aliases]
                     :method :post
                     :body {:actions actions}})
       (catch Throwable t
         (timbre/error t "Could not update aliases")
         (throw t))))

(defn add-aliases-to-idx! [{:keys [es-client index-name current-index]}]
  (update-aliases! es-client [{:add {:index current-index :alias (str index-name "-read")}}
                              {:add {:index current-index :alias (str index-name "-write")}}]))

(defn update-settings! [c idx settings]
  (s/request c {:url [idx :_settings]
                :method :put
                :body {:index settings}}))

(defn refresh [c idx]
  (s/request c {:url [idx :_refresh]
                :method :post}))

(defn put! [c idx id doc]
  {:pre [(every? some? [c idx id doc])]}
  (s/request c {:url    [idx :_doc id]
                :method :put
                :body   doc}))

(defn delete-index! [c idx]
  (s/request c {:url [idx :_alias "*"]
                :method :delete})
  (s/request c {:url [idx]
                :method :delete}))

(defn- reindex [client old-index new-index read-alias write-alias shards replicas]
  (timbre/info "Index version changed from" old-index "to" new-index "- reindexing")
  (create-index! client new-index nil shards replicas)
  (update-aliases! client [{:remove {:index old-index :alias write-alias}}
                           {:add {:index new-index :alias write-alias}}])
  (update-settings! client new-index {:refresh_interval -1})
  (async/go
    (try
      (let [ch (s/scroll-chan client
                              {:url [old-index :_search]
                               :body {:query {:match_all {}}}})]
        (loop []
          (when-let [page (async/<! ch)]
            (doseq [{document :_source es-id :_id} (-> page :body :hits :hits)]
              (let [updated-doc (migrations/migrate-document index-version document)]
                (put! client new-index es-id updated-doc)))
            (recur)))
        (refresh client new-index)
        (update-settings! client new-index {:refresh_interval "1s"})
        (update-aliases! client [{:remove {:index old-index :alias read-alias}}
                                 {:add {:index new-index :alias read-alias}}])
        (timbre/info "Reindexing ready"))
      (catch Throwable t
        (timbre/error t "Error occurred during reindexing")))))

(defn ^RestClient create-client [hosts cluster-name index-name shards replicas]
  (timbre/info "Connecting to Elasticsearch cluster" cluster-name)
  (let [client (s/client {:hosts hosts})
        current-index (version->index index-name index-version)
        old-indices (map #(version->index index-name %) (range 1 (inc index-version)))
        existing-index (last (filter #(index-exists? client %) old-indices))

        _ (timbre/info "Using index" current-index "| previous index was:" existing-index)
        read-alias (str index-name "-read")
        write-alias (str index-name "-write")]
    (if-not existing-index
      (create-index! client current-index {read-alias {} write-alias {}} shards replicas)
      (when-not (= current-index existing-index)
        (reindex client existing-index current-index read-alias write-alias shards replicas)))
    (timbre/info "Elasticsearch connection ready")
    client))

(defprotocol IndexApi
  (destroy-index! [elastic]))

(defrecord Elasticsearch [hosts cluster-name index-name shards replicas]
  component/Lifecycle
  (start [this]
    (if (:es-client this)
      this
      (assoc this :es-client (create-client hosts cluster-name index-name shards replicas)
                  :current-index (version->index index-name index-version)
                  :read-alias (str index-name "-read")
                  :write-alias (str index-name "-write"))))
  (stop [this]
    (if-let [elastic (:es-client this)]
      (do
        (.close elastic)
        (assoc this :es-client nil))
      this))
  IndexApi
  (destroy-index! [this]
    (let [es-client (:es-client this)
          current-index (:current-index this)]
      (when (and es-client current-index)
        (try
          (delete-index! es-client current-index)
          (catch Throwable t
            (timbre/warn t "Error in destroy-index!")))))))

(defn elasticsearch-component [config]
  (map->Elasticsearch config))
