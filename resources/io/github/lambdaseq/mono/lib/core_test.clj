(ns {{prefix}}.example-lib.core-test
  (:require [clojure.test :as test]
            [{{prefix}}.example-lib.core :as core]))

(test/deftest greets-person
  (test/is (= "Hello, world!" (core/greet "world"))))
