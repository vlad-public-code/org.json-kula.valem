# Getting Started

Run Valem three ways: the console (fastest, no server), the backend API, and the developer
UI. For full endpoint details see [../reference/api-reference.md](../reference/api-reference.md);
for every config knob see [../reference/configuration.md](../reference/configuration.md).

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
run the SPA separately — see [Running the developer UI](../../README.md#running-the-developer-ui).)

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
fallback. See [../reference/configuration.md](../reference/configuration.md#persistence-model-spec--state).

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
[generating-specs-with-llm.md](generating-specs-with-llm.md). Without one, `/models/generate*`
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
Browser E2E (Playwright) lives in `valem-e2e` (backend must be on :8080).

Next: [deployment-and-operations.md](deployment-and-operations.md) for persistence and production config.
