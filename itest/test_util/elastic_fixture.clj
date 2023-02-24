(ns test-util.elastic-fixture
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [onkalo.component.elastic :as elastic]
            [com.stuartsierra.component :refer [start stop]])
  (:import [java.io IOException]))

(defonce es-component (atom nil))
(defonce index-name (atom nil))

(defn get-index-name []
  (if @index-name
    @index-name
    (reset! index-name (str "onkalo-test-" (System/currentTimeMillis)))))

(defn start-es-component! []
  (let [{{:keys [url http cluster-name]} :elastic} (edn/read-string (slurp "test-config.edn"))
        esc-config {:hosts [(str url http)]
                    :cluster-name cluster-name
                    :index-name (get-index-name)
                    :shards 1
                    :replicas 0}]
    (reset! es-component (start (elastic/elasticsearch-component esc-config)))))

(defn stop-es-component! []
  (when @es-component (do (elastic/destroy-index! @es-component)
                          (stop @es-component)))
  (reset! es-component nil)
  (reset! index-name nil))

(defn elastic-fixture [f]
  (start-es-component!)
  (f)
  (stop-es-component!))

(defn get-client []
  @es-component)

; You can use these functions to start up Elasticsearch in Docker if you don't have an instance running otherwise

(defn stop-and-remove-elastic! []
  (let [{{:keys [docker-image-name]} :elastic} (edn/read-string (slurp "test-config.edn"))]
    (shell/sh "docker" "kill" docker-image-name)
    (shell/sh "docker" "rm" docker-image-name)))

(defn start-elastic! []
  (let [{{:keys [http transport version docker-image-name]} :elastic} (edn/read-string (slurp "test-config.edn"))
        docker-cmd (format "docker run -d -p %d:9200 -p %d:9300 --name %s elasticsearch:%s" http transport docker-image-name version)]
    (try
      (let [{:keys [exit err out]} (apply shell/sh (str/split docker-cmd #" "))]
        (if (= exit 0)
          (do (println "Started container " out)
              (Thread/sleep 3000)
              (start-es-component!))
          (throw (Exception. (str "Could not start docker: " err)))))
      (catch IOException e
        (println "docker is not in path or starting it failed")
        (throw e)))))
