(ns onkalo.metadata.elastic-migrations-test
  (:require [clojure.test :refer :all]
            [onkalo.metadata.elastic-migrations :as em]))

(def non-affected-document
  {:foo :bar })

(def document-with-invalid-contents-and-location
  {:organization "186-R"
   :metadata {:contents "Dokumentin tyypit:- Piha- ja istutussuunnitelma"
              :location-etrs-tm35fin [6700864 386802]
              :location-wgs84 [{:type "point" :coordinates [75.449168 2.333387]}]}})

(def document-with-corrected-contents-and-location
  {:organization "186-R"
   :metadata {:contents "Piha- ja istutussuunnitelma"
              :location-etrs-tm35fin [386802 6700864]
              :location-wgs84 [{:type "point" :coordinates [24.943698 60.42817]}]}})

(deftest migrations-for-v17-work
  (let [migrated-docs (->> [non-affected-document
                            document-with-invalid-contents-and-location
                            document-with-corrected-contents-and-location]
                           (map #(em/migrate-document 17 %))
                           doall)]
    (is (= [non-affected-document
            document-with-corrected-contents-and-location
            document-with-corrected-contents-and-location]
           migrated-docs))))
