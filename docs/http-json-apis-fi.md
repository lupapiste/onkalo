# Onkalon HTTP/JSON-rajapinnat

## Autentikaatio

Rajapintoja voi käyttää API-keyn avulla.

API-key autentikaatio tapahtuu HTTP Basic authenticationilla. Jokaisen pyynnön HTTP-otsakkeissa pitää siis olla
Authorization-header, jonka arvo on `Basic <app-id>:<app-key>`, josta <app-id>:<app-key> osa Base64-enkoodattuna.

## Yleisperiaatteet tietojen muodosta

Merkistönä käytetään UTF-8:aa.

Ajanhetket siirretään ISO 8601 -mukaisena merkkijonona muodossa yyyy-MM-dd'T'HH:mm:ss.SSSXXX, eli esimerkiksi:

    2016-02-01T00:00:00.000+02:00

### Julkisten asiakirjojen hakurajapinta

Kaikissa kyselyissä on oltava mukana GET-parametri organization, jonka arvo on organisaation tunnus. Esimerkiksi:

    GET /public-api/documents/123456789?organization=186-R

#### GET /public-api/documents/{fileId}

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus
----------|--------|------------|-------
organization | String | kyllä | asiakirjan omistajaorganisaatio

##### Vastaukset

Onnistunut kysely palauttaa asiakirjan sisällön vastauksen rungossa. Otsakkeet ovat muotoa:

    Content-Type: application/pdf
    Content-Disposition: attachment;filename=alkuperäinen_tiedostonimi.pdf

Virhetilanteessa HTTP-statuskoodi on virheen mukainen ja vastauksen runko sisältää lisätietoa virheestä text/plain-muodossa.

#### GET /public-api/documents/{fileId}/preview

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus
----------|--------|------------|-------
organization | String | kyllä | asiakirjan omistajaorganisaatio

##### Vastaukset

Onnistunut kysely palauttaa JPG-muotoisen asiakirjan esikatselukuvan vastauksen rungossa. Otsakkeet ovat muotoa:

    Content-Type: image/jpg

Virhetilanteessa HTTP-statuskoodi on virheen mukainen ja vastauksen runko sisältää lisätietoa virheestä text/plain-muodossa.


#### GET /public-api/documents

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus | Oletus
----------|--------|------------|--------|--------
organization | String | kyllä | asiakirjan omistajaorganisaatio, voi esiintyä monta kertaa, jos halutaan tuloksia useista organisaatioista |
from | int | ei | monenko tuloksen yli hypätään vastauksessa | 0
limit | int | ei | montako tulosta korkeintaan palautetaan | 1000
modified-since | date-time | ei | ajanhetki, jonka jälkeen muokattuja asiakirjoja palautetaan | nyt - 24h

##### Vastaukset

Onnistunut kysely palauttaa hakutulokset JSON-muodossa.

JSON-vastaussanoma sisältää päätasolla aina yhden objektin, jonka rakenne on seuraava:

Kenttä        | Tietotyyppi     | Kuvaus
--------------|-----------------|--------------------------------------------------------------------------
meta          | Object          | tietoja kyselystä
meta.count    | int             | hakutulosten lukumäärä vastauksessa
meta.from     | int             | yli hypättyjen tulosten lukumäärä
meta.limit    | int             | käytetty tulosten määrän rajaus
meta.moreResultsAvailable | boolean | onko enemmän tuloksia saatavilla
results       | Array           | lista hakutuloksia

Yksittäisen hakutulosobjektin rakenne:

Kenttä        | Tietotyyppi      | Kuvaus
--------------|------------------|--------------------------------------------------------------------------
contentType   | String           | hakutuloksen asiakirjan MIME-tyyppi
fileId        | String           | hakutuloksen asiakirjan id, käytetään latausrajapinnassa
modified      | String, iso 8601 | asiakirjan viimeisin muokkausajankohta
organization  | String           | asiakirjan omistajaorganisaatio
source        | String           | asiakirjan arkistoon siirtänyt järjestelmä
metadata      | Object           | muut asiakirjan metatiedot

Metadata-objektin rakenne:

