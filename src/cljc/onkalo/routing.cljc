(ns onkalo.routing)

(def
  ^{:doc "Programmatic access to the root URL path.
          Be sure to check out URLs in resources if you want to change the root path."}
  root
  "/onkalo")

(def ui-root (str root "/ui"))

(def api-v2-root (str root "/api/v2"))

(defn path [name]
  (str root name))
