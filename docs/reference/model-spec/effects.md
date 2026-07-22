---
title: Effects
parent: Model spec format
grandparent: Reference
nav_order: 3
description: "Every effect field: executors, triggers, dedupe keys, status paths, and fold-back."
---

# `effects`

How a model reaches the outside world — declared as data in the spec, executed post-commit by the
shell, folded back as an ordinary mutation. For the narrative version see the
[Effects guide](../../model-guide/effects.md).

---

## `effects`

Effect requests the pure core emits as data when a boolean `trigger` becomes `true` after a mutation.
Each effect is executed by a shell selected by `executor`, and **replaces the removed `actions`
section** — a spec that still carries a non-empty `actions` array is rejected with a migration pointer.

- **`executor: "caller"`** — pure, in-core; computes `emit` + `payload` and returns them to the client
  in the mutation response as `dispatchedEffects` (also broadcast over WebSocket). No server egress.
  This is the direct successor to the old `actions`.
- **`executor: "server"`** — the api `HttpEffectExecutor` performs a spec-provided-URL HTTP request
  **asynchronously post-commit**, maps the response via `response.set`, and folds it back as a new
  mutation, driving a `statusPath` `pending→in_flight→applied|failed` machine.
- **`executor: "llm"`** — `LlmEffectExecutor` calls the configured `LlmClient` with a state-derived
  `prompt` (optional `responseSchema` for structured output), parses the JSON completion, and folds it
  back via `response.set` (`$response` bound). Replay never re-calls — the completion is a logged mutation.
- **`executor: "timer"`** — `TimerEffectExecutor` schedules the `response.set` fold-back at a future
  time: absolute `at` (epoch millis or ISO-8601, from state) or relative `afterMs` (delay). The clock
  lives in the shell; `response.set` is evaluated at fire time against current state.
- **`executor: "<plugin>"`** — any other string names a **pluggable** kind supplied by a jar on the
  classpath: a pure `EffectKind` (validate + resolve) discovered via `ServiceLoader`, plus a shell-side
  `EffectExecutor` bean that does the I/O and folds back. Adding a kind needs no core/api edits — just
  the jar. A deployment may restrict the active set with `valem.effects.kinds.enabled` (comma-list;
  unset = every discovered kind enabled); a spec selecting an unknown or disabled kind is rejected at
  validation.

```json
{
  "id":      "notify-on-priority",
  "executor": "caller",
  "trigger": "priorityFlag = true",
  "emit":    "priority-alert",
  "payload": { "score": "$string(sentimentScore)", "category": "issueCategory" }
}
```

### Effect field reference

**Common fields (all executors):**

| Field | Required | Description |
|---|---|---|
| `id` | yes | Unique effect identifier. |
| `executor` | no (default `"server"`) | Which shell runs the effect: `caller`, `server`, `llm`, `timer`, or a plugin kind name. |
| `trigger` | yes | JSONata boolean over the merged document; the effect is considered when it evaluates `true` after a mutation. |
| `dedupeKey` | no | JSONata expression producing the effect's **edge key**: the effect re-fires only when this value *transitions* (edge-triggered, not level-triggered). Also the key for the fold-back compare-and-swap — an in-flight effect whose key changed is superseded or cancelled rather than applied stale. |
| `statusPath` | no | Canonical address of the I/O status sub-document the runtime maintains for this effect (`{phase, key, at, error}`), driving the `pending → in_flight → applied \| failed \| cancelled` state machine and the in-flight guard. |
| `response.set` | server/llm/timer | Map of canonical JSON Path target → JSONata expression; how the executor's result **folds back** into state as an ordinary mutation. For `server` and `llm`, `$response` is bound to the parsed response/completion; for `timer`, the expressions are evaluated at fire time against current state. |

**`executor: "caller"`** — pure, no egress; surfaced as `dispatchedEffects` in the mutation response:

| Field | Required | Description |
|---|---|---|
| `emit` | yes | Event name surfaced to the client. |
| `payload` | no | Map of payload field → JSONata expression over the merged document. |

**`executor: "server"`** — an outbound HTTP request behind the SSRF egress guard. Exactly **one**
locator must be present: `request.url`, a `requests` fan-out, or a composition `target`.

| Field | Required | Description |
|---|---|---|
| `request.method` | no (default `GET`) | HTTP method. |
| `request.url` | one locator | Absolute URL; `{ expr }` segments interpolate JSONata over the merged document. |
| `request.headers` | no | Header map; values interpolate `{ expr }` segments. |
| `request.body` | no | A whole JSONata expression producing the JSON request body. |
| `requests` | one locator | JSONata expression producing an **array** of request descriptors — a fan-out of multiple HTTP calls from one trigger. |
| `target` | one locator | Composition link to another **model** (by coordinate, not URL): write-link `{ ref, path }` plus a sibling `body` (JSONata for the value written at `target.path`), or read-link `{ ref, read }` (no mutation of the target). See the [composition & branching guide](../../model-guide/composition-and-branching.md). |
| `body` | with write-link `target` | JSONata → the value written at `target.path`. |
| `policy` | no | `{ timeoutMs (default 5000), retries (default 0), backoff, egressProfile }` — execution policy for the HTTP call. |

**`executor: "llm"`** — calls the configured `LlmClient` and folds the completion back:

| Field | Required | Description |
|---|---|---|
| `prompt` | yes | JSONata expression producing the prompt text from state. |
| `responseSchema` | no | JSON Schema for structured output; the parsed completion is bound as `$response` in `response.set`. |
| `policy.model` / `policy.temperature` | no | Per-effect override of the configured LLM model/temperature. |

**`executor: "timer"`** — schedules the fold-back; the clock lives in the shell:

| Field | Required | Description |
|---|---|---|
| `at` | one of | JSONata → absolute fire time (epoch millis or ISO-8601), typically read from state. |
| `afterMs` | one of | JSONata → relative delay in milliseconds. |

A spec that names an executor kind that is unknown, or disabled via `valem.effects.kinds.enabled`,
is rejected at validation. Inherited effects carry a read-only, materializer-written `origin`
(`{fromRef, fromOwner}`) recording which ancestor contributed them — the basis for cross-owner
approval (see [security-model.md](../../deployment/security-model.md)).

### Worked `server` + `timer` example

From the bundled `insurance-quote.json`: fetch a live regional rate multiplier whenever the region
changes, and expire a priced quote after 15 minutes.

```json
[
  {
    "id": "fetch-region-rate",
    "executor": "server",
    "trigger": "quote.applicant.region != null",
    "dedupeKey": "quote.applicant.region",
    "request": {
      "method": "GET",
      "url": "https://rates.example.com/regional?region={ quote.applicant.region }"
    },
    "response": { "set": { "$.quote.regionMultiplier": "$response.multiplier" } },
    "statusPath": "$.quote.ioRegionRate"
  },
  {
    "id": "expire-quote",
    "executor": "timer",
    "trigger": "quote.decision = 'quoted' and quote.state = 'quoted'",
    "dedupeKey": "quote.state",
    "afterMs": "900000",
    "response": { "set": { "$.quote.state": "'expired'" } },
    "statusPath": "$.quote.ioExpiry"
  }
]
```

The egress/SSRF controls for `server` effects (`valem.effects.allowed-hosts`,
`allow-private-ips`, `max-response-bytes`, …) are documented in
[security-model.md](../../deployment/security-model.md) and [configuration.md](../../deployment/configuration.md#effects-egress--pluggable-kinds).

---
