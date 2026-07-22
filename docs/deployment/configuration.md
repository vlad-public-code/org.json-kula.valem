---
title: Configuration
parent: Deployment
nav_order: 3
description: "Single source of truth for every valem.* property."
redirect_from:
  - /reference/configuration.html
---

# Configuration Reference

Single source of truth for every `valem.*` property. Other docs link here rather than
listing partial, divergent subsets.

---

## Core runtime

| Property | Default | Description |
|---|---|---|
| `valem.mutation-queue-size` | `10` | Max concurrent mutations per model (executing + waiting); excess returns HTTP 429. |
| `valem.max-models` | `1000` | Max number of models the registry will hold; excess `POST /models` returns HTTP 429. |
| `valem.history.max-entries` | `50` | Retained per-model temporal-history snapshots — the source for `GET /models/{id}/history` and point-in-time `?at=` reads. `0` disables temporal history. **JVM system property** (see below), not a Spring property. |

### Core safety limits (JVM system properties)

These bound resource use inside `valem-core`, which has no Spring dependency, so they are read as
**JVM `-D` system properties at class-load** (e.g. `-Dvalem.limits.max-array-index=2000000`) — *not*
from `application.yml`. They apply process-wide.

| Property | Default | Description |
|---|---|---|
| `valem.limits.max-array-index` | `1000000` | Hard ceiling on the array index a single write may target, capping the null-padding one mutation can force. A write beyond it is rejected with a typed `StateLimitExceededException` → HTTP 422, before any allocation. Covers live mutate, defaults, and mutation-log replay. |
| `valem.limits.regex-max-input` | `100000` | Max input-string length a schema `pattern` keyword will validate; longer values are rejected up front rather than fed to the regex engine (ReDoS amplification guard). |
| `valem.limits.regex-timeout-ms` | `1000` | Wall-clock budget for a single `pattern` match; a catastrophic-backtracking match is aborted past the deadline instead of pinning a CPU under the model lock. |
| `valem.limits.expression-cache-size` | `10000` | Max compiled-JSONata-expression entries per `ExpressionCache` (bounded LRU). Eviction is safe — an evicted expression recompiles on next use. |
| `valem.history.max-entries` | `50` | (Listed above.) Retained temporal-history snapshots per model. |

## Security / auth

| Property | Default | Description |
|---|---|---|
| `valem.api.key` | *(unset)* | When set, every request must carry `Authorization: Bearer <key>`; blank = **open/dev mode** (all requests permitted, warning logged). Compared constant-time. The same key authenticates the WebSocket handshake via `?token=<key>`. See [security-model.md](security-model.md). |
| `valem.security.csp` | `default-src 'none'; frame-ancestors 'none'` | The `Content-Security-Policy` response header directives. The default is correct for `valem-api` used headless but blocks a bundled UI's own same-origin script/stylesheet/WebSocket loads — a deployable that serves a browser UI (e.g. `valem-web`) overrides it, e.g. `default-src 'self'; connect-src 'self' ws: wss:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; object-src 'none'`. |
| `valem.websocket.allowed-origins` | *(unset = same-origin)* | Comma-separated allowlist of origins permitted to open the `/models/{id}/subscribe` WebSocket handshake. Unset = same-origin only; set `*` only for development. |
| `valem.mcp.allowed-origins` | *(unset = open)* | Comma-separated allowlist of browser `Origin`s permitted to call the Streamable-HTTP MCP endpoint (`/mcp`), for DNS-rebinding protection. Empty = open (requests with no `Origin` — typical non-browser MCP clients — always pass); set it in production to restrict browser origins. See [mcp-server.md](mcp-server.md). |
| `valem.rate-limit.enabled` | `false` | Enable the optional per-IP sliding-window rate-limit filter. Off = no behaviour change. |
| `valem.rate-limit.requests` | `100` | Requests allowed per window per client IP (when enabled). |
| `valem.rate-limit.window-seconds` | `60` | Sliding-window length in seconds (when enabled). Over-limit requests get HTTP 429 + `Retry-After`. |
| `valem.rate-limit.trust-forwarded-for` | `false` | When `true`, key the rate limiter on the first `X-Forwarded-For` hop instead of the socket peer. Enable **only** behind a trusted proxy that sets the header — otherwise a client can spoof it to evade limiting. Default off means a proxied deployment keys on the proxy address until you opt in. |

> There is no per-field authorization. Any caller past the `valem.api.key` gate (or any caller
> in open mode) may read/mutate/evolve every field of every model.

## Effects (egress + pluggable kinds)

