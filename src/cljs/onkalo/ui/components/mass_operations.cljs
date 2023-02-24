(ns onkalo.ui.components.mass-operations
  (:require [ajax.core :refer [POST]]
            [cognitect.transit :as transit]
            [clojure.string :refer [blank?]]
            [onkalo.ui.components.common :as co :refer [text-input text-input-list]]
            [reagent.core :as reagent]
            [reagent.ratom :refer [reaction]]
            [search-commons.components.dialog :as dialog]
            [search-commons.components.multi-select-view :as msv]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.time :as tu]
            [search-commons.routing :as routing]
            [search-commons.utils.state :as state]
            [search-commons.shared-utils :as utils]
            [search-commons.components.search-results :as sr]
            [search-commons.components.time :as t]))

(defn doc-entry [{:keys [source organization id fileId tiedostonimi filename applicationId type deleted] :as doc}]
  {:source source
   :org-id organization
   :doc-id id
   :file-id (or fileId id)
   :filename (or tiedostonimi filename)
   :application-id applicationId
   :type type
   :deleted deleted})

(defn prep-change-archiving-status-request-map! [operation deletion-explanation]
  (swap! state/mass-operation-request-map assoc :docs (if @state/multi-select-mode
                                                        @state/multi-selected-results
                                                        #{(doc-entry @state/selected-result)})
                                                :operation operation
                                                :deletion-explanation deletion-explanation))

(defn prep-update-mass-metadata-request-map! [operation metadata]
  (swap! state/mass-operation-request-map assoc :docs @state/multi-selected-results
                                                :metadata metadata
                                                :operation operation))

(defn update-results [{:keys [success? doc-id updated-metadata]}]
  (when success?
    (state/update-onkalo-result-data doc-id updated-metadata)))

(defn update-documents-metadata [result]
  (doseq [single-doc-result result] (update-results single-doc-result)))

(defn handler [header message result]
  (let [ok-fn (fn []
                (reset! state/mass-operation-request-map nil)
                (reset! state/multi-selected-results #{})
                (update-documents-metadata result))]
    (dialog/ok-dialog header message ok-fn)))

(defn change-archiving-status [operation deletion-explanation]
  (let [success-header (t "Ilmoitus")
        success-message (t (str (name operation) ".success"))
        error-header (t "Virhe")
        error-message (t (str (name operation) ".error"))]
    (prep-change-archiving-status-request-map! operation deletion-explanation)
    (POST (routing/path "/change-archiving-status")
          {:params  @state/mass-operation-request-map
           :handler (partial handler success-header success-message)
           :error-handler #(handler error-header error-message (:response %))})))

(defn mass-update-metadata [operation metadata]
  (let [success-header (t "Ilmoitus")
        success-message (t (str (name operation) ".success"))
        error-header (t "Virhe")
        error-message (t (str (name operation) ".error"))]
    (prep-update-mass-metadata-request-map! operation metadata)
    (POST (routing/path "/mass-update-metadata")
          {:params  @state/mass-operation-request-map
           :handler (partial handler success-header success-message)
           :error-handler #(handler error-header error-message (:response %))})))

(defn mass-undo-archiving-button []
  (let [total (count @state/multi-selected-results)
        total-not-deleted (count (remove :deleted @state/multi-selected-results))
        header (t "Vahvista poistaminen")
        message (str (t "Olet poistamassa valittuja tiedostoja arkistosta") " (" total " " (t "kpl") ")")
        confirm-text (t "Poista")
        confirm-button "negative"
        confirm-fn (partial change-archiving-status :undo-archiving)]
    [:button.negative
     {:on-click #(dialog/yes-no-dialog-with-explanation header message confirm-fn confirm-button confirm-text)
      :disabled (or (= 0 total-not-deleted)
                    (= 0 total))}
     [:i.lupicon-remove]
     [:span (t "Merkitse valitut poistetuksi")]]))

(defn mass-redo-archiving-button []
  (let [total (count @state/multi-selected-results)
        total-deleted (count (filter :deleted @state/multi-selected-results))
        header (t "Vahvista palauttaminen")
        message (str (t "Olet palauttamassa valitut tiedostot arkistoon") " (" total " " (t "kpl") ")")
        confirm-text (t "Palauta")
        confirm-button "positive"
        confirm-fn (partial change-archiving-status :redo-archiving)]
    [:button.positive
     {:on-click #(dialog/yes-no-dialog-with-explanation header message confirm-fn confirm-button confirm-text)
      :disabled (or (= 0 total-deleted)
                    (= 0 total))}
   [:i.lupicon-save]
   [:span (t "Palauta valitut arkistoon")]]))

(defn mass-update-metadata-button [{:keys [metadata disabled]}]
  (let [header (t "Vahvista päivittäminen")
        message (t "Olet päivittämässä valittujen tiedostojen metadataa")
        confirm-text (t "Päivitä")
        confirm-button "positive"
        confirm-fn (partial mass-update-metadata :mass-update-metadata metadata)]
    [:button.positive
     {:on-click #(dialog/yes-no-dialog header message confirm-fn confirm-button confirm-text)
      :disabled disabled}
     [:i.lupicon-save]
     [:span (t "Päivitä valitut metatiedot")]]))

(defn- toggle! [bool-atom]
  (reset! bool-atom (not @bool-atom)))

(defn- switch-on! [bool-atom]
  (reset! bool-atom true))

(defn- noop [e])

(defn ->val [event]
  (.. event -target -value))

(defn ->checked [event]
  (.. event -target -checked))

(defn checkbox [{:keys [id label value-atom disabled? on-click tooltip on-change]}]
  (let [on-change (or on-change noop)]
    [:div {:on-click (or on-click noop)
           :title tooltip}
     [:input {:id     id
              :type   "checkbox"
              :checked   @value-atom
              :on-change (fn [e]
                           (when value-atom (toggle! value-atom))
                           (on-change (->checked e)))
              :disabled disabled?}]
     [:label {:for id} label]]))

(defn yes-no-select [{:keys [id label value-atom disabled? on-click tooltip on-change css-class]}]
  (let [on-change (or on-change noop)]
    [:div {:on-click (or on-click noop)
           :title tooltip
           :class css-class}
     [:label {:for id} label]
     [:div
      [:select.yes-no-select {:id id
                              :value     @value-atom
                              :on-change (fn [e]
                                           (when value-atom (toggle! value-atom))
                                           (on-change (->val e)))
                              :disabled  disabled?}
       [:option {:value true}  (t "Kyllä")]
       [:option {:value false} (t "Ei")]]]]))

(defn- format-current-values [current-values]
  (when (seq current-values)
    (str (t "Valittujen dokumenttien nykyiset arvot") ": "
         (->> current-values
              set
              vec
              (interpose ", ")
              (apply str)))))

(defn- get-value-in [path]
  (fn [m] (get-in m path)))

(defn- bool->text [bool]
  (get {true (t "Kyllä")
        false (t "Ei")
        nil   (t "Ei")}
       bool))

(def not-blank? (comp not blank?))

(defn building-ids [{:keys [id label value-atom disabled? on-click validate error tooltip] :as args}]
  (let [user-input (reagent/atom "")
        id-list (reaction (utils/->tokens @user-input))]
    (fn [{:keys [id label value-atom disabled? on-click validate error tooltip] :as args}]
      (reset! value-atom @id-list)
      [text-input {:id id
                   :label label
                   :on-click on-click
                   :value-atom user-input
                   :validate #(validate @id-list)
                   :disabled? disabled?
                   :tooltip tooltip
                   :error error}])))

(defn metadata-update-form []
  (let [docs-selected? (reaction (< 0 (count @state/multi-selected-results)))
        metadata (reagent/atom {:kuntalupatunnukset            {:value [""]  :selected false}
                                :kuntalupatunnukset-muutossyy  {:value ""    :selected false}
                                :paatospvm                     {:value nil   :selected false}
                                :myyntipalvelu                 {:value false :selected false}
                                :propertyId                    {:value ""    :selected false :convert-fn utils/->db-property-id}
                                :nationalBuildingIds           {:value []    :selected false}
                                :address                       {:value ""    :selected false}
                                :permit-expired                {:value false :selected false}
                                :permit-expired-date           {:value nil   :selected false}
                                :demolished                    {:value false :selected false}
                                :demolished-date               {:value nil   :selected false}})

        kuntalupatunnukset-cur                 (reagent/cursor metadata [:kuntalupatunnukset :value])
        kuntalupatunnukset-selected-cur        (reagent/cursor metadata [:kuntalupatunnukset :selected])
        kuntalupatunnukset-error-cur           (reagent/cursor metadata [:kuntalupatunnukset :error])

        kuntalupatunnukset-muutossyy-cur       (reagent/cursor metadata [:kuntalupatunnukset-muutossyy :value])
        kuntalupatunnukset-muutossyy-error-cur (reagent/cursor metadata [:kuntalupatunnukset-muutossyy :error])

        paatospvm-cur                          (reagent/cursor metadata [:paatospvm :value])
        paatospvm-selected-cur                 (reagent/cursor metadata [:paatospvm :selected])

        myyntipalvelu-cur                      (reagent/cursor metadata [:myyntipalvelu :value])
        myyntipalvelu-selected-cur             (reagent/cursor metadata [:myyntipalvelu :selected])

        propertyid-cur                         (reagent/cursor metadata [:propertyId :value])
        propertyid-selected-cur                (reagent/cursor metadata [:propertyId :selected])
        propertyid-error-cur                   (reagent/cursor metadata [:propertyId :error])

        buildingids-cur                        (reagent/cursor metadata [:nationalBuildingIds :value])
        buildingids-selected-cur               (reagent/cursor metadata [:nationalBuildingIds :selected])
        buildingids-error-cur                  (reagent/cursor metadata [:nationalBuildingIds :error])

        address-cur                            (reagent/cursor metadata [:address :value])
        address-selected-cur                   (reagent/cursor metadata [:address :selected])
        address-error-cur                      (reagent/cursor metadata [:address :error])

        expired-cur                            (reagent/cursor metadata [:permit-expired :value])
        expired-selected-cur                   (reagent/cursor metadata [:permit-expired :selected])

        expired-date-cur                       (reagent/cursor metadata [:permit-expired-date :value])
        expired-date-selected                  (reaction (and @expired-selected-cur @expired-cur @expired-date-cur))

        demolished-cur                         (reagent/cursor metadata [:demolished :value])
        demolished-selected-cur                (reagent/cursor metadata [:demolished :selected])

        demolished-date-cur                    (reagent/cursor metadata [:demolished-date :value])
        demolished-date-selected               (reaction (and @demolished-selected-cur @demolished-cur @demolished-date-cur))

        selected-metadata (reaction (->> @metadata
                                         (filter (fn [[_ {:keys [selected error]}]]
                                                   (and selected (not error))))
                                         (map (fn [[key {:keys [value convert-fn]}]]
                                                [key ((or convert-fn identity) value)]))
                                         (into {})))
        has-error?       (fn [[_ {:keys [selected error]}]] (and selected error))
        submit-disabled? (reaction (or (= 0 (count @state/multi-selected-results))
                                       (empty? @selected-metadata)
                                       (some has-error? @metadata)))]
    (fn []
      (swap! metadata assoc-in [:permit-expired-date :selected] @expired-date-selected)
      (swap! metadata assoc-in [:demolished-date :selected] @demolished-date-selected)
      (swap! metadata assoc-in [:kuntalupatunnukset-muutossyy :selected] @kuntalupatunnukset-selected-cur)
      [:div.metadata-editor-container
       [:div.editor
        [:div.editor-header
         [:div (t "Metatiedot")]]
        [:div.editor-form

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom paatospvm-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [:div.date-field
           [t/date-field {:value-atom paatospvm-cur
                          :label (t "Päätöspäivämäärä")
                          :on-click #(when @docs-selected? (switch-on! paatospvm-selected-cur))
                          :prevent-manual-entry true
                          :tooltip (format-current-values
                                    (map (comp tu/format-date :paatospvm)
                                         @state/multi-selected-results))
                          :disabled (or (not @docs-selected?)
                                        (not @paatospvm-selected-cur))}]]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom myyntipalvelu-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [yes-no-select {:id "mass-update-myyntipalvelu"
                          :label (t "myyntipalvelu")
                          :on-click #(when @docs-selected? (switch-on! myyntipalvelu-selected-cur))
                          :value-atom myyntipalvelu-cur
                          :tooltip (format-current-values
                                    (map (comp bool->text (get-value-in [:metadata :myyntipalvelu]))
                                         @state/multi-selected-results))
                          :css-class "yes-no-wrapper"
                          :disabled? (or (not @docs-selected?)
                                         (not @myyntipalvelu-selected-cur))}]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom kuntalupatunnukset-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [text-input-list {:id "mass-update-kuntalupatunnukset"
                            :label (t "Kuntalupatunnus")
                            :on-click #(when @docs-selected? (switch-on! kuntalupatunnukset-selected-cur))
                            :values-atom kuntalupatunnukset-cur
                            :item->error (fn [item]
                                          (when (blank? item)
                                            (t "Syötä kuntalupatunnus")))
                            :validate (fn [values]
                                        (if (some blank? (or values []))
                                          (reset! kuntalupatunnukset-error-cur true)
                                          (reset! kuntalupatunnukset-error-cur false)))
                            :tooltip (format-current-values (map :kuntalupatunnukset @state/multi-selected-results))
                            :disabled? (or (not @docs-selected?)
                                           (not @kuntalupatunnukset-selected-cur))}]

          [:div.right-field.max-width
           [text-input {:id "mass-update-kuntalupatunnukset-muutossyy"
                       :label (t "Muutoksen syy")
                       :on-click #(when @docs-selected? (switch-on! kuntalupatunnukset-selected-cur))
                       :value-atom kuntalupatunnukset-muutossyy-cur
                       :validate (fn [value]
                                   (if (blank? value)
                                     (reset! kuntalupatunnukset-muutossyy-error-cur (t "Muutoksen syy vaaditaan"))
                                     (reset! kuntalupatunnukset-muutossyy-error-cur nil)))
                       :error     @kuntalupatunnukset-muutossyy-error-cur
                       :disabled? (or (not @docs-selected?)
                                      (not @kuntalupatunnukset-selected-cur))}]]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom propertyid-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [text-input {:id "mass-update-property-id"
                       :label (t "propertyId")
                       :on-click #(when @docs-selected? (switch-on! propertyid-selected-cur))
                       :value-atom propertyid-cur
                       :validate (fn [value]
                                   (if (utils/property-id? value)
                                     (reset! propertyid-error-cur nil)
                                     (reset! propertyid-error-cur (t "Anna kiinteistötunnus oikeassa muodossa"))))
                       :error     @propertyid-error-cur
                       :tooltip (format-current-values (map (comp sr/to-human-readable-property-id :property-id)
                                                            @state/multi-selected-results))
                       :disabled? (or (not @docs-selected?)
                                      (not @propertyid-selected-cur))}]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom buildingids-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [building-ids {:id "mass-update-national-building-ids"
                         :label (t "Valtakunnalliset rakennustunnukset")
                         :on-click #(when @docs-selected? (switch-on! buildingids-selected-cur))
                         :value-atom buildingids-cur
                         :validate (fn [building-ids]
                                     (if (and (not-empty building-ids)
                                              (every? utils/rakennustunnus? building-ids))
                                       (reset! buildingids-error-cur nil)
                                       (reset! buildingids-error-cur (t "Anna rakennustunnukset oikeassa muodossa"))))
                         :error    @buildingids-error-cur
                         :tooltip (format-current-values (map :national-building-ids
                                                              @state/multi-selected-results))
                         :disabled? (or (not @docs-selected?)
                                        (not @buildingids-selected-cur))}]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom address-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [text-input {:id "mass-update-address"
                       :label (t "address")
                       :on-click #(when @docs-selected? (switch-on! address-selected-cur))
                       :value-atom address-cur
                       :validate (fn [value]
                                   (if (not-empty value)
                                       (reset! address-error-cur nil)
                                       (reset! address-error-cur (t "Syötä osoite"))))
                       :error   @address-error-cur
                       :tooltip (format-current-values (map :address @state/multi-selected-results))
                       :disabled? (or (not @docs-selected?)
                                      (not @address-selected-cur))}]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom expired-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [yes-no-select {:id "mass-update-permit-expired"
                          :label (t "Lupa on rauennut")
                          :on-click #(when @docs-selected? (switch-on! expired-selected-cur))
                          :value-atom expired-cur
                          :tooltip (format-current-values
                                    (map (comp bool->text :permit-expired)
                                         @state/multi-selected-results))
                          :disabled? (or (not @docs-selected?)
                                         (not @expired-selected-cur))
                          :css-class "yes-no-wrapper"}]
          [:div.date-field.right-field
           [t/date-field {:value-atom expired-date-cur
                          :label (t "Raukeamispäivämäärä")
                          :visible? (and @docs-selected?
                                         @expired-selected-cur
                                         @expired-cur)
                          :prevent-manual-entry true
                          :tooltip (format-current-values
                                    (map (comp tu/format-date :permit-expired-date)
                                         @state/multi-selected-results))}]]]

         [:div.mass-update-row
          [:div.select-metadata
           [checkbox {:value-atom demolished-selected-cur
                      :disabled? (not @docs-selected?)}]]
          [yes-no-select {:id "mass-update-demolished"
                          :label (t "Rakennus on purettu")
                          :on-click #(when @docs-selected? (switch-on! demolished-selected-cur))
                          :value-atom demolished-cur
                          :tooltip (format-current-values
                                    (map (comp bool->text :demolished)
                                         @state/multi-selected-results))
                          :disabled? (or (not @docs-selected?)
                                         (not @demolished-selected-cur))
                          :css-class "yes-no-wrapper"}]
          [:div.date-field.right-field
            [t/date-field {:value-atom demolished-date-cur
                           :label (t "Purkamispäivämäärä")
                           :visible? (and @docs-selected?
                                          @demolished-selected-cur
                                          @demolished-cur)
                           :prevent-manual-entry true
                           :tooltip (format-current-values
                                     (map (comp tu/format-date :demolished-date)
                                          @state/multi-selected-results))}]]]

         [:div.mass-update-button-row
          [mass-update-metadata-button {:metadata @selected-metadata
                                        :disabled @submit-disabled?}]]]]])))

(defn mass-operations []
  (fn []
    [:div
     [:div.select-view-header
      [:h4 (t "Massatoiminnot")]]
     [:div.operations-buttons
      [:div.select-view-content.stacked
       [mass-undo-archiving-button]
       [mass-redo-archiving-button]]]
     [:div.mass-update-metadata
      [:div.metadata-fields
       [metadata-update-form]]]]))
