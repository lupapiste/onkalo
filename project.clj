(defproject onkalo "2023.1"
  :description "Lupapiste electronic archival service"
  :url "https://www.lupapiste.fi"
  :license {:name         "European Union Public Licence v. 1.2"
            :url          "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"
            :distribution :manual}
  :repositories {"osgeo" {:url "https://repo.osgeo.org/repository/release/"}}
  :min-lein-version "2.5.1"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.memoize "1.0.257"]
                 [org.clojure/data.csv "1.0.1"]
                 [org.clojure/data.zip "1.0.0"]
                 [com.stuartsierra/component "1.1.0"]
                 [ring "1.9.6"]
                 [ring/ring-core "1.9.6"]
                 [ring-jetty-component "0.3.1"]
                 [compojure "1.7.0"]
                 [duct "0.7.0"]
                 [meta-merge "1.0.0"]
                 [ring "1.9.6"]
                 [ring/ring-defaults "0.3.4"]
                 [com.taoensso/timbre "6.0.4"]
                 [viesti/timbre-json-appender "0.2.8"]
                 [org.apache.httpcomponents/httpcore "4.4.16"]
                 [org.apache.httpcomponents/httpclient "4.5.14"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 ;; Use newer version of core.async
                 [org.clojure/core.async "1.6.673"]
                 [cc.qbits/spandex "0.7.10" :exclusions [cheshire org.clojure/core.async]]
                 [org.apache.commons/commons-imaging "1.0-alpha2"]
                 [clj-time "0.15.2"]
                 [prismatic/schema "1.4.1"]
                 [metosin/compojure-api "2.0.0-alpha31" :exclusions [prismatic/schema]]
                 [metosin/muuntaja "0.6.8"]
                 [org.apache.activemq/artemis-jms-client "2.27.1"]
                 [pandect "1.0.2"]
                 [metosin/potpuri "0.5.3"]

                 [com.fzakaria/slf4j-timbre "0.3.21"]
                 [org.slf4j/log4j-over-slf4j "1.7.36"]
                 [org.slf4j/jul-to-slf4j "1.7.36"]
                 [org.slf4j/jcl-over-slf4j "1.7.36"]

                 ; Explicit dep to this version to avoid conflicts from buddy and others:
                 [com.fasterxml.jackson.core/jackson-databind "2.14.1"]
                 [com.fasterxml.jackson.datatype/jackson-datatype-jsr310 "2.14.1"]

                 ; Define explicit version of guava for compatibility
                 [com.google.guava/guava "31.1-jre"]
                 ; GCS Library
                 [com.google.cloud/google-cloud-storage "2.16.0" :exclusions [[com.google.guava/guava]]]

                 ;; PDF / XMP tools
                 [org.apache.pdfbox/pdfbox "2.0.27"]
                 [org.apache.pdfbox/xmpbox "2.0.27"]
                 [com.github.librepdf/openpdf "1.3.30"]

                 ;; Lupapiste deps
                 [lupapiste/commons "4.0.1" :exclusions [prismatic/schema]]
                 [lupapiste/document-search-commons "1.0.5" :exclusions [lupapiste/commons]]
                 [lupapiste/jms-client "0.4.1"]
                 [fi.lupapiste/pubsub-client "2.5.1"]

                 ;; Clojurescript
                 [org.clojure/clojurescript "1.10.879"]

                 ;; Geo Tools
                 [cljts "0.3.0-20150228.035522-2" :exclusions [xerces/xercesImpl]]]
  :main ^:skip-aot onkalo.main
  :target-path "target/%s/"
  :uberjar-name "onkalo.jar"
  :source-paths ["src/clj" "src/cljc"]
  :test-paths ["test" "itest"]
  :clean-targets ^{:protect false} ["resources/public/js/main.js"
                                    "resources/public/js/out"
                                    :target-path]
  :manifest {:build-info {"git-commit" ~(fn [_] (.trim (:out (clojure.java.shell/sh "git" "rev-parse" "--verify" "HEAD"))))
                          "build" ~(fn [_] (or (System/getenv "BUILD_TAG") "unknown"))}}
  :plugins [[jonase/eastwood "1.2.4"]]
  :eastwood {:exclude-linters [:implicit-dependencies :reflection :deprecations]}
  :profiles {:dev {:source-paths ["dev"]
                   :repl-options {:init-ns user}
                   :dependencies [[reloaded.repl "0.2.4"]
                                  [ring/ring-mock "0.4.0" :exclusions [ring/ring-codec]]
                                  [org.clojure/tools.namespace "1.3.0"]
                                  [org.clojure/test.check "1.1.1"]
                                  [prismatic/schema-generators "0.1.5" :exclusions [org.clojure/test.check
                                                                                    prismatic/schema]]
                                  [org.apache.activemq/artemis-jms-server "2.27.1"]]
                   :sass {:source-paths ["checkouts/document-search-commons/scss/"]
                          :target-path  "checkouts/document-search-commons/resources/public/css"
                          :output-style :expanded
                          :source-map   true}
                   :plugins [[lein-figwheel "0.5.20"]
                             [lein-cljsbuild "1.1.8"]
                             [test2junit "1.3.3"]
                             [deraen/lein-sass4clj "0.3.1"]]}
             :uberjar {:aot :all
                       :prep-tasks ^:replace ["clean"
                                              ["cljsbuild" "once" "prod"]
                                              "javac"
                                              "compile"]}}
  :figwheel {:css-dirs ["checkouts/document-search-commons/resources/public/css"]
             :server-port 3950}
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/cljs" "src/cljc" "checkouts/document-search-commons/src/cljs" "checkouts/document-search-commons/src/cljc"]
                        :compiler {:main onkalo.ui.app
                                   :output-to "resources/public/js/main.js"
                                   :asset-path "/onkalo/ui/js/out"}
                        :figwheel {:on-jsload "onkalo.ui.app/start"}}
                       {:id "prod"
                        :source-paths ["src/cljs" "src/cljc"]
                        :compiler {:main onkalo.ui.app
                                   :output-to "resources/public/js/main.js"
                                   :output-dir "resources/public/js/out"
                                   :source-map "resources/public/js/main.js.map"
                                   :language-in  :ecmascript6
                                   :rewrite-polyfills true
                                   :language-out :ecmascript5
                                   :optimizations :advanced
                                   :closure-extra-annotations ["api" "observable"]}}]}
  :aliases {"extract-strings" ["run" "-m" "lupapiste-commons.i18n.extract/extract-strings" "t"]})
