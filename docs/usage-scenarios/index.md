---
title: Usage scenarios
nav_order: 3
has_children: true
description: "Where Valem fits in a real project: agent state, rules and calculations, effect-driven workflows, model-driven UIs."
---

# Usage scenarios

Valem is a runtime for **state whose rules must always hold** — expressed as data, not code. That
shape shows up in more places than it first appears. These pages describe the four it fits best,
with the concrete pieces each one uses.

| Scenario | The problem it solves | Start here |
|---|---|---|
| [Structured state for AI agents](agent-state.md) | An agent that must keep a coherent, inspectable world model across a long session — and be able to prove *why* a value is what it is. | [MCP tools]({% link reference/mcp-tools.md %}) |
| [Rules & calculations as data](rules-and-calculations.md) | Pricing, quoting, eligibility, scoring: business arithmetic that changes far more often than your release cycle. | [Model guide]({% link model-guide/index.md %}) |
| [Effect-driven workflows](workflows-and-effects.md) | State changes that must reach the outside world — call an API, ask an LLM, wait an hour — without losing determinism. | [Effects]({% link model-guide/effects.md %}) |
| [Model-driven UIs](model-driven-ui.md) | A form or dashboard that should follow the model, not be rebuilt every time the model changes. | [View system]({% link reference/view-system.md %}) |

## The common thread

In every one of them, the same three properties are doing the work:

- **Derived state can't drift.** You mutate base fields; the runtime recomputes everything that
  depends on them, in dependency order, on every change.
- **Invariants are enforced at the boundary.** A `rollback` constraint refuses the mutation; a `flag`
  constraint records the violation. Neither is something a caller can forget to check.
- **Everything is explainable.** Each derivation and constraint evaluation is traceable, and the
  optional durable audit trail is tamper-evident.

## How they combine

The scenarios are not exclusive — a real deployment usually stacks them. A quoting service is
*rules-and-calculations* at the core, with an `http` **effect** that pulls a live rate, a
**viewDefinition** so the agent's output is directly renderable, and an **MCP** surface so an agent
can build and test new quote models against the same runtime.

Ready-to-run specs for all of this: the [examples gallery](examples-gallery.md).
