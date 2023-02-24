(ns onkalo.main
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [lupapiste-commons.ring.utils :refer [wrap-request-logging]]
            [lupapiste-commons.utils :refer [get-build-info]]
            [meta-merge.core :refer [meta-merge]]
            [onkalo.system :refer [new-system]]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [timbre-json-appender.core :as tas])
  (:import [javax.imageio ImageIO]
           [java.util TimeZone]))

(def prod-config
  {:app {:middleware [wrap-request-logging]}})

(def app-system (atom nil))

(defn configure-logging [config]
  (-> (if (= :json (:format config))
        ;; Log JSON to stdout if file not defined
        {:min-level (or (:level config) :info)
         :appenders {:json (tas/json-appender {:inline-args? true
                                               :level-key    :severity
                                               :msg-key      :message})}}
        {:level          (get config :level :info)
         :ns-whitelist   []
         :ns-blacklist   []
         :middleware     []
         :timestamp-opts {:pattern  "yyyy-MM-dd HH:mm:ss"
                          :locale   :jvm-default
                          :timezone (TimeZone/getTimeZone "Europe/Helsinki")}
         :output-fn      (partial timbre/default-output-fn {:stacktrace-fonts {}})
         :appenders      {:println (appenders/println-appender {:stream :auto})}})
      (timbre/set-config!)))

(defn -main [& args]
  (when-let [config (edn/read-string (slurp (or (first args) "config.edn")))]
    (configure-logging (:logging config))
    (timbre/info "Starting app")
    (try
      (let [build-info (get-build-info "onkalo.jar")]
        (doseq [[k v] build-info]
          (timbre/info (str k ": " v)))
        (ImageIO/scanForPlugins)
        (timbre/info "ImageIO: Registered image MIME types:" (str/join ", " (ImageIO/getReaderMIMETypes)))
        (let [system (component/start (new-system (meta-merge prod-config config {:build-info build-info})))]
          (reset! app-system system)
          (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(component/stop system)))))
      (catch Throwable t
        (let [message "Error while starting application"]
          (timbre/error t message)
          (println (str message ": " t)))
        (System/exit 1)))))
