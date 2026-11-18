(ns site.serve
  "The local preview server: the one function that actually binds a port.
   site.core builds the Ring handler; this namespace binds it with
   ring-chez.adapter, which runs over jolt's own sockets. No task other
   than `serve` requires this namespace."
  (:require [ring-chez.adapter :as adapter]
            [site.core :as core]))

(defn serve!
  "Builds, then serves output-dir at http://localhost:<port><base-path>
   until interrupted."
  [project port-str]
  (core/generate! project)
  (let [port       (Integer/parseInt (or port-str "3000"))
        output-dir (:output-dir project)
        base       (core/base-path (:base-path project))]
    ;; ring-chez binds 127.0.0.1 by default; :host says so explicitly, since
    ;; this is a local-only preview server.
    (adapter/run-server (core/make-static-handler output-dir base)
                        {:port port :host "127.0.0.1"})
    (println (str "Serving " output-dir " at http://localhost:" port base "/"))
    @(promise)))
