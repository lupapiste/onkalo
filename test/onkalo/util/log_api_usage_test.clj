(ns onkalo.util.log-api-usage-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer :all]
            [onkalo.util.log-api-usage :refer :all]))

(def elastic-data
  {:source       "lupapiste"
   :fileId       "file-id"
   :contentType  "application/pdf"
   :organization "123-R"
   :modified     "2018-05-24T00:00:00.000Z"
   :metadata
                 {:nakyvyys        "julkinen"
                  :myyntipalvelu   true
                  :henkilotiedot   "ei-sisalla"
                  :julkisuusluokka "julkinen"
                  :applicationId   "LP-1"
                  :address         "Latokuja 1"
                  :type            "paapiirustus.julkisivupiirustus"
                  :propertyId      "75342600110012"}})

(deftest create-message-creates-edn-string
  (is (= (create-message "api-user" "external-id" 100 elastic-data)
         {:fileId       "file-id"
          :organization "123-R"
          :apiUser      "api-user"
          :externalId   "external-id"
          :message-id   "external-id-api-usage"
          :metadata     {:henkilotiedot   "ei-sisalla"
                         :julkisuusluokka "julkinen"
                         :myyntipalvelu   true
                         :nakyvyys        "julkinen"
                         :applicationId   "LP-1"
                         :address         "Latokuja 1"
                         :type            "paapiirustus.julkisivupiirustus"
                         :propertyId      "75342600110012"}
          :timestamp    100})))
