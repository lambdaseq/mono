(ns {{namespace}}.core
  (:gen-class)
  (:require [{{library-namespace}}.core :as example]))

(defn greet [person]
  (example/greet person))

(defn -main [& _]
  (println (greet "world")))
