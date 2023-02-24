(ns onkalo.domain
  (:require [schema.core :as s]
            [lupapiste-commons.tos-metadata-schema :as tms]))

(defn field-editable? [k]
  (#{:julkisuusluokka :salassapitoaika :salassapitoperuste :suojaustaso :turvallisuusluokka
     :kayttajaryhma :kayttajaryhmakuvaus :henkilotiedot :arkistointi :pituus :laskentaperuste
     :perustelu :myyntipalvelu :type :contents :nakyvyys :deleted :deletion-explanation :address
     :propertyId :projectDescription :firstName :lastName :location :permit-expired :permit-expired-date
     :demolished :demolished-date :paatospvm :kuntalupatunnukset :kuntalupatunnukset-muutossyy} k))

(defn field-editable-via-api? [k]
  (#{:nationalBuildingIds :buildingIds} k))

(def history-event
  {:modified s/Inst
   :user     {(s/optional-key :userId) (s/maybe s/Str)
              (s/optional-key :username) tms/NonEmptyStr
              (s/optional-key :firstName) tms/NonEmptyStr
              :lastName tms/NonEmptyStr}
   :field    [s/Keyword]
   :old-val  s/Any
   :new-val  s/Any
   (s/optional-key :deletion-explanation) tms/NonEmptyStr})
