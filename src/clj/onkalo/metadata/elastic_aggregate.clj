(ns onkalo.metadata.elastic-aggregate
  (:require [onkalo.boundary.elastic-document-api :as esd]
            [plumbing.core :as p]
            [clj-time.format :as tf]
            [onkalo.metadata.elastic-api :as ea]))

(defn do-aggregate-query
  ([elastic sources]
   (do-aggregate-query elastic sources nil nil))
  ([elastic sources aggregations]
   (do-aggregate-query elastic sources aggregations nil))
  ([elastic sources aggregations after]
   (let [{:keys [after_key buckets]} (-> (esd/search elastic
                                                     {:aggs {:results (p/assoc-when
                                                                        {:composite (p/assoc-when
                                                                                      {:size    1000
                                                                                       :sources sources}
                                                                                      :after after)}
                                                                        :aggregations aggregations)}
                                                      :size 0})
                                         :body
                                         :aggregations
                                         :results)]
     (lazy-cat
       buckets
       (when after_key
         (do-aggregate-query elastic sources aggregations after_key))))))

(def formatter (tf/formatter "dd.MM.yyyy"))

(defn format-date [date-str]
  (->> (tf/parse ea/date-parser date-str)
       (tf/unparse formatter)))

(defn documents-per-organization [elastic]
  (->> (do-aggregate-query elastic
                           [{:organization {:terms {:field "organization"}}}]
                           {:earliest {:min {:field "metadata.arkistointipvm"}},
                            :latest   {:max {:field "metadata.arkistointipvm"}}})
       (map (fn [{:keys [key doc_count earliest latest]}]
              {:organization (-> key :organization)
               :count        doc_count
               :earliest     (-> earliest :value_as_string format-date)
               :latest       (-> latest :value_as_string format-date)}))))

(defn lupapiste-apps-per-organization [elastic]
  (->> (do-aggregate-query elastic
                           [{:organization {:terms {:field "organization"}}}
                            {:permitId {:terms {:field "metadata.applicationId"}}}])
       (filter #(->> % :key :permitId (re-matches #"^LP-.*")))
       (reduce (fn [acc {:keys [key]}]
                 (update acc (:organization key) #(inc (or % 0))))
               {})))

(defn municipal-ids-per-organization [elastic]
  (->> (do-aggregate-query elastic
                           [{:organization {:terms {:field "organization"}}}
                            {:kl {:terms {:field "metadata.kuntalupatunnukset"}}}])
       (reduce (fn [acc {:keys [key]}]
                 (update acc (:organization key) #(inc (or % 0))))
               {})))
