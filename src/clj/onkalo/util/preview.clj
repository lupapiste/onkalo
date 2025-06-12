(ns onkalo.util.preview
  (:require [onkalo.storage.document :as document]
            [onkalo.util.image-resize :as image-resize]))

;; Attachment types that do not support preview functionality, due to privacy reasons.
(def attachment-types
  {:ennakkoluvat_ja_lausunnot                                        [:elyn_tai_kunnan_poikkeamapaatos
                                                                      :ennakkoneuvottelumuistio
                                                                      :lausunnon_liite
                                                                      :lausunto
                                                                      :naapurien_suostumukset
                                                                      :naapurin_huomautus
                                                                      :naapurin_kuuleminen
                                                                      :naapurin_suostumus
                                                                      :paatos_ajoliittymasta
                                                                      :selvitys_naapurien_kuulemisesta
                                                                      :suunnittelutarveratkaisu
                                                                      :vesi_ja_viemariliitoslausunto_tai_kartta
                                                                      :ymparistolupa]
   :hakija                                                           [:osakeyhtion_perustamiskirja
                                                                      :ote_asunto_osakeyhtion_hallituksen_kokouksen_poytakirjasta
                                                                      :ote_kauppa_ja_yhdistysrekisterista
                                                                      :ottamisalueen_omistus_hallintaoikeus
                                                                      :valtakirja]
   :katselmukset_ja_tarkastukset                                     [:aloituskokouksen_poytakirja
                                                                      :katselmuksen_liite
                                                                      :katselmuksen_tai_tarkastuksen_poytakirja
                                                                      :kayttoonottokatselmuksen_poytakirja
                                                                      :loppukatselmuksen_poytakirja
                                                                      :tarkastusasiakirja
                                                                      :tarkastusasiakirjan_yhteeveto]
   :kaytostapoistetun-oljy-tai-kemikaalisailion-jattaminen-maaperaan [:kiinteiston-omistajien-suostumus
                                                                      :sailion-tarkastuspoytakirja]
   :kiinteiston_hallinta                                             [:jakosopimus
                                                                      :jaljennos_perunkirjasta
                                                                      :kaupparekisteriote
                                                                      :ote_asunto-osakeyhtion_kokouksen_poytakirjasta
                                                                      :ote_osakeyhtion_hallituksen_kokouksen_poytakirjasta
                                                                      :ote_osakeyhtion_yhtiokokouksen_poytakirjasta
                                                                      :rasitesopimus
                                                                      :saantokirja
                                                                      :sukuselvitys
                                                                      :testamentti
                                                                      :tilusvaihtosopimus
                                                                      :yhtiojarjestys]
   :laitoksen_tiedot                                                 [:muut_paatokset_sopimukset
                                                                      :selvitys_ymparistovahinkovakuutuksesta
                                                                      :voimassa_olevat_ymparistolupa_vesilupa]
   :muistomerkin-rauhoittaminen                                      [:kauppakirja
                                                                      :lainhuutotodistus
                                                                      :selvitys-omistusoikeudesta]
   :muut                                                             [:paatos
                                                                      :paatosote
                                                                      :sijoituslupaasiakirja
                                                                      :sopimus
                                                                      :suorituskyvyttomyysvakuusasiakirja
                                                                      :tutkimus
                                                                      :vakuusasiakirja]
   :muutoksenhaku                                                    [:huomautus
                                                                      :oikaisuvaatimus
                                                                      :valitus]
   :osapuolet                                                        [:cv
                                                                      :paa_ja_rakennussuunnittelijan_tiedot
                                                                      :patevyystodistus
                                                                      :suunnittelijan_tiedot
                                                                      :tutkintotodistus]
   :paatoksenteko                                                    [:hakemus
                                                                      :ilmoitus
                                                                      :muistio
                                                                      :paatoksen_liite
                                                                      :paatos
                                                                      :paatosehdotus
                                                                      :paatosote
                                                                      :poytakirjaote
                                                                      :valitusosoitus]
   :rakennuspaikan_hallinta                                          [:jaljennos_kauppakirjasta_tai_muusta_luovutuskirjasta
                                                                      :jaljennos_myonnetyista_lainhuudoista
                                                                      :jaljennos_perunkirjasta
                                                                      :jaljennos_vuokrasopimuksesta
                                                                      :kiinteiston_lohkominen
                                                                      :ote_asunto-osakeyhtion_kokouksen_poytakirjasta
                                                                      :ote_yhtiokokouksen_poytakirjasta
                                                                      :rasitesopimus
                                                                      :rasitustodistus
                                                                      :sopimusjaljennos
                                                                      :todistus_erityisoikeuden_kirjaamisesta
                                                                      :todistus_hallintaoikeudesta]
   :yleiset-alueet                                                   [:aiemmin-hankittu-sijoituspaatos
                                                                      :valtakirja]})

(def attachment-type-set (->> attachment-types
                              (mapcat (fn [[k xs]]
                                        (map #(str (name k) "." (name %)) xs)))
                              set))

(defn preview-allowed?
  "True if the preview is not explicitly disallowed via `attachment-types`. Argument is a string in
  'group.type' format (e.g., 'muut.energiatodistus')."
  [attachment-type]
  (not (contains? attachment-type-set attachment-type)))

(defn get-document-store-preview [config id organization size]
  (let [{:keys [status body]
         :as   response} (document/document-metadata config id organization false ["metadata.type"])
        actual-size (-> (or size 600)
                        (min 1000)
                        (max 100))]
    (cond
      (and (= status 200) (preview-allowed? (some-> body :metadata :type)))
      (-> (document/get-preview config id organization)
          (update :body image-resize/scale actual-size))

      ;; Document exists but preview is not allowed
      (= status 200)
      {:status 403 ; Forbidden
       :body   {:error "Preview disallowed"}}

      ;; Metadata fail
      :else response)))
