---
title: Third-party libraries
nav_order: 11
description: "The third-party libraries Valem stands on, and what each is used for."
---

# Third-party libraries
{: .no_toc }

Valem is Apache-2.0 licensed and stands on a small, carefully chosen set of libraries. The pure core
(`valem-core` / `valem-service`) depends only on Jackson plus the two JSON libraries below — no
framework. Everything else is à-la-carte and pulled in only by the module that needs it.
{: .fs-5 .fw-300 }

1. TOC
{:toc}

---

## Core & JSON

| Library | Purpose | License |
|---|---|---|
| [Jackson](https://github.com/FasterXML/jackson) | JSON tree (`JsonNode`) and (de)serialization — the substrate for all model state. | Apache-2.0 |
| [tracked-json](https://github.com/vlad-public-code/org.json-kula.tracked-json) | `JsonPointer`-tracking `JsonNode` wrapper + RFC 6902 JSON Patch + RFC 9535 JSONPath. | Apache-2.0 |
| [jsonata-jvm-compiler](https://github.com/vlad-public-code/org.json-kula.jsonata-jvm-compiler) | Parses, compiles, and evaluates the JSONata expressions in derivations, constraints, and effects. | Apache-2.0 |
| [json-schema-validator](https://github.com/networknt/json-schema-validator) | Validates base documents and mutations against a model's JSON Schema. | Apache-2.0 |
| [Caffeine](https://github.com/ben-manes/caffeine) | In-memory caching on hot paths. | Apache-2.0 |

## Web & API (`valem-api` / `valem-web`)

| Library | Purpose | License |
|---|---|---|
| [Spring Boot](https://spring.io/projects/spring-boot) | REST, WebSocket, actuator, and security layers (virtual threads enabled). | Apache-2.0 |
| [springdoc-openapi](https://springdoc.org/) | OpenAPI 3 spec + Swagger UI for the REST surface. | Apache-2.0 |
| [Micrometer](https://micrometer.io/) (Prometheus) | Metrics and observability. | Apache-2.0 |
| [HikariCP](https://github.com/brettwooldridge/HikariCP) | JDBC connection pooling for DB backends. | Apache-2.0 |
| [jsoup](https://jsoup.org/) | Extracts readable text for the LLM `web_fetch` tool. | MIT |

## Persistence adapters

Each is an optional adapter jar, loaded only when you select its backend.

| Library | Backend | License |
|---|---|---|
| [PostgreSQL JDBC](https://jdbc.postgresql.org/) | Postgres spec / state / blob / audit | BSD-2-Clause |
| [MongoDB Java Driver](https://www.mongodb.com/docs/drivers/java/sync/) | Mongo spec / state + GridFS blobs | Apache-2.0 |
| [Lettuce](https://lettuce.io/) | Redis spec / state | Apache-2.0 |
| [AWS SDK for Java v2](https://github.com/aws/aws-sdk-java-v2) (S3) | S3 / MinIO blob store | Apache-2.0 |

## Frontend (`valem-ui` / `valem-view-react`)

| Library | Purpose | License |
|---|---|---|
| [React](https://react.dev/) | The management SPA and the `EvaluatedView` renderer. | MIT |
| [Vite](https://vitejs.dev/) | Dev server and build tooling. | MIT |
| [TypeScript](https://www.typescriptlang.org/) | Types across the UI and the TS SDK. | Apache-2.0 |

## Testing & benchmarks

| Library | Purpose | License |
|---|---|---|
| [JUnit 5](https://junit.org/junit5/) | Test framework. | EPL-2.0 |
| [AssertJ](https://assertj.github.io/doc/) | Fluent assertions. | Apache-2.0 |
| [Playwright](https://playwright.dev/) | End-to-end browser tests (`valem-e2e`). | Apache-2.0 |
| [JMH](https://github.com/openjdk/jmh) | Micro-benchmarks (`valem-benchmarks`). | GPL-2.0 (Classpath) |
| Embedded [Postgres](https://github.com/zonky2/embedded-postgres) · [Mongo](https://github.com/bwaldvogel/mongo-java-server) · [Redis mock](https://github.com/fppt/jedis-mock) · [S3Mock](https://github.com/adobe/S3Mock) | In-process backends for adapter tests. | Apache-2.0 / BSD / MIT |

---

{: .note }
License tags are for orientation. Consult each project for its authoritative license terms.
