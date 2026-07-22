---
title: FAQ — and when not to use Valem
parent: Getting started
nav_order: 5
description: "Straight answers: how Valem differs from a rules engine, JSON Schema, or just writing the code — and where it's the wrong tool."
---

# FAQ
{: .no_toc }

The questions people actually ask before adopting it, including the ones with unflattering answers.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## When is Valem the wrong tool?

Starting here, because it saves everyone time.

- **Your rules never change.** If the logic is stable and lives happily in code, a spec adds a layer
  and buys you little. Valem pays off when rules change often, must be auditable, or are generated.
- **You need per-field authorization.** There is none. Access is a single coarse gate
  (`valem.api.key`); any caller who can reach a model can read and write every base field. Field- or
  role-level access must live in a layer you put in front.
- **You need high-throughput stream processing.** A model is guarded by a per-model lock and holds
  its state in memory. It's built for correctness on a bounded document, not for millions of
  events per second.
- **Your computation isn't expressible as a pure function of state.** Expressions are
  [JSONata](https://jsonata.org) — deliberately not a general programming language. Loops over
  external systems, heavy numerics, and ML inference belong outside, reached through an effect.
- **You want a database.** Valem persists state so it survives restarts; it is not a query engine.
  There are no joins, no indexes, no cross-model queries.

## How is this different from a rules engine (Drools et al.)?

A classic rules engine fires *actions* when conditions match. Valem computes *values* and refuses
mutations that would break an invariant. The differences that matter in practice:

- **Direction.** Rules engines are forward-chaining over a working memory; Valem is a dependency
  graph recomputed in topological order — closer to a spreadsheet than to an inference system.
- **Explainability.** Every value carries traces of the derivation and constraint evaluations behind
  it, and the audit trail is tamper-evident. You can answer "why was this number 4,200 in March?"
- **Determinism.** No I/O in the core; effects are declared as data and executed after the commit,
  so replaying history reproduces state without re-firing anything.

## Isn't this just JSON Schema?

JSON Schema validates a document's *shape*. It cannot compute `total = subtotal + tax`, cannot
express "this maximum depends on the customer's risk band", and cannot tell you why a value is what
it is. Valem uses JSON Schema for the base document's shape, then adds derivations,
meta-derivations (live per-field limits), constraints, and effects on top.

## Why not just write it in code?

You can, and for a handful of stable rules you probably should. The trade you're making:

| Hand-written | Valem |
|---|---|
| A rule change is a release | A rule change is a spec evolution against a live model |
| Consistency depends on every caller doing the right thing | The runtime recomputes and enforces on every mutation |
| "Why this number?" means reading code and logs | `explain` returns the traces; the audit trail is durable |
| An LLM can generate code you must review carefully | An LLM generates a spec the runtime validates, tests, and dry-runs |

## Do I need an LLM to use Valem?

No. Specs are plain JSON; write them by hand, generate them from your own tooling, or have an agent
author them. LLM spec generation is an opt-in feature of the server (`/models/generate*`, `503` when
no provider is configured), and the MCP server deliberately runs **no LLM of its own** — the
connected agent generates, Valem verifies.

## Is it production-ready?

The runtime is as-built and tested, and [v1.0.0]({{ site.gh_repo }}/releases/latest) is tagged. Be
deliberate about three defaults chosen for a fast first run rather than for exposure: the API is
**open unless `valem.api.key` is set**, storage is **in-memory unless you configure a backend**, and
the MCP HTTP endpoint's origin allowlist is **open when empty**. See
[Security model]({% link deployment/security-model.md %}).

Maven artifacts are not on Maven Central yet — you install them into your local `~/.m2` by building
the repository once. See [Embed Valem in your project]({% link extending/embedding.md %}).

## How big can a model get?

The practical limits are memory and lock contention, not a hard cap: a model's state lives in memory
(persisted separately), and all mutations of one model serialise on that model's lock. Different
models are independent. Very large documents are better split into linked models — see
[Composition & branching]({% link model-guide/composition-and-branching.md %}).

## What happens when an effect's HTTP call fails?

The mutation that triggered it already committed — effects run **after** the commit, asynchronously.
Failure is visible in state through the effect's `statusPath` machine
(`pending → in_flight → applied | failed`), with retries configurable. Replay never re-runs the
call; a folded-back result is an ordinary logged mutation. See [Effects]({% link model-guide/effects.md %}).

## Can several clients share one model?

Yes — that's what the [web API]({% link deployment/web-api.md %}) is for. Every client sees the same
state, mutations are serialised per model, and each committed cycle broadcasts a `ChangeEvent` over
WebSocket. An agent can join the same models over the `/mcp` endpoint, and a paired browser session
puts a human and an agent on one model at once.

## Which language can I use it from?

The engine is Java 21. Over HTTP, anything — with typed
[client SDKs]({% link extending/client-sdks.md %}) for Java and TypeScript, and a plain JSON console
protocol for scripts. Views are handed to clients as renderer-agnostic JSON, so a non-React frontend
consumes the same contract.

## What's open source, and what isn't?

Everything documented on this site is Apache-2.0 and lives in the
[public repository]({{ site.gh_repo }}): the engine, the service layer, the REST/WebSocket API, the
MCP server, the view system, the persistence adapters, the SDKs, and the management UI. The hosted
[sandbox]({{ site.sandbox_url }}) is a demo deployment layered on top of that runtime.

## How do I get help or report something?

Open an issue on the [repository]({{ site.gh_repo }}/issues). If a doc page is wrong, every page has
an **Edit this page on GitHub** link.
