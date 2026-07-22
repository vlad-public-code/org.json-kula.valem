---
title: Running the web API
parent: Deployment
nav_order: 1
description: "Run the Spring Boot deployable: REST + WebSocket, LLM spec generation, durable storage, and the management UI."
redirect_from:
  - /running-the-api.html
---

# Running the web API
{: .no_toc }

`valem-web` is the runnable Spring Boot deployable; `valem-api` is the headless library it wraps. It
exposes the model runtime over REST + WebSocket, serves the management UI, and — optionally — an MCP
endpoint and LLM-powered spec generation.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Build and run

From the repository root:

```bash
# Build the engine, then start the server on port 8080
mvn install -pl valem-core,valem-service -q
mvn spring-boot:run -pl valem-web
```

The API is now at `http://localhost:8080`, with the management SPA served at `/`. Storage is
in-memory by default. Add `-Dskip.frontend=true` for a REST-only, backend-fast build (it skips the
Node build of `valem-ui`).

For a deployable artifact:

```bash
mvn package -pl valem-web
java -jar valem-web/target/valem-web-1.0.0-SNAPSHOT.jar
```

## What it exposes

| Surface | Path | Notes |
|---|---|---|
| REST | `/models/**`, `/blobs/**` | Full list in the [API reference]({% link reference/api-reference.md %}) |
| WebSocket | `/models/{id}/subscribe` | A `ChangeEvent` after every committed mutation |
| MCP over HTTP | `/mcp` | Same tool surface as the stdio server — see [Running the MCP server](mcp-server.md) |
| Management UI | `/` | The `valem-ui` SPA, built and served by `valem-web` |
| LLM generation | `/models/generate`, `/models/generate/preview` | `503` unless a provider is configured |

### REST at a glance

| Method | Path | Description |
|---|---|---|
| `GET` | `/models` | List all registered model IDs |
| `POST` | `/models` | Create a model from a spec |
| `GET` | `/models/{id}` | Model info (version, counts) |
| `POST` | `/models/{id}/mutations` | Apply field mutations |
| `POST` | `/models/{id}/mutations/patch` | Apply an RFC 6902 JSON Patch |
| `GET` | `/models/{id}/state` | Full merged state (base + derived); `?at=` for point-in-time |
| `GET` | `/models/{id}/schema/{path}` | Effective JSON Schema for a field |
| `GET` | `/models/{id}/explain/{path}` | Constraint / derivation evaluation traces |
| `GET` | `/models/{id}/audit` | Durable, tamper-evident audit trail |
| `POST` | `/models/{id}/snapshot` · `/restore` | Capture / restore a state snapshot |
| `POST` | `/models/{id}/spec/evolve` | Incremental spec evolution |
| `DELETE` | `/models/{id}` | Remove a model |
| `POST` | `/blobs` · `GET /blobs/{id}` | Content-addressed binary storage |
| `WS` | `/models/{id}/subscribe` | Push a `ChangeEvent` after each mutation |
| `POST` | `/models/generate/preview` · `/generate` | Build a prompt / generate a validated spec |

Request/response shapes, the WebSocket protocol, and the console command reference are in the
[API reference]({% link reference/api-reference.md %}) — the single source of truth.

## Example: create and mutate

```bash
# Create a model with a derived total and a rollback constraint
curl -s -X POST http://localhost:8080/models \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "order", "version": "1.0.0", "schema": {},
    "derivations":  [{ "path": "$.order.total", "expr": "order.subtotal + order.tax" }],
    "constraints":  [{ "id": "max-order", "expr": "order.total <= 5000",
                       "message": "Order exceeds the cap", "policy": "rollback" }]
  }'

# Mutate base fields — total derives automatically
curl -s -X POST http://localhost:8080/models/order/mutations \
  -H 'Content-Type: application/json' \
  -d '{ "$.order.subtotal": 200, "$.order.tax": 20 }'

# Read merged state
curl -s http://localhost:8080/models/order/state
```

## Durable storage

State is in-memory by default and lost on restart. Persistence is per concern — spec, state, and
blob each select a backend independently:

```yaml
valem:
  persistence-dir: /var/valem/data   # filesystem spec + state
  blob-store: filesystem
  blob-store-path: /var/valem/blobs
```

`memory` and `filesystem` work out of the box. PostgreSQL, MongoDB, Redis, and S3 ship as
à-la-carte adapter jars discovered at runtime — bundle one into the web jar with a convenience
profile, then select it:

```bash
mvn -Pweb-postgres -pl valem-web package     # also: -Pweb-mongo | -Pweb-redis | -Pweb-s3
java -jar valem-web/target/valem-web-1.0.0-SNAPSHOT.jar \
  --valem.storage.type=postgres --spring.datasource.url=jdbc:postgresql://localhost:5432/valem
```

Selecting a backend whose adapter jar is absent **aborts startup** naming the jar to add — there is
no silent fallback. Run modes, compaction, and the audit trail are covered in
[Persistence & operations](operations.md); every property is in
[Configuration](configuration.md).

## Enable LLM spec generation

Configure a provider before starting to unlock the `/models/generate` endpoints. Without one, the
server still runs and those endpoints return `503`.

```bash
# Anthropic (default provider)
export VALEM_LLM_API_KEY=sk-ant-...
mvn spring-boot:run -pl valem-web

# OpenAI
VALEM_LLM_PROVIDER=openai VALEM_LLM_MODEL=gpt-4o \
  VALEM_LLM_API_KEY=$OPENAI_API_KEY mvn spring-boot:run -pl valem-web

# Ollama (local, no key)
VALEM_LLM_PROVIDER=ollama VALEM_LLM_MODEL=llama3 mvn spring-boot:run -pl valem-web
```

The key is always read from `valem.llm.api-key` (env `VALEM_LLM_API_KEY`) — there is no
provider-specific fallback such as an implicit `ANTHROPIC_API_KEY` read. Providers supported:
`anthropic`, `openai`, `ollama`, `openrouter`, `groq`, `mistral`, `gemini`, `cerebras`.

Workflow and the repair loop: [Generating specs with an LLM]({% link model-guide/generating-specs-with-llm.md %}).

## Before you expose it

The API is **open by default** — a development posture, not a production one. At minimum, set
`valem.api.key`, decide on `valem.websocket.allowed-origins` and `valem.mcp.allowed-origins`, and
review the egress guard. See [Security model](security-model.md).

## The management UI

A React SPA (`valem-ui`) is built and served at `/` by `valem-web`. For live development, run it
against the backend separately:

```bash
cd valem-ui
npm install
npm run dev        # http://localhost:5173, proxies /models + /blobs to :8080
```

## Next

- [Configuration](configuration.md) — every `valem.*` property.
- [Persistence & operations](operations.md) — run modes, compaction, blobs, audit.
- [Security model](security-model.md) — auth, egress, limits.
- [Client SDKs]({% link extending/client-sdks.md %}) — typed Java and TypeScript clients.
