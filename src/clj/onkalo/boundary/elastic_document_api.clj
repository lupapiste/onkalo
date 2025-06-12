(ns onkalo.boundary.elastic-document-api
  (:require [clojure.string :as str]
            [onkalo.component.elastic]
            [qbits.spandex :as s])
  (:import [onkalo.component.elastic Elasticsearch]))

(defprotocol DocumentApi
  (put [elastic id document])
  (search [elastic search-opts])
  (get-by-id [elastic id source-fields])
  (delete [elastic id])
  (refresh [elastic]))

(extend-protocol DocumentApi
  Elasticsearch
  (put [{:keys [es-client write-alias]} id document]
    (s/request es-client {:url    [write-alias :_doc id]
                          :method :put
                          :body   document}))
  (search [{:keys [es-client read-alias]} search-opts]
    (s/request es-client {:url    [read-alias :_search]
                          :method :get
                          :body   search-opts}))
  (get-by-id [{:keys [es-client read-alias]} id source-fields]
    (try (s/request es-client (merge {:url    [read-alias :_doc id]
                                      :method :get}
                                     (when source-fields
                                       {:query-string {:_source (str/join "," source-fields)}})))
         (catch Throwable t
           (when-not (= 404 (:status (ex-data t)))
             (throw t)))))
  (delete [{:keys [es-client write-alias]} id]
    (s/request es-client {:url    [write-alias :_doc id]
                          :method :delete}))
  (refresh [{:keys [es-client write-alias]}]
    (s/request es-client {:url    [write-alias :_refresh]
                          :method :post})))