Kenttä               | Tietotyyppi      | Kuvaus
---------------------|------------------|--------------------------------------------------------------------------
address              | String           | katuosoite (katu + numero)
applicants           | Array[String]    | hakijat, hankkeeseen ryhtyvät, lista merkkijonoja muodossa "sukunimi etunimi"
applicationId        | String           | hankkeen tunnus Lupapisteessä
arkistoija           | Object           | arkistoijan tiedot: {"firstName": etunimi, "lastName": sukunimi, "username": käyttäjätunnus}
arkistointipvm       | String, iso 8601 | arkistointihetken aikaleima
buildingIds          | Array[String]    | lista kunnan sisäisiä rakennustunnuksia
contents             | String           | asiakirjan sisällön tarkempi kuvaus
deleted              | String, iso 8601 | aikaleima, jos asiakirja on merkitty poistetuksi arkistosta
henkilotiedot        | String           | arvojoukko: sisaltaa, sisaltaa-arkaluontoisia, ei-sisalla
history              | Object           | historiatiedot mahdollisista asiakirjan metatietojen muutoksista arkistoinnin jälkeen
julkisuusluokka      | String           | arvojoukko: julkinen, salainen, osittain-salassapidettava
kasittelija          | Object           | hakemuksen käsittelijä: {"firstName": etunimi, "lastName": sukunimi, "username": käyttäjätunnus}
kayttajaryhma        | String           | arvojoukko: viranomaisryhma, lausunnonantajaryhma
kayttajaryhmakuvaus  | String           | arvojoukko: muokkausoikeus, lukuoikeus
kayttotarkoitukset   | Array[String]    | rakennuksen käyttötarkoitukset, ks. esim. https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/usage_types.cljc
kieli                | String           | asiakirjan kieli, ISO 639-1 (esim. fi)
kuntalupatunnukset   | Array[String]    | lista kunnan sisäisiä lupatunnuksia
kylanimi             | String           | kylän / kaupunginosan nimi
kylanumero           | String           | kylän / kaupunginosan numero
location-etrs-tm35fin | Array[Number]   | asiakirjaan liittyvät koordinaatit ETRS-TM35FIN-koordinaatistossa, järjestyksessä x (itä) y (pohjoinen)
location-wgs84.type  | String           | koordinaatin tyyppi, tällä hetkellä aina "point"
location-wgs84.coordinates | Array[Number] | asiakirjaan liittyvät koordinaatit WGS84-koordinaatistossa, järjestyksessä x (itä) y (pohjoinen)
lupapvm              | String, iso 8601 | luvan lainvoimaisuuspäivämäärä
municipality         | String           | kuntanumero
myyntipalvelu        | boolean          | sallitaanko asiakirjan myynti julkisessa myyntipalvelussa
nakyvyys             | String           | asiakirjan näkyvyyden rajoitus, arvojoukko: julkinen, viranomainen, asiakas-ja-viranomainen
nationalBuildingIds  | Array[String]    | lista kansallisia VTJ-PRT-rakennustunnuksia
operations           | Array[String]    | lista asiakirjaan liittyviä lupapisteen toimenpiteitä (hanketyyppejä)
paatoksentekija      | String           | asiakirjaan liittyvän päätöksen antaja
paatospvm            | String, iso 8601 | asiakirjaan liittyvän päätöksen antoajankohta
postinumero          | String           | postinumero
propertyId           | String           | kiinteistötunnus
sailytysaika         | Object           | arkistoinnin tiedot
sailytysaika.arkistointi | String       | säilyttämisen luonne, arvojoukko: ikuisesti, määräajan, toistaiseksi
sailytysaika.pituus  | Number           | säilytysajan pituus vuosina mikäli määräajan
sailytysaika.retention-period-end | String, iso 8601 | säilytyksen päättymisajankohta, mikäli määräajan
sailytysaika.laskentaperuste | String   | säilytyksen päättymisajankohta, mikäli toistaiseksi, arvojoukko: rakennuksen_purkamispäivä, vakuuksien_voimassaoloaika
sailytysaika.perustelu | String         | valitun säilyttämisen juridinen perustelu
salassapitoaika      | Number           | salassapitoaika vuosina salassapidettävälle asiakirjalle
salassapitoperuste   | String           | salassapidon laillinen perustelu salassapidettävälle asiakirjalle
scale                | String           | piirustuksen mittakaava
security-period-end  | String, iso 8601 | salassapidon päättymisajankohta salassapidettävälle asiakirjalle
size                 | String           | piirustuksen paperikoko
suojaustaso          | String           | suojaustaso salassapidettävälle asiakirjalle, arvojoukko: ei-luokiteltu, suojaustaso4, suojaustaso3, suojaustaso2, suojaustaso1
suunnittelijat       | Array[String]    | lista suunnittelijoiden nimiä
tiedostonimi         | String           | asiakirjan alkuperäinen tiedostonimi
tila                 | String           | asiakirjan tila, "arkistoitu"
tosFunction          | Object           | tiedonohjaussuunnitelman tehtäväluokka
tosFunction.code     | String           | tehtäväluokan numero, tasot eroteltu välilyönnillä, esim. "10 03 00 01"
tosFunction.name     | String           | tehtäväluokan nimi
turvallisuusluokka   | String           | turvallisuusluokka salassapidettävälle asiakirjalle, arvojoukko: ei-turvallisuusluokkaluokiteltu,  turvallisuusluokka4, turvallisuusluokka3, turvallisuusluokka2, turvallisuusluokka1
type                 | String           | asiakirjan tyyppikoodi Lupapisteessä
versio               | String           | asiakirjan version Lupapisteessä

