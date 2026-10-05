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
