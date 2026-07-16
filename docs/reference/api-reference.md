---
title: API reference
parent: Reference
nav_order: 2
description: "Every Valem access surface: REST, WebSocket, and the console JSON protocol."
---

# API Reference

Canonical reference for every Valem access surface: REST, WebSocket, and the console
JSON protocol. This is the single source of truth for endpoints and request/response shapes —
`README.md` and `CLAUDE.md` link here rather than duplicating it.

Related: [model-spec-format.md](model-spec-format.md) (request bodies),
[configuration.md](configuration.md) (server config),
[security-model.md](security-model.md) (auth).

> **Live OpenAPI spec.** A running `valem-web` also serves a generated OpenAPI 3 document at
> `/v3/api-docs` and an interactive Swagger UI at `/swagger-ui.html` (springdoc; behind the API
> key when one is configured).

---

## 1. REST endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/models` | List all registered model IDs (alphabetical). |
| `POST` | `/models` | Create model from a `ModelSpec` body. A `template.ref` branch is flattened + lineage-pinned before validation. Returns `201 Created` (`Location: /models/{id}`) with `{id, status:"created"}`. 422 on spec/composition validation failure, 409 on duplicate id. |
| `GET` | `/models/{id}` | Model info: id, version, derivation/constraint/effect counts. |
| `GET` | `/models/{id}/spec` | Full `ModelSpec` JSON as stored. |
| `GET` | `/models/{id}/lineage` | The pinned ancestor chain a branch was materialized from (`[]` for a non-branch model). |
| `POST` | `/models/{id}/promote` | Promote a local model into a web repository (one-way, closure-checked). Body `{toRepo}`. 400 if `toRepo` missing/blank; 409 on a closure/locality violation. |
| `GET` | `/models/{id}/effects/pending` | Inherited effects quarantined pending the owner's approval (cross-owner branching only — see [security-model.md](security-model.md)). |
| `POST` | `/models/{id}/effects/{effectId}/approve` | Approve a quarantined inherited effect, keyed to its current `definitionHash`. |
| `GET` | `/composition/graph` | Cross-model link/lineage topology, computed on demand (never authoritative state). |
| `DELETE` | `/models/{id}` | Remove model from the registry (also tears down composition watch subscriptions and deletes its durable audit trail). 404 if not found. |
| `POST` | `/models/{id}/mutations` | Apply field mutations (flat `{"$.path": value}` map). 422 on schema violation, 409 + violations on ROLLBACK constraint. Optional `X-View` header returns a `viewDelta`. |
| `POST` | `/models/{id}/mutations/patch` | Same as above but body is an RFC 6902 JSON Patch (`Content-Type: application/json-patch+json`). |
| `GET` | `/models/{id}/state` | Merged document (base + derived; evaluates LAZY derivations first). `?at=<ISO-8601>` for point-in-time read (404 if no history at that time, 400 on bad timestamp). |
| `GET` | `/models/{id}/state/{path}` | Single field value; evaluates a LAZY derivation on demand. 404 if not found. |
| `GET` | `/models/{id}/history` | ISO-8601 timestamps of committed mutations (chronological, last `valem.history.max-entries`, default 50). |
| `GET` | `/models/{id}/schema/{path}` | Effective JSON Schema for a field, overlaid with live meta values. |
| `GET` | `/models/{id}/explain/{path}` | Derivation/constraint trace records from the in-memory ring buffer (bounded, live). URL-encode `$constraint:id` as `%24constraint%3Aid`. |
| `GET` | `/models/{id}/audit` | Durable, append-only audit trail — the queryable superset of `explain`. One `AuditRecord` per committed cycle (mutations, `derivedUpdated`, `traces`, flagged constraints, dispatched effects, `source`, `sequence`, and the tamper-evident `prevHash`/`hash`). Filters: `path` (prefix), `from`/`to` (ISO-8601 window, 400 on bad timestamp), `limit` (default 100, newest-first). Returns `[]` when no audit backend is configured; 404 for an unknown model. |
| `GET` | `/models/{id}/audit/verify` | Verify the audit trail's SHA-256 hash chain. Returns `{valid, recordsChecked, firstBrokenSequence, detail}`; `valid:false` means a record was altered, reordered, or deleted since it was written. 404 for an unknown model. |
| `POST` | `/models/{id}/snapshot` | Capture and return an immutable `Snapshot`. |
| `POST` | `/models/{id}/restore` | Restore state from a `Snapshot` body. |
| `POST` | `/models/{id}/spec/evolve` | Apply a `SpecEvolution` diff. 422 if the evolved spec fails validation or a schema change would strand existing state; 409 if `expectedVersion` no longer matches. |
| `GET` | `/models/{id}/view` | Evaluate the default view definition → `EvaluatedView`. |
| `GET` | `/models/{id}/view/{viewId}` | Evaluate a named view → `EvaluatedView`. |
| `POST` | `/blobs` | Multipart binary upload (field `file`, optional `mediaType`). Returns `201 Created` with a content-addressed `BlobRef`. 413 if the upload exceeds `valem.blob.max-bytes` or the in-memory store's total-bytes budget. |
| `GET` | `/blobs/{blobId}` | Stream stored binary. 404 if not found. |
| `GET` | `/models/{id}/blobs/{blobId}` | Stream a blob only if referenced by the model's current state (404 otherwise). |
| `POST` | `/models/generate/preview` | Build the initial prompt server-side without calling the LLM. Body `{modelId, domainDescription, includeView}`. 400 if `domainDescription` exceeds 5,000 characters. |
| `POST` | `/models/generate` | Send a (possibly edited) prompt to the LLM, return `{valid, spec}` or `{valid:false, errors, rawResponse}`. 503 if no LLM configured, 400 if `prompt` exceeds 100,000 characters, 502 on LLM call failure. |
| `POST` | `/models/generate/stream` | SSE version of the above: runs the full `SpecGenerator` retry loop, streaming `event: progress` frames (`LlmProgressEvent`s — tool calls, validation attempts, test runs, retries) then a terminal `event: done` frame with `{valid, spec}` or `{valid:false, errors, rawResponse}`. Body `{modelId, domainDescription, includeView}`. |
| `POST` | `/models/generate/evolution/preview` | Build the evolution prompt server-side without calling the LLM. Body `{modelId, currentSpec, evolutionRequest, includeView}`. |
| `POST` | `/models/generate/evolution` | Send an evolution prompt to the LLM, return `{valid, evolution}` (a `SpecEvolution`) or `{valid:false, error, rawResponse}`. 503 if no LLM configured, 400 if `prompt` exceeds 100,000 characters, 502 on LLM call failure. |
| `POST` | `/models/{id}/spec/evolve/ai` | Natural-language spec evolution: the LLM drafts a `SpecEvolution` from `{description}` and it's applied immediately. Returns `{version, spec}`. 503 if no LLM configured, 400 if `description` missing/blank or over 5,000 characters, 502 on LLM failure, 422 if the evolved spec is invalid. |
| `POST` | `/models/{id}/spec/evolve/ai/stream` | SSE version of the above: streams `event: progress` frames then a terminal `event: done` frame with `{version, spec}`, or `event: error`. Body `{description}`. |
| `GET` | `/llm/interactions` | All recorded `LlmInteractionRecord` (prompt + response) pairs. Content is redacted when `valem.llm.log.capture-content=false`. |
| `POST` | `/admin/blobs/gc` | Mark-and-sweep unreferenced blobs. `?apply=false` (default) is a dry run reporting orphans; `?apply=true` deletes them. Only backends implementing `EnumerableBlobStore` (memory, filesystem) are swept; others report `supported:false`. `apply=true` requires a configured `valem.api.key` (403 in open mode). The mark set covers current state **and** retained history snapshots. |