Kenttiä, jotka on määritelty valinnaiseksi arkistoon lataamisen yhteydessä, ei löydy hakutuloksesta, jos niitä ei ole latausvaiheessa käytetty.

Mikäli asiakirja liittyy tiettyyn rakennukseen, ovat asiakirjaan liitetyt metatiedot sopivin ja saatavilla olevin osin
tämän rakennuksen mukaisesti. Hankkeeseen yleisesti liittyvät asiakirjat saavat metatiedoikseen kaikki hankkeen tiedot,
siis esimerkiksi kaikki rakennusnumerot ja kiinteistön keskikoordinaatit.

Esimerkkivastaus:

```json
{
    "meta": {
        "count": 1,
        "from": 0,
        "limit": 1,
        "moreResultsAvailable": true
    },
    "results": [
        {
            "contentType": "application/pdf",
            "fileId": "570f6a1129aa5a6544225d53",
            "metadata": {
                "address": "Högmontie 7",
                "applicants": ["Sibbo Sonja"],
                "applicationId": "LP-753-2016-90025",
                "arkistoija": {
                    "firstName": "Sonja",
                    "lastName": "Sibbo",
                    "username": "sonja"
                },
                "arkistointipvm": "2016-05-19T16:00:26.132Z",
                "buildingIds": [],
                "henkilotiedot": "sisaltaa",
                "julkisuusluokka": "julkinen",
                "kasittelija": {
                    "firstName": "Sonja",
                    "lastName": "Sibbo",
                    "username": "sonja"
                },
                "kayttotarkoitukset": [
                    "011 yhden asunnon talot"
                ],
                "kieli": "fi",
                "kuntalupatunnukset": [
                    "moi"
                ],
                "location-etrs-tm35fin": [
                    402147.911,
                    6701426.462
                ],
                "location-wgs84": {
                    "coordinates": [
                        25.22202,
                        60.43723
                    ],
                    "type": "point"
                },
                "lupapvm": "2016-04-13T21:00:00.000Z",
                "municipality": "753",
                "myyntipalvelu": true,
                "nakyvyys": "julkinen",
                "nationalBuildingIds": [],
                "operations": [
                    "pientalo"
                ],
                "paatoksentekija": "Pekka Päättäjä",
                "paatospvm": "2016-04-13T21:00:00.000Z",
                "propertyId": "75342600110012",
                "sailytysaika": {
                    "arkistointi": "ikuisesti",
                    "perustelu": "AL 11665"
                },
                "suunnittelijat": [],
                "tiedostonimi": "emojia-PDFA.pdf",
                "tila": "arkistoitu",
                "tosFunction": {
                    "code": "10 03 00 01",
                    "name": "Rakennuslupamenettely"
                },
                "type": "paapiirustus.julkisivupiirustus",
                "versio": "0.2"
            },
            "modified": "2016-05-19T16:00:26.226Z",
            "organization": "753-R",
            "source": "lupapiste"
        }
    ]
}
```

### Kaikkien asiakirjojen hakurajapinta

Kaikissa kyselyissä on oltava mukana GET-parametri organization, jonka arvo on organisaation tunnus. Esimerkiksi:

    GET /documents/123456789?organization=186-R

#### GET /documents/{fileId}

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus
----------|--------|------------|-------
organization | String | kyllä | asiakirjan omistajaorganisaatio

##### Vastaukset

Onnistunut kysely palauttaa asiakirjan sisällön vastauksen rungossa. Otsakkeet ovat muotoa:

    Content-Type: application/pdf
    Content-Disposition: attachment;filename=alkuperäinen_tiedostonimi.pdf

Virhetilanteessa HTTP-statuskoodi on virheen mukainen ja vastauksen runko sisältää lisätietoa virheestä text/plain-muodossa.

#### GET /documents/{fileId}/preview

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus
----------|--------|------------|-------
organization | String | kyllä | asiakirjan omistajaorganisaatio

##### Vastaukset

Onnistunut kysely palauttaa JPG-muotoisen asiakirjan esikatselukuvan vastauksen rungossa. Otsakkeet ovat muotoa:

    Content-Type: image/jpg

Virhetilanteessa HTTP-statuskoodi on virheen mukainen ja vastauksen runko sisältää lisätietoa virheestä text/plain-muodossa.


#### GET /documents

Hakee kaikista organisaation arkistoiduista asiakirjoista annettujen hakuparametrien perusteella.
Hakusanat tokenisoidaan automaattisesti välilyöntien kohdalta, eli esimerkiksi haku

    address=alvar aallon katu

