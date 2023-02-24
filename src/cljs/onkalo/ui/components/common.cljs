(ns onkalo.ui.components.common
  (:require [reagent.core :as reagent]
            [reagent.ratom :refer [reaction]]
            [search-commons.utils.i18n :refer [t]]))

(defn- noop [e])

(defn ->val [event]
  (.. event -target -value))

(defn- vec-remove
  "remove elem in collection"
  [coll index]
  (vec (concat (subvec coll 0 index) (subvec coll (inc index)))))

(defn text-input [{:keys [id label value-atom value on-change disabled?
                          on-click validate error tooltip error-moves-content?] :as props}]
  (let [validate (or validate noop)
        error (when (and error (not disabled?))
                error)
        val (if value-atom @value-atom value)
        on-change (or on-change
                      (fn [e]
                           (let [value (->val e)]
                             (reset! value-atom value))))]
    (validate val)  ;;Validate in render to ensure validity when no on-change occurs.
                    ;;Life-cycle method in a form-3 component would be more efficient but adds complexity.

    [:div.text-field {:on-click (or on-click noop)
                      :title tooltip}
     [:label {:for id} label]
     [:input {:id     id
              :type   "text"
              :value  (or val "")
              :on-change on-change
              :disabled disabled?}]
     (if error-moves-content?
       (when error [:span.error error])
       [:span.error {:dangerouslySetInnerHTML {:__html (or error "&nbsp;")}}])]))

(defn text-input-list [{:keys [values-atom validate item->error]}]
  (let [validate (or validate noop)
        item->error (or item->error noop)
        more-than-one-item (reaction (> (count @values-atom) 1))]
    (fn [{:keys [id label values-atom disabled? on-click error tooltip error-moves-content?] :as props}]
      ;;Life-cycle method in a form-3 component would be more efficient but adds complexity.
      (validate @values-atom)
      [:div.list-container
       (doall (for [[index value] (map-indexed vector @values-atom)]
                ^{:key index}
                [:div.removable-item
                 [text-input {:id (str id index)
                              :label (if (zero? index) label)
                              :on-click on-click
                              :on-change (fn [e]
                                           (reset! values-atom (assoc @values-atom index (->val e))))
                              :value value
                              :error (item->error value)
                              :tooltip tooltip
                              :disabled? disabled?
                              :error-moves-content? error-moves-content?}]
                 (if @more-than-one-item
                   [:i.lupicon-remove.remove-item {:class (if disabled? "remove-disabled" "negative")
                                                   :on-click (fn []
                                                               (when-not disabled?
                                                                 (reset! values-atom (vec-remove @values-atom index))))}])]))
       [:button.positive.add-item {:disabled (or disabled? (empty? @values-atom))
                                   :on-click (fn [] (reset! values-atom (conj @values-atom "")))}
        [:i.lupicon-circle-plus]
        [:span (t "Lisää uusi")]]])))
