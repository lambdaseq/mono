(ns build
  (:refer-clojure :exclude [test])
  (:require [mono.build :as mono]))

(defn build [options] (mono/run-task :build options))
(defn test [options] (mono/run-task :test options))
(defn clean [options] (mono/run-task :clean options))
(defn deploy [options] (mono/run-task :deploy options))
