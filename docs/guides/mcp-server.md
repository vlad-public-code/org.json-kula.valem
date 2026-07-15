# MCP server

`valem-mcp` exposes Valem over the [Model Context Protocol](https://modelcontextprotocol.io)
so any MCP-compatible agent — Claude Code, Claude Desktop, and others — can use Valem as the
**structured-state backend for a session**: the agent creates a model from a declarative spec,
mutates base fields, reads the reactively-computed merged state, and traces *why* any value is what
it is. Agents have no standard way to maintain structured world state — MCP is that standard way,
and this server is the doorway to it.

| | |
|---|---|
| Module | [`valem-mcp`](../../valem-mcp) |
| Transport | MCP over **stdio** — newline-delimited JSON-RPC 2.0 |
| Runtime deps | `valem-service` + `valem-cli-common` + `jackson-databind` (no Spring, no MCP SDK) |
| Storage | in-memory for the process lifetime by default; durable/shared with `--url` (see [Modes](#modes-embedded-vs-remote)) |
| Main class | `org.json_kula.valem.mcp.McpServer` |

It is a thin adapter over the same pure-Java [`ModelService`](../architecture/overview.md#service-layer-modelservice--valem-service)
that backs the REST API and the console, so the reactive pipeline, constraints, and effects behave
identically to every other access surface.

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
| `get_state` | `GET /models/{id}/state` | `id` | `at` (ISO-8601) |
| `get_field` | `GET /models/{id}/state/{path}` | `id`, `path` | — |
| `mutate` | `POST /models/{id}/mutations` | `id`, `mutations` (`{"$.path": value}`) | — |
| `explain` | `GET /models/{id}/explain/{path}` | `id`, `path` | — |
| `get_history` | `GET /models/{id}/history` | `id` | — |
| `evolve_spec` | `POST /models/{id}/spec/evolve` | `id`, `evolution` (a SpecEvolution) | — |
| `delete_model` | `DELETE /models/{id}` | `id` | — |
| `get_view` | `GET /models/{id}/view[/{viewId}]` | `id` | `viewId` |

Blobs and snapshot/restore are intentionally **not** exposed — the MCP surface targets the
agent-state use case (create, mutate, read, explain, evolve), and binary/base64 payloads are a poor
fit for a tool-call channel. Use the REST API for those.

`create_model` returns the model info (id + version + derivation/meta/constraint/effect counts);
`evolve_spec` returns the new version.

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

### Result shape

A successful `tools/call` returns a `text` content block whose text is the operation's JSON result
(pretty-printed); when the result is a JSON object it is **also** returned as `structuredContent`, so a
client can consume it without re-parsing the text. (Array/scalar results — `list_models`,
`get_history`, `explain`, a scalar `get_field` — are conveyed via the text block only, since MCP
structured content is defined as an object.)

A tool-level failure — an unknown model id, a schema violation, a ROLLBACK constraint — is returned as
a result with `isError: true` (per the MCP tool-error convention), **not** as a JSON-RPC protocol
error, so the model sees the failure and can react. A ROLLBACK constraint violation is surfaced
**structurally**: both the text block and `structuredContent` carry
`{ "error": "Constraint violation", "violations": [ { "constraintId", "message", "policy" } ] }`, so
the agent knows exactly which constraint failed and why. Malformed protocol requests (bad method,
missing `name`) return JSON-RPC errors.

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
| `valem://schema/model-spec` | `application/json` | The full ModelSpec JSON Schema. |
| `valem://schema/spec-evolution` | `application/json` | The SpecEvolution JSON Schema. |
| `valem://examples/<name>` | `application/json` | Each bundled example spec (e.g. `insurance-quote`, `car-loan-calculator`) — working models to learn from. |

Standard MCP flow: `resources/list` enumerates them, `resources/read` (with a `uri`) returns
`{ contents: [ { uri, mimeType, text } ] }`. An unknown uri returns JSON-RPC error `-32002`.

## Protocol notes

- **stdio framing** — one JSON message per line; messages never contain embedded newlines (JSON
  string values escape them). **Only protocol messages go to stdout**; all diagnostics go to stderr.
- **Handshake** — `initialize` → capabilities + server info + usage `instructions`;
  `notifications/initialized` is accepted with no reply; `ping` returns `{}`. A leading UTF-8 BOM on
  the input stream (some Windows launchers prepend one) is tolerated.
- **Protocol version** — the server negotiates `2024-11-05`, `2025-03-26`, or `2025-06-18`, echoing
  the client's requested version when supported and otherwise falling back to the newest.
- **Capabilities** — `tools` and `resources` are advertised (no prompts).

## Storage caveat

All state is in memory for the life of the process — nothing is persisted, and each client launches
its own instance. For durable, shared, multi-client state, front the REST API (`valem-api`) with
a persistence backend instead. This mirrors `valem-console`; the MCP server is for driving a
Valem model from within an agent session, not for operating a shared deployment.
