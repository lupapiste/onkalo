(ns helper
  (:require [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [onkalo.storage.document :as document]
            [reloaded.repl :refer [system]]
            [search-commons.shared-utils :as util])
  (:import [java.util UUID]))

(def config system)

(def example-types [:paapiirustus.asemapiirros
                    :rakennuspaikan_hallinta.rasitesopimus
                    :erityissuunnitelmat.iv_suunnitelma
                    :erityissuunnitelmat.pohjarakennesuunnitelma
                    :rakennuspaikka.selvitys_rakennuspaikan_korkeusasemasta
                    :osapuolet.patevyystodistus
                    :selvitykset.maalampo_porausraportti
                    :muut.sijoituslupaasiakirja
                    :muut.muu
                    :case-file
                    :katselmukset_ja_tarkastukset.kayttoonottokatselmuksen_poytakirja
                    :erityissuunnitelmat.ikkunadetaljit
                    :ennakkoluvat_ja_lausunnot.ymparistolupa
                    :selvitykset.yhteistilat
                    :yleiset-alueet.tieto-kaivupaikkaan-liittyvista-johtotiedoista
                    :suunnitelmat.opastesuunnitelma
                    :selvitykset.selvitys_rakennuksen_terveellisyydesta
                    :paatoksenteko.hakemus
                    :rakennuspaikka.ote_kiinteistorekisteristerista
                    :paatoksenteko.ilmoitus
                    :pelastusviranomaiselle_esitettavat_suunnitelmat.merkki_ja_turvavalaistussuunnitelma
                    :paatoksenteko.paatosehdotus
                    :rakennuspaikka.rakennusoikeuslaskelma
                    :suunnitelmat.hankeselvitys
                    :paapiirustus.muu_paapiirustus
                    :rakennuspaikan_hallinta.todistus_erityisoikeuden_kirjaamisesta
                    :selvitykset.meluselvitys])


(def document-metadata {:type               :muut.muu
                        :applicants         ["Hakija"]
                        :operations         [:aita]
                        :tosFunction        {:name "funktio" :code "koodi"}
                        :kuntalupatunnukset ["ktunnus"]
                        :tiedostonimi       "dokumentti.pdf"
                        :kieli              :fi
                        :versio             "1.0"
                        :julkisuusluokka    :julkinen
                        :sailytysaika       {:arkistointi :toistaiseksi
                                             :perustelu   "jees"
                                             :laskentaperuste :rakennuksen_purkamispäivä}
                        :henkilotiedot      :ei-sisalla
                        :tila               :valmis
                        :myyntipalvelu      true
                        :nakyvyys           :julkinen
                        :kayttotarkoitukset ["ei tiedossa"]})

(defn metadata [options]
  (let [{:keys [metadata errors]} (#'document/validate-metadata (merge document-metadata
                                                                       options)
                                    nil)]
    (when (seq errors)
      (println (ex-info "Bad metadata" errors)))
    metadata))

(defn upload-file [options filepath]
  (let [metadata (metadata options)]
    (if metadata
      (#'document/upload-valid-doc! config
                                    (:organization options)
                                    (str (UUID/randomUUID))
                                    false
                                    (io/file filepath)
                                    "application/pdf"
                                    metadata)
      (println "Error or metadata empty. Skipping..."))))

(defn import-json
  "Reads an application JSON file from given `filepath` and returns 'metadata friendly' maps.

  Studio 3T notes: When exporting either select minimal projection (see select-keys below) or choose
  the mongoexport format option. Multiple applications must be exported as a document array."
  [filepath]
  (-> (slurp filepath)
      (json/read-str :key-fn keyword)
      (->> (map #(select-keys % [:_id :address :organization :municipality
                                 :propertyId :location-wgs84]))
           (map #(set/rename-keys % {:_id :applicationId})))))

(defn filter-columns [allowed-columns header]
  (filter (set allowed-columns) header))

(defn csv-data->maps [[header & rows] & {:keys [transform-header]
                                         :or {transform-header identity}}]
  (map zipmap (repeat (transform-header header)) rows))

(defn transform-metadata [{:keys [propertyId nationalBuildingIds] :as metadata-map}]
  (cond-> metadata-map
    propertyId          (assoc :propertyId (util/->db-property-id propertyId))
    nationalBuildingIds (assoc :nationalBuildingIds [nationalBuildingIds])))

(defn take-keys [maps ks]
  (map (fn [m] (select-keys m ks)) maps))

(defn import-csv
  "Reads CSV file from given `filepath` and returns 'metadata friendly' maps."
  [filepath & {:keys [transform-metadata-map]
               :or {transform-metadata-map transform-metadata
                    }}]
  (with-open [reader (io/reader filepath)]
    (let [csv-data (doall (csv/read-csv reader))
          header-fn (fn [header] (map keyword header))
          metadata-maps (-> (csv-data->maps csv-data :transform-header header-fn)
                            (take-keys [:applicationId :municipality :organization :address :propertyId :nationalBuildingIds]))
          result (map transform-metadata-map metadata-maps)]
      result)))

(defn upload-json
  "Uploads documents for JSON application data.

  `json-path` refers to file than contains application document(s). See `import-json` details.

  `pdf-path` refers to a pdf file to be uploaded. The same file is uploaded for every application.

  `options` any metadata options."
  ([json-path pdf-path options]
   (doseq [opts (import-json json-path)]
     (upload-file (merge opts options) pdf-path)))
  ([json-path pdf-path]
   (upload-json json-path pdf-path nil)))

(defn upload-test-files-with-metadata!
  "Uploads documents with metadata from file(s)

  `json-path` refers to file that contains application document(s). See `import-json` details.
  `csv-path`  refers to file that contains application entries (or more specifically whatever data that passes as document metadata). See `import-csv` details.
  `pdf-path`  refers to a pdf file to be uploaded. The same file is uploaded for every metadata entry.
  `options`   any metadata options. This is added to the metadata of each file.

   Consider adding at least paatospvm. Some onkalo operations expect it.
   Example (obviously replace ' ' with double quotes):
   This example sets the same coordinates for all entries. Naturally you can also import them from the CSV per entry.

   (upload-test-files-with-metadata! '/path/to/document.pdf'
                                  {:csv-path 'path/to/metadata.csv'
                                   :options {:type :paapiirustus.asemapiirros
                                             :paatospvm '2016-12-30T12:00:00.000Z'
                                             :lupapvm   '2016-12-30T12:00:00.000Z'
                                             :location-etrs-tm35fin [397076.183311 6691762.327002]}}))
"
  ([pdf-path {:keys [json-path csv-path options]}]
   (let [json-metadatas (if json-path (import-json json-path))
         csv-metadatas  (if csv-path  (import-csv csv-path))
         metadatas (concat json-metadatas csv-metadatas)]
     ;; Parallelize by map -> pmap to reduct time when running a large dataset. Parallel doseq does not exist out-of-the-box, hence the map.
     ;; A word of warning though, when uploading in parallel for some reason about 10%
     ;; of the pdf:s got corrupted (invalid PDF signature error or similar)
     (doall (map (fn [file-metadata n]
                   (upload-file (merge file-metadata options) pdf-path)
                   (if (= 0 (rem n 100)) (println "###############  COUNT: " n " ###########"))) ;;Poor man's status indicator
                 metadatas
                 (range))))))
