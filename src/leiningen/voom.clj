(ns leiningen.voom
  (:require [clojure.java.shell :as shell]
            [clojure.string :as s]
            [clojure.pprint :refer [pprint print-table]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.data.fressian :as fress]
            [clojure.data.codec.base64 :as b64]
            [clojure.core.logic.pldb :as pldb]
            [clojure.core.logic :as l]
            [clojure.walk :as walk]
            [leiningen.core.project :as project]
            [leiningen.core.main :as lmain]
            [leiningen.voom.long-sha :as sha :only [mk]]
            [leiningen.voom.pldb :as vdb]
            [leiningen.voom.shabam :refer [shabam-new shabam-contains? shabam-add
                                           sha-ancestors sha-successors]]
            [lonocloud.synthread :as ->]
            [org.satta.glob :refer [glob]]
            [robert.hooke :as hooke])
  (:import [clojure.lang #_IPersistentVector Seqable]
           [java.util Date]
           [java.lang.reflect Array]
           [java.io File FileInputStream FileOutputStream OutputStreamWriter
            Closeable]
           [java.util.logging Logger Handler Level]
           [org.sonatype.aether.util.version GenericVersionScheme]
           [org.sonatype.aether.transfer ArtifactNotFoundException]))

(set! *warn-on-reflection* true)


;;=== FIFO and shell functions ===

(def ^:dynamic ^FileInputStream *ififo* nil)
(def ^:dynamic ^OutputStreamWriter *ofifo* nil)
(def ^:dynamic ^File *pwd* nil)

(defn sh
  [& cmdline]
  (apply shell/sh (map #(if (= File (class %))
                          (.getPath ^File %)
                          %)
                       cmdline)))

(defn fcmd
  [cmd]
  (if (and *ififo* *ofifo*)
    (do
      (binding [*out* *ofifo*]
        (println cmd)
        (.flush *out*))
      (let [b (byte-array 5)]
        (while (= -1 (.read *ififo* b))
          (Thread/sleep 1))
        (-> b String. s/trim Integer/parseInt)))
    -1))