### Request / response shapes

**`POST /models/{id}/mutations`** — body is a flat map (no wrapper):
```json
{ "$.order.subtotal": 180.0, "$.order.tax": 18.0 }
```

Success (`MutationResponse`):
```json
{
  "success": true,
  "mutatedPaths":       ["$.order.subtotal", "$.order.tax"],
  "derivedUpdated":     ["$.order.total"],
  "flaggedConstraints": [],
  "dispatchedEffects":  [
    { "effectId": "notify-total", "emit": "webhook.sent", "payload": { "total": 198.0 } }
  ],
  "traces": [
    { "targetPath": "$.order.total", "expression": "subtotal + tax",
      "inputPaths": ["$.order.subtotal", "$.order.tax"], "result": 198.0,
      "constraintPassed": null, "errorMessage": null },
    { "targetPath": "$constraint:cap", "expression": "order.total <= 1000",
      "inputPaths": ["$.order.total"], "result": null, "constraintPassed": true,
      "errorMessage": null }
  ]
}
```
`viewDelta` (a `Map<String, EvaluatedComponent>`) is present only when the request carried an
`X-View` header, and is omitted entirely otherwise.

Constraint ROLLBACK (HTTP 409):
```json
{
  "error": "Constraint violation",
  "violations": [
    { "constraintId": "credit-check", "message": "Exceeds credit limit", "policy": "ROLLBACK" }
  ]
}
```

