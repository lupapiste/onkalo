(ns onkalo.metadata.narc-export-test
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.narc-export :as narc-export]
            [test-util.elastic-fixture :as ef]))

(def organization "091-R")
(def application-id "LP-091-2021-90002")

(def base-metadata {:type                  :paapiirustus.asemapiirros
                    :applicationId         application-id
                    :buildingIds           ["bid1"]
                    :nationalBuildingIds   ["national-bid1"]
                    :propertyId            "09100101140005"
                    :applicants            ["Maverick" "Goose"]
                    :operations            [:kerrostalo-rivitalo]
                    :tosFunction           {:name "Rakennuslupamenettely" :code "10 03 00 01"}
                    :address               "Seutulantie 10"
                    :organization          organization
                    :municipality          "091"
                    :location-etrs-tm35fin [394801.203 6704866.824]
                    :location-wgs84        [25.0868 60.46625]
                    :kuntalupatunnukset    ["kuntatunnus"]
                    :lupapvm               #inst"2017-01-05T00:00:00.000+03:00"
                    :paatospvm             #inst"2017-01-04T00:00:00.000+03:00"
                    :arkistointipvm        #inst"2017-04-04T00:00:00.000+03:00"
                    :paatoksentekija       "Pertti Päättäjä"
                    :tiedostonimi          "asemapiirros.pdf"
                    :kasittelija           {:userId "foo" :firstName "foo" :lastName "bar"}
                    :arkistoija            {:userId "foo" :firstName "foo" :lastName "bar"}
                    :kayttotarkoitukset    ["011 yhden asunnon talot"]
                    :kieli                 "fi"
                    :versio                "1.5"
                    :suunnittelijat        ["Seppo Suunnittelija"]
                    :foremen               "Teppo Työnjohtaja"
                    :contents              "Hieno piirros asemasta"
                    :tyomaasta-vastaava    "Timo Työmaavastaava"
                    :closed                #inst"2017-01-20T00:00:00.000+03:00"
                    :projectDescription    "Rakennetaan erittäinen komea talo"})

(def public-s2-metadata {:julkisuusluokka :julkinen
                         :sailytysaika    {:arkistointi :ikuisesti
                                           :perustelu   "perustelu"}
                         :henkilotiedot   :ei-sisalla
                         :kieli           :fi
                         :tila            :arkistoitu
                         :nakyvyys        :julkinen
                         :myyntipalvelu   true})

(def secret-s2-metadata (merge public-s2-metadata
                               {:julkisuusluokka     :salainen
                                :salassapitoaika     50
                                :security-period-end #inst"2067-01-04T00:00:00.000+03:00"
                                :turvallisuusluokka  :turvallisuusluokka4}))

(def id1 "test-file-1")
(def id2 "test-file-2")

(defn persist [md id]
  (let [c  (ef/get-client)
        id (ea/persist-document-to-index c
                                         "test-bucket"
                                         id
                                         "sha256-sum"
                                         "application/pdf"
                                         :gcs
                                         md)]
    (ea/refresh-index c)
    id))

(defn insert-documents [f]
  (persist (merge base-metadata public-s2-metadata) id1)
  (persist (merge base-metadata secret-s2-metadata) id2)
  (f))

(t/use-fixtures :once ef/elastic-fixture insert-documents)


(t/deftest case-file-is-converted-to-sahke2-schema
  (with-redefs
    [onkalo.storage.document/get-document (fn [_ doc-id org]
                                            (when (and (= doc-id (str application-id "-case-file-xml"))
                                                       (= org organization))
                                              {:content-fn (fn []
                                                             (io/input-stream (io/file "test/original-case-file.xml")))}))]
    (let [expected-content (slurp (io/file "test/sahke2-case-file.xml"))]
      (t/is (= (:body (narc-export/sahke2-case-file {:elastic (ef/get-client)} organization application-id))
               expected-content)
            "Exported Sähke2 case file XML matches expected data"))))