(defn git
  [{:keys [^File gitdir ok-statuses sh-opts]
    :or {ok-statuses #{0}}} & subcmd]
  ;; We won't handle bare repos or displaced worktrees
  (let [cmd-args (if (nil? gitdir)
                   []
                   [:dir gitdir])
        all-args (concat subcmd cmd-args sh-opts)
        ;; _ (prn :calling (doall (cons 'git all-args)))
        {:keys [exit] :as rtn} (apply sh "git" all-args)
        rtn (assoc rtn :bool (if (zero? (:exit rtn))
                               true
                               false))]
    (when-not (ok-statuses exit)
      (throw (ex-info "git error" (assoc rtn :git all-args))))
    (assoc rtn :lines (when (not= "\n" (:out rtn))
                        (re-seq #"(?m)^.*$" (:out rtn))))))

;; === git sha-based versions ===

(def timestamp-fmt "yyyyMMdd_hhmmss")

(defn formatted-timestamp
  [^String fmt t]
  (.format (doto (java.text.SimpleDateFormat. fmt java.util.Locale/US)
             (.setTimeZone (java.util.SimpleTimeZone. 0 "GMT")))
           t))

(defn get-voom-version
  [path & {:keys [long-sha? gitdir]}]
  (let [shafmt (if long-sha? "%H" "%h")
        fmt (str "--pretty=" shafmt ",%cd")
        {:keys [out]} (git {:gitdir gitdir} "log" "-1" fmt path)
        [sha, datestr] (-> out s/trim (s/split #"," 2))
        ctime (Date. ^String datestr)]
    {:ctime ctime :sha sha}))

(defn format-voom-ver
  [gver]
  (let [{:keys [version ctime sha]} gver]
    (assert version   (str "format-voom-ver requires :version " (pr-str gver)))
    (assert ctime (str "format-voom-ver requires :ctime " (pr-str gver)))
    (assert sha   (str "format-voom-ver requires :sha " (pr-str gver)))
    (str (s/replace version #"-SNAPSHOT" "")
         "-" (formatted-timestamp timestamp-fmt ctime) "-g" sha)))

(defn update-proj-version
  [project long-sha?]
  (let [gver (-> project :root (get-voom-version :long-sha? long-sha?))
        upfn #(format-voom-ver (assoc gver :version %))
        nproj (update-in project [:version] upfn)
        nmeta (update-in (meta project) [:without-profiles :version] upfn)
        nnproj (with-meta nproj nmeta)]
    nnproj))

(defn ^:info-subtask ver-parse
  "Parses jar-path-like-string or artifact-version-string to find ctime and sha.
   Can handle cases in the range of:
     1.2.3-20120219223112-abc123f
     1.2.3-20120219_223112-gabc123f
     foo-1.2.3-20120219223112-gabc123f
     foo-1.2.3-20120219_223112-abc123f
     /path/to/foo-1.2.3-20120219_223112-gabc123f19ea8d29b13.jar"
  [ver-str]
  (let [[_ ctime sha] (re-matches #".*-?([0-9]{8}_?[0-9]{6})-g?([a-f0-9]{4,40})(?:\.jar)?$" ver-str)]
    (when (and ctime sha)
      {:ctime (s/replace ctime #"_" "") :sha sha})))

(defn dirty-wc?
  [path]
  (let [{:keys [out err exit]} (git {} "status" "--short" path)]
    (not (empty? out))))


;; === manage VOOM_REPOS directory ===

(def task-dir ".voom-box")

(def voom-repos (or (System/getenv "VOOM_REPOS")
                    (str (System/getProperty "user.home")
                         (str "/.voom-repos"))))

(defn remotes
  "For a given .git directory, returns a map like:
  {:origin {:push  \"git@github.com:abrooks/lein-voom.git\",
            :fetch \"git@github.com:abrooks/lein-voom.git\"}}"
  [gitdir]
  (reduce
   (fn [m line]
     (let [[_ remote url direction] (re-matches #"(.*)\t(.*)\s+\(([^)]*)\)$" line)]
       (assoc-in m [(keyword remote) (keyword direction)] url)))
   {}
   (:lines (git {:gitdir gitdir} "remote" "-v"))))

(defn ensure-repo
  "Makes sure the task-dir contains the specified repo, cloning it if not found."
  [^String repo]
  (let [repo-dir (->> repo .getBytes ^bytes b64/encode String. (io/file voom-repos))]
    (if (.exists repo-dir)
      true
      (:bool (git {} "clone" repo repo-dir)))))

(defn ancestor?
  [gitdir old new]
  (:bool (git {:gitdir gitdir :ok-statuses #{0 1}}
                   "merge-base" "--is-ancestor" old new)))

(defn dirty-repo?
  [gitdir]
  (git {:gitdir gitdir} "fetch")
  (let [g {:gitdir gitdir}
        dirty (:lines (git g "status" "--short"))
        stashes (:lines (git g "stash" "list"))
        remotes (->> (git g "ls-remote")
                     :lines
                     next
                     (map #(first (s/split % #"\t" 2)))
                     set)
        local-refs (filter (complement #{"refs/stash"})
                           (:lines (git g "rev-parse" "--symbolic" "--all")))
        local-shas (:lines (apply git g "rev-parse" local-refs))
        locals (zipmap local-refs local-shas)
        unpushed-local-refs (map key (filter #(not (remotes (val %))) locals))
        local-commits (filter #(not (some (partial ancestor? gitdir %)
                                          remotes))
                              unpushed-local-refs)]
    (if (empty? (concat dirty stashes local-commits))
      false
      {:dirty-files dirty
       :stashes stashes
       :local-commits local-commits})))

(defn with-log-level [level f]
  (let [handlers (doall (.getHandlers (Logger/getLogger "")))
        old-levels (doall (map #(.getLevel ^Handler %) handlers))
        _ (doseq [h handlers] (.setLevel ^Handler h level))
        result (f)]
    (dorun (map #(.setLevel ^Handler %1 %2) handlers old-levels))
    result))

(defn find-project-files
  [^File d]
  (let [{:keys [lines]} (git {:gitdir d} "ls-files" "project.clj" "**/project.clj")]
    (map #(str d "/" %) lines)))

(defn contains-sha? [d sha]
  (->>
   (git {:gitdir d :ok-statuses #{0 1}} "rev-parse" "--verify" "--quiet" sha)
   :exit
   zero?))

(defn locate-sha
  [dirs sha]
  (seq (for [d dirs
             :when (contains-sha? d sha)]
         d)))

(defn all-repos-dirs []
  (glob (str (s/replace voom-repos #"/$" "") "/*")))

(defn fetch-all
  [dirs]
  (doseq [^File d dirs]
    (print (str "Fetching: " (.getPath d) "\n"))
    (flush)
    (git {:gitdir d} "fetch")))

(defn find-project
  [pgroup pname candidate]
  (prn "find-project" pgroup pname candidate)
  (for [p (find-project-files candidate)
        :let [{:keys [group name] :as prj} (project/read p)]
        :when (and (= group pgroup)
                   (= name pname))]
    prj))

(defn find-matching-projects
  "[project {groupId name ctime sha}] [project coordinate-string]"
  [repos-dir pspec]
  (prn "find-matching-projects" repos-dir pspec)
  ;; TODO: find correct sha and project.clj more efficiently and with
  ;; less ambiguity. (only consider projects changed at the given sha)
  (let [{:keys [sha artifactId groupId]} pspec
        dirs (all-repos-dirs)
        sha-candidates (locate-sha dirs sha)
        sha-candidates (or sha-candidates
                           (do (fetch-all dirs)
                               (locate-sha dirs sha)))]
    (prn "sha-candidates" sha-candidates)
    (mapcat (fn [c]
              (git {:gitdir c} "checkout" sha)
              (find-project groupId artifactId c))
            sha-candidates)))

(defn install-versioned-artifact
  [proot]
  (println "Calling recursive build-deps on:" proot)
  (print (:out (sh "lein" "voom" "build-deps" :dir proot)))
  (let [install-cmd ["lein" "voom" "wrap" "install" :dir proot]
        _ (apply println "install-versioned-artifact:" install-cmd)
        rtn (apply sh install-cmd)]
    (when-not (zero? (:exit rtn))
      (throw (ex-info "lein voom install error" (assoc rtn :cmd install-cmd))))
    rtn))

(defn missing-artifacts-from-exception
  "Returns a sequence of artifacts indicated as missing anywhere in
  any ArtifactNotFoundException that appears in the cause chain of e"
  [e]
  (for [^Exception cause (iterate #(.getCause ^Exception %) e)
        :while cause
        :when (instance? ArtifactNotFoundException cause)]
    (let [art (.getArtifact ^ArtifactNotFoundException cause)]
      (select-keys (bean art) [:groupId :artifactId :version]))))


;; === build-deps ===

(defn ensure-deps-repos
  "Ensures that any repos, mentioned in dependency metadata, are
   cloned locally."
  [deps]
  (doseq [dep deps
          :let [dep-meta-repo (-> dep meta :voom :repo)]
          :when dep-meta-repo]
    (ensure-repo dep-meta-repo)))

(defn resolve-artifact
  "Build and install given artifact using git. Return value is
  undefined. Throws an exception with detailed message if artifact
  cannot be resolved."
  [old-exception proj {:keys [groupId artifactId version] :as art}]

  (if-let [vmap (ver-parse version)]
    (let [dep-entry [(symbol groupId artifactId) version]
          dep (first (filter #(= dep-entry %) (:dependencies proj)))
          dep-meta-repo (-> dep meta :voom :repo)
          _ (when dep-meta-repo (ensure-repo dep-meta-repo))
          prjs (find-matching-projects voom-repos (merge vmap art))]
      (when (empty? prjs)
        (throw (ex-info (str "No project found for " artifactId " " version
                             " (Hint: might need to clone a new repo into "
                             voom-repos ")")
                        {:artifact art :vmap vmap} old-exception)))
      (when (< 1 (count prjs))
        (println "WARNING: multiple projects match" artifactId ":" )
        (doseq [prj prjs]
          (println "->" (:root prj))))
      (doseq [prj prjs]
        (install-versioned-artifact (:root prj))))
    (throw (ex-info (str "Not parseable as voom-version: " version) {:artifact art} old-exception))))

(def null-writer
  "Like /dev/null, but for Java!"
  (proxy [java.io.Writer] []
    (write ([_]) ([_ _ _]))
    (flush [])
    (close [])))

(defn try-once-resolve-voom-version [project]
  (let [non-dev-proj (project/set-profiles project [] [:dev :user])]
    (try
      (with-log-level Level/OFF
        #(binding [*err* null-writer]
           (leiningen.core.classpath/resolve-dependencies :dependencies non-dev-proj)))
      :ok
      (catch Exception e
        ;; lein resolve-dependencies wraps a
        ;; DependencyResolutionException in an ex-info, so if we want
        ;; the real cause of failure we have to dig for it:
        (if-let [arts (seq (missing-artifacts-from-exception e))]
          (doseq [art arts]
            (resolve-artifact e non-dev-proj art))
          (throw e))))))

(defn build-deps
  "Like 'lein deps', but also builds voom-versioned things as needed."
  [project & args]
  (try
    (loop []
      (when-not (= :ok (try-once-resolve-voom-version project))
        (recur)))
    (catch clojure.lang.ExceptionInfo e
      (println "Failed to resolve dependency:" (.getMessage e))
      (pprint {:exception-data (ex-data e)}))))


;; === freshen ===

(let [gvs (GenericVersionScheme.)]
  (defn version-in-range?
    [range-str version-str]
    (.containsVersion (.parseVersionConstraint gvs range-str)
                      (.parseVersion gvs version-str))))

(defn fetch-checkout-all
  "This is slower than necessary for most real use cases."
  [dirs]
  (doseq [^File d dirs]
    (println "Checking out latest:" (.getPath d))
    (git {:gitdir d} "fetch")
    (git {:gitdir d} "checkout" "origin/HEAD")))

(defn read-project [gitdir sha prj-path]
  (let [tmp-file (File/createTempFile ".project-" ".clj")
        _ (spit tmp-file
                (:out (git {:gitdir gitdir} "show" (str sha ":" prj-path))))
        prj (try (assoc (project/read (str tmp-file))
                   :root (or (.getParent (io/file prj-path)) ""))
                 (catch Throwable t
                   (throw (ex-info "Error reading project file"
                                   {:project-file prj-path
                                    :git-sha sha
                                    :git-dir gitdir}
                                   t))))]
    (.delete tmp-file)
    prj))


(defn patch-fn
  [f default-val]
  (fn [filename & args]
    (if (re-find #"\.lein|project[^/]*\.clj$" filename)
      (apply f filename args)
      default-val)))

;; Shhh...
(.setDynamic #'slurp)
(.setDynamic #'load-file)
(defn robust-read-project
  [gitdir sha prj-path]
  (try
    ;; Hack to work around our crazy project.clj files
    (binding [slurp (patch-fn slurp "{}")
              load-file (patch-fn load-file {})]
      (read-project gitdir sha prj-path))
    (catch Exception e
      ;; 128 means git complained about
      ;; something. Probably a non-existant
      ;; project.clj at this sha.
      (when-not (= 128 (:exit (ex-data e)))
        (println "Ignoring error:" (pr-str e)))
      nil)))
(defn robust-read-proj-blob
  [gitdir blob-sha]
  ;; Hack to work around crazy project.clj files
  (binding [slurp (patch-fn slurp "{}")
            load-file (patch-fn load-file {})]
    (let [tmp-file (File/createTempFile ".project-" ".clj")]
      (try
        (spit tmp-file
              (:out (git {:gitdir gitdir} "cat-file" "-p" (str blob-sha))))
        (project/read (str tmp-file))
        (catch Exception e
          ;; It was really just a best effort anyway. Silently ignore.
          nil)
        (finally
         (.delete tmp-file))))))

(defn origin-branches
  [gitdir & {:keys [sha?]}]
  (->> (apply git {:gitdir gitdir} "rev-parse"
              (concat
               (when-not sha?
                 ["--symbolic-full-name"])
               ["--remotes=origin/"]))
       :lines
       (map #(re-find #"[^/]+$" %))
       distinct))

(defn new-throttle
  ([msg-fn] (new-throttle 500 msg-fn (constantly false)))
  ([ms msg-fn] (new-throttle 500 msg-fn (constantly false)))
  ([ms msg-fn report-pred]
     {:last-report (atom 0)
      :ms ms ;; reporting rate in milliseconds
      :msg-fn msg-fn
      :report-pred report-pred}))

(defn throttled [throttle arg]
  (let [now (System/currentTimeMillis)]
    (when (or ((:report-pred throttle) arg)
              (< (:ms throttle) (- now @(:last-report throttle))))
      (reset! (:last-report throttle) now)
      ((:msg-fn throttle) arg))))

(defn report-progress
  "Eagerly consume xs, but return a lazy seq that reports progress
  every half second as the returned seq is consumed."
  [msg xs]
  (concat
   (let [c (count xs)
         digits (inc (long (quot (Math/log c) (Math/log 10))))
         t (new-throttle
            (fn [i]
              (printf (str "\r%s %" digits "d/%d ...") msg (inc i) c)
              (flush))
            #(= (inc %) c))]
     (map-indexed
      (fn [i x]
        (throttled t i)
        x)
      xs))
   (lazy-seq (println "done"))))

(defn parse-sha-refs
  [s]
  (let [[sha datestr parents refstr] (vec (.split #"," s 4))
        refs (when refstr
               (when-let [[_ x] (re-find #"\((.*)\)" refstr)]
                 (mapv #(s/replace % #"^tag: " "") (.split #",\s+" x))))]
    {:sha sha, :ctime (Date. ^String datestr), :parents parents, :refs refs}))

(defn project-change-shas
  [gitdir & opts]
  (->> (apply git {:gitdir gitdir} "log" "--pretty=format:%H,%cd,%p,%d"
              "--name-status" "-m" opts)
       :lines
       (keep #(if-let [[_ op path] (re-matches #"(.)\t(.*)" %)]
                (when (re-find #"(^|/)project\.clj$" path)
                  {:op op :path path})
                (when (seq %)
                  (parse-sha-refs %))))
       (#(concat % [{:sha "end sigil"}]))
       (reductions (fn [[partial complete] entry]
                     (if (:sha entry)
                       [entry partial]
                       [(update-in partial [:ops] (fnil conj []) entry) nil]))
                   [nil nil])
       (keep second)
       (filter :ops)))

(def repo-tag-version "2")

(defn get-repo-tag-version
  [gitdir]
  (-> (git {:gitdir gitdir :ok-statuses #{0 1}}
           "config" "--local" "voom.tag-version") :out s/trim))

(defn set-repo-tag-version
  [gitdir ver]
  (git {:gitdir gitdir :ok-statuses #{0 5}}
       "config" "--local" "--unset-all" "voom.tag-version")
  (git {:gitdir gitdir}
       "config" "--local" "--add" "voom.tag-version" (str ver)))

(defn clear-voom-tags
  [gitdir]
  (let [tags (->> (git {:gitdir gitdir} "tag" "--list" "voom-*")
                  :lines
                  (remove empty?))]
    (when (seq tags)
      (apply git {:gitdir gitdir} "tag" "--delete" tags)
      nil)))

(defn tag-repo-projects
  [gitdir]
  (when (not= repo-tag-version (get-repo-tag-version gitdir))
    (clear-voom-tags gitdir))
  (let [branches (origin-branches gitdir)
        proj-shas (apply project-change-shas gitdir
                         "--not" "--tags=voom-branch--*"
                         (map #(str #_double-negative--> "^origin/" %) branches))]

    ;; add missing voom-- tags
    (doseq [:when (seq proj-shas)
            {:keys [sha refs parents ops]} (report-progress gitdir proj-shas)
            {:keys [op path]} ops]
      (when-let [p (if (= "D" op)
                     {:root (str (.getParent (io/file path)))}
                     (robust-read-project gitdir sha path))]
        (let [snaps (some #(.contains ^String % "-SNAPSHOT")
                         (map second (:dependencies p)))
              tag (s/join "--" (-> ["voom"
                                    (str (:group p)
                                         (when (:group p) "%")
                                         (:name p))
                                    (:version p)
                                    (s/replace (:root p) #"/" "%")
                                    (subs sha 0 7)
                                    (when snaps "snaps")
                                    (when (empty? parents) "no-parent")]))]
          (git {:gitdir gitdir} "tag" "-f" tag sha))))

    ;; TODO: clean up abandoned voom-- and voom-branch-- tags
    ;; Update all voom-branch-- tags
    (doseq [branch branches]
      (let [tag (str "voom-branch--" branch)]
        (git {:gitdir gitdir} "tag" "-f" tag (str "origin/" branch)))))
  (set-repo-tag-version gitdir repo-tag-version))

(defn p-repos
  "Call f once for each repo dir, in parallel. When all calls are
  done, return nil."
  [f]
  (->> (all-repos-dirs)
       (map #(future (f %)))
       doall
       (map deref)
       dorun))

(defn parse-tag
  [tag]
  (->
   (zipmap [:prefix :proj :version :path :sha :snaps :no-parent] (s/split tag #"--"))
   (update-in [:path] (fnil #(s/replace % #"%" "/") :NOT_FOUND))
   (update-in [:proj] (fnil #(s/replace % #"%" "/") :NOT_FOUND))))

(defn assert-good-version [ver proj-name version tags found-branch neg-tags commits]
  (when-not ver
    (println "Tags matching" proj-name version ":")
    (doseq [t tags]
      (prn t))
    (println "Tag filters to exclude from branch" found-branch ":")
    (doseq [t neg-tags]
      (prn t))
    (println "Commits:")
    (doseq [c commits]
      (prn c))
    (throw (ex-info "Failed to find version for commit." {}))))

(declare newest-voom-ver-by-spec)

(defn print-repo-infos
  [repo-infos]
  (->> repo-infos
       (map (fn [info]
              (-> info
                  (dissoc :gitdir)
                  (assoc :repo (-> (remotes (:gitdir info)) :origin :fetch))
                  (update-in [:sha] #(subs (str % "--------") 0 7)))))
       (sort-by :ctime)
       (print-table [:repo :path :proj :version :branch :ctime :sha]))
  (newline))

(defn fresh-version [[prj ver :as dep]]
  (let [voom-meta (-> dep meta :voom)
        ver-spec (or (:version voom-meta)
                     (re-find #"^[^.]+." ver))
        groups (->> (newest-voom-ver-by-spec prj (merge voom-meta {:version ver-spec
                                                                   :allow-snaps false}))
                    (map #(assoc % :voom-ver (format-voom-ver
                                              (update-in % [:sha] subs 0 7))))
                    (group-by :voom-ver))]
    (case (count groups)
     0 (do (println "No matching version found for" prj (pr-str ver-spec))
           dep)
     1 (assoc dep 1 (key (first groups)))
     (do (print "\nMultiple bump resolutions for:"
                prj (pr-str ver-spec) (pr-str voom-meta))
         (print-repo-infos (map #(first (val %)) groups))
         dep))))

(defn rewrite-project-file [input-str replacement-map]
  (reduce (fn [^String text [[prj old-ver :as old-dep] [_ new-ver]]]
            (let [short-prj (if (= (name prj) (namespace prj))
                              (name prj)
                              (str prj))
                  pattern (re-pattern (str "\\Q" short-prj "" "\\E(\\s+)\\Q\"" old-ver "\"\\E"))
                  matches (re-seq pattern input-str)]
              (when (empty? matches)
                (throw (ex-info (str "No match found for " [prj old-ver])
                                {:pattern pattern :dep old-dep})))
              (when (second matches)
                (throw (ex-info (str "More than one match found for " [prj old-ver])
                                {:pattern pattern :dep old-dep})))
              (s/replace text pattern (str short-prj "$1" \" new-ver \"))))
          input-str
          replacement-map))

(defn freshen
  "Modify dependency versions in this project.clj based on what's available in $VOOM_REPOS

  Fetches latest upstream commits for all repos in $VOOM_REPOS (usually
  ~/.voom-repos), index-tags those commits as needed, and then finds
  the latest version for each dependency that matches any voom
  metadata applied to the dep. Updates the project.clj file in place
  with the version found.

  Metadata on a dep can restrict versions found based on :repo,
  :branch, path, and partial :version.  For example:
  ^{:voom {:version \"1\", :path \"modules/foo\"}} [bar/foo \"1.0.0\"]

  Will only update deps that can be found as lein projects in
  $VOOM_REPOS, so you may need to manually clone new working copies
  there so voom can find your deps.

  Example: lein voom freshen"
  [project]
  (ensure-deps-repos (:dependencies project))
  (p-repos (fn [p] (fetch-all [p]) (tag-repo-projects p)))
  (let [prj-file-name (str (:root project) "/project.clj")
        old-deps (:dependencies project)
        desired-new-deps (doall (map #(fresh-version %) old-deps))]
    (doseq [[[prj old-ver] [_ new-ver]] (map list old-deps desired-new-deps)]
      (println (format "%-40s" prj)
               (str old-ver
                    (when (not= old-ver new-ver)
                      (str " -> " new-ver)))))
    (if (= old-deps desired-new-deps)
      (println "No versions bumped.")
      (let [replacement-map (into {} (map #(when (not= %1 %2) [%1 %2])
                                          old-deps desired-new-deps))
            tmp-file (File/createTempFile
                      ".project-" ".clj" (File. ^String (:root project)))]

        (spit tmp-file
              (rewrite-project-file (slurp prj-file-name) replacement-map))

        (if (= desired-new-deps (:dependencies (project/read (str tmp-file))))
          (.renameTo tmp-file (File. prj-file-name))
          (throw (ex-info (str "Freshen mis-fire. See "
                               tmp-file " for attempted change.")
                          {:old-deps old-deps
                           :replacement-map replacement-map
                           :desired-new-deps desired-new-deps
                           :tmp-file-name (str tmp-file)})))))))

(defn all-projects
  []
  (into #{}
        (flatten
         (for [g (all-repos-dirs)]
           (map #(s/replace (second (s/split % #"--")) #"%" "/")
                (:lines (git {:gitdir g} "tag" "--list" "voom--*")))))))

(defn resolve-short-proj
  [dep projects]
  (for [proj projects
        :when (.contains ^String proj dep)]
    proj))

(defn ^File to-file
  [p]
  (if (= File (type p))
    p
    (File. ^String p)))

(defn ^File adj-path
  "Adjusts current File object with subsequent elements."
  [^File f & elems]
  (reduce #(-> (File. ^File %1 ^String %2) .toPath .normalize .toFile)
          f
          elems))

(defn ^File adj-dir
  [p & elems]
  ;; TODO Should this be made an isDirectory check?
  (let [^File f (to-file p)
        d (if (.isFile f)
            (.getParentFile f)
            f)]
    (apply adj-path d elems)))

(defn ^:info-subtask ^File find-box
  "Locates voom-box root starting from current working directory."
  []
  (loop [^File path (or *pwd* (-> "user.dir" System/getProperty File.))]
    (let [^String ppath (.getCanonicalPath path)
          ^File pfile (adj-dir ppath task-dir)]
      (when-not (= "/" ppath)
        (if (.exists pfile)
          path
          (recur (.getParentFile path)))))))

(defn all-boxes
  []
  (when-let [box (find-box)]
    (->> (.listFiles box)
         (filter #(.contains (.getCanonicalPath ^File %) (str "/" task-dir "/")))
         (map (memfn ^File getName)))))

(defn safe-delete-repo
  [checkout pdir]
  (if-let [dirty (dirty-repo? checkout)]
          (do (println "Must clean up repo:" pdir)
              (prn dirty)
              (lmain/abort "Please fix."))
          (do (sh "rm" "-f" pdir)
              (sh "rm" "-rf" checkout))))

(defn box-repo-add
  [{:keys [gitdir branch sha proj path]}]
  (if-let [bdir (find-box)]
    (let [sha (str sha)
          pname (-> (str proj)
                    (s/replace #"/" "--"))
          pdir (adj-path bdir pname)
          checkout (adj-path bdir task-dir pname)
          g {:gitdir checkout}
          remote (-> (remotes gitdir) :origin :fetch)]
      (when (and (.exists ^File checkout)
                 (not= remote (-> (remotes (:gitdir g)) :origin :fetch)))
        (safe-delete-repo checkout pdir))
      (if (.exists ^File checkout)
        (git g "fetch")
        (git {} "clone" remote "--refer" gitdir checkout))
      (git g "checkout" sha) ; must detach head for update...
      (git g "branch" "-f" branch sha)
      (git g "checkout" branch)
      (sh "rm" "-f" pdir)
      ;; (SYMLINK WILL NEED TO BE UPDATED FOR EACH CHECKOUT)
      (sh "ln" "-s" (adj-path checkout path) pdir))
    (println "Can't find box")))

(defn fold-args-as-meta
  [adeps]
  ;; TODO better handle malformed commandlines for error reporting
  (loop [deps [] [fdep & rdeps] adeps]
    (if fdep
      (if (seq rdeps)
        (if (map? fdep)
          (let [[ndep & rdeps] rdeps]
            (recur (conj deps (with-meta ndep {:voom fdep})) rdeps))
          (recur (conj deps fdep) rdeps))
        (conj deps fdep))
      deps)))

(defn box-add
  [proj & adeps]
  (let [deps (fold-args-as-meta (map edn/read-string adeps))]
    (ensure-deps-repos deps)
    (p-repos (fn [p] (tag-repo-projects p)))
    (doseq [dep deps
            :let [full-projs (if (.contains (str dep) "/")
                               [dep]
                               (resolve-short-proj (pr-str dep) (all-projects)))
                  full-projs (map symbol full-projs)
                  repo-infos (mapcat #(newest-voom-ver-by-spec % (-> dep meta :voom))
                                     full-projs)]]
      (case (count repo-infos)
        0 (throw (ex-info "Could not find matching projects" {:dep dep}))
        1 (box-repo-add (first repo-infos))
        (do
          (print "Multiple projects / locations match" (str \" dep \"\:))
          (print-repo-infos repo-infos))))))

(defn box-init
  [proj target & args]
  (sh "mkdir" "-p" (adj-path target task-dir))
  (apply box-add proj args))

(defn box-new
  [proj target & args]
  (let [p (adj-path *pwd* target)]
    (sh "mkdir" "-p" p)
    (fcmd (str "target_dir='" (.getAbsolutePath p) "'"))
    (binding [*pwd* p]
      (apply box-init proj args))))

(defn box-remove
  [proj & args]
  (doseq [a args
          :let [prjs (resolve-short-proj a (all-boxes))]]
    (if (= 1 (count prjs))
      (let [box-root (find-box)
            link (adj-path box-root (first prjs))
            repo (adj-path box-root task-dir (first prjs))]
        (safe-delete-repo repo link))
      (do
        (print (str "Cannot remove '" a "', multiple matches:"))
        (doseq [p prjs]
          (print (str " " p)))
        (println)))))

(declare voom)
(defn box
  [proj & args]
  (let [[^String ver ^String pwd ^String ififo ^String ofifo & rargs] args
        _ (assert (= "1" ver))
        fpwd (File. pwd)
        fofifo (future (-> ofifo FileOutputStream. OutputStreamWriter.))
        fififo (future (-> ififo FileInputStream.))]
    (binding [*pwd* fpwd
              *ofifo* @fofifo
              *ififo* @fififo]
      (.read *ififo* (byte-array 5))
      (apply voom proj rargs)
      ;; TODO formalize break/exit handling
      (fcmd "break"))))

;; === lein entrypoint ===

(defn wrap
  "Execute any lein task, but using git info as this project's version qualifier.

   Usage: lein voom wrap [<flags>] <lein task name> <lein task args>

   Flags:
     :insanely-allow-dirty-working-copy - by default voom sanely
         refuses to generate a voom-version from a dirty working copy
     :long-sha - uses a full length sha instead of the default
         short form

   Example: lein voom wrap install"
  [project & args]
  (let [[kw-like more-args] (split-with #(re-find #"^:" %) args)
        kargset (set (map edn/read-string kw-like))
        new-project (update-proj-version project (:long-sha kargset))]
    ;; TODO throw exception if upstream doesn't contain this commit :no-upstream
    ;;    :no-upstream - by default voom wants to see the current
    ;;      version reachable via an upstream repo
    (if (and (dirty-wc? (:root new-project))
             (not (:insanely-allow-dirty-working-copy kargset)))
      (lmain/abort "Refusing to continue with dirty working copy. (Hint: Run 'git status')")
      (lmain/resolve-and-apply new-project more-args))))

(defn install
  "Same as 'lein voom wrap install': install a voom-versioned artifact of this project.

  Example: lein voom install"
  [project & args]
  (apply wrap project "install" args))

(defn deploy
  "Same as 'lein voom wrap deploy': deploy a voom-versioned artifact of this project.

  Example: lein voom deploy clojars"
  [project & args]
  (apply wrap project "deploy" args))

(defn retag-all-repos
  "Clear and recreate all voom index tags in $VOOM_REPOS.

  You shouldn't need to use this usually because the index tags are
  automatically added incrementally and re-generated when tag schemas
  change.

  Example: lein voom retag-all-repos"
  [_]
  (time (p-repos (fn [p] (clear-voom-tags p) (tag-repo-projects p)))))

;; ===== Git import =====

(defn git-commits
  [gitdir & args]
  (->>
   (apply git {:gitdir gitdir} "log" "--full-history" "--reverse"
          "--pretty=%H,%ct,%T,%P" args)
   :lines
   (map #(let [[sha ctime tree parents] (s/split % #",")
               parents (when parents (s/split parents #" "))]
           (zipmap [:sha :ctime :tree :parents]
                   [sha ctime tree parents])))))

(defn git-tree
  [gitdir tree-sha]
  (into {}
        (for [e (:lines (git {:gitdir gitdir} "cat-file" "-p" tree-sha))
              :let [[_ ftype sha fname] (s/split (s/trim e) #"\s" 4)]]
          [fname {:sha sha
                  :ftype ftype}])))

;; ===== relation database tables =====

(pldb/db-rel r-branch repo-path branch-name sha)
(pldb/db-rel r-commit ^:index sha ctime parent-count)
(pldb/db-rel r-commit-parent ^:index sha ^:index parent-sha)
(pldb/db-rel r-commit-path ^:index sha ^:index path)
(pldb/db-rel r-proj-path ^:index sha ^:index path ^:index blob-sha)
(pldb/db-rel r-proj ^:index blob-sha ^:index name version has-snaps?)

(defmacro q
  [db & args]
  (let [[count [rfresh & body]] (if (-> args first number?)
                                  ((juxt first rest) args)
                                  [nil args])
        run-mode (if count
                   '[l/run count]
                   '[l/run*])
        body (walk/postwalk #(get '{_ (l/lvar)} % %)
                            body)]
    ;; TODO: consider handling rfresh as map to get map results back
    `(pldb/with-db ~db
       (~@run-mode ~rfresh
                   ~@body))))

(defn sub-paths
  "Turns path 'foo/bar/baz/quux' into:
  ['' 'foo' 'foo/bar' 'foo/bar/baz' 'foo/bar/baz/quux']"
  [path]
  (-> path
      (s/split #"/")
      (->> (reductions #(str %1 "/" %2)))
      (conj "")))

(defn file-path-merges
  [parents]
  (->> parents
       (mapcat #(into #{} (mapcat sub-paths %)))
       (reduce #(update-in % [%2] (fnil inc 0)) {})
       (filter #(< 1 (val %)))
       (map first)))

(defn merged-paths
  [gitdir sha]
  ;; TODO How to properly handle project file deletions?
  (->> (git {:gitdir gitdir} "log" "-1" "-m"
            "--name-status" "--pretty=format:--" sha)
       :lines
       (remove #{""})
       (map #(s/replace % #"^[ADM]\t" ""))
       (partition-by #{"--"})
       (remove #{'("--")})
       file-path-merges
       (map #(-> (git {:gitdir gitdir}
                      "rev-parse" (str sha ":" %))
                 :out
                 s/trim
                 (->> (vector %))))
       (into {})))

(defn exported-commits
  [gitdir tip-shas seen-shas]
  (let [marks-file (File/createTempFile "marks" ".txt")]
    (try
      (let [{:keys [out]} (apply sh "git" "fast-export" "--no-data"
                                 (str "--export-marks=" marks-file)
                                 (concat (map str tip-shas)
                                         (map #(str "^" %) seen-shas)
                                         [:dir gitdir, :out-enc :bytes]))
            marks (into {}
                        (map #(let [[mark sha] (s/split % #" ")]
                                [mark (sha/mk sha)])
                             (re-seq #"(?m)^.*$" (slurp marks-file))))
            merge-commit-fn (fn [cmd commit]
                              (if (= cmd "merge")
                                (-> commit
                                    (assoc :merge true)
                                    (assoc :paths (merged-paths gitdir (-> commit :sha str))))
                                commit))]
        (with-open [rdr (-> out
                            java.io.ByteArrayInputStream.
                            (java.io.InputStreamReader. "ISO-8859-1")
                            java.io.BufferedReader.)]
          (loop [commits [], commit {}]
            (if-let [line (.readLine rdr)]
              (if (empty? line)
                (recur (conj commits commit) {})
                (let [[cmd more-line] (s/split line #" " 2)]
                  (recur commits
                         (case cmd
                           ("reset" "commit" "author") commit ;; ignore these
                           "mark" (assoc commit :sha (marks more-line))
                           "committer" (->> more-line
                                            (re-matches #".* (\d+) .\d+")
                                            second Long/parseLong (* 1000) Date.
                                            (assoc commit :ctime))
                           "data" (do (.skip rdr (Long/parseLong more-line))
                                      commit)
                           ("from" "merge") (let [commit (merge-commit-fn cmd commit)]
                                              (update-in commit [:parents]
                                                         (fnil conj [])
                                                         (marks more-line)))
                           "M" (if (:merge commit)
                                 commit
                                 (let [[_ sha path] (s/split more-line #" " 3)]
                                   (update-in commit [:paths]
                                              assoc path (sha/mk sha))))
                           "D" (update-in commit [:paths] assoc more-line nil)
                           (do (println "Unparsed:" line)
                               commit)))))
              commits))))
      (finally
       (.delete marks-file)))))

(defn proj-fact-tail
  [gitdir blob-sha]
  (if-let [proj (robust-read-proj-blob gitdir blob-sha)]
    (let [proj-name (symbol (:group proj) (:name proj))
          has-snaps? (boolean (some #(.contains ^String % "-SNAPSHOT")
                                    (map second (:dependencies proj))))]
      [proj-name (:version proj) has-snaps?])
    [nil nil nil]))

(defn add-commit-facts
  [pldb gitdir commit]
  (let [sha (:sha commit)]
    (-> pldb (pldb/db-fact r-commit sha (:ctime commit)
                           (count (:parents commit)))
        (->/for [parent (:parents commit)]
          (pldb/db-fact r-commit-parent sha parent))
        (->/for [[mode otype bsha path]
                 , (->> (git {:gitdir gitdir}
                             "ls-tree" "-r" "--full-tree" (str sha))
                        :lines
                        (map #(s/split % #"\s+" 4)))
                   :when (re-find #"(^|/)project.clj$" path)
                   :let [blob-sha (sha/mk bsha)]]
          (->/as pldb
            (pldb/db-fact r-proj-path sha (s/replace path #"(^|/)project.clj$" "") blob-sha)
            (->/when-not (first (q pldb [x] (r-proj blob-sha _ _ _) (l/== x true)))
              (->/apply pldb/db-fact
                        r-proj blob-sha (proj-fact-tail gitdir blob-sha)))))
        (->/for [dir-path (set (mapcat sub-paths (keys (:paths commit))))]
          (pldb/db-fact r-commit-path sha dir-path)))))

(defn build-shabam
  [{:keys [shabam pldb]} tips]
  (loop [shabam shabam, stack (vec tips)]
    (if-let [frame (peek stack)]
      (if (vector? frame)
        ;; parents all added themselves
        (recur
         (apply shabam-add shabam frame)
         (pop stack))
        ;; no parents checked yet
        (if (shabam-contains? shabam frame)
          (recur shabam (pop stack)) ;; I'm in db. Done.
          (let [parents (seq
                         (q pldb [par]
                            (r-commit-parent frame par)))]
            (recur shabam
                   (-> stack
                       (conj (into [frame] parents)) ;; to add myself later
                       (into parents)))))) ;; have parents check themselves
      {:shabam shabam :pldb pldb})))

(defn add-git-facts
  [db gitdir]
  (-> db
    (->/let [old-branches (into {} (map (comp vec next)
                                        (vdb/get-facts (:pldb db) r-branch)))
             new-branches (zipmap
                           (origin-branches gitdir)
                           (map sha/mk (origin-branches gitdir :sha? true)))]
      (->/when (not= old-branches new-branches)
        (->/let [repo (-> (remotes gitdir) :origin :fetch)]

          ;; Update branchs
          (->/for [[name sha] old-branches]
            (update-in [:pldb] pldb/db-retraction r-branch repo name sha))
          (->/for [[name sha] new-branches]
            (update-in [:pldb] pldb/db-fact r-branch repo name sha))

          ;; Add commits
          (->/for [commit (exported-commits
                           gitdir (vals new-branches) (vals old-branches))]
            (->/assoc :pldb (add-commit-facts gitdir commit)))

          (build-shabam (vals new-branches))
          (vary-meta assoc ::dirty true))))))

(def voomdb-header "voom-db-7")

(defn ^File git-db-file
  [gitdir]
  (io/file gitdir ".git" "voomdb.frs.gz"))

(defn read-git-db
  [gitdir]
  (let [file (git-db-file gitdir)
        [header & [shabam & reldata]] (-> file
                               io/input-stream
                               java.util.zip.GZIPInputStream.
                               (fress/read :handlers sha/read-handlers))]
    (if (= header voomdb-header)
      {:shabam (update-in shabam [:bitmaps] vec)
       :pldb
       , (vdb/from-reldata
          [r-branch r-commit r-commit-parent r-commit-path r-proj-path r-proj]
          reldata)}
      (do
        (println "Existing voomdb file for" (str gitdir)
                 "has wrong header" header "needed:"
                 voomdb-header)
        {:shabam (shabam-new)
         :pldb pldb/empty-db}))))

(defn write-git-db
  [db gitdir]
  (with-open [w (-> (git-db-file gitdir)
                    io/output-stream
                    java.util.zip.GZIPOutputStream.
                    ^Closeable (fress/create-writer
                                :handlers sha/write-handlers))]
    (fress/begin-open-list w)
    (fress/write-object w voomdb-header)
    (fress/write-object w (:shabam db))
    (doseq [item (vdb/to-reldata (:pldb db))]
      (fress/write-object w item))))

(defn updated-git-db
  [gitdir]
  (let [db (-> (if (.exists (git-db-file gitdir))
                 (read-git-db gitdir)
                 {:shabam (shabam-new) :pldb pldb/empty-db})
               (add-git-facts gitdir))]
    (when (::dirty (meta db))
      (write-git-db db gitdir))
    db))

(defn update-repo-dbs
  "Update voom dbs for all git repos in $VOOM_REPOS.

  Example: lein voom update-repo-dbs"
  [_]
  (time (p-repos (fn [p] (updated-git-db p)))))

;; ===== latest version querying =====

(defn compare-max [& xs]
  (when (seq xs)
    (reduce #(if (neg? (compare %1 %2)) %2 %1) xs)))

(defn newest-voom-ver-by-spec
"
  proj dir change -> commit where a change happened in a subtree rooted with a project.clj
  - mark all project directory changes with project.clj version info
  - a. narrow proj dir changes by proj name spec
  - b. narrow all proj dir changes by *this* proj dir
  - c. for each branch (matching spec) narrow proj dir changes by branch reachability
  - d. narrow proj dir changes further by version range (spec)
  - e. find max sem ver
  - f. narrow proj dir changes further by max sem ver
  - g. narrow proj dir changes to candidates by finding proj dir changes
       with no childer proj dir changes in this set
"
  [proj-name {:keys [sha version repo branch path allow-snaps]
              :or {allow-snaps true sha (l/lvar)}}]
  (for [gitdir (all-repos-dirs)
        :when (or (nil? repo) (= repo (-> (remotes gitdir) :origin :fetch)))
        :let [sha (if (string? sha)
                    (sha/mk sha)
                    sha)
              {:keys [shabam pldb]} (updated-git-db gitdir)
              candidates-a (->>
                       (q pldb [msha ctime path version has-snaps?]
                          (l/fresh [bsha]
                                   (l/== msha sha)
                                   (r-proj bsha proj-name version has-snaps?)
                                   (r-proj-path sha path bsha)
                                   (r-commit-path sha path)
                                   (r-commit sha ctime _)))
                       (map #(zipmap [:sha :ctime :path :version :has-snaps?]
                                     %)))
              paths (distinct (map :path candidates-a))]
        found-path paths
        :when (or (= found-path path) (nil? path))
        [found-branch branch-sha] (q pldb [ref sha] (r-branch _ ref sha))
        :when (or (= found-branch branch) (nil? branch))
        :let [candidates-b (filter #(= (:path %) found-path) candidates-a)
              sha-candidates-a (group-by :sha candidates-b)
              shas-c (sha-ancestors shabam branch-sha (map :sha candidates-b))
              shas-c (if (contains? sha-candidates-a branch-sha)
                       (conj shas-c branch-sha)
                       shas-c)
              gvs (GenericVersionScheme.)
              version-constraint (when version
                                   (.parseVersionConstraint gvs version))
              candidates-d (if version
                        (keep #(let [[candidate] (get sha-candidates-a %)]
                                 (when (.containsVersion
                                        version-constraint
                                        (.parseVersion gvs (:version candidate)))
                                   candidate))
                              shas-c)
                        (map (comp first sha-candidates-a) shas-c))
              [_ max-ver-e] (apply compare-max
                                   (map #(vector (when % (.parseVersion gvs %)) %)
                                        (distinct (map :version candidates-d))))
              candidates-f (filter #(= max-ver-e (:version %)) candidates-d)
              shas-f (map :sha candidates-f)
              candidates-g (remove #(seq (sha-successors shabam (:sha %) shas-f))
                                   candidates-f)]
        {:keys [sha ctime]} candidates-g]
    {:sha sha
     :ctime ctime
     :version max-ver-e
     :path found-path
     :proj proj-name
     :gitdir gitdir
     :branch found-branch}))

(def subtasks [#'build-deps #'deploy #'find-box #'freshen #'install
               #'retag-all-repos #'update-repo-dbs #'ver-parse #'wrap])

(def internal-subtasks [#'box #'box-new #'box-init #'box-add #'box-remove])

(defn ^:no-project-needed ^{:subtasks subtasks} voom
  "Generate and use artifacts versioned with git commit and commit time.

  There are several subtasks, as well as the 'box' script which has
  several commands of its own, built using this plugin."
  [project subtask-name & args]

  (let [subtask-var (resolve (symbol "leiningen.voom"
                                     (s/replace subtask-name #"^:" "")))]
    (if-let [subtask (some #{subtask-var} (concat subtasks internal-subtasks))]
      (if (:info-subtask (meta subtask))
        (prn (apply subtask args))
        (apply subtask project args))
      (println "No voom subtask found" (prn-str subtask-name)
               " Try: lein help voom"))))

(comment
  (l/run* [q] (l/fresh [q] (l/== (sha/mk "7b3e68a8839aeb4")
                                 (sha/mk "7b3e68a8839aeb4"))))
  (def xdb1 (pldb/db [r-tree :a :b :c :d]))
  (pldb/with-db xdb1 (l/run* [a] (r-tree a :b :c :d)))
  (def xdb2 (vdb/from-reldata [r-tree] (vdb/to-reldata xdb1)))
  (pldb/with-db xdb2 (l/run* [a] (r-tree a :b :c :d)))
  (def xdb3 (vdb/from-reldata [r-tree] (fress/read (fress/write (vdb/to-reldata xdb1)))))
  (pldb/with-db xdb3 (l/run* [a] (r-tree a :b :c :d)))

  (time (def db (add-git-facts {:shabam (shabam-new)
                                :pldb pldb/empty-db}
                               (io/file voom-repos "lein-voom"))))
  (time (def db (updated-git-db (io/file voom-repos "lein-voom"))))
  (time (def db2 (updated-git-db (io/file voom-repos "lonocore"))))
  )