hakisi osoitteita, joissa esiinty *alvar* tai *aallon* tai *katu*. Mikäli halutaan hakea välilyöntejä sisältävällä
lausekkeella, käytetään lainausmerkkejä:

    address="alvar aallon katu"

hakee osoitteet, jotka sisältävät sanat *alvar aallon katu* peräkkäin.

Sama haku- tai rajauskenttä voi myös esiintyä parametrina usemman kerran-

Päivämääräkenttiin voidaan tehdä alku- tai loppupäivämäärähaku käyttäen hakutermissä prefixiä päivämäärän edellä. Tuettuja
ovat *gt* (suurempi kuin), *gte* (suurempi tai yhtä suuri), *lt* (pienempi kuin) ja *lte* (pienempi tai yhtä suuri kuin). Esim:

    gte:2016-02-01T00:00:00.000+02:00


##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus | Oletus
----------|--------|------------|--------|--------
organization | String | kyllä | asiakirjan omistajaorganisaatio, voi esiintyä monta kertaa, jos halutaan tuloksia useista organisaatioista |
search-from | int | ei | monenko tuloksen yli hypätään vastauksessa | 0
search-limit | int | ei | montako tulosta korkeintaan palautetaan | 30
all | String | ei | kohdistaa haun kaikkiin alla listattuihin kenttiin

##### Hakukentät, voidaan käyttää parametrina kyselyssä

Mikäli parametrina annetaan useita hakukenttiä, haku palauttaa tulokset, joissa ainakin yksi kenttä täsmää. Haku tehdään
ns. wildcardina, eli haku täsmää kun tuloskenttä sisältää osanaan hakulausekkeen.

Parametri | Tyyppi |  Kuvaus 
----------|--------|---------
address              | String           | katuosoite (katu + numero)
applicants           | String           | hakijat, hankkeeseen ryhtyvät, "sukunimi etunimi"
applicationId        | String           | hankkeen tunnus Lupapisteessä
arkistoija.firstName | String           | arkistoijan tiedot
arkistoija.lastName  | String           | arkistoijan tiedot
arkistoija.username  | String           | arkistoijan tiedot
arkistointipvm       | String, iso 8601 | arkistointihetken aikaleima
buildingIds          | String           | kunnan sisäinen rakennustunnus
contents             | String           | asiakirjan sisällön tarkempi kuvaus
foremen              | String           | työnjohtajan nimi
kasittelija.firstName| String           | hakemuksen käsittelijän tiedot
kasittelija.lastName | String           | hakemuksen käsittelijän tiedot
kasittelija.username | String           | hakemuksen käsittelijän tiedot
kuntalupatunnukset   | String           | kuntalupatunnus
kylanimi             | String           | kylän / kaupunginosan nimi
kylanumero           | String           | kylän / kaupunginosan numero
lupapvm              | String, iso 8601 | luvan lainvoimaisuuspäivämäärä
municipality         | String           | kuntanumero
nationalBuildingIds  | String           | kansallinen VTJ-PRT-tunnus
paatoksentekija      | String           | asiakirjaan liittyvän päätöksen antaja
postinumero          | String           | postinumero
projectDescription   | String           | hankkeen kuvausteksti
propertyId           | String           | kiinteistötunnus
scale                | String           | piirustuksen mittakaava
size                 | String           | piirustuksen paperikoko
suunnittelijat       | String           | suunnittelijan nimi
tiedostonimi         | String           | asiakirjan alkuperäinen tiedostonimi
tosFunction.code     | String           | tehtäväluokan numero, tasot eroteltu välilyönnillä, esim. "10 03 00 01"
tosFunction.name     | String           | tehtäväluokan nimi
tyomaasta-vastaava   | String           | työmaasta vastaavan nimi

##### Rajoittavat hakukentät, voidaan käyttää parametrina kyselyssä

Nämä kentät rajaavat tulosjoukkoa. Tuloksena palautetaan siis vain osumat, jotka täsmäävät kaikkiin annettuihin 
hakuehtoihin. Sen lisäksi tuloksen pitää täsmätä ainakin yhteen yllä mainittuun hakukenttään, jos all-parametri tai
kenttäkohtainen parametri on annettu.

Parametri | Tyyppi |  Kuvaus 
----------|--------|---------
closed               | String, iso 8601 | hankkeen valmistumispäivämäärä
kayttotarkoitukset   | String    | rakennuksen käyttötarkoitus, ks. esim. https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/usage_types.cljc
operations           | String           | asiakirjaan liittyvä lupapisteen toimenpide (hanketyyppi), ks. alla
paatospvm            | String, iso 8601 | asiakirjaan liittyvän päätöksen antoajankohta
shape                | String    | karttarajaus, polygoni jonka sisälle asiakirjan pistekoordinaatin pitää osua. Koordinaatit WGS84-koordinaatistossa, järjestyksessä x (itä) y (pohjoinen), muodossa x,y;x,y;x,y. Ensimmäisen ja viimeisen koordinaatin pitää olla samat. Parametri voi esiintyä useamman kerran, jo polygoneja halutaan luoda useita.
type                 | String           | asiakirjan tyyppikoodi Lupapisteessä, esim. "paapiirustus.asemapiirros"

