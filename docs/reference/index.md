---
title: Reference
nav_order: 5
has_children: true
description: "Authoritative, as-built references: the ModelSpec format, the API surface, MCP tools, LLM prompts, and the view system."
---

# Reference

Authoritative, as-built descriptions of every interface Valem exposes. One topic, one home — the
rest of the docs link here rather than restating any of it.

| Page | The single source of truth for |
|---|---|
| [Model spec format](model-spec-format.md) | Every `ModelSpec` field: schema, constants, defaults, derivations, meta-derivations, constraints, effects, tests, `viewDefinition`, and spec evolution. |
| [API reference](api-reference.md) | REST endpoints, the WebSocket protocol, and the console JSON protocol — request and response shapes. |
| [MCP tools & resources](mcp-tools.md) | Every MCP tool, its arguments, result shape and error shapes; the advertised resources; protocol details. |
| [LLM prompts](llm-prompts.md) | Exactly what is sent to the LLM on generation, repair, evolution, and test-repair calls. |
| [View system](view-system.md) | `ViewDefinition` / `ComponentSpec` component types, the `EvaluatedView` contract, and the React renderer. |

## Related references elsewhere

Two reference-grade documents live with the operational chapter because that's where they're used:

- [Configuration]({% link deployment/configuration.md %}) — every `valem.*` property, with defaults.
- [Security model]({% link deployment/security-model.md %}) — auth, origin checks, effect egress,
  limits.

And two look-ups that stand on their own:

- [Glossary]({% link glossary.md %}) — canonical definitions of the core terms.
- [Third-party libraries]({% link libraries.md %}) — what Valem is built on and why.

{: .note }
Reference pages describe the **shipped** system. If you want the reasoning rather than the
specification, start with the [Model guide]({% link model-guide/index.md %}).
