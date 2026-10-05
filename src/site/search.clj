(ns site.search
  (:require [clojure.string :as str]))

(defn- escape-json-string [s]
  (str/replace s
               (re-pattern "[\"\\\\\\u0000-\\u001f]")
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
