(ns site.core-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
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
   {:out :doc :home :site}. opts: :base, :home-template? (ship a project
   homepage template), :assets? (ship an asset directory)."
  ([base] (build-fixture-site! base {}))
  ([base {:keys [home-template? assets?]}]
   (let [tmp       (fs/create-temp-dir {:prefix "jltc-site"})
         docs      (io/file (str tmp) "docs")
         guide     (io/file docs "guide")
         templates (io/file docs "templates")]
     (fs/create-dirs guide)
     (spit (io/file guide "index.md") "# Intro\n\nHello.\n")
     (when home-template?
       (fs/create-dirs templates)
       (spit (io/file templates "home.html")
             (str "{% extends \"base.html\" %}\n"
                  "{% block content %}<p class=\"bespoke\">"
                  "<img src=\"{{site-base}}/media/x.gif\"></p>{% endblock %}\n")))
     (when assets?
       (fs/create-dirs (io/file docs "media"))
       (spit (io/file docs "media" "x.gif") "GIF89a"))
     (let [site {:title "jlt-commons" :description "d" :github-url "https://example.invalid"
                 :base-path base
                 :guide-dir guide
                 :templates-dir templates
                 :output-dir (io/file (str tmp) "_site")
                 :home-template (when home-template? "home.html")
                 :asset-dirs (when assets? [(io/file docs "media")])}]
       (core/generate! site)
       {:out  (:output-dir site)
        :site site
        :doc  (slurp (io/file (:output-dir site) "guide" "index.html"))
        :home (slurp (io/file (:output-dir site) "index.html"))}))))

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

(deftest mermaid-is-vendored-and-loaded
  ;; The renderer rewrites ```mermaid fences into <pre class="mermaid">
  ;; whether or not the bundle is present, so a missing bundle shows up as
  ;; a diagram rendered as unstyled source text rather than as an error.
  (let [{:keys [out doc]} (build-fixture-site! "/some-lib")]
    (is (fs/exists? (io/file out "vendor" "mermaid" "mermaid.min.js")))
    (is (str/includes? doc "/some-lib/vendor/mermaid/mermaid.min.js"))))
