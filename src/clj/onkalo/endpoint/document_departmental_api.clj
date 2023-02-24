(ns onkalo.endpoint.document-departmental-api
  "Endpoints for the docstore department terminal (departmental)."
  (:require [compojure.api.exception :as ex]
            [compojure.api.sweet :refer :all]
            [onkalo.routing :as routing]
            [onkalo.storage.document :as document]
            [onkalo.util.auth :as auth]
            [onkalo.util.document-api-util :as dau]
            [onkalo.util.lupapiste-api :as la]
            [ring.util.http-response :refer :all]
            [schema.core :as s])
  (:import [java.io File]))

(defn execute-search
  [{:keys [lupapiste-api]} elastic organizations limiting-params search-from search-limit source-fields]
  (try
    (dau/validate-shape-parameters! limiting-params)
    (dau/find-from-elastic elastic
                           organizations
                           (merge limiting-params
                                  {:allowed-document-types
                                   (map (juxt identity
                                              (partial la/allowed-departmental-document-types-for-organization lupapiste-api))
                                        organizations)})
                           search-from
                           search-limit
                           nil
                           source-fields
                           false)
    (catch Exception e
      (let [{:keys [type] :as ed} (ex-data e)
            error-response (case type
                             ::s/error {:error (str "Shape must be a single closed polygon, with the coordinates given like "
                                                    "y1,x1;y2,x2;y3,x3;y4,x4;y1,x1 in WGS84 system. Multiple shape parameters may be used "
                                                    "for multiple different polygons. Error: " (.getMessage e))}
                             :onkalo.metadata.elastic-api/elastic-error {:error      "Elasticsearch search failed"
                                                                         :error-data ed}
                             {:error (.getMessage e)})]
        {:status 400
         :body   error-response}))))

(def Access [(s/one String "Cannot be empty") String])

