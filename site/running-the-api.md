---
title: Running the API
nav_order: 3
---

# Running the API
{: .no_toc }

`valem-web` is the runnable Spring Boot deployable; `valem-api` is the headless library it wraps.
It exposes the model runtime over REST + WebSocket and (optionally) LLM-powered spec generation.
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

The API is now at `http://localhost:8080`. Storage is in-memory by default.

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
provider-specific fallback such as an implicit `ANTHROPIC_API_KEY` read.

Providers supported: `anthropic`, `openai`, `ollama`, `openrouter`, `groq`, `mistral`, `gemini`,
`cerebras`.

## Durable storage

State is in-memory by default and lost on restart. Enable persistence per concern (spec / state /
blob select a backend independently):

```yaml
valem:
  persistence-dir: /var/valem/data   # filesystem spec + state
  blob-store: filesystem
  blob-store-path: /var/valem/blobs
```

Backends beyond memory/filesystem — PostgreSQL, MongoDB, Redis, S3 — ship as à-la-carte adapter
jars. Bundle one into the web jar and select it:

```bash
mvn -Pweb-postgres -pl valem-web package
java -jar valem-web/target/valem-web-1.0.0-SNAPSHOT.jar --valem.storage.type=postgres
```

## REST API at a glance

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

## The management UI

A React SPA (`valem-ui`) is built and served at `/` by `valem-web`. For live development, run it
against the backend separately:

```bash
cd valem-ui
npm install
npm run dev        # http://localhost:5173, proxies /models + /blobs to :8080
```

See [Running the MCP server]({% link running-the-mcp-server.md %}) to drive models from an AI agent
instead of over HTTP.
