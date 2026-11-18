(ns site.generic-home
  "The homepage a project gets when it ships no template of its own.

   Renders the project's docs/guide/index.md, falling back to its
   README.md. That is enough for most projects, and it means onboarding a
   new one needs nothing but markdown."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [selmer.parser :as selmer]
            [site.markdown :as md]))

(defn- content-source
  "docs/guide/index.md if it exists, else README.md, else nil."
  [{:keys [guide-dir readme-file]}]
  (let [index-md (io/file guide-dir "index.md")]
    (cond
      (fs/exists? index-md)    index-md
      (fs/exists? readme-file) readme-file
      :else                    nil)))

(defn prefix-guide-links
  "The generic homepage renders guide/index.md but is written to the site
   root, so every relative *.html href in it was resolved one directory
   too deep and needs a guide/ prefix. An absolute href (https://... or a
   leading /) is left alone, which the negative lookahead handles before
   the capture starts."
  [html]
  (str/replace html #"href='(?!https?://|/)([^']*\.html[^']*)'" "href='guide/$1'"))

(defn rewrite-readme-asset-links
  "README.md's relative links are relative to the repository root, which the
   site does not mirror. A directory under docs/ named in :asset-dirs is
   copied to the site root under its own name (site.core/copy-project-assets!),
   so the docs/images/logo.svg a README shows on GitHub is
   <site-base>/images/logo.svg on the site. Rewrites each src and href that
   starts with docs/<name>/ or ./docs/<name>/, in either quote style, for each
   configured name. Anything else is left as it was, since a path the build
   does not copy has no site URL to point at."
  [html asset-names site-base]
  (reduce (fn [h n]
            (str/replace h (re-pattern (str "(src|href)=([\"'])(?:\\./)?docs/"
                                            (java.util.regex.Pattern/quote n) "/"))
                         (fn [[_ attr q]] (str attr "=" q site-base "/" n "/"))))
          html
          asset-names))

(defn rewrite-readme-guide-links
  "A README links to its guide pages as GitHub needs them, docs/guide/x.md
   from the repository root. The site has each one at
   <site-base>/guide/x.html, so on a homepage rendered from the README those
   links were dead. Rewrites each href that starts with docs/guide/ or
   ./docs/guide/ and names a .md file, in either quote style, keeping a
   #fragment. A link to anything else under docs/ is left as it was."
  [html site-base]
  (str/replace html
               #"href=([\"'])(?:\./)?docs/guide/([^\"'#]+)\.md(#[^\"']*)?\1"
               (fn [[_ q path frag]] (str "href=" q site-base "/guide/" path ".html" frag q))))

(defn rendered-source
  "What the homepage's source document rendered to, so a caller can both
   build the page and index it without rendering the markdown twice.
   :source is :guide-index, :readme or nil; :title and :body-html come
   from md/render-doc-page and are nil when there is no source. :toc-html
   rides along for the page template."
  [project]
  (let [src (content-source project)]
    (cond
      (nil? src)
      {:source nil}

      (= (.getName src) "index.md")
      (assoc (md/render-doc-page (slurp src) (md/rewrite-nested-doc-links "index.md"))
             :source :guide-index)

      :else
      (assoc (md/render-doc-page (slurp src)) :source :readme))))

(defn render
  "The generic homepage as HTML.

   Only the guide/index.md case gets its links rewritten and then
   guide/-prefixed. README-sourced content keeps whatever the default
   rewrite produced, since its links are relative to the repository root,
   except that links into a configured asset directory point at the copy the
   build makes (rewrite-readme-asset-links) and links to guide pages at the
   pages the build writes (rewrite-readme-guide-links).

   The three-arity form takes an already-computed rendered-source, for a
   caller that needs the same render for something else."
  ([project site-ctx] (render project site-ctx (rendered-source project)))
  ([project site-ctx {:keys [source body-html toc-html]}]
   (let [content (case source
                   nil          (str "<h1>" (:site-title site-ctx) "</h1><p>No documentation yet.</p>")
                   :guide-index (prefix-guide-links body-html)
                   (-> body-html
                       (rewrite-readme-asset-links (map fs/file-name (:asset-dirs project))
                                                   (:site-base site-ctx))
                       (rewrite-readme-guide-links (:site-base site-ctx))))]
     (selmer/render-file "generic-home.html"
                         (merge site-ctx
                                {:page    "home"
                                 :content content
                                 ;; nil when there is no source document or it has no
                                 ;; h2/h3. Selmer reads nil as falsy, so {% if toc %}
                                 ;; skips the block.
                                 :toc     toc-html})))))
