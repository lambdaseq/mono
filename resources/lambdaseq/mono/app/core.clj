(ns {{prefix}}.example-app.core
  (:gen-class)
  (:require [{{prefix}}.example-lib.core :as shared]))

(defn greet [person]
  (shared/greet person))

(defn -main [& _]
  (println (greet "world")))
