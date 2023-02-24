(ns onkalo.metadata.elastic-api-test
  (:require [clojure.test :refer :all]
            [test-util.elastic-fixture :as ef]
            [onkalo.metadata.elastic-api :refer :all]
            [schema.core :as s]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [clj-time.core :as t]
            [clj-time.format :as tf]))

(def bucket "test-bucket")
(def content-type "application/pdf")
(def organization "186-R")
(def organization2 "888-R")
(def terminal-organization "123-R")

(def base-metadata {:type                  :paapiirustus.asemapiirros
                    :applicationId         "LP-1234"
                    :buildingIds           ["bid1"]
                    :nationalBuildingIds   ["national-bid1"]
                    :propertyId            "18600101140005"
                    :applicants            ["Maverick" "Goose"]
                    :operations            [:kerrostalo-rivitalo]
                    :tosFunction           {:name "Rakennuslupamenettely" :code "10 03 00 01"}
                    :address               "Seutulantie 10"
                    :organization          organization
                    :municipality          "186"
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
                         :sailytysaika {:arkistointi :ikuisesti
                                        :perustelu "perustelu"}
                         :henkilotiedot :ei-sisalla
                         :kieli :fi
                         :tila :arkistoitu
                         :nakyvyys :julkinen
                         :myyntipalvelu true})

