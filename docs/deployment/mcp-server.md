---
title: Running the MCP server
parent: Deployment
nav_order: 2
description: "Install, run, and register valem-mcp: embedded, remote, or paired with a browser."
redirect_from:
  - /guides/mcp-server.html
  - /running-the-mcp-server.html
---

# Running the MCP server
{: .no_toc }

`valem-mcp` exposes Valem over the [Model Context Protocol](https://modelcontextprotocol.io) so any
MCP-compatible agent — Claude Code, Claude Desktop, and others — can use Valem as the
**structured-state backend for a session**.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## What it is

The agent creates a model from a declarative spec, mutates base fields, reads the
reactively-computed merged state, and traces *why* any value is what it is. Agents have no standard
way to maintain structured world state — MCP is that standard way, and this server is the doorway to
it.

| | |
|---|---|
| Module | [`valem-mcp`]({{ site.gh_repo }}/blob/main/valem-mcp) |
| Transport | MCP over **stdio** — newline-delimited JSON-RPC 2.0 (plus Streamable HTTP via `valem-web`, below) |
| Runtime deps | `valem-service` + `valem-cli-common` + `jackson-databind` (no Spring, no MCP SDK) |
| Storage | in-memory for the process lifetime by default; durable/shared with `--url` (see [Modes](#modes)) |
| Main class | `org.json_kula.valem.mcp.McpServer` |

It is a thin adapter over the same pure-Java
[`ModelService`]({% link extending/architecture.md %}#service-layer-modelservice--valem-service) that
backs the REST API and the console, so the reactive pipeline, constraints, and effects behave
identically to every other access surface. The tools it exposes are catalogued in the
[MCP tools reference]({% link reference/mcp-tools.md %}).

## Download

Grab the prebuilt, executable jar from the latest release (Java 21 required):

**[⬇ valem-mcp.jar (latest release)]({{ site.gh_repo }}/releases/latest/download/valem-mcp.jar)**

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

## Modes

The jar runs in one of three modes, selected purely by its flags. The default is unchanged from
earlier releases — zero-config, in-memory, offline.

| | Embedded (default) | Remote (`--url`) | Remote with browser (`--url --browser`) |
|---|---|---|---|
| Selected by | no `--url` | `--url <base>` (or `VALEM_URL`) | `--url <base> --browser` |
| State | in-memory, dies with the process | durable, shared, on the `valem-web` server | one shared session with a paired browser tab |
| `list_models` | this process only | every model on the server | the paired session only |
| `create_model` collisions | never | real `409` on a duplicate id | never (session-scoped ids) |
| Auth | none | server API key via `--api-key` / `VALEM_API_KEY` | a human approving the pairing |
| Authoring tools | local | **still local** | **still local** |

```bash
# Drive a shared, durable server; the API key is read from the flag or VALEM_API_KEY.
java -jar valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.internal --api-key "$KEY"
```

The pure authoring/verification tools (`validate_spec`, `eval_expression`, `test_spec`, `dry_run`)
**always run against local core**, even in remote mode — they are pure functions of their inputs, so
an agent can vet a candidate spec offline before pushing it to the shared server. Everything else
(create/mutate/get_state/explain/evolve/…) is routed to the server in remote mode. The API key is
never logged or echoed.

Browser pairing has its own walkthrough — the handshake, what it protects, and how a paired session
behaves: [Connect your agent]({% link getting-started/connect-your-agent.md %}).

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
API key:

```bash
claude mcp add valem -- \
  java -jar /absolute/path/to/valem-mcp-1.0.0-SNAPSHOT.jar --url https://valem.onrender.com --browser
```

The client launches the process, performs the `initialize` handshake, and lists the
[tools]({% link reference/mcp-tools.md %}).

## HTTP transport: an MCP endpoint on the server

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

## Storage caveat (embedded mode)

In embedded mode all state is in memory for the life of the process — nothing is persisted, and each
client launches its own instance. For durable, shared, multi-client state, front the REST API with a
persistence backend instead (`--url`, or the HTTP endpoint above). This mirrors `valem-console`; the
embedded MCP server is for driving a Valem model from within an agent session, not for operating a
shared deployment.

## Next

- [MCP tools & resources]({% link reference/mcp-tools.md %}) — the full tool surface.
- [Connect your agent]({% link getting-started/connect-your-agent.md %}) — the paired-browser loop.
- [Running the web API](web-api.md) — the durable, shared server.
