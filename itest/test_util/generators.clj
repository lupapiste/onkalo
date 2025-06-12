(ns test-util.generators
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [schema-generators.generators :as scg]))

(def plain-double-gen (gen/double* {:infinite? false
                                    :NaN?      false}))

(def generators {ams/Coordinates (gen/tuple plain-double-gen plain-double-gen)
                 ams/PropertyId  (gen/fmap str/join (gen/vector (gen/choose 0 9) 14))
                 tms/Vuodet      gen/nat})

(defn generate-document-metadata [additional-data]
  (-> (scg/generate ams/full-document-metadata generators)
      (merge additional-data)
      (dissoc :drawing-wgs84 :deleted :organization)))
