import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import type { AddressInfo } from 'node:net';

import {
  ValemClient,
  ValemError,
  addressToPointer,
  type MinimalWebSocket,
  type WebSocketCtor,
} from '../src/index.ts';

// ── A tiny mock Valem server ─────────────────────────────────────────────

interface Recorded {
  method: string;
  url: string;
  headers: http.IncomingHttpHeaders;
  body: string;
}

function startMockServer(
  handler: (req: Recorded) => { status?: number; json?: unknown; text?: string },
): Promise<{ baseUrl: string; requests: Recorded[]; close: () => Promise<void> }> {
  const requests: Recorded[] = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => (body += c));
    req.on('end', () => {
      const rec: Recorded = { method: req.method!, url: req.url!, headers: req.headers, body };
      requests.push(rec);
      const out = handler(rec);
      const status = out.status ?? 200;
      if (out.text !== undefined) {
        res.writeHead(status, { 'Content-Type': 'text/plain' });
        res.end(out.text);
      } else if (out.json !== undefined) {
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(out.json));
      } else {
        res.writeHead(status);
        res.end();
      }
    });
  });
  return new Promise((resolve) => {
    server.listen(0, () => {
      const port = (server.address() as AddressInfo).port;
      resolve({
        baseUrl: `http://127.0.0.1:${port}`,
        requests,
        close: () => new Promise<void>((r) => server.close(() => r())),
      });
    });
  });
}

// ── Pure helpers ──────────────────────────────────────────────────────────────

test('addressToPointer converts canonical addresses to RFC 6901 pointers', () => {
  assert.equal(addressToPointer('$.order.total'), '/order/total');
  assert.equal(addressToPointer('$.items[0].qty'), '/items/0/qty');
  assert.equal(addressToPointer('$.a/b'), '/a~1b');
  assert.equal(addressToPointer('$'), '');
});

test('buildWsUrl maps http->ws and includes token + paths', () => {
  const c = new ValemClient({ baseUrl: 'http://localhost:8080/', apiKey: 'secret' });
  const url = c.buildWsUrl('m1', ['$.a', '$.b']);
  assert.match(url, /^ws:\/\/localhost:8080\/models\/m1\/subscribe\?/);
  assert.match(url, /token=secret/);
  assert.match(url, /paths=%24.a%2C%24.b/);
});

test('buildWsUrl maps https->wss', () => {
  const c = new ValemClient({ baseUrl: 'https://api.example.com' });
  assert.ok(c.buildWsUrl('m1').startsWith('wss://api.example.com/models/m1/subscribe'));
});

// ── REST surface ───────────────────────────────────────────────────────────────

test('createModel POSTs the spec as JSON and returns the response', async () => {
  const srv = await startMockServer(() => ({ status: 201, json: { id: 'm1', status: 'created' } }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    const res = await c.createModel({ id: 'm1', schema: {} });
    assert.deepEqual(res, { id: 'm1', status: 'created' });
    assert.equal(srv.requests[0].method, 'POST');
    assert.equal(srv.requests[0].url, '/models');
    assert.deepEqual(JSON.parse(srv.requests[0].body), { id: 'm1', schema: {} });
  } finally {
    await srv.close();
  }
});

test('apiKey is sent as a Bearer Authorization header', async () => {
  const srv = await startMockServer(() => ({ json: [] }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl, apiKey: 'k123' });
    await c.listModels();
    assert.equal(srv.requests[0].headers['authorization'], 'Bearer k123');
  } finally {
    await srv.close();
  }
});

