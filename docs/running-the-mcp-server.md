---
title: Running the MCP server
nav_order: 4
description: "Expose Valem over the Model Context Protocol so an AI agent can create, mutate, and verify models."
---

# Running the MCP server
{: .no_toc }

`valem-mcp` exposes Valem over the [Model Context Protocol](https://modelcontextprotocol.io) so an AI
agent — Claude Code, Claude Desktop, or any MCP client — can create, mutate, and verify models
directly. It speaks JSON-RPC 2.0 over stdio and depends only on the pure service layer (no Spring, no
HTTP server).
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Build the server jar

```bash
mvn install -pl valem-core,valem-service,valem-view -q
mvn package -pl valem-mcp -q
# → valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar
```

## Register it with an MCP client

MCP clients launch the server as a subprocess. Point yours at the built jar.

### Claude Code / Claude Desktop

Add a `.mcp.json` at your project root (Claude Code) or to your Desktop MCP config:

```json
{
  "mcpServers": {
    "valem": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/valem-mcp/target/valem-mcp-1.0.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

Restart the client; the agent can now list and call Valem's tools.

## What the agent gets

**Tools** (16) map onto `ModelService` — model CRUD plus authoring/verification:

- **Lifecycle:** `create_model`, `mutate`, `get_state`, `get_spec`, `explain`, `evolve_spec`,
  `snapshot`, `restore`, `delete_model`, `list_models`
- **Authoring & verify:** `validate_spec`, `eval_expression`, `test_spec`, `dry_run` — the agent
  generates a spec, Valem verifies it before anything is committed.

**Resources** expose the ModelSpec authoring guide, the JSON schemas, and bundled example specs, so
the agent can study the format and working models before writing one. State is in-memory (mirroring
the console); durable, shared, multi-tenant state is the REST path — see
[Running the API]({% link running-the-api.md %}).

## A typical agent loop

1. The agent reads the spec-format resource and an example.
2. It drafts a `ModelSpec` and calls `validate_spec` / `eval_expression` to check its expressions.
3. It calls `create_model`, then `mutate` to exercise the model and `explain` to inspect why a value
   or constraint resolved as it did.
4. It refines with `evolve_spec` — an incremental diff that preserves live state.

Because the pure core is deterministic and side-effect-free, the agent can dry-run freely without
touching the outside world.

## Pairing with a browser

Adding `--browser` to `--url` pairs the MCP with a browser tab on that host (e.g. the hosted Valem
sandbox) instead of an API key, so the agent and a human in the browser drive **one shared, live
model** — the agent's `evolve_spec` pushes the browser to re-render automatically, with no
copy-paste. See [MCP server guide: pairing with a browser]({% link guides/mcp-server.md %}#pairing-with-a-browser-remote_with_browser-mode)
for the full handshake and the `pair_browser` tool.

For the full tool-by-tool reference and workflow patterns, see the
[MCP server guide]({% link guides/mcp-server.md %}).
