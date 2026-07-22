---
title: Deployment
nav_order: 6
has_children: true
description: "Run Valem for real: the web API, the MCP server, configuration, persistence, and security."
---

# Deployment

Everything needed to run Valem somewhere other than your laptop.

| Page | Covers |
|---|---|
| [Running the web API](web-api.md) | The `valem-web` Spring Boot deployable: REST + WebSocket, the management UI, the `/mcp` endpoint, LLM generation. |
| [Running the MCP server](mcp-server.md) | The `valem-mcp` jar: embedded, remote, or paired with a browser; registering it with a client. |
| [Configuration](configuration.md) | Every `valem.*` property, with defaults — the single source of truth. |
| [Persistence & operations](operations.md) | Run modes, durable storage, compaction, blobs, the audit trail. |
| [Security model](security-model.md) | Authentication, WebSocket/MCP origin checks, effect egress and SSRF, blob and rate limits. |

## Choosing a shape

| You want | Run |
|---|---|
| A shared, durable service your apps call | `valem-web` — REST + WebSocket, with a persistence backend |
| An agent with its own scratch state | `valem-mcp` embedded (in-memory, one process per client) |
| An agent against shared, durable models | `valem-mcp --url <server>`, or connect to `valem-web`'s `/mcp` endpoint directly |
| No server at all | The console (stdin/stdout JSON) or the [embedded engine]({% link extending/embedding.md %}) |

## Before production

Valem's defaults are chosen for a fast first run, not for exposure. Three things to change
deliberately:

1. **Set `valem.api.key`.** Blank means every request is allowed.
2. **Pin your origins.** `valem.websocket.allowed-origins` and, if you expose `/mcp`,
   `valem.mcp.allowed-origins` — an empty MCP list means open.
3. **Choose durability on purpose.** In-memory is the default; a restart loses everything.

Each is covered in [Security model](security-model.md) and [Configuration](configuration.md).
