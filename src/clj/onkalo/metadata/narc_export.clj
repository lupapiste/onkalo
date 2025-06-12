(ns onkalo.metadata.narc-export
  (:require [clojure.data.zip.xml :as zip-xml]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.xml-utils :as xml-utils]
            [onkalo.storage.document :as document]
            [potpuri.core :as p]
            [taoensso.timbre :as timbre])
  (:import [java.io ByteArrayInputStream]
           [java.time ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

(def lp-ns "xmlns:lp=\"http://www.lupapiste.fi/onkalo/schemas/sahke2-case-file/2016/6/1\"")

(def narc-ns "xmlns:narc=\"http://www.arkisto.fi/skeemat/Sahke2/2019/08/29\"")

(def invalid-retention-period "<narc:RetentionPeriod>999999</narc:RetentionPeriod>")
(def correct-retention-period "<narc:RetentionPeriod>-1</narc:RetentionPeriod>")

(defn- tag= [node tag]
  (-> node :tag (= tag)))

(def xml-date-formatter (.withZone DateTimeFormatter/ISO_DATE (ZoneId/of "Europe/Helsinki")))

(defn- format-xml-date [iso-ts-string]
  (-> (ZonedDateTime/parse iso-ts-string)
      (.format xml-date-formatter)))

(defn- publicity-class [{:keys [julkisuusluokka]}]
  {:tag     :narc:PublicityClass
   :content [(case (keyword julkisuusluokka)
               :julkinen "Julkinen"
               :osittain-salassapidettava "Osittain salassapidettävä"
               :salainen "Salassa pidettävä")]})

(defn- security-period [{:keys [salassapitoaika]}]
  (when salassapitoaika
    {:tag     :narc:SecurityPeriod
     :content [salassapitoaika]}))

(defn- security-period-end [{:keys [security-period-end]}]
  (when security-period-end
    {:tag     :narc:SecurityPeriodEnd
     :content [(format-xml-date security-period-end)]}))

(defn- security-reason [{:keys [salassapitoperuste]}]
  (when salassapitoperuste
    {:tag     :narc:SecurityReason
     :content [salassapitoperuste]}))

(defn- protection-level [{:keys [suojaustaso]}]
  (when suojaustaso
    {:tag     :narc:ProtectionLevel
     :content [(case (keyword suojaustaso)
                 :ei-luokiteltu "-"
                 :suojaustaso4 "IV"
                 :suojaustaso3 "III"
                 :suojaustaso2 "II"
                 :suojaustaso1 "I")]}))

(defn- security-class [{:keys [turvallisuusluokka]}]
  (when turvallisuusluokka
    {:tag     :narc:SecurityClass
     :content [(case (keyword turvallisuusluokka)
                 :ei-turvallisuusluokkaluokiteltu "Ei turvallisuusluokiteltu"
                 :turvallisuusluokka4 "Turvallisuusluokka IV"
                 :turvallisuusluokka3 "Turvallisuusluokka III"
                 :turvallisuusluokka2 "Turvallisuusluokka II"
                 :turvallisuusluokka1 "Turvallisuusluokka I")]}))

(defn- personal-data [{:keys [henkilotiedot]}]
  {:tag     :narc:PersonalData
   :content [(case (keyword henkilotiedot)
               :ei-sisalla "ei sisällä henkilötietoja"
               :sisaltaa "sisältää henkilötietoja"
               :sisaltaa-arkaluonteisia "sisältää arkaluontoisia henkilötietoja")]})

(defn- restriction-element [{:keys [julkisuusluokka] :as metadata}]
  {:tag     :narc:Restriction
   :content (concat [(publicity-class metadata)]
                    (when (not= (keyword julkisuusluokka) :julkinen)
                      (->> [(security-period metadata)
                            (security-period-end metadata)
                            (security-reason metadata)
                            (protection-level metadata)
                            (security-class metadata)]
                           (remove nil?)))
                    [(personal-data metadata)])})

(defn- retention-period [{{:keys [pituus arkistointi]} :sailytysaika}]
  {:tag     :narc:RetentionPeriod
   :content [(if (#{:ikuisesti :toistaiseksi} (keyword arkistointi))
               -1
               pituus)]})

(defn- retention-reason [{{:keys [perustelu]} :sailytysaika}]
  {:tag     :narc:RetentionReason
   :content [perustelu]})

(defn- insert-retention-period-end [loc {{:keys [retention-period-end]} :sailytysaika}]
  (if retention-period-end
    (-> (zip/insert-right loc {:tag     :narc:RetentionPeriodEnd
                               :content [(format-xml-date retention-period-end)]})
        (zip/right))
    loc))

(defn- status [{:keys [tila]}]
  {:tag     :narc:Status
   :content [tila]})

(defn- tos-function [{:keys [tosFunction]}]
  {:tag     :narc:Function
   :content [(:code tosFunction)]})

(defn- authenticity-element [{:keys [arkistointipvm]}]
  {:tag     :narc:Authenticity
   :content [{:tag     :narc:Checker
              :content ["Lupapiste"]}
             {:tag     :narc:Date
              :content [(format-xml-date arkistointipvm)]}
             {:tag     :narc:Description
              :content ["asiakirjan alkuperäisyys todettu"]}]})

(defn- export-file-name [{:keys [fileId contentType]}]
  (case contentType
    "application/pdf" (str fileId ".pdf")
    "image/tiff" (str fileId ".tif")
    "application/xml" (str fileId ".xml")
    (str fileId ".data")))

(defn- document-element [{:keys [fileId contentType sha256] :as attachment}]
  {:tag     :narc:Document
   :content [{:tag     :narc:NativeId
              :content [fileId]}
             {:tag     :narc:UseType
              :content ["Arkisto"]}
             {:tag     :narc:File
              :content [{:tag     :narc:Name
                         :content [(export-file-name attachment)]}
                        {:tag     :narc:Path
                         :content ["./"]}]}
             ;; Note that we break the SÄHKE2 transfer package rules there, because we cannot resolve the values without  reading the actual files.
             ;; See https://arkisto.fi/uploads/Viranomaisille/S%C3%A4hk%C3%B6isen%20arkistoinnin%20palvelu/SAPA-ohjeet/S%C3%A4hke2-aineistot%20-%20siirtopaketin%20muodostaminen%201.0.pdf
             ;; and https://arkisto.fi/uploads/Viranomaisille/Digitaalisten%20aineistojen%20s%C3%A4ilytt%C3%A4mispalvelu/Digitaaliseen%20s%C3%A4ilytt%C3%A4miseen%20hyv%C3%A4ksytt%C3%A4v%C3%A4t%20tiedostoformaatit_ohje.pdf
             ;; The actual spec is basically like this:
             ;; Format.Name should be the PRONOM format as defined by UK National Archives:
             ;; PDF/A-1a; PRONOM: fmt/95
             ;; PDF/A-1b; PRONOM: fmt/354
             ;; PDF/A-2a; PRONOM: fmt/476
             ;; PDF/A-2b; PRONOM: fmt/477
             ;; PDF/A-2u; PRONOM: fmt/478
             ;; PDF/A-3a; PRONOM: fmt/479
             ;; PDF/A-3b; PRONOM: fmt/480
             ;; PDF/A-3u; PRONOM: fmt/481
             ;; while Format.Version should be the version part of the PDF/A standard, i.e.
             ;; PDF/A-2b -> version is "2b"
             {:tag     :narc:Format
              :content [{:tag     :narc:Name
                         :content [contentType]}
                        {:tag     :narc:Version
                         :content ["-"]}]}
             ;; Older files do not have sha256 calculated, as this was never back-migrated to those
             ;; These would have to be calculated to meet actual SÄHKE2 spec
             {:tag     :narc:HashAlgorithm
              :content [(if sha256 "sha256" "")]}
             {:tag     :narc:HashValue
              :content [(or sha256 "")]}]})

(defn- update-record [attachments node]
  (if (tag= node :narc:Record)
    (let [native-id-node (zip-xml/xml1-> (zip/xml-zip node) :narc:NativeId)
          att-id         (zip-xml/text native-id-node)
          attachment     (get attachments att-id)
          {:keys [kieli] :as metadata} (:metadata attachment)]
      (if (seq metadata)
        ;; This is a bit fragile way to navigate the XML tree, but because the order of elements is sequential in schema
        ;; and we know our data, we should be able to pretty reliably fill in the missing elements.
        (-> (zip/insert-right native-id-node {:tag     :narc:Language
                                              :content [kieli]})
            (zip/up)
            (zip-xml/xml1-> :narc:Title)
            (zip/left)
            (zip/insert-right (restriction-element metadata))
            (zip/right)
            (zip/right)
            (zip/insert-right (retention-period metadata))
            (zip/right)
            (zip/insert-right (retention-reason metadata))
            (zip/right)
            (insert-retention-period-end metadata)
            (zip/insert-right (status metadata))
            (zip/right)
            (zip/insert-right (tos-function metadata))
            (zip/right)
            (zip/right)
            (zip/insert-right (authenticity-element metadata))
            (zip/up)
            (zip-xml/xml1-> :narc:Agent)
            (zip/left)
            (zip/insert-right (document-element attachment))
            (zip/root))
        ;; It's possible that the case-file includes a document that is not actually in the archive, so we must remove
        ;; the element to produce a valid XML
        (timbre/warn "Attachment not found with id" att-id)))
    node))

(defn- update-action [attachments node]
  (if (tag= node :narc:Action)
    (update node :content #(keep (partial update-record attachments) %))
    node))

(defn sahke2-case-file [{:keys [elastic] :as config} organization application-id]
  (p/if-all-let [id      (str application-id "-case-file-xml")
                 doc     (try (document/get-document config id organization)
                              (catch Exception _))
                 xml-str (slurp ((:content-fn doc)))]
    (try
      (let [attachments (->> (ea/find-documents elastic
                                                organization
                                                {:applicationId application-id}
                                                0
                                                10000
                                                nil
                                                nil
                                                false)
                             :results
                             (reduce #(assoc %1 (:fileId %2) %2) {}))
            root        (-> xml-str
                            (str/replace lp-ns narc-ns)
                            (str/replace "</lp:" "</narc:")
                            (str/replace "<lp:" "<narc:")
                            (str/replace invalid-retention-period correct-retention-period)
                            (.getBytes "UTF-8")
                            (ByteArrayInputStream.)
                            (xml/parse xml-utils/startparse-sax-no-doctype))]
        {:status  200
         :headers {"content-type" "text/xml"}
         :body    (->> (update root :content #(mapv (partial update-action attachments) %))
                       (xml-utils/element->string-builder! (StringBuilder.))
                       (.toString))})
      (catch Throwable t
        (timbre/error t "Cannot export Sähke2 case file for application" application-id)
        {:status  500
         :headers {"content-type" "text/plain"}
         :body    (.getMessage t)}))
    {:status  404
     :headers {"content-type" "text/plain"}
     :body    (str "No case-file found for application id " application-id)}))
