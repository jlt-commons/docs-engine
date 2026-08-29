(ns site.markdown
  (:require [clojure.string :as str]
            [markdown.core :as md]))

(defn unescape-entities
  "Decodes HTML entities markdown-clj emits inside rendered text — both
   named (&quot; &lt; &gt; &amp;) and numeric (&#NN;, e.g. &#95; for _,
   &#42; for *). Numeric decode is generic (not a hardcoded whitelist):
   a slugify that skips it leaks the entity's digit code into the slug
   (verified against markdown-clj 1.11.4 — see markdown_test.clj)."
  [s]
  (-> s
      (str/replace #"&#(\d+);" (fn [[_ code]] (str (char (Integer/parseInt code)))))
      (str/replace "&quot;" "\"")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")))

(defn strip-tags [s]
  (str/replace s #"<[^>]+>" ""))

(defn slugify
  "Plain-text slug for a heading id, matching GitHub's algorithm: decode
   entities, strip any inline markup (e.g. <code>), lowercase, trim, DROP
   punctuation and symbols, then turn each remaining whitespace character
   into a hyphen. Letters, digits, `-` and `_` survive.

   Parity with GitHub is the point, not prettiness. An anchor written in a
   source .md file has to resolve both on github.com and on the generated
   site, and the only way that holds for every heading is to compute the id
   the same way GitHub does. Two consequences look like bugs and are not:

     - Punctuation is REMOVED, not hyphenated, so `orbit.clj` slugs to
       `orbitclj` and `glitter's` to `glitters`. Collapsing it to a hyphen
       instead was the old behaviour, and it silently broke every anchor
       into a heading containing a `.` or an apostrophe.
     - Punctuation with a space on BOTH sides leaves the spaces behind, so
       `err*] ...) is` yields a double hyphen. GitHub does exactly this."
  [text]
  (-> text
      unescape-entities
      strip-tags
      str/lower-case
      str/trim
      (str/replace #"[^\p{L}\p{N}\s_-]" "")
      (str/replace #"\s" "-")))

(defn- fence-line? [line]
  (boolean (re-find #"^\s*```" line)))

(defn- escape-intraword-underscores-in-plain-text
  [text]
  (str/replace text #"(?<=[A-Za-z0-9])_(?=[A-Za-z0-9])" "\\\\_"))

(defn- escape-intraword-underscores-in-line
  "Escapes intraword underscores in `line`, skipping inline code spans
   (`` `...` ``) — their content must stay byte-for-byte verbatim, and
   markdown-clj's own code handling already protects it."
  [line]
  (->> (re-seq #"`[^`]*`|[^`]+|`" line)
       (map (fn [token]
              (if (str/starts-with? token "`")
                token
                (escape-intraword-underscores-in-plain-text token))))
       (str/join)))

(defn escape-intraword-underscores
  "Backslash-escapes a `_` flanked by word characters on both sides (e.g.
   the `_` in `example_native_lib`), so markdown-clj's underscore-
   emphasis transformer doesn't render it as <i>...</i>.

   markdown.common/make-separator (the `_` -> <i> transformer) partitions
   purely on runs of the separator character — no GFM/CommonMark
   \"intraword\" flanking check at all (verified directly against
   markdown-clj 1.11.4's source). Any snake_case identifier in plain text
   silently has its first _..._ pair turned into <i>...</i>, eating two
   of its underscores. Real trigger: a project's README H1 is often its
   snake_case package name, rendered through this exact pipeline as the
   site homepage's <h1>.

   markdown-clj already honors a leading backslash as an escape for `_`
   (markdown.common/escaped-chars turns `\\_` into the literal entity
   `&#95;`, before any emphasis transformer runs) — this reuses that
   existing mechanism rather than inventing a new one. `render-markdown`
   already unescapes `&#95;`/decodes entities everywhere the result
   matters (slugify, extract-title), so callers see plain `_` again.

   Skips fenced code block lines entirely (their content must stay
   byte-for-byte verbatim) and skips inline code spans within non-fenced
   lines, for the same reason."
  [markdown-text]
  (let [lines (str/split markdown-text #"\n" -1)]
    (loop [lines lines in-fence? false out []]
      (if (empty? lines)
        (str/join "\n" out)
        (let [line (first lines)
              more (rest lines)]
          (cond
            in-fence?
            (recur more (not (fence-line? line)) (conj out line))

            (fence-line? line)
            (recur more true (conj out line))

            :else
            (recur more false (conj out (escape-intraword-underscores-in-line line)))))))))

(defn- heading-line? [line]
  (boolean (re-find #"^#" line)))

(defn- blockquote-line? [line]
  (boolean (re-find #"^\s*>" line)))

(defn- blockquote-content
  "Strips a blockquote line's leading `>` marker (and the ONE optional
   space right after it, if present — handles both `> text` and `>text`),
   returning just the quoted text."
  [line]
  (second (re-find #"^\s*>\s?(.*)$" line)))

(defn- table-line?
  "A GFM table row/separator — always starts with a literal `|` in this
   corpus's real tables (header rows, data rows, `|---|---|` separators).
   Deliberately narrower than \"any line containing |\": several guide
   docs have ordinary prose or mermaid-diagram lines with a `|` mid-line
   (e.g. `(fn [byte-index row col] -> chunk-string | nil)`), and those are
   NOT table rows — they still need normal hard-wrap unwrapping."
  [line]
  (boolean (re-find #"^\s*\|" line)))

(defn- hr-line? [line]
  (boolean (re-find #"^\s*(-{3,}|\*{3,}|_{3,})\s*$" line)))

(defn- list-marker-indent
  "Leading-whitespace count if `line` looks like a bullet (-, *, +) or
   ordered (N.) list marker, else nil. Requires whitespace right after the
   single marker char/dot so a bold-span opener like \"**text\" (no space
   after the first *) never false-matches."
  [line]
  (when-let [[_ indent] (re-find #"^(\s*)(?:[-*+]|\d+\.)\s" line)]
    (count indent)))

(defn unwrap-hard-wraps
  "The guide docs' source markdown is hard-wrapped at ~78 columns (a
   stylistic choice upstream, not something this repo changes). markdown-
   clj is line-oriented and mishandles paragraphs split across physical
   lines: a **bold** span whose closing `**` lands on the next physical
   line never closes (renders as literal `**`), and a wrapped continuation
   line that coincidentally starts with `-`/`*`/`+`/`N.` gets misparsed as
   a new list item (dropping the space between words at the join, e.g.
   \"is\" + \"the\" -> \"isthe\").

   This preprocessing step rejoins those hard-wrapped lines into single
   logical lines (joined with a space, not deleted) before handing the
   text to markdown-clj. Fenced code block content, headings, blank
   lines, and table rows are left completely untouched. Horizontal
   rules are recognized but NOT left untouched — every hr line
   (regardless of source character: `---`, `***`, `___`, any length
   >= 3) is rewritten to a canonical `***`, working around a
   markdown-clj 1.11.4 bug where a dash-based `---` renders as an
   empty `<h2></h2>` (nested inside the preceding `<p>` if one
   precedes it, or silently dropped entirely at the very start of a
   document) instead of `<hr/>`. `***`/`___` render correctly as
   `<hr/>` in every context tested. Blockquote CONTENT gets the exact same hard-wrap-join
   treatment as ordinary prose (a `**bold**`/`*em*` span split across
   wrapped `>`-prefixed lines has the identical unclosed-span bug) — only
   the `>` MARKER itself is treated as structural: consecutive
   `>`-prefixed lines belonging to the same quote are stripped of their
   markers, joined with a space like any other paragraph, and re-emitted
   as a single `> <joined text>` line (verified against markdown-clj
   1.11.4: a single-line blockquote renders <blockquote><p> with inline
   <strong>/<em> spans parsed correctly, same as any paragraph).

   The list-marker ambiguity is the subtle part: a `-`/`*`/`+`/`N.`-
   prefixed line is only a GENUINE new list item when it's preceded by a
   blank line, a heading/blockquote/table/hr boundary, the start of the
   document, or ANOTHER LIST ITEM AT THE SAME OR SHALLOWER INDENT. A
   marker-shaped line that's MORE indented than the enclosing item, or
   that directly follows ordinary prose with no blank line before it, is
   treated as a coincidental wrap and joined into the previous line as
   plain text instead (this is what real Markdown authoring looks like:
   genuine new lists start at a blank line or a sibling item, not
   mid-sentence). Verified against two real occurrences of this exact
   shape: guide-page.md's `+ \\`net.example.proj.symbols\\` + ...` (column 0,
   follows prose -> continuation) and index.md's indented
   `  + \\`bb refs <name> <path>\\`...` (follows its own list item's
   marker line, but MORE indented than it -> continuation, not a nested
   sub-list)."
  [markdown-text]
  (let [lines (str/split markdown-text #"\n" -1)]
    (loop [lines       lines
           in-fence?   false
           prev-tag    :start
           list-indent nil
           buffer      nil
           out         []]
      (if (empty? lines)
        (str/join "\n" (cond-> out buffer (conj buffer)))
        (let [line (first lines)
              more (rest lines)
              flush (cond-> out buffer (conj buffer))]
          (cond
            in-fence?
            (recur more (not (fence-line? line)) :fence-end nil nil (conj out line))

            (fence-line? line)
            (recur more true :fence-start nil nil (conj flush line))

            (str/blank? line)
            (recur more false :blank nil nil (conj flush line))

            (heading-line? line)
            (recur more false :heading nil nil (conj flush line))

            (blockquote-line? line)
            (let [content (str/trim (blockquote-content line))]
              (if (= prev-tag :blockquote)
                (recur more false :blockquote nil (str buffer " " content) out)
                (recur more false :blockquote nil (str "> " content) flush)))

            (table-line? line)
            (recur more false :table nil nil (conj flush line))

            (hr-line? line)
            ;; Canonicalize to *** regardless of the source character
            ;; (-/*/_): markdown-clj 1.11.4 mis-renders a dash-based ---
            ;; as an empty <h2></h2> (nested inside the preceding <p> if
            ;; one precedes it, or silently dropped entirely at doc
            ;; start) instead of <hr/> — verified directly against the
            ;; library. *** and ___ both render correctly as <hr/> in
            ;; every context tested, so rewriting to *** sidesteps the
            ;; dash-specific bug without touching hr-line?'s detection.
            (recur more false :hr nil nil (conj flush "***"))

            :else
            (let [marker-indent (list-marker-indent line)
                  genuine-list-item? (and marker-indent
                                          (not= prev-tag :prose)
                                          (or (not= prev-tag :list-item)
                                              (<= marker-indent list-indent)))]
              (if genuine-list-item?
                (recur more false :list-item marker-indent line flush)
                (recur more false
                       (if (= prev-tag :list-item) :list-item :prose)
                       (if (= prev-tag :list-item) list-indent nil)
                       (if buffer (str buffer " " (str/trim line)) line)
                       out)))))))))

(defn render-markdown
  "markdown -> HTML. Does NOT use markdown-clj's own :heading-anchors —
   its id-generation embeds literal entity text (&#95; etc.) in id
   attribute values, which round-trips (ids still match their hrefs) but
   looks broken and, worse, produces DIFFERENT ids than our own slugify
   would for the same text. We always assign our own ids afterward
   (assign-heading-ids) instead, over plain <h1>/<h2>/<h3> with no id.

   Runs escape-intraword-underscores then unwrap-hard-wraps first (see
   their docstrings) so snake_case identifiers don't get misread as
   emphasis and hard-wrapped source paragraphs/list-items don't trip
   markdown-clj's line-oriented parsing."
  [markdown-text]
  (md/md-to-html-string (-> markdown-text
                            escape-intraword-underscores
                            unwrap-hard-wraps)
                        :code-style (fn [lang] (str "class=\"" lang "\""))))

(defn assign-heading-ids
  "Adds a unique id= to every <h1>/<h2>/<h3>, computed from its OWN
   rendered text via slugify. Dedupes within a page by appending -2, -3…"
  [html]
  (let [seen (atom {})]
    (str/replace html #"(?s)<h([123])>(.*?)</h\1>"
                 (fn [[_ level inner]]
                   (let [base (slugify inner)
                         n    (get (swap! seen update base (fnil inc 0)) base)
                         id   (if (> n 1) (str base "-" n) base)]
                     (str "<h" level " id=\"" id "\">" inner "</h" level ">"))))))

(defn extract-toc-headings
  "[{:level :id :text}] for every h2/h3 in already-id'd html (h1 is the
   page title, shown by the page body itself, not part of the TOC).
   :text keeps markdown-clj's own entity escaping (e.g. \"&gt;\") — it's
   already safe to re-embed in new markup as-is; do not unescape it here."
  [html]
  (for [[_ level id inner] (re-seq #"(?s)<h([23]) id=\"([^\"]*)\">(.*?)</h\1>" html)]
    {:level (Integer/parseInt level) :id id :text (strip-tags inner)}))

(defn render-toc-html
  "Nests consecutive :level 3 headings under the :level 2 heading that
   precedes them, matching each doc's actual section structure."
  [headings]
  (when (seq headings)
    (loop [remaining headings
           out       []]
      (if (empty? remaining)
        (str "<ul class=\"toc\">" (str/join out) "</ul>")
        (let [{:keys [level id text]} (first remaining)]
          (if (= level 3)
            (recur (rest remaining)
                   (conj out (str "<li><a href=\"#" id "\">" text "</a></li>")))
            (let [subs      (take-while #(= 3 (:level %)) (rest remaining))
                  rest-item (drop (count subs) (rest remaining))
                  sub-html  (when (seq subs)
                              (str "<ul>"
                                   (str/join (for [s subs]
                                               (str "<li><a href=\"#" (:id s) "\">" (:text s) "</a></li>")))
                                   "</ul>"))]
              (recur rest-item
                     (conj out (str "<li><a href=\"#" id "\">" text "</a>" (or sub-html "") "</li>"))))))))))

(def ^:private collapse-section-threshold
  "A rendered <h2> section (heading + body HTML) longer than this many
   characters counts as \"long\" for collapse-long-sections."
  1500)

(def ^:private collapse-min-long-sections
  "How many long sections a page needs before ANY of them is collapsed.
   Below this, the page reads as prose and collapsing would only hide it;
   at or above it, the page is a reference you navigate, and a list of
   headings is more useful than a wall. gtk-widget-layer.md (34 sections)
   is the motivating case; a 3-section page is deliberately untouched."
  5)

(def ^:private collapse-min-sections
  "A page with this many top-level sections is an index you navigate even
   when no single section is long enough for collapse-section-threshold.
   glitter's examples.md (9 sections, ~9.9k of rendered body) is the
   motivating case; the long-section rule above misses it entirely."
  9)

(def ^:private collapse-min-total
  "...but section count alone is not enough. Twenty one-line sections are a
   glossary, and collapsing each of them hides a single line behind a
   toggle, which is worse than leaving it visible. Pair the count rule with
   a floor on total body size so only genuinely substantial pages qualify.

   Measured across all 50 configured projects: the long-section rule alone
   collapses 59 of 501 pages; adding this one takes it to 100 (20%). There
   is no tighter pair that still catches examples.md - it is a typical
   9-section page, not an outlier - so ~41 other pages is the cost of the
   feature, not a mistuned threshold."
  9000)

(defn collapse-long-sections
  "Wraps each <h2> section of an already-id'd page body in a <details>,
   but only on pages that are long enough to read as a reference (see
   collapse-min-long-sections). Returns html unchanged otherwise, so
   short pages keep their current shape exactly.

   The <h2> keeps its own id and moves INSIDE the <summary>, so every
   TOC anchor still resolves to the same element. Anchors that land in a
   collapsed section are opened by the page script in base.html; without
   that script the content is still present in the DOM, just closed.

   Runs after assign-heading-ids (it matches on the id attribute those
   add) and before mermaidify, whose <pre class=\"mermaid\"> rewrite is
   position-independent."
  [html]
  (let [chunks   (str/split html #"(?=<h2 id=\")")
        preamble (when-not (str/starts-with? (or (first chunks) "") "<h2 id=\"")
                   (first chunks))
        sections (if preamble (rest chunks) chunks)]
    (if (and (< (count (filter #(> (count %) collapse-section-threshold) sections))
                collapse-min-long-sections)
             (or (< (count sections) collapse-min-sections)
                 (< (reduce + 0 (map count sections)) collapse-min-total)))
      html
      (str preamble
           (str/join
            (for [s sections]
              (if-let [[_ heading body] (re-find #"(?s)^(<h2 id=\"[^\"]*\">.*?</h2>)(.*)$" s)]
                (str "<details class=\"doc-section\">"
                     "<summary>" heading "</summary>"
                     "<div class=\"doc-section-body\">" body "</div>"
                     "</details>")
                s)))))))

(defn mermaidify
  "Rewrites markdown-clj's fenced ```mermaid output
   (<pre><code class=\"mermaid\">...</code></pre>) into the
   <pre class=\"mermaid\">...</pre> shape mermaid.js's default .mermaid
   selector expects. Leaves every other fenced code block untouched."
  [html]
  (str/replace html #"(?s)<pre><code class=\"mermaid\">(.*?)</code></pre>"
               (fn [[_ content]] (str "<pre class=\"mermaid\">" content "</pre>"))))

(defn rewrite-doc-links
  "Rewrites bare same-directory doc-to-doc markdown links to .html.
   markdown-clj renders link hrefs with SINGLE quotes (verified against
   1.11.4) — href='other-page.md' or href='other-page.md#anchor'.

   The captured path must contain no '/' — every genuine internal
   guide-to-guide link in this project's content is a bare same-directory
   filename (verified: e.g. query-api.md, query-api.md#some-anchor).
   A link with a '/' in it (e.g. a cross-repo relative link like
   ../../../other-repo/docs/guide/other.md) is left COMPLETELY untouched:
   rewriting only its .md -> .html would make it LOOK like a valid
   same-site link when it actually points outside this site and will
   still 404 — dishonest disguise is worse than an honest dead link.
   Fixing the upstream content is out of scope (resources/md/ is synced,
   never hand-edited here)."
  [html]
  (str/replace html #"href='([^'/]+)\.md(#[^']*)?'" "href='$1.html$2'"))

(defn- resolve-relative-md-path
  "Resolves a '.'/'..'-bearing relative link's split path segments
   against the current doc's own directory segments (both relative to
   guide-dir). Pure path algebra, no filesystem access. Returns the
   resolved segment vector, or nil once a '..' would walk above
   guide-dir itself (i.e. the link points outside the guide tree
   entirely) — nil is sticky, so any further segments after that point
   stay nil too. Mirrors site.infra/resolve-relative-md-path, minus
   that fn's dual guide/infra section disambiguation (a guide-only
   link has only one destination tree to land in or fall outside of)."
  [current-dir-segs target-segs]
  (reduce (fn [acc seg]
            (cond
              (nil? acc)   nil
              (= seg ".")  acc
              (= seg "..") (if (empty? acc) nil (vec (butlast acc)))
              :else        (conj acc seg)))
          current-dir-segs
          target-segs))

(defn rewrite-nested-doc-links
  "Builds a (fn [html] html) link-rewriter (for
   site.markdown/render-doc-page's 2-arity form) for one guide doc at
   own-rel-path — its path relative to guide-dir, e.g.
   \"section-one/02-second-page.md\", or a bare filename like
   \"index.md\" at guide-dir's own root. Resolves every relative .md
   href against the doc's own directory and rewrites it to a
   browser-relative .html href that correctly walks back up to
   guide-dir root and down into the target's own directory (however
   deep either side is) — e.g. from section-one/, a link to
   ../section-two/01-first-page.md becomes
   ../section-two/01-first-page.html. An absolute URL, or a path
   that resolves outside guide-dir entirely (a genuine cross-repo
   reference), is left COMPLETELY untouched — same 'honest dead link
   beats a disguised one' philosophy as rewrite-doc-links and
   site.infra/infra-link-rewriter.

   For a doc at guide-dir's own root linking to another root-level doc
   (own-rel-path has no '/', target resolves with no '/') this produces
   BYTE-IDENTICAL output to rewrite-doc-links — e.g. other-page.md ->
   other-page.html, not /guide/other-page.html — so this fn is safe to
   use uniformly for every project, flat or nested; it is a strict
   generalization, not a project-specific alternate path. See
   site.core/render-all-docs, which now always builds this per doc-id
   rather than defaulting to plain rewrite-doc-links."
  [own-rel-path]
  (let [own-dir-segs (vec (butlast (str/split own-rel-path #"/")))]
    (fn [html]
      (str/replace
       html #"href='([^'#]+\.md)(#[^']*)?'"
       (fn [[whole path anchor]]
         (if (re-find #"^(https?://|/)" path)
           whole
           (let [target-segs (resolve-relative-md-path own-dir-segs (str/split path #"/"))
                 anchor      (or anchor "")]
             (if target-segs
               (let [dir-part  (butlast target-segs)
                     file-part (str/replace (last target-segs) #"\.md$" "")
                     ups       (repeat (count own-dir-segs) "..")
                     rel-path  (str/join "/" (concat ups dir-part [file-part]))]
                 (str "href='" rel-path ".html" anchor "'"))
               whole))))))))

(defn extract-title
  "Plain-text content of the page's own <h1> (post assign-heading-ids;
   the id on it is irrelevant here). nil if the doc has no h1. Decodes
   HTML entities (not just strips tags) — an H1 with inline code
   containing _ or * would otherwise leak literal &#95;/&#42; entity
   text into the title, which then double-escapes when Selmer renders
   it into the nav sidebar / <title> tag."
  [html]
  (when-let [[_ inner] (re-find #"(?s)<h1[^>]*>(.*?)</h1>" html)]
    (unescape-entities (strip-tags inner))))

(defn render-doc-page
  "Full pipeline: one markdown doc's source text -> everything site.core
   needs to place it on a page. body-html already contains the doc's own
   <h1> — callers must not render a second one.

   :body-html may have its <h2> sections wrapped in <details> (see
   collapse-long-sections). :print-html is the same content with that
   wrapping never applied — a closed <details> does not print its
   contents, so anything building a linear/printable document must use
   :print-html, not :body-html.

   link-rewriter defaults to rewrite-doc-links (bare same-directory
   filenames only — every docs/guide/*.md page's actual convention).
   Pass a different (fn [html] html) to handle a differently-shaped
   content tree's relative links (see site.infra/infra-link-rewriter,
   which resolves docs/infra/**'s nested './'/'../' links)."
  ([markdown-text] (render-doc-page markdown-text rewrite-doc-links))
  ([markdown-text link-rewriter]
   (let [html       (render-markdown markdown-text)
         idded      (assign-heading-ids html)
         headings   (extract-toc-headings idded)
         toc-html   (render-toc-html headings)
         linked     (link-rewriter idded)
         body-html  (mermaidify (collapse-long-sections linked))
         print-html (mermaidify linked)
         title      (extract-title idded)]
     {:title title :toc-html toc-html :body-html body-html :print-html print-html})))