### Error status codes

Beyond the per-endpoint codes in the table above, `GlobalExceptionHandler` maps these domain
exceptions uniformly (RFC 7807 `ProblemDetail`):

| Status | Trigger |
|---|---|
| `429` | `valem.mutation-queue-size` exceeded (too many concurrent mutations for a model), or a model-count limit exceeded. |
| `422` | `CompositionException` (invalid `template.ref`/link graph) or, by default, `ReferenceException`. |
| `409` | `ReferenceException.PromotionClosureFailure` / `ReferenceLocalityViolation` (e.g. `POST /models/{id}/promote` breaking closure or locality rules). |
| `405` | `ReferenceException.DemoteUnsupported`. |

**`GET /models/{id}/state`** — returns the merged document; fields at their natural JSON path,
no `$value`/`$meta` wrapping:
```json
{ "order": { "subtotal": 180.0, "tax": 18.0, "total": 198.0 } }
```

---

## 2. WebSocket

Connect to `ws://host/models/{id}/subscribe`. When `valem.api.key` is configured the
handshake must carry the key as a query-param token (browsers cannot set headers on the WS
handshake): `ws://host/models/{id}/subscribe?token=<apiKey>`. A missing/invalid token is rejected
with HTTP 401. Cross-origin handshakes are allowed only from `valem.websocket.allowed-origins`
(same-origin only when unset). The topic carries two frame shapes, discriminated by `kind`:

After each committed mutation, a `"mutation"` frame (`kind` defaults to `"mutation"` if absent, for
older clients):

```json
{
  "kind": "mutation",
  "modelId": "order-001",
  "mutatedPaths":       ["$.order.subtotal"],
  "derivedUpdated":     ["$.order.total"],
  "flaggedConstraints": [],
  "dispatchedEffects":  []
}
```

