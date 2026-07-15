---
title: Home
nav_order: 1
description: "Valem — a deterministic reactive computation runtime for AI-generated structured data models."
permalink: /
---

# Valem
{: .fs-9 }

A deterministic, reactive computation runtime for AI-generated structured data models — a
spreadsheet-like computation model for JSON-based agent systems.
{: .fs-6 .fw-300 }

[Try the live sandbox]({{ site.sandbox_url }}){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 target="_blank" rel="noopener" }
[View on GitHub]({{ site.gh_repo }}){: .btn .fs-5 .mb-4 .mb-md-0 target="_blank" rel="noopener" }

---

## What is Valem?

An LLM is good at *describing* a domain but bad at *maintaining consistent state* over it. Valem
closes that gap. You give it a **ModelSpec** — a declarative JSON document (typically LLM-generated)
that names a domain's fields, the formulas that derive values from them, the invariants that must
always hold, and the side effects that fire when conditions are met. Valem compiles that into a live,
reactive model: mutate a base field and every dependent value recomputes in dependency order,
constraints are enforced, and nothing is left inconsistent.

Think of a spreadsheet — cells, formulas, validation — but addressed by JSON Path, expressed in
JSONata, and driven over a REST/WebSocket API, an in-process library, an MCP server, or a console.

## Why it exists

- **Deterministic & replayable.** The pure core performs no I/O; the same inputs always produce the
  same state, and history replays without re-contacting the outside world.
- **Reactive by construction.** A dependency graph recomputes only what a change actually affects.
- **LLM-native.** Specs are JSON an LLM can generate, and Valem validates-and-repairs them in a loop.
- **Explainable.** Every derivation and constraint evaluation is traceable, with an optional durable,
  tamper-evident audit trail.
- **Embeddable.** A pure-Java core with no framework lock-in, wrapped by an à-la-carte Spring layer.

## Live sandbox

A zero-setup public demo runs on Render: describe a domain in plain language, watch an LLM generate a
ModelSpec, then mutate fields and see derivations, constraints, and effects react live.

[Open the Valem sandbox →]({{ site.sandbox_url }}){: target="_blank" rel="noopener" }

## Where to next

| If you want to… | Go to |
|---|---|
| Add Valem to your own project | [Use Valem in your project]({% link getting-started.md %}) |
| Run the REST / WebSocket backend | [Running the API]({% link running-the-api.md %}) |
| Drive a model from an AI agent | [Running the MCP server]({% link running-the-mcp-server.md %}) |
| Understand the spec format | [Model spec]({% link model-spec.md %}) |
| See what Valem is built on | [Third-party libraries]({% link libraries.md %}) |

---

**Requirements:** Java 21+ · Maven 3.9+ · Node.js 20+ (for the UI only). Licensed under Apache-2.0.
