# Onkalo HTTP APIs

## Authentication

The APIs can be used with an API key consisting of app id and app key.

Authentication uses HTTP Basic authentication. Each request must contain an Authorization header which value is `Basic <app-id>:<app-key>` where the `<app-id>:<app-key>` part is Base64 encoded.

## General principles about the data

Character set is UTF-8

All datetime fields are transferred as ISO 8601 strings formatted yyyy-MM-dd'T'HH:mm:ss.SSSXXX, e.g.:

    2016-02-01T00:00:00.000+02:00

## Supported data transfer formats

Data transfer format for responses may be specified by providing the `Accept` header in requests. The header contents
should be one of the supported formats: `application/json`, `application/edn`, `application/transit+json` or
`application/transit+msgpack`. If no `Accept` header is present, the default format is `application/json`.
    
## APIs for Document Stores

APIs available for document store users are documented in [Swagger](http://www-qa.lupapiste.fi/onkalo/api/v2/document-store/api-docs/).
Information about the meaning of the different keys in result objects can be found in this document. 

## Search API for public documents

All queries must have an query string parameter `organization` whose value is the id of the organization.
For example:

    GET /public-api/documents/123456789?organization=186-R
    
Multiple organizations can be provided by settings the organization parameter multiple times in the query string. 

#### GET /public-api/documents/{fileId}

##### Parameters

Parameter | Type | Mandatory | Description
----------|--------|------------|-------
organization | String | yes | owner organization of the document

##### Responses

Successful query returns the contents of the document in the body of the response. Response headers are e.g.:

    Content-Type: application/pdf
    Content-Disposition: attachment;filename=original_filename.pdf

If an error occurs, the HTTP status code is set according to nature of the error, and the body of the response will include
details of the error in text/plain format.

#### GET /public-api/documents/{fileId}/preview

##### Parameters

Parameter | Type | Mandatory | Description
----------|--------|------------|-------
organization | String | yes | owner organization of the document

##### Responses

Successful query returns a JPEG preview image of the document in the body of the response. Response headers are:

    Content-Type: image/jpg

If an error occurs, the HTTP status code is set according to nature of the error, and the body of the response will include
details of the error in text/plain format.

#### GET /public-api/documents/{fileId}/exists

Checks for the existance of a `fileId` in the system.

##### Parameters

Parameter | Type | Mandatory | Description
----------|--------|------------|-------
organization | String | yes | owner organization of the document

##### Responses

Successful query returns a JSON response with status `200`:

    {"exists": true}

If the document is not found in the system, a response with status `404` is returned:

    {"error": "Document not found"}
    
And as with the other APIs, if the document does exists but it's not public, a response with status `403` is returned:

    {"error": "Document is not public"}

#### GET /public-api/documents/{fileId}/metadata

Returns the metdata for a single (existing) `fileId` in the system.

##### Parameters

Parameter | Type | Mandatory | Description
----------|--------|------------|-------
organization | String | yes | owner organization of the document

##### Responses

Successful query returns a JSON response with status `200`. The content is a single object containing the
metadata of the document. It is identical to the objects in the `results` as described for the search
APIs below.

Failure responses are the same as for the `/public-api/documents/{fileId}/exists` endpoint.

#### GET /public-api/documents

This query is meant for fetching all documents that have been modified in he index since the time set in
`modified-since`. This would be useful e.g. in polling for changes from an another system. 

##### Parameters

Parameter | Type | Mandatory | Description | Default
----------|--------|------------|--------|--------
organization | String | yes | owner organization of the document, can be present multiple times
from | int | no | how many results to skip from the start | 0
limit | int | no | how many results to return in total | 1000
modified-since | date-time | no | documents modified after this instance will be returned | now - 24h

#### GET /public-api/documents/search

Searches all archived and public / resellable documents of the organizations given as query parameters.
 
The search string is tokenized and all words are used for search if the query includes the parameter:

    tokenize=true

In this case the query:
    
    address=alvar aallon katu

would search for addresses, which contain `*alvar* or *aallon* or *katu*`. Without the `tokenize` parameter the 
resulting query would be `*alvar aallon katu*`, i.e. the words would have to occur in this order 
(note the asterisks symbolising wild cards).

Multiple search phrases may be provided by using quotation marks, e.g.:

    address="alvar aallon katu" "urho kekkosen katu"

would search for results that contain either `*alvar aallon katu*` or `*urho kekkosen katu*`.

The same search or filtering parameter may be included multiple times in the query for different options.

Date fields can be used for "starting from" and "up to" type queries by using a prefix in the search term in front
 of the date. Supported prefixes are:
ovat *gt* (greater than), *gte* (greater or equal than), *lt* (less than) ja *lte* (less or equal than). E.g.:

    gte:2016-02-01T00:00:00.000+02:00


##### Parameters

Parameter | Type | Mandatory | Description | Default
----------|--------|------------|--------|--------
organization | String | yes | owner organization of the document, can be present multiple times
search-from | int | no | how many results to skip from the start | 0
search-limit | int | no | how many results to return in total | 30
all | String | no | all search fields will be queried for the query set in this parameter

##### Search fields, can be used as parameters in a search query

If the query contains multiple search fields, results matching at least one of the fields will be returned. The search
will be a wildcard query, i.e. a field in a document will match if the search string is a substring of the result field.

Address field will use exact match if the search string ends in a word that begins with a number, e.g. `Street 54`.

projectDescription field will use a (analyzed) match query with some fuzziness allowed. 

Parameter | Type |  Description 
----------|--------|---------
address              | String           | street address (street + number)
applicants           | String           | permit applicant
applicationId        | String           | id of the application in Lupapiste
arkistoija.firstName | String           | user details about the archiver
arkistoija.lastName  | String           | user details about the archiver
arkistoija.username  | String           | user details about the archiver
arkistointipvm       | String, iso 8601 | the moment the document was uploaded into the archives
buildingIds          | String           | local building id assigned by the owner organization / municipality
contents             | String           | detailed contents of the document, free text
foremen              | String           | names of the foremen at the construction site (in a single string)
kasittelija.firstName| String           | user details of the handler of the application
kasittelija.lastName | String           | user details of the handler of the application
kasittelija.username | String           | user details of the handler of the application
kuntalupatunnukset   | String           | permit id related to this document, assigned by the owner organization / municipality
kylanimi             | String           | name of the village or borough
kylanumero           | String           | number of the village or borough
lupapvm              | String, iso 8601 | the date the validity permit starts
municipality         | String           | number of the owner municipality
nationalBuildingIds  | String           | Finnish nationally unique VTJ-PRT building id 
paatoksentekija      | String           | name of the person that has given the verdict for this application
postinumero          | String           | postal code of the location related to this document
projectDescription   | String           | description of the building project
propertyId           | String           | Finnish property id of the plot
scale                | String           | scale of the drawing if the document is a drawing (rarely used)
size                 | String           | paper size of the drawing (rarely used)
suunnittelijat       | String           | name of the designer / architect relating to this document
tiedostonimi         | String           | original filename of the document when uploaded into the archives
tosFunction.code     | String           | number of the information management function, e.g. "10 03 00 01"
tosFunction.name     | String           | name of the information management  function
tyomaasta-vastaava   | String           | name of the person in charge of the construction site


##### Limiting / filtering fields, can be used as parameters in a search query

These fields filter the result set. Only results matching all filtering fields given in the search query will be 
returned. In addition the result must match at least one of the search fields described above if such a field is present
in the query of if `all` query parameter is used.

Parameter | Type |  Description 
----------|--------|---------
closed               | String, iso 8601 | the date of completion of the building project
kayttotarkoitukset   | String           | building usage / purpose type, see https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/usage_types.cljc for allowed values
operations           | String           | Lupapiste operation (application type) related to the document, see below for allowed values
paatospvm            | String, iso 8601 | the date when the verdict was given to the application
shape                | String           | map search, a polygon that must contain the coordinate point of the document. Coordinates are in WGS84 system, ordered x (east) y (north), formatted x,y;x,y;x,y. First and last coordinate must be the same to complete the polygon. The parameter can be present multiple times for multiple polygons.
type                 | String           | document type, an id corresponding to values allowed in Lupapiste, e.g. "paapiirustus.asemapiirros". See https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/attachment_types.cljc


#### Responses to search queries

Successful search queries will return results in JSON format.

The JSON-response contains always one top level object, which has the following contents:

Field        | Type     | Description
--------------|-----------------|--------------------------------------------------------------------------
meta          | Object          | metadata about the query
meta.count    | int             | number of results in the response
meta.from     | int             | number of skipped results
meta.limit    | int             | the limit of results set in the query
meta.moreResultsAvailable | boolean | true if there would be more results available
results       | Array           | the list of results

A single result object has the following structure:

Field        | Type      | Description
--------------|------------------|--------------------------------------------------------------------------
contentType   | String           | MIME type of the document linked to this result
fileId        | String           | id of the result document, used for downloading the actual document
modified      | String, iso 8601 | last modification time of the document
organization  | String           | owner organization of the document
source        | String           | the system that uploaded the document into the archive
metadata      | Object           | other metadata of the document

The metadata object has the following contents:

Field               | Type      | Description
---------------------|------------------|--------------------------------------------------------------------------
address              | String           | street address (street + number)
applicants           | Array[String]    | permit applicants, list of strings in format "lastname firstname"
applicationId        | String           | id of the application in Lupapiste
arkistoija           | Object           | user details of the archiver: {"firstName": "etunimi", "lastName": "sukunimi", "username": "user1234"}
arkistointipvm       | String, iso 8601 | the moment the document was uploaded into the archives
buildingIds          | Array[String]    | list of local building ids assigned by the owner organization / municipality
contents             | String           | detailed contents of the document, free text
foremen              | String           | names of the foremen at the construction site (in a single string)
henkilotiedot        | String           | does the document include personal info, allowed values: sisaltaa, sisaltaa-arkaluontoisia, ei-sisalla (corresponds to includes, includes especially sensitive info, does not includ
julkisuusluokka      | String           | publicity of the document, allowed values: julkinen, salainen, osittain-salassapidettava (corresponds to public, secret, partly secret)
kasittelija          | Object           | handler of the application: {"firstName": "etunimi", "lastName": "sukunimi", "username": "user1234"}
kayttajaryhma        | String           | user group, allowed values: viranomaisryhma, lausunnonantajaryhma (corresponds to authorities and statement givers)
kayttajaryhmakuvaus  | String           | user group privileges, allowed values: muokkausoikeus, lukuoikeus (corresponds to modify and read-only)
kayttotarkoitukset   | Array[String]    | building usage / purpose types, see https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/usage_types.cljc for allowed values
kieli                | String           | language of the document, ISO 639-1 (e.g. "fi")
kuntalupatunnukset   | Array[String]    | list of permit ids related to this document, assigned by the owner organization / municipality
kylanimi             | String           | name of the village or borough
kylanumero           | String           | number of the village or borough
location-etrs-tm35fin | Array[Number]   | coordinates relating to this document in ETRS-TM35FIN system, in order x (east) y (north)
location-wgs84.type  | String           | type of the coordinate, currently always "point"
location-wgs84.coordinates | Array[Number] | coordinates relating to this document in WGS84 system, in order x (east) y (north)
lupapvm              | String, iso 8601 | the date the validity permit starts
municipality         | String           | number of the owner municipality
myyntipalvelu        | boolean          | true if the document can be sold in a public web store
nakyvyys             | String           | limitation of the document visiblity, allowed values: julkinen, viranomainen, asiakas-ja-viranomainen (corresponding to public, authority, applicant-and-authority)
nationalBuildingIds  | Array[String]    | list of Finnish nationally unique VTJ-PRT building ids 
operations           | Array[String]    | list of Lupapiste operations (application types) related to this document, see below for allowed values
paatoksentekija      | String           | name of the person that has given the verdict for this application
paatospvm            | String, iso 8601 | date when the verdict was given
postinumero          | String           | postal code of the location related to this document
projectDescription   | String           | description of the construction project, free text 
propertyId           | String           | Finnish property id of the plot
sailytysaika         | Object           | this object contains the archive retention settings
sailytysaika.arkistointi | String       | type of the retention, allowed values: ikuisesti, määräajan, toistaiseksi (corresponding to permanently, for a limited time, until further notice)
sailytysaika.pituus  | Number           | length of the retention in years if for a limited time
sailytysaika.retention-period-end | String, iso 8601 | date when the retention of the document ends
sailytysaika.laskentaperuste | String   | the event triggering the retention end, if until further notice. Allowed values: rakennuksen_purkamispäivä, vakuuksien_voimassaoloaika (demolition or end of guarantees)
sailytysaika.perustelu | String         | legal basis / justification for the selected retenetion time
salassapitoaika      | Number           | length of the security period in years
salassapitoperuste   | String           | legal basis for the secrecy of the document
scale                | String           | scale of the drawing if the document is a drawing (rarely used)
security-period-end  | String, iso 8601 | date when the security period ends and the document will become public
size                 | String           | paper size of the drawing (rarely used)
suojaustaso          | String           | protection level of the document, allowed values from lowest to highest protection: ei-luokiteltu, suojaustaso4, suojaustaso3, suojaustaso2, suojaustaso1
suunnittelijat       | Array[String]    | list of designer / architect names relating to this document
tiedostonimi         | String           | original filename of the document when uploaded into the archives
tila                 | String           | state of document, always "arkistoitu" (archived)
tosFunction          | Object           | the information management function selected for this document 
tosFunction.code     | String           | number of the function, e.g. "10 03 00 01"
tosFunction.name     | String           | name of the function
turvallisuusluokka   | String           | security class for a secret document, allowed values: ei-turvallisuusluokkaluokiteltu,  turvallisuusluokka4, turvallisuusluokka3, turvallisuusluokka2, turvallisuusluokka1 (corresponding to not classified ... top secret)
tyomaasta-vastaava   | String           | name of the person in charge of the construction site
type                 | String           | document type, an id corresponding to values allowed in Lupapiste, e.g. "paapiirustus.asemapiirros". See https://github.com/lupapiste/commons/blob/master/src/lupapiste_commons/attachment_types.cljc 
versio               | String           | version of the document in Lupapiste when it was archived

Any fields that are optional in the upload data are not found in the result if they were not used in the upload.  

If a document result relates to a certain building, the metadata of the result corresponds to this building where
applicable and possible. Documents that relate to the application as whole have all data of the application in the
result metadata, e.g. all building numbers and the central coordinates of the property.

Example response:

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

#### Allowed operations

Below are the allowed values for the operations field

Operation id | Description
----------------| -------------------
aiemmalla-luvalla-hakeminen | Construction permit (applied for on paper)
aita | Construction of a fence
aloitusoikeus | Applying for the right to start construction work as a separate procedure
asuinrakennus | Construction of residential buildings
auto-katos | Construction of a carport or some other shelter or shed (e.g. BBQ shelter, boat house)
jakaminen-tai-yhdistaminen | Splitting or merging a residential property
jatevesi | Construction or refurbishment of a waste water treatment system
jatkoaika | Applying for extra time for construction
julkinen-rakennus | Construction of a public building
julkisivu-muutos | Modifying a facade or a roof, advertising boards or signs on buildings
kaivuu | Digging, mining or filling in ground on one's own property
kayttotark-muutos | Modification to the intended use or a major renovation of an entire building
kerrostalo-rivitalo | Construction of a residential block of flats  or terraced house
kerrostalo-rt-laaj | Extension of a residential block of flats or row house
kortteli-yht-alue-muutos | Modification related to the common areas of a block
laajentaminen | Extension or renovation of a building
linjasaneeraus | Renovation of a building and/or modification of building technology
maalampo | Drilling a geothermal well or installing a heat transfer pipeline
mainoslaite | Placement of advertising boards and signs on one's own property
markatilan-laajentaminen | Modification or extension of a wet room
masto-tms | Construction of a mast, barrel, tank, platform or an equivalent, or the placement of some other external component (e.g. awning, solar thermal collector)
muu-laajentaminen | Other building alteration work
muu-maisema-toimenpide | Other modification that significantly affects a landscape (e.g. extensive landscape construction, a stadium, an audience tent, assembly area, structures to be built at a water line)
muu-rakennus-laaj | Extension of a business, office, educational establishment, day care or service premises, nursing home or other building
muu-rakentaminen | Construction of some other structure
muu-tontti-tai-kort-muutos | Other modifications to a plot or block area
muu-uusi-rakentaminen | Construction of a building not mentioned above (barn, office, retail outlet, educational establishment, day care or service premises, nursing home or some other building)
paikoutysjarjestus-muutos | Changes related to parking arrangements
parveke-tai-terassi | Construction or glazing of a balcony and/or terrace
perus-tai-kant-rak-muutos | Modification of foundations and/or load-bearing structures
pientalo | Construction of a detached house (detached house with no more than two dwellings)
pientalo-laaj | Extension of a detached house (detached house with no more than two dwellings)
purkaminen | Demolition of a building
puun-kaataminen | Felling trees
rak-valm-tyo | Preparatory work for construction (tree felling, digging, excavation)
raktyo-aloit-loppuunsaat | Extension for start and/or completion of construction work (applying for extra time)
sisatila-muutos | Modification to the interior of a building (intended use and/or other significant change to the interior)
suunnittelijan-nimeaminen | Naming or replacing a designer, once a permit has been granted
takka-tai-hormi | Fireplace and chimney flue construction
talousrakennus-laaj | Extension of a warehouse, garage or other outbuilding
teollisuusrakennus | Construction of an industrial or storage building
teollisuusrakennus-laaj | Extension of an industrial or storage building
tontin-ajoliittyman-muutos | Modification to the road access of a plot
tontin-jarjestelymuutos | Construction or modification of a plot's road access, parking arrangements, or storage area
tyonjohtajan-nimeaminen | Appointment of a supervisor
tyonjohtajan-nimeaminen-v2 | Appointment of a supervisor
vapaa-ajan-asuinrakennus | Construction of a leisure home or sauna building
vapaa-ajan-rakennus-laaj | Extension of a leisure home or sauna building
varasto-tms | Construction of a new warehouse, sauna, garage or other outbuilding
ya-jatkoaika | Applying for additional time
ya-katulupa-kaapelityot | Performing cable work
ya-katulupa-kaukolampotyot | Performing district heating work
ya-katulupa-kiinteiston-johto-kaapeli-ja-putkiliitynnat | Installing a property's wire, cable and pipeline connections
ya-katulupa-maalampotyot | Performing geothermal piping work
ya-katulupa-muu-liikennealuetyo | Other traffic area work
ya-katulupa-vesi-ja-viemarityot | Performing water and sewer work
ya-kayttolupa-harrastustoiminnan-jarjestaminen | Organisation of recreational activities
ya-kayttolupa-kattolumien-pudotustyot | Work dropping snow from rooftops
ya-kayttolupa-kioskit | Kiosk placement
ya-kayttolupa-mainostus-ja-viitoitus | Placement of advertising devices and signposts
ya-kayttolupa-metsastys | Hunting
ya-kayttolupa-muu-kayttolupa | Other operating permit
ya-kayttolupa-muu-tyomaakaytto | Other heavy use of a public area
ya-kayttolupa-nostotyot | Lifting work
ya-kayttolupa-talon-julkisivutyot | A building's façade work (scaffolding)
ya-kayttolupa-talon-rakennustyot | Construction work of a building (fencing, worksite cubicles)
ya-kayttolupa-tapahtumat | Organising an event
ya-kayttolupa-terassit | Placement of a terrace
ya-kayttolupa-vaihtolavat | Demountable platform placement
ya-kayttolupa-vesistoluvat | Water permits
ya-sijoituslupa-ilmajohtojen-sijoittaminen | Placement of overhead wires
ya-sijoituslupa-jatekatoksien-sijoittaminen | Placement of waste disposal shelters
ya-sijoituslupa-kaukolampoputkien-sijoittaminen | Placement of district heat pipes
ya-sijoituslupa-leikkipaikan-tai-koiratarhan-sijoittaminen | Placement of a playground or dog park
ya-sijoituslupa-maalampoputkien-sijoittaminen | Placement of geothermal pipelines
ya-sijoituslupa-muu-sijoituslupa | Other placement permit
ya-sijoituslupa-muuntamoiden-sijoittaminen | Placement of transformer substations
ya-sijoituslupa-rakennuksen-pelastuspaikan-sijoittaminen | Placement of a building's rescue site
ya-sijoituslupa-rakennuksen-tai-sen-osan-sijoittaminen | Placement of a building or a part thereof
ya-sijoituslupa-sahko-data-ja-muiden-kaapelien-sijoittaminen | Placement of electrical, data and other cables
ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen | Placement of water and sewer piping
ya-kayttolupa-muu-liikennealuetyo | Other traffic area work
poikkeamis | Applying for a deviation
