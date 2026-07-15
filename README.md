# Valem

Deterministic reactive computation runtime for AI-generated structured data models.
A spreadsheet-like computation model for JSON-based agent systems.

## Documentation

Full docs live under [`docs/`](docs/README.md) — an audience-keyed index. Canonical references:
[API](docs/reference/api-reference.md) · [ModelSpec format](docs/reference/model-spec-format.md) ·
[Configuration](docs/reference/configuration.md) · [Security model](docs/reference/security-model.md) ·
[Architecture](docs/architecture/overview.md). This README is a quickstart; the canonical detail
lives in those docs.

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+ and npm 9+
- Local Maven snapshot dependencies installed in `~/.m2`:
  - `io.github.vlad-public-code:tracked-json:1.0.0`
  - `io.github.vlad-public-code:jsonata-jvm-compiler:1.0.3`

## Running the console app (no HTTP server required)

The console app is the fastest way to use Valem from a script or an AI agent. It reads one JSON command per line from `stdin` and writes one JSON response per line to `stdout`. No HTTP, no browser, no server process.

```bash
# Build the fat jar (first time)
mvn install -pl valem-core,valem-service -q
mvn package -pl valem-console -q

# Run interactively
java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar

# Or pipe commands
echo '{"cmd":"list-models"}' | java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
```

All state is held in memory for the lifetime of the process.

### Console command reference

Every request is a JSON object with a `"cmd"` field. Every response has `"ok": true` and a `"result"` field, or `"ok": false` and an `"error"` string.

| Command | Required fields | Optional fields |
|---|---|---|
| `list-models` | — | — |
| `create-model` | `spec` (full ModelSpec object) | — |
| `get-spec` | `id` | — |
| `get-info` | `id` | — |
| `get-state` | `id` | `at` (ISO-8601) |
| `get-field` | `id`, `path` (`$.`-prefixed) | — |
| `mutate` | `id`, `mutations` (path→value map) | — |
| `patch-mutate` | `id`, `patch` (RFC 6902 array) | — |
| `get-history` | `id` | — |
| `get-schema` | `id`, `path` | — |
| `explain` | `id`, `path` | — |
| `snapshot` | `id` | — |
| `restore` | `id`, `snapshot` (Snapshot object) | — |
| `evolve-spec` | `id`, `evolution` (SpecEvolution object) | — |
| `delete-model` | `id` | — |
| `upload-blob` | `data` (base64), `mediaType` | — |
| `get-blob` | `blobId` | — |
| `get-model-blob` | `id`, `blobId` | — |
| `help` | — | — |
| `exit` | — | — |

### Console example

```jsonc
// stdin
{"cmd":"create-model","spec":{"id":"order","version":"1","schema":{},"derivations":[{"path":"$.total","expr":"subtotal + tax"}],"constraints":[],"actions":[],"metaDerivations":[],"tests":[],"initialState":{}}}
{"cmd":"mutate","id":"order","mutations":{"subtotal":100,"tax":8}}
{"cmd":"get-state","id":"order"}

// stdout
{"ok":true,"result":{"id":"order","status":"created"}}
{"ok":true,"result":{"success":true,"mutatedPaths":["subtotal","tax"],"derivedUpdated":["$.total"],...}}
{"ok":true,"result":{"subtotal":100,"tax":8,"total":108}}
```

---

## Running the backend

```bash
# From the repo root — build and start the Spring Boot server on port 8080.
# valem-web is the runnable deployable; valem-api is the headless library it wraps.
mvn install -pl valem-core,valem-service -q
mvn spring-boot:run -pl valem-web
```

