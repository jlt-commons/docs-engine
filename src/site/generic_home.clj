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

(defn render
  "The generic homepage as HTML.

   Only the guide/index.md case gets its links rewritten and then
   guide/-prefixed. README-sourced content keeps whatever the default
   rewrite produced: its links are relative to the repository root, which
   is a different context and out of scope here."
  [project site-ctx]
  (let [src          (content-source project)
        guide-index? (and src (= (.getName src) "index.md"))
        rendered     (cond
                       (nil? src)   nil
                       guide-index? (md/render-doc-page (slurp src) (md/rewrite-nested-doc-links "index.md"))
                       :else        (md/render-doc-page (slurp src)))
        content      (cond
                       (nil? src)   (str "<h1>" (:site-title site-ctx) "</h1><p>No documentation yet.</p>")
                       guide-index? (prefix-guide-links (:body-html rendered))
                       :else        (:body-html rendered))]
    (selmer/render-file "generic-home.html"
                        (merge site-ctx
                               {:page    "home"
                                :content content
                                ;; nil when there is no source document or it has no
                                ;; h2/h3. Selmer reads nil as falsy, so {% if toc %}
                                ;; skips the block.
                                :toc     (:toc-html rendered)}))))
