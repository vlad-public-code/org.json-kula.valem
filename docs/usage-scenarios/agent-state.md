---
title: Structured state for AI agents
parent: Usage scenarios
nav_order: 1
description: "Give an agent a world model it cannot corrupt: declarative rules, verified specs, explainable values."
---

# Structured state for AI agents
{: .no_toc }

Agents have no standard way to hold structured state. Valem gives them one — with the arithmetic and
the invariants enforced *outside* the context window.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## The problem

Ask a model to track an order, a quote, or a case file across a long session and the failure mode is
predictable: totals that no longer match their line items, a limit that was checked ten turns ago,
a field silently dropped on the last rewrite. Scratchpads and JSON blobs in the transcript don't fix
it, because nothing *enforces* anything — every invariant is only as good as the model's attention
at that moment.

## The shape of the fix

Move the rules out of the transcript and into a runtime:

1. The agent describes the domain **once**, as a `ModelSpec` — schema, derivations, constraints.
2. From then on, the agent only writes **base fields**. Derived values are the runtime's job.
3. Every write is validated against the effective schema and checked against the constraints
   *before* it commits. A bad write is refused with a structured error the agent can act on.

The agent stops being responsible for consistency and starts being responsible for intent.

## What Valem provides

| Need | Mechanism |
|---|---|
| Create and drive a model from the session | The [MCP server]({% link deployment/mcp-server.md %}) — 24 tools over stdio or Streamable HTTP |
| Author a spec the agent can trust | `validate_spec`, `eval_expression`, `test_spec`, `dry_run` — **the agent generates, Valem verifies** |
| Change the model without losing state | `evolve_spec` — a versioned diff, with `expectedVersion` conflict detection |
| Answer "why is this value 4200?" | `explain` (live traces) and `get_audit` (durable, tamper-evident) |
| Know what a field will accept | `get_effective_schema` — the schema overlaid with live meta-derived min/max/required |
| Keep context small | `get_state` with `paths`/`depth` projection, opt-in mutation traces, a result-size guard |
| React to outside changes | Subscribe to `valem://state/{id}` and get `resources/updated` pushes |

Every tool maps one-to-one onto the same `ModelService` the REST API uses, so an agent and a
conventional client can share one model with identical semantics.

## Two ways to run it

- **Embedded (default).** `java -jar valem-mcp.jar` — in-memory, offline, zero config. State lives
  for the process. Ideal for an agent that needs a scratch model for one session.
- **Remote / paired.** `--url <server>` puts the agent on a durable, shared server; adding
  `--browser` pairs it with a browser tab so a human and the agent drive **one live session**
  together. See [Connect your agent]({% link getting-started/connect-your-agent.md %}).

## Why the verification tools matter

Valem does **not** run its own LLM when driven over MCP — the connected agent is already a capable
model. What the server adds is the substrate to *check* the agent's work before it becomes state:
validate a draft spec, evaluate a single JSONata expression against sample input, run the spec's
embedded tests, or dry-run the whole reactive cascade in a throwaway runtime. None of it touches the
live registry.

That inverts the usual reliability problem. The agent is allowed to be creative; the runtime decides
what is admissible.

## Workflow state, not workflow events

The same mechanism scales from one agent's scratch state to a whole multi-step workflow. Instead
of an orchestrator that listens for "research finished" and decides what to do, the workflow is a
model whose state *is* the answer:

- **Artifacts record their inputs.** A draft stores the brief fingerprint it was written for; a
  review stores the draft version it scored. Freshness is a derivation, so changing an input makes
  everything built from it stale in one cycle, with no invalidation events to get wrong.
- **The next step is derived.** One expression over the artifacts says what should happen next.
  Each step is an [effect](workflows-and-effects.md) whose trigger is that derived state: an `llm`
  effect for a model call, a `caller` effect to hand work to an outside agent or a person, a
  `timer` for deadlines. Because fold-backs are compare-and-swap, a step whose input changed while
  it ran is superseded instead of overwriting newer work.
- **Gates are constraints.** "Approve only after a passing review of this exact version", "claim a
  task only when its dependencies are done", "no step past the budget" are rollback constraints.
  The agent gets a structured refusal; it does not have to remember the rule.
- **Plans can be data.** A task list with `dependsOn` lets agents grow the plan at run time while
  readiness, progress and stalls (a failed dependency or a cycle) stay derived.
- **A step can be its own model.** A form that collects a step's input, or a whole sub-workflow,
  is a separate model that hands its result to the parent through a write-link when its own
  derived "done" becomes true. To the parent, it is one more artifact.

Working examples of each are in the [gallery](examples-gallery.md#featured-agent-workflows-as-reactive-state).

Limits of the pattern today:

- **Cross-model links are effects, not dependencies.** A parent cannot derive from a child's
  field; the child pushes its result (write-link) or the parent pulls once when a trigger fires
  (read-link). Each link has a status machine, not a dependency edge.
- **A link targets an existing model.** Nothing in a spec creates a fresh step model per run; an
  agent or the shell creates it (for example by branching a template) before the link fires.
- **The `llm` effect is one completion, not an agent loop.** Multi-turn work with tools happens in
  an outside agent that writes its output back, or in a custom effect kind.
- **No identity.** Constraints can require that an approval names an approver, but not that the
  caller is that person: any caller with model access can write any field.

## Worth knowing before you build on it

- There is **no per-field access control**. Any caller with model access can read and mutate every
  field; access is a single coarse gate (`valem.api.key`). See
  [security model]({% link deployment/security-model.md %}).
- Embedded mode keeps state **in memory only** — nothing is persisted, and each client launches its
  own process. Durable, shared, multi-client state is the [`valem-web`]({% link deployment/web-api.md %})
  path.
- An agent-authored spec is still untrusted input. Effects declared in it reach the network; the
  egress guard and the `valem.effects.kinds.enabled` list are what bound that.

## Next

- [MCP tools reference]({% link reference/mcp-tools.md %}) — every tool, argument, and result shape.
- [Running the MCP server]({% link deployment/mcp-server.md %}) — install, modes, client registration.
- [Connect your agent]({% link getting-started/connect-your-agent.md %}) — the paired-browser loop.