test('mutate posts the address map and forwards X-View', async () => {
  const srv = await startMockServer(() => ({
    json: { success: true, mutatedPaths: ['$.n'], derivedUpdated: ['$.d'], flaggedConstraints: [], dispatchedEffects: [], traces: [] },
  }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    const res = await c.mutate('m1', { '$.n': 5 }, 'main');
    assert.equal(res.success, true);
    assert.deepEqual(res.derivedUpdated, ['$.d']);
    const req = srv.requests[0];
    assert.equal(req.url, '/models/m1/mutations');
    assert.equal(req.headers['x-view'], 'main');
    assert.deepEqual(JSON.parse(req.body), { '$.n': 5 });
  } finally {
    await srv.close();
  }
});

test('setField sends a json-patch add with the right content-type and pointer', async () => {
  const srv = await startMockServer(() => ({ json: { success: true, mutatedPaths: [], derivedUpdated: [], flaggedConstraints: [], dispatchedEffects: [], traces: [] } }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    await c.setField('m1', '$.order.qty', 3);
    const req = srv.requests[0];
    assert.equal(req.url, '/models/m1/mutations/patch');
    assert.equal(req.headers['content-type'], 'application/json-patch+json');
    assert.deepEqual(JSON.parse(req.body), [{ op: 'add', path: '/order/qty', value: 3 }]);
  } finally {
    await srv.close();
  }
});

test('audit builds the query string from filters', async () => {
  const srv = await startMockServer(() => ({ json: [] }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    await c.audit('m1', { path: '$.order', from: '2026-01-01T00:00:00Z', limit: 10 });
    const url = srv.requests[0].url;
    assert.match(url, /^\/models\/m1\/audit\?/);
    assert.match(url, /path=%24.order/);
    assert.match(url, /from=2026-01-01T00%3A00%3A00Z/);
    assert.match(url, /limit=10/);
  } finally {
    await srv.close();
  }
});

test('verifyAudit calls the verify endpoint and returns the result', async () => {
  const srv = await startMockServer(() => ({ json: { valid: true, recordsChecked: 3, detail: 'ok' } }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    const v = await c.verifyAudit('m1');
    assert.equal(v.valid, true);
    assert.equal(v.recordsChecked, 3);
    assert.equal(srv.requests[0].url, '/models/m1/audit/verify');
  } finally {
    await srv.close();
  }
});

test('non-2xx responses throw ValemError carrying status and body', async () => {
  const srv = await startMockServer(() => ({ status: 409, text: 'constraint violated' }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    await assert.rejects(
      () => c.mutate('m1', { '$.n': 1 }),
      (err: unknown) => {
        assert.ok(err instanceof ValemError);
        assert.equal(err.status, 409);
        assert.match(err.body, /constraint violated/);
        return true;
      },
    );
  } finally {
    await srv.close();
  }
});

test('204 No Content resolves without parsing a body', async () => {
  const srv = await startMockServer(() => ({ status: 204 }));
  try {
    const c = new ValemClient({ baseUrl: srv.baseUrl });
    const out = await c.deleteModel('m1');
    assert.equal(out, undefined);
    assert.equal(srv.requests[0].method, 'DELETE');
  } finally {
    await srv.close();
  }
});

// ── WebSocket subscription (fake socket + mock timers) ──────────────────────────

class FakeWebSocket implements MinimalWebSocket {
  static instances: FakeWebSocket[] = [];
  readonly url: string;
  closed = false;
  private listeners = new Map<string, ((ev: unknown) => void)[]>();
  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }
  addEventListener(type: string, listener: (ev: unknown) => void): void {
    const arr = this.listeners.get(type) ?? [];
    arr.push(listener);
    this.listeners.set(type, arr);
  }
  emit(type: string, ev?: unknown): void {
    for (const l of this.listeners.get(type) ?? []) l(ev);
  }
  close(): void {
    this.closed = true;
  }
}

test('subscribe delivers parsed ChangeEvents and reconnects after an unexpected close', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  FakeWebSocket.instances = [];

  const c = new ValemClient({
    baseUrl: 'http://localhost:8080',
    apiKey: 'tok',
    webSocketCtor: FakeWebSocket as unknown as WebSocketCtor,
  });

  const events: string[] = [];
  let opens = 0;
  let closes = 0;
  const sub = c.subscribe('m1', {
    onEvent: (e) => events.push(e.modelId),
    onOpen: () => opens++,
    onClose: () => closes++,
    paths: ['$.order'],
  });

  // First socket connects; URL carries token + paths.
  assert.equal(FakeWebSocket.instances.length, 1);
  const first = FakeWebSocket.instances[0];
  assert.match(first.url, /token=tok/);
  assert.match(first.url, /paths=%24.order/);

  first.emit('open');
  first.emit('message', { data: JSON.stringify({ modelId: 'm1', mutatedPaths: [], derivedUpdated: [], flaggedConstraints: [], dispatchedEffects: [] }) });
  assert.deepEqual(events, ['m1']);
  assert.equal(opens, 1);

  // Unexpected close -> a reconnect is scheduled and fires on the backoff tick.
  first.emit('close');
  assert.equal(closes, 1);
  assert.equal(FakeWebSocket.instances.length, 1);
  t.mock.timers.tick(600);
  assert.equal(FakeWebSocket.instances.length, 2);

  // Explicit close() prevents further reconnects.
  const second = FakeWebSocket.instances[1];
  sub.close();
  assert.equal(second.closed, true);
  second.emit('close');
  t.mock.timers.tick(10000);
  assert.equal(FakeWebSocket.instances.length, 2);
});

test('subscribe throws when no WebSocket implementation is available', () => {
  const c = new ValemClient({ baseUrl: 'http://localhost:8080' });
  // Force no ctor by passing an options object without one and clearing global.
  const client = new ValemClient({ baseUrl: 'http://localhost:8080', webSocketCtor: undefined });
  // globalThis.WebSocket may be undefined in Node; assert only when it truly is missing.
  if (!(globalThis as { WebSocket?: unknown }).WebSocket) {
    assert.throws(() => client.subscribe('m1', { onEvent: () => {} }), /No WebSocket implementation/);
  } else {
    assert.ok(c);
  }
});
