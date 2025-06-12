(ns onkalo.metadata.xmp-processor-test
  (:require [clojure.test :refer :all]
            [onkalo.metadata.xmp-processor :refer :all]
            [meta-merge.core :refer [meta-merge]]
            [clojure.java.io :as io]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [lupapiste-commons.schema-utils :as schema-utils])
  (:import [java.io File]
           [java.util Arrays]
           [java.nio.file Files]))

(def test-metadata
  {:tila :arkistoitu
   :arkistoija {:firstName "Sonja" :lastName "Sibbo" :username "sonja"}
   :address "Jussaksentie 5"
   :nakyvyys :julkinen
   :location-etrs-tm35fin '(404614.645 6694801.868)
   :sailytysaika {:pituus 6
                  :arkistointi :määräajan
                  :retention-period-end #inst "2022-03-15T22:00:00.000-00:00"
                  :perustelu "Hyvä aika"}
   :myyntipalvelu true
   :type :hakemus
   :municipality "753"
   :organization "753-R"
   :lupapvm #inst "2016-03-15T22:00:00.000-00:00"
   :suunnittelijat ()
   :applicationId "LP-753-2016-90012"
   :paatospvm #inst "2016-03-15T22:00:00.000-00:00"
   :kieli :fi
   :kasittelija {:firstName "Sonja" :lastName "Sibbo" :username "sonja"}
   :location-wgs84 '(25.26998 60.37836)
   :nationalBuildingIds '()
   :arkistointipvm #inst "2016-03-16T14:55:54.000-00:00"
   :versio "1.0"
   :buildingIds '()
   :applicant "Sibbo Sonja"
   :operations '(:pientalo)
   :tiedostonimi "4L09111-30155-30155 LUPAKUVA 100 G&G.pdf"
   :henkilotiedot :ei-sisalla
   :kayttotarkoitukset '("011 yhden asunnon talot")
   :paatoksentekija "Johtava Virkamies"
   :tosFunction {:code "10 03 00 01" :name "Rakennuslupamenettely"}
   :propertyId "75342300070019"
   :kuntalupatunnukset '("234235")
   :julkisuusluokka :julkinen})

(deftest metadata-can-be-added-to-pdf
  (let [pdf (io/as-file "./test/test.pdf")
        tmp-pdf (File/createTempFile "onkalo-test" ".pdf")]
    (io/copy pdf tmp-pdf)
    (write-metadata-to-file! tmp-pdf test-metadata "application/pdf")
    (is (= test-metadata (->> (read-metadata-map tmp-pdf "application/pdf")
                              (schema-utils/coerce-metadata-to-schema ams/full-document-metadata))))
    (io/delete-file tmp-pdf :silently)))

(deftest metadata-can-be-replaced-in-pdf
  (let [pdf (io/as-file "./test/with-existing-metadata.pdf")
        tmp-pdf (File/createTempFile "onkalo-test" ".pdf")]
    (io/copy pdf tmp-pdf)
    (write-metadata-to-file! tmp-pdf test-metadata "application/pdf")
    (is (= test-metadata (->> (read-metadata-map tmp-pdf "application/pdf")
                              (schema-utils/coerce-metadata-to-schema ams/full-document-metadata))))
    (io/delete-file tmp-pdf :silently)))

(deftest metadata-wont-be-altered-in-a-signed-pdf
  (let [pdf (io/as-file "./test/signed.pdf")
        tmp-pdf (File/createTempFile "onkalo-test" ".pdf")]
    (io/copy pdf tmp-pdf)
    (write-metadata-to-file! tmp-pdf test-metadata "application/pdf")
    (is (Arrays/equals (Files/readAllBytes (.toPath pdf)) (Files/readAllBytes (.toPath tmp-pdf))))
    (io/delete-file tmp-pdf :silently)))

(deftest metadata-can-be-added-to-tiff
  (let [tiff (io/as-file "./test/test.tiff")
        tmp-tiff (File/createTempFile "onkalo-test" ".tiff")]
    (io/copy tiff tmp-tiff)
    (write-metadata-to-file! tmp-tiff test-metadata "image/tiff")
    (is (= test-metadata (->> (read-metadata-map tmp-tiff "image/tiff")
                              (schema-utils/coerce-metadata-to-schema ams/full-document-metadata))))
    (io/delete-file tmp-tiff :silently)))

(deftest metadata-is-escaped-properly
  (let [pdf (io/as-file "./test/test.pdf")
        tmp-pdf (File/createTempFile "onkalo-test" ".pdf")
        metadata {:foo "this & that is < 5"}]
    (io/copy pdf tmp-pdf)
    (write-metadata-to-file! tmp-pdf metadata "application/pdf")
    (is (= metadata (read-metadata-map tmp-pdf "application/pdf")))
    (io/delete-file tmp-pdf :silently)))
