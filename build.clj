(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]))

(defn test "Test the deps-new templates." [opts]
  (let [result (b/process {:command-args ["clojure" "-M:test"]})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Template tests failed." result))))
  opts)

(defn ci "Verify templates and generated workspaces." [opts]
  (test opts)
  (let [result (b/process {:command-args ["clojure" "-M:integration"]})]
    (when-not (zero? (:exit result))
      (throw (ex-info "Generated workspace checks failed." result))))
  opts)
