# Valem

Deterministic reactive computation runtime for AI-generated structured data models.
A spreadsheet-like computation model for JSON-based agent systems.

## Project layout

```
valem/                   ← parent POM (multi-module Maven)
├── valem-core/          ← pure-Java library, no Spring
│   src/main/java/org/json_kula/valem/core/
│   ├── model/                ← ModelSpec and sub-records (deserialized from JSON)
│   ├── graph/                ← DependencyGraph, ModelSpecCompiler, ModelSpecValidator,
│   │                            SpecEvolution, ExpressionPathExtractor, CompiledModel
│   ├── state/                ← ModelState, Snapshot, DirtyPropagator, PathConverter
│   ├── engine/               ← ModelRuntime, evaluators, EffectDispatcher,
│   │                            EffectiveSchemaBuilder, ExpressionCache,
│   │                            SchemaValidator, TestCaseRunner, ModelHistory
│   ├── blob/                 ← BlobStore interface + InMemoryBlobStore + FilesystemBlobStore
│   └── llm/                  ← LlmClient interface, SpecGenerator (feedback loop),
│                                SpecGenerationPrompt (prompt templates)
│
├── valem-service/       ← pure-Java business logic, no Spring
│   src/main/java/org/json_kula/valem/service/
│   ├── ModelService          ← orchestrates all use cases (mutate, snapshot, evolve, …)
│   ├── ModelRegistry         ← ConcurrentHashMap<id, ModelRuntime>
│   ├── ModelInfo             ← DTO: id, version, counts
│   ├── JsonPatchTranslator   ← RFC 6902 patch → mutations
│   └── *Exception            ← domain exceptions (Not Found, Already Exists, Access Denied, …)
│
├── valem-view/          ← pure-Java view evaluation, no Spring
│   src/main/java/org/json_kula/valem/view/
│   ├── model/                ← ViewDefinition, ViewSpec, ComponentSpec and sub-specs
│   └── engine/               ← ViewEvaluator, EvaluatedView, EvaluatedComponent subtypes
│
├── valem-api/           ← Spring Boot 3 REST + WebSocket layer — HEADLESS library (no main,
│   │                            no executable repackaging); embeddable in a host Boot app
│   src/main/java/org/json_kula/valem/api/
│   ├── controller/           ← ModelController, BlobController, ViewController,
│   │                            GenerateController, LlmLogController
│   ├── registry/             ← ModelRegistry (Spring wrapper), ModelLoader (ApplicationRunner)
│   ├── persistence/          ← legacy in-api ModelStore (Filesystem/InMemory); the supported
│   │                            multi-backend stores live in valem-persistence/* (below)
│   ├── config/               ← StorageConfig (per-concern backend wiring), SecurityConfig,
│   │                            ServiceConfig, WebSocketConfig, LlmConfig
│   ├── filter/               ← RateLimitFilter (optional per-IP, off by default)
│   ├── dto/                  ← CreateModelResponse, MutationResponse, ValidationErrorResponse
│   ├── llm/                  ← AnthropicLlmClient, OpenAiLlmClient, MockLlmClient,
│   │                            RecordingLlmClient, LlmInteractionLog, LlmInteractionRecord
│   ├── websocket/            ← ValemWebSocketHandler, ChangeEvent, TokenHandshakeInterceptor
│   └── error/                ← GlobalExceptionHandler (RFC 7807 Problem Detail)
│
├── valem-web/           ← the runnable open web deployable (executable Spring Boot jar): owns
│                                ValemWebApplication (main) + the repackaging + the deployer's
│                                chosen persistence adapter(s). = valem-api (headless) + memory
│                                + filesystem by default; add an adapter jar (or -Pweb-postgres|web-
│                                mongo|web-redis|web-s3) for other backends. Builds valem-ui (the
│                                management SPA) via frontend-maven-plugin and serves it at / (skip
│                                with -Dskip.frontend=true). Also hosts McpHttpController — the MCP
│                                surface over Streamable HTTP at /mcp (reuses valem-mcp's McpHttpSession;
│                                shared models, valem.api.key gate, valem.mcp.allowed-origins).
│
├── valem-effects-noop/  ← reference effect-kind plugin: ServiceLoader EffectKind + auto-configured
│                                EffectExecutor bean; proves the pluggable-effects SPI (drop-in jar, no
│                                core/api edits). Template for real custom effect kinds.
│
├── valem-persistence/   ← pluggable storage SPI + adapters (no Spring in the modules)
│   ├── valem-persistence-api/         ← SpecStore, StateStore, ModelStore, CompositeModelStore,
│   │                                          MutationLogReplay (create-as-you-go), BlobSpooler,
│   │                                          audit/ (AuditStore, AuditRecord, AuditQuery, DisabledAuditStore)
│   ├── valem-persistence-memory/      ← InMemoryModelStore + InMemoryBlobStore
│   ├── valem-persistence-filesystem/  ← FilesystemModelStore + FilesystemBlobStore
│   ├── valem-persistence-postgres/    ← Postgres Spec/State/Blob stores (spring-jdbc)
│   ├── valem-persistence-mongo/       ← Mongo Spec/State stores + GridFS blob store
│   ├── valem-persistence-redis/       ← Redis Spec/State stores (Lettuce)
│   └── valem-persistence-s3/          ← S3/MinIO blob store (AWS SDK v2)
│
├── valem-console/       ← standalone JSON REPL (no Spring)
│   src/main/java/org/json_kula/valem/console/
│   ├── ConsoleApp
│   └── CommandDispatcher
│
├── valem-mcp/           ← MCP server (Model Context Protocol over stdio, no Spring; same
│   src/main/java/org/json_kula/valem/mcp/   deps as console: service + jackson only)
│   ├── McpServer             ← JSON-RPC 2.0 stdio transport + protocol core (initialize/tools/resources/
│   │                            logging handshake; negotiates up to 2025-11-25; resources subscribe +
│   │                            templates/list; async pair_browser worker w/ progress+cancellation +
│   │                            URL-mode elicitation; swappable notification sink for reuse over HTTP)
│   ├── McpHttpSession        ← reuses McpServer's core for the Streamable-HTTP endpoint (see valem-web)
│   ├── ToolRegistry          ← 24 tools → ModelService: model CRUD (create/mutate/patch_model/
│   │                            get_state[paths,depth]/explain/snapshot/restore/get_effective_schema/
│   │                            get_audit/verify_audit/upload_blob/download_blob/evolve_spec/…) +
│   │                            authoring/verify (validate_spec/eval_expression/test_spec/dry_run) —
│   │                            the agent generates, Valem verifies; opt-in mutate traces, outputSchema
│   │                            decls, result-size guard, structured errors; + pair_browser in
│   │                            remote_with_browser mode (--url --browser)
│   └── ResourceRegistry      ← MCP resources: spec-format/jsonata-gotchas/spec-evolution/view-system
│                                guides + JSON schemas + bundled example specs + valem://state/{id}
│                                subscribable resource (+ examples/state uriTemplates)
│
├── valem-client/        ← thin Java SDK over REST/WS (jackson + JDK java.net.http only;
│                                ValemClient, reconnecting subscribe, audit; no engine dep)
│
├── valem-ui/            ← open management/DevTools SPA (Vite+React); built & served at / by
│                                valem-web. npm-workspace member alongside valem-view-react.
├── valem-view-react/    ← open EvaluatedView → React renderer library (workspace member)
│
└── clients/valem-sdk-ts/ ← isomorphic TypeScript/JS SDK (fetch + reconnecting WebSocket)
```

