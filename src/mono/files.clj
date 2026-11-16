(ns mono.files
  (:require [clojure.java.io :as io]
            [mono.config :as config]))

(defn with-workspace-lock [root action]
  (let [file (config/resolve-inside root ".mono/lock")]
    (io/make-parents file)
    ;; Closing the file releases its channel lock, including when the action fails.
    (with-open [handle (java.io.RandomAccessFile. file "rw")]
      (.lock (.getChannel handle))
      (action))))
