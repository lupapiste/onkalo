(ns onkalo.metadata.elastic-migrations
  (:require [search-commons.geo-conversion :as gc]
            [clojure.string :as str]))

(defn- add-geojson-coords [document]
  (let [coords (get-in document [:metadata :location-wgs84])
        geo-json (if (and (sequential? coords) (= 2 (count coords)))
                   {:type "point"
                    :coordinates coords}
                   coords)]
    (if geo-json
      (assoc-in document [:metadata :location-wgs84] geo-json)
      document)))

(defn- change-applicant-to-list [{:keys [metadata] :as document}]
  (if (string? (:applicant metadata))
    (-> (assoc metadata :applicants [(:applicant metadata)])
        (dissoc :applicant)
        (->> (assoc document :metadata)))
    document))

(defn- recalculate-wgs84-coordinates [{:keys [metadata] :as document}]
  (if-let [etrs-location (:location-etrs-tm35fin metadata)]
    (assoc-in document
              [:metadata :location-wgs84]
              {:type "point" :coordinates (gc/epsg3067->wgs84 etrs-location)})
    document))

(defn- combine-location-and-drawing [{:keys [metadata] :as document}]
  (let [location (:location-wgs84 metadata)
        drawing (:drawing-wgs84 metadata)
        updated-metadata (cond-> (dissoc metadata :drawing-wgs84)
                                 location (assoc :location-wgs84 [location])
                                 (seq drawing) (update :location-wgs84 concat drawing))]
    (assoc document :metadata updated-metadata)))

(defn- remove-extra-text [original-text]
  (let [variations ["Dokumentin tyypit:-"
                    "Dokumentin tyypit: -"
                    "Dokumentin tyypit:"]]
    (->> (reduce #(str/replace %1 %2 "")
                 original-text
                 variations)
         str/trim)))

(defn- remove-extra-text-for-186 [{:keys [metadata organization] :as document}]
  (let [string-to-remove "Dokumentin tyypit"]
    (if (and (= organization "186-R")
             (:contents metadata)
             (str/includes? (:contents metadata) string-to-remove))
      (update-in document [:metadata :contents] remove-extra-text)
      document)))

(defn- coordinates-mixed?
  "Checks if x and y coordinate (ETRS-TM35FIN) have switched places"
  [[x y]]
  (and x y
       (< 6000000 x 9000000)
       (< 200000 y 900000)))

(defn- fix-mixed-up-coordinates [{:keys [metadata] :as document}]
  (if (coordinates-mixed? (:location-etrs-tm35fin metadata))
    (let [[y x] (:location-etrs-tm35fin metadata)
          new-etrs-location [x y]
          new-wgs-location (->> (:location-wgs84 metadata)
                                (map (fn [wgs84-loc]
                                  (if (.equalsIgnoreCase "point" (:type wgs84-loc))
                                    {:type "point" :coordinates (gc/epsg3067->wgs84 new-etrs-location)}
                                    wgs84-loc)))
                                doall)]
      (->> (merge metadata {:location-etrs-tm35fin new-etrs-location
                            :location-wgs84 new-wgs-location})
           (assoc document :metadata)))
    document))

(defn- add-missing-paatospvm [{:keys [metadata] :as document}]
  (if (and (nil? (:paatospvm metadata))
           (some? (:lupapvm metadata)))
    (assoc-in document [:metadata :paatospvm] (:lupapvm metadata))
    document))

(defn- remove-nil-coordinates [{:keys [metadata] :as document}]
  (if (some nil? (:location-wgs84 metadata))
    (update-in document [:metadata :location-wgs84] #(filter map? %))
    document))

(defn- add-location-docstore-field
  "LPK-5015 Fixes two problems with the field previously used for document search:
   1) `:location-wgs84` includes drawings (which sometimes overlap neighbouring lots)
   2) `:location-wgs84` is not always in the exact same place as `:location-etrs-tm35fin` (the one user sees/edits)
   Note that all documents that have `:location-etrs-tm35fin` also have `:location-wgs84`."
  [{{loc-wgs :location-wgs84 loc-etrs :location-etrs-tm35fin} :metadata :as document}]
  (let [is-point? #(.equalsIgnoreCase "point" (:type %))
        geojson   (if (some? loc-etrs)
                    {:type "point" :coordinates (vec (gc/epsg3067->wgs84 loc-etrs))}
                    (->> loc-wgs
                         (filter is-point?)
                         first))]
    (if (some? geojson)
      (-> document
          (assoc-in [:metadata :location-docstore] geojson)
          (assoc-in [:metadata :location-wgs84 0] geojson)) ; First element is the location, the rest are drawings
      document)))

(def migrations
  "Migrations are defined as a map keyed by Elasticsearch index version. The values are lists of migrations that will
   be run on the corresponding index update, e.g. {9 [foo]} means that foo will be run when updating the index from 8 to 9.
   Migrations are functions that take a document as the single argument.

   NOTE: Migration functions should update the :modified key for actually modified documents."
  {8 [add-geojson-coords]
   9 [change-applicant-to-list]
   13 [recalculate-wgs84-coordinates]
   16 [combine-location-and-drawing]
   17 [remove-extra-text-for-186 fix-mixed-up-coordinates]
   19 [add-missing-paatospvm]
   21 [remove-nil-coordinates]
   26 [add-location-docstore-field]})

(defn migrate-document [new-index-version document]
  (let [migrations (get migrations new-index-version)]
    ((apply comp migrations) document)))
