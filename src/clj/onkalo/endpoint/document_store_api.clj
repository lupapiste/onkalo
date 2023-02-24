(ns onkalo.endpoint.document-store-api
  (:require [compojure.api.exception :as ex]
            [compojure.api.sweet :refer :all]
            [onkalo.component.elastic :refer [searchable-properties limiting-properties]]
            [onkalo.util.preview :as preview]
            [onkalo.metadata.xmp-processor :as xmp]
            [onkalo.routing :as routing]
            [onkalo.storage.document :as document]
            [onkalo.util.auth :as auth]
            [onkalo.util.document-api-util :as dau]
            [onkalo.util.log-api-usage :as lau]
            [ring.util.http-response :refer :all]
            [schema.core :as s])
  (:import [java.io File]))

(defn execute-search [elastic orgs limiting-params search-from search-limit source-fields]
  (try
    (dau/validate-shape-parameters! limiting-params)
    (dau/find-from-elastic elastic
                           orgs
                           limiting-params
                           search-from
                           search-limit
                           :public-only?
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

(defn document-store-api [{:keys [elastic mq topics] {:keys [bucket-suffix]} :storage :as config}]
  (api
    {:swagger
     {:ui   (str routing/api-v2-root "/document-store/api-docs")
      :spec (str routing/api-v2-root "/document-store/api-docs/swagger.json")
      :data {:info                {:title       "Onkalo API for document stores"
                                   :version     "2.0.0"
                                   :description "This API provides endpoints suitable for document store operation.
                                    Only public and resellable document results are returned, and the document
                                    metadata is limited to an appropriate set."}
             :tags                [{:name "document-store", :description "document store endpoints"}]
             :securityDefinitions {:login {:type "basic"}}}}
     :exceptions {:handlers {::ex/response-validation (dau/with-logging ex/response-validation-handler :error)}}}

    (context (str routing/api-v2-root "/document-store/documents") []
      :tags ["document-store"]
      :middleware [(auth/wrap-docstore-auth config)]

      (GET "/search" {docstore-orgs :onkalo/docstore-orgs}
        :query-params [{shape :- [s/Str] nil}
                       {propertyId :- s/Str nil}
                       {search-from :- Long 0}
                       {search-limit :- Long 30}
                       {organization :- (describe [String] "Optionally limit the search to specific organizations") []}]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/HeavyStoreResponse]}
        :summary "Search for documents inside a map area."
        :description "Search for document results inside provided polygons. Shape parameter may be present multiple times
                  for multiple polygons. Returned results will include all metadata available for document store users.
                  Suggested use is to fetch the results the user is interested in using this API."
        (execute-search elastic
                        (if (seq organization) organization docstore-orgs)
                        (merge {}
                               (when shape {:shape-docstore shape})
                               (when propertyId {:propertyId propertyId}))
                        search-from
                        search-limit
                        dau/heavy-response-fields))

      (POST "/search" {docstore-orgs :onkalo/docstore-orgs}
        :body-params [geojson :- {:type        (s/enum "MultiPolygon" "Polygon" "multipolygon" "polygon")
                                  :coordinates [[s/Any]]}
                      {search-from :- Long 0}
                      {search-limit :- Long 30}
                      {organizations :- (describe [String] "Optionally limit the search to specific organizations") []}]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/HeavyStoreResponse]}
        :summary "Search for documents inside a map area."
        :description "Search for document results inside provided polygons. The geojson parameter should contain a
                  valid GeoJSON geometry object, either a Polygon or a MultiPolygon. Returned results will include all
                  metadata available for document store users. Suggested use is to fetch the results the user is
                  interested in using this API."
        (execute-search elastic
                        (if (seq organizations) organizations docstore-orgs)
                        {:geojson-docstore geojson}
                        search-from
                        search-limit
                        dau/heavy-response-fields))

      (GET "/search-location-id" {docstore-orgs :onkalo/docstore-orgs}
        :query-params [shape :- [s/Str]
                       {search-from :- Long 0}
                       {search-limit :- Long 30}
                       {organization :- (describe [String] "Optionally limit the search to specific organizations") []}]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/LightStoreResponse]}
        :summary "Search for documents inside a map area, return only ids and coordinates."
        :description "Search for document results inside provided polygons. Shape parameter may be present multiple times
                  for multiple polygons. Returned results will include only the result coordinates, fileId and organization.
                  Suggested use is to fetch background results to be shown on a larger map area."
        (execute-search elastic
                        (if (seq organization) organization docstore-orgs)
                        {:shape-docstore shape}
                        search-from
                        search-limit
                        dau/light-response-fields))

      (POST "/search-location-id" {docstore-orgs :onkalo/docstore-orgs}
        :body-params [geojson :- {:type        (s/enum "MultiPolygon" "Polygon" "multipolygon" "polygon")
                                   :coordinates [[s/Any]]}
                      {search-from :- Long 0}
                      {search-limit :- Long 30}
                      {organization :- (describe [String] "Optionally limit the search to specific organizations") []}]
        :return {:meta    dau/SearchResponseMetaSchema
                 :results [dau/LightStoreResponse]}
        :summary "Search for documents inside a map area, return only ids and coordinates."
        :description "Search for document results inside provided polygons. Shape parameter may be present multiple times
                  for multiple polygons. Returned results will include only the result coordinates, fileId and organization.
                  Suggested use is to fetch background results to be shown on a larger map area."
        (execute-search elastic
                        (if (seq organization) organization docstore-orgs)
                        {:geojson geojson}
                        search-from
                        search-limit
                        dau/light-response-fields))

      (GET "/:id" {api-user :onkalo/api-user}
        :query-params [organization :- s/Str
                       {externalId :- s/Str ""}]
        :path-params [id :- s/Str]
        :return File
        :produces ["application/octet-stream"]
        :summary "Fetch a file from Onkalo."
        :description "Returns the document corresponding to the given id (= fileId in search results). The documents are
                  in application/pdf or image/tiff format. The format is declared in the Content-Type header of the
                  response. If the id is not found in Onkalo, the response status will be 404."
        (auth/for-public-document
          bucket-suffix organization elastic id
          (fn [elastic-data]
            (let [response (document/get-document config id organization :attachment)
                  content-type (get-in response [:headers "Content-Type"])]
              ; TODO: Remove hard-coded test account from here if not needed
              (when (and (= (:status response) 200) (not= api-user "document_store_test"))
                (lau/log-api-usage mq (:api-usage topics) api-user externalId elastic-data))
              (update response :body xmp/remove-metadata content-type)))))

      (GET "/:id/preview" []
        :query-params [organization :- s/Str
                       {size :- Long 600}]
        :path-params [id :- s/Str]
        :return File
        :produces ["image/jpeg"]
        :summary "Fetch the thumbnail image for a file from Onkalo."
        :description "Returns a thumbnail image corresponding to the given id (= fileId in search results).
                  If the id is not found in Onkalo, the response status will be 404. Previews are disallowed for some document types. For those documents the response status is 403."
        (auth/for-public-document
          bucket-suffix organization elastic id
          (fn [_]
            (preview/get-document-store-preview config id organization size))))

      (GET "/:id/metadata" []
        :query-params [organization :- s/Str]
        :path-params  [id :- s/Str]
        :return dau/HeavyStoreResponse
        :summary "Return the metadata for a given file id."
        :description "Returns the same metadata as in /search results for a given fileId.
                  If the id is not found in Onkalo, the response status will be 404."
        (auth/for-public-document
          bucket-suffix organization elastic id
          (fn [_]
            (document/document-metadata config id organization false dau/heavy-response-fields)))))))