| Property | Default | Description |
|---|---|---|
| `valem.effects.allow-private-ips` | `false` | Relax the built-in `server` effect's `EgressGuard` to permit loopback/private/link-local hosts. Local dev / IT stubs only — does **not** apply to plugin `EffectExecutor` kinds, which enforce their own (if any) egress rules. This is an **address-only** relaxation: it never widens the URL scheme, so cleartext `http` still needs `allow-insecure-http`. |
| `valem.effects.allow-insecure-http` | `false` | Permit cleartext `http` egress for the built-in `server` effect. Independent of `allow-private-ips`; enabling private-IP access alone no longer implies http. A dev pointing at a plain-http loopback stub sets both. |
| `valem.effects.allowed-hosts` | *(blank = any public host)* | Comma-separated allowlist of destination hostnames the built-in `server` effect may call. Blank imposes no host restriction beyond the SSRF address checks. |
| `valem.effects.max-response-bytes` | `1048576` (1 MB) | Max response size the built-in `server` effect will fold back. Not enforced for plugin kinds. |
| `valem.effects.kinds.enabled` | *(unset = all)* | Comma-separated allowlist of active effect executor kinds — built-in (`caller`/`server`/`llm`/`timer`) and any `EffectKind`/`EffectExecutor` discovered via `ServiceLoader` (e.g. `valem-effects-noop`). A spec selecting an unlisted/unknown kind is rejected at validation. Unset/empty = every discovered kind enabled. |

See [security-model.md](security-model.md) for the egress-guard scope caveat on plugin kinds.

## Model composition & references

Properties for links between models, branching from templates, and inherited-effect approval.

| Property | Default | Description |
|---|---|---|
| `valem.composition.repositories` | *(empty; `local` is implicit)* | Priority-ordered chain of additional repositories resolved after the implicit in-process `local` repo. Each entry: `id`, `transport` (**how it is reached** — `http` and `mcp` wired; `filesystem` reserved), `repo-class` (**its class** — `local` or `web`; orthogonal to transport, inferred per transport when unset: `http`→web, `filesystem`→local, `mcp`→web), `locator` (base URL for `http`; a launch command such as `java -jar valem-mcp.jar` for `mcp`), optional `credential` (bearer token for a private `http` repo), optional `trusted`. Class (not transport) drives reference-locality and promote targets: only a **web-class** repo is a valid promote target and satisfies the reference-locality closure. An `mcp` repo speaks JSON-RPC 2.0 over the launched process's stdio (the same protocol as `valem-mcp`). |
| `valem.composition.lazy-binding` | `false` | Allow a link `target.ref` whose target model is not yet registered (bind/validate at first fire). Off = a create/evolve with a link to an unknown model is rejected 422 (`UnresolvedLinkTarget`). Enable for out-of-order or peer (A⇄B) creation. |
| `valem.authz.inherited-effects` | `approve` | Policy for an effect inherited by branching a **different owner's** template (an ownership boundary crossed via `lineage`): `approve` (quarantine + require per-effect approval — the default), `allow` (trust inherited effects), `deny` (never run inherited cross-owner effects). Same-owner / branch-authored effects always run; `caller` (pure) effects are never gated. A quarantined effect is inert with `statusPath` phase `blocked` / `effect_approval_required`; approve via `POST /models/{id}/effects/{effectId}/approve`. |

## Persistence (model spec + state)

| Property | Default | Description |
|---|---|---|
| `valem.persistence-dir` | *(unset)* | Shortcut that enables filesystem persistence for spec + state. Specs → `{dir}/{id}/spec.json`; baseline snapshot + incremental mutation log under `{dir}/{id}/`. Models reloaded on startup. Unset (and no `storage.type`) = in-memory only (lost on restart). |
| `valem.storage.type` | *(unset)* | Single backend for **both** spec and state: `memory`, `filesystem`, `postgres` (alias `postgresql`), `mongodb` (alias `mongo`), or `redis`. |
| `valem.storage.spec-type` | *(falls back to `storage.type`)* | Backend for the model **spec** alone (per-concern override). |
| `valem.storage.state-type` | *(falls back to `storage.type`)* | Backend for runtime **state** alone (per-concern override). |
| `valem.storage.compaction-threshold` | `100` | Mutation-log length that triggers compaction into a new baseline snapshot. |
| `valem.storage.jdbc.pool-size` | `8` | Maximum size of the pooled `HikariDataSource` synthesized for a DB backend when no `DataSource` bean is supplied. The pool is lazy (`initializationFailTimeout=-1`, `minimumIdle=0`) and closed on shutdown. |
| `valem.storage.audit-type` | *(resolved, see below)* | Backend for the durable, append-only audit trail: `none`, `memory` (retained, non-durable), `filesystem` (`{id}/audit.jsonl`), `postgres` (`ss_audit` table), or `mongodb` (`ss_audit` collection). Unset → follows the state backend when it is `filesystem`/`postgres`/`mongodb`, else `none`. |

