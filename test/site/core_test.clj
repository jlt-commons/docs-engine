(ns site.core-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.core :as core]))

(deftest base-path-normalizes-every-accepted-form
  (testing "root-hosted sites collapse to the empty string"
    (is (= "" (core/base-path nil)))
    (is (= "" (core/base-path "")))
    (is (= "" (core/base-path "   ")))
    (is (= "" (core/base-path "/")))
    (is (= "" (core/base-path "///"))))
  (testing "a project path always gains a leading slash and loses trailing ones"
    (is (= "/some-lib" (core/base-path "some-lib")))
    (is (= "/some-lib" (core/base-path "/some-lib")))
    (is (= "/some-lib" (core/base-path "/some-lib/")))
    (is (= "/some-lib" (core/base-path "/some-lib///")))))

(deftest site-context-exposes-the-base-path-to-templates
  (is (= "" (:site-base (core/site-context {:title "t" :description "d" :base-path ""}))))
  (is (= "/some-lib"
         (:site-base (core/site-context {:title "t" :description "d" :base-path "some-lib"})))))

(deftest nav-items-prefix-the-base-path
  (let [rendered {"intro.md" {:title "Intro" :slug "intro"}}
        ids      ["intro.md"]]
    (testing "root-hosted site is unchanged from the original engine"
      (is (= [{:href "/guide/intro.html" :title "Intro"}]
             (core/nav-items rendered ids ""))))
    (testing "project site is served under its own prefix"
      (is (= [{:href "/some-lib/guide/intro.html" :title "Intro"}]
             (core/nav-items rendered ids "/some-lib"))))))

(deftest docs-href-prefixes-the-base-path
  (let [dir (io/file "test" "fixtures" "bp-guide")]
    (fs/create-dirs dir)
    (spit (io/file dir "index.md") "# Index")
    (try
      (is (= "/guide/index.html" (core/docs-href {:guide-dir dir} "")))
      (is (= "/some-lib/guide/index.html" (core/docs-href {:guide-dir dir} "/some-lib")))
      (finally (fs/delete-tree dir)))))

(defn- build-fixture-site!
  "Builds a complete site into a temp dir and returns
   {:out :doc :home :site :index}. opts: :home-template? (ship a project
   homepage template), :assets? (ship an asset directory), :search (copied
   into the site map when given), :readme? (ship a README.md and no
   guide/index.md, so the README is the homepage source). :index is the
   parsed search-documents.json, or nil when none was written."
  ([base] (build-fixture-site! base {}))
  ([base {:keys [home-template? assets? diagram? mermaid-override readme? search no-h1?]}]
   (let [tmp       (fs/create-temp-dir {:prefix "jltc-site"})
         docs      (io/file (str tmp) "docs")
         guide     (io/file docs "guide")
         templates (io/file docs "templates")]
     (fs/create-dirs guide)
     (if readme?
       (spit (io/file (str tmp) "README.md") "# Readme Title\n\n## Usage\n\nRun it.\n")
       (spit (io/file guide "index.md")
             (if diagram?
               "# Intro\n\nHello.\n\n```mermaid\nflowchart LR\n  a --> b\n```\n"
               "# Intro\n\nHello.\n")))
     (spit (io/file guide "plain.md") "# Plain\n\nNo diagram here.\n")
     (when no-h1?
       (spit (io/file guide "noh1.md") "No heading here.\n\n## Sec\n\ntext\n"))
     (when home-template?
       (fs/create-dirs templates)
       (spit (io/file templates "home.html")
             (str "{% extends \"base.html\" %}\n"
                  "{% block content %}<p class=\"bespoke\">"
                  "<img src=\"{{site-base}}/media/x.gif\"></p>{% endblock %}\n")))
     (when assets?
       (fs/create-dirs (io/file docs "media"))
       (spit (io/file docs "media" "x.gif") "GIF89a"))
     (let [site (cond-> {:title "jlt-commons" :description "d" :github-url "https://example.invalid"
                         :base-path base
                         :guide-dir guide
                         :templates-dir templates
                         :output-dir (io/file (str tmp) "_site")
                         :home-template (when home-template? "home.html")
                         :asset-dirs (when assets? [(io/file docs "media")])}
                  readme?                  (assoc :readme-file (io/file (str tmp) "README.md"))
                  (some? search)           (assoc :search search)
                  (some? mermaid-override) (assoc :mermaid mermaid-override))]
       (core/generate! site)
       {:out   (:output-dir site)
        :site  site
        :index (let [f (io/file (:output-dir site) "search-documents.json")]
                 (when (fs/exists? f) (json/parse-string (slurp f))))
        :doc   (when-not readme? (slurp (io/file (:output-dir site) "guide" "index.html")))
        :plain (slurp (io/file (:output-dir site) "guide" "plain.html"))
        :home  (slurp (io/file (:output-dir site) "index.html"))}))))

