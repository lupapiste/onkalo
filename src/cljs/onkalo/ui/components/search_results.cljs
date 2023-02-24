(ns onkalo.ui.components.search-results
  (:require [search-commons.utils.state :as state]
            [search-commons.components.multi-select-view :as ms]
            [reagent.core :as reagent]
            [goog.string.format]
            [search-commons.routing :as routing]
            [clojure.string :as s]
            [search-commons.components.search-results :as sr]
            [onkalo.ui.components.result-view :as rv]
            [onkalo.ui.components.mass-operations :as mo]))

(defonce document-in-view (reagent/atom []))

(def result-view
  (with-meta
    (fn []
      (if @state/multi-select-mode
        (ms/select-view mo/mass-operations)
        (when-let [result @state/selected-result]
          [:div.animated-container
           ^{:key (str "result-view-" (:id result))}
           [rv/result-view-component document-in-view result]])))
    {:component-did-update sr/animate-view-transition}))

(defn document-view []
  (let [{:keys [id organization filename content-type]} @document-in-view]
    (when id
      [:div.document-view
       [:div.document-view-header
        filename
        [:div.document-view-exit {:on-click #(reset! document-in-view {})}
         [:i.icon-cancel-circled]]]
       [:div.iframe-container
        (let [element (if (s/starts-with? content-type "image/") :img :iframe)
              url (routing/path (str "/view/" organization "/" id))]
          (if (s/ends-with? content-type "xml")
            (do (sr/fetch-div-content id url)
                [:div {:class "xml-container" :dangerouslySetInnerHTML {:__html (:text @sr/div-content)}}])
            [element {:src url}]))]])))