Each storage concern — spec, state, blob — selects its backend independently. When spec and
state resolve to the **same** backend a single store instance backs both; when they **differ** a
`CompositeModelStore` wires the two halves (e.g. spec in Postgres, state in Redis). Resolution order
for spec/state: per-concern `*-type` → `storage.type` → `filesystem` if `persistence-dir` is set →
`memory`. Backend connection settings reuse the standard Spring keys (`spring.datasource.*`,
`spring.data.mongodb.*`, `spring.data.redis.url`); if no `DataSource` bean is present, a pooled
`HikariDataSource` is synthesized from `spring.datasource.url`/`username`/`password` (sized by
`valem.storage.jdbc.pool-size`). Spring Boot's `DataSourceAutoConfiguration` /
`JdbcTemplateAutoConfiguration` are excluded so putting HikariCP on the classpath does not force a
`DataSource` bean in memory-only deployments.

**À-la-carte adapters (the jar *is* the enablement).** Each backend beyond `memory`/`filesystem` is a
separate adapter jar — `valem-persistence-postgres`, `-mongo`, `-redis`, `-s3` — discovered at
runtime through the `PersistenceProvider` ServiceLoader SPI (each provider owns and shares one client
across its spec/state/blob/audit concerns). The
default `valem-web` deployable ships **only** memory + filesystem, so enabling another backend is
*add its adapter jar to the classpath + set the `valem.storage.*` property* — no recompile.
Selecting a backend whose adapter jar is **absent aborts startup** with a message naming the concern,
the type, and the jar to add (e.g. `spec backend 'postgres' … Add the valem-persistence-postgres
adapter jar`) — there is **no silent fallback** to another backend.

> A failure to reconstruct a model's **state** on startup (corrupt snapshot or mutation log) loads
> the model **spec-only** (state reset; derived fields recompute on first access) rather than
> dropping the whole model.

**Durable audit trail.** Independently of spec/state, an `AuditStore` retains one append-only
`AuditRecord` per committed mutation cycle — the *what/when/why/what-it-triggered* (mutations,
re-evaluated derivations, derivation/constraint traces, flagged constraints, dispatched effects,
and the `source`: `client` | `patch` | `foldback`). This is the durable, queryable superset of the
bounded in-memory `explain` ring buffer, queried via `GET /models/{id}/audit`. It is **never
compacted** (unlike the state mutation log). `none` disables retention (the default when state is
ephemeral); `filesystem` writes `{persistence-dir}/{id}/audit.jsonl` and survives restarts;
`postgres`/`mongodb` store one hash-chained row/document per cycle in `ss_audit` (apply the Postgres
DDL in `db/migration/V1__init.sql`). The trail is **tamper-evident** — every record is SHA-256
hash-chained to its predecessor, and `GET /models/{id}/audit/verify` reports the first
altered/reordered/deleted record.

## Blob storage

| Property | Default | Description |
|---|---|---|
| `valem.storage.blob-type` | *(resolved, see below)* | Explicit blob backend: `memory`, `filesystem`, `postgres`, `mongodb`, or `s3`. |
| `valem.blob-store` | `memory` | Legacy blob selector: `memory` (in-process `ConcurrentHashMap`, SHA-256 keyed, lost on restart) or `filesystem`. |
| `valem.blob-store-path` | `~/.valem/blobs` | Directory for `filesystem` blob store. |
| `valem.storage.blob` | *(unset)* | Set to `s3` to use the S3 blob backend (with `valem.storage.s3.bucket`/`region`/`endpoint`/`access-key`/`secret-key`). |
| `valem.blob.max-bytes` | `52428800` (50 MB) | Per-blob upload cap enforced by `BlobController`; oversized upload → HTTP 413. |
| `valem.blob.max-total-bytes` | `536870912` (512 MB) | Total-bytes ceiling for the in-memory blob store; over-budget upload is rejected with HTTP 413 (no eviction). A non-positive value opts into unbounded (a startup warning is logged). |
| `spring.servlet.multipart.max-file-size` | `50MB` | Servlet multipart per-part cap (set explicitly, not left to defaults). |
| `spring.servlet.multipart.max-request-size` | `55MB` | Servlet multipart whole-request cap. |