##### Vastaukset

Onnistunut kysely palauttaa hakutulokset JSON-muodossa. Vastauksen rakenne on samanlainen kuin yllä on kuvattu public-apille.

#### GET /documents/by-modification-date

Sopii kaikkien asiakirjojen noutamiseen julkisuusasetuksista riippumatta. Mikäli asiakirjoja muokataan Onkalossa,
niiden muokkauspäivämäärä (modification) päivittyy, ja ne ilmaantuvat tuloksiin tuoreina.

Suositeltu käyttötapa on hakea esimerkiksi päivittäin asiakirjat joita muokattu edellisen hakukerran jälkeen, eli
siirtää `modified-since` aikaleimaa joka päivä vuorokaudella / edellisen haun ajankohtaan.

Myös arkistossa poistetuksi merkityt asiakirjat näkyvät tämän rajapinnan kautta muuttuneina. Tällöin asiakirjan
metatiedoissa on aikaleima kentässä `deleted`.

##### Parametrit

Parametri | Tyyppi | Pakollinen | Kuvaus | Oletus
----------|--------|------------|--------|--------
organization | String | kyllä | asiakirjan omistajaorganisaatio, voi esiintyä monta kertaa, jos halutaan tuloksia useista organisaatioista |
from | int | ei | monenko tuloksen yli hypätään vastauksessa | 0
limit | int | ei | montako tulosta korkeintaan palautetaan | 1000
modified-since | date-time | ei | ajanhetki, jonka jälkeen muokattuja asiakirjoja palautetaan | nyt - 24h
modified-before | date-time | ei | ajanhetki, jota ennen muokattuja asiakirjoja palautetaan | nykyhetki

##### Vastaukset

Onnistunut kysely palauttaa tulokset JSON-muodossa. Vastauksen rakenne on samanlainen kuin yllä on kuvattu 
public-apille /public-api/documents.

Tulokset palautetaan alkaen viimeisimpänä muokatusta.

### Asiakirjojen lisäysrajapinta

#### PUT /documents/{fileId}

Lisää asiakirjan arkistoon annetulla id:llä.

##### Parametrit

Parametri        | Tyyppi  | Pakollinen | Kuvaus
-----------------|---------|------------|-------
overwrite        | boolean | ei         | jos true, lisätään asiakirjasta uusi versio, jos id on jo olemassa
useTosMetadata   | boolean | ei         | jos true, haetaan tiedonohjausmetatiedot (SÄHKE2) organisaation julkaistusta tiedonohjaussuunnitelmasta

##### Pyynnön runko

Pyynnön rungon (body) tulee olla multipart-tyyppinen, jossa ensimmäinen osa on nimeltään "metadata",
sisältäen asiakirjan metatiedot UTF-8-merkistöisenä JSON-muotoisena objektina. Content-Typen on oltava
application/json.

Toisen osan on oltava nimeltään "file" ja sisällöltään asiakirjan varsinainen binäärisisältö. Content-Typen on vastattava
asiakirjan todellista MIME-tyyppiä, esim. application/pdf.

Metadata-objektissa voi olla seuraavia tietoja, joista osa on valinnaisia:

