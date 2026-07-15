---
title: Documentation index
nav_exclude: true
search_exclude: true
sitemap: false
description: "GitHub-facing map of the Valem documentation tree."
---

# Valem Documentation

Index of the documentation tree, for people and AI agents working in this repository.

This directory is **both** the docs tree and the Jekyll source of the published site —
**<https://vlad-public-code.github.io/org.json-kula.valem/>**. If you just want to *read* the
docs, the site is the comfortable way (nav, search, rendered diagrams); the files here are the
source of truth either way. This README is not part of the site navigation — the site's front
door is [index.md](index.md).

> **Scope.** These docs describe the **as-built, open** Valem runtime: `reference/` and
> `architecture/` document how the shipped system works; `guides/` are task-oriented how-tos.
> Nothing here is a proposal or a plan.

---

## Start here by task

**Evaluate Valem / run it for the first time:**
- [guides/getting-started.md](guides/getting-started.md) — run the console, backend, and UI from source
- [getting-started.md](getting-started.md) — use Valem as a Maven dependency in your own project
- [guides/examples-gallery.md](guides/examples-gallery.md) — ready-to-run model specs (start with the insurance-quote model)

**Integrate against a running server:**
- [reference/api-reference.md](reference/api-reference.md) — REST, WebSocket, console protocol (single source of truth)
- [guides/client-sdks.md](guides/client-sdks.md) — typed TypeScript + Java clients
- [guides/composition-and-branching.md](guides/composition-and-branching.md) — links between models, branching, promotion

**Author or generate specs** (human or LLM):
- [reference/model-spec-format.md](reference/model-spec-format.md) — the ModelSpec format (canonical)
- [guides/effects.md](guides/effects.md) — built-in effect executors + writing a custom effect kind
- [guides/generating-specs-with-llm.md](guides/generating-specs-with-llm.md) — LLM-driven generation
- [reference/llm-prompts.md](reference/llm-prompts.md) — exact prompts sent to the LLM

**Drive Valem from an AI agent session:**
- [guides/mcp-server.md](guides/mcp-server.md) — the MCP server: 16 tools + spec-format resources over stdio
- `CLAUDE.md` (repo root) — build commands, module map, and coding conventions for agents working on this codebase

**Deploy and operate:**
- [guides/deployment-and-operations.md](guides/deployment-and-operations.md) — run modes, persistence, blobs
- [reference/configuration.md](reference/configuration.md) — every `valem.*` property (single source of truth)
- [reference/security-model.md](reference/security-model.md) — auth, effect egress/SSRF guard, limits

**Understand or change the runtime:**
- [architecture/overview.md](architecture/overview.md) — component map, data flow, key design decisions
- [architecture/reactive-engine.md](architecture/reactive-engine.md) — dependency graph + reactive algorithm internals
- [glossary.md](glossary.md) — canonical definitions of core terms

---

## 60-second start

No server, no config — the console app runs a model straight from stdin:

```bash
mvn install -pl valem-core,valem-service -q && mvn package -pl valem-console -q
printf '%s\n' \
  '{"cmd":"create-model","spec":{"id":"order","version":"1.0.0","schema":{},"derivations":[{"path":"$.total","expr":"subtotal + tax"}]}}' \
  '{"cmd":"mutate","id":"order","mutations":{"$.subtotal":100,"$.tax":8}}' \
  '{"cmd":"get-state","id":"order"}' \
  | java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
# → {"ok":true,"result":{"subtotal":100,"tax":8,"total":108}}   (total derived reactively)
```

Zero-setup alternative: the [live sandbox](https://valem.onrender.com/) — describe a domain in
plain language, watch an LLM generate a ModelSpec, then mutate fields and see it react.

---

## Full map

```
docs/                          ← doc tree + Jekyll source of the published site
  README.md                    ← this index (GitHub-facing; excluded from site nav)
  glossary.md                  ← canonical definitions of core terms
  libraries.md                 ← third-party libraries and what each is used for

  guides/                      ← task-oriented
    getting-started.md         ← run console / backend / UI from source
    examples-gallery.md        ← ready-to-run specs
    effects.md                 ← built-in effect executors + custom effect kinds
    client-sdks.md             ← TS + Java clients
    mcp-server.md              ← drive Valem over MCP
    composition-and-branching.md
    generating-specs-with-llm.md
    deployment-and-operations.md

  reference/                   ← authoritative, as-built
    model-spec-format.md       ← the ModelSpec format (single source of truth)
    api-reference.md           ← REST + WebSocket + console
    configuration.md           ← all valem.* properties
    view-system.md             ← view definitions + evaluator + renderer
    llm-prompts.md             ← prompt structure and repair loop
    security-model.md          ← auth, effect egress/SSRF, blob/rate limits

  architecture/                ← how it works inside
    overview.md
    reactive-engine.md

  index.md, getting-started.md,        ← site-only pages: home + quick paths
  running-the-api.md,                     (introductions that link into the
  running-the-mcp-server.md,              canonical guides/reference docs)
  model-spec.md

  _config.yml, Gemfile,        ← site plumbing (Jekyll + just-the-docs);
  _includes/, 404.html            deployed by .github/workflows/pages.yml
```

---

## Working on these docs

Rules for anyone — human or agent — editing this tree:

- **One topic, one home.** Each topic has a single canonical doc; everything else links to it.
  Spec format → `reference/model-spec-format.md`; API → `reference/api-reference.md`;
  config → `reference/configuration.md`. Never duplicate their content elsewhere.
- **As-built only.** `reference/` and `architecture/` describe the shipped system. Proposals,
  plans, and reviews do not belong in this repository.
- **Every page needs front matter** (`title`, `description`; pages inside `guides/`,
  `reference/`, `architecture/` also need `parent` + `nav_order`) — the site nav is built
  from it.
- **Links:** use plain relative `.md` links between docs (they work on GitHub and are converted
  to page links on the site). A link to anything *outside* `docs/` (source files, module
  directories) must be an absolute GitHub URL, or it will 404 on the site. The site-only pages
  at the top level use `{% raw %}{% link %}{% endraw %}` tags instead.
- **Publishing:** pushing changes under `docs/**` to `main` triggers
  `.github/workflows/pages.yml`, which builds and deploys the site. To preview locally:
  `cd docs && bundle install && bundle exec jekyll serve`.

## See also

- [Live sandbox](https://valem.onrender.com/)
- [Source repository](https://github.com/vlad-public-code/org.json-kula.valem)
- [tracked-json](https://vlad-public-code.github.io/org.json-kula.tracked-json/) — `JsonPointer`-tracking Jackson `JsonNode` wrapper with JSONPath (RFC 9535) and JSON Patch (RFC 6902); one of Valem's two JSON foundations
- [jsonata-jvm-compiler](https://vlad-public-code.github.io/org.json-kula.jsonata-jvm-compiler/) — compiles JSONata expressions to native Java classes at runtime; evaluates every Valem expression