(deftest root-hosted-build-uses-root-relative-asset-urls
  (let [{:keys [doc]} (build-fixture-site! "")]
    (is (str/includes? doc "href=\"/css/screen.css\""))
    (is (str/includes? doc "href=\"/guide/index.html\""))))

(deftest project-hosted-build-prefixes-every-url
  (let [{:keys [doc home]} (build-fixture-site! "/some-lib")]
    (testing "stylesheets resolve inside the project, not against the org site"
      (is (str/includes? doc "href=\"/some-lib/css/screen.css\""))
      (is (not (str/includes? doc "href=\"/css/screen.css\""))))
    (testing "nav links stay inside the project site"
      (is (str/includes? doc "href=\"/some-lib/guide/index.html\""))
      (is (str/includes? doc "href=\"/some-lib/\"")))
    (testing "the home page gets the same treatment"
      (is (str/includes? home "href=\"/some-lib/css/screen.css\"")))))

(deftest static-handler-serves-under-the-configured-base-path
  ;; serve! reuses generate!'s output, but generate! bakes /x into every
  ;; URL while the handler used to know nothing about it: the homepage
  ;; loaded at /, and everything it linked to 404'd under the prefix.
  (let [{:keys [out]} (build-fixture-site! "/x")
        handler       (core/make-static-handler out "/x")]
    (testing "a request for the base path plus a file resolves against output-dir"
      (is (= 200 (:status (handler {:uri "/x/index.html"})))))
    (testing "a request for the bare base path serves the homepage"
      (is (= 200 (:status (handler {:uri "/x/"})))))
    (testing "the same path without the prefix is not found"
      (is (= 404 (:status (handler {:uri "/index.html"})))))))

(deftest static-handler-does-not-swallow-a-sibling-of-the-base-path
  ;; strip-base-path requires uri to be exactly base, or base followed by
  ;; "/". Drop the "/" from that guard and a bare prefix match takes over.
  ;;
  ;; The input matters. Most near-misses are masked by the (subs rel 1)
  ;; on the next line, which assumes a leading slash: "/x-other" would
  ;; strip to "-other", then lose its first character, and 404 anyway.
  ;; "/x_index.html" is the shape that actually leaks: it strips to
  ;; "_index.html", the subs drops the underscore, and the handler serves
  ;; the real homepage at a uri that is not under the base path at all.
  (let [{:keys [out]} (build-fixture-site! "/x")
        handler       (core/make-static-handler out "/x")]
    (testing "the base path itself and paths under it resolve"
      (is (= 200 (:status (handler {:uri "/x"}))))
      (is (= 200 (:status (handler {:uri "/x/index.html"})))))
    (testing "a uri merely sharing the base as a string prefix does not"
      (is (= 404 (:status (handler {:uri "/x_index.html"})))))))

(deftest static-handler-is-unaffected-when-root-hosted
  (let [{:keys [out]} (build-fixture-site! "")
        handler       (core/make-static-handler out "")]
    (is (= 200 (:status (handler {:uri "/index.html"}))))
    (is (= 200 (:status (handler {:uri "/"}))))))

(deftest selected-nav-item-still-matches-after-prefixing
  ;; write-doc-page! computes active-href separately from nav-items. If the two
  ;; drift, every nav item silently renders unselected and the bug is cosmetic
  ;; enough to ship.
  (let [{:keys [doc]} (build-fixture-site! "/some-lib")]
    (is (str/includes? doc "class=\"selected\""))))