After a successful `POST /models/{id}/spec/evolve`, a `"spec-evolved"` frame — however the evolve was
made (this REST call, the MCP's `evolve_spec` tool, or an AI-assisted evolve):

```json
{ "kind": "spec-evolved", "modelId": "order-001", "version": "1.1.0" }
```

It deliberately carries only the new version, not the spec itself; a subscriber re-fetches
`GET /models/{id}/spec` (and view/state) as the source of truth. This is what lets a browser tab
notice a spec change made by another client on the same model — see
[MCP server: pairing with a browser](../guides/mcp-server.md#pairing-with-a-browser-remote_with_browser-mode).

Optional `?paths=` query parameter filters `"mutation"` events to those touching the listed path
prefixes (comma-separated, OR'd): `ws://host/models/{id}/subscribe?paths=$.order,$.customer`.
Constraint violations, dispatched (caller) effects, and `"spec-evolved"` frames are always forwarded
regardless of the filter. See [security-model.md](security-model.md) for the WebSocket auth/origin
model.

---

## 3. Console JSON protocol

The console app (`valem-console`) exposes the same operations over stdin/stdout — one JSON
object per line — for AI agents and scripts that avoid running an HTTP server. By default there is
no persistence and no WebSocket; all state is in-memory for the process lifetime.

```
→  {"cmd":"<command>", ...args}
←  {"ok":true,"result":<value>}
←  {"ok":false,"error":"<message>"}
```

**Embedded vs remote.** Like the MCP jar, the console runs embedded (in-memory, the default) or —
with `--url <base>` (and optionally `--api-key <key>`, or the `VALEM_URL` / `VALEM_API_KEY`
environment variables) — against a durable, shared `valem-web` server. The command set is
identical in both modes; remote mode differs only in the documented ways (durable/shared state,
server-wide `list-models`, real `409` collisions, API-key auth). `--help` and `--version` are also
recognised. A non-2xx server response surfaces as `{"ok":false,"error":<server message>}` — the same
shape an embedded failure produces.

```bash
java -jar valem-console.jar                       # embedded, in-memory (default)
java -jar valem-console.jar --url https://host     # remote against valem-web
```

| Command | REST equivalent | Required | Optional |
|---|---|---|---|
| `list-models` | `GET /models` | — | — |
| `create-model` | `POST /models` | `spec` | — |
| `get-spec` | `GET /models/{id}/spec` | `id` | — |
| `get-info` | `GET /models/{id}` | `id` | — |
| `get-state` | `GET /models/{id}/state` | `id` | `at` |
| `get-field` | `GET /models/{id}/state/{path}` | `id`, `path` | — |
| `mutate` | `POST /models/{id}/mutations` | `id`, `mutations` | — |
| `patch-mutate` | `POST /models/{id}/mutations/patch` | `id`, `patch` | — |
| `get-history` | `GET /models/{id}/history` | `id` | — |
| `get-schema` | `GET /models/{id}/schema/{path}` | `id`, `path` | — |
| `explain` | `GET /models/{id}/explain/{path}` | `id`, `path` | — |
| `snapshot` | `POST /models/{id}/snapshot` | `id` | — |
| `restore` | `POST /models/{id}/restore` | `id`, `snapshot` | — |
| `evolve-spec` | `POST /models/{id}/spec/evolve` | `id`, `evolution` | — |
| `delete-model` | `DELETE /models/{id}` | `id` | — |
| `upload-blob` | `POST /blobs` | `data` (base64), `mediaType` | — |
| `get-blob` | `GET /blobs/{blobId}` | `blobId` | — |
| `get-model-blob` | `GET /models/{id}/blobs/{blobId}` | `id`, `blobId` | — |
| `get-view` | `GET /models/{id}/view[/{viewId}]` | `id` | `viewId` |
| `help` / `exit` | — | — | — |

Binary data is base64-encoded.

---

## 4. MCP (Model Context Protocol)

The `valem-mcp` module exposes core model operations to AI agents over MCP — newline-delimited
JSON-RPC 2.0 on stdio — so a Valem model can be driven from within an agent session (Claude Code,
Claude Desktop, and other MCP clients). The tool surface (`create_model`, `mutate`, `get_state`,
`get_field`, `explain`, `evolve_spec`, `get_view`, …) covers the core CRUD/mutation/authoring surface
shared with the console commands above; it does not yet cover the composition/branching endpoints
(`lineage`, `promote`, `effects/pending`, `/composition/graph`) or the async LLM generation/evolution
endpoints (`generate/stream`, `spec/evolve/ai[/stream]`, `generate/evolution*`). See
[../guides/mcp-server.md](../guides/mcp-server.md) for the tool list, result shape, client
registration, and protocol notes.
