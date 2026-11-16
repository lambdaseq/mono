(ns mono.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn fail [message data]
  (throw (ex-info message data)))

(defn validate-name [value]
  (when-not (and (string? value) (re-matches #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*" value))
    (fail "Names must use lowercase letters, digits, and single hyphens." {:name value}))
  value)

(defn validate-prefix [value]
  (when-not (and (string? value)
                 (every? #(re-matches #"[a-z][a-z0-9]*(?:-[a-z0-9]+)*" %) (str/split value #"\." -1)))
    (fail "Namespace prefixes must contain lowercase dot-separated names." {:namespace value}))
  value)

(defn validate-workspace-template [value]
  (when-not (#{:mixed :libs} value)
    (fail "Workspace template must be mixed or libs." {:template value}))
  value)

(defn validate-platform [value]
  (when-not (#{:clj :cljs} value)
    (fail "Platform must be clj or cljs." {:platform value}))
  value)

(defn resolve-inside [root relative]
  (when-not (and (string? relative) (not (.isAbsolute (io/file relative))))
    (fail "Expected a relative workspace path." {:path relative}))
  (let [base (.toPath (.getCanonicalFile (io/file root)))
        path (.toPath (.getCanonicalFile (io/file root relative)))]
    (when (or (= base path) (not (.startsWith path base)))
      (fail "Path escapes the workspace." {:path relative}))
    (.toFile path)))

(defn find-root [start]
  (loop [dir (.getCanonicalFile (io/file start))]
    (cond
      (.isFile (io/file dir "monorepo.edn")) dir
      (.getParentFile dir) (recur (.getParentFile dir))
      :else nil)))

(defn read-edn [file]
  (try
    (with-open [reader (java.io.PushbackReader. (io/reader file))]
      (let [value (edn/read {:eof ::eof} reader)]
        (when-not (= ::eof (edn/read {:eof ::eof} reader))
          (fail "Expected one EDN form." {:file (str file)}))
        value))
    (catch Exception e
      (throw (ex-info (str "Cannot read " file ": " (.getMessage e)) {:file (str file)} e)))))

(defn validate-module [{:keys [path kind platform compiler namespace main lib version deploy] :as module}]
  (when-not (and (map? module) (#{:app :lib} kind)
                 (string? path) (string? namespace) (symbol? lib)
                 (string? version) (re-matches #"[A-Za-z0-9][A-Za-z0-9._+-]*" version))
    (fail "Invalid module settings." {:module module}))
  (validate-platform platform)
  (let [[directory module-name & more] (str/split path #"/" -1)]
    (when (or (seq more) (not= directory (if (= kind :app) "apps" "libs")))
      (fail "Modules must be directly beneath apps/ or libs/." {:path path}))
    (validate-name module-name))
  (validate-prefix namespace)
  (when-not (qualified-symbol? lib)
    (fail "Library coordinates must be qualified symbols." {:lib lib}))
  (validate-name (name lib))
  (when (and (= kind :app) (not (symbol? main)))
    (fail "Apps require a :main namespace symbol." {:path path}))
  (when (and (= platform :clj) compiler)
    (fail "CLJ modules cannot specify a CLJS compiler." {:path path}))
  (when (and (= platform :cljs) (not (#{:shadow :cljs} compiler)))
    (fail "CLJS compiler must be :shadow or :cljs." {:path path}))
  (when (and deploy (not (and (vector? deploy) (seq deploy)
                              (every? #(and (string? %) (not (str/blank? %))) deploy))))
    (fail "Deployment must be a nonempty argument vector." {:path path}))
  module)

(defn validate-manifest [{:keys [schema-version namespace modules runtime] :as manifest}]
  (when-not (and (= 1 schema-version) (vector? modules) (map? runtime))
    (fail "Expected schema-version 1, a modules vector, and runtime coordinates." {}))
  (validate-prefix namespace)
  (doseq [module modules] (validate-module module))
  (when-not (= (count modules) (count (set (map :path modules))))
    (fail "Module paths must be unique." {}))
  manifest)

(defn load-workspace [start]
  (let [root (or (find-root start) (fail "No monorepo.edn found above this directory." {}))
        manifest (validate-manifest (read-edn (io/file root "monorepo.edn")))]
    (doseq [{:keys [path]} (:modules manifest)]
      (let [dir (resolve-inside root path)]
        (when-not (.isFile (io/file dir "deps.edn"))
          (fail "Registered module has no deps.edn." {:module path}))))
    {:root root :manifest manifest}))

(defn select-modules [manifest requested]
  (let [paths (cond (nil? requested) [] (string? requested) [requested]
                    (sequential? requested) requested
                    :else (fail ":module must be a path or a sequence of paths." {}))
        known (set (map :path (:modules manifest)))
        unknown (remove known paths)]
    (when (seq unknown) (fail "Unknown modules." {:modules (vec unknown)}))
    (filterv #(or (empty? paths) (contains? (set paths) (:path %))) (:modules manifest))))
