(ns {{namespace}}.core-test
  (:require [clojure.test :as test]
            [{{namespace}}.core :as core]))

(test/deftest greets-person
  (test/is (= "Hello, world!" (core/greet "world"))))
