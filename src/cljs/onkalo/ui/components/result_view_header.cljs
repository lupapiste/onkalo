(ns onkalo.ui.components.result-view-header
  (:require [clojure.string :as s]
            [search-commons.components.search-results :as sr]
            [reagent.core :as reagent]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.components.search-form :as sf]
            [search-commons.components.combobox :as cb]
            [search-commons.utils.state :as state])
  (:require-macros [search-commons.utils.macros :refer [handler-fn]]))

(def editable-metadata-fields
  [:type :contents :metadata :address :propertyId :projectDescription
   :kasittelija :location :paatospvm :nationalBuildingIds :kuntalupatunnukset :kuntalupatunnukset-muutossyy
   :buildingIds :permit-expired :permit-expired-date :demolished :demolished-date])

(defn as-date [metadata key]
  (let [current-val (get metadata key)]
    (cond-> metadata
      current-val (assoc key (js/Date. current-val)))))

(defn convert [m key f]
  (if (contains? m key)
    (assoc m key (f (get m key)))
    m))

(defn ->editable-metadata [result]
  (-> result
      (select-keys editable-metadata-fields)
      (convert :nationalBuildingIds vec)
      (convert :kuntalupatunnukset  vec)
      (convert :buildingIds vec)
      (as-date :paatospvm)
      (as-date :permit-expired-date)
      (as-date :permit-demolished-date)))

(defn- edit-button [result metadata editing? editable-metadata]
  [:div.edit-button
   [:i {:class    (if @editing? "lupicon-circle-remove edit" "lupicon-pen edit")
        :title    (if @editing? (t "cancel") (t "edit"))
        :on-click (fn []
                    (reset! editable-metadata (assoc (->editable-metadata result)
                                                     :metadata metadata))
                    (reset! editing? (not @editing?)))}]])

(defn type-header-dropdown [editable-metadata option-value-map match-anywhere?]
  (let [results (reagent/atom [])
        input-value (reagent/atom (t (:type @editable-metadata)))
        results-visible? (reagent/atom false)]
    (fn [editable-metadata option-value-map match-anywhere?]
      [:div.dropdown.autocomplete-component
       [:div.dropdown-input.autocomplete-selection-wrapper
        [:span.select-arrow {:class (if @results-visible? "lupicon-chevron-small-up" "lupicon-chevron-small-down")}]
        [:form {:auto-complete "off"}
         [:input {:class     (when @results-visible? "active")
                  :value     @input-value
                  :on-focus  (handler-fn
                               (reset! input-value "")
                               (reset! results (cb/sorted-results option-value-map @input-value match-anywhere?))
                               (reset! results-visible? true))
                  :on-blur   (handler-fn
                               (when (s/blank? @input-value) (reset! input-value (t (:type @editable-metadata))))
                               (reset! results-visible? false))
                  :on-change (fn [e]
                               (let [text (.. e -target -value)
                                     sorted-matches (cb/sorted-results option-value-map text match-anywhere?)]
                                 (reset! input-value text)
                                 (if (and
                                       (= (count sorted-matches) 1)
                                       (= (.toLowerCase (ffirst sorted-matches)) (.toLowerCase text)))
                                   (do
                                     (reset! results-visible? false)
                                     (swap! editable-metadata assoc :type (second (first sorted-matches))))
                                   (do
                                     (reset! results-visible? true)
                                     (reset! results sorted-matches)))))}]]]
       (when @results-visible?
         [:div.autocomplete-dropdown
          [:ul.autocomplete-result
           (doall
             (for [[text value] @results]
               [:li.autocomplete-result-item
                {:key           value
                 :on-mouse-down (fn []
                                  (reset! input-value text)
                                  (swap! editable-metadata assoc :type value)
                                  (reset! results-visible? false))}
                text]))]])])))

(defn type-header-element [type organization edit-possible? editable-metadata]
  (if edit-possible?
    [type-header-dropdown editable-metadata (sf/type-map (state/org-attachment-types organization)) true]
    [:h4 (t (sr/type-str type))]))

(defn content-element [edit-possible? contents editable-metadata]
  [:div.content-element
   (if edit-possible?
     [:input {:type        "text"
              :value       (:contents @editable-metadata)
              :on-change   #(swap! editable-metadata assoc :contents (.. % -target -value))
              :placeholder (t "Sisältö")}]
     (when contents [:div.document-contents contents]))])

(defn address-element [edit-possible? address municipality propertyId applicants editable-metadata]
  (if edit-possible?
    [:div
     [:input {:type        "text"
              :value       (:address @editable-metadata)
              :on-change   #(swap! editable-metadata assoc :address (.. % -target -value))}]
     ", " (sr/municipality-name municipality) " - "
     [:input {:type        "text"
              :value       (:propertyId @editable-metadata)
              :on-change   #(swap! editable-metadata assoc :propertyId (.. % -target -value))}]
     " - " (s/join ", " applicants)
     ]
    [:div
     address ", " (sr/municipality-name municipality) " - "
     (sr/to-human-readable-property-id propertyId) " - " (s/join ", " applicants)]))

(defn result-view-header [editing? editable-metadata
                          {:keys [type contents municipality applicationId organization
                                  propertyId applicants address metadata deleted] :as result}]
  (let [edit-possible? (and @editing? (not deleted))]
    [:div.result-view-header
     [:div.document-info
      [type-header-element type organization edit-possible? editable-metadata]
      [content-element edit-possible? contents editable-metadata]
      [address-element edit-possible? address municipality propertyId applicants editable-metadata]
      [:div.cf
       [:a.application-link {:href (str "/app/fi/authority#!/application/" applicationId)}
        applicationId]]]
     [:div.edit-button-container
      [edit-button result metadata editing? editable-metadata]]]))
