(ns onkalo.ui.components.result-view-preview
  (:require [search-commons.routing :as routing]
            [search-commons.utils.i18n :refer [t]]))

(defn preview [document-in-view {:keys [contentType id organization fileId filename tiedostonimi source-system]}]
  [:div.preview
   [:div.preview-image
    {:on-click (fn [] (when-not (= contentType "image/tiff")
                        (reset! document-in-view {:id            id
                                                  :organization  organization
                                                  :file-id       fileId
                                                  :filename      (or filename tiedostonimi)
                                                  :source-system source-system
                                                  :content-type  contentType})))}
    [:div
     (let [url (routing/path (str "/preview/" organization "/" id))]
       [:img {:src url}])]
    (when-not (= contentType "image/tiff")
      [:div
       [:span.btn.primary.view-document
        [:i.lupicon-eye]
        [:span (t "Avaa dokumentti")]]])]
   [:div.result-button
    [:a.btn.secondary.download-document {:href (routing/path (str "/download/" organization "/" id))}
     [:i.lupicon-download]
     [:span (t "Lataa arkistokappale")]]]])