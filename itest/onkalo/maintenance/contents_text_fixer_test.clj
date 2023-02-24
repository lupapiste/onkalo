(ns onkalo.maintenance.contents-text-fixer-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.generators]
            [qbits.spandex :as s]
            [schema-generators.generators :as scg]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [test-util.elastic-fixture :as ef]
            [test-util.generators :as g]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.maintenance.contents-text-fixer :as fixer]))

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

(defn insert-documents [f]
  (let [base-metadata (g/generate-document-metadata {:location-wgs84 [{:type "point" :coordinates [25.0868 60.46625]}]
                                                     :location-docstore {:type "point" :coordinates [25.0868 60.46625]}
                                                     :address "Seutulantie 10"
                                                     :contents "Dokumentin tyypit:- foobar"})
        elastic (ef/get-client)
        doc1 {:bucket       bucket
              :fileId       file-id1
              :organization "186-R"
              :metadata     base-metadata}

        docs [doc1
              (-> (assoc doc1 :fileId file-id2)
                  (assoc-in [:metadata :contents] "not affected"))
              (-> (assoc doc1 :fileId file-id3)
                  (assoc-in [:metadata :contents] "jätekatosDokumentin tyypit:- Pohjapiirustus- Leikkauspiirustus- Julkisivupiirustus"))]]
    (doseq [doc docs]
      (persist elastic (file->es-id (:fileId doc)) doc))
    (ea/refresh-index elastic))
  (f))

(use-fixtures :once ef/elastic-fixture insert-documents)

(deftest contents-fixing-works
  (let [elastic (ef/get-client)]
    (is (= 3 (-> (ea/find-documents elastic "186-R" {:address "Seutulantie"} 0 1000 false)
                 :meta
                 :count)))

    (fixer/remove-extra-text-for-186-r {:elastic elastic} nil)
    ; The fixer is async
    (Thread/sleep 2000)

    (let [end-result (-> (ea/find-documents elastic "186-R" {:address "Seutulantie"} 0 1000 false)
                         :results)]
      (is (= 3 (count end-result)))
      (is (some #(= (-> % :metadata :contents) "foobar") end-result))
      (is (some #(= (-> % :metadata :contents) "jätekatos Pohjapiirustus- Leikkauspiirustus- Julkisivupiirustus") end-result))
      (is (some #(= (-> % :metadata :contents) "not affected") end-result)))))