> **Closed-source overlay.** A separate private repo (`../Valem-internal`, sibling checkout) layers
> closed modules over these OPEN modules through their seams — it consumes `valem-api` and the
> parent pom from `~/.m2` (run `mvn install` here first). Never fork open modules into it.

## Package

`org.json_kula.valem` — not `org.valem`.

## Build and test

```powershell
# Build + test everything (run from repo root)
mvn test

# Core only (fastest, no Spring boot-up)
mvn test -pl valem-core

# Service only (requires core installed)
mvn install -pl valem-core -q; mvn test -pl valem-service

# API only (requires core + service + view installed)
mvn install -pl valem-core,valem-service,valem-view -q; mvn test -pl valem-api

# MCP server only (requires core + service + view installed)
mvn install -pl valem-core,valem-service,valem-view -q; mvn test -pl valem-mcp

# Run the server (valem-web is the runnable deployable; valem-api is a headless library)
mvn install -pl valem-core,valem-service -q; mvn spring-boot:run -pl valem-web
# Bundle a backend adapter into the web jar (a-la-carte): -Pweb-postgres | web-mongo | web-redis | web-s3
mvn -Pweb-postgres -pl valem-web package

# Install all modules to local repo
mvn install
```

All commands must be run from `...\Valem` (the repo root).
Use PowerShell — `&&` is not available in PowerShell 5.1, chain with `;` or separate commands.