Blob backend resolution order: `storage.blob-type` → `s3` if `storage.blob=s3` → legacy
`blob-store` (`memory`/`filesystem`) → the DB backend if `storage.type` is `postgres`/`mongodb`
→ `memory`. (`redis` has no blob backend; a redis `storage.type` leaves blobs on `memory`.)

## Observability (Actuator + Micrometer)

Spring Boot Actuator is enabled with a Micrometer meter registry and a Prometheus scrape endpoint.

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET /actuator/health` | **open** (bypasses the API key) | Liveness/readiness for orchestrators |
| `GET /actuator/info` | **open** | Build/app info |
| `GET /actuator/metrics[/{name}]` | API key when configured | Micrometer meters |
| `GET /actuator/prometheus` | API key when configured | Prometheus scrape (OpenMetrics) |

Valem-specific meters:

| Meter | Type | Tags | Meaning |
|---|---|---|---|
| `valem.mutation.duration` | timer | `outcome` = success/flagged/rollback/schema_violation/error | Per-mutation latency + count by outcome |
| `valem.effects.dispatched` | counter | — | Effect requests emitted by committed mutations |
| `valem.audit.records` | counter | `source` = client/patch/foldback | Durable audit records appended |
| `valem.effect.duration` | timer | `kind` = server/llm/timer, `outcome` = success/failure | Effect-executor latency + success/failure breakdown |
| `valem.models.created` | counter | — | Models created via `POST /models` |
| `valem.models.registered` | gauge | — | Models currently registered |

Exposure is controlled by the standard `management.endpoints.web.exposure.include` (default
`health,info,metrics,prometheus`). Only `health`/`info` are reachable without the API key; the rest
sit behind the same gate as the model API.

## LLM integration

| Property | Default | Description |
|---|---|---|
| `valem.llm.provider` | `anthropic` | One of `anthropic`, `openai`, `ollama`, `openrouter`, `groq`, `mistral`, `gemini`, `cerebras`. |
| `valem.llm.api-key` | *(unset)* | API key for the provider; not required for `ollama`. |
| `valem.llm.model` | *(provider-appropriate default)* | Model name sent to the provider. When unset, a sensible default is chosen for the provider: `anthropic`→`claude-sonnet-4-6`, `openai`→`gpt-4o`, `mistral`→`mistral-large-latest`, `groq`→`llama-3.3-70b-versatile`, `gemini`→`gemini-2.0-flash`, `cerebras`→`llama-3.3-70b`, `ollama`→`llama3.1`, `openrouter`→`anthropic/claude-3.7-sonnet`. Override per deployment. |
| `valem.llm.max-tokens` | `8192` | Max tokens for LLM responses. On a truncated response the first retry transiently raises this to `min(2 × max-tokens, max-tokens-hard)` on the *same* prompt (keeping the work) before falling back to the "smaller spec" prompt on a second truncation. |
| `valem.llm.max-tokens-hard` | `16384` | Ceiling for the adaptive truncation retry above. |
| `valem.llm.base-url` | *(provider default)* | Override the endpoint (Ollama, proxies, OpenAI-compatible servers). |
| `valem.llm.max-retries` | `3` | Base validation-retry attempts in `SpecGenerator` (always attempted). |
| `valem.llm.max-retries-hard` | `6` | Ceiling for a *converging* hard spec — extra attempts past the base budget are granted only while the validation-error count keeps dropping. |
| `valem.llm.repair-temperature` | `0.2` | Sampling temperature on the **first repair** attempt. Set slightly *above* `generation-temperature` on purpose: after a deterministic first attempt failed, a little randomness helps the model escape the rut and try a different fix rather than re-emitting the same output. |
| `valem.llm.repair-temperature-step` | `0.15` | Amount the repair temperature rises on **each subsequent** repair attempt (`repair-temperature + (n−1)·step`), so a model that keeps re-emitting the same failing output diverges instead of repeating it. `0` = flat repair temperature. |
| `valem.llm.repair-temperature-max` | `0.8` | Ceiling the escalating repair temperature is clamped to (kept below 1.0 so retries stay structured, not incoherent). |
| `valem.llm.generation-temperature` | `0.0` | Sampling temperature on the **initial** attempt. Low (~0) makes the first attempt deterministic and structured rather than creative. |
| `valem.llm.structured-output.enabled` | `true` | Send the `ModelSpec`/`SpecEvolution` JSON Schema so the output shape is provider-enforced. OpenAI-compatible providers use it as `response_format` (`json_schema` mode); Anthropic uses a forced `submit_spec` tool whose `input_schema` is the schema (forced when no grounding tools are configured; offered but not forced alongside `web_search`/`eval_jsonata`). Non-strict (a spec embeds an arbitrary JSON Schema, which strict mode cannot represent). |
| `valem.llm.prompt-cache.enabled` | `true` | Anthropic only: send the (stable) system context as a block array carrying an `ephemeral` `cache_control` breakpoint, so retries and tool-loop turns re-read the `tools`+`system` prefix at ~10% of input price. Set `false` if a proxy chokes on a block-array `system` (it is then sent as a plain string). |
| `valem.llm.tool-loop.max-iterations` | `40` | Hard ceiling on tool-use round-trips per generation before one final tools-withheld request forces the answer (prevents an unbounded loop when a model keeps invoking exhausted tools). Keep it above the combined tool budget (`web-fetch.max-calls` + `web-search.max-calls` + `eval-tool.max-calls`). |
| `valem.llm.mock` | `false` | Use `MockLlmClient` (no real call; canned response). |
| `valem.llm.web-fetch.enabled` | `true` | Enable web tools (`web_fetch` + `web_search`) during generation. See SSRF notes in [security-model.md](security-model.md). |
| `valem.llm.web-fetch.max-calls` | `5` | Max fetches per `generate()` session (shared across retries). |
| `valem.llm.web-fetch.max-chars` | `8000` | Max plain-text characters per fetched page. |
| `valem.llm.web-search.enabled` | `true` | Offer the `web_search` tool alongside `web_fetch` (so the model finds authoritative URLs instead of guessing). Set `false` for fetch-only. Gated by `web-fetch.enabled`. |
| `valem.llm.web-search.provider` | `duckduckgo` | Which `SearchBackend` answers `web_search`: `duckduckgo` (keyless HTML scraping — free, but datacenter/hosting-provider IPs can get silently challenge-blocked, which parses as zero results), `brave` (the [Brave Search API](https://brave.com/search/api/), needs `web-search.api-key`; recommended for a public-facing deployment), or `tavily` (the [Tavily Search API](https://docs.tavily.com/documentation/api-reference/introduction), also needs `web-search.api-key`). |
| `valem.llm.web-search.api-key` | *(unset)* | API key for the selected `web-search.provider`, when it needs one (e.g. `brave`, `tavily`). Ignored by `duckduckgo`. |
| `valem.llm.web-search.max-calls` | `3` | Max searches per `generate()` session (independent of the fetch budget). |
| `valem.llm.web-search.max-results` | `5` | Max results returned per search. |
| `valem.llm.eval-tool.enabled` | `true` | Offer the `eval_jsonata` tool so the model can test a candidate expression against a sample input (local; no network) and fix it in place before committing. Set `false` to drop it. |
| `valem.llm.eval-tool.max-calls` | `25` | Max `eval_jsonata` evaluations per `generate()`/`generateEvolution()` session. |
| `valem.llm.max-concurrent-requests` | `1` *(shipped in `application.yml`; unset = `0`)* | Cap on simultaneous LLM calls. `0` = unlimited; `1` = fully serialised app-wide. Use a low value to avoid HTTP 429s on throttled keys when multiple generations overlap (limits concurrency, not raw request rate). |
| `valem.llm.log.capture-content` | `true` | When `true`, `LlmInteractionLog` records the full prompt/response text of each LLM call (visible via `GET /llm/interactions`). Set `false` to keep only metadata (provider, model, timing, token counts) and redact the content. |

**Provider base-URL defaults:** Anthropic `https://api.anthropic.com/v1/messages`; OpenAI
`https://api.openai.com/v1`; Ollama `http://localhost:11434/v1`; OpenRouter
`https://openrouter.ai/api/v1`; Groq `https://api.groq.com/openai/v1`; Mistral
`https://api.mistral.ai/v1`; Gemini `https://generativelanguage.googleapis.com/v1beta/openai/`;
Cerebras `https://api.cerebras.ai/v1`.

LLM beans are created only when `valem.llm.mock=true`, `valem.llm.api-key` is non-blank,
or the provider is `ollama`. Otherwise `/models/generate*` returns 503.

---

## Environment-variable equivalents

Spring relaxed binding maps `valem.llm.provider` → `VALEM_LLM_PROVIDER`, etc. There is no
provider-specific environment-variable fallback (e.g. no implicit `ANTHROPIC_API_KEY` read) —
`valem.llm.api-key` is the only key source, itself settable via `VALEM_LLM_API_KEY`. See
[../getting-started/quickstart.md](../getting-started/quickstart.md) for examples.
