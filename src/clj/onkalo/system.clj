(ns onkalo.system
  (:require [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [lupapiste-commons.ring.utils :as lcru]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.component.gcs :as gcs]
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
            [onkalo.server :refer [jetty-server]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [search-commons.translations :as translations]
            [lupapiste-commons.ring.session :as rs]
            [muuntaja.middleware :as mm]
            [taoensso.timbre :as timbre]))

(def base-config
  {:app {:middleware [mm/wrap-format
                      [wrap-not-found :not-found]
                      [wrap-defaults :defaults]
                      lcru/wrap-exception-logging
                      [lcru/wrap-csp-header :csp]]
         :not-found  "Resource Not Found"
         :defaults   (merge api-defaults
                            {:security {:content-type-options :nosniff}})}})

(defn new-system [config]
  (let [config           (meta-merge base-config config)
        pubsub-component (pubsub/pubsub-component (merge (:gcs config)
                                                         (:pubsub config)))]
    (timbre/info "Onkalo using MQ component" pubsub-component)
    (-> (component/system-map
          ;; services
          :app (handler-component (:app config))
          :http (jetty-server (:http config))
          :mq pubsub-component
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
          :laundry (:laundry config)
          :frontend (:frontend config)
          :storage (:storage config)
          :backup-gcs (:backup-gcs config)
          :http-config (:http config)
          :topics (-> config :mq :topics))
        (component/system-using
          {:http                      [:app]
           :documents                 [:gcs :elastic :api-keys :toj :laundry :lupapiste-api :mq :storage :pubsub :topics]
           :document-store-api        [:gcs :elastic :api-keys :lupapiste-api :mq :storage :pubsub :topics]
           :document-terminal-api     [:gcs :elastic :api-keys :lupapiste-api :storage :pubsub]
           :document-departmental-api [:gcs :elastic :api-keys :lupapiste-api :storage :pubsub]
           :buildings-api             [:gcs :elastic :api-keys :lupapiste-api :storage]
           :maintenance-api           [:gcs :elastic :storage :backup-gcs :pubsub]
           :reporting-api             [:elastic :api-keys :translations :session-store :http-config]
           :ui-routes                 [:translations
                                       :elastic
                                       :session-store
                                       :gcs
                                       :frontend
                                       :lupapiste-api
                                       :storage
                                       :pubsub
                                       :http-config]
           :status                    [:elastic]
           :app                       [:ui-routes :status :documents :document-store-api :document-terminal-api
                                       :document-departmental-api :maintenance-api :reporting-api
                                       :buildings-api]}))))
