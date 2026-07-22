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

> **Scope.** These docs describe the **as-built, open** Valem runtime. `reference/` documents the
> interfaces as shipped, `model-guide/` explains how they behave, `deployment/` and `extending/`
> cover running and embedding it. Nothing here is a proposal or a plan.

---

## The six chapters

| Chapter | For | Contents |
|---|---|---|
| [getting-started/](getting-started/index.md) | First contact | [what-is-valem.md](getting-started/what-is-valem.md), [sandbox.md](getting-started/sandbox.md), [quickstart.md](getting-started/quickstart.md), [connect-your-agent.md](getting-started/connect-your-agent.md) |
| [usage-scenarios/](usage-scenarios/index.md) | "Does this fit my project?" | [agent-state.md](usage-scenarios/agent-state.md), [rules-and-calculations.md](usage-scenarios/rules-and-calculations.md), [workflows-and-effects.md](usage-scenarios/workflows-and-effects.md), [model-driven-ui.md](usage-scenarios/model-driven-ui.md), [examples-gallery.md](usage-scenarios/examples-gallery.md) |
| [model-guide/](model-guide/index.md) | Authoring models | [anatomy.md](model-guide/anatomy.md), [reactive-pipeline.md](model-guide/reactive-pipeline.md), [effects.md](model-guide/effects.md), [views.md](model-guide/views.md), [composition-and-branching.md](model-guide/composition-and-branching.md), [generating-specs-with-llm.md](model-guide/generating-specs-with-llm.md) |
| [reference/](reference/index.md) | Looking things up | [model-spec-format.md](reference/model-spec-format.md), [api-reference.md](reference/api-reference.md), [mcp-tools.md](reference/mcp-tools.md), [llm-prompts.md](reference/llm-prompts.md), [view-system.md](reference/view-system.md) |
| [deployment/](deployment/index.md) | Running it | [web-api.md](deployment/web-api.md), [mcp-server.md](deployment/mcp-server.md), [configuration.md](deployment/configuration.md), [operations.md](deployment/operations.md), [security-model.md](deployment/security-model.md) |
| [extending/](extending/index.md) | Building on it | [embedding.md](extending/embedding.md), [client-sdks.md](extending/client-sdks.md), [architecture.md](extending/architecture.md), [reactive-engine.md](extending/reactive-engine.md) |

Standalone: [glossary.md](glossary.md) · [libraries.md](libraries.md)

---

## Start here by task

**Evaluate Valem / run it for the first time:**
- [getting-started/what-is-valem.md](getting-started/what-is-valem.md) — the idea in five minutes
- [getting-started/sandbox.md](getting-started/sandbox.md) — the hosted demo, no install
- [getting-started/quickstart.md](getting-started/quickstart.md) — run the console, backend, and UI from source
- [usage-scenarios/examples-gallery.md](usage-scenarios/examples-gallery.md) — ready-to-run model specs

**Integrate against a running server:**
- [reference/api-reference.md](reference/api-reference.md) — REST, WebSocket, console protocol (single source of truth)
- [extending/client-sdks.md](extending/client-sdks.md) — typed TypeScript + Java clients
- [model-guide/composition-and-branching.md](model-guide/composition-and-branching.md) — links between models, branching, promotion

**Author or generate specs** (human or LLM):
- [model-guide/anatomy.md](model-guide/anatomy.md) — the sections of a spec and the two path dialects
- [reference/model-spec-format.md](reference/model-spec-format.md) — the ModelSpec format (canonical)
- [model-guide/effects.md](model-guide/effects.md) — built-in effect executors + writing a custom effect kind
- [model-guide/generating-specs-with-llm.md](model-guide/generating-specs-with-llm.md) — LLM-driven generation
- [reference/llm-prompts.md](reference/llm-prompts.md) — exact prompts sent to the LLM

**Drive Valem from an AI agent session:**
- [deployment/mcp-server.md](deployment/mcp-server.md) — install and run the MCP server (3 modes)
- [reference/mcp-tools.md](reference/mcp-tools.md) — the tools, resources, and protocol details
- [getting-started/connect-your-agent.md](getting-started/connect-your-agent.md) — pairing an agent with a browser tab
- `CLAUDE.md` (repo root) — build commands, module map, and coding conventions for agents working on this codebase

**Deploy and operate:**
- [deployment/web-api.md](deployment/web-api.md) — run the Spring Boot deployable
- [deployment/operations.md](deployment/operations.md) — run modes, persistence, blobs
- [deployment/configuration.md](deployment/configuration.md) — every `valem.*` property (single source of truth)
- [deployment/security-model.md](deployment/security-model.md) — auth, effect egress/SSRF, limits

