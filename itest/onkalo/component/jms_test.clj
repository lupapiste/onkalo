(ns onkalo.component.jms-test
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :refer [start stop]]
            [onkalo.boundary.message-queue :as mq]
            [onkalo.component.jms :as jms]
            [test-util.jms-fixture :as jmsf]))

(defonce messages (atom []))

(defn run-jms [f]
  (reset! messages [])
  (jmsf/jms-fixture f))

(deftest sending-message
  (run-jms
   #(do
      (jms/add-consumer @jmsf/jms-component "test-queue"
                        (fn [msg] (swap! messages conj msg)))
      (mq/publish @jmsf/jms-component "test-queue" "test message")
      (Thread/sleep 100)
      (is (= (count @messages) 1))
      (is (= (first @messages) "\"test message\"")))))

(deftest sending-multiple-messages
  (run-jms
   #(do (jms/add-consumer @jmsf/jms-component "test-queue"
                             (fn [msg] (swap! messages conj msg)))
        (mq/publish @jmsf/jms-component "test-queue" "message 1")
        (mq/publish @jmsf/jms-component "test-queue" "message 2")
        (mq/publish @jmsf/jms-component "test-queue" "message 3")
        (Thread/sleep 100)
        (is (= (count @messages) 3))
        (is (= @messages ["\"message 1\"" "\"message 2\"" "\"message 3\""])))))
