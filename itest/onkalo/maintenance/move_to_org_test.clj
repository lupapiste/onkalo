(ns onkalo.maintenance.move-to-org-test
  "Similar to `onkalo.storage.document-test`, except here we migrate documents during the test"
  (:require [clj-time.coerce :as c]
            [clj-time.format :as tf]
            [clojure.core.async :refer [<!!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [lupapiste-commons.archive-metadata-schema :as ams]
            [onkalo.boundary.object-storage :as os]
            [onkalo.maintenance.move-to-org :as job]
            [onkalo.metadata.elastic-api :as ea]
            [onkalo.metadata.frontend-update :as fu]
            [onkalo.storage.document :as doc]
            [schema.core :as s]
            [test-util.test-system :as test-system])
  (:import [java.io BufferedInputStream File]
           [java.util Date]))

(set! *warn-on-reflection* true)

(use-fixtures :each (test-system/system-fixture-fn))

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
   :versio                (random-str)})

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

(defn- doc-id
  [org public-id]
  (str "arkisto-" (str/lower-case org) "-_test_-" public-id))

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
        src-org  "123-R"
        dst-org  "999-R"
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata src-org)]

    (testing "get document before uploading"
      (is (thrown-with-msg? Exception (re-pattern (str "Document arkisto-123-r-_test_/"  id " not found"))
                            (doc/get-document system id src-org))
          "not found error returned"))

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system src-org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    ;; Run migration and block until done
    (let [{:keys [assigned-cnt
                  conflicted]} (<!! (job/move-to-org system {:source-organization      src-org
                                                             :destination-organization dst-org
                                                             :dry-run                  false}))]
      (is (= 1 assigned-cnt))
      (is (empty? conflicted)))

    (testing "get document"
      (let [{:keys [content-fn content-type]} (doc/get-document system id dst-org)]
        (is (= "text/plain" content-type))
        (is (= contents (is->contents (content-fn))))))

    ;; Make sure we refresh the metadata to match the new org
    (let [new-metadata (assoc metadata :organization dst-org)]
      (testing "upload again without overwrite"
        (let [{:keys [status body]} (upload-valid-doc! system dst-org id false file "text/plain" new-metadata)
              messages              (test-system/messages true)]
          (is (= 409 status))
          (is (= (format "Object with id %s already exists and no overwrite was requested." id)
                 body))
          (is (empty? messages))))

      (testing "upload again with overwrite"
        (let [{:keys [status body]} (upload-valid-doc! system dst-org id true file "text/plain" new-metadata)
              messages              (test-system/messages true)]
          (is (= 200 status))
          (is (= "OK" body))
          (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
                 messages)
              "preview jpeg conversion requested from laundry (using the original src-org bucket)"))))

    (test-system/refresh-es-index)

    (testing "get document"
      (let [{:keys [content-fn content-type]} (doc/get-document system id dst-org)]
        (is (= "text/plain" content-type))
        (is (= contents (is->contents (content-fn))))))))

(deftest upload-preview-test
  (let [system   (test-system/system)
        src-org  "123-R"
        dst-org  "999-R"
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata src-org)]

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system src-org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    ;; Run migration and block until done
    (let [{:keys [assigned-cnt
                  conflicted]} (<!! (job/move-to-org system {:source-organization      src-org
                                                             :destination-organization dst-org
                                                             :dry-run                  false}))]
      (is (= 1 assigned-cnt))
      (is (empty? conflicted)))

    (testing "get preview before laundry generates one"
      (let [{:keys [status body headers]} (doc/get-preview system id dst-org)
            messages                      (test-system/messages true)]
        (is (= 200 status))
        (is (instance? BufferedInputStream body)
            "placeholder image returned")
        (is (= {"Content-Type"        "image/jpeg"
                "Content-Disposition" "inline"}
               headers))
        (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
               messages)
            "preview jpeg conversion requested (again) from laundry (using the original src-org bucket)")))

    (testing "manually upload preview"
      (let [preview-contents (str (random-uuid))
            file             (make-temp-file preview-contents)
            ;; Again, we upload to the original src bucket, as we are not moving the files
            bucket           (os/org-preview-bucket src-org (-> system :storage :bucket-suffix))]
        (os/upload (:gcs system) bucket id {:content      file
                                            :content-type "text/plain"})

        (testing "get preview"
          (let [{:keys [status body headers]} (doc/get-preview system id dst-org)
                messages                      (test-system/messages true)]
            (is (= 200 status))
            (is (= preview-contents (is->contents body))
                "preview contents returned")
            (is (= {"Content-Type" "text/plain"} headers))
            (is (empty? messages)
                "no preview conversion needed anymore")))))))

