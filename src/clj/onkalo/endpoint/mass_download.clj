(ns onkalo.endpoint.mass-download
  (:require [search-commons.zip-files :as zf]
            [onkalo.storage.document :as document]
            [taoensso.timbre :as timbre]
            [clj-time.core :as time]))

(defn- fetch-doc-stream [config {:keys [file-id filename org-id]}]
  (try
    (let [{:keys [content-fn]} (document/get-document config file-id org-id)]
      {:file-id file-id :filename filename :stream (content-fn)})
    (catch Exception _
      (timbre/error "Error occurred during mass download for" org-id "/" file-id))))

(defn get-files [config docs]
  (let [start    (System/currentTimeMillis)
        date     (time/now)
        now      (str (time/year date) "-" (time/month date) "-" (time/day date))
        response {:status  200
                  :headers {"Content-Type"        "application/octet-stream"
                            "Content-Disposition" (str "attachment; filename=\"LP-dokumentit-" now ".zip\"")}
                  :body    (->> docs
                                (pmap #(fetch-doc-stream config %))
                                (zf/zip-files)
                                (zf/temp-file-input-stream))}]
    (timbre/info "***** Mass download file zipping took " (- (System/currentTimeMillis) start) "ms")
    response))
