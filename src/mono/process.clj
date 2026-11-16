(ns mono.process
  (:require [clojure.java.io :as io]
            [mono.config :as config]))

(defn start-process [directory arguments]
  (-> (ProcessBuilder. ^java.util.List (mapv str arguments))
      (.directory (io/file directory))
      (.inheritIO)
      (.start)))

(defn run-command [directory arguments]
  (let [process (start-process directory arguments)
        hook (Thread. ^Runnable (fn [] (.destroy process)))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (let [exit (.waitFor process)]
        (when-not (zero? exit)
          (config/fail (str "Command failed with exit " exit ".")
                       {:exit exit :command arguments :directory (str directory)})))
      (finally (.removeShutdownHook (Runtime/getRuntime) hook)))))
