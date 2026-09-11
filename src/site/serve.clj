(ns site.serve
  "The local preview server's bb/JVM half: the one function that actually
   binds a port. site.core builds the Ring handler with no HTTP-server
   library involved at all; this namespace is the thin wrapper around
   org.httpkit.server that turns it into a running server.

   Jolt never loads this file. site/serve.jolt is the jolt-native twin
   (ring-chez.adapter instead of http-kit) — jolt's require resolves
   <ns>.jolt before <ns>.clj (see the docs-engine README, \"Local preview
   server: two hosts, one namespace\"), so the two never collide, and
   babashka/JVM never see the .jolt file at all, since their own require
   only ever looks for .clj/.cljc."
  (:require [org.httpkit.server :as hk]
            [site.core :as core]))

(defn serve!
  "Builds, then serves output-dir at http://localhost:<port><base-path>
   until interrupted."
  [project port-str]
  (core/generate! project)
  (let [port       (Integer/parseInt (or port-str "3000"))
        output-dir (:output-dir project)
        base       (core/base-path (:base-path project))]
    (println (str "Serving " output-dir " at http://localhost:" port base "/"))
    ;; :ip "127.0.0.1" — local-only dev preview server; without an
    ;; explicit :ip, http-kit binds all network interfaces by default.
    (hk/run-server (core/make-static-handler output-dir base) {:port port :ip "127.0.0.1"})
    @(promise)))
