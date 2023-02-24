(ns onkalo.component.jms
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [lupapiste-jms.client :as jms]
            [onkalo.boundary.message-queue :as mq])
  (:import [javax.jms Queue Destination Message MessageListener JMSConsumer JMSContext JMSProducer]
           [org.apache.activemq.artemis.api.jms ActiveMQJMSClient]
           [org.apache.activemq.artemis.jms.client ActiveMQJMSConnectionFactory]))

(defn- on-exception [e]
  (timbre/error e "JMS exception" (.getMessage e)))

(defn create-connection-factory ^ActiveMQJMSConnectionFactory
  [^String url connection-options]
  (let [{:keys [retry-interval retry-multiplier max-retry-interval reconnect-attempts]
         :or   {retry-interval     (* 2 1000)
                retry-multiplier   2
                max-retry-interval (* 5 60 1000) ; 5 mins
                reconnect-attempts -1}} connection-options]
    (doto (ActiveMQJMSConnectionFactory. url)
      (.setRetryInterval (long retry-interval))
      (.setRetryIntervalMultiplier (double retry-multiplier))
      (.setMaxRetryInterval (long max-retry-interval))
      (.setReconnectAttempts (int reconnect-attempts)))))


(defn- start-embedded! []
  (require 'artemis-server)
  ((ns-resolve 'artemis-server 'start)))

(defn- stop-embedded! []
  (require 'artemis-server)
  (timbre/info "Stopping embedded Artemis broker")
  ((ns-resolve 'artemis-server 'stop)))

(defn create-connection [jms-config]
  (try
    (when (:embedded? jms-config)
      (start-embedded!))
    (create-connection-factory (:broker-url jms-config)
                               jms-config)
    (catch Exception e
      (timbre/error "Couldn't create JMS connection factory:" (.getMessage e)))))

(defn- test-connection [connection-factory jms-config]
  (try
    (doto (jms/create-context connection-factory jms-config)
      (.close))
    true
    (catch Exception e
      (timbre/error "Couldn't create JMS context:" (.getMessage e))
      false)))

(defn ensure-connection [jms jms-config]
  (if (:connection-factory jms)
    jms
    (loop [sleep-time 2000
           try-times  5]
      (let [connection-factory (create-connection jms-config)]
        (if (test-connection connection-factory jms-config)
          (do (timbre/info "Created JMS connection factory")
              (assoc jms
                     :connection-factory connection-factory
                     :queues (atom {})))
          (if (zero? try-times)
            (do
              (timbre/error "Can't connect to JMS broker")
              (assoc jms
                     :connection-factory connection-factory
                     :queues (atom {})))
            (let [sleep-time (min (* 2 sleep-time) 15000)]
              (timbre/warnf "Couldn't connect to broker %s, reconnecting in %s seconds"
                            (:broker-url jms-config)
                            (/ sleep-time 1000))
              (Thread/sleep sleep-time)
              (recur sleep-time (dec try-times)))))))))

(defn start! [this jms-config]
  (timbre/info "Starting JMS")
  (ensure-connection this jms-config))

(defn stop! [this jms-config]
  (timbre/info "Stopping JMS")
  (doseq [q (vals @(:queues this))]
    (doseq [{:keys [^JMSConsumer consumer ^JMSContext context]} (->> q :consumers)]
      (.close consumer)
      (.close context))
    (when-let [{:keys [^JMSProducer producer ^JMSContext context]} (:producer q)]
      (.close context)))
  (when (:embedded? jms-config)
    (stop-embedded!))
  (dissoc this :connection-factory))

(defn- ^Queue create-queue [queue-name]
  (ActiveMQJMSClient/createQueue queue-name))

(defn- create-listener [l]
  (condp instance? l
    MessageListener l
    clojure.lang.Fn (jms/message-listener l)))

(def exception-listener
  (jms/exception-listener (fn [error]
                            (timbre/errorf error
                                           "Caught JMS error: %s"
                                           (.getMessage error)))))

(defn- create-consumer
  [^ActiveMQJMSConnectionFactory cf ^Destination dest listener & [options-map]]
  (let [^JMSContext ctx       (jms/create-context cf (assoc options-map
                                                            :ex-listener exception-listener))
        ^JMSConsumer consumer (.createConsumer ctx dest)]
    {:consumer (doto consumer (.setMessageListener listener))
     :context  ctx}))

(defn- create-producer [^ActiveMQJMSConnectionFactory cf ^Destination dest & [options-map]]
  (let [^JMSContext ctx       (jms/create-context cf options-map)
        ^JMSProducer producer (jms/create-jms-producer ctx options-map)]
    {:producer producer
     :context  ctx}))

(defn add-consumer [jms queue-name listener]
  (timbre/info "JMS - Adding consumer for queue '" queue-name "'")
  (swap! (:queues jms)
         update queue-name
         (fn [q]
           (let [queue    (or (:queue q) (create-queue queue-name))
                 consumer (create-consumer (:connection-factory jms)
                                           queue
                                           (create-listener listener)
                                           (:jms-config jms))]
             (-> q
                 (assoc :queue queue)
                 (update :consumers conj consumer))))))

(defn add-producer [jms queue-name]
  (timbre/info "JMS - Adding producer for queue '" queue-name "'")
  (try
    (swap! (:queues jms)
           update queue-name
           (fn [q]
             (let [queue    (or (:queue q) (create-queue queue-name))
                   producer (or (:producer q)
                                (create-producer (:connection-factory jms)
                                                 queue
                                                 (:jms-config jms)))]
               (-> q
                   (assoc :queue queue)
                   (assoc :producer producer)))))
    (catch Exception e
      (timbre/errorf "Failed to add message producer to queue '%s': %s"
                     queue-name (.getMessage e)))))

(defn get-queue-data [jms queue-name]
  (-> @(:queues jms)
      (get queue-name)))

(def send-lock (Object.))

(defn send-message [{^Queue queue :queue {:keys [^JMSProducer producer ^JMSContext context]} :producer} data]
  (try
    (locking send-lock
      (let [^Message msg (jms/create-message data context)]
        (.send producer queue msg)
        nil))
    (catch Exception e
      (timbre/errorf "Failed to send message '%s' to queue '%s': %s"
                     data (.getQueueName ^Queue queue) (.getMessage e)))))

(defn- queue-initialized-for-sending-messages?
  [{queue :queue {:keys [producer context]} :producer}]
  (and queue producer context))

(defrecord Jms [jms-config]
  component/Lifecycle
  (start [this]
    (if (:connection-factory this)
      this
      (start! this jms-config)))
  (stop [this]
    (if (:connection-factory this)
      (stop! this jms-config)
      this))

  mq/MessageQueue
  (publish [jms queue-name message]
    (let [queue-data  (get-queue-data jms queue-name)
          encoded-msg (pr-str message)]
      (if (queue-initialized-for-sending-messages? queue-data)
        (send-message queue-data encoded-msg)
        (if (add-producer jms queue-name)
          (recur queue-name message)
          (timbre/errorf "Failed to send message '%s' to queue '%s'"
                         encoded-msg queue-name))))))

(defn jms-component [jms-config]
  (->Jms jms-config))
