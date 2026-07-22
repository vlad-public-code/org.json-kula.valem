---
title: MCP tools & resources
parent: Reference
nav_order: 3
description: "Every MCP tool, its arguments and result shape, plus the resources and protocol details the server advertises."
---

# MCP tools & resources
{: .no_toc }

The complete surface `valem-mcp` exposes to an agent — over stdio and, identically, over Streamable
HTTP at `/mcp`. To install and run the server see
[Running the MCP server]({% link deployment/mcp-server.md %}).
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Model tools

Each tool maps one-to-one to a `ModelService` operation (and to a REST endpoint / console command —
see [api-reference.md](api-reference.md)). Tool arguments are addressed by canonical JSON Path,
exactly as the REST API and console are.

| Tool | Maps to | Required args | Optional |
|---|---|---|---|
| `list_models` | `GET /models` | — | — |
| `create_model` | `POST /models` | `spec` (a full ModelSpec) | — |
| `get_model_info` | `GET /models/{id}` | `id` | — |
| `get_spec` | `GET /models/{id}/spec` | `id` | — |
| `get_state` | `GET /models/{id}/state` | `id` | `at` (ISO-8601), `paths` (project subtrees), `depth` (cap nesting) |
| `get_field` | `GET /models/{id}/state/{path}` | `id`, `path` | — |
| `get_effective_schema` | `GET /models/{id}/schema/{path}` | `id`, `path` | — |
| `mutate` | `POST /models/{id}/mutations` | `id`, `mutations` (`{"$.path": value}`) | `includeTraces` |
| `patch_model` | `POST /models/{id}/mutations/patch` | `id`, `patch` (RFC 6902 JSON Patch) | `includeTraces` |
| `explain` | `GET /models/{id}/explain/{path}` | `id`, `path` | `limit` |
| `get_history` | `GET /models/{id}/history` | `id` | — |
| `get_audit` | `GET /models/{id}/audit` | `id` | `pathPrefix`, `from`, `to`, `limit` |
| `verify_audit` | `GET /models/{id}/audit/verify` | `id` | — |
| `snapshot` | `POST /models/{id}/snapshot` | `id` | — |
| `restore` | `POST /models/{id}/restore` | `id`, `snapshot` | — |
| `evolve_spec` | `POST /models/{id}/spec/evolve` | `id`, `evolution` (a SpecEvolution) | — |
| `delete_model` | `DELETE /models/{id}` | `id` | — |
| `get_view` | `GET /models/{id}/view[/{viewId}]` | `id` | `viewId` |
| `upload_blob` | `POST /models/blobs` | `data` (base64) | `mediaType` |
| `download_blob` | `GET /models/{id}/blobs/{blobId}` | `blobId` | `modelId` |
| `pair_browser` | `POST /sandbox/pair` + `/token` | — | — |

`patch_model` closes the array-manipulation gap the flat `mutations` map cannot express: its `patch`
is an RFC 6902 JSON Patch (`add`/`remove`/`replace`/`move`/`copy`/`test`), so an agent can insert,
remove, or reorder array elements (`{"op":"add","path":"/items/-","value":{…}}`). Note the `path`
fields there are **RFC 6901 JSON Pointer** (`/items/0`, `-` for append), not the `$.`-rooted address
form the other tools use.

`get_effective_schema` returns a field's schema overlaid with **live** meta-derived constraints
(current min/max/required/…), so an agent can check what the pipeline will accept before writing
rather than discovering an invalid value only by getting a schema-violation error.

`snapshot`/`restore` are a matched pair: `snapshot` captures an immutable point-in-time copy of a
model's state, and `restore` rolls the state back to a snapshot handed back verbatim — a natural
safety step around a risky `evolve_spec`.

`get_audit`/`verify_audit` expose the **durable, append-only audit trail** — one record per committed
reactive cycle (mutations, `derivedUpdated`, traces, flagged constraints, dispatched effects, `source`,
`sequence`), the queryable superset of the bounded `get_history`/`explain` ring buffer. `get_audit`
filters by `pathPrefix` + ISO-8601 `from`/`to` window + `limit`; `verify_audit` walks the tamper-evident
hash chain and reports the first altered/reordered/deleted record. In remote/paired mode these read the
server's durable `AuditStore`; the embedded server keeps an in-memory trail for the session (no hash
chain, so `verify_audit` reports valid).

`upload_blob`/`download_blob` move binary content (base64 in `data`) in and out of the
content-addressed blob store — niche, for specs that reference binary fields; large blobs may trip the
result-size guard, so this channel suits small binaries only.

`pair_browser` only appears in `remote_with_browser` mode (`--url ... --browser`) — embedded mode and
plain `--url` remote mode never advertise it. The handshake it drives is described in
[Connect your agent]({% link getting-started/connect-your-agent.md %}).

`create_model` returns the model info (id + version + derivation/meta/constraint/effect counts);
`evolve_spec` returns the new version.

## Authoring & verification tools

