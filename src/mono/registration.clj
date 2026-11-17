(ns mono.registration
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.zip :as z])
  (:import [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

(defn find-modules-form [source]
  (let [forms (->> (iterate z/right (z/of-string source))
                   (take-while some?)
                   (filter #(and (= :list (z/tag %))
                                 (= 'def (some-> % z/down z/sexpr))
                                 (= 'modules (some-> % z/down z/right z/sexpr))))
                   vec)]
    (when (> (count forms) 1)
      (throw (ex-info "Keep a single modules definition, or pass :register false." {})))
    (first forms)))

(defn read-modules-map [form]
  (let [value (-> form z/down z/right z/right)
        value (if (= :string (z/tag value)) (z/right value) value)
        modules (when (= :quote (z/tag value)) (z/down value))]
    (when-not (= :map (some-> modules z/tag))
      (throw (ex-info "Automatic registration needs a quoted modules map. Use :register false to register manually." {})))
    modules))

(defn assoc-in-form [form [key & more] value]
  (if-let [child (z/get form key)]
    (if (seq more)
      (if (nil? (z/sexpr child))
        (z/assoc form key (assoc-in {} more value))
        (-> (assoc-in-form child more value) z/up))
      (if (= value (z/sexpr child)) form (z/assoc form key value)))
    (z/assoc form key (if (seq more) (assoc-in {} more value) value))))

(defn create-module [opts directory]
  (let [kind (keyword (:kind opts))
        platform (keyword (:platform opts))]
    (cond-> {:kind kind :dir directory :platform platform
             :lib (symbol (:top opts) (:main opts))}
      (= :app kind) (assoc :main (symbol (str (:namespace opts) ".core")))
      (= :cljs platform) (assoc :compiler (keyword (:compiler opts))))))

(defn validate-module [modules directories id module target]
  (when (contains? modules id)
    (when-not (and (= target (get directories id))
                   (map? (get modules id))
                   (= (dissoc module :dir) (select-keys (get modules id) (keys (dissoc module :dir)))))
      (throw (ex-info "Module ID is already registered with different settings." {:module id :existing (get modules id)}))))
  (doseq [[other-id other] modules
          :when (not= id other-id)]
    (when (or (= (:lib module) (:lib other)) (= target (get directories other-id)))
      (throw (ex-info "Module coordinates or directory are already registered." {:module other-id :existing other}))))
  (merge module (get modules id)))

(defn validate-dev-dependency [deps lib directory existing-directory target]
  (doseq [path [[:aliases] [:aliases :dev] [:aliases :dev :extra-deps]]]
    (let [value (get-in deps path)]
      (when (and (some? value) (not (map? value)))
        (throw (ex-info "Root :aliases, :dev, and :extra-deps must be maps for automatic registration." {:path path})))))
  (when-let [existing (get-in deps [:aliases :dev :extra-deps lib])]
    (when-not (= existing-directory target)
      (throw (ex-info "Root :dev dependency already has different coordinates." {:lib lib :existing existing}))))
  (or (get-in deps [:aliases :dev :extra-deps lib]) {:local/root directory}))

(defn find-workspace! [target]
  (loop [directory (.getParentFile (io/file target))]
    (when directory
      (let [build-file (io/file directory "build.clj")
            deps-file (io/file directory "deps.edn")
            source (when (and (.isFile build-file) (.isFile deps-file)) (slurp build-file))
            form (when source (find-modules-form source))]
        (if form
          {:directory directory :build-file build-file :build-source source :modules (read-modules-map form)
           :deps-file deps-file :deps-source (slurp deps-file)}
          (recur (.getParentFile directory)))))))

(defn prepare-registration! [{:keys [kind target-dir main], :as opts}]
  (when (contains? #{"app" "lib"} kind)
    (when-not (boolean? (get opts :register true))
      (throw (ex-info ":register must be true or false." {:register (:register opts)})))
    (when (get opts :register true)
      (let [target (.getCanonicalFile (io/file target-dir))]
        (when-let [{:keys [directory build-file build-source modules deps-file deps-source]} (find-workspace! target)]
          (let [relative (-> (.toPath directory) (.relativize (.toPath target)) str (str/replace "\\" "/"))
                id (keyword main)
                module (create-module opts relative)
                existing (z/sexpr modules)
                directories (into {} (keep (fn [[id module]]
                                             (when (string? (:dir module))
                                               [id (.getCanonicalFile (io/file directory (:dir module)))]))) existing)
                module (validate-module existing directories id module target)
                deps-form (z/of-string deps-source)
                deps (z/sexpr deps-form)
                _ (when-not (map? deps) (throw (ex-info "Root deps.edn must contain a map." {})))
                dependency-path (get-in deps [:aliases :dev :extra-deps (:lib module) :local/root])
                dependency-directory (when (string? dependency-path)
                                       (.getCanonicalFile (io/file directory dependency-path)))
                dependency (validate-dev-dependency deps (:lib module) relative dependency-directory target)]
            {:module id
             :files [{:path (str build-file) :before build-source
                      :after (-> modules (assoc-in-form [id] module) z/root-string)}
                     {:path (str deps-file) :before deps-source
                      :after (-> deps-form (assoc-in-form [:aliases :dev :extra-deps (:lib module)] dependency) z/root-string)}]}))))))

(defn replace-file! [path content]
  (let [target (.toPath (io/file path))
        temporary (Files/createTempFile (.getParent target) ".mono-registration-" ".tmp" (make-array FileAttribute 0))]
    (try
      (Files/copy target temporary (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/COPY_ATTRIBUTES]))
      (spit (.toFile temporary) content)
      (Files/move temporary target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      (finally (Files/deleteIfExists temporary)))))

(defn register-module! [_ {:keys [registration]}]
  (when registration
    ;; Check both files before writing either, including recovery from a partial write.
    (doseq [{:keys [path before after]} (:files registration)]
      (let [current (slurp path)]
        (when-not (or (= before current) (= after current))
          (throw (ex-info "Workspace changed during generation. Register the module manually or retry." {:path path})))))
    (doseq [{:keys [path after]} (:files registration)]
      (when-not (= after (slurp path)) (replace-file! path after)))
    (println "Registered" (:module registration) "in build.clj and root :dev dependencies.")))
