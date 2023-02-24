(ns onkalo.metadata.elastic-scroll
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [potpuri.core :as p]
            [qbits.spandex :as s])
  (:import [java.io BufferedOutputStream PipedInputStream PipedOutputStream]
           [java.time ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def field-delimiter ";")
(def line-break "\n")
(def quote "\"")
(def escaped-quote (str quote quote))

(defn escape-field
  ([content] (escape-field false content))
  ([force? content]
   (let [s       (str content)
         escape? (or (str/includes? s field-delimiter)
                     (str/includes? s line-break)
                     (str/includes? s quote)
                     (str/includes? s "'")
                     force?)
         s       (str/replace s quote escaped-quote)]
     (str (when escape? quote) (if (str/blank? s) "-" s) (when escape? quote)))))

(defn tr-fn [translations]
  (fn [key]
    (get-in translations [:data :fi key])))

(defn format-date [v]
  (when v
    (try
      (-> (ZonedDateTime/parse v DateTimeFormatter/ISO_OFFSET_DATE_TIME)
          (.withZoneSameInstant (ZoneId/of "Europe/Helsinki"))
          (.toLocalDate))
      (catch Exception _
        v))))

(defn scroll-query-as-stream
  [{{:keys [es-client read-alias]} :elastic} query-map header-row es-hit->string]
  (let [ch           (s/scroll-chan es-client {:url  [read-alias :_search]
                                               :body query-map
                                               :ttl  "5m"})
        in           (PipedInputStream.)
        out          (PipedOutputStream. in)
        buffered-out (BufferedOutputStream. out 1048576)]
    (async/go
      (.write buffered-out (.getBytes header-row))
      (loop []
        (when-let [page (async/<! ch)]
          (->> page
               :body
               :hits
               :hits
               (run! (fn [hit]
                       (->> (es-hit->string hit)
                            .getBytes
                            (.write buffered-out))
                       (.flush buffered-out))))
          (recur)))
      (.close buffered-out)
      (.close out))
    in))

(defn find-imported-documents-for-organization [{:keys [translations] :as config} organization]
  (let [tr (tr-fn translations)]
    (scroll-query-as-stream config
                            {:query   {:bool {:filter   [{:term {:organization organization}}]
                                              :must_not [{:exists {:field "metadata.applicationId"}}
                                                         {:wildcard {:fileId "5???????????????????????"}}
                                                         {:wildcard {:fileId "6???????????????????????"}}]}}
                             :size    5000
                             :_source ["metadata.paatospvm"
                                       "metadata.tiedostonimi"
                                       "metadata.type"
                                       "metadata.projectDescription"]}
                            (str (->> ["Tiedostonimi" "Liitteen tyyppi" "Päätöspäivämäärä" "Hankkeen kuvaus"]
                                      (str/join field-delimiter))
                                 "\n")
                            (fn [{{{:keys [paatospvm tiedostonimi type projectDescription]} :metadata} :_source}]
                              (-> (->> [tiedostonimi
                                        (tr (str "attachmentType." type))
                                        (format-date paatospvm)
                                        projectDescription]
                                       (map escape-field)
                                       (str/join field-delimiter))
                                  (str "\n"))))))

(defn find-all-documents-for-organization [{:keys [translations] :as config} organization]
  (let [tr          (tr-fn translations)
        cols        ["address"
                     "applicationId"
                     "arkistointipvm"
                     "contents"
                     "deleted"
                     "henkilotiedot"
                     "history"
                     "julkisuusluokka"
                     "kayttotarkoitukset"
                     "kuntalupatunnukset"
                     "location-etrs-tm35fin"
                     "location-wgs84"
                     "myyntipalvelu"
                     "nakyvyys"
                     "nationalBuildingIds"
                     "operations"
                     "paatoksentekija"
                     "paatospvm"
                     "propertyId"
                     "paatospvm"
                     "projectDescription"
                     "tiedostonimi"
                     "tila"
                     "tosFunction.code"
                     "tosFunction.name"
                     "turvallisuusluokka"
                     "type"
                     "versio"]
        date-field? #{"arkistointipvm"
                      "deleted"
                      "paatospvm"}]
    (scroll-query-as-stream config
                            {:query   {:bool {:filter [{:term {:organization organization}}]}}
                             :size    5000
                             :_source (conj (->> cols
                                                 (mapv #(str "metadata." %)))
                                            "fileId")}
                            (str (->> (concat cols
                                              ["Dokumentin alkuperä"
                                               "ID"])
                                      (str/join field-delimiter))
                                 "\n")
                            (fn [{{{:keys [applicationId] :as metadata} :metadata id :fileId} :_source}]
                              (let [source (cond
                                             applicationId "Lupapiste"
                                             (and id (re-matches #"5[\w\d]{23}" id)) "Digitoijan työpöytä"
                                             :else "Rajapintatuonti")]
                                (-> (->> cols
                                         (map (fn [str-k]
                                                (let [k (if (str/starts-with? str-k "tosFunction")
                                                          (->> (str/split str-k #"\.")
                                                               (mapv keyword))
                                                          [(keyword str-k)])
                                                      v (get-in metadata k)]
                                                  (cond
                                                    (date-field? str-k) (format-date v)
                                                    (= str-k "type") (tr (str "attachmentType." v))
                                                    (= str-k "location-wgs84") (->> (p/find-first v {:type "point"})
                                                                                    :coordinates
                                                                                    (str/join ","))
                                                    (= str-k "operations") (->> v
                                                                                (map #(tr (str "operations." %)))
                                                                                (str/join ", "))
                                                    (sequential? v) (str/join ", " v)
                                                    (coll? v) (pr-str v)
                                                    :else v))))
                                         (map escape-field)
                                         (str/join field-delimiter))
                                    (str field-delimiter source field-delimiter id "\n")))))))
