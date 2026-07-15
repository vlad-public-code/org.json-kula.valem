---
title: Deployment & operations
parent: Guides
nav_order: 8
description: "Running Valem durably and safely: run modes, persistence, blobs."
---

# Deployment & Operations

Running Valem durably and safely. Full property list:
[../reference/configuration.md](../reference/configuration.md). Security posture:
[../reference/security-model.md](../reference/security-model.md).

## Run modes
- **In-memory (default).** No persistence; all models and state lost on restart. Fine for demos,
  agents, and the console app.
- **Filesystem-persisted.** Set `valem.persistence-dir`; specs and snapshots are written
  atomically and reloaded on startup.

## Durable configuration

```yaml
valem:
  api:
    key: ${VALEM_API_KEY}        # require Authorization: Bearer for every request
  persistence-dir: /var/valem/data
  blob-store: filesystem
  blob-store-path: /var/valem/blobs
```

Persistence layout:
```
{persistence-dir}/{modelId}/
  spec.json       — ModelSpec (written on create and spec evolve)
  snapshot.json   — baseline state snapshot (rewritten on compaction and restore)
  mutations.jsonl — incremental RFC 6902 mutation log appended after every committed mutation
  audit.jsonl     — durable append-only audit trail (when an audit backend is enabled)
```
State persists as a baseline snapshot plus an incremental mutation log; on load the log is replayed
create-as-you-go, and it is compacted back into a new baseline once it passes
`valem.storage.compaction-threshold`. Writes are atomic (`.tmp` then rename); a crash mid-write
leaves the previous version intact. A model whose state fails to reconstruct loads spec-only rather
than being dropped.

## Hardening checklist
- **Set `valem.api.key`** — otherwise the API is fully open (open/dev mode logs a warning). The key
  gate is coarse: any authenticated caller can read/mutate/evolve every field of every model (there
  is no per-field access control). See [../reference/security-model.md](../reference/security-model.md).
- **WebSocket** is authenticated by a `?token=<apiKey>` query param and restricted to
  `valem.websocket.allowed-origins` (same-origin only when unset). Front it with a proxy that does
  not log query strings, since the token travels in the URL.
- **`valem.llm.web-fetch.enabled=false`** in untrusted environments (SSRF gaps).
- **Constrain `server` effects** — the built-in `server` executor is fronted by an SSRF egress guard
  (loopback/private-IP blocking, https-only); tighten it further with `valem.effects.allowed-hosts`
  (destination allowlist) and restrict which effect kinds may run at all with
  `valem.effects.kinds.enabled`. Third-party effect-kind **plugins enforce their own** egress rules —
  only install plugin jars from trusted sources.
- **Blob uploads** buffer in heap for the in-memory store — keep Spring multipart limits, use the
  filesystem store for larger payloads.
- **`valem.mutation-queue-size`** bounds concurrent mutations per model (429 on overflow);
  add per-IP rate limiting at the proxy (the base API has none).

## Scaling notes
State is in-memory and a single model serializes mutations under one lock; scale by running many
independent models rather than expecting intra-model parallelism. A single hot/shared model is a
throughput ceiling: scale horizontally across independent models.

## Pluggable persistence backends
Spec, state, and blob storage each select a backend independently (memory / filesystem / PostgreSQL /
MongoDB / Redis / S3), wired à-la-carte as adapter jars — see
[../reference/configuration.md](../reference/configuration.md) for the `valem.storage.*` properties.
