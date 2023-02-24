(ns onkalo.util.lupapiste-api
  (:require [clj-http.client :as http]
            [clojure.core.memoize :as memo]
            [taoensso.timbre :as timbre]))

(defn- get-docstore-enabled-orgs [{:keys [host user password]}]
  (try
    (->> (http/get (str host "/rest/docstore/organizations?status=active")
                   {:basic-auth [user password]
                    :as :json})
         :body
         :data
         (map :id)
         (concat ["091-R"]))
    (catch Throwable t
      (timbre/error t "Error requesting available organizations from Lupapiste DocStore API")
      (throw t))))


(def ^{:arglists '([config])} docstore-enabled-orgs
  (memo/ttl get-docstore-enabled-orgs :ttl/threshold 300000)) ; 5 minutes


(defn- get-docterminal-enabled-orgs-with-allowed-attachment-types [{:keys [host user password]}]
  (try
    (->> (http/get (str host "/rest/docstore/allowed-attachment-types")
                   {:basic-auth [user password]
                    :as :json})
         :body
         :data)
    (catch Throwable t
      (timbre/error t "Error requesting available organizations from Lupapiste DocStore API"))))

(defn- municipality-and-permit-type->organization-info [allowed-types-resp]
  (reduce (fn [acc org]
            (apply assoc acc
                   (mapcat (juxt (juxt :id :permitType)
                                 (constantly org))
                           (:municipalities org))))
          {}
          allowed-types-resp))

(defn- organization-id->organization-info [allowed-types-resp]
  (reduce (fn [acc {:keys [id] :as org}]
            (assoc acc id org))
          {}
          allowed-types-resp))

(def ^{:arglists '([config])} docterminal-enabled-orgs-data
  (memo/ttl #(->> (get-docterminal-enabled-orgs-with-allowed-attachment-types %)
                  ((juxt municipality-and-permit-type->organization-info
                         organization-id->organization-info))
                  (zipmap [:by-municipalities :by-org-ids]))
            :ttl/threshold 60000)) ; 1 minute

(defn municipalities->organizations
  "Given a sequence of municipality codes, returns a sequence of all
  organizations that have some of the municipalities in their scope. For now,
  only permit type R is considered."
  [lupapiste-api municipality-codes]
  (map (:by-municipalities (docterminal-enabled-orgs-data lupapiste-api))
       (map #(vector % "R") municipality-codes)))

(defn allowed-document-types-for-organization [lupapiste-api org-id]
  (some-> (docterminal-enabled-orgs-data lupapiste-api)
          :by-org-ids
          (get org-id)))

(defn allowed-terminal-document-types-for-organization [lupapiste-api org-id]
  (some-> (allowed-document-types-for-organization lupapiste-api org-id)
          :allowedTerminalAttachmentTypes))

(defn allowed-departmental-document-types-for-organization [lupapiste-api org-id]
  (some-> (allowed-document-types-for-organization lupapiste-api org-id)
          :allowedDepartmentalAttachmentTypes))

(defn update-attachment-status-change-to-lupapiste [{:keys [host user password]} {:keys [deleted deletion-explanation]} doc]
  (let [{:keys [application-id doc-id]} doc
        lp-host                         (str host "/rest/onkalo/change-attachment-archival-status")
        options {:basic-auth       [user password]
                 :content-type     :json
                 :throw-exceptions false
                 :form-params      {:application-id       application-id
                                    :attachment-id        doc-id
                                    :target-state         (if (inst? deleted) "valmis" "arkistoitu")
                                    :deletion-explanation deletion-explanation}}]
    (http/post lp-host options)))
