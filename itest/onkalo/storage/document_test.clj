(ns onkalo.storage.document-test
  (:require [clj-time.coerce :as c]
            [clj-time.format :as tf]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [onkalo.boundary.object-storage :as os]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.frontend-update :as fu]
            [onkalo.storage.document :as doc]
            [onkalo.util.document-api-util :as dau]
            [onkalo.util.laundry-api :as laundry-api]
            [schema.core :as s]
            [test-util.test-system :as test-system])
  (:import [java.io BufferedInputStream File]))

(set! *warn-on-reflection* true)

(use-fixtures :once (test-system/system-fixture-fn))

(defn- upload-valid-doc! [config organization id overwrite tempfile content-type metadata]
  (is (= organization (:organization metadata)))
  (#'doc/upload-valid-doc! config organization id overwrite tempfile content-type metadata))

(def ^:private random-str (comp str random-uuid))

(s/defn base-metadata :- ams/full-document-metadata
  {:always-validate true
   :private         true}
  [org]
  {:address               (random-str)
   :applicants            [(random-str)]
   :applicationId         (random-str)
   :arkistointipvm        #inst "2024-02-01"
   :buildingIds           [(random-str)]
   :contents              (random-str)
   :henkilotiedot         :ei-sisalla
   :julkisuusluokka       :julkinen
   :kasittelija           {:userId    (random-str)
                           :username  (random-str)
                           :firstName (random-str)
                           :lastName  (random-str)}
   :kieli                 :fi
   :kuntalupatunnukset    [(random-str)]
   :location-wgs84        [25.0868 60.46625]
   :location-etrs-tm35fin [394801.203 6704866.824]
   :municipality          (random-str)
   :myyntipalvelu         false
   :nakyvyys              :julkinen
   :nationalBuildingIds   [(random-str)]
   :operations            [:maalampo]
   :organization          org
   :paatospvm             #inst "2024-01-01"
   :permit-expired        false
   :projectDescription    (random-str)
   :propertyId            "12345678901234"
   :sailytysaika          {:arkistointi :ikuisesti
                           :perustelu   (random-str)}
   :tiedostonimi          (random-str)
   :tila                  :valmis
   :tosFunction           {:name (random-str)
                           :code (random-str)}
   :type                  :hakemus
   :versio                (random-str)
   :kayttotarkoitukset    ["ei tiedossa"]})

(defn- make-temp-file
  [contents]
  (let [f (File/createTempFile "test-tempfile" ".txt")]
    (.deleteOnExit f)
    (spit f contents)
    f))

(defn- is->contents
  [is]
  (let [file (File/createTempFile "output" ".tmp")]
    (with-open [is (io/input-stream is)]
      (io/copy is file))
    (slurp file)))

(defn- physical-bucket
  [org]
  (str "_local-test-onkalo_-arkisto-" org "-_test_"))

(defn- physical-review-bucket
  [org]
  (str "_local-test-onkalo_-arkisto-" org "-_test_-preview"))

(defn- expected-convert-message
  [org id]
  (let [org (str/lower-case org)]
    {:handler            :convert-to-jpeg
     :message-id         (str (physical-bucket org) "/" id "-preview")
     :response-expected? false
     :data               {:bucket        (physical-bucket org)
                          :object-key    id
                          :output-size   1000
                          :quality       60
                          :suffix        ""
                          :target-bucket (physical-review-bucket org)
                          :watermark     true}}))

(defn- date->str
  [date]
  (->> date
       c/from-date
       (tf/unparse (:date-time tf/formatters))))

(defn- xy->point
  [[x y]]
  [{:type "point" :coordinates [x y]}])

(deftest upload-document-multiple-times-test
  (let [system   (test-system/system)
        org      "123-R"
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata org)]

    (testing "get document before uploading"
      (is (thrown-with-msg? Exception (re-pattern (str "Document arkisto-123-r-_test_/"  id " not found"))
                            (doc/get-document system id org))
          "not found error returned"))

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    (testing "get document"
      (let [{:keys [content-fn content-type]} (doc/get-document system id org)]
        (is (= "text/plain" content-type))
        (is (= contents (is->contents (content-fn))))))

    (testing "upload again without overwrite"
      (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 409 status))
        (is (= (format "Object with id %s already exists and no overwrite was requested." id)
               body))
        (is (empty? messages))))

    (testing "upload again with overwrite"
      (let [{:keys [status body]} (upload-valid-doc! system org id true file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))))

(deftest upload-preview-test
  (let [system   (test-system/system)
        org      "123-R"
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata org)]

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    (testing "get preview before laundry generates one"
      (let [{:keys [status body headers]} (doc/get-preview system id org)
            messages                      (test-system/messages true)]
        (is (= 200 status))
        (is (instance? BufferedInputStream body)
            "placeholder image returned")
        (is (= {"Content-Type"        "image/jpeg"
                "Content-Disposition" "inline"}
               headers))
        (is (= {"to-conversion-service" [(expected-convert-message org id)]}
               messages)
            "preview jpeg conversion requested (again) from laundry")))

    (testing "manually upload preview"
      (let [preview-contents (str (random-uuid))
            file             (make-temp-file preview-contents)
            bucket           (os/org-preview-bucket org (-> system :storage :bucket-suffix))]
        (os/upload (:gcs system) bucket id {:content      file
                                            :content-type "text/plain"})

        (testing "get preview"
          (let [{:keys [status body headers]} (doc/get-preview system id org)
                messages                      (test-system/messages true)]
            (is (= 200 status))
            (is (= preview-contents (is->contents body))
                "preview contents returned")
            (is (= {"Content-Type" "text/plain"} headers))
            (is (empty? messages)
                "no preview conversion needed anymore")))))))

