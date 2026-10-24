# Valem

**A spreadsheet engine for AI agents.** An LLM describes a domain as a JSON spec (fields, formulas,
invariants); Valem compiles it into a live model that recomputes every dependent value on each change,
refuses changes that break a rule, and can explain why any number is what it is.

[![Live sandbox](https://img.shields.io/badge/try_it-valem.run-0969da)](https://valem.run/)
[![Docs](https://img.shields.io/badge/docs-valem.run%2Fdocs-0969da)](https://valem.run/docs/)
[![MCP server](https://img.shields.io/badge/MCP-valem.run%2Fmcp-6e40c9)](https://valem.run/docs/getting-started/connect-your-agent.html)
[![Release](https://img.shields.io/github/v/release/vlad-public-code/org.json-kula.valem)](https://github.com/vlad-public-code/org.json-kula.valem/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

## Why

LLMs are good at *describing* a domain and bad at *keeping state consistent* over it. Ask one to
maintain a quote, an order, or a loan application across twenty turns and the total drifts from its
line items, a limit checked three messages ago is forgotten, and arithmetic is off by a rounding step.

Valem moves the rules out of the conversation and into a deterministic runtime:

- **Derivations** are JSONata formulas over JSON paths, recomputed in dependency order (only what a
  change touches).
- **Constraints** are invariants checked before a change commits: roll it back or flag it.
- **Effects** (HTTP calls, LLM calls, timers) are emitted as data and run after the commit, so replay
  never re-fires I/O.
- **Explain and audit**: every value has a derivation trace, and the audit trail is hash-chained.

The agent writes the spec; Valem validates it, tests it, and keeps it true.

## Try it in 30 seconds

**In the browser, no install.** Open [valem.run](https://valem.run/), describe a model in plain
words ("a mortgage with overpayments", "an invoice with VAT and discounts"), then type into the live
form and watch every derived value update.

**From your agent (MCP, hosted, nothing to download):**

```bash
# Claude Code
claude mcp add --transport http valem https://valem.run/mcp
```

Claude Desktop: Settings → Connectors → *Add custom connector* → `https://valem.run/mcp`. Then ask
the agent to call `pair_browser` and it will build models you see live in a browser tab. The
authoring tools (`validate_spec`, `eval_expression`, `test_spec`, `dry_run`) work without pairing.
Guide: [Connect your agent](https://valem.run/docs/getting-started/connect-your-agent.html).

**Self-hosted with Docker:**

```bash
docker run --rm -p 8080:8080 ghcr.io/vlad-public-code/valem:latest
# → http://localhost:8080 (management UI, REST + WebSocket API, and /mcp)
```

## What a spec looks like

```json
{
  "id": "order",
  "version": "1.0.0",
  "schema": {},
  "derivations": [
    { "path": "$.order.items[*].lineTotal", "expr": "$parent.qty * $parent.price" },
    { "path": "$.order.total", "expr": "$sum(order.items.(qty * price))" }
  ],
  "constraints": [
    { "id": "max-order", "expr": "order.total <= 5000",
      "message": "Order exceeds the cap", "policy": "rollback" }
  ]
}
```

Write `qty` on any line item and `lineTotal` and `total` recompute; a write that pushes the total
over 5,000 is rejected before it commits. Full format:
[ModelSpec reference](https://valem.run/docs/reference/model-spec-format.html).

## Ways to run it

| You want | Use |
|---|---|
| An agent that maintains structured state | The [MCP server](https://valem.run/docs/deployment/mcp-server.html), hosted (`valem.run/mcp`) or self-hosted (stdio jar from [Releases](https://github.com/vlad-public-code/org.json-kula.valem/releases)) |
| A backend shared by many clients | [`valem-web`](https://valem.run/docs/deployment/web-api.html): REST + WebSocket, pluggable storage (filesystem, Postgres, Mongo, Redis, S3) |
| The engine inside your JVM app | [Embed `valem-core`/`valem-service`](https://valem.run/docs/extending/embedding.html) |
| Scripting with no server | The [JSON console](https://valem.run/docs/reference/api-reference.html#3-console-json-protocol) (one JSON command per line on stdin) |
| A live model inside your web page | [Embed a live model](https://valem.run/docs/usage-scenarios/embed-a-live-model.html) |

Clients: [TypeScript and Java SDKs](https://valem.run/docs/extending/client-sdks.html).

## Documentation

Published at **[valem.run/docs](https://valem.run/docs/)** (source in [`docs/`](docs/README.md)),
organised in six chapters: **Getting started**, **Usage scenarios**, **Model guide**, **Reference**,
**Deployment**, **Extending**. Good first reads:

- [What is Valem?](https://valem.run/docs/getting-started/what-is-valem.html): the idea in five minutes
- [Valem vs alternatives](https://valem.run/docs/getting-started/compared.html): how it compares to code interpreters, rules engines, form libraries and spreadsheets
- [FAQ, and when not to use Valem](https://valem.run/docs/getting-started/faq.html)
- [ModelSpec format](docs/reference/model-spec-format.md): the spec format, single source of truth
- [API reference](docs/reference/api-reference.md): REST, WebSocket, and console protocol
- [MCP tools](docs/reference/mcp-tools.md): every tool and resource the MCP server exposes
- [Configuration](docs/deployment/configuration.md): every `valem.*` property
- [Security model](docs/deployment/security-model.md): auth, effect egress/SSRF, limits
- [Architecture](docs/extending/architecture.md): component map, data flow, design decisions
- For agents: [llms.txt](https://valem.run/docs/llms.txt)

## Building from source

### Prerequisites

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
[configuration.md](docs/deployment/configuration.md#persistence-model-spec--state). For durable
setups, persistence layout, and the hardening checklist see
[operations.md](docs/deployment/operations.md).

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
[configuration.md](docs/deployment/configuration.md#llm-integration).

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

See [generating-specs-with-llm.md](docs/model-guide/generating-specs-with-llm.md) for the workflow and
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
