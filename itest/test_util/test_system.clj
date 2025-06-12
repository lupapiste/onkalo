(ns test-util.test-system
  (:require [clojure.edn :as edn]
            [com.stuartsierra.component :as component]
            [onkalo.metadata.elastic-api :as ea]
            [taoensso.timbre :as log]
            [test-util.elastic-fixture :as test-elastic]
            [test-util.test-pubsub :as test-pubsub]
            [test-util.test-storage :as test-storage]))

(def ^:dynamic *test-system* nil)

(def default-config (edn/read-string (slurp "test-config.edn")))

(defn- make-system
  [config]
  (let [config (or config default-config)]
    (-> (component/system-map
          :gcs (test-storage/in-memory-storage (merge (:gcs config)
                                                      (:storage config)))
          :elastic (test-elastic/make-es-component (:elastic config))
          :elastic-cleaner (test-elastic/map->MockEsCleaner {})
          :pubsub (test-pubsub/make-component (merge (:gcs config)
                                                     (:pubsub config)))
          :storage (:storage config)
          :laundry (:laundry config))
        (component/system-using
          {:elastic-cleaner [:elastic]}))))

(defn system-fixture-fn
  ([]
   (system-fixture-fn nil))
  ([config]
   (fn [test-fn]
     (log/info "Starting test system")
     (binding [*test-system* (component/start (make-system config))]
       (try
         (test-fn)
         (finally
           (log/info "Stopping test system")
           (component/stop *test-system*)))))))

(defn system
  "Return the system during tests"
  []
  (or *test-system*
      (throw (ex-info "Test system not running, call `system-fixture-fn`" {}))))

(defn messages
  "Return any published pubsub messages as a map of topic name to message list.
  Optionally can also clear the messages."
  ([]
   (messages false))
  ([clear?]
   (let [[old _] (swap-vals! test-pubsub/*messages (if clear? (constantly {}) identity))]
     old)))

(defn refresh-es-index
  "Refresh the ES index, eg. after inserting a new document"
  []
  (ea/refresh-index (:elastic (system))))

(defn object-exists?
  [bucket id]
  (test-storage/object-exists? (-> (system) :gcs :storage) bucket id))
