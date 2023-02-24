(ns onkalo.maintenance.continue-migration-test
  (:require [clojure.test :refer :all]
            [test-util.elastic-fixture :as ef]
            [onkalo.component.elastic :as e]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.boundary.elastic-document-api :refer [refresh]]
            [onkalo.maintenance.continue-migration :as continue-migration]
            [com.stuartsierra.component :refer [start stop]]
            [clojure.data]
            [qbits.spandex :as s]
            [test-util.generators :as g]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [schema-generators.generators :as scg]))

(def bucket "test-bucket")
(def content-type "application/pdf")
(def organization "186-R")

(def base-metadata (g/generate-document-metadata {:location-wgs84 [{:type "point" :coordinates [25.0868 60.46625]}]
                                                  :location-docstore {:type "point" :coordinates [25.0868 60.46625]}}))

(def id "test1")
(def spaced-id "test-fi%2520le-8 + %50 str%20an%2Bge")
(def sha256-sum "sha256-sum")

(deftest continue-migration-works
  (let [elastic (ef/start-es-component!)
        es-client (:es-client elastic)
        old-index (:current-index elastic)
        next-index-version (inc e/index-version)
        next-index (str "onkalo-test-" (System/currentTimeMillis) "-v" next-index-version)
        read-alias "test-document-metadata-read"
        write-alias "test-document-metadata-write"]
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
    (with-redefs
      [onkalo.component.elastic/index-version next-index-version]
      (e/create-index! es-client next-index nil 1 0)
      (e/update-aliases! es-client [{:remove {:index old-index :alias write-alias}}
                                    {:add {:index next-index :alias write-alias}}])
      (continue-migration/continue-migration {:elastic {:es-client es-client
                                                        :read-alias read-alias
                                                        :current-index next-index}}
                                             {:source-index old-index})
      (Thread/sleep 2000)
      (let [read-alias-results (-> (s/request es-client
                                              {:url  [read-alias :_search]
                                               :body {:query {:match_all {}}}})
                                   :body
                                   :hits
                                   :hits)
            next-index-results (-> (s/request es-client
                                              {:url  [next-index :_search]
                                               :body {:query {:match_all {}}}})
                                   :body
                                   :hits
                                   :hits)]
        (is (= 2 (count read-alias-results)))
        (is (= 2 (count next-index-results)))))
    (e/delete-index! es-client next-index)
    (e/destroy-index! elastic)
    (stop elastic)
    (reset! ef/index-name nil)
    (reset! ef/es-component nil)))
