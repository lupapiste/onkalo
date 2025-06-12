(ns user
  (:require [clojure.repl :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.tools.namespace.repl :refer [refresh disable-reload!]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [com.stuartsierra.component :as component]
            [meta-merge.core :refer [meta-merge]]
            [reloaded.repl :refer [system init start stop go reset]]
            [onkalo.system :as system]
            #_[ajax.core]))

;(disable-reload! 'ajax.core)

(reloaded.repl/set-init! #(system/new-system (meta-merge (edn/read-string (slurp "config.edn"))
                                                         (if (.exists (io/file "config-local.edn"))
                                                           (edn/read-string (slurp "config-local.edn"))
                                                           {}))))