(defn document-departmental-api [{:keys [elastic] {:keys [bucket-suffix]} :storage :as config}]
  (api
    {:swagger
     {:ui   (str routing/api-v2-root "/document-departmental/api-docs")
      :spec (str routing/api-v2-root "/document-departmental/api-docs/swagger.json")
      :data {:info                {:title       "Onkalo API for document departmentals"
                                   :version     "2.0.0"
                                   :description "This API provides endpoints suitable for document
                                   departmental operation. The document access is defined by access-full
                                   parameter that defines which organizations' documents can be accessed (and
                                   fully, without any limitations)"}
             :tags                [{:name        "document-departmental"
                                    :description "document departmental endpoints"}]
             :securityDefinitions {:login {:type "basic"}}}}
     :exceptions {:handlers {::ex/response-validation (dau/with-logging ex/response-validation-handler :error)}}}

    (context (str routing/api-v2-root "/document-departmental/documents") []
      :tags ["document-departmental"]
      :middleware [(auth/wrap-docdepartmental-auth config)]

      (GET "/search" {}
        :query-params [{shape :- [s/Str] nil}
                       {propertyId :- s/Str nil}
                       {applicationId :- s/Str nil}
                       {municipalPermitId :- s/Str nil}
                       {search-from :- Long 0}
                       {search-limit :- Long 30}
                       access-full :- Access]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/HeavyStoreResponse]}
        :summary "Search for documents inside a map area."
        :description "Search for document results inside provided polygons. Shape parameter may be present
                  multiple times for multiple polygons. Returned results will include all metadata available
                  for document store users.  Suggested use is to fetch the results the user is interested in
                  using this API."
        (execute-search config
                        elastic
                        access-full
                        (cond-> {}
                          shape (assoc :shape shape)
                          propertyId (assoc :propertyId propertyId)
                          applicationId (assoc :applicationId (dau/->upper-case applicationId))
                          municipalPermitId (assoc :kuntalupatunnukset (dau/->upper-case municipalPermitId)))
                        search-from
                        search-limit
                        dau/heavy-response-fields))

      (POST "/search" {}
        :body-params [geojson :- {:type        (s/enum "MultiPolygon" "Polygon" "multipolygon" "polygon")
                                  :coordinates [[s/Any]]}
                      {search-from :- Long 0}
                      {search-limit :- Long 30}
                      access-full :- Access]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/HeavyStoreResponse]}
        :summary "Search for documents inside a map area."
        :description "Search for document results inside provided polygons. The geojson parameter should
                  contain a valid GeoJSON geometry object, either a Polygon or a MultiPolygon. Returned
                  results will include all metadata available for document store users. Suggested use is to
                  fetch the results the user is interested in using this API."
        (execute-search config
                        elastic
                        access-full
                        {:geojson geojson}
                        search-from
                        search-limit
                        dau/heavy-response-fields))

      (GET "/search-location-id" {}
        :query-params [shape :- [s/Str]
                       {search-from :- Long 0}
                       {search-limit :- Long 30}
                       access-full :- Access]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/LightStoreResponse]}
        :summary "Search for documents inside a map area, return only ids and coordinates."
        :description "Search for document results inside provided polygons. Shape parameter may be present
                  multiple times for multiple polygons. Returned results will include only the result
                  coordinates, fileId and organization.  Suggested use is to fetch background results to be
                  shown on a larger map area."
        (execute-search config
                        elastic
                        access-full
                        {:shape shape}
                        search-from
                        search-limit
                        dau/light-response-fields))

      (POST "/search-location-id" {}
        :body-params [geojson :- {:type        (s/enum "MultiPolygon" "Polygon" "multipolygon" "polygon")
                                  :coordinates [[s/Any]]}
                      {search-from :- Long 0}
                      {search-limit :- Long 30}
                      access-full :- Access]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/LightStoreResponse]}
        :summary "Search for documents inside a map area, return only ids and coordinates."
        :description "Search for document results inside provided polygons. Shape parameter may be present
                  multiple times for multiple polygons. Returned results will include only the result
                  coordinates, fileId and organization.  Suggested use is to fetch background results to be
                  shown on a larger map area."

        (execute-search config
                        elastic
                        access-full
                        {:geojson geojson}
                        search-from
                        search-limit
                        dau/light-response-fields))

      (GET "/:id" []
        :query-params [organization :- s/Str
                       access-full :- Access]
        :path-params [id :- s/Str]
        :return File
        :produces ["application/octet-stream"]
        :summary "Fetch a file from Onkalo."
        :description "Returns the document corresponding to the given id (= fileId in search results). The
                  documents are in application/pdf or image/tiff format. The format is declared in the
                  Content-Type header of the response. If the id is not found in Onkalo, the response status
                  will be 404."
        (auth/for-docdepartmental-document
          config bucket-suffix elastic {:organization organization
                                        :id           id
                                        :access-full  access-full}
          #(document/get-document config id organization :attachment)))

      (GET "/:id/preview" []
        :query-params [organization :- s/Str
                       access-full :- Access]
        :path-params [id :- s/Str]
        :return File
        :produces ["image/jpeg"]
        :summary "Fetch the thumbnail image for a file from Onkalo."
        :description "Returns a thumbnail image corresponding to the given id (= fileId in search results).
                  If the id is not found in Onkalo, the response status will be 404."
        (auth/for-docdepartmental-document
          config bucket-suffix elastic {:organization organization
                                        :id           id
                                        :access-full  access-full}
          #(document/get-preview config id organization)))

      (GET "/:id/metadata" []
        :query-params [organization :- s/Str
                       access-full :- Access]
        :path-params [id :- s/Str]
        :return dau/HeavyStoreResponse
        :summary "Return the metadata for a given file id."
        :description "Returns the same metadata as in /search results for a given fileId.
                  If the id is not found in Onkalo, the response status will be 404."
        (auth/for-docdepartmental-document
          config bucket-suffix elastic {:organization organization
                                        :id           id
                                        :access-full  access-full}
          #(document/document-metadata config id organization false dau/heavy-response-fields))))))
