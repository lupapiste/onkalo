(ns onkalo.metadata.xmp-processor
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.walk :refer [postwalk keywordize-keys]]
            [clojure.xml :as xml]
            [lupapiste-commons.schema-utils :as su]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.metadata.xml-utils :as xml-utils]
            [taoensso.timbre :as timbre])
  (:import [com.lowagie.text.pdf PdfReader PdfStamper]
           [com.sun.org.apache.xerces.internal.impl.io MalformedByteSequenceException]
           [java.io ByteArrayInputStream File InputStream ByteArrayOutputStream]
           [java.nio.charset Charset]
           [java.util Date]
           [java.text SimpleDateFormat]
           [org.apache.commons.imaging Imaging]
           [org.apache.commons.imaging.formats.tiff TiffImageMetadata]
           [org.apache.commons.imaging.formats.tiff.constants TiffTagConstants]
           [org.apache.commons.imaging.formats.tiff.write TiffImageWriterLossy]
           [org.xml.sax SAXParseException]))

(def base-xmlns "onkalo")

(defn- ->iso-8601-date [date]
  (let [format (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssXXX")]
    (.format format date)))

(def default-xmp
  {:tag :x:xmpmeta,
   :attrs {:x:xmptk "Onkalo XMP-processor", :xmlns:x "adobe:ns:meta/"},
   :content [{:tag :rdf:RDF, :attrs {:xmlns:rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#"}}]})

(defn- parse-byte-array [^bytes b]
  (try
    (xml/parse (ByteArrayInputStream. b) xml-utils/startparse-sax-no-doctype)
    (catch SAXParseException ex
      (timbre/warn "Could not parse existing metadata:" ex (String. b "UTF-8"))
      default-xmp)
    (catch MalformedByteSequenceException ex
      (timbre/warn "Existing metadata is not valid UTF-8:" ex (String. b "UTF-8"))
      default-xmp)))

(defn- pdf-metadata-utf8-bytes [^PdfReader reader]
  (if (= "UTF-8" (str (Charset/defaultCharset)))
    (.getMetadata reader)
    ; Seems that the metadata is returned in platform default charset.
    ; Create an intermediate String with that charset, and return UTF-8 bytes
    (.getBytes (String. (.getMetadata reader)) "UTF-8")))

(declare entry->xml-elem)

(defn- clj-val->xmp-val [val]
  (cond
    (true? val) "True"
    (false? val) "False"
    (keyword? val) (name val)
    (instance? Date val) (->iso-8601-date val)
    :else (str val)))

(defn- clj-seq->rdf-seq [vals base-xmlns]
  {:tag :rdf:Seq
   :content (map (fn [v] {:tag :rdf:li
                          :attrs (if (map? v) {:rdf:parseType "Resource"} {})
                          :content (if (map? v)
                                     (map #(entry->xml-elem % base-xmlns) v)
                                     [(clj-val->xmp-val v)])})
                 vals)})

(defn- entry->xml-elem [[k v] base-xmlns]
  {:tag (keyword (str base-xmlns ":" (name k)))
   :attrs (if (map? v) {:rdf:parseType "Resource"} {})
   :content (cond
              (map? v) (map #(entry->xml-elem % (name k)) v)
              (sequential? v) [(clj-seq->rdf-seq v (name k))]
              :else [(clj-val->xmp-val v)])})

(defn- namespace-elements [acc [k v]]
  (if-let [complex-type (cond
                          (map? v) v
                          (and (sequential? v) (map? (first v))) (first v))]
    (-> (reduce namespace-elements acc complex-type)
        (assoc (keyword (str "xmlns:" (name k))) (str "http://onkalo.solita.fi/metadata/" (name k) "/")))
    acc))

(defn- generate-xmlns-map [metadata]
  (let [subnamespaces (->> metadata
                           (reduce namespace-elements {}))]
    (merge {:xmlns:onkalo "http://onkalo.solita.fi/metadata/"}
           subnamespaces
           {:rdf:about ""})))

(defn- metadata-map->rdf-metadata [metadata]
  {:tag :rdf:RDF
   :content [{:tag :rdf:Description
              :attrs (generate-xmlns-map metadata)
              :content (map #(entry->xml-elem % base-xmlns) metadata)}]})

(defn- ->value-type [key value]
  (cond
    (map? value) (name key)
    (and (sequential? value) (map? (first value))) (str "seq " (name key))
    (sequential? value) (str "seq " (->value-type key (first value)))
    (integer? value) "Integer"
    (float? value) "Real"
    (rational? value) "Real"
    (true? value) "Boolean"
    (false? value) "Boolean"
    (instance? Date value) "Date"
    :else "Text"))

(defn- entry->pdfa-property-def [[k v]]
  {:tag :rdf:li
   :attrs {:rdf:parseType "Resource"}
   :content [{:tag :pdfaProperty:name :content [(name k)]}
             {:tag :pdfaProperty:valueType :content [(->value-type k v)]}
             {:tag :pdfaProperty:category :content ["internal"]}
             {:tag :pdfaProperty:description :content [(str "Metadata value for " (name k))]}]})

(defn- entry->pdfa-field-def [[k v]]
  {:tag :rdf:li
   :attrs {:rdf:parseType "Resource"}
   :content [{:tag :pdfaField:name :content [(name k)]}
             {:tag :pdfaField:valueType :content [(->value-type k v)]}
             {:tag :pdfaField:description :content [(str "Metadata value for " (name k))]}]})

(defn- ->schema-value-type [name submap]
  {:tag :rdf:li
   :attrs {:rdf:parseType "Resource"}
   :content [{:tag :pdfaType:type :content [name]}
             {:tag :pdfaType:namespaceURI :content [(str "http://onkalo.solita.fi/metadata/" name "/")]}
             {:tag :pdfaType:prefix :content [name]}
             {:tag :pdfaType:description :content [(str "Subvalues for " name)]}
             {:tag :pdfaType:field :content [{:tag :rdf:Seq
                                              :content (->> submap
                                                            (remove map?)
                                                            (map entry->pdfa-field-def))}]}]})

(defn- map->sub-schema [elems [k v]]
  (if-let [a-map (cond (map? v) v
                       (and (sequential? v) (map? (first v))) (first v))]
    (-> (reduce map->sub-schema elems a-map)
        (conj (->schema-value-type (name k) a-map)))
    elems))

(defn- gather-sub-schemas [metadata]
  (let [rdf-li-items (->> metadata
                          (reduce map->sub-schema [])
                          (remove nil?))]
    {:tag :pdfaSchema:valueType
     :content [{:tag :rdf:Seq
                :content rdf-li-items}]}))

(defn- gather-schema-properties [metadata]
  {:tag :pdfaSchema:property
   :content [{:tag :rdf:Seq
              :content (map entry->pdfa-property-def metadata)}]})

(defn- metadata-map->xmp-extension-map [metadata]
  (let [sub-schemas (gather-sub-schemas metadata)
        properties (gather-schema-properties metadata)
        schema-resource (io/resource "extensionschema.xml")]
    (with-open [schema-is (io/input-stream schema-resource)]
      (postwalk
        (fn [form]
          (if-let [element (first (filter #(= :replaceWithContent (:tag %)) (:content form)))]
            (update form :content (fn [content]
                                    (-> (remove #(= % element) content)
                                        (conj properties sub-schemas))))
            form))
        (xml/parse schema-is xml-utils/startparse-sax-no-doctype)))))

(defn- merge-extension-schema [original-rdf extension-rdf]
  (let [updated-rdf (postwalk
                      (fn [{:keys [tag attrs] :as form}]
                        (cond
                          ;; rdf:Description might not have all required pdfa namespaces defined so we merge the attrs from our extension schema
                          (and (= :rdf:Description tag)
                               (:xmlns:pdfaExtension attrs)) (assoc form :attrs (merge attrs (get-in extension-rdf [:content 0 :attrs])))
                          ;; Remove previous onkalo schema, if any, and conj the new one into the list of extension schemas
                          (= :pdfaExtension:schemas tag) (update-in form [:content 0 :content]
                                                                            (fn [content]
                                                                              (-> (filter (fn [li-elem]
                                                                                            (empty? (filter #(= base-xmlns (first (:content %))) (:content li-elem))))
                                                                                          content)
                                                                                  (conj (get-in extension-rdf [:content 0 :content 0 :content 0 :content 0])))))
                          :else form))
                      original-rdf)]
    (if (= original-rdf updated-rdf)
      (meta-merge original-rdf extension-rdf)
      updated-rdf)))

(defn- merge-custom-metadata [original-rdf custom-metadata]
  (let [updated-rdf (postwalk
                      (fn [form]
                        (if (and (= :rdf:Description (:tag form)) (get-in form [:attrs :xmlns:onkalo]))
                          (merge form (get-in custom-metadata [:content 0]))
                          form))
                      original-rdf)]
    (if (= original-rdf updated-rdf)
      (meta-merge original-rdf custom-metadata)
      updated-rdf)))

(defn- generate-merged-rdf [parsed-xmp-xml metadata]
  (let [original-rdf (get-in parsed-xmp-xml [:content 0])
        extension-schema (metadata-map->xmp-extension-map metadata)
        merged-schema (merge-extension-schema original-rdf extension-schema)
        custom-metadata (metadata-map->rdf-metadata metadata)]
    (->> (merge-custom-metadata merged-schema custom-metadata)
         (assoc-in parsed-xmp-xml [:content 0])
         (xml-utils/element->string-builder! (StringBuilder.))
         (.toString))))

(defn- add-metadata-to-pdf-file [^File file reader rdf-xml]
  (let [temp-file (File/createTempFile (.getName file) ".tmp")]
    (with-open [os (io/output-stream temp-file)
                stamper (PdfStamper. reader os)]
      (.setXmpMetadata stamper (.getBytes rdf-xml)))
    (io/copy temp-file file)
    (io/delete-file temp-file :silently)))

(defn- write-metadata-to-pdf! [^File file metadata]
  (with-open [is (io/input-stream file)
              reader (PdfReader. is)]
    ;; Only alter the file when it is not signed
    (if (zero? (-> (.getAcroFields reader) (.getSignedFieldNames) count))
      (let [existing-metadata (pdf-metadata-utf8-bytes reader)
            metadata-xml (if (> (count existing-metadata) 0)
                           (parse-byte-array existing-metadata)
                           default-xmp)]
        (->> (generate-merged-rdf metadata-xml metadata)
             (add-metadata-to-pdf-file file reader)))
      (timbre/warn "Skipping XMP metadata writing because the PDF file is signed"))))

(defn- write-updated-tiff-to-output-stream! [xml-str ^TiffImageMetadata tiff-metadata metadata output-stream]
  (let [metadata-xml (if xml-str
                       (parse-byte-array (.getBytes xml-str "utf-8"))
                       default-xmp)
        rdf-xml (generate-merged-rdf metadata-xml metadata)
        tiff-output-set (.getOutputSet tiff-metadata)
        byte-order (-> tiff-metadata .-contents .-header .-byteOrder)
        root-directory (.getRootDirectory tiff-output-set)
        writer (TiffImageWriterLossy. byte-order)]
    (doto root-directory
      (.removeField TiffTagConstants/TIFF_TAG_XMP)
      (.add TiffTagConstants/TIFF_TAG_XMP ^bytes (.getBytes rdf-xml "utf-8")))
    (.write writer output-stream tiff-output-set)))

(defn- write-metadata-to-tiff! [^File file metadata]
  (let [xml-str (Imaging/getXmpXml file)
        tiff-metadata (Imaging/getMetadata file)
        temp-file (File/createTempFile (.getName file) ".tmp")]
    (with-open [os (io/output-stream temp-file)]
      (write-updated-tiff-to-output-stream! xml-str tiff-metadata metadata os))
    (io/copy temp-file file)
    (io/delete-file temp-file :silently)))

(defn write-metadata-to-file! [^File file metadata content-type]
  (case content-type
    "application/pdf" (write-metadata-to-pdf! file metadata)
    "image/tiff" (write-metadata-to-tiff! file metadata)
    file))

(defn find-type [name-tag value-tag {:keys [tag content]}]
  (when (and (= :rdf:li tag) (seq (filter #(= name-tag (:tag %)) content)))
    (let [k (->> (filter #(= name-tag (:tag %)) content)
                 (first)
                 (:content)
                 (first))
          v (->> (filter #(= value-tag (:tag %)) content)
                 (first)
                 (:content)
                 (first))]
      {k v})))

(defn find-complex-type [{:keys [tag content]}]
  (when (and (= :rdf:li tag) (seq (filter #(= :pdfaType:type (:tag %)) content)))
    (let [k (->> (filter #(= :pdfaType:type (:tag %)) content)
                 (first)
                 (:content)
                 (first))
          v (->> (filter #(= :pdfaType:field (:tag %)) content)
                 (first)
                 (:content)
                 (first)
                 (:content)
                 (map #(find-type :pdfaField:name :pdfaField:valueType %))
                 (into {}))]
      {k v})))

(defn find-types [{:keys [content]} type-fn]
  (->> (reduce (fn [acc node]
                 (conj acc (or (type-fn node)
                               (find-types node type-fn))))
               []
               content)
       (flatten)))

(defn build-attribute-type-map [xml]
  (let [extension-schema (->> (get-in xml [:content 0 :content])
                              (filter (fn [rdf] (= :pdfaExtension:schemas (:tag (first (:content rdf))))))
                              (first))
        value-types (into {} (find-types extension-schema (partial find-type :pdfaProperty:name :pdfaProperty:valueType)))]
    (->> (into {} (find-types extension-schema find-complex-type))
         (merge value-types))))

(defn- process-metadata-node [{:keys [tag content]} ignore-ns?]
  (when (or ignore-ns? (and tag (.startsWith (name tag) (str base-xmlns ":"))))
    (let [key (last (s/split (name tag) #":"))
          value (cond
                  (= (:tag (first content)) :rdf:Seq) (map #(first (:content %)) (:content (first content)))
                  (map? (first content)) (->> (map #(process-metadata-node % :ignore-ns) content)
                                              (into {}))
                  :else (first content))]
      [key value])))

(defn- coerce-value [type v]
  (case type
    "Real" (Double/parseDouble v)
    "Integer" (Long/parseLong v)
    "Boolean" (if (= v "True") true false)
    "Date" (su/parse-iso-8601-date v)
    "Text" v))

(defn- coerce-values [type-map [k v]]
  [k
   (if-let [t (get type-map k)]
     (cond
       (and (sequential? v) (map? (first v))) (map #(coerce-values t [k %]) v)
       (sequential? v) (map #(coerce-value (second (s/split t #" ")) %) v)
       (map? v) (into {} (map #(coerce-values t %) v))
       :else (coerce-value t v))
     v)])

(defn- collect-onkalo-data [results xml-elems]
  (reduce
    (fn [acc node]
      (let [new-acc (conj acc (process-metadata-node node false))]
        (->> (collect-onkalo-data new-acc (:content node))
             (remove nil?))))
    results
    xml-elems))

(defn- xml-str->metadata-map [md-byte-array]
  (let [metadata-xml (parse-byte-array md-byte-array)
        type-map (build-attribute-type-map metadata-xml)]
    (->> (collect-onkalo-data [] (:content metadata-xml))
         (map #(coerce-values type-map %))
         (into {})
         keywordize-keys)))

(defn pdf-metadata->metadata-map [^File file]
  (with-open [is (io/input-stream file)
              reader (PdfReader. is)]
    (xml-str->metadata-map (pdf-metadata-utf8-bytes reader))))

(defn tiff-metadata->metadata-map [^File file]
  (-> (Imaging/getXmpXml file)
      (.getBytes "utf-8")
      (xml-str->metadata-map)))

(defn read-metadata-map [^File file content-type]
  (case content-type
    "application/pdf" (pdf-metadata->metadata-map file)
    "image/tiff" (tiff-metadata->metadata-map file)))

(defn remove-metadata-from-pdf [^InputStream is]
  (let [os (ByteArrayOutputStream.)]
    (with-open [is (io/input-stream is)
                reader (PdfReader. is)
                stamper (PdfStamper. reader os)]
      ;; Only alter the file when it is not signed
      (when (zero? (-> (.getAcroFields reader) (.getSignedFieldNames) count))
        (let [existing-metadata (pdf-metadata-utf8-bytes reader)
              metadata-xml (if (> (count existing-metadata) 0)
                             (parse-byte-array existing-metadata)
                             default-xmp)
              rdf-xml (generate-merged-rdf metadata-xml {})]
          (.setXmpMetadata stamper (.getBytes rdf-xml)))))
    ;; Closing the stamper writes to output stream
    (io/input-stream (.toByteArray os))))

(defn remove-metadata-from-tiff [^InputStream is]
  (let [temp-os (ByteArrayOutputStream.)
        final-os (ByteArrayOutputStream.)]
    (with-open [is (io/input-stream is)]
      (io/copy is temp-os)
      (let [bytes (.toByteArray temp-os)
            xml-str (Imaging/getXmpXml bytes)
            tiff-metadata (Imaging/getMetadata bytes)]
        (write-updated-tiff-to-output-stream! xml-str tiff-metadata {} final-os)))
    (io/input-stream (.toByteArray final-os))))

(defn ^InputStream remove-metadata [^InputStream is content-type]
  (case content-type
    "application/pdf" (remove-metadata-from-pdf is)
    "image/tiff" (remove-metadata-from-tiff is)))
