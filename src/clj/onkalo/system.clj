(ns onkalo.system
  (:require [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [lupapiste-commons.ring.utils :refer [wrap-exception-logging]]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.component.gcs :as gcs]
            [onkalo.component.jms :refer [jms-component]]
            [onkalo.component.elastic :refer [elasticsearch-component]]
            [onkalo.component.pubsub :as pubsub]
            [onkalo.endpoint.user-interface :refer [ui-routes]]
            [onkalo.endpoint.status :refer [status]]
            [onkalo.endpoint.documents :refer [documents]]
            [onkalo.endpoint.document-store-api :refer [document-store-api]]
            [onkalo.endpoint.document-terminal-api :refer [document-terminal-api]]
            [onkalo.endpoint.document-departmental-api :refer [document-departmental-api]]
            [onkalo.endpoint.buildings-api :refer [buildings-api]]
            [onkalo.endpoint.maintenance :refer [maintenance-api]]
            [onkalo.endpoint.reporting :refer [reporting-api]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [search-commons.translations :as translations]
            [lupapiste-commons.ring.session :as rs]
            [muuntaja.middleware :as mm]
            [taoensso.timbre :as timbre]))

(def base-config
  {:app {:middleware [mm/wrap-format
                      [wrap-not-found :not-found]
                      [wrap-defaults :defaults]
                      wrap-exception-logging]
         :not-found  "Resource Not Found"
         :defaults   api-defaults}})

(defn new-system [config]
  (let [config           (meta-merge base-config config)
        pubsub-component (pubsub/pubsub-component (merge (:gcs config)
                                                         (:pubsub config)))
        mq-component     (if (-> config :mq :implementation (= :jms))
                           (jms-component (:jms config))
                           pubsub-component)]
    (timbre/info "Onkalo using MQ component" mq-component)
    (-> (component/system-map
          ;; services
          :app (handler-component (:app config))
          :http (jetty-server (:http config))
          :mq mq-component
          :gcs (gcs/gcs-component (merge (:gcs config)
                                         (:storage config)))
          :elastic (elasticsearch-component (:elasticsearch config))
          :pubsub pubsub-component
          ;; endpoints
          :ui-routes (endpoint-component (partial ui-routes (-> config :build-info)))
          :status (endpoint-component (partial status (-> config :build-info)))
          :documents (endpoint-component documents)
          :document-store-api (endpoint-component document-store-api)
          :document-terminal-api (endpoint-component document-terminal-api)
          :document-departmental-api (endpoint-component document-departmental-api)
          :buildings-api (endpoint-component buildings-api)
          :maintenance-api (endpoint-component maintenance-api)
          :reporting-api (endpoint-component reporting-api)
          :api-keys (:api-keys config)
          :lupapiste-api (:lupapiste-api config)
          :translations (translations/->Translations)
          :session-store (rs/rekeyable (get-in config [:http :session-key-path]))
          :toj (:toj config)
          :frontend (:frontend config)
          :storage (:storage config)
          :backup-gcs (:backup-gcs config)
          :topics (-> config :mq :topics))
        (component/system-using
          {:http                      [:app]
           :documents                 [:gcs :elastic :api-keys :toj :lupapiste-api :mq :storage :pubsub :topics]
           :document-store-api        [:gcs :elastic :api-keys :lupapiste-api :mq :storage :pubsub :topics]
           :document-terminal-api     [:gcs :elastic :api-keys :lupapiste-api :storage :pubsub]
           :document-departmental-api [:gcs :elastic :api-keys :lupapiste-api :storage :pubsub]
           :buildings-api             [:gcs :elastic :api-keys :lupapiste-api :storage]
           :maintenance-api           [:gcs :elastic :storage :backup-gcs :pubsub]
           :reporting-api             [:elastic :api-keys :translations :session-store]
           :ui-routes                 [:translations :elastic :session-store :gcs :frontend :lupapiste-api :storage :pubsub]
           :status                    [:elastic]
           :app                       [:ui-routes :status :documents :document-store-api :document-terminal-api
                                       :document-departmental-api :maintenance-api :reporting-api
                                       :buildings-api]}))))
