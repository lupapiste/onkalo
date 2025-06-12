(ns test-util.test-pubsub
  "Mock PubSub component for tests, which captures published messages in an atom."
  (:require [com.stuartsierra.component :as component]
            [lupapiste-pubsub.protocol :as pubsub]
            [onkalo.component.pubsub :as pubsub-component]
            [taoensso.timbre :as log]))

(defonce *messages (atom {}))

(defrecord MockPubSubClient []
  pubsub/MessageQueueClient
  (publish [_ topic-name message]
    (log/infof "Publishing to mock-pubsub, topic %s" topic-name)
    (swap! *messages update topic-name (fnil conj []) message))
  (subscribe [_ _topic-name _handler]
    (throw (UnsupportedOperationException.)))
  (subscribe [_ _topic-name _handler _additional-config]
    (throw (UnsupportedOperationException.)))
  (get-publisher [_ _topic-name]
    (throw (UnsupportedOperationException.)))
  (stop-subscriber [_ _topic-name]
    (throw (UnsupportedOperationException.)))
  (remove-subscription [_ _topic-name]
    (throw (UnsupportedOperationException.)))
  (halt [_]
    (log/info "Shutting down mock-pubsub")))

;; Wraps OnkaloPubSub so that we may supply our own client and
;; still use the protocol implementations it has
(defrecord MockPubSub [onkaloPubSub]
  component/Lifecycle
  (start [_]
    ;; Skip starting onkaloPubSub and just inject the mock client
    (merge onkaloPubSub
           {:client (->MockPubSubClient)}))
  (stop [_]
    (component/stop onkaloPubSub)))

(defn make-component
  [_config]
  (reset! *messages {})
  (->MockPubSub (pubsub-component/->OnkaloPubSub nil nil nil)))
