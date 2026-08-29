(ns site.markdown-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [site.markdown :as md]))

(deftest slugify-plain-text
  (is (= "the-abi-problem" (md/slugify "The ABI problem"))))

(deftest slugify-strips-inline-code-and-decodes-underscore-entity
  ;; markdown-clj renders inline code's underscores as the literal text
  ;; "&#95;" (an HTML entity, not a real underscore character) — verified
  ;; against markdown-clj 1.11.4. A slugify that only strips tags (and
  ;; doesn't decode numeric entities) leaks the "95" into the slug.
  ;; The underscore SURVIVES the slug (GitHub keeps _ and -), so this still
  ;; proves the entity was decoded: an undecoded &#95; would leak "95" and
  ;; give "why--force95load-is-mandatory". The double hyphen is GitHub's:
  ;; the stripped backtick left a space on each side of "-force_load".
  (is (= "why--force_load-is-mandatory"
         (md/slugify "Why <code>-force&#95;load</code> is mandatory"))))

(deftest slugify-strips-inline-code-and-decodes-asterisk-entity
  ;; same class of leak, via &#42; for a literal "*" inside inline code
  ;; (e.g. dynamic-var names like *out*/*err*) — verified against a real
  ;; guide heading (guide-page.md).
  ;; Undecoded, &#42; would leak "42" ("out42"). Decoded, the * is dropped as
  ;; punctuation. The double hyphen before "is" is GitHub's: the whole
  ;; "…)" token vanished and left the spaces that flanked it.
  (is (= "jolt-gotcha-binding-out-err--is-a-no-op"
         (md/slugify "Jolt gotcha: <code>(binding [*out* *err*] …)</code> is a NO-OP"))))

(deftest render-markdown-basic
  (is (= "<h1>Hi</h1><p>Some <em>text</em>.</p>"
         (md/render-markdown "# Hi\n\nSome *text*."))))

(deftest render-markdown-dash-hr-renders-as-real-hr-not-phantom-heading
  ;; markdown-clj 1.11.4 mis-renders a bare --- as an empty <h2></h2>
  ;; (nested inside the preceding <p>) instead of <hr/> — verified
  ;; directly against the library. unwrap-hard-wraps canonicalizes every
  ;; hr line to *** before markdown-clj ever sees it.
  (is (= "<p>para</p><hr/><h2>Next</h2>"
         (md/render-markdown "para\n\n---\n\n## Next"))))

(deftest render-markdown-dash-hr-at-document-start-renders-as-real-hr
  ;; the same bug silently drops a leading --- entirely (no <hr/>, no
  ;; phantom heading) when nothing precedes it.
  (is (= "<hr/><p>text</p>"
         (md/render-markdown "---\n\ntext"))))

;; ---- intraword underscore emphasis (markdown-clj bug workaround) ----
;; markdown.common/make-separator (the "_" -> <i> transformer) partitions
;; purely on runs of the separator char, with no GFM/CommonMark "intraword"
;; flanking check at all — verified directly against markdown-clj 1.11.4's
;; source. Any snake_case identifier in plain text (not inside inline code
;; or a fenced block, both already protected by markdown-clj's own code
;; handling) gets its first _..._ pair silently turned into <i>...</i>,
;; eating two of its underscores. Real trigger: a project's own README H1
;; is its snake_case package name (example_native_lib) rendered
;; through this exact pipeline as the site homepage's <h1>.

(deftest render-markdown-intraword-underscore-does-not-become-italics
  (is (= "<h1>example&#95;native&#95;lib</h1>"
         (md/render-markdown "# example_native_lib"))))

(deftest render-markdown-intraword-underscore-in-plain-paragraph-text
  ;; same bug, mid-sentence, not just in a heading -- proves the fix isn't
  ;; heading-specific.
  (is (= "<p>see resources/md/guide/foo&#95;bar&#95;baz.md for details.</p>"
         (md/render-markdown "see resources/md/guide/foo_bar_baz.md for details."))))

(deftest render-markdown-spaced-underscore-emphasis-still-works
  ;; regression guard: genuine word-boundary emphasis (not intraword) must
  ;; still render as <i>, unaffected by the intraword-only escape.
  (is (= "<p>line <i>emphasis</i> here</p>"
         (md/render-markdown "line _emphasis_ here"))))

(deftest render-markdown-intraword-underscore-inside-inline-code-untouched
  ;; regression guard: inline code spans are already protected by
  ;; markdown-clj's own escaping (&#95;) -- the new preprocessing step must
  ;; skip them, not double-escape or otherwise alter their content.
  (is (= "<p>code span <code>snake&#95;case&#95;var</code> stays</p>"
         (md/render-markdown "code span `snake_case_var` stays"))))

(deftest render-markdown-intraword-underscore-inside-fenced-block-untouched
  ;; regression guard: fenced code block content is already protected by
  ;; markdown-clj's own codeblock handling -- must stay byte-identical.
  (is (= "<pre><code>snake&#95;case&#95;in&#95;fence = 1\n</code></pre>"
         (md/render-markdown "```\nsnake_case_in_fence = 1\n```"))))

(deftest slugify-and-title-round-trip-an-intraword-underscore-heading
  ;; the end-to-end path that actually surfaced the bug: a real project
  ;; README's H1 (its snake_case package name) rendered as the site
  ;; homepage's page title and heading id.
  (let [html (md/render-doc-page "# example_native_lib\n\nBody text.")]
    (is (= "example_native_lib" (:title html)))))

(deftest assign-heading-ids-gives-every-heading-a-clean-id
  (let [html (md/assign-heading-ids (md/render-markdown "# Title\n\n## Why `-force_load` is mandatory\n\n## The build script"))]
    (is (str/includes? html "<h1 id=\"title\">"))
    (is (str/includes? html "<h2 id=\"why--force_load-is-mandatory\">"))
    (is (str/includes? html "<h2 id=\"the-build-script\">"))))

(deftest assign-heading-ids-dedupes-repeated-slugs-within-a-page
  (let [html (md/assign-heading-ids (md/render-markdown "## See also\n\n## See also"))]
    (is (str/includes? html "<h2 id=\"see-also\">"))
    (is (str/includes? html "<h2 id=\"see-also-2\">"))))

(deftest extract-toc-headings-skips-h1-keeps-h2-and-h3
  (let [idded (md/assign-heading-ids
               (md/render-markdown "# Page title\n\n## Section A\n\n### Sub A1\n\n## Section B"))
        heads (md/extract-toc-headings idded)]
    (is (= [{:level 2 :id "section-a" :text "Section A"}
            {:level 3 :id "sub-a1" :text "Sub A1"}
            {:level 2 :id "section-b" :text "Section B"}]
           heads))))

(deftest render-toc-html-nests-h3-under-preceding-h2
  (let [toc (md/render-toc-html [{:level 2 :id "a" :text "A"}
                                 {:level 3 :id "a1" :text "A1"}
                                 {:level 3 :id "a2" :text "A2"}
                                 {:level 2 :id "b" :text "B"}])]
    (is (str/includes? toc "<li><a href=\"#a\">A</a><ul><li><a href=\"#a1\">A1</a></li><li><a href=\"#a2\">A2</a></li></ul></li>"))
    (is (str/includes? toc "<li><a href=\"#b\">B</a></li>"))))

(deftest render-toc-html-nil-for-no-headings
  (is (nil? (md/render-toc-html []))))

(deftest mermaidify-converts-fenced-mermaid-block-only
  (let [html (md/render-markdown "```mermaid\nflowchart LR\n  a --> b\n```\n\n```clojure\n(+ 1 2)\n```")
        out  (md/mermaidify html)]
    (is (str/includes? out "<pre class=\"mermaid\">flowchart LR"))
    (is (not (str/includes? out "<code class=\"mermaid\">")))
    (is (str/includes? out "<code class=\"clojure\">"))))

(deftest rewrite-doc-links-md-to-html-bare-and-with-anchor
  ;; verified: markdown-clj renders link hrefs with SINGLE quotes
  ;; (href='...'), not double quotes.
  (let [html (md/render-markdown "[a](other-page.md) and [b](symbol-extraction.md#some-anchor)")
        out  (md/rewrite-doc-links html)]
    (is (str/includes? out "href='other-page.html'"))
    (is (str/includes? out "href='symbol-extraction.html#some-anchor'"))
    (is (not (str/includes? out ".md'")))))

(deftest rewrite-doc-links-leaves-a-slash-containing-link-untouched
  ;; a link with '/' in its target (e.g. a cross-repo relative path like
  ;; guide-page.md's real link to ../../../sample-project/docs/guide/
  ;; mcp-extensions.md) is NOT a same-site internal doc link — rewriting
  ;; its .md -> .html would disguise it as one even though it still 404s.
  ;; Left exactly as markdown-clj rendered it.
  (let [html (md/render-markdown
              "[ext](../../../sample-project/docs/guide/mcp-extensions.md) and [bare](other-page.md)")
        out  (md/rewrite-doc-links html)]
    (is (str/includes? out "href='../../../sample-project/docs/guide/mcp-extensions.md'"))
    (is (str/includes? out "href='other-page.html'"))))

(deftest rewrite-nested-doc-links-matches-flat-rewrite-doc-links-byte-for-byte
  ;; For a root-level doc linking to other root-level docs (the shape
  ;; every project without nested subdirectories has), the nested-aware
  ;; rewriter must produce IDENTICAL output to plain rewrite-doc-links —
  ;; this is what makes it safe to use uniformly (see site.core/render-all-docs).
  (let [html (md/render-markdown "[a](other-page.md) and [b](symbol-extraction.md#some-anchor)")
        flat (md/rewrite-doc-links html)
        nested ((md/rewrite-nested-doc-links "index.md") html)]
    (is (= flat nested))
    (is (str/includes? nested "href='other-page.html'"))
    (is (str/includes? nested "href='symbol-extraction.html#some-anchor'"))))

(deftest rewrite-nested-doc-links-cross-directory-descends-and-climbs
  ;; A doc one level deep linking to a sibling subdirectory's page —
  ;; a real nested-project shape (section-one/02-...
  ;; linking to ../section-two/01-first-page.md).
  (let [html (md/render-markdown "[first](../section-two/01-first-page.md)")
        out  ((md/rewrite-nested-doc-links "section-one/02-second-page.md") html)]
    (is (str/includes? out "href='../section-two/01-first-page.html'"))))

(deftest rewrite-nested-doc-links-same-directory-sibling
  (let [html (md/render-markdown "[third](03-third-page.md)")
        out  ((md/rewrite-nested-doc-links "section-one/02-second-page.md") html)]
    (is (str/includes? out "href='../section-one/03-third-page.html'"))))

(deftest rewrite-nested-doc-links-root-to-subdirectory
  (let [html (md/render-markdown "[section0](section-zero/index.md)")
        out  ((md/rewrite-nested-doc-links "index.md") html)]
    (is (str/includes? out "href='section-zero/index.html'"))))

(deftest rewrite-nested-doc-links-still-leaves-cross-repo-links-untouched
  ;; A '..' walking above guide-dir itself (own doc is one level deep,
  ;; target climbs out three levels into a sibling repo) still resolves
  ;; to nil and is left completely untouched — the nested rewriter keeps
  ;; the same 'honest dead link' philosophy for genuinely external refs.
  (let [html (md/render-markdown
              "[ext](../../../sample-project/docs/guide/mcp-extensions.md)")
        out  ((md/rewrite-nested-doc-links "section-one/02-second-page.md") html)]
    (is (str/includes? out "href='../../../sample-project/docs/guide/mcp-extensions.md'"))))

(deftest render-doc-page-full-pipeline
  (let [md-text (str "# Query API\n\n"
                     "See [struct-by-value-shim.md](struct-by-value-shim.md).\n\n"
                     "## The API\n\n"
                     "```mermaid\nflowchart LR\n  a --> b\n```\n")
        {:keys [title toc-html body-html]} (md/render-doc-page md-text)]
    (is (= "Query API" title))
    (is (str/includes? toc-html "<a href=\"#the-api\">The API</a>"))
    (is (str/includes? body-html "href='struct-by-value-shim.html'"))
    (is (str/includes? body-html "<pre class=\"mermaid\">"))))

(deftest render-doc-page-nil-toc-when-only-h1
  (is (nil? (:toc-html (md/render-doc-page "# Just a title\n\nNo sections here.")))))

(deftest extract-title-decodes-entities-from-inline-code
  ;; markdown-clj renders inline code's underscores as literal &#95; entity text.
  ;; extract-title must decode these entities so the returned title is clean plain text,
  ;; not entity-laden text that would double-escape when Selmer renders it into the nav.
  (is (= "The self-contained dylib (-force_load)"
         (:title (md/render-doc-page "# The self-contained dylib (`-force_load`)\n\nBody text.")))))

;; ---- unwrap-hard-wraps ----
;; resources/md/guide/*.md is hard-wrapped at ~78 cols (synced from a
;; sibling project, never hand-edited here). markdown-clj is line-oriented
;; and mishandles paragraphs split across physical lines. These tests pin
;; the two concrete failure shapes plus the no-regression cases, using the
;; REAL wrapping style (not just single-line synthetic fixtures).

(deftest unwrap-hard-wraps-rejoins-a-bold-span-split-across-a-wrap
  ;; the closing ** landed on the next physical line in the source; without
  ;; unwrapping, markdown-clj never closes the span and emits literal **.
  (let [joined (md/unwrap-hard-wraps "all keyed by a **file\npath** for lookup.")]
    (is (= "all keyed by a **file path** for lookup." joined))
    (is (str/includes? (md/render-markdown joined) "<strong>file path</strong>"))))

(deftest unwrap-hard-wraps-treats-a-plus-prefixed-wrap-as-prose-continuation
  ;; a coincidental wrap starting with "+" mid-sentence (prev line is
  ;; ordinary prose, no blank line before it) must NOT become a new list
  ;; item — this is the exact shape of guide-page.md's real wrap bug.
  (let [text   "it's `a` + `b` + `c`\n+ `d` + `e`, all keyed."
        joined (md/unwrap-hard-wraps text)]
    (is (= "it's `a` + `b` + `c` + `d` + `e`, all keyed." joined))
    (is (not (str/includes? (md/render-markdown joined) "<ul>")))))

(deftest unwrap-hard-wraps-keeps-a-tight-list-as-separate-items
  ;; genuine sibling list items (no blank line between them, both at column
  ;; 0) must NOT be merged into one item just because they're adjacent.
  (let [text (str "1. A thin C **shim** flattens the API.\n"
                  "2. A **self-contained dylib** folds the core in\n"
                  "   (needed because of X).\n"
                  "3. **Node lifecycle** is manual.\n")
        html (md/render-markdown text)]
    (is (= 3 (count (re-seq #"<li>" html))))
    (is (str/includes? html "<strong>shim</strong>"))
    (is (str/includes? html "<strong>self-contained dylib</strong>"))
    (is (str/includes? html "<strong>Node lifecycle</strong>"))))

(deftest unwrap-hard-wraps-does-not-nest-an-indented-plus-wrap-under-its-own-item
  ;; index.md's real bug: a list item's own wrapped continuation happens to
  ;; be indented AND start with "+" — that must stay part of the SAME item,
  ;; not become a nested sub-list (markdown-clj's default line-oriented
  ;; behavior on this input).
  (let [text (str "- ✅ [a.md](a.md) — `bb symbols`\n"
                  "  + `bb refs <name>`: does the thing.\n")
        html (md/render-markdown text)]
    (is (= 1 (count (re-seq #"<li>" html))))
    (is (not (str/includes? html "<ul><li><ul>")))))

(deftest unwrap-hard-wraps-noop-on-already-single-line-content
  ;; the preprocessing step must not alter content that has no hard-wraps —
  ;; pinned directly (see also: all pre-existing tests in this file, which
  ;; exercise render-markdown/render-doc-page on single-line fixtures and
  ;; stay green after unwrap-hard-wraps was wired into render-markdown).
  (let [text "# Hi\n\nSome *text*.\n\n- a\n- b\n"]
    (is (= text (md/unwrap-hard-wraps text)))))

;; ---- collapse-long-sections -------------------------------------------

(defn- doc-with-sections
  "An already-id'd page body with `n` <h2> sections, each body `body-chars`
   long — the shape collapse-long-sections consumes (post-assign-heading-ids)."
  [n body-chars]
  (apply str "<h1>Title</h1><p>intro</p>"
         (for [i (range n)]
           (str "<h2 id=\"s" i "\">Section " i "</h2>"
                "<p>" (apply str (repeat body-chars "x")) "</p>"))))

(deftest collapse-long-sections-leaves-short-pages-untouched
  ;; Below collapse-min-long-sections (5) the page reads as prose, and
  ;; collapsing would only hide it. Byte-identical output is the contract.
  (let [html (doc-with-sections 4 2000)]
    (is (= html (md/collapse-long-sections html)))))

(deftest collapse-long-sections-leaves-many-SHORT-sections-untouched
  ;; Section COUNT alone must not trigger it: 20 one-line sections are a
  ;; glossary, and collapsing each hides a single line behind a toggle.
  ;; The count rule is paired with a total-size floor for exactly this.
  (let [html (doc-with-sections 20 40)]
    (is (= html (md/collapse-long-sections html)))))

(deftest collapse-long-sections-triggers-on-many-medium-sections
  ;; The second trigger: no single section reaches the long threshold, but
  ;; there are enough of them, carrying enough total content, that the page
  ;; reads as an index. glitter's examples.md is the motivating case.
  ;; 9 x 1100 clears the 9000-char floor while each section stays under
  ;; the 1500-char long-section threshold, so this exercises the SECOND
  ;; trigger and not the first.
  (let [html (doc-with-sections 9 1100)]
    (is (str/includes? (md/collapse-long-sections html) "doc-section"))))

(deftest collapse-long-sections-wraps-when-page-is-a-reference
  (let [out (md/collapse-long-sections (doc-with-sections 6 2000))]
    (is (= 6 (count (re-seq #"<details class=\"doc-section\">" out))))
    (is (= 6 (count (re-seq #"</details>" out))))))

(deftest collapse-long-sections-preserves-every-heading-id
  ;; TOC anchors point at these ids; losing or rewriting one silently
  ;; breaks every link into the page.
  (let [out (md/collapse-long-sections (doc-with-sections 6 2000))]
    (doseq [i (range 6)]
      (is (str/includes? out (str "<h2 id=\"s" i "\">"))))))

(deftest collapse-long-sections-keeps-the-h1-preamble-outside-any-details
  ;; Content before the first <h2> is the page title and intro — it must
  ;; stay visible, not get swallowed into the first collapsed section.
  (let [out (md/collapse-long-sections (doc-with-sections 6 2000))]
    (is (str/starts-with? out "<h1>Title</h1><p>intro</p><details"))))

(deftest collapse-long-sections-loses-no-body-content
  ;; Every section's body survives the wrap — the failure this guards is a
  ;; greedy regex eating a section body into a neighbouring wrapper.
  (let [html (doc-with-sections 6 2000)
        out  (md/collapse-long-sections html)]
    (is (= (count (re-seq #"x" html)) (count (re-seq #"x" out))))))

(deftest render-doc-page-print-html-is-never-collapsed
  ;; A closed <details> prints nothing, so the print path must get the
  ;; linear rendering. Uses a source doc long enough to trigger collapsing.
  (let [src (apply str "# Title\n\n"
                   (for [i (range 6)]
                     (str "## Section " i "\n\n" (str/join " " (repeat 320 "word")) "\n\n")))
        {:keys [body-html print-html]} (md/render-doc-page src)]
    (is (str/includes? body-html "<details class=\"doc-section\">"))
    (is (not (str/includes? print-html "<details")))
    (is (str/includes? print-html "<h2 id=\"section-0\">"))))

(deftest slugify-drops-a-dot-rather-than-hyphenating-it
  ;; A heading naming a source file is the common case, and the old slugify
  ;; turned the dot into a hyphen ("orbit-clj-..."), so an anchor that worked
  ;; on github.com 404'd on the generated site. GitHub deletes the dot.
  (is (= "orbitclj-the-first-live-reactive-area-demo"
         (md/slugify "<code>orbit.clj</code>: the first live <code>reactive-area</code> demo"))))

(deftest slugify-drops-an-apostrophe-rather-than-hyphenating-it
  ;; Same class, via a possessive: the old slugify produced "glitter-s".
  (is (= "ticking-glitters-state-atom-costs-a-full-view-recompute-not-just-a-frame"
         (md/slugify "Ticking glitter&#39;s state atom costs a full view recompute, not just a frame"))))

(deftest slugify-keeps-hyphens-and-underscores
  (is (= "reactive-area" (md/slugify "reactive-area")))
  (is (= "gl_area_smokeclj" (md/slugify "<code>gl_area_smoke.clj</code>"))))