(deftest a-project-without-a-template-gets-the-generic-homepage
  ;; Onboarding a project should need markdown and nothing else.
  (let [{:keys [home]} (build-fixture-site! "/some-lib")]
    (is (str/includes? home "Hello."))
    (is (not (str/includes? home "class=\"bespoke\"")))))

(deftest a-project-template-is-rendered-and-still-extends-the-engine-base
  ;; The project's file lives outside the engine, so this covers the whole
  ;; staging mechanism: Selmer can neither render a string that extends,
  ;; nor take an absolute path.
  (let [{:keys [home]} (build-fixture-site! "/some-lib" {:home-template? true})]
    (testing "the project's own block rendered"
      (is (str/includes? home "class=\"bespoke\"")))
    (testing "and it inherited the engine's base.html chrome"
      (is (str/includes? home "site-nav"))
      (is (str/includes? home "/some-lib/css/screen.css")))
    (testing "with site-base interpolated inside the project's own markup"
      (is (str/includes? home "src=\"/some-lib/media/x.gif\"")))))

(deftest staged-templates-never-reach-the-published-output
  ;; Staging under output-dir would publish the engine's templates as part
  ;; of the site.
  (let [{:keys [out]} (build-fixture-site! "/some-lib" {:home-template? true})]
    (is (not (fs/exists? (io/file out ".templates"))))
    (is (not (fs/exists? (io/file out "base.html"))))))

(deftest configured-asset-dirs-are-copied-under-their-own-name
  (let [{:keys [out]} (build-fixture-site! "/some-lib" {:assets? true})]
    (is (fs/exists? (io/file out "media" "x.gif")))))

(deftest a-missing-asset-dir-warns-instead-of-failing-the-build
  ;; A project may generate assets from a pipeline that has not run in
  ;; this checkout. That should not take the docs down.
  (let [tmp   (fs/create-temp-dir {:prefix "jltc-site"})
        guide (io/file (str tmp) "docs" "guide")]
    (fs/create-dirs guide)
    (spit (io/file guide "index.md") "# Intro\n")
    (let [site {:title "t" :description "d" :base-path ""
                :guide-dir guide
                :output-dir (io/file (str tmp) "_site")
                :asset-dirs [(io/file (str tmp) "docs" "not-there")]}
          out  (with-out-str (core/generate! site))]
      (is (str/includes? out "not-there"))
      (is (fs/exists? (io/file (:output-dir site) "index.html"))))))

(deftest mermaid-is-vendored-and-loaded-only-where-a-diagram-exists
  ;; The bundle is 3.4 MB, around 450x a rendered page, so loading it on a
  ;; site with no diagrams is the regression this guards. The renderer
  ;; rewrites fences whether or not the bundle is present, so getting this
  ;; wrong in the other direction shows up as a diagram rendered as
  ;; unstyled source text rather than as an error.
  (let [{:keys [out doc plain]} (build-fixture-site! "/some-lib" {:diagram? true})]
    (testing "the bundle ships regardless, since some page may need it"
      (is (fs/exists? (io/file out "vendor" "mermaid" "mermaid.min.js"))))
    (testing "the page with a diagram loads it"
      (is (str/includes? doc "<pre class=\"mermaid\">"))
      (is (str/includes? doc "/some-lib/vendor/mermaid/mermaid.min.js"))
      (is (str/includes? doc "mermaid.initialize")))
    (testing "a page without one does not"
      (is (not (str/includes? plain "mermaid.min.js")))
      (is (not (str/includes? plain "mermaid.initialize"))))))

(deftest a-site-with-no-diagrams-anywhere-never-loads-mermaid
  (let [{:keys [doc plain home]} (build-fixture-site! "/some-lib")]
    (doseq [[label page] [["guide index" doc] ["plain page" plain] ["homepage" home]]]
      (is (not (str/includes? page "mermaid.min.js")) (str label " should not load mermaid")))))

