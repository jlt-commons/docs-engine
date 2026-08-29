(ns site.project
  "Resolves a project directory into the config map site.core builds from.

   A project owns its content and the engine owns the rendering, so
   everything here is a convention over the project's own tree rather
   than a setting the engine holds on the project's behalf. That is the
   difference between this and the engine it descends from, where each
   project's config and bespoke homepage lived inside the engine repo —
   fine for one author, wrong for an organization where the project's
   maintainer is not the engine's."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def config-name
  "The project's own config file, read relative to its docs directory."
  "site.edn")

(defn- first-h1
  "The text of a markdown document's first H1: a line starting with a
   single # then whitespace. A ## line does not match, since \\s+ has to
   follow the single #. nil when the document has no H1."
  [text]
  (when-let [[_ title] (re-find #"(?m)^#\s+(.+)$" text)]
    (str/trim title)))

(defn- default-title
  "The README's first H1, or the directory name when there is no README
   or it has no H1."
  [project-dir]
  (let [readme (io/file project-dir "README.md")]
    (or (when (fs/exists? readme) (first-h1 (slurp readme)))
        (str (fs/file-name (fs/absolutize project-dir))))))

(defn ssh-remote->https
  "git@host:owner/repo.git -> https://host/owner/repo, dropping .git. An
   already-https remote comes back with just the .git suffix dropped.
   Any other shape returns nil rather than guessing at it."
  [remote]
  (when remote
    (let [remote (str/trim remote)]
      (cond
        (str/starts-with? remote "git@")
        (when-let [[_ host path] (re-find #"^git@([^:]+):(.+)$" remote)]
          (str "https://" host "/" (str/replace path #"\.git$" "")))

        (str/starts-with? remote "https://")
        (str/replace remote #"\.git$" "")

        :else nil))))

(defn- default-github-url
  "origin's URL as an https link, or nil when the directory is not a git
   repo, has no origin, or the remote does not parse. Never throws: this
   decorates a nav link, it does not gate a build."
  [project-dir]
  (when (fs/exists? project-dir)
    (try
      (let [{:keys [out exit]} (process/shell {:out :string :err :string :continue true
                                               :dir (str project-dir)}
                                              "git" "remote" "get-url" "origin")]
        (when (zero? exit) (ssh-remote->https out)))
      (catch Exception _ nil))))

(defn- validate-asset-dir!
  "An :asset-dirs entry names one directory directly under docs/. No path
   separators, no leading dot, no \\\"..\\\".

   An allowlist rather than a pattern to reject, because this decides what
   gets copied into a published site. A project that could write
   :asset-dirs [\\\"../../.ssh\\\"] would publish it."
  [entry project-name]
  (when-not (and (string? entry) (re-matches #"[^/\\.][^/\\]*" entry))
    (throw (ex-info (str ":asset-dirs entries each name a single directory directly under docs/ — "
                         "no path separators, no leading dot, no \"..\" (got " (pr-str entry) ")")
                    {:entry entry :project project-name})))
  entry)

(defn read-config
  "The project's docs/site.edn as a map. An absent file is {}, so a
   project with nothing but docs/guide/*.md still builds."
  [docs-dir]
  (let [f (io/file docs-dir config-name)]
    (if (fs/exists? f)
      (edn/read-string (slurp f))
      {})))

(defn resolve-project
  "project-dir -> the config map site.core/generate! takes.

   :output-dir is the project's own _site/ and is not configurable. The
   engine writes into the project it was pointed at and nowhere else,
   which is what lets a CI job run it against a checkout without handing
   it credentials for anywhere.

   Conventions, all relative to the project's docs/ directory: guide
   pages in guide/, an asset directory per :asset-dirs entry, and
   templates in whatever :templates-dir names (default templates/)."
  [project-dir]
  (let [project-dir (io/file project-dir)
        docs-dir    (io/file project-dir "docs")
        cfg         (read-config docs-dir)
        name        (str (fs/file-name (fs/absolutize project-dir)))
        title       (or (:title cfg) (default-title project-dir))]
    (merge
     {:name          name
      :project-dir   project-dir
      :docs-dir      docs-dir
      :guide-dir     (io/file docs-dir "guide")
      :readme-file   (io/file project-dir "README.md")
      :output-dir    (io/file project-dir "_site")
      :title         title
      :description   (or (:description cfg) title)
      :github-url    (or (:github-url cfg) (default-github-url project-dir))
      ;; "" for a site served at a domain root, "/repo" for a project site
      ;; under an organization's Pages root. See site.core/base-path.
      :base-path     (or (:base-path cfg) "")
      :templates-dir (io/file docs-dir (or (:templates-dir cfg) "templates"))
      ;; nil means the generic homepage, rendered from the project's own
      ;; guide index or README. A project only writes a template when it
      ;; wants something the generic page cannot express.
      :home-template (:home-template cfg)
      :asset-dirs    (mapv (fn [d] (io/file docs-dir (validate-asset-dir! d name)))
                           (:asset-dirs cfg))}
     ;; Only when the site said something. Absent means auto-detect per page,
     ;; which is right almost always; see site.core/mermaid-needed?.
     (when (contains? cfg :mermaid) {:mermaid (:mermaid cfg)}))))
