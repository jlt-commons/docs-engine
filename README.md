# docs-engine

The static-site generator behind jlt-commons project documentation. A project
writes markdown; this turns it into a site that GitHub Pages serves.

It is a small babashka program. The only external dependency is `markdown-clj`,
fetched on first run.

## The idea

A project keeps its own docs, its own config and its own homepage. The engine
keeps the rendering. Nothing about a project lives in this repository, which
matters in an organization where the person maintaining a project is usually not
the person maintaining the engine.

Each project's CI checks this repo out at a pinned tag, builds, and deploys to
that project's own Pages site. No repository ever pushes into another, so no
cross-repo tokens exist to leak.

## Onboarding a project

Give the project a `docs/` directory:

```
your-project/
  docs/
    site.edn            # config, all keys optional
    guide/
      index.md          # pinned first in the nav
      other-page.md
    templates/          # optional, only if you want a bespoke homepage
      home.html
    demos/              # optional, any assets your pages reference
```

Then `docs/site.edn`:

```clojure
{:base-path   "/your-project"
 :title       "your-project"
 :description "One sentence. It becomes the meta description and the footer."
 :asset-dirs  ["demos"]
 :home-template "home.html"}
```

**`:base-path` is the one that bites.** A project site is served at
`jlt-commons.github.io/your-project/`. Leave the base path out and every
stylesheet, script and nav link resolves against the *organization* site
instead. The page still renders, wearing the wrong clothes, which is what makes
it easy to ship. `site.core-test` covers it.

Leave `:base-path` as `""` only for a site served at a domain root.

| Key | Default | What it does |
|---|---|---|
| `:base-path` | `""` | Prefix for every URL the engine emits |
| `:title` | the README's first H1, else the directory name | Site title and brand |
| `:description` | the title | Meta description and footer line |
| `:github-url` | `git remote get-url origin`, as https | The nav's GitHub link |
| `:asset-dirs` | none | Directories under `docs/` copied into the build verbatim |
| `:templates-dir` | `templates` | Where project templates live, relative to `docs/` |
| `:home-template` | none, so the generic homepage | A file in the templates dir |

`:asset-dirs` is an allowlist, and each entry names one directory directly under
`docs/`. No separators, no leading dot, no `..`. This decides what gets copied
into a published site, so a project that could write `["../../.ssh"]` would
publish it.

## Building

```bash
bb test                      # the test suite
bb build ../your-project     # generate ../your-project/_site/
bb serve ../your-project     # build, then serve, honouring :base-path
bb serve ../your-project 4000
bb clean ../your-project     # delete the build output
```

`_site/` is generated. Add it to the project's `.gitignore`.

`bb serve` mounts the build at the project's configured `:base-path`, so the
local URL matches the deployed one. A project with `:base-path "/your-project"`
previews at `http://localhost:3000/your-project/`, and the server prints that
full URL when it starts. A root-hosted project is served at `/` as before.

That matters because the base path is baked into every generated URL. Serving
the build at the server's root instead would render the homepage unstyled with
every link dead, which is a confusing way to discover that your site is fine.

## Homepages

Ship no template and the engine renders `docs/guide/index.md` (falling back to
`README.md`) into a plain, readable page. That is enough for most projects, and
onboarding one needs nothing but markdown.

Ship `docs/templates/home.html` and it is used instead. Write it as a Selmer
template extending the engine's chrome:

```html
{% extends "base.html" %}
{% block title %}your-project{% endblock %}
{% block body-class %}home{% endblock %}
{% block content %}
  <h1>...</h1>
  <a href="{{site-base}}/guide/index.html">Read the guide</a>
  <img src="{{site-base}}/demos/thing.gif">
{% endblock %}
```

Every internal URL goes through `{{site-base}}`. A bare `/guide/index.html` in a
project template points at the organization site.

Available variables: `site-title`, `site-brand`, `site-tagline`, `site-github-url`,
`site-base`, `site-docs-href`.

A project template overrides any engine template of the same name, so a bespoke
`404.html` or `docs.html` works the same way. Overriding `base.html` means
inheriting none of the engine's later fixes to it, so prefer a block override
where one will do.

## The default Contributing page

Every project's guide gets a `guide/contributing.html`, in the nav right
after `index.md`, even without writing one. It comes from the engine's own
`resources/content/contributing.md`, on the same footing as `base.html` or
`404.html`: shared chrome, not a project's content, which is the point —
"how to open an issue against a jlt-commons project" is the same answer
everywhere, so it lives in one place instead of N copies that drift.

A project with something project-specific to say (a note on known-broken
areas, its own escalation path, anything beyond the shared etiquette)
writes its own `docs/guide/contributing.md`. That file is discovered the
same way any other guide page is, and having one there replaces the
engine's default outright rather than merging with it.

This is the general shape for any future default page: the engine's
`discover-doc-ids` adds its own basenames to a project's guide-dir root
group (never a nested subdirectory) for names the project has not written
itself, and `doc-source` resolves each one to the project's file if it
exists, else the engine's bundled default. `contributing.md` is the only
one today.

## Markdown

Standard markdown through `markdown-clj`, plus:

- **Syntax highlighting** for Clojure and Scheme, via a vendored highlight.js.
- **Mermaid diagrams.** A ```` ```mermaid ```` fence becomes a rendered diagram,
  themed to match the reader's light or dark setting.
- **Links between guide pages** are rewritten from `.md` to `.html`, including
  across subdirectories.
- **Long reference pages** get their `<h2>` sections wrapped in collapsible
  `<details>`, with anchors, in-page find and printing all handled.

## Publishing

The project's own workflow does the deploying. See
[`raylib-jlt`](https://github.com/jlt-commons/raylib-jlt/blob/main/.github/workflows/site.yml)
for a working one to copy: it checks this repo out at a tag, builds, asserts the
output is not empty, and deploys to Pages from `main` only.

Pin a tag rather than tracking `main`, so a change here cannot break a project's
docs build without someone choosing it.

## Where this came from

A trimmed port of a private single-author engine, reduced to one site and given
base-path support for the organization site
([`jlt-commons.github.io`](https://github.com/jlt-commons/jlt-commons.github.io)),
then extracted here and generalized: project-supplied config and templates, asset
directories, a generic homepage, and mermaid.

The organization site still carries its own copy of the earlier port. Moving it
onto this engine is worth doing and has not been done yet.
