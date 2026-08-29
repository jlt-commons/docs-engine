(ns site.project-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [site.project :as project]))

(defn- scratch-project!
  "A throwaway project directory. site-edn is written to docs/site.edn
   when given; files is a map of relative path -> content."
  ([site-edn] (scratch-project! site-edn {}))
  ([site-edn files]
   (let [dir (fs/file (fs/create-temp-dir {:prefix "jltc-project"}))]
     (fs/create-dirs (io/file dir "docs" "guide"))
     (when site-edn (spit (io/file dir "docs" "site.edn") (pr-str site-edn)))
     (doseq [[path content] files]
       (let [f (io/file dir path)]
         (io/make-parents f)
         (spit f content)))
     dir)))

(deftest a-project-with-no-config-still-resolves
  ;; docs/guide/*.md alone is a valid project.
  (let [dir (scratch-project! nil)
        p   (project/resolve-project dir)]
    (is (= "" (:base-path p)))
    (is (nil? (:home-template p)))
    (is (= [] (:asset-dirs p)))
    (is (= (str (io/file dir "docs" "guide")) (str (:guide-dir p))))
    (is (= (str (io/file dir "_site")) (str (:output-dir p))))))

(deftest the-title-falls-back-to-the-readme-h1
  (testing "an H1 in the README becomes the title"
    (let [dir (scratch-project! nil {"README.md" "# Some Library\n\nBody.\n"})]
      (is (= "Some Library" (:title (project/resolve-project dir))))))
  (testing "an H2 is not an H1"
    (let [dir (scratch-project! nil {"README.md" "## Not a title\n"})]
      (is (not= "Not a title" (:title (project/resolve-project dir))))))
  (testing "an explicit title wins over the README"
    (let [dir (scratch-project! {:title "Explicit"} {"README.md" "# From Readme\n"})]
      (is (= "Explicit" (:title (project/resolve-project dir)))))))

(deftest asset-dirs-resolve-under-docs
  (let [dir (scratch-project! {:asset-dirs ["demos" "screenshots"]})
        p   (project/resolve-project dir)]
    (is (= [(str (io/file dir "docs" "demos"))
            (str (io/file dir "docs" "screenshots"))]
           (mapv str (:asset-dirs p))))))

(deftest asset-dirs-reject-anything-that-escapes-docs
  ;; This decides what gets copied into a published site, so it is an
  ;; allowlist. A project that could name "../../.ssh" would publish it.
  (doseq [bad ["../secrets" "nested/dir" ".git" "a\\b" "" 42]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (project/resolve-project (scratch-project! {:asset-dirs [bad]})))
        (str "should have rejected " (pr-str bad)))))

(deftest ssh-remotes-become-browsable-links
  (is (= "https://github.com/jlt-commons/raylib-jlt"
         (project/ssh-remote->https "git@github.com:jlt-commons/raylib-jlt.git")))
  (is (= "https://github.com/jlt-commons/raylib-jlt"
         (project/ssh-remote->https "https://github.com/jlt-commons/raylib-jlt.git")))
  (testing "an unrecognized shape returns nil rather than a guess"
    (is (nil? (project/ssh-remote->https "ssh://weird/thing")))
    (is (nil? (project/ssh-remote->https nil)))))

(deftest the-templates-dir-is-configurable-but-defaults-to-templates
  (is (= "templates" (str (fs/file-name (:templates-dir (project/resolve-project (scratch-project! nil)))))))
  (is (= "layouts" (str (fs/file-name (:templates-dir (project/resolve-project
                                                       (scratch-project! {:templates-dir "layouts"}))))))))