(deftest upload-metadata-test
  (let [system   (test-system/system)
        org      "123-R"
        user     {:id        "1"
                  :username  "user"
                  :firstName "first"
                  :lastName  "last"}
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata org)]

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    (testing "get document metadata"
      (let [{:keys [status body]} (doc/document-metadata system id org false)]
        (is (= 200 status))
        (is (= {:source       "lupapiste"
                :organization org
                :contentType  "text/plain"
                :fileId       id
                :metadata     (-> (#'ea/stringify-values metadata)
                                  (update :arkistointipvm date->str)
                                  (update :paatospvm date->str)
                                  (update :location-wgs84 xy->point)
                                  (assoc :location-docstore (-> metadata :location-wgs84 xy->point first))
                                  (dissoc :organization))}
               (dissoc body :modified :sha256)))

        (testing "get document metadata but only selected fields"
          (testing "selected fields are returned"
            (let [{:keys [status body]} (doc/document-metadata system id org false #{"metadata.nakyvyys"})]
              (is (= 200 status))
              (is (= {:fileId   id
                      :metadata (-> (#'ea/stringify-values metadata)
                                    (select-keys [:nakyvyys]))}
                     (dissoc body :modified :sha256)))))
          (testing "unkown requested fields are ignored"
            (let [{:keys [status body]} (doc/document-metadata system id org false #{"metadata.i-dont-exist"})]
              (is (= 200 status))
              (is (= {:fileId id}
                     (dissoc body :modified :sha256)))))
          (testing "all heavy-response fields are returned"
            (let [meta-keys             (->> dau/heavy-response-fields
                                             (filter #(str/starts-with? % "metadata."))
                                             (mapv (comp keyword second #(str/split % #"\."))))
                  {:keys [status body]} (doc/document-metadata system id org false dau/heavy-response-fields)]
              (is (= 200 status))
              (is (= {:fileId       id
                      :organization org
                      :contentType  "text/plain"
                      :metadata     (-> (#'ea/stringify-values metadata)
                                        (update :arkistointipvm date->str)
                                        (update :paatospvm date->str)
                                        (update :location-wgs84 xy->point)
                                        (select-keys meta-keys))}
                     (dissoc body :modified :sha256))
                  "response values match expected values")
              (is (every? (fn [k] (some? (get-in body [:metadata k]))) meta-keys)
                  "response contains each requested value"))))

        (testing "update metadata"
          (let [new-addr              (str (random-uuid))
                new-meta              (merge metadata {:address new-addr})
                {:keys [status body]} (fu/update-metadata system id new-meta user org)]
            (is (= 200 status))
            (is (= body
                   (-> metadata
                       (select-keys [:buildingIds
                                     :contents
                                     :demolished
                                     :kasittelija
                                     :kuntalupatunnukset
                                     :location-wgs84
                                     :nationalBuildingIds
                                     :paatospvm
                                     :permit-expired
                                     :projectDescription
                                     :propertyId
                                     :type])
                       (update :location-wgs84 xy->point)
                       (assoc :address new-addr
                              :location (:location-etrs-tm35fin metadata)
                              :permit-expired-date nil
                              :demolished nil
                              :demolished-date nil
                              :kuntalupatunnukset-muutossyy nil
                              :metadata (select-keys metadata [:henkilotiedot
                                                               :julkisuusluokka
                                                               :kieli
                                                               :myyntipalvelu
                                                               :nakyvyys
                                                               :sailytysaika
                                                               :tila])))))))))))

(deftest laundry-validation-test
  (with-redefs [doc/generate-temp-id (constantly "temp-id")]
    (let [system          (test-system/system)
          contents        (str (random-uuid))
          file            (make-temp-file contents)
          content-type    "image/jpeg"
          expected-id     "temp-id"
          expected-bucket "_local-test-onkalo_-temp-_test_"]
      (testing "happy path"
        (with-redefs [laundry-api/do-check-image-properties! (fn [_laundry bucket object-id]
                                                               (is (= expected-bucket bucket))
                                                               (is (= expected-id object-id))
                                                               (is (test-system/object-exists? bucket object-id))
                                                               {:properties {:width        100
                                                                             :height       100
                                                                             :content-type "image/jpeg"}})]
          (is (not (test-system/object-exists? expected-bucket expected-id)))
          (is (not (doc/invalid-contents? system file content-type)))
          (is (not (test-system/object-exists? expected-bucket expected-id)))))
      (testing "not found"
        (with-redefs [laundry-api/do-check-image-properties! (fn [_laundry _bucket _object-id]
                                                               {:error {:status 404
                                                                        :body   "Could not download object _local-test-onkalo_-temp-_test_/temp-id"}})]
          (is (not (test-system/object-exists? expected-bucket expected-id)))
          (is (doc/invalid-contents? system file content-type))
          (is (not (test-system/object-exists? expected-bucket expected-id)))))
      (testing "exception while calling Laundry"
        (with-redefs [laundry-api/do-check-image-properties! (fn [_laundry _bucket _object-id]
                                                               (throw (ex-info "Intentional test exception" {})))]
          (is (not (test-system/object-exists? expected-bucket expected-id)))
          (is (doc/invalid-contents? system file content-type))
          (is (not (test-system/object-exists? expected-bucket expected-id)))))
      (testing "sneakily remove file during validation"
        (with-redefs [laundry-api/do-check-image-properties! (fn [_laundry _bucket _object-id]
                                                               ;; The temp file shouldn't magically disappear
                                                               ;; while verifying, so let's consider that an error
                                                               ;; regardless what the result from Laundry was
                                                               (os/delete-temp-file (:gcs system) expected-id)
                                                               {:properties {:width        100
                                                                             :height       100
                                                                             :content-type "image/jpeg"}})]
          (is (not (test-system/object-exists? expected-bucket expected-id)))
          (is (doc/invalid-contents? system file content-type))
          (is (not (test-system/object-exists? expected-bucket expected-id))))))))
