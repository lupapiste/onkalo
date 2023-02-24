(ns onkalo.metadata.metadata-util-test
  (:require [clojure.test :refer :all]
            [onkalo.metadata.metadata-util :refer :all]
            [onkalo.metadata.frontend-update :as frontend-update]
            [meta-merge.core :refer [meta-merge]]))

(def standard-metadata
  {:tila                  :arkistoitu
   :arkistoija            {:username "sonja" :firstName "Sonja" :lastName "Sibbo"}
   :jattopvm              #inst "2017-11-15T14:26:57.000-00:00"
   :address               "Bubbiksenpolku 1"
   :nakyvyys              :julkinen
   :location-etrs-tm35fin [404847.0 6695062.0]
   :sailytysaika          {:perustelu "AL/17413/07.01.01.03.01/2016" :arkistointi :ikuisesti}
   :myyntipalvelu         false
   :history               []
   :type                  :selvitykset.energiatodistus
   :municipality          "753"
   :organization          "753-R"
   :lupapvm               #inst "1970-01-01T00:00:00.000-00:00"
   :suunnittelijat        []
   :foremen               "Intonen Mikko"
   :applicationId         "LP-753-2017-90083"
   :paatospvm             #inst "2017-11-14T22:00:00.000-00:00"
   :kieli                 :fi
   :kasittelija           {:userId    "777777777777777777000023"
                           :firstName "Sonja"
                           :lastName  "Sibbo"}
   :location-wgs84        [{:type "point" :coordinates [25.27406 60.38075]}]
   :nationalBuildingIds   []
   :arkistointipvm        #inst "2018-03-07T11:25:41.819-00:00"
   :versio                "1.0"
   :buildingIds           []
   :operations            [:kerrostalo-rivitalo]
   :tiedostonimi          "city-tax_self-attestation_.pdf"
   :henkilotiedot         :sisaltaa
   :kayttotarkoitukset    ["021 rivitalot"]
   :paatoksentekija       "Sonja Sibbo"
   :contents              "Energiatodistus on tämmöinen siis"
   :tosFunction           {:code "10 03 00 01", :name "Rakennuslupamenettely"}
   :applicants            ["Panaani Pena"]
   :propertyId            "75342300070047"
   :kuntalupatunnukset    ["testi"]
   :julkisuusluokka       :julkinen})

(def sonja
  {:role      "authority"
   :email     "sonja.sibbo@sipoo.fi"
   :username  "sonja"
   :firstName "Sonja"
   :orgAuthz
              {:753-R         #{:tos-editor :tos-publisher :authority :approver :archivist :digitizer}
               :753-YA        #{:authority :approver}
               :998-R-TESTI-2 #{:authority :approver}}
   :expires   1520425135726
   :language  "fi"
   :id        "777777777777777777000023"
   :lastName  "Sibbo"})

(deftest un-delete
  (let [deleted-metadata (assoc standard-metadata :deleted #inst"2018-03-25T23:28:36.116-00:00"
                                :deletion-explanation "Poistin testatakseni")
        new-metadata     {:deleted 0 :deletion-explanation "Palautin testatakseni"}]
    (testing "un-delete removes deleted and explanation -keys"
      (is (= (un-delete-when-called-for deleted-metadata new-metadata (meta-merge deleted-metadata new-metadata))
             standard-metadata)))))

(deftest add-history-event
  (let [new-md     {:tila            :arkistoitu
                    :nakyvyys        :julkinen
                    :sailytysaika    {:perustelu "AL/17413/07.01.01.03.01/2016" :arkistointi :ikuisesti},
                    :myyntipalvelu   false
                    :kieli           :fi
                    :henkilotiedot   :sisaltaa
                    :contents        "Energiatodistus on tämmöinen siis todellakin"
                    :julkisuusluokka :julkinen}
        deleted-md {:deleted              "2018-03-07T12:00:00.000Z"
                    :deletion-explanation "ei pitänytkään arkistoida"}
        updated-md (add-archive-edit-history "bucket/55555" sonja standard-metadata new-md)]
    (testing "adding history event for normal metadata update"

      (is (= (-> updated-md :history (count)) 1))
      (is (= (-> updated-md :history (first) :user)
             {:userId    "777777777777777777000023"
              :username  "sonja"
              :firstName "Sonja"
              :lastName  "Sibbo"}))
      (is (= (-> updated-md :history (first) :field)
             [:contents]))
      (is (= (-> updated-md :history (first) :old-val)
             "Energiatodistus on tämmöinen siis"))
      (is (= (-> updated-md :history (first) :new-val)
             "Energiatodistus on tämmöinen siis todellakin")))

    (let [updated-md-2 (add-archive-edit-history "bucket/55555" sonja standard-metadata deleted-md)]

      (testing "adding document deleted event"
        (is (= (-> updated-md-2 :history (count)) 1))

        (is (= (-> updated-md-2 :history (first) :user)
               {:userId    "777777777777777777000023"
                :username  "sonja"
                :firstName "Sonja"
                :lastName  "Sibbo"}))
        (is (= (-> updated-md-2 :history (first) :field)
               [:deleted]))
        (is (= (-> updated-md-2 :history (first) :old-val)
               nil))
        (is (= (-> updated-md-2 :history (first) :new-val)
               "2018-03-07T12:00:00.000Z"))
        (is (= (-> updated-md-2 :history (first) :deletion-explanation)
               "ei pitänytkään arkistoida"))))

    (let [new-metadata (assoc standard-metadata :location-wgs84 [{:type "point" :coordinates [20.0 60.5]}])
          updated-md-3 (add-archive-edit-history "bucket/55555" sonja standard-metadata new-metadata)]
      (is (-> updated-md-3 :history empty?)
          ":location-wgs84 modification does not go in history"))

    (let [new-metadata (assoc standard-metadata :address (apply str (repeatedly 32800 (constantly "a"))))
          updated-md-3 (add-archive-edit-history "bucket/55555" sonja standard-metadata new-metadata)]
      (is (= (-> updated-md-3 :history first :new-val)
             "<Value not stored because it exceeds maximum length>")
          "too long values are replaced with a notification"))))