## Key dependencies (local snapshots — must be installed first)

- `io.github.vlad-public-code:tracked-json:1.0.0`
- `io.github.vlad-public-code:jsonata-jvm-compiler:1.0.3`

Both must be in the local Maven repo (`~/.m2`) before building.

## Tech stack

| Concern | Library |
|---|---|
| JSON tree | Jackson 2.21.2 (BOM-pinned) |
| JSONata eval | `jsonata-jvm-compiler` (parse + compile + evaluate) |
| JSON Patch | `tracked-json` — `org.json_kula.tracked_json.json_patch.JsonPatch` (`compile(doc).apply(target)`) |
| REST + WS | Spring Boot 3.3.5, virtual threads enabled |
| Tests | JUnit Jupiter 5.11.4 + AssertJ 3.26.3 |
| Java | 21 (records, sealed interfaces, pattern-matching switch) |

## Core concepts

**ModelSpec** — declarative JSON document produced by an LLM. Fields:
- `id`, `version`, `schema` — identity and JSON Schema for the base document
- `constants` — named immutable values (any JSON type: primitive/array/object), materialized once in `CompiledModel.constantsNode()` and bound as **`$const`** in every expression evaluation (via `EvalBindings.forModel`). Reference as `$const.<name>`. No dependency edge — a derivation reading only `$const` never recomputes (reference an input alongside it).
- `defaultValues` — `(path, expr)` rules that deep-merge a JSONata object into a **newly-created container** (array element / object / root `$`), filling only caller-absent fields. `$parent` = the container's JSON parent, `$self` = its caller-provided fields. A `path: "$"` rule fires once at creation and **replaces the removed `initialState` seed map** (a spec still carrying `initialState` is rejected). Applied before the reactive pipeline (step 1.5), so derivations see the defaults. See [model-spec-format.md](docs/reference/model-spec-format.md).
- `derivations` — computed read-only fields (JSONata expressions). Evaluated in topological level order; each level evaluates against `mergedDocument()` so a derivation at level k+1 can reference results from level k. For wildcard paths (`$.items[*].lineTotal`), evaluated once per array element with `$parent` bound to the element node. See [model-spec-format.md](docs/reference/model-spec-format.md).
- `metaDerivations` — live per-field metadata (min/max/required/…)
- `constraints` — boolean invariants with `rollback | flag` policy
- `effects` — effect requests the pure core emits as data (`EffectDispatcher` → `EffectRequest`); **replaces the removed `actions` section** (a legacy `actions` array in a spec is rejected with a migration pointer). Executed **async post-commit** by a shell selected by `executor`, routed through `CompositeEffectExecutor`: `caller` (surfaced in the response, pure, in-core), `server` (`HttpEffectExecutor`: spec-provided-URL HTTP + generic `EgressGuard` + retries; folds the response back), `llm` (`LlmEffectExecutor`: calls the configured `LlmClient` with a state-derived prompt, folds the JSON completion back), and `timer` (`TimerEffectExecutor`: schedules a fold-back at `at`/`afterMs` — the clock lives in the shell, not the pure core). `executor` is an **open string, not a closed enum**: kinds beyond the four built-ins are **pluggable** via two SPIs — a pure `EffectKind` (`core/engine/spi`, discovered by `ServiceLoader`, resolves to the generic `EffectRequest.Plugin` carrier) and a shell `EffectExecutor` (`api/effects`, a Spring bean routed by `CompositeEffectExecutor`); `EffectKindRegistry` discovers them and applies the `valem.effects.kinds.enabled` enable-list. Adding a kind is a new jar + config, no core/api edits (reference: `valem-effects-noop`). All fold back via `ModelService.mutate`, driving a `statusPath` `pending→in_flight→applied|failed` machine; URL/prompt/params live in the spec; edge-triggered via `dedupeKey` + `statusPath` guard; replay never re-executes I/O. Fold-backs use a **keyed compare-and-swap** (`resolveFoldback`/`completeFoldback`, atomic under the model lock): an in-flight effect whose input changed is `SUPERSEDED` (discard + re-fire for the latest value) or `CANCELLED` (trigger no longer holds — also how a timer cancels), so a stale result never overwrites a newer input.
- `tests` — embedded spec-level test cases
- `viewDefinition` — optional UI component tree (evaluated by `valem-view`); see [view-system.md](docs/reference/view-system.md) and [model-spec/views.md](docs/reference/model-spec/views.md)

