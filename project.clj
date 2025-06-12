(defproject onkalo "0.1.0-SNAPSHOT"
  :description "Lupapiste electronic archival service"
  :url "https://www.lupapiste.fi"
  :license {:name         "European Union Public Licence v. 1.2"
            :url          "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"
            :distribution :manual}
  :repositories {"osgeo" {:url "https://repo.osgeo.org/repository/release/"}}
  :min-lein-version "2.5.1"
  :dependencies [[org.clojure/clojure "1.11.4"]
                 [org.clojure/core.memoize "1.0.257"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.zip "1.0.0"]
                 [com.stuartsierra/component "1.1.0"]
                 [ring "1.10.0"]
                 [ring/ring-core "1.10.0" :exclusions [commons-io]]
                 [info.sunng/ring-jetty9-adapter "0.22.0"]
                 [suspendable "0.1.1"]
                 [compojure "1.7.0"]
                 [duct "0.7.0"]
                 [meta-merge "1.0.0"]
                 [ring "1.10.0"]
                 [ring/ring-defaults "0.3.4"]
                 [com.taoensso/timbre "6.1.0"]
                 [viesti/timbre-json-appender "0.2.14"]
                 [org.apache.httpcomponents/httpcore "4.4.16"]
                 [org.apache.httpcomponents/httpclient "4.5.14"]
                 [clj-http "3.12.3"]
                 [cheshire "5.12.0"]
                 ;; Use newer version of core.async
                 [org.clojure/core.async "1.6.681"]
                 [cc.qbits/spandex "0.8.2" :exclusions [cheshire org.clojure/core.async]]
                 [clj-time "0.15.2"]
                 [prismatic/schema "1.4.1"]
                 [metosin/compojure-api "2.0.0-alpha31" :exclusions [prismatic/schema]]
                 [metosin/muuntaja "0.6.8"]
                 [pandect "1.0.2"]
                 [metosin/potpuri "0.5.3"]

                 [com.fzakaria/slf4j-timbre "0.3.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.36"]
                 [org.slf4j/jul-to-slf4j "1.7.36"]
                 [org.slf4j/jcl-over-slf4j "1.7.36"]

                 ;; Explicit dep to this version to avoid conflicts from buddy and others:
                 [com.fasterxml.jackson.core/jackson-databind "2.15.2"]
                 [com.fasterxml.jackson.datatype/jackson-datatype-jsr310 "2.15.2"]

                 ;; Define explicit version of guava for compatibility
                 [com.google.guava/guava "32.0.1-jre"]
                 ;; GCS Library
                 ;; Check Google's BOM at https://storage.googleapis.com/cloud-opensource-java-dashboard/com.google.cloud/libraries-bom/index.html
                 ;; to find out which versions work together.
                 [com.google.cloud/google-cloud-storage "2.22.3" :exclusions [[com.google.guava/guava]]]

                 ;; PDF / XMP tools
                 [org.apache.pdfbox/pdfbox "2.0.28"]
                 [org.apache.pdfbox/xmpbox "2.0.28"]
                 [com.github.librepdf/openpdf "1.3.30"]
                 ;; Used for processing TIFF metadata
                 [org.apache.commons/commons-imaging "1.0.0-alpha5"]
                 [commons-io "2.16.1"]

                 ;; Lupapiste deps
                 [lupapiste/commons "5.3.12" :exclusions [prismatic/schema]]
                 [lupapiste/document-search-commons "2.0.12" :exclusions [lupapiste/commons
                                                                          org.clojure/clojurescript]]
                 [lupapiste/jms-client "0.4.1"]
                 [fi.lupapiste/pubsub-client "2.5.2"]

                 ;; Clojurescript
                 [org.clojure/clojurescript "1.11.60"]

                 ;; Geo Tools
                 [cljts "0.3.0-20150228.035522-2" :exclusions [xerces/xercesImpl]]]
  :main ^:skip-aot onkalo.main
  :target-path "target/%s/"
  :uberjar-name "onkalo.jar"
  :source-paths ["src/clj" "src/cljc" "src/cljs"]
  :test-paths ["test" "itest"]
  :clean-targets ^{:protect false} ["resources/public/js/"
                                    :target-path]
  :manifest {:build-info {"git-commit" ~(fn [_] (.trim (:out (clojure.java.shell/sh "git" "rev-parse" "--verify" "HEAD"))))
                          "build"      ~(fn [_] (or (System/getenv "BUILD_TAG") "unknown"))}}
  :plugins [[jonase/eastwood "1.2.4"]]
  :eastwood {:exclude-linters [:implicit-dependencies :reflection :deprecations]}
  :profiles {:dev     {:source-paths ["dev"]
                       :repl-options {:init-ns user}
                       :dependencies [[reloaded.repl "0.2.4"]
                                      [ring/ring-mock "0.4.0" :exclusions [ring/ring-codec]]
                                      [org.clojure/tools.namespace "1.4.4"]
                                      [org.clojure/test.check "1.1.1"]
                                      [prismatic/schema-generators "0.1.5" :exclusions [org.clojure/test.check
                                                                                        prismatic/schema]]
                                      ;; For storage testing
                                      [com.google.cloud/google-cloud-nio "0.126.16"]]
                       :plugins      [[lein-shell "0.5.0"]
                                      [test2junit "1.3.3"]]}
             :cljs    {:source-paths ["src/cljs"
                                      "src/cljc"
                                      "checkouts/document-search-commons/src/cljs"
                                      "checkouts/document-search-commons/src/cljc"]
                       :dependencies [[com.google.javascript/closure-compiler-unshaded "v20230411"]
                                      [org.clojure/google-closure-library "0.0-20230227-c7c0a541"]
                                      [org.clojure/google-closure-library-third-party "0.0-20230227-c7c0a541"]
                                      [thheller/shadow-cljs "2.25.4"]]}
             :uberjar {:aot        :all
                       :prep-tasks ^:replace ["clean"
                                              ["shell" "npm" "ci"]
                                              ["with-profile" "cljs" "run" "-m" "shadow.cljs.devtools.cli" "release" "prod"]
                                              "javac"
                                              "compile"]}}
  :aliases {"extract-strings" ["run" "-m" "lupapiste-commons.i18n.extract/extract-strings" "t"]
            "front"           ["do"
                               ["shell" "npm" "install"]
                               ["with-profile" "cljs" "run" "-m" "shadow.cljs.devtools.cli" "--npm" "watch" "dev"]]})
