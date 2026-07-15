---
title: Use Valem in your project
nav_order: 2
---

# Using Valem in your project
{: .no_toc }

Valem is a multi-module Maven project. You can embed the pure-Java engine directly, wrap it in your
own Spring app, or talk to a running server from Java or TypeScript.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Prerequisites

- **Java 21+** and **Maven 3.9+**
- Two snapshot dependencies must be in your local Maven repo (`~/.m2`):
  - `io.github.vlad-public-code:tracked-json:1.0.0`
  - `io.github.vlad-public-code:jsonata-jvm-compiler:1.0.3`
- **Node.js 20+ / npm 9+** — only if you build the management UI.

## Pick the module you need

Valem is layered so you depend only on what you use. All artifacts share the group id
`io.github.vlad-public-code`.

| Module | Depend on it when you want… | Spring? |
|---|---|---|
| `valem-core` | The pure engine: compile a `ModelSpec`, mutate, derive, evaluate constraints/effects. | No |
| `valem-service` | The orchestration layer (`ModelService`) used by every front end. | No |
| `valem-view` | Evaluate a spec's `viewDefinition` into a renderer-agnostic `EvaluatedView`. | No |
| `valem-api` | A headless Spring Boot REST + WebSocket layer to embed in your own Boot app. | Yes |
| `valem-client` | A thin Java SDK (REST + reconnecting subscribe + audit) over a running server. | No |
| `valem-mcp` | An MCP server so an AI agent can drive models over stdio. | No |
| `valem-persistence-*` | À-la-carte durable backends (filesystem, Postgres, Mongo, Redis, S3). | No |

## Embed the pure engine

Add the core and service modules:

```xml
<dependency>
  <groupId>io.github.vlad-public-code</groupId>
  <artifactId>valem-core</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>io.github.vlad-public-code</groupId>
  <artifactId>valem-service</artifactId>
  <version>1.0.0</version>
</dependency>
```

Then drive a model in-process — no server, no framework:

```java
ModelService service = ModelService.inMemory();

// Register a spec (id "order") with a derived total and a rollback constraint
service.createModel(mapper.readValue(specJson, ModelSpec.class));

// Mutate base fields — derived fields recompute automatically
service.mutate("order", Map.of("$.subtotal", 200, "$.tax", 20));

JsonNode state = service.getState("order");   // { subtotal: 200, tax: 20, total: 220 }
```

See [Model spec]({% link model-spec.md %}) for the full spec format.

## Talk to a running server

### Java client

```xml
<dependency>
  <groupId>io.github.vlad-public-code</groupId>
  <artifactId>valem-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
ValemClient client = ValemClient.builder()
    .baseUrl("http://localhost:8080")
    .apiKey(System.getenv("VALEM_API_KEY"))   // optional
    .build();

client.mutate("order", Map.of("$.subtotal", 200, "$.tax", 20));

// Reconnecting subscription — receive a ChangeEvent after every mutation
client.subscribe("order", event -> System.out.println(event.derivedUpdated()));
```

### TypeScript / JavaScript SDK

An isomorphic SDK (fetch + reconnecting WebSocket) lives in the repo under
[`clients/valem-sdk-ts`]({{ site.gh_repo }}/tree/main/clients/valem-sdk-ts).

```ts
import { ValemClient } from "@valem/sdk";

const client = new ValemClient({ baseUrl: "http://localhost:8080" });
await client.mutate("order", { "$.subtotal": 200, "$.tax": 20 });
const state = await client.getState("order");

client.subscribe("order", (event) => console.log(event.derivedUpdated));
```

## No server, no code: the console

The console app reads one JSON command per line on stdin and writes one JSON response per line on
stdout — the fastest way to script Valem or wire it into an agent.

```bash
mvn install -pl valem-core,valem-service -q
mvn package -pl valem-console -q
echo '{"cmd":"list-models"}' | java -jar valem-console/target/valem-console-1.0.0-SNAPSHOT.jar
```

## Next steps

- [Running the API]({% link running-the-api.md %}) — REST + WebSocket + LLM generation
- [Running the MCP server]({% link running-the-mcp-server.md %}) — drive models from an AI agent
- [Model spec]({% link model-spec.md %}) — the declarative format Valem compiles