There is no per-field access control: any caller with model access can read/mutate/evolve every field. Access is a single coarse gate (`valem.api.key`).

**Reactive pipeline** (triggered by `ModelRuntime.mutate()`):

| Step | Component | What it does |
|---|---|---|
| 0 | `SchemaValidator` | Pre-validates each mutation against the effective schema; throws `SchemaViolationException` (HTTP 422) **before** opening a transaction |
| 1 | `beginTransaction` | Saves a rollback snapshot |
| 2 | `setValue()` | Writes mutations into the base document |
| 2.5 | `DefaultValueApplier` | Fills `defaultValues` rules into newly-created containers (absent fields only) before propagation; `ModelRuntime.initialize()` runs this alone at creation for the `$` seed rule |
| 3 | `DirtyPropagator` | BFS through the dependency DAG; wildcard pattern matching |
| 4 | `DerivationEvaluator` | Topological level order, EAGER fields only; LAZY fields marked stale |
| 5 | `MetaDerivationEvaluator` | Per-element for `[*]` paths |
| 6 | `ConstraintEvaluator` | Evaluated against `mergedDocument()`; ROLLBACK throws, FLAG records |
| 7 | `EffectDispatcher` | Effect triggers evaluated against `mergedDocument()`; emits `EffectRequest`s (executed post-commit by the shell) |
| 8 | commit / rollback | Broadcasts `ChangeEvent` over WebSocket |

**Path notation** — **addresses** (paths used as data: spec `path` fields, `defaultValues` paths, mutation/patch keys, view `bind`) are canonical JSON Path: `$.`-rooted with bracket array indices (`$.order.items[0].qty`, wildcard `$.order.items[*].qty`). `ModelSpecValidator` **rejects** non-canonical addresses (legacy dot-index `$.items.0.x`, unrooted forms). **Expression** bodies (`expr`/`trigger`/…) use JSONata navigation (`order.total`) and are never rewritten. Internally `PathConverter` converts addresses to dot-segments / `JsonPointer`; `toCanonicalAddress` produces the canonical form.

