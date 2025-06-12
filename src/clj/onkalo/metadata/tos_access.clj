(ns onkalo.metadata.tos-access
  (:require [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [taoensso.timbre :as timbre]))

(defn- get-metadata-from-toj [{:keys [host]} organization tos-function document-type]
  (try
    (let [url (str host "/tiedonohjaus/api/org/" organization "/asiat/" tos-function (when (not= "case-file" document-type) (str "/document/" document-type)))
          _ (timbre/info "Fetching TOS metadata from" url)
          {:keys [status body]} (http/get url {:as               :json
                                               :coerce           :always
                                               :throw-exceptions false})]
      (case status
        200 {:tos-metadata body}
        404 {:tos-errors (:error body)}
        {:tos-errors "Unknown error"}))
    (catch Exception e
      (timbre/error "Error accessing TOJ" e)
      {:tos-errors "Unknown error"})))

(def tos-metadata
  (memo/ttl get-metadata-from-toj
            :ttl/threshold 10000))
