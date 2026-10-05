(ns site.search-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  (testing "the encoded string for 'ls ' contains a space character"
    (is (str/includes? (search/->json [{:text "ls "}]) " ")))
  (testing "keys keep insertion order irrelevant; compare parsed values"
    (is (= [{"text" "value"}]
           (json/parse-string (search/->json [{:text "value"}]))))))

(deftest json-rejects-non-string-values
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"values must be strings"
                        (search/->json [{:n 1}]))))
