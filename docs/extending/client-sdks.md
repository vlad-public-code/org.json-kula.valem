---
title: Client SDKs
parent: Extending
nav_order: 2
description: "Typed TypeScript and Java clients: REST, reconnecting subscribe, audit."
redirect_from:
  - /guides/client-sdks.html
---

# Client SDKs

Typed clients so integrators don't hand-roll HTTP + JSON-Patch + WebSocket-reconnect plumbing per
project. Both cover the same surface — REST lifecycle/mutation/read + a reconnecting change
subscription + the durable audit trail — and both throw a typed error carrying HTTP status and body.

| SDK | Location | Runtime deps | Tests |
|---|---|---|---|
| **TypeScript / JavaScript** (isomorphic) | [`clients/valem-sdk-ts`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/clients/valem-sdk-ts) | none (uses global `fetch`; `WebSocket` injectable) | `node --test` (mock HTTP server + fake WebSocket) |
| **Java** | [`valem-client`](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-client) module | `jackson-databind` + JDK `java.net.http` | JUnit (JDK `HttpServer` + fake connector) |

## Installation

Both SDKs are Apache-2.0 licensed and versioned with the server (SDK `MAJOR.MINOR` tracks the server
`MAJOR.MINOR`; a client talks to any server of the same or newer minor). Once published:

**Java** — `valem-client` on Maven Central:

```xml
<dependency>
  <groupId>io.github.vlad-public-code</groupId>
  <artifactId>valem-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

**TypeScript / JavaScript** — `@valem/sdk` on npm:

```bash
npm install @valem/sdk
```

> Release tooling is wired but not yet triggered: `mvn -Prelease deploy -pl valem-client`
> produces the signed `-sources`/`-javadoc` jars and publishes via the Central portal; `npm publish`
> (public access, `prepack` builds `dist/`) ships the npm package. Both need registry credentials.
> Until then, build locally (`mvn install -pl valem-client`; `npm install && npm run build`).

## Shared design

- **Mutations** are addressed by canonical JSON Path (`mutate(id, { "$.a.b": v })`); a `setField`
  convenience wraps a single change as an RFC 6902 JSON Patch, converting `$.items[0].qty` →
  `/items/0/qty` for you. `patch(id, ops)` takes raw JSON Patch.
- **Subscriptions** open `WS /models/{id}/subscribe`, authenticate with the API key as `?token=`,
  accept an optional `paths` prefix filter, deliver parsed `ChangeEvent`s, and **reconnect with
  exponential backoff** (0.5s → 8s) until explicitly closed.
- **Audit** exposes the durable trail (`GET /models/{id}/audit`) with path/time/limit filters —
  the queryable superset of `explain` — plus `verifyAudit(id)` to check the tamper-evident hash chain.
- Errors: any non-2xx throws `ValemError` (TS) / `ValemException` (Java) with the status
  and raw body.

## TypeScript quick start

```ts
import { ValemClient } from '@valem/sdk';
const client = new ValemClient({ baseUrl: 'http://localhost:8080', apiKey });
await client.mutate('insurance-quote', { '$.quote.applicant.age': 35, '$.quote.coverage': 100000 });
const sub = client.subscribe('insurance-quote', { onEvent: e => console.log(e.derivedUpdated) });
```

(In Node, `npm install ws` and pass `webSocketCtor: WebSocket`.) See the
[package README](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/clients/valem-sdk-ts/README.md).

## Java quick start

```java
try (var client = new ValemClient("http://localhost:8080", apiKey)) {
    client.mutate("insurance-quote", Map.of("$.quote.applicant.age", 35, "$.quote.coverage", 100000));
    var sub = client.subscribe("insurance-quote", e -> System.out.println(e.derivedUpdated()));
}
```

See the [module README](https://github.com/vlad-public-code/org.json-kula.valem/blob/main/valem-client/README.md).
