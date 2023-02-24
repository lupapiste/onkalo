(ns onkalo.maintenance.property-id-fixer-test
  (:require [clojure.test :refer :all]
            [qbits.spandex :as s]
            [test-util.elastic-fixture :as ef]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [schema-generators.generators :as scg]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.maintenance.property-id-fixer :as property-id-fixer]))

(def bucket "arkisto-186-r-prod")

(defn file->es-id [id]
  (str bucket "-" id))

(defn persist [{:keys [es-client current-index]} id doc]
  (try
    (s/request es-client {:url    [current-index :_doc id]
                          :method :put
                          :body   doc})
    (catch Exception e
      (println doc)
      (throw e))))

(def file-id1 (scg/generate tms/NonEmptyStr))
(def file-id2 (scg/generate tms/NonEmptyStr))
(def file-id3 (scg/generate tms/NonEmptyStr))
(def file-id4 (scg/generate tms/NonEmptyStr))

(defn insert-documents [f]
  (let [elastic (ef/get-client)
        doc1    {:fileId file-id1
                 :organization "186-R"
                 :metadata {:propertyId "186-013-1331-0008"
                            :municipality "186"}}
        doc2    {:fileId file-id2
                 :organization "186-R"
                 :metadata {:propertyId "013-9999-0008"
                            :municipality "186"}}
        doc3    {:fileId file-id3
                 :organization "186-R"
                 :metadata {:propertyId "18601314560010"
                            :municipality "186"}}
        doc4 {:fileId file-id4
              :organization "186-R"
              :metadata {:propertyId "-unknown-format-"
                         :municipality "186"}}]
    (doseq [doc [doc1 doc2 doc3 doc4]]
      (persist elastic (file->es-id (:fileId doc)) doc))
    (ea/refresh-index elastic))
  (f))

(use-fixtures :once ef/elastic-fixture insert-documents)

(deftest removal-works
  (let [elastic (ef/get-client)]
    (is (= 4 (-> (ea/find-documents elastic "186-R" {} 0 1000 false)
                 :meta
                 :count)))

    (property-id-fixer/reformat-property-ids {:elastic elastic} nil)
    (Thread/sleep 2000)

    (let [end-result (-> (ea/find-documents elastic "186-R" {} 0 1000 false)
                         :results)]
      (is (= 4 (count end-result)))
      (is (some #(= (-> % :metadata :propertyId) "18601313310008") end-result))
      (is (some #(= (-> % :metadata :propertyId) "18601399990008") end-result))
      (is (some #(= (-> % :metadata :propertyId) "18601314560010") end-result))
      (is (some #(= (-> % :metadata :propertyId) "-unknown-format-") end-result)))))
