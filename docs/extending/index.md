---
title: Extending
nav_order: 7
has_children: true
description: "Embed the engine, talk to a server from your own code, add effect kinds and renderers, and understand the internals."
---

# Extending

Putting Valem *inside* something else — as a library, behind your own API, or with your own effect
kinds and renderers.

| Page | Covers |
|---|---|
| [Embed Valem in your project](embedding.md) | Which module to depend on, and driving a model in-process. |
| [Client SDKs](client-sdks.md) | The typed Java and TypeScript clients over a running server. |
| [Architecture overview](architecture.md) | Component map, data flow, and the key design decisions. |
| [Reactive engine internals](reactive-engine.md) | The dependency graph, the propagation algorithm, the state layer. |

## The seams

Valem is layered so you can enter at the level you need and nothing above it comes along:

| Layer | Module | Framework |
|---|---|---|
| Pure engine — compile a spec, mutate, derive, constrain | `valem-core` | none |
| Orchestration — the use cases every front end shares | `valem-service` | none |
| View evaluation — `viewDefinition` → `EvaluatedView` | `valem-view` | none |
| HTTP/WS layer — headless, embeddable in your own Boot app | `valem-api` | Spring Boot |
| Runnable deployable | `valem-web` | Spring Boot |

Two extension points are designed to be used from **outside** the repository, with no fork and no
edits to core:

- **Custom effect kinds.** A pure `EffectKind` (discovered by `ServiceLoader`) plus a shell-side
  `EffectExecutor` bean, gated by the `valem.effects.kinds.enabled` list — a drop-in jar. The
  `valem-effects-noop` module is a working reference implementation. See
  [Effects]({% link model-guide/effects.md %}).
- **Custom renderers.** `EvaluatedView` is plain JSON over REST and the console, so a mobile,
  Angular, or terminal renderer implements the same contract the built-in React one does. See the
  [view system reference]({% link reference/view-system.md %}).

Persistence is pluggable the same way: the `valem-persistence-api` SPI (`SpecStore`, `StateStore`,
`BlobStore`, `AuditStore`) has memory, filesystem, Postgres, Mongo, Redis, and S3 adapters, and
nothing stops you writing another.

## Working on Valem itself

Building the repo, running the tests, and the module-by-module map live in the
[repository README]({{ site.gh_repo }}#readme) and `CLAUDE.md`. The two pages in this chapter —
[architecture](architecture.md) and [reactive engine internals](reactive-engine.md) — are the
as-built description of how the runtime works inside.
