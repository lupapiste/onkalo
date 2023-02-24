(ns onkalo.component.pubsub
  (:require [com.stuartsierra.component :as component]
            [lupapiste-pubsub.bootstrap :as pubsub-bootstrap]
            [lupapiste-pubsub.core :as pubsub-client]
            [lupapiste-pubsub.protocol :as pubsub]
            [onkalo.boundary.object-storage :as os]
            [onkalo.boundary.preview :as preview-boundary]
            [onkalo.boundary.message-queue :as mq]))

(defrecord OnkaloPubSub [project-id endpoint credentials-provider]
  component/Lifecycle
  (start [this]
    (let [channel-provider (pubsub-bootstrap/transport-channel-provider endpoint)
          providers        {:channel-provider     channel-provider
                            :credentials-provider credentials-provider}
          config           (merge providers
                                  {:topic-admin        (pubsub-bootstrap/topic-admin-client providers)
                                   :subscription-admin (pubsub-bootstrap/subscription-admin-client providers)
                                   :project-id         project-id})]
      (merge this
             config
             {:client (pubsub-client/init config)})))
  (stop [{:keys [client topic-admin channel-provider subscription-admin] :as this}]
    (when client
      (pubsub/halt client))
    (when topic-admin
      (pubsub-bootstrap/shutdown-client topic-admin))
    (when subscription-admin
      (pubsub-bootstrap/shutdown-client subscription-admin))
    (when channel-provider
      (pubsub-bootstrap/terminate-transport! channel-provider))
    (dissoc this :client :topic-admin :subscription-admin :channel-provider))

  preview-boundary/PreviewGenerator
  (generate-preview [this gcs organization id]
    (let [bucket         (os/org-bucket-name gcs organization false)
          preview-bucket (os/org-bucket-name gcs organization true)]
      (pubsub/publish (:client this)
                      "to-conversion-service"
                      {:handler            :convert-to-jpeg
                       :message-id         (str bucket "/" id "-preview")
                       :response-expected? false
                       :data               {:bucket        bucket
                                            :object-key    id
                                            :target-bucket preview-bucket
                                            :suffix        ""
                                            :output-size   1000
                                            :quality       60
                                            :watermark     true}})))

  mq/MessageQueue
  (publish [this topic-name message]
    (pubsub/publish (:client this)
                    topic-name
                    message)))

(defn pubsub-component [{:keys [service-account-file project endpoint]}]
  (let [credentials-provider (cond
                               endpoint (pubsub-bootstrap/no-credentials-provider)
                               service-account-file (pubsub-bootstrap/fixed-credentials-provider service-account-file)
                               :else (pubsub-bootstrap/google-credentials-provider))]
    (->OnkaloPubSub project endpoint credentials-provider)))