(deftest a-bespoke-homepage-with-a-hand-written-diagram-loads-mermaid
  ;; A bespoke template's markup does not exist until it renders, and the
  ;; script tag is emitted by that same pass, so detection reads the
  ;; template's source instead. This is the case that would silently break.
  (let [tmp   (fs/create-temp-dir {:prefix "jltc-site"})
        docs  (io/file (str tmp) "docs")
        guide (io/file docs "guide")
        tpl   (io/file docs "templates")]
    (fs/create-dirs guide) (fs/create-dirs tpl)
    (spit (io/file guide "index.md") "# Intro\n")
    (spit (io/file tpl "home.html")
          "{% extends \"base.html\" %}\n{% block content %}<pre class=\"mermaid\">flowchart LR\n a-->b</pre>{% endblock %}\n")
    (let [site {:title "t" :description "d" :base-path "" :guide-dir guide
                :templates-dir tpl :home-template "home.html"
                :output-dir (io/file (str tmp) "_site")}]
      (core/generate! site)
      (let [home (slurp (io/file (:output-dir site) "index.html"))]
        (is (str/includes? home "mermaid.min.js"))
        (is (str/includes? home "mermaid.initialize"))))))

(deftest the-site-can-override-the-detection-in-both-directions
  ;; The escape hatch for a diagram arriving through an include the
  ;; detector cannot see, and for turning it off deliberately.
  (testing ":mermaid true forces it on where nothing was detected"
    (let [{:keys [plain]} (build-fixture-site! "" {:mermaid-override true})]
      (is (str/includes? plain "mermaid.min.js"))))
  (testing ":mermaid false forces it off even with a diagram present"
    (let [{:keys [doc]} (build-fixture-site! "" {:diagram? true :mermaid-override false})]
      (is (str/includes? doc "<pre class=\"mermaid\">"))
      (is (not (str/includes? doc "mermaid.min.js"))))))

(deftest mermaid-needed?-reads-the-site-override-before-the-sources
  (is (true?  (core/mermaid-needed? {} "<pre class=\"mermaid\">x</pre>")))
  (is (false? (core/mermaid-needed? {} "no diagram here")))
  (is (false? (core/mermaid-needed? {} nil)))
  (is (true?  (core/mermaid-needed? {:mermaid true} "no diagram here")))
  (is (false? (core/mermaid-needed? {:mermaid false} "<pre class=\"mermaid\">x</pre>"))))

;; A project that has never written its own Contributing page still gets
;; the engine's default one, so nothing about "how to engage with this
;; project" needs copying into every project that uses this engine.

(deftest a-project-with-no-contributing-page-gets-the-engine-default
  (let [{:keys [out]} (build-fixture-site! "/some-lib")
        contributing  (slurp (io/file out "guide" "contributing.html"))]
    (is (str/includes? contributing "Etiquette"))
    (is (str/includes? contributing "docs-engine"))))

(deftest the-default-contributing-page-is-in-nav-right-after-index
  ;; index.md is pinned first; among what is left, "contributing.md" sorts
  ;; alphabetically ahead of "plain.md", so this is also a sort-order check,
  ;; not just a presence check.
  (let [{:keys [doc]} (build-fixture-site! "/some-lib")]
    (is (str/includes? doc "href=\"/some-lib/guide/contributing.html\""))
    (let [index-at   (str/index-of doc "/guide/index.html")
          contrib-at (str/index-of doc "/guide/contributing.html")
          plain-at   (str/index-of doc "/guide/plain.html")]
      (is (< index-at contrib-at plain-at)))))

(deftest a-project-with-its-own-contributing-page-overrides-the-default
  (let [tmp   (fs/create-temp-dir {:prefix "jltc-site"})
        docs  (io/file (str tmp) "docs")
        guide (io/file docs "guide")]
    (fs/create-dirs guide)
    (spit (io/file guide "index.md") "# Intro\n")
    (spit (io/file guide "contributing.md") "# Contributing\n\nOur own house rules.\n")
    (let [site {:title "t" :description "d" :base-path "" :guide-dir guide
                :output-dir (io/file (str tmp) "_site")}]
      (core/generate! site)
      (let [contributing (slurp (io/file (:output-dir site) "guide" "contributing.html"))]
        (is (str/includes? contributing "Our own house rules."))
        (is (not (str/includes? contributing "Etiquette")))))))

