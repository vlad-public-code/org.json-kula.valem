---
title: What is Valem?
parent: Getting started
nav_order: 1
description: "The idea in five minutes: a declarative spec compiled into a live, reactive, explainable model."
---

# What is Valem?
{: .no_toc }

A deterministic, reactive computation runtime for structured data models — a spreadsheet-like
computation model for JSON, driven by an API, a library, an MCP server, or a console.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

An LLM is good at *describing* a domain and bad at *maintaining consistent state* over it. Ask it to
keep a quote, an order, or a loan application coherent across twenty turns and it will quietly drift:
a total that no longer matches its line items, a limit that was checked three messages ago, a field
silently dropped.

Valem closes that gap by moving the *rules* out of the conversation and into a runtime that enforces
them on every change.

## The idea

You hand Valem a **ModelSpec** — a declarative JSON document (usually LLM-generated) describing:

| Section | Meaning |
|---|---|
| `schema` | The shape of the writable, base document (JSON Schema). |
| `derivations` | Values computed from other values — `total = subtotal + tax`. |
| `constraints` | Invariants that must always hold — `total <= creditLimit`. |
| `metaDerivations` | Live per-field metadata: current min/max/required. |
| `effects` | What should reach the outside world when a condition becomes true. |
| `viewDefinition` | An optional, renderer-agnostic UI tree for the model. |

Valem compiles that into a live model. You then only ever mutate **base** fields; everything else is
the runtime's job:

```bash
POST /models/order/mutations   { "$.subtotal": 100, "$.tax": 8 }
GET  /models/order/state    →  { "subtotal": 100, "tax": 8, "total": 108 }
```

`total` was never written by anyone. It was derived — in dependency order, only for the fields the
change actually touched, with the constraint checked before the mutation was allowed to commit.

Think of a spreadsheet — cells, formulas, validation — but addressed by JSON Path, expressed in
[JSONata](https://jsonata.org), and driven over a REST/WebSocket API, an in-process library, an MCP
server, or a console.

The name says it: ***valem*** is the Estonian word for **"formula"**. It's pronounced **VAH-lem**
(IPA [ˈvɑlem]).

## What you get for describing a domain this way

- **Deterministic & replayable.** The pure core performs no I/O, so the same inputs always produce
  the same state and history replays without re-contacting the outside world.
- **Reactive by construction.** A dependency graph recomputes only what a change actually affects —
  not the whole document.
- **LLM-native.** Specs are JSON a model can generate, and Valem validates-and-repairs them in a
  loop. An agent can also author a spec itself and have Valem *verify* it before anything is
  committed.
- **Effectful, but governed.** HTTP calls, LLM calls, and timers are **declared** in the spec,
  executed post-commit behind an egress guard, and fold back into state as ordinary mutations —
  replay never re-runs I/O.
- **Explainable.** Every derivation and constraint evaluation is traceable, with an optional
  durable, tamper-evident audit trail.
- **Embeddable.** A pure-Java core with no framework lock-in, wrapped by an à-la-carte Spring layer.

## How you reach it

Valem is one engine behind four access surfaces, all backed by the same `ModelService`, so behaviour
is identical whichever you pick:

| Surface | Use it when |
|---|---|
| **REST + WebSocket** ([`valem-web`]({% link deployment/web-api.md %})) | A server your apps and UIs talk to; durable, shared state. |
| **MCP server** ([`valem-mcp`]({% link deployment/mcp-server.md %})) | An AI agent should own and inspect structured state during a session. |
| **Java library** ([embedding]({% link extending/embedding.md %})) | The engine runs in-process inside your own application. |
| **Console** ([quickstart]({% link getting-started/quickstart.md %})) | Scripts and pipelines: one JSON command per line on stdin. |

## Next

- [Try the sandbox](sandbox.md) — see it work before installing anything.
- [Quickstart](quickstart.md) — run it locally in a couple of minutes.
- [Glossary]({% link glossary.md %}) — canonical definitions of every term used above.