(def secret-s2-metadata (merge public-s2-metadata
                               {:julkisuusluokka :salainen
                                :salassapitoaika 50
                                :security-period-end #inst"2067-01-04T00:00:00.000+03:00"}))

(def partly-secret-s2-metadata (merge public-s2-metadata
                                      {:julkisuusluokka :salainen
                                       :salassapitoaika 50
                                       :security-period-end #inst"2067-01-04T00:00:00.000+03:00"}))

(def non-public-s2-metadata (merge public-s2-metadata
                                   {:contents "foobar barfoo"
                                    :nakyvyys :viranomainen}))

(def non-sellable-s2-metadata (merge public-s2-metadata
                                     {:contents "foobar barfoo"
                                      :myyntipalvelu false}))

(def expired-secret-s2-metadata (merge public-s2-metadata
                                       {:contents "foobar barfoo"
                                        :address "Asemastarbo 57"
                                        :julkisuusluokka :salainen
                                        :salassapitoaika 50
                                        :security-period-end #inst"2017-06-01T00:00:00.000+03:00"}))

(def non-public-s2-metadata-sensitive-person-details (merge public-s2-metadata
                                                            {:henkilotiedot :sisaltaa-arkaluonteisia}))

(def public-s2-metadata-normal-person-details (merge public-s2-metadata
                                                     {:henkilotiedot :sisaltaa}))

(def alternate-location {:location-wgs84 [27.0 63.0]})

(def terminal (merge public-s2-metadata
                     alternate-location
                     {:organization terminal-organization
                      :municipality "123"}))

(def terminal-alternate-municipality-and-type (merge public-s2-metadata
                                                     alternate-location
                                                     {:organization terminal-organization
                                                      :municipality "091"
                                                      :type "muu"}))

(def deleted-document (merge public-s2-metadata
                             {:deleted #inst"2018-04-03T00:00:00.000+03:00"
                              :deletion-explanation "Bad document"}))

(def id1 "test-file-1")
(def id2 "test-file-2")
(def id3 "test-file-3")
(def id4 "test-file-4")
(def id5 "test-file-5")
(def id6 "test file-6")
(def id7 "test-file %7")
(def id8 "test-file-8 + %50 strange")
(def id9 "test-file-9")
(def id10 "test-file-10")
(def id11 "test-file-11")
(def id12 "test-file-12")
(def id13 "deleted-document")

(def valid-drawing {:type "Polygon"
                    :coordinates [[[25.09525837049 60.488495242537]
                                   [25.094953876678 60.487790364259]
                                   [25.097550912364 60.487890298721]
                                   [25.097304587279 60.488677125947]
                                   [25.097280399964 60.489089913559]
                                   [25.09525837049 60.488495242537]]]})

(def invalid-drawing {:type "Polygon"
                      :coordinates [[[25.09525837049 60.488495242537]
                                     [25.094953876678 60.487790364259]
                                     [25.097550912364 60.487890298721]
                                     [25.097304587279 60.488677125947]
                                     [25.097280399964 60.489089913559]]]})

(def doc-count (atom 0))

(defn persist [md id]
  (let [c (ef/get-client)
        id (persist-document-to-index c
                                      bucket
                                      id
                                      "sha256-sum"
                                      content-type
                                      :gcs
                                      md)]
    (refresh-index c)
    (swap! doc-count inc)
    id))

(defn insert-documents [f]
  (reset! doc-count 0)
  (persist (merge base-metadata public-s2-metadata) id1)
  (persist (merge base-metadata secret-s2-metadata) id2)
  (persist (merge base-metadata partly-secret-s2-metadata) id3)
  (persist (merge base-metadata non-public-s2-metadata) id4)
  (persist (merge base-metadata non-sellable-s2-metadata) id5)
  (persist (merge base-metadata expired-secret-s2-metadata) id6)
  (persist (merge base-metadata public-s2-metadata {:contents "ö"}) id7)
  (persist (merge base-metadata public-s2-metadata {:organization organization2}) id8)
  (persist (merge base-metadata non-public-s2-metadata-sensitive-person-details) id9)
  (persist (merge base-metadata public-s2-metadata-normal-person-details alternate-location) id10)
  (persist (merge base-metadata terminal) id11)
  (persist (merge base-metadata terminal-alternate-municipality-and-type) id12)
  (persist (merge base-metadata deleted-document) id13)
  (f))

(use-fixtures :once ef/elastic-fixture insert-documents)

(defn persist-and-verify [md id]
  (is (nil? (s/check ams/full-document-metadata md)))
  (let [persisted-id (persist md id)
        expected-id (str bucket "-" id)]
    (is (= persisted-id expected-id))))

(defn verify-result-ids [{:keys [results]} & ids]
  (is (= (count results) (count ids)))
  (when (not= (count results) (count ids))
    (println "Unexpected results:" (remove #((set ids) (:fileId %)) results)))
  (doseq [id ids]
    (is (some? (some #(= id (:fileId %)) results)))))

(deftest documents-can-be-persisted
  (let [id "jakgsjdjkl2442"]
    (persist-and-verify (merge base-metadata public-s2-metadata {:contents "gdghajfgalgj"
                                                                 :address "gjladhjglahls"
                                                                 :organization "753-R"})
                        id)))

(deftest drawings-can-be-persisted
  (persist-and-verify (merge base-metadata public-s2-metadata {:drawing-wgs84 [valid-drawing]
                                                               :organization "999-R"})
                      "drawing1"))

(deftest invalid-drawings-throw-exception
  (is (thrown? IllegalArgumentException
               (persist (merge base-metadata public-s2-metadata {:drawing-wgs84 [invalid-drawing]
                                                                 :organization "999-R"})
                        "drawing2"))))

(deftest documents-can-be-found-by-any-field-using-all
  (-> (find-documents (ef/get-client) organization {:all "asemasta"} 0 1000 false)
      (verify-result-ids id1 id2 id3 id6 id9 id10)))

(deftest correct-documents-are-returned-for-single-field
  (-> (find-documents (ef/get-client) organization {:contents "asemasta"} 0 1000 false)
      (verify-result-ids id1 id2 id3 id9 id10)))

(deftest correct-documents-are-returned-for-given-fields
  (-> (find-documents (ef/get-client) organization {:contents "asemasta"
                                                    :address "asemasta"
                                                    :arkistoija "asemasta"} 0 1000 false)
      (verify-result-ids id1 id2 id3 id6 id9 id10)))

(deftest secret-documents-cant-be-found-in-public-only-mode
  (-> (find-documents (ef/get-client) organization {:all "asemasta"} 0 1000 :public-only?)
      (verify-result-ids id1 id6 id10)))

(deftest secret-documents-arent-returned-from-public-modified-api
  (-> (find-public-documents (ef/get-client) organization "2017-05-01" 0 1000)
      (verify-result-ids id1 id6 id7 id10)))

(deftest all-organization-documents-are-returned-from-all-docs-modified-api
  (-> (find-all-documents (ef/get-client) organization "2017-05-01" nil 0 1000)
      (verify-result-ids id1 id2 id3 id4 id5 id6 id7 id9 id10 id13)))

(deftest all-docs-modified-api-can-be-limited-with-modified-before
  (-> (find-all-documents (ef/get-client) organization "2017-05-01" "2019-11-14" 0 1000)
      :results
      empty?
      is)
  (let [tomorrow (tf/unparse (:date-time tf/formatters) (t/plus (t/now) (t/days 1)))]
    (-> (find-all-documents (ef/get-client) organization "2017-05-01" tomorrow  0 1000)
        :results
        count
        (= 10)
        is)))

(deftest all-documents-are-returned-from-private-modified-api
  (-> (all-documents-ascending-by-modification (ef/get-client) #inst"2017-05-01" 0 1000)
      :results
      count
      (= @doc-count)
      is))

(deftest documents-can-be-found-for-multiple-organizations
  (-> (find-documents (ef/get-client) [organization organization2] {:all "asemasta"} 0 1000 false)
      (verify-result-ids id1 id2 id3 id6 id8 id9 id10)))

(deftest documents-can-be-found-for-multiple-organizations-by-single-field
  (-> (find-documents (ef/get-client) [organization organization2] {:contents "asemasta"} 0 1000 false)
      (verify-result-ids id1 id2 id3 id8 id9 id10)))

(deftest public-only-works-for-multiple-organizations-as-well
  (-> (find-documents (ef/get-client) [organization organization2] {:all "asemasta"} 0 1000 :public-only?)
      (verify-result-ids id1 id6 id8 id10)))

(deftest document-with-a-difficult-id-can-be-found
  (let [doc (get-document (ef/get-client) bucket id8)]
    (= id8 (:fileId doc))))

(deftest documents-can-be-searched-by-text-and-limited-by-shape
  (-> (find-documents (ef/get-client)
                      organization
                      {:contents "asemasta"
                       :shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]}
                      0
                      1000
                      false)
      (verify-result-ids id10)))

(deftest documents-can-be-searched-by-text-and-limited-by-geojson
  (-> (find-documents (ef/get-client)
                      organization
                      {:contents "asemasta"
                       :geojson {"type"        "MultiPolygon"
                                 "coordinates" [[[[26.9,62.9] [26.9,63.1] [27.1,63.1] [27.1,62.9] [26.9,62.9]]]]}}
                      0
                      1000
                      false)
      (verify-result-ids id10)))

(deftest documents-can-be-searched-by-text-and-limited-by-shape-docstore
  (-> (find-documents (ef/get-client)
                      organization
                      {:contents       "asemasta"
                       :shape-docstore ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]}
                      0
                      1000
                      false)
      (verify-result-ids id10)))

(deftest documents-can-be-searched-by-text-and-limited-by-geojson-docstore
  (-> (find-documents (ef/get-client)
                      organization
                      {:contents         "asemasta"
                       :geojson-docstore {"type"        "MultiPolygon"
                                          "coordinates" [[[[26.9,62.9] [26.9,63.1] [27.1,63.1] [27.1,62.9] [26.9,62.9]]]]}}
                      0
                      1000
                      false)
      (verify-result-ids id10)))

(deftest docterminal-search-is-filtered-by-allowed-municipalities
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["123"]}
                      0
                      1000
                      true)
      (verify-result-ids id11))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["123" "091"]}
                      0
                      1000
                      true)
      (verify-result-ids id11 id12)))

(deftest docterminal-search-is-filtered-by-allowed-document-types
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-document-types [[terminal-organization ["paapiirustus.asemapiirros"]]]}
                      0
                      1000
                      true)
      (verify-result-ids id11))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-document-types [[terminal-organization ["muu"]]]}
                      0
                      1000
                      true)
      (verify-result-ids id12))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-document-types [[terminal-organization ["paapiirustus.asemapiirros" "muu"]]]}
                      0
                      1000
                      true)
      (verify-result-ids id11 id12)))

(deftest allowed-document-types-and-municipalities-work-in-unison
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["123" "091"]
                       :allowed-document-types [[terminal-organization ["paapiirustus.asemapiirros" "muu"]]]}
                      0
                      1000
                      true)
      (verify-result-ids id11 id12))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["123"]
                       :allowed-document-types [[terminal-organization ["paapiirustus.asemapiirros" "muu"]]]}
                      0
                      1000
                      true)
      (verify-result-ids id11))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["123"]
                       :allowed-document-types [[terminal-organization ["muu"]]]}
                      0
                      1000
                      true)
      (verify-result-ids))
  (-> (find-documents (ef/get-client)
                      terminal-organization
                      {:shape ["26.9,62.9;26.9,63.1;27.1,63.1;27.1,62.9;26.9,62.9"]
                       :allowed-municipalities ["091"]
                       :allowed-document-types [[terminal-organization ["paapiirustus.asemapiirros"]]]}
                      0
                      1000
                      true)
      (verify-result-ids)))
