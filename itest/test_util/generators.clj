(ns test-util.generators
  (:require [clojure.test :refer :all]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [clojure.test.check.generators :as gen]
            [clojure.string :as str]
            [schema-generators.generators :as scg]))

(def generators {ams/PropertyId (gen/fmap str/join (gen/vector (gen/choose 0 9) 14))})

(defn generate-document-metadata [additional-data]
  (-> (scg/generate ams/full-document-metadata generators)
      (merge additional-data)
      (dissoc :drawing-wgs84 :deleted :organization)))