Kenttä               | Tietotyyppi      | Pakollinen |  Kuvaus
---------------------|------------------|------------|--------------------------------------------------------------------------
address              | String           | kyllä      | katuosoite (katu + numero)
applicants           | Array[String]    | kyllä      | hakijat, hankkeeseen ryhtyvät, lista merkkijonoja muodossa "sukunimi etunimi"
applicationId        | String           | ei         | hankkeen tunnus Lupapisteessä
arkistoija           | Object           | ei         | arkistoijan tiedot: {"firstName": etunimi, "lastName": sukunimi, "username": käyttäjätunnus}
buildingIds          | Array[String]    | ei         | lista kunnan sisäisiä rakennustunnuksia
contents             | String           | ei         | asiakirjan sisällön tarkempi kuvaus
henkilotiedot        | String           | ei ★       | arvojoukko: sisaltaa, sisaltaa-arkaluontoisia, ei-sisalla
julkisuusluokka      | String           | ei ★       | arvojoukko: julkinen, salainen, osittain-salassapidettava
kasittelija          | Object           | ei         | hakemuksen käsittelijä: {"firstName": etunimi, "lastName": sukunimi, "username": käyttäjätunnus}
kayttajaryhma        | String           | ei         | arvojoukko: viranomaisryhma, lausunnonantajaryhma
kayttajaryhmakuvaus  | String           | ei         | arvojoukko: muokkausoikeus, lukuoikeus
kayttotarkoitukset   | Array[String]    | kyllä      | rakennuksen käyttötarkoitukset, ks. esim. https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/usage_types.cljc
kieli                | String           | kyllä      | asiakirjan kieli, ISO 639-1 (esim. fi)
kuntalupatunnukset   | Array[String]    | kyllä      | lista kunnan sisäisiä lupatunnuksia
kylanimi             | String           | ei         | kylän / kaupunginosan nimi
kylanumero           | String           | ei         | kylän / kaupunginosan numero
location-etrs-tm35fin | Array[Number]   | ei         | asiakirjaan liittyvät koordinaatit ETRS-TM35FIN-koordinaatistossa, järjestyksessä x (itä) y (pohjoinen)
location-wgs84       | Object           | ei         | WGS84-koordinaatit
location-wgs84.type  | String           | ei         | koordinaatin tyyppi, tällä hetkellä aina "point"
location-wgs84.coordinates | Array[Number] | ei      | asiakirjaan liittyvät koordinaatit WGS84-koordinaatistossa, järjestyksessä x (itä) y (pohjoinen)
lupapvm              | String, iso 8601 | kyllä      | luvan lainvoimaisuuspäivämäärä
municipality         | String           | kyllä      | kuntanumero
myyntipalvelu        | boolean          | ei ★       | sallitaanko asiakirjan myynti julkisessa myyntipalvelussa
nakyvyys             | String           | ei ★       | asiakirjan näkyvyyden rajoitus, arvojoukko: julkinen, viranomainen, asiakas-ja-viranomainen
nationalBuildingIds  | Array[String]    | ei         | lista kansallisia VTJ-PRT-rakennustunnuksia
operations           | Array[String]    | kyllä      | lista asiakirjaan liittyviä Lupapisteen toimenpiteitä (hanketyyppejä), ks. [sallitut arvot](#sallitut-toimenpiteet-operations)
organization         | String           | kyllä      | asiakirjan omistajaorganisaatio
paatoksentekija      | String           | ei         | asiakirjaan liittyvän päätöksen antaja
paatospvm            | String, iso 8601 | kyllä      | asiakirjaan liittyvän päätöksen antoajankohta
postinumero          | String           | ei         | postinumero
projectDescription   | String           | ei         | hankkeen kuvausteksti, esim. "Rakennetaan kaksikerroksinen, puuverhoiltu omakotitalo ja talousrakennus sekä maalämpökaivo"
propertyId           | String           | ei         | kiinteistötunnus
sailytysaika         | Object           | ei ★       | arkistoinnin tiedot
sailytysaika.arkistointi | String       | ei ★       | säilyttämisen luonne, arvojoukko: ikuisesti, määräajan, toistaiseksi
sailytysaika.pituus  | Number           | ei         | säilytysajan pituus vuosina mikäli määräajan
sailytysaika.retention-period-end | String, iso 8601 | ei | säilytyksen päättymisajankohta, mikäli määräajan
sailytysaika.laskentaperuste | String   | ei         | säilytyksen päättymisajankohta, mikäli toistaiseksi, arvojoukko: rakennuksen_purkamispäivä, vakuuksien_voimassaoloaika
sailytysaika.perustelu | String         | ei ★       | valitun säilyttämisen juridinen perustelu
salassapitoaika      | Number           | ei         | salassapitoaika vuosina salassapidettävälle asiakirjalle
salassapitoperuste   | String           | ei         | salassapidon laillinen perustelu salassapidettävälle asiakirjalle
scale                | String           | ei         | piirustuksen mittakaava
security-period-end  | String, iso 8601 | ei         | salassapidon päättymisajankohta salassapidettävälle asiakirjalle
size                 | String           | ei         | piirustuksen paperikoko
suojaustaso          | String           | ei         | suojaustaso salassapidettävälle asiakirjalle, arvojoukko: ei-luokiteltu, suojaustaso4, suojaustaso3, suojaustaso2, suojaustaso1
suunnittelijat       | Array[String]    | ei         | lista suunnittelijoiden nimiä
tiedostonimi         | String           | kyllä      | asiakirjan alkuperäinen tiedostonimi
tosFunction          | Object           | kyllä      | tiedonohjaussuunnitelman tehtäväluokka
tosFunction.code     | String           | kyllä      | tehtäväluokan numero, tasot eroteltu välilyönnillä, esim. "10 03 00 01"
tosFunction.name     | String           | kyllä      | tehtäväluokan nimi
turvallisuusluokka   | String           | ei         | turvallisuusluokka salassapidettävälle asiakirjalle, arvojoukko: ei-turvallisuusluokkaluokiteltu,  turvallisuusluokka4, turvallisuusluokka3, turvallisuusluokka2, turvallisuusluokka1
type                 | String           | kyllä      | asiakirjan tyyppikoodi Lupapisteessä, esim. "paapiirustus.asemapiirros"
versio               | String           | kyllä      | asiakirjan versio Lupapisteessä

★ ei pakollinen, mikäli kutsussa on annettu parametrina useTosMetadata = true, ja organisaatio on julkaissut tiedonohjaussuunnitelman tarvittavine tietoineen

##### Vastaukset

Vastauksen rungon tyyppi on text/plain. Mahdolliset vastaukset

HTTP-statuskoodi | Syy
-----------------| ---------------------------------
200              | Lisäys onnistui
400              | Pyyntö oli virheellinen, lisätietoja vastauksen rungossa JSON-muodossa
401              | Pääsy estetty, api-avaimet virheelliset tai puuttuvat
403              | Ei oikeutta lisätä tiedostoa organisaatiolle
409              | Asiakirjan id on jo käytössä ja uuden version lisäystä ei pyydetty
422              | Pyynnön toteuttaminen vaati tiedonohjaussuunnitelman käyttöä, mutta organisaatio ei ole julkaissut suunnitelmaa, siitä ei löytynyt asiakirjatyypin tietoja tai asiakirjatyyppiä ei ole asetettu arkistoitavaksi. Lisätietoja rungossa.
500              | Sisäinen virhe, asiakirjan arkistointi epäonnistui

#### Sallitut toimenpiteet (operations)

Alla on listattu operations-kentän sallitut arvot.

Toimenpiteen id | Selitys
----------------| -------------------
kerrostalo-rivitalo | Asuinkerrostalon tai rivitalon rakentaminen
pientalo | Asuinpientalon rakentaminen (enintään kaksiasuntoinen erillispientalo)
vapaa-ajan-asuinrakennus | Vapaa-ajan asunnon tai saunarakennuksen rakentaminen
varasto-tms | Uuden varaston, saunan, autotallin tai muun talousrakennuksen rakentaminen
julkinen-rakennus | Julkisen rakennuksen rakentaminen
teollisuusrakennus | Teollisuus- tai varastorakennuksen rakentaminen
muu-uusi-rakentaminen | Muun kuin edellä mainitun rakennuksen rakentaminen (navetta, liike-, toimisto-, opetus-, päiväkoti-, palvelu-, hoitolaitos- tai muu rakennus)
laajentaminen | Rakennuksen laajentaminen tai korjaaminen
kerrostalo-rt-laaj | Asuinkerrostalon tai rivitalon laajentaminen
pientalo-laaj | Asuinpientalon laajentaminen (enintään kaksiasuntoinen erillispientalo)
vapaa-ajan-rakennus-laaj | Vapaa-ajan asunnon tai saunarakennuksen laajentaminen
talousrakennus-laaj | Varaston, autotallin tai muun talousrakennuksen laajentaminen
teollisuusrakennus-laaj | Teollisuus- tai varastorakennuksen laajentaminen
muu-rakennus-laaj | Liike-, toimisto-, opetus-, päiväkoti-, palvelu-, hoitolaitos- tai muun rakennuksen laajentaminen
perus-tai-kant-rak-muutos | Perustusten ja/tai kantavien rakenteiden muuttaminen
kayttotark-muutos | Koko rakennuksen käyttötarkoituksen muutos tai merkittävä korjaaminen
sisatila-muutos | Rakennuksen sisätilojen muutos (käyttötarkoitus ja/tai muu merkittävä sisämuutos)
julkisivu-muutos | Rakennuksen julkisivujen tai katon muutos, mainoslaitteet tai opasteet rakennuksissa
jakaminen-tai-yhdistaminen | Asuinhuoneiston jakaminen tai yhdistäminen
markatilan-laajentaminen | Märkätilan muuttaminen tai laajentaminen
linjasaneeraus | Rakennuksen linjasaneeraus ja/tai talotekniikan muuttaminen
takka-tai-hormi | Takan ja savuhormin rakentaminen
parveke-tai-terassi | Parvekkeen ja/tai terassin rakentaminen tai lasittaminen
muu-laajentaminen | Muu rakennuksen muutostyö
auto-katos | Autokatoksen tai muun katoksen tai vajan (esim. grillikatos, venevaja) rakentaminen
masto-tms | Maston, piipun, säiliön, laiturin tai vastaavan rakentaminen tai muun erillislaitteen sijoittaminen (esim. markiisi, aurinkokeräin)
mainoslaite | Mainoslaitteiden ja opasteiden sijoittaminen omalle kiinteistölle
aita | Aidan rakentaminen
maalampo | Maalämpökaivon poraaminen tai lämmönkeruuputkiston asentaminen
jatevesi | Rakennuksen jätevesien käsittelyjärjestelmän rakentaminen tai uusiminen
muu-rakentaminen | Muun rakennelman rakentaminen
purkaminen | Rakennuksen purkaminen
kaivuu | Kaivaminen, louhiminen tai maan täyttäminen omalla kiinteistöllä
puun-kaataminen | Puiden kaataminen
tontin-jarjestelymuutos | Tontin ajoliittymän, paikoitusjärjestelyjen tai varastointialueen rakentaminen tai muutos
muu-maisema-toimenpide | Muu maisemaan merkittävästi vaikuttava muutostoimenpide (esim. laaja maisemarakentaminen, katsomo, yleisöteltta, kokoontumisalue, vesirajaan rakennettavat rakennelmat)
tontin-ajoliittyman-muutos | Tontin ajoliittymän muutos
paikoutysjarjestus-muutos | Paikoitusjärjestelyihin liittyvät muutokset
kortteli-yht-alue-muutos | Korttelin yhteisiin alueisiin liittyvä muutos
muu-tontti-tai-kort-muutos | Muu tontin tai korttelialueen muutos
tyonjohtajan-nimeaminen | Työnjohtajan nimeäminen
tyonjohtajan-nimeaminen-v2 | Työnjohtajan nimeäminen
suunnittelijan-nimeaminen | Suunnittelijan nimeäminen tai vaihtaminen, kun lupa on myönnetty
jatkoaika | Lisäajan hakeminen rakentamiselle
aiemmalla-luvalla-hakeminen | Rakentamisen lupa (haettu paperilla)
rak-valm-tyo | Rakentamista valmisteleva työ (puunkaato, maankaivu, louhinta)
aloitusoikeus | Aloittamisoikeuden hakeminen rakennustyölle erillisenä toimenpiteenä
raktyo-aloit-loppuunsaat | Rakennustyön aloittamisen ja/tai loppuunsaattamisen jatkaminen (jatkoajan hakeminen)
ya-kayttolupa-tapahtumat | Tapahtuman järjestäminen
ya-kayttolupa-harrastustoiminnan-jarjestaminen | Harrastustoiminnan järjestäminen
ya-kayttolupa-metsastys | Metsästäminen
ya-kayttolupa-vesistoluvat | Vesistöluvat
ya-kayttolupa-terassit | Terassin sijoittaminen
ya-kayttolupa-kioskit | Kioskin sijoittaminen
ya-kayttolupa-muu-kayttolupa | Muu käyttölupa
ya-kayttolupa-mainostus-ja-viitoitus | Mainoslaitteiden ja opasteviittojen sijoittaminen
ya-kayttolupa-nostotyot | Nostotyöt
ya-kayttolupa-vaihtolavat | Vaihtolavan sijoittaminen
ya-kayttolupa-kattolumien-pudotustyot | Kattolumien pudotustyöt
ya-kayttolupa-muu-liikennealuetyo | Muu liikennealuetyö
ya-kayttolupa-talon-julkisivutyot | Talon julkisivutyöt (rakennustelineet)
ya-kayttolupa-talon-rakennustyot | Talon rakennustyöt (aitaaminen, työmaakopit)
ya-kayttolupa-muu-tyomaakaytto | Muu yleisen alueen työmaakäyttö
ya-katulupa-vesi-ja-viemarityot | Vesi- ja viemäritöiden tekeminen
ya-katulupa-maalampotyot | Maalämpöputkitöiden tekeminen
ya-katulupa-kaukolampotyot | Kaukolämpötöiden tekeminen
ya-katulupa-kaapelityot | Kaapelitöiden tekeminen
ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat | Kiinteistön johto-, kaapeli- ja putkiliityntöjen tekeminen
ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen | Vesi- ja viemärijohtojen sijoittaminen
ya-sijoituslupa-maalampoputkien-sijoittaminen | Maalämpöputkien sijoittaminen
ya-sijoituslupa-kaukolampoputkien-sijoittaminen | Kaukolämpöputkien sijoittaminen
ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen | Sähkö-, data ja muiden kaapelien sijoittaminen
ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen | Rakennuksen tai sen osan sijoittaminen
ya-sijoituslupa-ilmajohtojen-sijoittaminen | Ilmajohtojen sijoittaminen
ya-sijoituslupa-muuntamoiden-sijoittaminen | Muuntamoiden sijoittaminen
ya-sijoituslupa-jatekatoksien-sijoittaminen | Jätekatoksien sijoittaminen
ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen | Leikkipaikan tai koiratarhan sijoittaminen
ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen | Rakennuksen pelastuspaikan sijoittaminen
ya-sijoituslupa-muu-sijoituslupa | Muu sijoituslupa
ya-jatkoaika | Jatkoajan hakeminen
