(ns onkalo.component.elastic-test
  (:require [clojure.test :refer :all]
            [test-util.elastic-fixture :as ef]
            [onkalo.component.elastic :as e]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.boundary.elastic-document-api :refer [refresh]]
            [com.stuartsierra.component :refer [start stop]]
            [clojure.data]))

(def bucket "test-bucket")
(def content-type "application/pdf")
(def organization "186-R")

(def base-metadata {:type                  "paapiirustus.asemapiirros"
                    :applicationId         "LP-1234"
                    :buildingIds           ["bid1"]
                    :nationalBuildingIds   ["national-bid1"]
                    :propertyId            "18600101140005"
                    :applicants            ["Maverick" "Goose"]
                    :operations            ["kerrostalo-rivitalo"]
                    :tosFunction           {:name "Rakennuslupamenettely" :code "10 03 00 01"}
                    :address               "Seutulantie 10"
                    :organization          organization
                    :municipality          "186"
                    :location-etrs-tm35fin [394801.203 6704866.824]
                    :location-wgs84        [25.0868 60.46625]
                    :drawing-wgs84         [{:type "Polygon"
                                             :coordinates [[[25.09525837049 60.488495242537]
                                                            [25.094953876678 60.487790364259]
                                                            [25.097550912364 60.487890298721]
                                                            [25.097304587279 60.488677125947]
                                                            [25.097280399964 60.489089913559]
                                                            [25.09525837049 60.488495242537]]]}]
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
                    :projectDescription    "Rakennetaan erittäinen komea talo"
                    :julkisuusluokka       "julkinen"
                    :sailytysaika          {:arkistointi "ikuisesti"
                                            :perustelu   "perustelu"}
                    :henkilotiedot         "ei-sisalla"
                    :tila                  "arkistoitu"
                    :nakyvyys              "julkinen"
                    :myyntipalvelu         true})

(def id "test1")
(def id2 "test2")
(def spaced-id "test-fi%2520le-8 + %50 str%20an%2Bge")
(def sha256-sum "sha256-sum")


(deftest reindexing-works
  (let [elastic (ef/start-es-component!)
        next-index-version (inc e/index-version)
        expected-md (-> base-metadata
                        (merge {:arkistointipvm "2017-04-03T21:00:00.000Z",
                                :paatospvm "2017-01-03T21:00:00.000Z",
                                :lupapvm "2017-01-04T21:00:00.000Z",
                                :closed "2017-01-19T21:00:00.000Z"
                                :location-docstore {:type "point" :coordinates [25.0868 60.46625]}
                                :location-wgs84 [{:type "point" :coordinates [25.0868 60.46625]}
                                                 {:type "Polygon" :coordinates [[[25.09525837049 60.488495242537]
                                                                                 [25.094953876678 60.487790364259]
                                                                                 [25.097550912364 60.487890298721]
                                                                                 [25.097304587279 60.488677125947]
                                                                                 [25.097280399964 60.489089913559]
                                                                                 [25.09525837049 60.488495242537]]]}]})
                        (dissoc :organization :drawing-wgs84))]
    (ea/persist-document-to-index elastic
                                  bucket
                                  id
                                  sha256-sum
                                  content-type
                                  :gcs
                                  base-metadata)

    (ea/persist-document-to-index elastic
                                  bucket
                                  spaced-id
                                  sha256-sum
                                  content-type
                                  :gcs
                                  base-metadata)
    (refresh elastic)
    (stop elastic)
    (reset! ef/es-component nil)
    (with-redefs
      ; This initiates reindexing
      [onkalo.component.elastic/index-version next-index-version]
      (let [elastic2 (ef/start-es-component!)]
        ; Reindexing is async
        (Thread/sleep 2000)
        (ea/persist-document-to-index elastic2
                                      bucket
                                      id2
                                      sha256-sum
                                      content-type
                                      :gcs
                                      base-metadata)

        ; Make sure reindexing an id with spaces still matches
        (ea/persist-document-to-index elastic2
                                      bucket
                                      spaced-id
                                      sha256-sum
                                      content-type
                                      :gcs
                                      base-metadata)

        (refresh elastic2)
        (is (=  expected-md (:metadata (ea/get-document elastic2 bucket id))))
        (is (= 3 (-> (ea/find-documents elastic2 organization {:all "asemasta"} 0 1000 false)
                     :meta
                     :count)))
        (e/destroy-index! elastic2)
        (stop elastic2)
        (reset! ef/es-component nil)))
    (let [elastic3 (ef/start-es-component!)]
      (e/add-aliases-to-idx! elastic3)
      ; The old index should still have 2 documents
      (is (= 2 (-> (ea/find-documents elastic3 organization {:all "asemasta"} 0 1000 false)
                   :meta
                   :count)))
      (e/destroy-index! elastic3)
      (stop elastic3)
      (reset! ef/index-name nil)
      (reset! ef/es-component nil))))
