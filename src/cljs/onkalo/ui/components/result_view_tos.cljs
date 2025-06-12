(ns onkalo.ui.components.result-view-tos
  (:require-macros [reagent.ratom :refer [reaction]]
                   [search-commons.utils.macros :refer [handler-fn]])
  (:require [search-commons.utils.i18n :refer [t]]
            [clojure.string :as string]
            [reagent.core :as reagent]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [schema.core :as sc]
            [ajax.core :refer [POST]]
            [onkalo.domain :as d])
  (:import [goog.i18n DateTimeFormat]))

(defn handle-change [metadata-cursor ks value]
  (swap! metadata-cursor assoc-in ks value))

(defn generic-text-input [edit-possible? attribute metadata-cursor metadata ks]
  (if edit-possible?
   (let [value (if (get-in @metadata-cursor ks)
                 (get-in @metadata-cursor ks)
                 (get-in (handle-change metadata-cursor ks nil) ks))]
    [:input {:type        "text"
             :value       value
             :on-change   #(handle-change metadata-cursor ks (.. % -target -value))
             :placeholder (t (:type attribute))}])
    [:div (if (string/blank? (get-in metadata ks)) (t "<Arvo puuttuu>") (get-in metadata ks))]))

(defn positive? [value]
  (when (pos? value)
    value))

(defn parse-number [x]
  (if (number? x)
    x
    (some-> (re-find #"^\d+$" x)
            (js/parseInt 10))))

(defn parse-years [value]
  (some->> value
           parse-number
           positive?))

(defn years-field [edit-possible? metadata-cursor metadata ks]
  [:div.years-field
   (if edit-possible?
     (let [value (if (get-in @metadata-cursor ks)
                   (get-in @metadata-cursor ks)
                   (get-in (handle-change metadata-cursor ks nil) ks))]
       [:input {:type      "number"
                :value     value
                :on-change (fn [e] (when-let [num (parse-years (.. e -target -value))]
                                     (handle-change metadata-cursor ks num)))}])
     [:span (get-in metadata ks)])
   [:span.year-label (t "vuotta")]])

(defn select-field [edit-possible? metadata-cursor metadata ks values]
  (if edit-possible?
    ^{:key (str (name (last ks)) "-select")}
    (let [value (if (get-in @metadata-cursor ks)
                  (get-in @metadata-cursor ks)
                  (get-in (handle-change metadata-cursor ks (first values)) ks))]
      [:select {:value     value
                :on-change #(handle-change metadata-cursor ks (keyword (.. % -target -value)))}
       (doall
         (for [val values]
           ^{:key (name val)}
           [:option {:value (name val)} (t val)]))])
    [:div (t (or (get-in metadata ks) (first values) "<Arvo puuttuu>"))]))

(defn date-time-label [metadata ks]
  (if-let [v (get-in metadata ks)]
    [:div (.format (DateTimeFormat. "d.M.yyyy HH:mm z") v)]
    [:div (t "Lasketaan automaattisesti")]))

(defn retention-period [metadata-cursor metadata edit-possible?]
  (let [{{:keys [arkistointi]} :sailytysaika} metadata]
    [:div.subtable
     [:h4 (t "Säilytysaika")]
     [:div
      [:label (t "Arkistointi")]
      (select-field edit-possible? metadata-cursor metadata [:sailytysaika :arkistointi] tms/arkistointi)]
     (when (= (keyword arkistointi) :määräajan)
       [:div
        [:div
         [:label (t "Arkistointiaika")]
         [years-field edit-possible? metadata-cursor metadata [:sailytysaika :pituus]]]
        [:div
         [:label (t :retention-period-end)]
         [:div (date-time-label metadata [:sailytysaika :retention-period-end])]]])
     (when (= (keyword arkistointi) :toistaiseksi)
       [:div
        [:label (t "Päättymisen peruste")]
        (select-field edit-possible? metadata-cursor metadata [:sailytysaika :laskentaperuste] tms/laskentaperuste)])
     [:div
      [:label (t "Perustelu")]
      (generic-text-input edit-possible? {:type :perustelu} metadata-cursor metadata [:sailytysaika :perustelu])]]))

(defn generic-bool-input [edit-possible? metadata-cursor metadata ks]
  (if edit-possible?
    [:div.checkbox-container
     [:input {:type      "checkbox"
              :checked   (get-in @metadata-cursor ks)
              :on-change #(handle-change metadata-cursor ks (not (get-in @metadata-cursor ks)))}]
     [:label (-> ks first t)]]
    [:div (if (get-in metadata ks) (t "yes") (t "no"))]))

(defn edit-field [edit-possible? attribute metadata-cursor metadata ks]
  (condp = (:schema attribute)
    tms/Vuodet (years-field edit-possible? metadata-cursor metadata ks)
    sc/Bool (generic-bool-input edit-possible? metadata-cursor metadata ks)
    sc/Inst (date-time-label metadata ks)
    (generic-text-input edit-possible? attribute metadata-cursor metadata ks)))

(def s2-metadata
  (concat tms/common-metadata-fields tms/asiakirja-metadata-fields))

(defn render-attribute [{md-type :type :as attribute} edit-possible? metadata-cursor metadata retention-edit-allowed?]
  (condp = attribute
    tms/SailytysAika (retention-period metadata-cursor metadata (and edit-possible? retention-edit-allowed?))
    (let [ks [md-type]
          value (or (md-type @metadata-cursor) (md-type metadata))]
      (conj
        [:div [:label (t md-type)]
         [:br]
         (if-let [values (:values attribute)]
           (select-field (and edit-possible? (d/field-editable? md-type)) metadata-cursor metadata ks values)
           (edit-field (and edit-possible? (d/field-editable? md-type)) attribute metadata-cursor metadata ks))]
        (when-let [dependencies (get (:dependencies attribute) (or value (first (:values attribute))))]
          (doall
            (for [dep dependencies]
              ^{:key (name (:type dep))}
              [:div (render-attribute dep edit-possible? metadata-cursor metadata retention-edit-allowed?)])))))))

(defn tos-metadata [edit-possible? editable-metadata metadata id]
  [:div.metadata-editor-container
   ^{:key (str id "-editor")}
   [:div.editor
    [:div.editor-header
     [:div (t "Tiedonohjausmetatiedot")]]
    [:div.editor-form
     (doall
       (for [md s2-metadata]
         ^{:key (name (:type md))}
         [:div (render-attribute
                 md
                 edit-possible?
                 (reagent/cursor editable-metadata [:metadata])
                 metadata
                 (not= :ikuisesti (get-in metadata [:sailytysaika :arkistointi])))]))]]])