Valem does **not** run its own LLM to generate specs — the connected agent is already a capable
model. Instead the server hands that agent the same verification substrate the runtime's internal
`SpecGenerator` loop uses, so the agent authors and certifies a spec itself: **the agent generates,
Valem verifies.** None of these touch the live registry, and all of them run against the **local**
core even in remote mode.

| Tool | Backed by | Purpose |
|---|---|---|
| `validate_spec` | `ModelSpecValidator` | Validate a spec **without creating it** → `{valid, errors[], warnings[]}` (each finding has a `location` + `message`). Draft → validate → fix → repeat. |
| `eval_expression` | the runtime's JSONata compiler | Evaluate one JSONata expression against a sample input → the computed value or the exact compile/eval error. Verify a derivation/constraint expression before committing it. |
| `test_spec` | `TestCaseRunner` | Run a spec's embedded tests (or ad-hoc `given`→`expect` cases, keyed by canonical JSON Path) in a throwaway runtime → pass/fail + per-field failures. Certify domain behavior before promotion. |
| `dry_run` | throwaway `ModelService` | Compile a candidate spec in an isolated runtime, apply optional sample mutations, return the resulting merged state — preview the full reactive cascade without registering anything. |

## Token economy

Response size — not latency — is the dominant cost of driving a model over a long agent session,
so several tools let the agent hold down the bytes it pulls back:

- **`get_state` projection.** On a large model `get_state` is the biggest context burner. Pass
  `paths` (canonical addresses, e.g. `["$.order","$.totals"]`) to return only those subtrees,
  spliced back into a pruned document at their addresses; absent addresses are skipped. Pass `depth`
  (an integer ≥ 0) to cap nesting — containers deeper than the limit collapse to a compact marker
  (`"<object: 5 fields>"` / `"<array: 3 items>"`). The two combine (`paths` first, then `depth`).
- **Opt-in `mutate`/`patch_model` traces.** By default a write returns only the actionable summary
  (`success`, `mutatedPaths`, `derivedUpdated`, `metaUpdated`, `flaggedConstraints`,
  `dispatchedEffects`). The full derivation/constraint `traces` — the same payload `explain` serves —
  are included only with `includeTraces: true`. Call `explain` when a value looks wrong; don't pay for
  the trace on every write.
- **`explain` limit.** Trace records can be bulky; `limit` returns only the most recent N.
- **Result-size guard.** Any tool result whose pretty-printed JSON would exceed ~50 KB is withheld and
  replaced with a compact note (`{"error":"Result too large", …, "hint": …}`) telling the agent how to
  narrow it, so one oversized read can't wreck the session.

## Tool metadata

