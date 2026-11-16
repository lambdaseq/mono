(ns {{namespace}}.core
  (:gen-class))

(defn greet [person]
  (str "Hello, " person "!"))

(defn -main [& _]
  (println (greet "world")))
