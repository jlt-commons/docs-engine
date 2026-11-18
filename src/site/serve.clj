(ns site.serve
  "The local preview server's bb/JVM half: the one function that actually
   binds a port. site.core builds the Ring handler with no HTTP-server
   library involved at all; this namespace is the thin wrapper around
   org.httpkit.server that turns it into a running server.

   http-kit isn't available under jolt, so `serve` is bb-only; no other
   task requires this namespace."
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
