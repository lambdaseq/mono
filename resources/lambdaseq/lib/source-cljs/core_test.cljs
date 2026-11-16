(ns {{namespace}}.core-test
  (:require [cljs.test :as test :include-macros true]
            [{{namespace}}.core :as core]))

(test/deftest greets-person
  (test/is (= "Hello, world!" (core/greet "world"))))
