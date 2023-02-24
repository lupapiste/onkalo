(ns onkalo.ui.components.footer
  (:require [search-commons.utils.i18n :refer [t]]))

(defn footer []
  [:footer
   [:div.informations
    [:div.footer-box
     [:h3 (t "footer.conversation.title")]
     [:span (t "footer.conversation.desc")]]
    [:div.footer-box
     [:h3 (t "footer.report-issue.title")]
     [:span (t "footer.report-issue.desc")]
     [:span
       [:a {:href (t "footer.report-issue.link-href")} (t "footer.report-issue.link-text")]]]
    [:div.footer-box
     [:ul
      [:li
       [:a {:href (t "footer.register.link-href")} (t "footer.register")]]
      [:li
       [:a {:href (t "footer.terms.link-href")} (t "footer.terms")]]
      [:li
       [:a {:href (t "footer.license.link-href")} (t "footer.license")]]]]
    [:div.footer-box
     [:p]]]])
