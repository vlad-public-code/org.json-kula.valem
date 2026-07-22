---
title: Quickstart
parent: Getting started
nav_order: 3
description: "Run Valem three ways: the console, the backend API, and the developer UI."
redirect_from:
  - /guides/getting-started.html
---

# Quickstart
{: .no_toc }

Run Valem on your own machine three ways: the console (fastest, no server), the backend API, and the
developer UI. For full endpoint details see
[../reference/api-reference.md](../reference/api-reference.md); for every config knob see
[../deployment/configuration.md](../deployment/configuration.md).

Want to try it without installing anything first? [Use the hosted sandbox](sandbox.md).

{: .tip }
> No Java or Maven on this machine? The whole server runs in a container:
>
> ```bash
> docker build -t valem . && docker run --rm -p 8080:8080 valem
> ```
>
> See [Running the web API](../deployment/web-api.md#run-with-docker).

1. TOC
{:toc}

## Prerequisites
- Java 21+, Maven 3.9+
- Node.js 20+ and npm 9+ (UI only)

## Console app (no HTTP server)
Reads one JSON command per line from stdin, writes one JSON response per line. Ideal for agents
and scripts. State is in-memory for the process lifetime.

```bash
mvn install -pl valem-core,valem-service -q
mvn package -pl valem-console -q
java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
# or:
echo '{"cmd":"list-models"}' | java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
```

Example session:
```jsonc
{"cmd":"create-model","spec":{"id":"order","version":"1","schema":{},"derivations":[{"path":"$.total","expr":"subtotal + tax"}],"constraints":[],"metaDerivations":[],"tests":[],"defaultValues":[]}}
{"cmd":"mutate","id":"order","mutations":{"subtotal":100,"tax":8}}
{"cmd":"get-state","id":"order"}
```

Full command list: [../reference/api-reference.md](../reference/api-reference.md#3-console-json-protocol).

## Backend API

`valem-web` is the runnable open web deployable (the executable Spring Boot jar);
`valem-api` is a headless library it wraps.

```bash
mvn install -pl valem-core,valem-service -q
mvn spring-boot:run -pl valem-web      # REST API + management UI at http://localhost:8080
```

`valem-web` also builds and serves the management SPA (`valem-ui`) at `/`. Add
`-Dskip.frontend=true` for a REST-only, backend-fast build. (For live UI development with hot reload,
run the SPA separately — see [Running the developer UI](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/README.md#running-the-developer-ui).)

Storage defaults to in-memory (lost on restart). Persistence is à-la-carte: `memory` and `filesystem`
work out of the box, and every other backend is a separate adapter jar discovered at runtime. Build a
runnable jar that bundles one via a convenience profile, then select it with `valem.storage.*`:

```bash
mvn -Pweb-postgres -pl valem-web package        # jar bundles the Postgres adapter
# also: -Pweb-mongo | -Pweb-redis | -Pweb-s3, or combine e.g. -Pweb-postgres,web-s3
java -jar valem-web/target/valem-web-1.0.0-SNAPSHOT.jar \
  --valem.storage.type=postgres --spring.datasource.url=jdbc:postgresql://localhost:5432/valem
```

Selecting a backend whose adapter jar is absent aborts startup naming the jar to add — no silent
fallback. See [../deployment/configuration.md](../deployment/configuration.md#persistence-model-spec--state).

Create and mutate a model:
```bash
curl -s -X POST http://localhost:8080/models -H 'Content-Type: application/json' -d '{
  "id":"order","version":"1.0.0","schema":{},
  "derivations":[{"path":"$.order.total","expr":"order.subtotal + order.tax"}],
  "constraints":[{"id":"max-order","expr":"order.total <= 5000","message":"Over cap","policy":"rollback"}],
  "metaDerivations":[],"tests":[]
}'
curl -s -X POST http://localhost:8080/models/order/mutations -H 'Content-Type: application/json' \
  -d '{ "$.order.subtotal": 200, "$.order.tax": 20 }'
curl -s http://localhost:8080/models/order/state
```

To enable LLM spec generation, configure a provider before starting — see
[generating-specs-with-llm.md](../model-guide/generating-specs-with-llm.md). Without one, `/models/generate*`
returns 503.

## Developer UI
```bash
cd valem-ui
npm install      # first time
npm run dev      # http://localhost:5173
```
The UI proxies `/models` and `/blobs` (and WebSocket) to the backend, which must be running.

## Tests
```bash
mvn test                      # all modules
mvn test -pl valem-core  # faster, no Spring context
```
Browser E2E (Playwright) lives in `valem-e2e` (backend must be on :8080). The UI is served from the
Vite dev server (:5173) while the API answers on :8080, so a WebSocket handshake's `Origin` header
is `http://localhost:5173` even though the proxy makes REST calls look same-origin. `WebSocketConfig`
defaults to same-origin only and silently rejects that handshake (visible as `ws proxy socket error`
in the Vite log), which breaks every e2e scenario that asserts a live push rather than relying on
the UI's own optimistic update. Start the backend with
`VALEM_WEBSOCKET_ALLOWED_ORIGINS=http://localhost:5173` (or `-Dvalem.websocket.allowed-origins=...`)
before running the e2e suite.

## Next

- [Connect your agent](connect-your-agent.md) — drive a model from an AI agent over MCP.
- [Usage scenarios](../usage-scenarios/index.md) — where Valem fits in a real project.
- [Model guide](../model-guide/index.md) — write your own spec.
- [Persistence & operations](../deployment/operations.md) — durable storage and production config.
