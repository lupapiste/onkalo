(ns onkalo.util.log-api-usage
  (:require [clojure.string :as str]
            [onkalo.boundary.message-queue :as mq]))

(defn- file-information [document-elastic-data]
  (select-keys document-elastic-data [:fileId :organization]))

(defn- request-information [api-user external-id]
  {:apiUser    api-user
   :externalId external-id})

(defn- document-related-metadata [document-elastic-data]
  {:metadata (-> document-elastic-data
                 :metadata
                 (select-keys [:henkilotiedot :julkisuusluokka :myyntipalvelu :nakyvyys :security-period-end
                               :applicationId :address :type :propertyId]))})

(defn create-message
  "Encodes the message data into EDN."
  [api-user external-id timestamp document-elastic-data]
  (merge (file-information document-elastic-data)
         (request-information api-user external-id)
         (document-related-metadata document-elastic-data)
         {:timestamp  timestamp
          :message-id (str (if (str/blank? external-id)
                             (:fileId document-elastic-data)
                             external-id)
                           "-api-usage")}))

(defn log-api-usage
  "Logs information about the download transaction. The log message contains
  information about the API user, the downloaded file and metadata about the
  document. The message is sent to the message queue."
  [mq topic api-user external-id document-elastic-data]
  (mq/publish mq
              topic
              (create-message api-user
                              external-id
                              (System/currentTimeMillis)
                              document-elastic-data)))
