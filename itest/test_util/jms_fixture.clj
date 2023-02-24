(ns test-util.jms-fixture
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [com.stuartsierra.component :refer [start stop]]
            [onkalo.component.jms :as jms]))

(defonce jms-component (atom nil))

(defn start-jms-component! []
  (reset! jms-component (start (jms/jms-component {:embedded? true :broker-url "vm://0"}))))

(defn stop-jms-component! []
  (when @jms-component (stop @jms-component))
  (reset! jms-component nil))

(defn jms-fixture [f]
  (start-jms-component!)
  (f)
  (stop-jms-component!))