(deftest upload-metadata-test
  (let [system   (test-system/system)
        src-org  "123-R"
        dst-org  "999-R"
        user     {:id        "1"
                  :username  "user"
                  :firstName "first"
                  :lastName  "last"}
        contents (str (random-uuid))
        file     (make-temp-file contents)
        id       (str (random-uuid))
        metadata (base-metadata src-org)
        now      (Date.)]

    (testing "upload new document"
      (let [{:keys [status body]} (upload-valid-doc! system src-org id false file "text/plain" metadata)
            messages              (test-system/messages true)]
        (is (= 200 status))
        (is (= "OK" body))
        (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
               messages)
            "preview jpeg conversion requested from laundry")))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    ;; Run migration and block until done
    (with-redefs [job/now (fn [] now)]
      (let [{:keys [assigned-cnt
                    conflicted]} (<!! (job/move-to-org system {:source-organization      src-org
                                                               :destination-organization dst-org
                                                               :dry-run                  false}))]
        (is (= 1 assigned-cnt))
        (is (empty? conflicted))))

    (testing "get document metadata"
      (let [{:keys [status body]} (doc/document-metadata system id dst-org false)]
        (is (= 200 status))
        (is (= {:source       "lupapiste"
                :organization dst-org
                :contentType  "text/plain"
                :fileId       id
                :metadata     (-> (#'ea/stringify-values metadata)
                                  (update :arkistointipvm date->str)
                                  (update :paatospvm date->str)
                                  (update :location-wgs84 xy->point)
                                  (assoc :location-docstore (-> metadata :location-wgs84 xy->point first))
                                  (dissoc :organization)
                                  (assoc :history [{:modified (date->str now)
                                                    :field    "organization"
                                                    :old-val  src-org
                                                    :new-val  dst-org}
                                                   {:modified (date->str now)
                                                    :field    "id"
                                                    ;; Only the org -prefix changes
                                                    :old-val  (doc-id src-org id)
                                                    :new-val  (doc-id dst-org id)}]))}
               (dissoc body :modified :sha256)))

        (testing "update metadata"
          (let [new-addr              (str (random-uuid))
                new-meta              (merge metadata {:address new-addr})
                {:keys [status body]} (fu/update-metadata system id new-meta user dst-org)]
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

(deftest conflict-test
  (let [system       (test-system/system)
        src-org      "123-R"
        dst-org      "999-R"
        user         {:id        "1"
                      :username  "user"
                      :firstName "first"
                      :lastName  "last"}
        src-contents (str (random-uuid))
        src-file     (make-temp-file src-contents)
        src-metadata (base-metadata src-org)
        dst-contents (str (random-uuid))
        dst-file     (make-temp-file dst-contents)
        dst-metadata (base-metadata dst-org)
        id           (str (random-uuid))
        new-id       (str (random-uuid))
        now          (Date.)]

    (testing "upload new documents for both orgs"
      (testing "upload document to destination org"
        (let [{:keys [status body]} (upload-valid-doc! system dst-org id false dst-file "text/plain" dst-metadata)
              messages              (test-system/messages true)]
          (is (= 200 status))
          (is (= "OK" body))
          (is (= {"to-conversion-service" [(expected-convert-message dst-org id)]}
                 messages)
              "preview jpeg conversion requested from laundry")))
      (testing "upload document to source org"
        (let [{:keys [status body]} (upload-valid-doc! system src-org id false src-file "text/plain" src-metadata)
              messages              (test-system/messages true)]
          (is (= 200 status))
          (is (= "OK" body))
          (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
                 messages)
              "preview jpeg conversion requested from laundry"))))

    ;; Make sure ES index is up-to-date immediately after uploading, otherwise reading
    ;; the document too soon might fail
    (test-system/refresh-es-index)

    ;; Run migration and block until done
    (with-redefs [job/now         (fn [] now)
                  job/generate-id (fn [] new-id)]
      (let [{:keys [assigned-cnt
                    conflicted]} (<!! (job/move-to-org system {:source-organization      src-org
                                                               :destination-organization dst-org
                                                               :dry-run                  false}))]
        (is (= 1 assigned-cnt))
        (is (= {id new-id}
               conflicted)
            "id was altered during job to avoid conflict")))

    ;; At this point all external systems ought to be notified that the ID has been changed,
    ;; and any subsequent API calls should use the new id.

    (testing "get document with new id"
      (let [{:keys [content-fn content-type]} (doc/get-document system new-id dst-org)]
        (is (= "text/plain" content-type))
        (is (= src-contents (is->contents (content-fn))))))

    (testing "get document metadata with new id"
      (let [{:keys [status body]} (doc/document-metadata system new-id dst-org false)]
        (is (= 200 status))
        (is (= {:source       "lupapiste"
                :organization dst-org
                :contentType  "text/plain"
                :fileId       id
                :metadata     (-> (#'ea/stringify-values src-metadata)
                                  (update :arkistointipvm date->str)
                                  (update :paatospvm date->str)
                                  (update :location-wgs84 xy->point)
                                  (assoc :location-docstore (-> src-metadata :location-wgs84 xy->point first))
                                  (dissoc :organization)
                                  (assoc :history [{:modified (date->str now)
                                                    :field    "organization"
                                                    :old-val  src-org
                                                    :new-val  dst-org}
                                                   {:modified (date->str now)
                                                    :field    "id"
                                                    ;; both org prefix and actual id changes
                                                    :old-val  (doc-id src-org id)
                                                    :new-val  (doc-id dst-org new-id)}]))}
               (dissoc body :modified :sha256)))

        (testing "update metadata"
          (let [new-addr              (str (random-uuid))
                new-meta              (merge src-metadata {:address new-addr})
                {:keys [status body]} (fu/update-metadata system new-id new-meta user dst-org)]
            (is (= 200 status))
            (is (= body
                   (-> src-metadata
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
                              :location (:location-etrs-tm35fin src-metadata)
                              :permit-expired-date nil
                              :demolished nil
                              :demolished-date nil
                              :kuntalupatunnukset-muutossyy nil
                              :metadata (select-keys src-metadata [:henkilotiedot
                                                                   :julkisuusluokka
                                                                   :kieli
                                                                   :myyntipalvelu
                                                                   :nakyvyys
                                                                   :sailytysaika
                                                                   :tila])))))))))

    (testing "upload again with new id"
      ;; Make sure we refresh the metadata to match the new org
      (let [new-metadata (assoc src-metadata :organization dst-org)]
        (testing "upload again without overwrite"
          (let [{:keys [status body]} (upload-valid-doc! system dst-org new-id false src-file "text/plain" new-metadata)
                messages              (test-system/messages true)]
            (is (= 409 status))
            (is (= (format "Object with id %s already exists and no overwrite was requested." new-id)
                   body))
            (is (empty? messages))))
        (testing "upload again with overwrite"
          (let [{:keys [status body]} (upload-valid-doc! system dst-org new-id true src-file "text/plain" new-metadata)
                messages              (test-system/messages true)]
            (is (= 200 status))
            (is (= "OK" body))
            (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
                   messages)
                "preview jpeg conversion requested from laundry (using the original src-org bucket and fileId)")))))

    (testing "get preview with new id before laundry generates one"
      (let [{:keys [status body headers]} (doc/get-preview system new-id dst-org)
            messages                      (test-system/messages true)]
        (is (= 200 status))
        (is (instance? BufferedInputStream body)
            "placeholder image returned")
        (is (= {"Content-Type"        "image/jpeg"
                "Content-Disposition" "inline"}
               headers))
        (is (= {"to-conversion-service" [(expected-convert-message src-org id)]}
               messages)
            "preview jpeg conversion requested (again) from laundry (using the original src-org bucket and fileId)")))

    (testing "manually upload preview with new id"
      (let [preview-contents (str (random-uuid))
            file             (make-temp-file preview-contents)
            ;; Again, we upload to the original src bucket and fileId, as we are not moving the files
            bucket           (os/org-preview-bucket src-org (-> system :storage :bucket-suffix))]
        (os/upload (:gcs system) bucket id {:content      file
                                            :content-type "text/plain"})

        (testing "get preview with new id"
          (let [{:keys [status body headers]} (doc/get-preview system new-id dst-org)
                messages                      (test-system/messages true)]
            (is (= 200 status))
            (is (= preview-contents (is->contents body))
                "preview contents returned")
            (is (= {"Content-Type" "text/plain"} headers))
            (is (empty? messages)
                "no preview conversion needed anymore")))))))

;; Moving documents between organizations is rare. But at least in theory we might one day
;; move such documents again and again so test for that
(deftest merge-orgs-linear-test
  ;; Merge org-1 into org-2 and then org-2 into org-3
  (let [system       (test-system/system)
        ;; Three organizations
        org-1        "111-R"
        org-2        "222-R"
        org-3        "333-R"
        ;; Each org has two files: A and B
        a-id         (str (random-uuid))
        b-id         (str (random-uuid))
        a-1-contents (str (random-uuid))
        a-1-file     (make-temp-file a-1-contents)
        a-1-metadata (base-metadata org-1)
        b-1-contents (str (random-uuid))
        b-1-file     (make-temp-file b-1-contents)
        b-1-metadata (base-metadata org-1)
        a-2-contents (str (random-uuid))
        a-2-file     (make-temp-file a-2-contents)
        a-2-metadata (base-metadata org-2)
        b-2-contents (str (random-uuid))
        b-2-file     (make-temp-file b-2-contents)
        b-2-metadata (base-metadata org-2)
        a-3-contents (str (random-uuid))
        a-3-file     (make-temp-file a-3-contents)
        a-3-metadata (base-metadata org-3)
        b-3-contents (str (random-uuid))
        b-3-file     (make-temp-file b-3-contents)
        b-3-metadata (base-metadata org-3)]

    (testing "upload 2 documents for each org"
      (doseq [[org id file metadata] [[org-1 a-id a-1-file a-1-metadata]
                                      [org-1 b-id b-1-file b-1-metadata]
                                      [org-2 a-id a-2-file a-2-metadata]
                                      [org-2 b-id b-2-file b-2-metadata]
                                      [org-3 a-id a-3-file a-3-metadata]
                                      [org-3 b-id b-3-file b-3-metadata]]]
        (testing "upload document to org"
          (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
                messages              (test-system/messages true)]
            (is (= 200 status))
            (is (= "OK" body))
            (is (= {"to-conversion-service" [(expected-convert-message org id)]}
                   messages)
                "preview jpeg conversion requested from laundry")))))

    (test-system/refresh-es-index)

    ;; Run migration from org-1 to org-2
    (let [{:keys [assigned-cnt
                  conflicted]} (<!! (job/move-to-org system {:source-organization      org-1
                                                             :destination-organization org-2
                                                             :dry-run                  false}))
          a-id-1               (get conflicted a-id)
          b-id-1               (get conflicted b-id)]
      ;; Both orgs have files A and B so we get two collisions
      ;; and the files from org-1 get a new prefix
      (is (= 2 assigned-cnt))
      (is (string? a-id-1))
      (is (string? b-id-1))

      (testing "org-2 has 4 documents"
        (doseq [[id contents] [;; Migrated from org-1
                               [a-id-1 a-1-contents]
                               [b-id-1 b-1-contents]
                               ;; Original unaltered docs from org-2
                               [a-id a-2-contents]
                               [b-id b-2-contents]]]
          (testing "get document with new id"
            (let [{:keys [content-fn content-type]} (doc/get-document system id org-2)]
              (is (= "text/plain" content-type))
              (is (= contents (is->contents (content-fn))))))))

      ;; Run another migration, this time from org-2 -> org-3
      (let [{:keys [assigned-cnt
                    conflicted]} (<!! (job/move-to-org system {:source-organization      org-2
                                                               :destination-organization org-3
                                                               :dry-run                  false}))
            a-id-2               (get conflicted a-id)
            b-id-2               (get conflicted b-id)]
        ;; Org 2 had A, B and A-1 and B-1
        ;; Org 3 had A and B -> We get two conflicts
        (is (= 4 assigned-cnt))
        (is (string? a-id-2))
        (is (string? b-id-2))

        (testing "org-3 has 6 documents"
          (doseq [[id contents] [;; Migrated from org-2, but originally from org-1
                                 [a-id-1 a-1-contents]
                                 [b-id-1 b-1-contents]
                                 ;; Migrated from org-2
                                 [a-id-2 a-2-contents]
                                 [b-id-2 b-2-contents]
                                 ;; Original unaltered docs from org-3
                                 [a-id a-3-contents]
                                 [b-id b-3-contents]]]
            (testing "get document with new id"
              (let [{:keys [content-fn content-type]} (doc/get-document system id org-3)]
                (is (= "text/plain" content-type))
                (is (= contents (is->contents (content-fn))))))))))))

(deftest merge-orgs-to-same-test
  ;; Merge org-1 into org-2 and then org-3 into org-2
  (let [system       (test-system/system)
        ;; Three organizations
        org-1        "111-R"
        org-2        "222-R"
        org-3        "333-R"
        ;; Each org has two files: A and B
        a-id         (str (random-uuid))
        b-id         (str (random-uuid))
        a-1-contents (str (random-uuid))
        a-1-file     (make-temp-file a-1-contents)
        a-1-metadata (base-metadata org-1)
        b-1-contents (str (random-uuid))
        b-1-file     (make-temp-file b-1-contents)
        b-1-metadata (base-metadata org-1)
        a-2-contents (str (random-uuid))
        a-2-file     (make-temp-file a-2-contents)
        a-2-metadata (base-metadata org-2)
        b-2-contents (str (random-uuid))
        b-2-file     (make-temp-file b-2-contents)
        b-2-metadata (base-metadata org-2)
        a-3-contents (str (random-uuid))
        a-3-file     (make-temp-file a-3-contents)
        a-3-metadata (base-metadata org-3)
        b-3-contents (str (random-uuid))
        b-3-file     (make-temp-file b-3-contents)
        b-3-metadata (base-metadata org-3)]

    (testing "upload 2 documents for each org"
      (doseq [[org id file metadata] [[org-1 a-id a-1-file a-1-metadata]
                                      [org-1 b-id b-1-file b-1-metadata]
                                      [org-2 a-id a-2-file a-2-metadata]
                                      [org-2 b-id b-2-file b-2-metadata]
                                      [org-3 a-id a-3-file a-3-metadata]
                                      [org-3 b-id b-3-file b-3-metadata]]]
        (testing "upload document to org"
          (let [{:keys [status body]} (upload-valid-doc! system org id false file "text/plain" metadata)
                messages              (test-system/messages true)]
            (is (= 200 status))
            (is (= "OK" body))
            (is (= {"to-conversion-service" [(expected-convert-message org id)]}
                   messages)
                "preview jpeg conversion requested from laundry")))))

    (test-system/refresh-es-index)

    ;; Run migration from org-1 to org-2
    (let [{:keys [assigned-cnt
                  conflicted]} (<!! (job/move-to-org system {:source-organization      org-1
                                                             :destination-organization org-2
                                                             :dry-run                  false}))
          a-id-1               (get conflicted a-id)
          b-id-1               (get conflicted b-id)]
      ;; Both orgs have files A and B so we get two collisions
      ;; and the files from org-1 get a new prefix
      (is (= 2 assigned-cnt))
      (is (string? a-id-1))
      (is (string? b-id-1))

      (testing "org-2 has 4 documents"
        (doseq [[id contents] [;; Migrated from org-1
                               [a-id-1 a-1-contents]
                               [b-id-1 b-1-contents]
                               ;; Original unaltered docs from org-2
                               [a-id a-2-contents]
                               [b-id b-2-contents]]]
          (testing "get document with new id"
            (let [{:keys [content-fn content-type]} (doc/get-document system id org-2)]
              (is (= "text/plain" content-type))
              (is (= contents (is->contents (content-fn))))))))

      ;; Run another migration, this time from org-3 -> org-2
      (let [{:keys [assigned-cnt
                    conflicted]} (<!! (job/move-to-org system {:source-organization      org-3
                                                               :destination-organization org-2
                                                               :dry-run                  false}))
            a-id-3               (get conflicted a-id)
            b-id-3               (get conflicted b-id)]
        ;; Org 2 had A, B and A-1 and B-1
        ;; Org 3 had A and B -> We get two conflicts
        (is (= 2 assigned-cnt))
        (is (string? a-id-3))
        (is (string? b-id-3))

        (testing "org-3 has 6 documents"
          (doseq [[id contents] [;; Migrated from org-1
                                 [a-id-1 a-1-contents]
                                 [b-id-1 b-1-contents]
                                 ;; Migrated from org-3
                                 [a-id-3 a-3-contents]
                                 [b-id-3 b-3-contents]
                                 ;; Original unaltered docs from org-2
                                 [a-id a-2-contents]
                                 [b-id b-2-contents]]]
            (testing "get document with new id"
              (let [{:keys [content-fn content-type]} (doc/get-document system id org-2)]
                (is (= "text/plain" content-type))
                (is (= contents (is->contents (content-fn))))))))))))

(deftest es-id->public-id-test
  (are [id] (thrown-with-msg? RuntimeException
                              #"Failed to parse public-id"
                              (job/es-id->public-id "123-R" "my-suffix" id))
    nil
    ""
    "asdasd"
    "arkisto-123-r-my-suffix-"
    "arkisto-999-r-my-suffix-foo"
    "arkisto-123-r-my-SUFFIX-foo")
  (is (= "foo" (job/es-id->public-id "123-R" "my-suffix" "arkisto-123-r-my-suffix-foo"))))
