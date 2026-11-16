(ns {{namespace}}.core)

(defn greet [person]
  (str "Hello, " person "!"))

(defn ^:export init []
  (set! (.-textContent (.getElementById js/document "app")) (greet "world")))