Each tool declares a human-readable `title` and MCP **annotations** so a client can reason about
safety before calling — `readOnlyHint` (all `list_*`/`get_*`/`explain`), `destructiveHint`
(`delete_model` and `evolve_spec`, which can drop sections), `idempotentHint`, and `openWorldHint`
(always false — every tool operates on this server's in-memory registry, not an external world).

`create_model` and `evolve_spec` embed the **full ModelSpec / SpecEvolution JSON Schema** (reused from
the runtime's own `SpecGenerationSchema`) as their `inputSchema`, so a generating model gets the exact
structure it must produce rather than a bare `"type":"object"` — the single biggest lever on
first-try generation success.

Tools whose result is always a JSON object also declare an **`outputSchema`** (a 2025-06-18 feature),
so a client can validate the `structuredContent` and the model knows the result shape *before* calling
— e.g. `mutate`/`patch_model` (the mutation-result fields), `create_model`/`get_model_info`
(`ModelInfo`), `validate_spec`, `test_spec`, `eval_expression`. Tools that return an array or scalar
(`list_models`, `get_history`, `explain`, a scalar `get_field`) carry no `outputSchema`, since they
emit no `structuredContent`.

## Result shape

A successful `tools/call` returns a `text` content block whose text is the operation's JSON result
(pretty-printed); when the result is a JSON object it is **also** returned as `structuredContent`, so a
client can consume it without re-parsing the text. (Array/scalar results — `list_models`,
`get_history`, `explain`, a scalar `get_field` — are conveyed via the text block only, since MCP
structured content is defined as an object.)

A tool-level failure — an unknown model id, a schema violation, a ROLLBACK constraint — is returned as
a result with `isError: true` (per the MCP tool-error convention), **not** as a JSON-RPC protocol
error, so the model sees the failure and can react. Three failure kinds an agent needs to react to
*programmatically* are surfaced **structurally** — both the text block and `structuredContent` carry
the parsed shape rather than a flattened string:

- **ROLLBACK constraint** — `{ "error": "Constraint violation", "violations": [ { "constraintId", "message", "policy" } ] }`.
- **Schema violation** (`mutate`/`patch_model`) — `{ "error": "Schema violation", "violations": [ { "path", "keyword", "message" } ] }`, so the agent can fix the offending field and retry.
- **Version conflict** (`evolve_spec` with a stale `expectedVersion`) — `{ "error": "Version conflict", "expected", "actual" }`, so the agent can re-read the current version and retry instead of guessing.

Malformed protocol requests (bad method, missing `name`) return JSON-RPC errors.

## Resources

The server also advertises the MCP **resources** capability — readable reference material the agent can
pull in while authoring a spec (the same substrate the runtime's own generator feeds its LLM):

| URI | mimeType | Content |
|---|---|---|
| `valem://guide/model-spec-format` | `text/markdown` | The ModelSpec authoring guide (sections, path notation, JSONata rules and gotchas). |
| `valem://guide/jsonata-gotchas` | `text/markdown` | JSONata pitfalls cheatsheet (bindings, singleton sequences, missing-vs-null, path dialect). |
| `valem://guide/spec-evolution` | `text/markdown` | How to author a SpecEvolution diff (targeted upserts vs wholesale, `expectedVersion`). |
| `valem://guide/view-system` | `text/markdown` | View-system orientation (component tree, `bind`, what `get_view` returns). |
| `valem://schema/model-spec` | `application/json` | The full ModelSpec JSON Schema. |
| `valem://schema/spec-evolution` | `application/json` | The SpecEvolution JSON Schema. |
| `valem://examples/<name>` | `application/json` | Each bundled example spec (e.g. `insurance-quote`, `car-loan-calculator`) — working models to learn from. |

Standard MCP flow: `resources/list` enumerates them, `resources/read` (with a `uri`) returns
`{ contents: [ { uri, mimeType, text } ] }`. An unknown uri returns JSON-RPC error `-32002`. The
bundled examples and the model-state resource are advertised as **resource templates** —
`resources/templates/list` returns `valem://examples/{name}` and `valem://state/{modelId}` — so a client
need not enumerate them by name.

### Subscribing to model state

The server advertises `resources.subscribe`, and a model's live merged state is a subscribable resource:

- `resources/read` on `valem://state/<modelId>` returns the current merged state.
- `resources/subscribe` on that uri opens a change subscription; every committed mutation then emits a
  `notifications/resources/updated` for the uri, and `resources/unsubscribe` stops it.

This closes the loop in a **paired session**: when the developer edits the model in the browser, the
agent gets a `resources/updated` push and can re-read without polling — instead of only seeing the
change on its next `get_state`. (It also fires in embedded/plain-remote mode for the agent's own
mutations.)

## Protocol notes

- **stdio framing** — one JSON message per line; messages never contain embedded newlines (JSON
  string values escape them). **Only protocol messages go to stdout**; all diagnostics go to stderr.
- **Handshake** — `initialize` → capabilities + server info + usage `instructions`;
  `notifications/initialized` is accepted with no reply; `ping` returns `{}`. A leading UTF-8 BOM on
  the input stream (some Windows launchers prepend one) is tolerated.
- **Protocol version** — the server negotiates `2024-11-05`, `2025-03-26`, `2025-06-18`, or
  `2025-11-25` (the current revision, the default), echoing the client's requested version when
  supported and otherwise falling back to the newest.
- **Capabilities** — `tools`, `resources` (with `subscribe`), and `logging` are advertised (no prompts).
- **Logging** — the server advertises the `logging` capability and emits `notifications/message`
  (a one-line readiness/mode log after `notifications/initialized`); a client can raise the minimum
  severity with `logging/setLevel` (below-floor messages are suppressed).
- **Progress & cancellation** — `pair_browser` (the one long-poll tool) runs off the read loop: it emits
  `notifications/progress` while it waits (when the client passed a `progressToken`) and honours
  `notifications/cancelled` to abort the wait promptly.
- **URL-mode elicitation** — when the client advertises the `elicitation` capability with URL mode
  (2025-11-25), `pair_browser` hands the developer the verification link via a URL-mode
  `elicitation/create` request (and a `notifications/elicitation/complete` once paired) instead of
  relaying the link and code through the model.

## Worked example (raw protocol)

Piping newline-delimited JSON-RPC to the jar (what the client does under the hood):

```
→ {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}
← {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"valem","version":"1.0.0"},"instructions":"..."}}

→ {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"create_model","arguments":{"spec":{"id":"demo","version":"1","schema":{},"derivations":[{"path":"$.total","expr":"price * qty"}],"constraints":[],"metaDerivations":[],"tests":[]}}}}
← {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{ \"id\": \"demo\", \"version\": \"1\", \"derivationCount\": 1, ... }"}],"structuredContent":{"id":"demo","version":"1","derivationCount":1},"isError":false}}

→ {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"mutate","arguments":{"id":"demo","mutations":{"$.price":10,"$.qty":3}}}}
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{ \"success\": true, \"derivedUpdated\": [\"$.total\"], ... }"}],"structuredContent":{"success":true,"derivedUpdated":["$.total"]},"isError":false}}

→ {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_state","arguments":{"id":"demo"}}}
← {"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{ \"price\": 10, \"qty\": 3, \"total\": 30 }"}],"structuredContent":{"price":10,"qty":3,"total":30},"isError":false}}
```

The reactive pipeline runs on every `mutate`: `$.total` recomputes to `30` with no extra call. Each
object result carries both the text block and `structuredContent`.
