---
title: Security model
parent: Reference
nav_order: 5
description: "Authentication, authorization, and network-egress surfaces — and their limits."
---

# Security Model

How authentication, authorization, and the network-egress surfaces work in the as-built system —
and their current limits.

---

## Authentication

`valem-api` ships a single-shared-key gate (`SecurityConfig`):

- **`valem.api.key` set** → every request must carry `Authorization: Bearer <key>`;
  all authenticated callers receive the same `ROLE_API`. There is no per-user identity.
- **`valem.api.key` blank/absent** → **open mode**: all requests permitted, warning logged.
  Intended for development only.

**Exception — health probes.** `GET /actuator/health` and `/actuator/info` are always reachable
without the API key (the `ApiKeyFilter` bypasses them and authorization permits them) so orchestrator
liveness/readiness checks work. `/actuator/metrics` and `/actuator/prometheus` remain behind the key.

Security headers (HSTS, frame-deny, content-type-options) are applied in all modes; CSRF is
disabled (stateless bearer API, no cookies). The key is compared with `MessageDigest.isEqual`
(constant-time), so the comparison does not leak key length or matching-prefix length via timing.

## Authorization

There is **no per-field authorization**. Access is a single coarse gate: any caller past the
`valem.api.key` check (or any caller in open mode / inside the deployment perimeter) may read,
mutate, and evolve **every** field of every model. Field-level RBAC (`fieldAccess`, `X-Roles`,
read/write roles) was removed — it was self-asserted and never an enforced boundary. See
[configuration.md](configuration.md) for the access gate.

## WebSocket

The handshake is authenticated by a query-param token (browsers cannot set headers on the WS
handshake): clients connect with `?token=<apiKey>`, validated by a `HandshakeInterceptor` against
`valem.api.key` (constant-time). A missing/invalid token is rejected with HTTP 401. When no
API key is configured the handshake is open (development mode), mirroring REST.

Allowed origins come from `valem.websocket.allowed-origins` (comma-separated); when unset the
endpoint is **same-origin only**. The previous `setAllowedOrigins("*")` default is removed, closing
the cross-site WebSocket-hijacking vector. Configure `*` explicitly only for development.

### Residual risk: the token travels in the URL

Because the browser WebSocket API cannot set request headers on the handshake, the API key is passed
as the `?token=` query parameter. Query strings are commonly written to proxy/load-balancer access
logs and browser history, so the key can be captured there even though the connection itself is TLS.
Mitigations for a hardened deployment:

- Terminate the WebSocket behind a proxy that strips or does not log query strings.
- Prefer a **short-lived handshake ticket** over the long-lived API key: mint a single-use,
  short-TTL token from an authenticated REST call and present *that* as `?token=`, so a leaked URL
  exposes only an already-expired ticket rather than the standing key. (Not yet implemented.)
- Rotate `valem.api.key` if a handshake URL is known to have been logged by an untrusted hop.

## Outbound network egress

### LLM web-fetch (`web_fetch` tool)
`WebFetchTool` guards SSRF during spec generation: https/http only; **every** resolved address
(all A/AAAA records) is validated and blocked when it falls in loopback / private (RFC 1918) /
link-local / multicast / shared-space (100.64/10) / IPv6 unique-local (`fc00::/7`) ranges;
IPv4-mapped IPv6 (`::ffff:a.b.c.d`) is unwrapped and re-checked as IPv4; redirects are not followed;
HTML is stripped to text; a per-session call cap applies; URL credentials are sanitized in logs.
The host is re-resolved and re-validated immediately before the request to narrow the DNS-rebinding
window (java.net.http exposes no resolver hook to fully pin the connection while preserving TLS
hostname verification, so a microsecond TOCTOU residue remains — disable with
`valem.llm.web-fetch.enabled=false` in fully untrusted environments).

### Legacy `actions` — removed, not merely restricted
The `actions` section is removed from the spec format entirely; effects (executor `caller`/`server`/
`llm`/`timer`/plugin) are the only egress surface. `ModelSpec`'s constructor rejects any spec that
still carries a non-empty `actions` array outright (no per-target inspection — the field itself is
gone), with a message pointing at the effects migration.

### Pluggable effect kinds — egress guard does not extend to plugins
`EgressGuard` (SSRF/private-IP blocking, `valem.effects.allow-private-ips`) and the response-size
cap (`valem.effects.max-response-bytes`) are wired only into the built-in `server` executor
(`HttpEffectExecutor`). A third-party `EffectKind`/`EffectExecutor` plugin (discovered via
`ServiceLoader`, routed by `CompositeEffectExecutor`) performs its own I/O with **no platform-enforced
SSRF or response-size guard** — that responsibility falls entirely to the plugin author. Only install
effect-kind plugin jars from trusted sources, and use `valem.effects.kinds.enabled` to allowlist
which discovered kinds may actually run.

### Inherited-effect approval (multi-tenant branching)
When a model is branched from a template owned by a *different* owner (see composition/branching in
[model-spec-format.md](model-spec-format.md)), any inherited effect whose executor is not `caller`
(i.e. it does outbound I/O) is quarantined: inert until the new owner explicitly approves it. This is
enforced by `EffectApprovalRegistry`, keyed to the effect's canonical `definitionHash` so any edit to
the effect's executable bytes re-quarantines it. Same-owner and branch-authored effects always run —
only cross-owner inheritance is gated. Policy is `valem.authz.inherited-effects`
(`approve` default — quarantine until approved via `POST /models/{id}/effects/{effectId}/approve`;
`allow` — no gating; `deny` — inherited cross-owner effects never dispatch). Pending effects are
listed at `GET /models/{id}/effects/pending`. See
[configuration.md](configuration.md).

## Resource limits

- **Blobs:** explicit servlet multipart caps (`spring.servlet.multipart.max-file-size` /
  `max-request-size`) plus a per-blob cap (`valem.blob.max-bytes`, 413 on exceed). The
  in-memory store buffers the upload in heap; set `valem.blob.max-total-bytes` to bound total
  heap usage — over-budget uploads are **rejected** (413), not evicted, since content-addressed
  refs must not vanish. Prefer the filesystem store for large payloads.
- **Mutations:** `valem.mutation-queue-size` (default 10) bounds concurrent mutations per
  model; excess returns 429.
- **Per-IP rate limiting:** an optional sliding-window filter (`valem.rate-limit.enabled`,
  off by default) caps requests per client IP and returns HTTP 429 + `Retry-After` over the limit.
  It honours a single `X-Forwarded-For` hop, so only enable it behind a trusted proxy.
