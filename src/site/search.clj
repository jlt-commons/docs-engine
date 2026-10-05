(ns site.search
  (:require [clojure.string :as str]
            [site.markdown :as md]))

(defn- escape-json-string [s]
  (str/replace s
               (re-pattern (str "[\"\\\\\\u0000-\\u001f" (char 0x2028) (char 0x2029) "]"))
               (fn [c]
                 (case c
                   "\"" "\\\""
                   "\\" "\\\\"
                   "\n" "\\n"
                   "\r" "\\r"
                   "\t" "\\t"
                   (format "\\u%04x" (int (first c)))))))

(defn ->json [docs]
  (str "["
       (str/join ","
                 (map (fn [doc]
                        (str "{"
                             (str/join ","
                                       (map (fn [[k v]]
                                              (when-not (string? v)
                                                (throw (ex-info "->json: values must be strings"
                                                                {:key k :value v})))
                                              (str "\"" (escape-json-string (name k))
                                                   "\":" "\"" (escape-json-string v) "\""))
                                            doc))
                             "}"))
                      docs))
       "]"))

(defn- plain-text
  "Rendered HTML -> single-line plain text: closing block tags and <br>
   become a space (so neighbouring cells/items stay separate words), then
   tags stripped, entities decoded, whitespace collapsed. Inline tags are
   removed without a space."
  [html]
  (-> html
      (str/replace #"(?i)</(?:p|li|td|th|tr|pre|blockquote|dt|dd|div|summary|h4|h5|h6)>|<br\s*/?>" " ")
      md/strip-tags md/unescape-entities (str/replace #"\s+" " ") str/trim))

(defn- heading-starts
  "Sorted positions of every <h2 id=\" and <h3 id=\" in html. Found with
   str/index-of, not a lookahead split (jolt's str/split disagrees with the
   JVM there; see split-before-h2-headings in site.markdown)."
  [html]
  (let [positions (fn [marker]
                    (loop [from 0 acc []]
                      (if-let [i (str/index-of html marker from)]
                        (recur (inc i) (conj acc i))
                        acc)))]
    (vec (sort (concat (positions "<h2 id=\"") (positions "<h3 id=\""))))))

(defn page-records
  "Splits one rendered page into search records: the page itself (intro text
   before the first h2/h3, h1 removed), then one record per h2/h3 in document
   order, each running to the next heading. h3 headings are prefixed with
   their h2 (\"Alpha › Sub\")."
  [{:keys [title href body-html]}]
  (let [starts (heading-starts body-html)
        intro  (subs body-html 0 (if (seq starts) (first starts) (count body-html)))
        page   {:title title :heading title :href href
                :text (plain-text (str/replace intro #"(?s)<h1[^>]*>.*?</h1>" ""))}
        ends   (conj (vec (rest starts)) (count body-html))]
    (loop [[s & more] starts
           [e & more-ends] ends
           h2 nil
           out [page]]
      (if-not s
        out
        (let [[m level id inner] (re-find #"(?s)^<h([23]) id=\"([^\"]*)\">(.*?)</h\1>"
                                          (subs body-html s))
              heading (plain-text inner)
              h2'     (if (= level "2") heading h2)]
          (recur more more-ends h2'
                 (conj out {:title title
                            :heading (if (and (= level "3") h2)
                                       (str h2 " › " heading)
                                       heading)
                            :href (str href "#" id)
                            :text (plain-text (subs body-html (+ s (count m)) e))})))))))

(defn index-documents
  "All pages' records, sorted by :href."
  [pages]
  (sort-by :href (mapcat page-records pages)))