The API is now available at `http://localhost:8080`. Storage is in-memory by default; other backends
are à-la-carte adapter jars (`mvn -Pweb-postgres -pl valem-web package`, then
`--valem.storage.type=postgres`) — see
[docs/reference/configuration.md](docs/reference/configuration.md#persistence-model-spec--state).

To enable LLM-powered spec generation, configure an LLM provider before starting.

**Anthropic (default)**
```bash
export ANTHROPIC_API_KEY=sk-ant-...
mvn spring-boot:run -pl valem-web
```

**OpenAI**
```bash
export OPENAI_API_KEY=sk-...
VALEM_LLM_PROVIDER=openai VALEM_LLM_MODEL=gpt-4o \
  VALEM_LLM_API_KEY=$OPENAI_API_KEY \
  mvn spring-boot:run -pl valem-web
```

**Ollama (local, no API key needed)**
```bash
# Start Ollama first: ollama serve
VALEM_LLM_PROVIDER=ollama VALEM_LLM_MODEL=llama3 \
  mvn spring-boot:run -pl valem-web
```

Without any provider configured the server starts normally; the `/models/generate` endpoints return 503.

## Running the developer UI

In a separate terminal:

```bash
cd valem-ui
npm install        # first time only
npm run dev
```

Open `http://localhost:5173` in your browser.

The UI proxies all `/models` and `/blobs` requests to the backend, including WebSocket connections. The backend must be running for the UI to work.

## Generating a model spec with Claude

The UI has a **✦ Generate** button in the sidebar that drives a full human-in-the-loop workflow:

1. Enter a model ID and a plain-text description of the domain.
2. Click **Preview Prompt** — the exact text that will be sent to Claude is shown in an editable textarea.
3. Tweak the prompt if needed, then click **Send to Claude**.
4. Review the generated spec JSON (also editable). If the result is wrong, click **← Re-generate** to go back and refine the prompt.
5. Click **Register Model** to create the model and navigate to its state panel.

The same workflow is also available via REST:

```bash
# 1. Preview the prompt (no LLM call)
curl -s -X POST http://localhost:8080/models/generate/preview \
  -H 'Content-Type: application/json' \
  -d '{"modelId":"order","domainDescription":"E-commerce order with line items, subtotal, 8% tax, and total"}'

# 2. Send the (optionally edited) prompt to Claude
curl -s -X POST http://localhost:8080/models/generate \
  -H 'Content-Type: application/json' \
  -d '{"modelId":"order","prompt":"<prompt from step 1>"}'

# 3. Register the returned spec
curl -s -X POST http://localhost:8080/models \
  -H 'Content-Type: application/json' \
  -d '<spec from step 2>'
```

### Configuration

| Property | Default | Description |
|---|---|---|
| `valem.llm.provider` | `anthropic` | LLM provider: `anthropic`, `openai`, or `ollama` |
| `valem.llm.api-key` | *(env `ANTHROPIC_API_KEY`)* | API key for the chosen provider; not required for `ollama` |
| `valem.llm.model` | `claude-sonnet-4-6` | Model name (e.g. `gpt-4o` for OpenAI, `llama3` for Ollama) |
| `valem.llm.max-tokens` | `8192` | Max tokens in the LLM response |
| `valem.llm.max-retries` | `3` | Retries for the automated `SpecGenerator` feedback loop |
| `valem.llm.base-url` | *(provider default)* | Override the API endpoint URL (e.g. a self-hosted OpenAI-compatible server) |

Provider defaults: Anthropic uses `https://api.anthropic.com/v1/messages`; OpenAI uses `https://api.openai.com/v1`; Ollama uses `http://localhost:11434/v1`. Set `base-url` to point at any OpenAI-compatible service (LM Studio, vLLM, etc.).

## Storage

### Model persistence

By default all model state is held in memory and lost on restart. Set `valem.persistence-dir` to enable durable storage:

```yaml
valem:
  persistence-dir: /var/valem/data
```

When set, the directory layout is:

```
{persistence-dir}/
  {modelId}/
    spec.json       — ModelSpec (written on create and spec evolve)
    snapshot.json   — latest committed state (written after every mutation and restore)
```

Writes are atomic (write to `.tmp` then rename), so a crash mid-write leaves the previous version intact. All models are reloaded automatically at startup.

### Blob storage

Binary data uploaded via `POST /blobs` is stored separately:

| `valem.blob-store` | Behaviour |
|---|---|
| `memory` (default) | In-process `ConcurrentHashMap`, keyed by SHA-256; lost on restart |
| `filesystem` | Files under `valem.blob-store-path` (default: `~/.valem/blobs`) |

### Production-ready configuration

To survive restarts, set both stores:

```yaml
valem:
  persistence-dir: /var/valem/data
  blob-store: filesystem
  blob-store-path: /var/valem/blobs
```

## Running tests

```bash
# All modules
mvn test

# Core only (faster — no Spring context)
mvn test -pl valem-core
```

## Running end-to-end tests

The `valem-e2e` module contains Playwright browser tests that drive the full stack (backend + UI).

**Prerequisites:** the backend must be running on port 8080 (see [Running the backend](#running-the-backend)). The UI dev server is started automatically by Playwright.

```bash
cd valem-e2e
npm install              # first time only
npx playwright install   # download browser binaries (first time only)

npm test                 # headless Chromium
npm run test:headed      # watch the browser
npm run test:ui          # Playwright interactive UI mode
npm run report           # open the last HTML report
```

## API quick reference

| Method | Path | Description |
|---|---|---|
| GET | `/models` | List all registered model IDs |
| POST | `/models` | Create model from spec |
| GET | `/models/{id}` | Model info (version, counts) |
| POST | `/models/{id}/mutations` | Apply field mutations |
| GET | `/models/{id}/state` | Full merged state (base + derived) |
| GET | `/models/{id}/schema/{path}` | Effective JSON Schema for a field |
| GET | `/models/{id}/explain/{path}` | Constraint evaluation traces |
| POST | `/models/{id}/snapshot` | Capture state snapshot |
| POST | `/models/{id}/restore` | Restore from snapshot |
| POST | `/models/{id}/spec/evolve` | Incremental spec evolution |
| DELETE | `/models/{id}` | Remove model |
| POST | `/blobs` | Upload binary (multipart `file` field) |
| GET | `/blobs/{blobId}` | Stream stored binary |
| WS | `/models/{id}/subscribe` | Receive `ChangeEvent` after each mutation |
| POST | `/models/generate/preview` | Build prompt from domain description (no LLM call) |
| POST | `/models/generate` | Send prompt to Claude, return validated spec |

## Example: create and mutate a model

```bash
# Create a model
curl -s -X POST http://localhost:8080/models \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "order",
    "version": "1.0.0",
    "schema": {},
    "derivations": [
      { "path": "$.order.total", "expr": "order.subtotal + order.tax" }
    ],
    "constraints": [
      { "id": "max-order", "expr": "order.total <= 5000", "policy": "ROLLBACK" }
    ],
    "actions": [], "metaDerivations": [], "tests": []
  }'

# Mutate base fields — total is derived automatically
curl -s -X POST http://localhost:8080/models/order/mutations \
  -H 'Content-Type: application/json' \
  -d '{ "$.order.subtotal": 200, "$.order.tax": 20 }'

# Read merged state
curl -s http://localhost:8080/models/order/state | python -m json.tool
```
