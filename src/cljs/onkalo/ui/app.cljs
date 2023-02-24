(ns onkalo.ui.app
  (:require [cljs.pprint :refer [pprint]]
            [onkalo.routing :as onkalo-routing]
            [onkalo.ui.components.footer :as footer]
            [onkalo.ui.components.header :as header]
            [onkalo.ui.components.onkalo-search-form :as onkalo-search-form]
            [onkalo.ui.components.search-results :as sr]
            [reagent.dom :as rd]
            [search-commons.components.dialog :as dialog]
            [search-commons.components.search-form :as search-form]
            [search-commons.components.search-results :as common-search-results]
            [search-commons.routing :as routing]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.state :as state]
            [search-commons.utils.utils :as utils]))

(defn search-view []
  [:div.app-container
   [header/header]
   [:div.wrapper {:id "wrapper"}
    (when @state/dialog-data [dialog/dialog])
    [:h1 (t "Haku")]
    [search-form/input-form onkalo-search-form/onkalo-deleted-query]
    [common-search-results/result-section sr/document-view sr/result-view]]
   [footer/footer]])

(defn ^:export start []
  (utils/setup-print-to-console!)
  (routing/set-root onkalo-routing/ui-root)
  (state/fetch-operations)
  (state/fetch-translations :fi)
  (state/fetch-user-and-config)
  (rd/render [search-view] (.getElementById js/document "app")))
