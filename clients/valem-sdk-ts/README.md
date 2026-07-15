# @valem/sdk

Typed TypeScript/JavaScript client for the [Valem](../../README.md) reactive computation
runtime. Isomorphic (browser + Node), dependency-free, with a reconnecting WebSocket subscription and
first-class support for the durable audit trail. It replaces the hand-rolled `fetch` + JSON-Patch +
reconnect plumbing you'd otherwise write per project.

## Install

```bash
npm install @valem/sdk
# In Node (for subscribe), also install a WebSocket implementation:
npm install ws
```

## Quick start

```ts
import { ValemClient } from '@valem/sdk';

const client = new ValemClient({
  baseUrl: 'http://localhost:8080',
  apiKey: process.env.VALEM_KEY, // optional; sent as Bearer + ws ?token=
});

// Create a model from a spec (e.g. the insurance-quote example).
await client.createModel(spec);

// Mutate by canonical address map — the primary path.
const res = await client.mutate('insurance-quote', {
  '$.quote.applicant.age': 35,
  '$.quote.coverage': 100000,
});
console.log(res.derivedUpdated);          // ["$.quote.annualPremium", ...]

// Or a single field via JSON Patch (pointer conversion handled for you).
await client.setField('insurance-quote', '$.quote.applicant.smoker', true);

// Read merged state, explain a value, and query the durable audit trail.
const state = await client.getState('insurance-quote');
const why   = await client.explain('insurance-quote', '$.quote.annualPremium');
const trail = await client.audit('insurance-quote', { path: '$.quote', limit: 50 });
```

### Subscribe to live changes (reconnecting)

```ts
import WebSocket from 'ws';

const sub = client.subscribe('insurance-quote', {
  onEvent: (e) => console.log('changed:', e.mutatedPaths, e.derivedUpdated),
  onOpen:  () => console.log('connected'),
  paths:   ['$.quote'],          // optional server-side prefix filter
});
// ...later
sub.close();                     // stops the socket and prevents reconnects
```

In Node, pass the constructor once when creating the client (browsers use the global `WebSocket`
automatically):

```ts
import WebSocket from 'ws';
const client = new ValemClient({ baseUrl, webSocketCtor: WebSocket });
```

The subscription reconnects automatically with exponential backoff (0.5s → 8s) after an unexpected
close, and stops permanently once you call `close()`.

## API surface

| Method | Endpoint |
|---|---|
| `listModels()` | `GET /models` |
| `createModel(spec)` | `POST /models` |
| `getModel(id)` / `getSpec(id)` / `deleteModel(id)` | `GET`/`GET`/`DELETE /models/{id}` |
| `getState(id, at?)` / `getField(id, path)` | `GET /models/{id}/state[/{path}]` |
| `mutate(id, map, viewId?)` | `POST /models/{id}/mutations` |
| `patch(id, ops, viewId?)` / `setField(id, path, value)` | `POST /models/{id}/mutations/patch` |
| `explain(id, path)` | `GET /models/{id}/explain/{path}` |
| `audit(id, {path, from, to, limit})` | `GET /models/{id}/audit` |
| `verifyAudit(id)` | `GET /models/{id}/audit/verify` (tamper-evident hash chain) |
| `history(id)` / `effectiveSchema(id, path)` | `GET /models/{id}/history` · `/schema/{path}` |
| `snapshot(id)` / `restore(id, snap)` | `POST /models/{id}/snapshot` · `/restore` |
| `evolveSpec(id, evolution)` | `POST /models/{id}/spec/evolve` |
| `getView(id, viewId?)` | `GET /models/{id}/view[/{viewId}]` |
| `subscribe(id, handlers)` | `WS /models/{id}/subscribe` |

All REST methods return parsed JSON and throw `ValemError` (with `.status` and `.body`) on any
non-2xx response.

## Development

```bash
npm install
npm test          # node --test (runs the .ts sources directly on Node >= 22)
npm run build     # tsc -> dist/ (for publishing)
npm run typecheck # tsc --noEmit
```

Tests are hermetic: the REST surface is exercised against an in-process mock server and the
reconnecting subscription against a fake WebSocket driven by mock timers — no running backend
required.
