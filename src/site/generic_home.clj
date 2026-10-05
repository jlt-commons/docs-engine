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
   rewrite produced: its links are relative to the repository root, which
   is a different context and out of scope here.

   The three-arity form takes an already-computed rendered-source, for a
   caller that needs the same render for something else."
  ([project site-ctx] (render project site-ctx (rendered-source project)))
  ([_project site-ctx {:keys [source body-html toc-html]}]
   (let [content (case source
                   nil          (str "<h1>" (:site-title site-ctx) "</h1><p>No documentation yet.</p>")
                   :guide-index (prefix-guide-links body-html)
                   body-html)]
     (selmer/render-file "generic-home.html"
                         (merge site-ctx
                                {:page    "home"
                                 :content content
                                 ;; nil when there is no source document or it has no
                                 ;; h2/h3. Selmer reads nil as falsy, so {% if toc %}
                                 ;; skips the block.
                                 :toc     toc-html})))))
