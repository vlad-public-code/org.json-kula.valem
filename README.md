# Valem

Deterministic reactive computation runtime for AI-generated structured data models.
A spreadsheet-like computation model for JSON-based agent systems.

**▶ [Try the live sandbox](https://valem.onrender.com/)** — a zero-setup public demo: describe a
domain in plain language, watch an LLM generate a ModelSpec, then mutate fields and see derivations,
constraints, and effects react live.

## Documentation

Full docs live under [`docs/`](docs/README.md) — an audience-keyed index. Canonical references
(this README is a quickstart; the detail lives in these docs and is deliberately not duplicated here):

- [ModelSpec format](docs/reference/model-spec-format.md) — the spec format, single source of truth
- [API reference](docs/reference/api-reference.md) — REST, WebSocket, and console protocol
- [Configuration](docs/reference/configuration.md) — every `valem.*` property
- [Security model](docs/reference/security-model.md) — auth, effect egress/SSRF, limits
- [Architecture](docs/architecture/overview.md) — component map, data flow, design decisions
- [Third-party libraries](docs/README.md#third-party-libraries) — what Valem builds on (Apache-2.0)

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 20+ and npm 9+ (UI only)

## Running the console app (no HTTP server required)

The console app is the fastest way to use Valem from a script or an AI agent. It reads one JSON
command per line from `stdin` and writes one JSON response per line to `stdout`. No HTTP, no
browser, no server process. All state is held in memory for the lifetime of the process.

```bash
# Build the fat jar (first time)
mvn install -pl valem-core,valem-service -q
mvn package -pl valem-console -q

# Run interactively
java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar

# Or pipe commands
echo '{"cmd":"list-models"}' | java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
```

Example session:

```jsonc
// stdin
{"cmd":"create-model","spec":{"id":"order","version":"1.0.0","schema":{},"derivations":[{"path":"$.total","expr":"subtotal + tax"}]}}
{"cmd":"mutate","id":"order","mutations":{"$.subtotal":100,"$.tax":8}}
{"cmd":"get-state","id":"order"}

// stdout
{"ok":true,"result":{"id":"order","status":"created"}}
{"ok":true,"result":{"success":true,"mutatedPaths":["$.subtotal","$.tax"],"derivedUpdated":["$.total"],...}}
{"ok":true,"result":{"subtotal":100,"tax":8,"total":108}}
```

Full command list: [console JSON protocol](docs/reference/api-reference.md#3-console-json-protocol).

## Running the backend

```bash
# From the repo root — build and start the Spring Boot server on port 8080.
# valem-web is the runnable deployable; valem-api is the headless library it wraps.
mvn install -pl valem-core,valem-service -q
mvn spring-boot:run -pl valem-web
```

The API is now available at `http://localhost:8080`, with the management UI served at `/`. Storage
is in-memory by default; other backends are à-la-carte adapter jars
(`mvn -Pweb-postgres -pl valem-web package`, then `--valem.storage.type=postgres`) — see
[configuration.md](docs/reference/configuration.md#persistence-model-spec--state). For durable
setups, persistence layout, and the hardening checklist see
[deployment-and-operations.md](docs/guides/deployment-and-operations.md).

To enable LLM-powered spec generation, configure a provider before starting (the key is read from
`valem.llm.api-key`, settable as `VALEM_LLM_API_KEY`; there is no provider-specific env fallback):

```bash
# Anthropic (default provider)
export VALEM_LLM_API_KEY=sk-ant-...
mvn spring-boot:run -pl valem-web

# OpenAI
VALEM_LLM_PROVIDER=openai VALEM_LLM_MODEL=gpt-4o \
  VALEM_LLM_API_KEY=$OPENAI_API_KEY mvn spring-boot:run -pl valem-web

# Ollama (local, no API key needed; start `ollama serve` first)
VALEM_LLM_PROVIDER=ollama VALEM_LLM_MODEL=llama3 mvn spring-boot:run -pl valem-web
```

Without any provider configured the server starts normally; the `/models/generate*` endpoints
return 503. All LLM knobs (providers, tool budgets, retries, temperatures):
[configuration.md](docs/reference/configuration.md#llm-integration).

## Running the developer UI

In a separate terminal:

```bash
cd valem-ui
npm install        # first time only
npm run dev
```

Open `http://localhost:5173` in your browser.

The UI proxies all `/models` and `/blobs` requests (including WebSocket connections) to the
backend, which must be running.

## Generating a model spec with an LLM

The UI's **✦ Generate** button drives a human-in-the-loop workflow: enter a model ID and a
plain-text domain description → **Preview Prompt** (editable) → send to the LLM → review/edit the
generated spec → **Register Model**. The same workflow is available over REST
(`POST /models/generate/preview` → `/models/generate` → `/models`).

See [generating-specs-with-llm.md](docs/guides/generating-specs-with-llm.md) for the workflow and
provider setup, and [llm-prompts.md](docs/reference/llm-prompts.md) for the exact prompts and the
validate-and-repair loop.

## Example: create and mutate a model over REST

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
      { "id": "max-order", "expr": "order.total <= 5000",
        "message": "Order exceeds the cap", "policy": "rollback" }
    ]
  }'

# Mutate base fields — total is derived automatically
curl -s -X POST http://localhost:8080/models/order/mutations \
  -H 'Content-Type: application/json' \
  -d '{ "$.order.subtotal": 200, "$.order.tax": 20 }'

# Read merged state
curl -s http://localhost:8080/models/order/state | python -m json.tool
```

Every endpoint (audit, snapshots, views, blobs, spec evolution, composition, …):
[api-reference.md](docs/reference/api-reference.md).

## Running tests

```bash
# All modules
mvn test

# Core only (faster — no Spring context)
mvn test -pl valem-core
```

## Running end-to-end tests

The `valem-e2e` module contains Playwright browser tests that drive the full stack (backend + UI).

**Prerequisites:** the backend must be running on port 8080 (see
[Running the backend](#running-the-backend)). The UI dev server is started automatically by
Playwright.

```bash
cd valem-e2e
npm install              # first time only
npx playwright install   # download browser binaries (first time only)

npm test                 # headless Chromium
npm run test:headed      # watch the browser
npm run test:ui          # Playwright interactive UI mode
npm run report           # open the last HTML report
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
