(ns {{prefix}}.example-app.core-test
  (:require [clojure.test :as test]
            [{{prefix}}.example-app.core :as core]))

(test/deftest greets-person
  (test/is (= "Hello, world!" (core/greet "world"))))
