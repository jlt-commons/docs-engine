(ns site.search-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.markdown :as md]
            [site.search :as search]))

(deftest json-round-trips-through-a-real-parser
  (testing "escape sequences round-trip correctly"
    (doseq [s ["plain"
               "say \"hi\""
               "back\\slash"
               "a\nb\rc\td"
               "bell\u0007"
               "ls ps "
               "é → ü"
               ""]]
      (is (= [{"text" s}]
             (json/parse-string (search/->json [{:text s}]))))))
  (testing "empty seq produces empty array"
    (is (= "[]" (search/->json []))))
  (testing "U+2028 LINE SEPARATOR is escaped as \\u2028, not raw character"
    (let [out (search/->json [{:text (str "a" (char 0x2028) "b")}])]
      (is (str/includes? out "\\u2028"))
      (is (not (str/includes? out (str (char 0x2028)))))))
  (testing "U+2029 PARAGRAPH SEPARATOR is escaped as \\u2029, not raw character"
    (let [out (search/->json [{:text (str "a" (char 0x2029) "b")}])]
      (is (str/includes? out "\\u2029"))
      (is (not (str/includes? out (str (char 0x2029))))))))

(deftest json-rejects-non-string-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"values must be strings"
                        (search/->json [{:n 1}]))))

(defn- page [src]
  {:title "T" :href "/x/guide/p.html" :body-html (:body-html (md/render-doc-page src))})

(deftest intro-only-page-is-one-record
  (is (= [{:title "T" :heading "T" :href "/x/guide/p.html" :text "Just intro."}]
         (search/page-records (page "# T\n\nJust intro.\n")))))

(deftest empty-body-is-one-record
  (let [recs (search/page-records {:title "T" :href "/x/guide/p.html" :body-html ""})]
    (is (= 1 (count recs)))
    (is (= "" (:text (first recs))))))

(deftest h2-and-h3-records-carry-the-heading-path
  (let [recs (search/page-records
              (page "# T\n\nIntro.\n\n## Alpha\n\nA body.\n\n### Sub\n\nSub body.\n\n## Beta\n\nB body.\n"))]
    (is (= ["/x/guide/p.html" "/x/guide/p.html#alpha" "/x/guide/p.html#sub" "/x/guide/p.html#beta"]
           (map :href recs)))
    (is (= ["T" "Alpha" "Alpha \u203a Sub" "Beta"] (map :heading recs)))
    (is (= ["Intro." "A body." "Sub body." "B body."] (map :text recs)))))

(deftest duplicate-headings-keep-their-own-text
  (let [recs (search/page-records
              (page "# T\n\n## Alpha\n\nOne.\n\n## Alpha\n\nTwo.\n"))
        by-href (into {} (map (juxt :href :text)) recs)]
    (is (= "One." (get by-href "/x/guide/p.html#alpha")))
    (is (= "Two." (get by-href "/x/guide/p.html#alpha-2")))))

(deftest markup-and-entities-become-plain-text
  (let [recs (search/page-records
              (page "# Title &amp; co\n\n## Use `x_y` here\n\nSee <b>bold</b> &amp; `a_b`.\n"))
        h2 (second recs)]
    (is (= "Use x_y here" (:heading h2)))
    (is (= "See bold & a_b." (:text h2)))
    (doseq [r recs, k [:text :heading]]
      (is (not (str/includes? (get r k) "<")))
      (is (not (str/includes? (get r k) "&#"))))))

(deftest collapsed-sections-split-the-same
  (let [src (str "# T\n\nIntro.\n\n"
                 (str/join "\n\n"
                           (for [n (range 10)]
                             (str "## S" n "\n\n" (str/join " " (repeat 150 "wordy"))))))
        {:keys [body-html print-html]} (md/render-doc-page src)
        mk (fn [h] (search/page-records {:title "T" :href "/p.html" :body-html h}))]
    (is (str/includes? body-html "<details"))
    (is (not (str/includes? print-html "<details")))
    (is (= 11 (count (mk body-html))))
    (is (= (mk print-html) (mk body-html)))))

(deftest index-documents-sorts-by-href
  (let [recs (search/index-documents
              [{:title "B" :href "/b.html" :body-html "<p>b</p>"}
               {:title "A" :href "/a.html" :body-html "<p>a</p>"}])]
    (is (= "/a.html" (:href (first recs))))))

(deftest adjacent-blocks-stay-separate-words
  (let [src (str "# T\n\nalpha\n\nbeta\n\n* one\n* two\n\n"
                 "| gamma | delta |\n|-------|-------|\n| eps | zeta |\n| eta | theta |\n\n"
                 "## Sec\n\n* p1\n* q1\n\n#### Deep\n\nTxt\n\n```\n(+ 1 2)\n```\n")
        [intro sec] (search/page-records (page src))
        tokens #(set (str/split (:text %) #"\s+"))]
    (is (every? (tokens intro) ["alpha" "beta" "one" "two" "gamma" "delta" "eps" "zeta" "eta" "theta"]))
    (is (every? (tokens sec) ["p1" "q1" "Deep" "Txt"]))
    (is (str/includes? (:text sec) "(+ 1 2)"))))
