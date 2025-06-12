(ns onkalo.ui.components.onkalo-search-form
  (:require [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.state :as state]))

(defn onkalo-deleted-query []
  [:div
   [:label
    [:input {:type      "checkbox"
             :checked   (:deleted? @state/search-query)
             :on-change #(swap! state/search-query assoc :deleted? (.. % -target -checked))}]
    [:span (t "Hae vain arkistosta poistetuista")]]])