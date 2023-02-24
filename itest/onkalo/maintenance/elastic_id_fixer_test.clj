(ns onkalo.maintenance.elastic-id-fixer-test
  (:require [clojure.test :refer :all]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [onkalo.maintenance.elastic-id-fixer :as fixer]
            [onkalo.metadata.elastic-api :as ea]
            [qbits.spandex :as s]
            [ring.util.codec :as codec]
            [schema-generators.generators :as scg]
            [taoensso.timbre :as timbre]
            [test-util.elastic-fixture :as ef]
            [test-util.generators :as g]))

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

(def file-id2 (str (scg/generate tms/NonEmptyStr) " a"))

(def base-metadata (g/generate-document-metadata {:location-wgs84 [{:type "point" :coordinates [25.0868 60.46625]}]
                                                  :location-docstore {:type "point" :coordinates [25.0868 60.46625]}
                                                  :address "Seutulantie 10"}))

(defn insert-documents [f]
  (let [elastic (ef/get-client)
        file-id "my beautiful file"
        doc1    {:bucket       bucket
                 :fileId       file-id
                 :organization "186-R"
                 :modified     "2018-06-16T18:49:05.048Z"
                 :metadata     base-metadata}

        docs    [doc1
                 (assoc doc1 :modified "2017-06-16T18:49:05.048Z")
                 (assoc doc1 :modified "2016-06-16T18:49:05.048Z")
                 (assoc doc1 :fileId "random")
                 (assoc doc1 :fileId file-id2)
                 (assoc doc1 :modified "2017-06-16T18:49:05.048Z" :fileId file-id2)]]
    (timbre/info "Persisting test documents")
    (persist elastic (codec/url-encode (file->es-id file-id)) (first docs))
    (persist elastic (codec/url-encode (codec/url-encode (file->es-id file-id))) (second docs))
    (persist elastic (file->es-id file-id) (nth docs 2))
    (persist elastic (file->es-id "random") (nth docs 3))
    (persist elastic (codec/url-encode (file->es-id file-id2)) (nth docs 4))
    (persist elastic (codec/url-encode (codec/url-encode (file->es-id file-id2))) (nth docs 5))
    (ea/refresh-index elastic))
  (f))

(use-fixtures :once ef/elastic-fixture insert-documents)


(deftest elastic-id-fixing-works
  (let [elastic (ef/get-client)]
    (is (= 6 (-> (ea/find-documents elastic "186-R" {:address "Seutulantie"} 0 1000 false)
                 :meta
                 :count)))

    (fixer/fix-duplicate-documents {:elastic elastic} nil)
    ; The fixer is async
    (Thread/sleep 2000)

    (let [end-result (-> (s/request (:es-client elastic) {:url    [(:current-index elastic) :_search]
                                                          :method :get
                                                          :body   {:query {:term {:metadata.address "Seutulantie 10"}}}})
                         :body
                         :hits
                         :hits)]
      ; Duplicates should have been removed and 3 docs with correct ids should remain
      (is (= 3 (count end-result)))
      (is (some #(= (:_id %) "arkisto-186-r-prod-my beautiful file") end-result))
      (is (some #(= (:_id %) (file->es-id file-id2)) end-result)))))


(deftest id-is-fixed-for-singular-files
  (let [elastic    (ef/get-client)
        single-doc {:bucket       bucket
                    :fileId       "kullervo"
                    :organization "186-R"
                    :modified     "2020-05-07T08:56:09.048Z"
                    :metadata     (assoc base-metadata :address "Sibeliuksenkatu 20")}]

    (persist elastic "AGHBjkl4nyz235rsc" single-doc)
    (ea/refresh-index elastic)

    (is (= 1 (-> (ea/find-documents elastic "186-R" {:address "Sibeliuksenkatu"} 0 1000 false)
                 :meta
                 :count)))

    (fixer/fix-duplicate-documents {:elastic elastic} {:organization "186-R"})
    ; The fixer is async
    (Thread/sleep 2000)

    (let [end-result (-> (s/request (:es-client elastic) {:url    [(:current-index elastic) :_search]
                                                          :method :get
                                                          :body   {:query {:term {:metadata.address "Sibeliuksenkatu 20"}}}})
                         :body
                         :hits
                         :hits)]
      (is (= 1 (count end-result)))
      (is (= (-> end-result first :_id) "arkisto-186-r-prod-kullervo")))))