**Understand or change the runtime:**
- [extending/embedding.md](extending/embedding.md) — depend on the modules from your own project
- [extending/architecture.md](extending/architecture.md) — component map, data flow, key design decisions
- [extending/reactive-engine.md](extending/reactive-engine.md) — dependency graph + reactive algorithm internals
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
  index.md                     ← site home
  glossary.md                  ← canonical definitions of core terms
  libraries.md                 ← third-party libraries and what each is used for

  getting-started/             ← first contact
    what-is-valem.md           ← the idea in five minutes
    sandbox.md                 ← the hosted demo, end to end
    quickstart.md              ← run console / backend / UI from source
    connect-your-agent.md      ← MCP browser pairing: agent + human, one session

  usage-scenarios/             ← where Valem fits in a project
    agent-state.md             ← structured state for AI agents
    rules-and-calculations.md  ← pricing / quoting / eligibility as data
    workflows-and-effects.md   ← effect-driven workflows and integrations
    model-driven-ui.md         ← UIs generated from the model
    examples-gallery.md        ← ready-to-run specs

  model-guide/                 ← how models work (explanatory)
    anatomy.md                 ← spec sections; addresses vs expressions
    reactive-pipeline.md       ← the mutation pipeline and its guarantees
    effects.md                 ← built-in executors + custom effect kinds
    views.md                   ← viewDefinition and its evaluation
    composition-and-branching.md
    generating-specs-with-llm.md

  reference/                   ← authoritative, as-built interfaces
    model-spec-format.md       ← the ModelSpec format (single source of truth)
    api-reference.md           ← REST + WebSocket + console
    mcp-tools.md               ← MCP tools, resources, protocol
    llm-prompts.md             ← prompt structure and repair loop
    view-system.md             ← view definitions + evaluator + renderer

  deployment/                  ← running it
    web-api.md                 ← the valem-web Spring Boot deployable
    mcp-server.md              ← the valem-mcp jar: embedded / remote / paired
    configuration.md           ← all valem.* properties
    operations.md              ← run modes, persistence, blobs, audit
    security-model.md          ← auth, effect egress/SSRF, blob/rate limits

  extending/                   ← building on it
    embedding.md               ← depend on the modules from your own project
    client-sdks.md             ← TS + Java clients
    architecture.md            ← component map, data flow, decisions
    reactive-engine.md         ← engine internals

  _config.yml, Gemfile,        ← site plumbing (Jekyll + just-the-docs);
  _includes/, 404.html            deployed by .github/workflows/pages.yml
```

---

## Working on these docs

Rules for anyone — human or agent — editing this tree:

- **One topic, one home.** Each topic has a single canonical doc; everything else links to it.
  Spec format → `reference/model-spec-format.md`; API → `reference/api-reference.md`;
  MCP tools → `reference/mcp-tools.md`; config → `deployment/configuration.md`. Never duplicate
  their content elsewhere.
- **Explain in the guide, specify in the reference.** `model-guide/` and `usage-scenarios/` say
  *why* and *when*; `reference/` says *exactly what*. If a page starts listing every field, it
  belongs in `reference/`.
- **As-built only.** Proposals, plans, and reviews do not belong in this repository.
- **Every page needs front matter** (`title`, `description`; every page inside a chapter directory
  also needs `parent` + `nav_order`) — the site nav is built from it. The chapter landing pages are
  the `index.md` files, which carry `has_children: true`.
- **Moved a page?** Add its old URL to `redirect_from:` (the `jekyll-redirect-from` plugin is
  enabled) so existing links keep working.
- **Links:** use plain relative `.md` links between docs (they work on GitHub and are converted
  to page links on the site). A link to anything *outside* `docs/` (source files, module
  directories) must be an absolute GitHub URL, or it will 404 on the site. Cross-chapter links may
  use `{% raw %}{% link %}{% endraw %}` tags, which Jekyll resolves from the docs root.
- **Publishing:** pushing changes under `docs/**` to `main` triggers
  `.github/workflows/pages.yml`, which builds and deploys the site. To preview locally:
  `cd docs && bundle install && bundle exec jekyll serve`.

## See also

- [Live sandbox](https://valem.onrender.com/)
- [Source repository](https://github.com/vlad-public-code/org.json-kula.valem)
- [tracked-json](https://vlad-public-code.github.io/org.json-kula.tracked-json/) — `JsonPointer`-tracking Jackson `JsonNode` wrapper with JSONPath (RFC 9535) and JSON Patch (RFC 6902); one of Valem's two JSON foundations
- [jsonata-jvm-compiler](https://vlad-public-code.github.io/org.json-kula.jsonata-jvm-compiler/) — compiles JSONata expressions to native Java classes at runtime; evaluates every Valem expression
