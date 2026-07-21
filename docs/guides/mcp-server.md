---
title: MCP server
parent: Guides
nav_order: 6
description: "Use Valem as the structured-state backend for an agent session over MCP."
---

# MCP server

`valem-mcp` exposes Valem over the [Model Context Protocol](https://modelcontextprotocol.io)
so any MCP-compatible agent — Claude Code, Claude Desktop, and others — can use Valem as the
**structured-state backend for a session**: the agent creates a model from a declarative spec,
mutates base fields, reads the reactively-computed merged state, and traces *why* any value is what
it is. Agents have no standard way to maintain structured world state — MCP is that standard way,
and this server is the doorway to it.

| | |
|---|---|
| Module | [`valem-mcp`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-mcp) |
| Transport | MCP over **stdio** — newline-delimited JSON-RPC 2.0 |
| Runtime deps | `valem-service` + `valem-cli-common` + `jackson-databind` (no Spring, no MCP SDK) |
| Storage | in-memory for the process lifetime by default; durable/shared with `--url` (see [Modes](#modes-embedded-vs-remote)) |
| Main class | `org.json_kula.valem.mcp.McpServer` |

It is a thin adapter over the same pure-Java [`ModelService`](../architecture/overview.md#service-layer-modelservice--valem-service)
that backs the REST API and the console, so the reactive pipeline, constraints, and effects behave
identically to every other access surface.

## Download

Grab the prebuilt, executable jar from the latest release (Java 21 required):

**[⬇ valem-mcp.jar (latest release)](https://github.com/vlad-public-code/org.json-kula.valem/releases/latest/download/valem-mcp.jar)**

Or build it from source below.

## Build

```powershell
# from the repo root (core/service/view must be installed first)
mvn install -pl valem-core,valem-service,valem-view -q
mvn package -pl valem-mcp -DskipTests
# → valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar  (shaded, executable)
```

Run it directly to speak the protocol on stdin/stdout:

```bash
java -jar valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar          # embedded, in-memory
java -jar valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar --help   # usage
java -jar valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar --version
```

## Modes: embedded vs remote

The jar runs in one of two modes, selected purely by whether a `--url` is supplied. The default is
unchanged from earlier releases — zero-config, in-memory, offline.

| | Embedded (default) | Remote (`--url`) |
|---|---|---|
| Selected by | no `--url` | `--url <base>` (or `VALEM_URL`) |
| State | in-memory, dies with the process | durable, shared, on the `valem-web` server |
| `list_models` | this process only | every model on the server |
| `create_model` collisions | never | real `409` on a duplicate id |
| Auth | none | server API key via `--api-key` / `VALEM_API_KEY` |
| Authoring tools | local | **still local** (see below) |

```bash
# Drive a shared, durable server; the API key is read from the flag or VALEM_API_KEY.
java -jar valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.internal --api-key "$KEY"
```

The pure authoring/verification tools (`validate_spec`, `eval_expression`, `test_spec`, `dry_run`)
**always run against local core**, even in remote mode — they are pure functions of their inputs, so
an agent can vet a candidate spec offline before pushing it to the shared server. Everything else
(create/mutate/get_state/explain/evolve/…) is routed to the server in remote mode. The API key is
never logged or echoed.

## Pairing with a browser: `remote_with_browser` mode

Add `--browser` to `--url` to pair the MCP with **a browser tab on that host** — for example the
[hosted Valem sandbox](https://valem.onrender.com/) — instead of authenticating with an API key.
Both ends then drive **one shared, live session**: a model the agent creates appears in the browser
immediately, the developer can enter data and inspect it through the sandbox's View/State/Explain
panels, the agent can read exactly what the developer entered with `get_state`/`explain`, and an
agent-authored `evolve_spec` pushes the browser to re-fetch and re-render automatically — no
copy-pasting a spec or mutation log between the two.

```bash
java -jar valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.onrender.com --browser
```

**How pairing works** (a device-authorization-grant handshake, [RFC 8628](https://www.rfc-editor.org/rfc/rfc8628) shape):

1. Ask the agent to pair (or call the `pair_browser` tool directly). The MCP mints a pairing on the
   host and prints a **verification link** plus a short **confirmation code**.
2. Open the link in a browser. It shows an **Approve** screen. The link the agent shows you is
   normally the *complete* one ([RFC 8628's `verification_uri_complete`](https://www.rfc-editor.org/rfc/rfc8628#section-3.3.1)),
   which fills the confirmation code in for you — check it matches what the agent printed, then
   approve. If your host serves only the plain link, type the code instead.
3. Call `pair_browser` again (it resumes the *same* pairing rather than minting a new one) if the
   first call reported `"pending"`. Once you've approved, it returns `{"status":"paired", ...}` and
   every other model tool now drives the shared session.

Model ids stay exactly as the agent chose them — `create_model({"id":"loan", ...})` and then
`mutate("loan", ...)` — even though the host namespaces ids internally per session; the MCP tracks
that transparently. `list_models` is scoped to the paired session, not the whole host. The pure
authoring tools (`validate_spec`, `eval_expression`, `test_spec`, `dry_run`) still run locally and
need no pairing.

A pairing is one-time, short-lived (a few minutes), and rests on three things. Polling is gated by a
`deviceSecret` known only to the MCP process, so only the process that minted a pairing can collect
its session. The `pairCode` in the verification link is 128 bits, so the link cannot be guessed. And
the pairing is only ever established by a **human clicking Approve in their own browser** — that is
the control that matters, and no amount of link handling replaces it.

The `userCode` is a second factor on top of that. In the complete link it rides in the URL
*fragment*, which browsers never transmit, so it cannot reach a server access log; the page reads it,
then strips it from the address bar and history before you approve. The server never reveals it to
the browser on its own (the peek endpoint returns only an expiry), and wrong-code attempts are
capped. Be aware of what it does *not* buy you: the agent receives the link and the code together and
shows you both, so if the agent's transcript leaks, both halves leak with it. The second factor
protects the channels that leak a URL *alone* — browser history, `Referer`, a pasted link.

If the host evicts the session (idle timeout or redeploy) mid-loop, the browser's own local recovery
copy rebuilds the model, and re-running `pair_browser` re-establishes the live link.

## Register with an MCP client

Point your client's MCP server config at the jar. For Claude Desktop
(`claude_desktop_config.json`) or any client using the same shape:

```json
{
  "mcpServers": {
    "valem": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/valem-mcp-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

For Claude Code:

```bash
claude mcp add valem -- java -jar /absolute/path/to/valem-mcp-1.0.0-SNAPSHOT.jar
```

To register the **remote** mode against a running `valem-web`, append `--url` (and pass the key
via the environment so it stays out of the config file):

```bash
claude mcp add valem --env VALEM_API_KEY=$KEY -- \
  java -jar /absolute/path/to/valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.internal
```

To register `remote_with_browser` mode against the hosted sandbox, add `--browser` instead of an
API key (see [Pairing with a browser](#pairing-with-a-browser-remote_with_browser-mode) above):

```bash
claude mcp add valem -- \
  java -jar /absolute/path/to/valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.onrender.com --browser
```

The client launches the process, performs the `initialize` handshake, and lists the tools below.

## Tools

Each tool maps one-to-one to a `ModelService` operation (and to a REST endpoint / console command —
see [api-reference.md](../reference/api-reference.md)). Tool arguments are addressed by canonical
JSON Path, exactly as the REST API and console are.

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

`pair_browser` only appears in [`remote_with_browser` mode](#pairing-with-a-browser-remote_with_browser-mode)
(`--url ... --browser`) — embedded mode and plain `--url` remote mode never advertise it.

`create_model` returns the model info (id + version + derivation/meta/constraint/effect counts);
`evolve_spec` returns the new version.

### Token economy

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

### Authoring & verification tools

Valem does **not** run its own LLM to generate specs — the connected agent is already a capable
model. Instead the server hands that agent the same verification substrate the runtime's internal
`SpecGenerator` loop uses, so the agent authors and certifies a spec itself: **the agent generates,
Valem verifies.** None of these touch the live registry.

| Tool | Backed by | Purpose |
|---|---|---|
| `validate_spec` | `ModelSpecValidator` | Validate a spec **without creating it** → `{valid, errors[], warnings[]}` (each finding has a `location` + `message`). Draft → validate → fix → repeat. |
| `eval_expression` | the runtime's JSONata compiler | Evaluate one JSONata expression against a sample input → the computed value or the exact compile/eval error. Verify a derivation/constraint expression before committing it. |
| `test_spec` | `TestCaseRunner` | Run a spec's embedded tests (or ad-hoc `given`→`expect` cases, keyed by canonical JSON Path) in a throwaway runtime → pass/fail + per-field failures. Certify domain behavior before promotion. |
| `dry_run` | throwaway `ModelService` | Compile a candidate spec in an isolated runtime, apply optional sample mutations, return the resulting merged state — preview the full reactive cascade without registering anything. |

### Tool metadata

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

### Result shape

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

### Subscribing to model state (§4.2)

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

## HTTP transport: an MCP endpoint on the server (§4.1)

Besides the stdio jar, `valem-web` exposes the **same MCP tool/resource surface over Streamable HTTP**
at `/mcp`, so hosted or remote agents can connect without a local jar and **share one server's models**
with each other and with the REST API. It reuses the exact protocol core the stdio server runs (same 24
tools, resources, negotiation, structured errors) — only the transport differs.

| Method | Purpose |
|---|---|
| `POST /mcp` | Send a JSON-RPC request (or batch); the response comes back as `application/json`. A body of only notifications returns `202 Accepted`. An `initialize` establishes a session — the response carries an `Mcp-Session-Id` header the client echoes on subsequent calls. |
| `GET /mcp` | Opens a `text/event-stream` the server pushes notifications on (log / progress / `resources/updated`), keyed by `Mcp-Session-Id`. |
| `DELETE /mcp` | Terminates a session. |

```bash
# initialize → note the Mcp-Session-Id response header, then reuse it
curl -sS localhost:8080/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}' -i

curl -sS localhost:8080/mcp -H 'Content-Type: application/json' -H 'Mcp-Session-Id: <id>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Auth rides the same `valem.api.key` gate as every other endpoint. Per the MCP spec the endpoint
validates the `Origin` header to prevent DNS-rebinding: a browser `Origin` must be listed in
`valem.mcp.allowed-origins` (comma-separated) — unless that list is empty, in which case the endpoint is
open, matching the API's open-by-default development posture (set it in production). Unlike the stdio
server (in-memory, one process per client), the HTTP endpoint's models are the server's shared, durable
models — the point of an in-server endpoint.

## Storage caveat

All state is in memory for the life of the process — nothing is persisted, and each client launches
its own instance. For durable, shared, multi-client state, front the REST API (`valem-api`) with
a persistence backend instead. This mirrors `valem-console`; the MCP server is for driving a
Valem model from within an agent session, not for operating a shared deployment.
