(ns onkalo.ui.components.result-view
  (:require-macros [reagent.ratom :refer [reaction]]
                   [search-commons.utils.macros :refer [handler-fn]])
  (:require [clojure.string :as s]
            [reagent.core :as reagent]
            [search-commons.shared-utils :as utils]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.state :as state]
            [search-commons.utils.time :as tu]
            [onkalo.ui.components.result-view-preview :as rp]
            [onkalo.ui.components.result-view-header :as rvh]
            [onkalo.ui.components.result-view-tos :as tv]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [ajax.core :refer [POST]]
            [search-commons.routing :as routing]
            [onkalo.ui.components.mass-operations :as mo]
            [onkalo.ui.components.common :as co]
            [search-commons.components.search-results :as sr]
            [search-commons.components.dialog :as dialog]
            [search-commons.components.time :as time]))

(defn validate-metadata [{:keys [type]} editable-metadata]
  (and
    (or (and (not (sequential? (:type editable-metadata)))
             (= type (:type editable-metadata)))
        (@state/available-attachment-types (:type editable-metadata)))
    (try (tms/sanitize-metadata (:metadata editable-metadata))
         true
         (catch js/Error _
           false))))

(defn store-data [editable-metadata organization id cb error-cb]
  (let [flat-metadata (merge (dissoc editable-metadata :metadata) (:metadata editable-metadata))]
    (POST (routing/path (str "/update-metadata/" organization "/" id))
          {:params        (if (sequential? (:type flat-metadata))
                            (assoc flat-metadata
                              :type (keyword (s/join "." (map name (:type editable-metadata)))))
                            (dissoc flat-metadata :type))
           :handler       cb
           :error-handler error-cb
           :response-format :transit})))