**Dependency graph** — `DependencyGraph` (adjacency list, Kahn's topological sort).
Node kinds: `BASE` (writable), `DERIVED` (computed), `META` (meta-derivations + synthetic `$constraint:<id>` / `$effect:<id>` nodes).

**Merged document** — `ModelState.mergedDocument()` deep-copies the base document and splices in all derived-cache values (length-aware via `setDerivedInDoc`). Used by constraint and action evaluators (do not use `baseDoc()` directly for these). A mutation cycle materializes it **once**: `DerivationEvaluator.evaluateAndMerge` builds it on the first level with work, carries it forward across topological levels (splicing each level's results via `spliceDerived`), and hands it to the meta/constraint/action phases — so the per-cycle deep-copy count is O(1), not O(levels × constraints).

**DerivationTrace** — ring buffer of 500 records in `ModelRuntime.traceLog`. Records both derivation evaluations and constraint evaluations. Exposed via `GET /models/{id}/explain/{path}`. Constraint traces use synthetic keys `$constraint:<id>` (URL-encode as `%24constraint%3Aid`). This is **live and bounded** — for the durable equivalent see AuditStore.

**AuditStore** — durable, **append-only** audit trail (`valem-persistence-api` SPI: `AuditStore`/`AuditRecord`/`AuditQuery`; `DisabledAuditStore` no-op + `InMemoryAuditStore` retained + `FilesystemAuditStore`/`PostgresAuditStore`/`MongoAuditStore` durable). One `AuditRecord` per committed cycle — mutations, `derivedUpdated`, derivation/constraint `traces`, flagged constraints, dispatched effect ids, `source` (`client`/`patch`/`foldback`), and a per-model monotonic `sequence`. Written under the model lock via `ModelService.AuditSink` (alongside the incremental-log `MutationPersister`), so audit order matches commit order; the fold-back path audits effect results too. Queried via `GET /models/{id}/audit` (path prefix + ISO-8601 window + limit, newest-first). Backend selected by `valem.storage.audit-type` (`none`|`memory`|`filesystem`); **never compacted**, unlike the state mutation log. This is the queryable superset of the bounded in-memory `explain` ring buffer. **Tamper-evident**: each record carries `prevHash` + SHA-256 `hash` over a canonical projection (`AuditHashing`, genesis for seq 0); `AuditStore.verify` / `GET /models/{id}/audit/verify` walks the chain and reports the first altered/reordered/deleted record (`AuditVerification`).

**BlobStore** — content-addressed (SHA-256) binary storage. Backends: in-memory, filesystem, Postgres (`BYTEA`), Mongo (GridFS), S3/MinIO. DB/GridFS backends spool uploads to a temp file while hashing (`BlobSpooler`) so large blobs don't buffer wholly in heap. Per-blob cap `valem.blob.max-bytes`.

**Persistence** — pluggable, with **per-concern backend selection** (`StorageConfig`): spec, state, and blob each pick a backend independently (`valem.storage.spec-type`/`state-type`/`blob-type`, falling back to `storage.type` then the legacy `persistence-dir`/`blob-store`). When spec and state differ, a `CompositeModelStore` wires them. State persists as a baseline snapshot + an incremental RFC 6902 mutation log; on load the log is replayed **create-as-you-go** (`MutationLogReplay`) so nested/array-creating mutations reconstruct rather than being dropped, and compaction is **offset-pinned**. `ModelLoader` degrades a corrupt-state model to spec-only instead of dropping it. See [configuration.md](docs/deployment/configuration.md).

**SpecEvolution** — incremental spec diff with `newVersion`, plus upsert/remove lists for each spec section. Applied via `POST /models/{id}/spec/evolve`. Uses `ModelState.withModel()` to carry forward existing state, seeding the new runtime's `ExpressionCache` so unchanged expressions aren't recompiled. The three formerly wholesale-only sections also take **targeted diffs** (mutually exclusive with their wholesale field per evolution): **schema** — `upsertSchemaDefs`/`removeSchemaDefs` (by `$defs` name), `upsertSchemaNodes`/`removeSchemaNodes` (by canonical data path, `required` tri-state; may not traverse a `$ref`), or `newSchema`; **view** — `upsertViews`/`removeViews`/`newDefaultView`, `upsertComponents`/`removeComponents` (by id, with `parentId`/`beforeId` placement; spliced structurally on raw JSON by `ViewDefinitionSplice`, no `valem-view` dep in core), or `newViewDefinition`; **constants** — `upsertConstants`/`removeConstants` (by name, removal blocked if `$const.<name>` is referenced), or `newConstants`. Guards: optional `expectedVersion` (409 on mismatch); a schema change validates carried-forward state against the new schema via `SchemaStateChecker` (422 if it would strand existing values). Local JSON Schema `$ref`/`$defs` (`#/$defs/<Name>`) are resolved by `SchemaPaths` (lazy, `$ref`-aware); non-local/dangling refs are rejected at validation. `ModelSpecValidator` also enforces view id-uniqueness + non-dangling `defaultView`/`itemView`, and the service parse-validates the view (invalid view = 422 at write, not 500 at render). See [tests-and-evolution.md](docs/reference/model-spec/tests-and-evolution.md).

**LLM integration** — `SpecGenerator` drives a validate-and-retry feedback loop (configurable max retries). `SpecGenerationPrompt` builds initial / repair / evolution / test-repair prompts. `LlmClient` is a `@FunctionalInterface`. Built-in implementations: `AnthropicLlmClient`, `OpenAiLlmClient` (also used for Ollama via OpenAI-compatible API), `MockLlmClient`. All calls are wrapped by `RecordingLlmClient` → `LlmInteractionLog`. See [llm-prompts.md](docs/reference/llm-prompts.md).

**View system** — `ViewDefinition` embedded in the spec describes a UI component tree. `ViewEvaluator` resolves it against the merged document. Returns an `EvaluatedView` ready for any renderer. See [view-system.md](docs/reference/view-system.md).

## REST API

The full endpoint list, request/response shapes, WebSocket protocol, and console command reference
live in [api-reference.md](docs/reference/api-reference.md) — the single source of truth; do not
duplicate endpoint tables here or elsewhere.

## Configuration

Every `valem.*` property (with defaults) lives in
[configuration.md](docs/deployment/configuration.md) — the single source of truth; do not duplicate
property tables here or elsewhere. Operational notes an agent commonly needs:

- Storage is in-memory unless `valem.persistence-dir` or `valem.storage.*` selects a durable
  backend. Specs are saved on create/evolve; state persists as a baseline snapshot + an incremental
  RFC 6902 mutation log (the mutation patch is written **inside the model lock**). On load the log
  is replayed create-as-you-go and compaction is offset-pinned; `ModelLoader` reloads all models on
  startup and **degrades a corrupt-state model to spec-only** rather than dropping it.
- LLM beans are only created when `valem.llm.mock=true`, `valem.llm.api-key` is non-blank, or the
  provider is `ollama`; otherwise `/models/generate*` returns 503. The key comes from
  `valem.llm.api-key` (env `VALEM_LLM_API_KEY`) — there is no provider-specific env fallback.

## Coding conventions

- Java 21: prefer records, sealed interfaces, pattern-matching switch.
- No Spring in `valem-core` or `valem-service`; all Spring code lives in `valem-api`.
- `ModelRuntime` is **not thread-safe** — `ModelService` synchronizes on the runtime for all operations that touch non-thread-safe structures: mutations (`mutate`, `patchMutate`, `restore`, `evolveSpec`) **and** reads that either write state during evaluation (`getState`, `getFieldValue` — LAZY derivations write to `derivedCache`) or iterate mutable collections (`getHistory`, `explain`, `snapshot` — `ArrayDeque`/`ArrayList` modified under the same lock).
- Do not evaluate constraint or effect trigger expressions against `state.baseDoc()` — use `state.mergedDocument()` so derived fields are visible.
- Derivation expressions evaluate against a level-aware snapshot of `mergedDocument()`: base fields plus all derivations from **prior** topological levels. Derivations within the same level cannot see each other. Wildcard expressions also receive `$parent` bound to the current array element.
- `ExpressionCache` is per-runtime; expressions are compiled once (expensive javac round-trip).
- Smart quotes (`"` `"`) in Java source files cause compile errors — always use ASCII `"`.
- `@PathVariable` annotations must include the explicit name string (no `-parameters` compiler flag set) — e.g. `@PathVariable("id") String id`.
- PowerShell 5.1: `&&` is not valid — use `;` or separate commands.

## Reference documentation

Documentation is organized under `docs/` in six chapters — `getting-started/`, `usage-scenarios/`,
`model-guide/`, `reference/`, `deployment/`, `extending/` — plus `glossary.md` and `libraries.md`.
Start at [docs/README.md](docs/README.md) (task-keyed index; also the Jekyll source of the published docs site).
Conventions: `reference/` = the as-built interfaces (specify), `model-guide/` = how they behave (explain).
One topic, one home — link, don't duplicate. Moved a page? add its old URL to `redirect_from:`.
(Design proposals, ADRs, audits, and business docs live in the private `Valem-internal` repo.)

| Document | Covers |
|---|---|
| [docs/reference/model-spec-format.md](docs/reference/model-spec-format.md) | ModelSpec map + top-level structure + path notation; the field-by-field pages live in [docs/reference/model-spec/](docs/reference/model-spec/) (schema-and-values, derivations-and-constraints, effects, tests-and-evolution, views, component-catalog) |
| [docs/reference/api-reference.md](docs/reference/api-reference.md) | REST + WebSocket + console API surface (request/response shapes, command reference) |
| [docs/reference/mcp-tools.md](docs/reference/mcp-tools.md) | Every MCP tool + resource: arguments, result/error shapes, protocol notes |
| [docs/extending/client-sdks.md](docs/extending/client-sdks.md) | Typed TypeScript + Java client SDKs (REST + reconnecting subscribe + audit) |
| [docs/deployment/mcp-server.md](docs/deployment/mcp-server.md) | MCP server (`valem-mcp`): drive a model from an agent session over the Model Context Protocol |
| [docs/usage-scenarios/examples-gallery.md](docs/usage-scenarios/examples-gallery.md) | Ready-to-run example specs (incl. the insurance-quote model + golden test suite) |
| [docs/deployment/configuration.md](docs/deployment/configuration.md) | Every `valem.*` property (single source) |
| [docs/reference/view-system.md](docs/reference/view-system.md) | View system architecture, component types, EvaluatedView contract |
| [docs/reference/llm-prompts.md](docs/reference/llm-prompts.md) | LLM prompt structure and generation loop |
| [docs/deployment/security-model.md](docs/deployment/security-model.md) | Auth (coarse key gate), WebSocket token auth, SSRF, blob/rate limits |
| [docs/extending/architecture.md](docs/extending/architecture.md) | Component map, data flow, key design decisions |
| [docs/extending/reactive-engine.md](docs/extending/reactive-engine.md) | Dependency graph, reactive algorithm, state layer, explainability internals |
