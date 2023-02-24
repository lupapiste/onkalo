(ns onkalo.ui.components.header
  (:require [ajax.core :refer [POST]]
            [search-commons.utils.i18n :refer [t]]
            [search-commons.utils.state :as state]
            [clojure.string :as string]
            [reagent.core :as reagent]
            [search-commons.routing :as routing])
  (:require-macros [search-commons.utils.macros :refer [handler-fn]]))

(defn display-name [user]
  (let [{:keys [firstName lastName]} user]
    (when firstName
      (str firstName " " lastName))))

(defn language-options [language-open?]
  [:ul
   (doall
     (for [lang [:fi :sv]]
       ^{:key lang} [:li
                     [:a {:href "#" :on-click (handler-fn (state/set-lang! lang)
                                                          (reset! language-open? false)) }
                      (str (string/upper-case (name lang)) " - " (t lang))]]))])

(defn app-link [lang path]
  (str "/app/" lang "/" path))

(defn header []
  (let [language-open? (reagent/atom false)]
    (fn []
      (let [{:keys [user]} @state/config
            user-name (display-name user)
            lang (name (-> @state/translations :current-lang))]
        [:nav.nav-wrapper
         [:div.nav-top
          [:div.nav-box
           [:div.brand
            [:a.logo {:href  (app-link lang "authority#!/applications")
                      :style {:background        (str "url(" (routing/path "/img/onkalo.png") ")")
                              :background-size   "127px 35px"
                              :background-repeat "no-repeat"}}
             ""]]
           [:div#language-select {:class (if @language-open? "language-open" "language-closed")}
            [:a {:href "#" :on-click (handler-fn (swap! language-open? not))}
             [:span lang]
             [:span {:class (if @language-open? "lupicon-chevron-small-up" "lupicon-chevron-small-down")}]]]
           [:div.header-menu
            [:div.header-box
             [:a {:href (app-link lang "#!/mypage")
                  :title (t "mypage.title")}
              [:span.header-icon.lupicon-user]
              [:span.narrow-hide (or user-name (t "Ei käyttäjää"))]]]
            [:div.header-box
             [:a {:href (app-link lang "logout") :title (t "logout")}
              [:span.header-icon.lupicon-log-out]
              [:span.narrow-hide (t "logout")]]]]]]
         (when @language-open?
           [:div.nav-bottom
            [:div.nav-box
             [:div.language-menu
              (language-options language-open?)]]])]))))