(defn save-button [loading? editing? editable-metadata edit-valid? {:keys [id organization deleted]}]
  [:div.save-button
   [:button.save.btn.btn-primary
    {:type     "submit"
     :class    (when @loading? "waiting")
     :disabled (or (not edit-valid?) @loading? deleted)
     :on-click (fn [_]
                 (reset! loading? true)
                 (store-data @editable-metadata
                             organization
                             id
                             (fn [updated-metadata]
                               (state/update-onkalo-result-data id updated-metadata)
                               (reset! editing? false)
                               (reset! loading? false))
                             #(reset! loading? false)))}
    [:i.spin.lupicon-refresh.wait]
    [:span (t (if @loading? "Tallennetaan" "Tallenna"))]]])

(defn undo-archiving-button [editing?]
  (let [header (t "Vahvista poistaminen")
        message (t "Olet poistamassa tiedostoa arkistosta")
        confirm-text (t "Poista")
        confirm-button "negative"
        confirm-fn (fn [expl]
                     (reset! editing? false)
                     (mo/change-archiving-status :undo-archiving expl))]
    [:button.negative {:on-click #(dialog/yes-no-dialog-with-explanation header message confirm-fn confirm-button confirm-text)}
     [:span (t "Poista")]]))

(defn redo-archiving-button [editing?]
  (let [header (t "Vahvista palauttaminen")
        message (t "Olet palauttamassa tiedostoa arkistoon")
        confirm-text (t "Palauta")
        confirm-button "positive"
        confirm-fn (fn [expl]
                     (reset! editing? false)
                     (mo/change-archiving-status :redo-archiving expl))]
    [:button.positive {:on-click #(dialog/yes-no-dialog-with-explanation header message confirm-fn confirm-button confirm-text)}
     [:span (t "Palauta dokumentti")]]))

(defn- metadata-list [list header & {:keys [edit key data item->error]}]
  (when (seq list)
    [:div.metadata-item
     [:div.metadata-header (t header)]
     (if edit
       (let [data-keys (if (keyword? key) [key] key)
             values-cur (reagent/cursor data data-keys)]
         [co/text-input-list {:id "update-national-building-id"
                              :values-atom values-cur
                              :item->error item->error
                              :error-moves-content? true}])
       (doall (for [item list]
                ^{:key item}
                [:div.metadata-value (t item)])))]))

(defn- metadata-string [render? string header & {:keys [edit key data]}]
  (when render?
    [:div.metadata-item
     [:div.metadata-header (t header)]
     (if edit
       [:input {:type        "text"
                :value       (key @data)
                :on-change   #(swap! data assoc key (.. % -target -value))}]
       [:div.metadata-value string])]))

(defn- metadata-boolean [checked? header & {:keys [edit key data]}]
  [:div.metadata-item
   [:div.metadata-header (t header)]
   (if edit
     [:div.checkbox-container
      [:input {:type      "checkbox"
               :checked   (boolean (key @data))
               :on-change #(swap! data update key not)}]
      [:label (t header)]]
     [:div.metadata-value (t (if checked? "yes" "no"))])])

(defn- metadata-date [iso-date-string header & {:keys [edit key data]}]
  [:div.metadata-item
   [:div.metadata-header (t header)]
   (if edit
     [time/date-field {:id "doc-edit-paatospvm"
                       :value-atom (reagent/cursor data [key])}]
     [:div.metadata-value (tu/format-date iso-date-string)])])

(defn- metadata-boolean-and-date
  "The date field is only visible if the boolean is true"
  [checked iso-date-string bool-header date-header & {:keys [edit bool-key date-key data]}]
  [:<>
   [metadata-boolean checked bool-header :edit edit :key bool-key :data data]
   (when (if edit (bool-key @data) checked)
     [metadata-date iso-date-string date-header :edit edit :key date-key :data data])])

(defn- metadata-bid [id bids header & {:keys [edit data key]}]
  (when (seq bids)
    [:div.metadata-item
     [:div.metadata-header (t header)]
     (if edit
       (let [data-keys (if (keyword? key) [key] key)
             values-cur (reagent/cursor data data-keys)]
         [co/text-input-list {:id "update-municipal-building-id"
                              :values-atom values-cur
                              :error-moves-content? true}])
       (doall (for [bid bids]
                ^{:key (str id "-" bid)}
                [:div.metadata-value bid])))]))

(defn- metadata-deleted [deleted deletion-explanation]
  [:div.metadata-item.deleted
   [:div.metadata-header (str (t "Poistettu ") (tu/format-date deleted))]
   [:div.metadata-value
    [:p (t "Perustelu: ")]
    [:p deletion-explanation]]])

(defn- metadata-name-string [kasittelija editing? editable-metadata]
  (when (not (s/blank? (:lastName kasittelija)))
    [:div.metadata-item
     [:div.metadata-header (t "handler")]
     (if editing?
       [:div
        [:input {:type      "text"
                 :value     (get-in @editable-metadata [:kasittelija :firstName])
                 :on-change #(swap! editable-metadata assoc-in [:kasittelija :firstName] (.. % -target -value))}]
        [:input {:type      "text"
                 :value     (get-in @editable-metadata [:kasittelija :lastName])
                 :on-change #(swap! editable-metadata assoc-in [:kasittelija :lastName] (.. % -target -value))}]]
       [:div.metadata-value (str (:firstName kasittelija) " " (:lastName kasittelija))])]))

(defn- format-wgs84 [location-wgs84]
  (when-let [coords (-> (filter #(= "point" (:type %)) location-wgs84)
                        first
                        :coordinates)]
    (let [[x y] coords]
      (str (Math/abs y) "° " (if (pos? y) "N" "S") ", " (Math/abs x) "° " (if (pos? x) "E" "W")))))

(defn- format-etrs [location-etrs-tm35fin]
  (when (seq location-etrs-tm35fin)
    (let [[x y] location-etrs-tm35fin]
      (str "y: " y ", x: " x))))

(def location-type->formatter
  {"location.wgs84" format-wgs84
   "location.etrs-tm35fin" format-etrs})

(defn location-type->path [location-type index]
   (cond-> ({"location.wgs84" [:location-wgs84]
             "location.etrs-tm35fin" [:location]}
            location-type)
     index (conj index)))

(defn- metadata-location [location location-type editing? editable-metadata]
  (fn [location location-type editing? editable-metadata]
    [:div.metadata-item
     [:div.metadata-header (t location-type)]
     (if editing?
       [:div
        [:input {:type      "text"
                 :value     (get-in @editable-metadata (location-type->path location-type 1))
                 :on-change #(swap! editable-metadata assoc-in (location-type->path location-type 1) (.. % -target -value))}]
        [:input {:type      "text"
                 :value     (get-in @editable-metadata (location-type->path location-type 0))
                 :on-change #(swap! editable-metadata assoc-in (location-type->path location-type 0) (.. % -target -value))}]]
       [:div.metadata-value ((get location-type->formatter location-type #(constantly "")) location)])]))

(defn national-building-id->error [national-building-id]
  (when-not (utils/rakennustunnus? national-building-id)
    (t "Korjaa rakennustunnus")))

(defn kuntalupatunnus->error [kuntalupatunnus]
  (when (s/blank? kuntalupatunnus)
    (t "Syötä kuntalupatunnus")))

(defn result-view-component [document-in-view result]
  (let [editable-metadata (reagent/atom {})
        editing? (reagent/atom false)
        loading? (reagent/atom false)]
    (fn [document-in-view result]
      (let [{:keys [operations kayttotarkoitukset kuntalupatunnukset kuntalupatunnukset-muutossyy
                    projectDescription kasittelija suunnittelijat nationalBuildingIds buildingIds
                    metadata id deleted deletion-explanation location-wgs84 location paatospvm
                    permit-expired permit-expired-date demolished demolished-date] :as all-data} result
            edit-valid? (validate-metadata result @editable-metadata)]
        [:div.result-view
         [rvh/result-view-header editing? editable-metadata result]
         [:div.result-view-content
          [rp/preview document-in-view result]
          (when @editing?
            [:div.editing-buttons
             [save-button loading? editing? editable-metadata edit-valid? result]
             (if deleted
               [redo-archiving-button editing?]
               [undo-archiving-button editing?])])
          [:div.metadata
           (when deleted (metadata-deleted deleted deletion-explanation))
           [metadata-list operations "Toimenpide"]
           [metadata-list kayttotarkoitukset "Käyttötarkoitus"]
           [metadata-list
            kuntalupatunnukset
            "Kuntalupatunnus"
            :edit @editing?
            :key  :kuntalupatunnukset
            :data editable-metadata
            :item->error kuntalupatunnus->error]
           [metadata-string
            (or @editing? (not (s/blank? kuntalupatunnukset-muutossyy)))
            kuntalupatunnukset-muutossyy
            "Kuntalupatunnuksen muutossyy"
            :edit @editing?
            :key :kuntalupatunnukset-muutossyy
            :data editable-metadata]
           [metadata-string
            (not (s/blank? projectDescription))
            projectDescription
            "projectDescription"
            :edit @editing?
            :key :projectDescription
            :data editable-metadata]
           [metadata-name-string kasittelija @editing? editable-metadata]
           [metadata-string
            (seq suunnittelijat)
            (s/join ", " suunnittelijat)
            "Hankkeen suunnittelijat"]
           [metadata-list
            nationalBuildingIds
            "Valtakunnalliset rakennustunnukset"
            :edit @editing?
            :key  :nationalBuildingIds
            :data editable-metadata
            :item->error national-building-id->error]
           [metadata-bid
            id
            buildingIds
            "Kunnan rakennustunnukset"
            :edit @editing?
            :key  :buildingIds
            :data editable-metadata]
           [metadata-location location-wgs84 "location.wgs84" false editable-metadata]
           [metadata-location location "location.etrs-tm35fin" @editing? editable-metadata]
           [metadata-date paatospvm "Päätöspäivämäärä"
            :edit @editing?
            :key :paatospvm
            :data editable-metadata]
           [metadata-boolean-and-date
            permit-expired
            permit-expired-date
            "Lupa on rauennut"
            "Raukeamispäivämäärä"
            :edit @editing?
            :bool-key :permit-expired
            :date-key :permit-expired-date
            :data editable-metadata]
           [metadata-boolean-and-date
            demolished
            demolished-date
            "Rakennus on purettu"
            "Purkamispäivämäärä"
            :edit @editing?
            :bool-key :demolished
            :date-key :demolished-date
            :data editable-metadata]
           [metadata-string
            true
            id
            "Arkistointitunnus"]
           [tv/tos-metadata (and @editing? (not deleted)) editable-metadata metadata id]]]]))))