(defn- hrefs [index] (map #(get % "href") index))

(deftest build-writes-a-parseable-search-index
  (let [{:keys [index]} (build-fixture-site! "/some-lib")]
    (is (seq index))
    (is (every? #(str/starts-with? % "/some-lib/") (hrefs index)))
    (is (some #(and (= "/some-lib/guide/plain.html" (get % "href"))
                    (= "Plain" (get % "heading")))
              index))))

(deftest a-guide-page-with-no-h1-builds-and-is-indexed-under-its-slug
  (let [{:keys [index]} (build-fixture-site! "/some-lib" {:no-h1? true})
        recs (filter #(str/starts-with? (get % "href") "/some-lib/guide/noh1.html") index)]
    (is (= 2 (count recs)))
    (is (every? #(= "noh1" (get % "title")) recs))))

(deftest search-false-writes-no-index
  (let [{:keys [out index]} (build-fixture-site! "/some-lib" {:search false})]
    (is (nil? index))
    (is (not (fs/exists? (io/file out "search-documents.json"))))))

(deftest bespoke-homepage-is-not-indexed
  (let [{:keys [index]} (build-fixture-site! "/some-lib" {:home-template? true})]
    (is (seq index))
    (is (not (some #{"/some-lib/"} (hrefs index))))))

(deftest readme-homepage-is-indexed
  (let [{:keys [index]} (build-fixture-site! "/some-lib" {:readme? true})
        by-href         (group-by #(get % "href") index)]
    (is (= "Readme Title" (get (first (get by-href "/some-lib/")) "heading")))
    (is (= "Run it." (get (first (get by-href "/some-lib/#usage")) "text")))))

(deftest guide-index-homepage-is-not-indexed-twice
  ;; guide/index.md renders at both / and /guide/index.html; indexing both
  ;; would return every hit for it twice.
  (let [{:keys [index]} (build-fixture-site! "/some-lib")]
    (is (not (some #{"/some-lib/"} (hrefs index))))
    (is (= 1 (count (filter #{"/some-lib/guide/index.html"} (hrefs index)))))))

(deftest index-is-byte-identical-across-builds
  (let [a (build-fixture-site! "/some-lib")
        b (build-fixture-site! "/some-lib")]
    (is (= (slurp (io/file (:out a) "search-documents.json"))
           (slurp (io/file (:out b) "search-documents.json"))))))

(deftest site-context-exposes-search
  (is (true? (:search (core/site-context {}))))
  (is (false? (:search (core/site-context {:search false})))))

(deftest nav-search-carries-base-pathed-urls
  (let [{:keys [doc home]} (build-fixture-site! "/some-lib")]
    (is (str/includes? doc "id=\"nav-search\""))
    (is (str/includes? doc "data-index=\"/some-lib/search-documents.json\""))
    (is (str/includes? doc "data-lunr=\"/some-lib/vendor/lunr/lunr.min.js\""))
    (is (str/includes? doc "src=\"/some-lib/js/search.js\""))
    (is (str/includes? home "id=\"nav-search\""))))

(deftest lunr-is-not-loaded-eagerly
  ;; search.js appends lunr on first use; a page that never searches
  ;; should not pay for the library.
  (let [{:keys [doc]} (build-fixture-site! "/some-lib")]
    (is (not (str/includes? doc "<script src=\"/some-lib/vendor/lunr/lunr.min.js\"")))))

(deftest search-false-renders-no-search-ui
  (let [{:keys [doc]} (build-fixture-site! "/some-lib" {:search false})]
    (is (not (str/includes? doc "nav-search")))
    (is (not (str/includes? doc "search.js")))))

(deftest search-assets-are-copied
  (let [{:keys [out]} (build-fixture-site! "")]
    (is (fs/exists? (io/file out "js" "search.js")))
    (is (fs/exists? (io/file out "vendor" "lunr" "lunr.min.js")))))
