(ns onkalo.metadata.xml-utils
  (:require [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import [java.io ByteArrayInputStream]
           [javax.xml XMLConstants]
           [javax.xml.parsers SAXParserFactory]
           [org.xml.sax.helpers DefaultHandler]))

;; Safer version of clojure.xml/startparse-sax
(defn startparse-sax-no-doctype [^ByteArrayInputStream s ^DefaultHandler ch]
  (-> (doto (SAXParserFactory/newInstance)
        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (.newSAXParser)
      (.parse s ch)))

(defn xml-escape [str]
  (str/escape str {\& "&amp;" \< "&lt;"}))

(defn element->string-builder!
  "Outputs the XML element to the StringBuilder given as argument.
   Based on clojure.xml/emit-element"
  [^StringBuilder b e]
  (cond
    (number? e)
    (.append b (str e))

    (string? e)
    (.append b (xml-escape e))

    :else
    (do
      (.append b "<")
      (.append b (name (:tag e)))
      (when (:attrs e)
        (doseq [attr (:attrs e)]
          (doto b
            (.append " ")
            (.append (name (key attr)))
            (.append "='")
            (.append (val attr))
            (.append "'"))))
      (if (:content e)
        (do
          (.append b ">")
          (doseq [c (:content e)]
            (if c
              (element->string-builder! b c)
              (timbre/warn "nil content encountered in element" (:tag e))))
          (doto b
            (.append "</")
            (.append (name (:tag e)))
            (.append ">")))
        (.append b "/>"))))
  b)
