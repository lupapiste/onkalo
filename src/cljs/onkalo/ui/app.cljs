(ns onkalo.ui.app
  (:require [onkalo.routing :as onkalo-routing]
            [onkalo.ui.components.onkalo-search-form :as onkalo-search-form]
            [onkalo.ui.components.search-results :as sr]
            [reagent.dom :as rd]
            [search-commons.components.dialog :as dialog]
            [search-commons.components.footer :as footer]
            [search-commons.components.header :as header]
            [search-commons.components.search-form :as search-form]
            [search-commons.components.search-results :as common-search-results]
            [search-commons.routing :as routing]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.state :as state]))

(defn search-view []
  [:div.app-container
   [header/header
    {:background        (str "url(" (routing/path "/img/onkalo.png") ")")
     :background-size   "127px 35px"
     :background-repeat "no-repeat"}]
   [:div.container
    (when @state/dialog-data [dialog/dialog])
    [:h1 (t "Haku")]
    [search-form/input-form onkalo-search-form/onkalo-deleted-query]
    [common-search-results/result-section sr/document-view sr/result-view]]
   [footer/footer]])

(defn render []
  (rd/render [search-view] (.getElementById js/document "app")))

(defn start []
  (enable-console-print!)
  (routing/set-root onkalo-routing/ui-root)
  (state/fetch-operations)
  (state/fetch-translations :fi)
  (state/fetch-user-and-config)
  (render))

(start)
