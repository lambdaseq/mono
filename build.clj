(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [mono.process :as process]))

(defn test [_]
  (process/run-command "." ["clojure" "-M:test"]))

(defn jar [_]
  (b/delete {:path "target"})
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir "target/classes"})
  (b/jar {:class-dir "target/classes" :jar-file "target/mono.jar"}))
