import type {
  AuditQuery,
  AuditRecord,
  AuditVerification,
  ChangeEvent,
  CreateModelResponse,
  DerivationTrace,
  Json,
  JsonPatchOp,
  ModelInfo,
  Mutations,
  MutationResponse,
  Snapshot,
} from './types.ts';

export * from './types.ts';

/** Thrown for any non-2xx HTTP response. Carries the status and the (possibly JSON) body text. */
export class ValemError extends Error {
  readonly status: number;
  readonly body: string;
  constructor(status: number, body: string) {
    super(`Valem HTTP ${status}: ${body}`);
    this.name = 'ValemError';
    this.status = status;
    this.body = body;
  }
}

/** Minimal structural type both the browser `WebSocket` and the Node `ws` package satisfy. */
export interface MinimalWebSocket {
  addEventListener(type: string, listener: (ev: unknown) => void): void;
  close(): void;
}
export type WebSocketCtor = new (url: string) => MinimalWebSocket;

export interface ValemClientOptions {
  /** Base URL of the Valem API, e.g. "http://localhost:8080". No trailing slash required. */
  baseUrl: string;
  /** API key sent as `Authorization: Bearer <key>` and as the WebSocket `?token=`. Optional. */
  apiKey?: string;
  /** Custom fetch implementation (defaults to global fetch; supply one for older Node). */
  fetch?: typeof fetch;
  /**
   * WebSocket constructor for {@link ValemClient.subscribe}. Defaults to the global
   * `WebSocket` (browsers). In Node, pass the `ws` package's constructor.
   */
  webSocketCtor?: WebSocketCtor;
}

export interface SubscribeHandlers {
  onEvent: (event: ChangeEvent) => void;
  onOpen?: () => void;
  onClose?: () => void;
  onError?: (err: unknown) => void;
  /** Filter server-side to events touching one of these path prefixes (e.g. ["$.order"]). */
  paths?: string[];
}

/** Handle for an active subscription. Call {@link Subscription.close} to stop and prevent reconnects. */
export interface Subscription {
  close(): void;
}

const DEFAULT_BACKOFF_MS = [500, 1000, 2000, 4000, 8000];

/**
 * Typed client for the Valem runtime. One instance can drive many models. All REST calls
 * return parsed JSON and throw {@link ValemError} on non-2xx. {@link subscribe} opens a
 * reconnecting WebSocket.
 */
export class ValemClient {
  private readonly baseUrl: string;
  private readonly apiKey?: string;
  private readonly fetchImpl: typeof fetch;
  private readonly webSocketCtor?: WebSocketCtor;

  constructor(options: ValemClientOptions) {
    if (!options || !options.baseUrl) {
      throw new Error('ValemClient requires a baseUrl');
    }
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.apiKey = options.apiKey;
    const f = options.fetch ?? globalThis.fetch;
    if (!f) throw new Error('No fetch implementation available; pass options.fetch');
    this.fetchImpl = f.bind(globalThis);
    this.webSocketCtor =
      options.webSocketCtor ?? (globalThis as { WebSocket?: WebSocketCtor }).WebSocket;
  }

  // ── Models ────────────────────────────────────────────────────────────────

  listModels(): Promise<string[]> {
    return this.request('GET', '/models');
  }

  createModel(spec: Json): Promise<CreateModelResponse> {
    return this.request('POST', '/models', spec);
  }

  getModel(id: string): Promise<ModelInfo> {
    return this.request('GET', `/models/${enc(id)}`);
  }

  getSpec(id: string): Promise<Json> {
    return this.request('GET', `/models/${enc(id)}/spec`);
  }

  deleteModel(id: string): Promise<void> {
    return this.request('DELETE', `/models/${enc(id)}`);
  }

  // ── State ─────────────────────────────────────────────────────────────────

  getState(id: string, at?: string): Promise<Record<string, Json>> {
    const q = at ? `?at=${enc(at)}` : '';
    return this.request('GET', `/models/${enc(id)}/state${q}`);
  }

  getField(id: string, path: string): Promise<Json> {
    return this.request('GET', `/models/${enc(id)}/state/${enc(path)}`);
  }

  history(id: string): Promise<string[]> {
    return this.request('GET', `/models/${enc(id)}/history`);
  }

  effectiveSchema(id: string, path: string): Promise<Record<string, Json>> {
    return this.request('GET', `/models/${enc(id)}/schema/${enc(path)}`);
  }

  // ── Mutations ─────────────────────────────────────────────────────────────

  /** Apply a map of canonical addresses -> values (POST /mutations). */
  mutate(id: string, mutations: Mutations, viewId?: string): Promise<MutationResponse> {
    return this.request('POST', `/models/${enc(id)}/mutations`, mutations, {
      contentType: 'application/json',
      headers: viewId ? { 'X-View': viewId } : undefined,
    });
  }

  /** Apply an RFC 6902 JSON Patch (POST /mutations/patch). */
  patch(id: string, ops: JsonPatchOp[], viewId?: string): Promise<MutationResponse> {
    return this.request('POST', `/models/${enc(id)}/mutations/patch`, ops, {
      contentType: 'application/json-patch+json',
      headers: viewId ? { 'X-View': viewId } : undefined,
    });
  }

  /** Convenience: set a single field by canonical address, via a JSON Patch `add`. */
  setField(id: string, path: string, value: Json, viewId?: string): Promise<MutationResponse> {
    return this.patch(id, [{ op: 'add', path: addressToPointer(path), value }], viewId);
  }

  // ── Explain / audit ───────────────────────────────────────────────────────

  explain(id: string, path: string): Promise<DerivationTrace[]> {
    return this.request('GET', `/models/${enc(id)}/explain/${enc(path)}`);
  }

  /** Durable, append-only audit trail (newest-first). Filters: path prefix, from/to (ISO-8601), limit. */
  audit(id: string, query?: AuditQuery): Promise<AuditRecord[]> {
    const params = new URLSearchParams();
    if (query?.path) params.set('path', query.path);
    if (query?.from) params.set('from', query.from);
    if (query?.to) params.set('to', query.to);
    if (query?.limit != null) params.set('limit', String(query.limit));
    const qs = params.toString();
    return this.request('GET', `/models/${enc(id)}/audit${qs ? `?${qs}` : ''}`);
  }

  /** Verify the tamper-evident hash chain of a model's audit trail. */
  verifyAudit(id: string): Promise<AuditVerification> {
    return this.request('GET', `/models/${enc(id)}/audit/verify`);
  }

  // ── Snapshot / evolve / view ──────────────────────────────────────────────

  snapshot(id: string): Promise<Snapshot> {
    return this.request('POST', `/models/${enc(id)}/snapshot`);
  }

  restore(id: string, snapshot: Snapshot): Promise<void> {
    return this.request('POST', `/models/${enc(id)}/restore`, snapshot);
  }

  evolveSpec(id: string, evolution: Json): Promise<{ id: string; version: string }> {
    return this.request('POST', `/models/${enc(id)}/spec/evolve`, evolution);
  }

  getView(id: string, viewId?: string): Promise<Json> {
    const path = viewId ? `/models/${enc(id)}/view/${enc(viewId)}` : `/models/${enc(id)}/view`;
    return this.request('GET', path);
  }

  // ── WebSocket subscription ────────────────────────────────────────────────

  /**
   * Opens a reconnecting WebSocket to {@code /models/{id}/subscribe}. Reconnects with exponential
   * backoff after an unexpected close, until {@link Subscription.close} is called. Authenticates
   * with the configured API key (as `?token=`) and applies an optional server-side path filter.
   */
  subscribe(id: string, handlers: SubscribeHandlers): Subscription {
    const ctor = this.webSocketCtor;
    if (!ctor) {
      throw new Error('No WebSocket implementation; pass options.webSocketCtor (e.g. the `ws` package)');
    }
    const url = this.buildWsUrl(id, handlers.paths);

    let closed = false;
    let attempt = 0;
    let socket: MinimalWebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const connect = (): void => {
      if (closed) return;
      let ws: MinimalWebSocket;
      try {
        ws = new ctor(url);
      } catch (err) {
        handlers.onError?.(err);
        scheduleReconnect();
        return;
      }
      socket = ws;
      ws.addEventListener('open', () => {
        attempt = 0;
        handlers.onOpen?.();
      });
      ws.addEventListener('message', (ev: unknown) => {
        const data = (ev as { data?: unknown }).data;
        try {
          handlers.onEvent(JSON.parse(String(data)) as ChangeEvent);
        } catch (err) {
          handlers.onError?.(err);
        }
      });
      ws.addEventListener('error', (ev: unknown) => handlers.onError?.(ev));
      ws.addEventListener('close', () => {
        handlers.onClose?.();
        if (!closed) scheduleReconnect();
      });
    };

    const scheduleReconnect = (): void => {
      if (closed) return;
      const delay = DEFAULT_BACKOFF_MS[Math.min(attempt, DEFAULT_BACKOFF_MS.length - 1)];
      attempt += 1;
      timer = setTimeout(connect, delay);
    };

    connect();

    return {
      close(): void {
        closed = true;
        if (timer) clearTimeout(timer);
        try {
          socket?.close();
        } catch {
          /* ignore */
        }
      },
    };
  }

  /** Exposed for testing / advanced use: the ws(s):// URL for a model subscription. */
  buildWsUrl(id: string, paths?: string[]): string {
    const wsBase = this.baseUrl.replace(/^http/i, 'ws');
    const params = new URLSearchParams();
    if (this.apiKey) params.set('token', this.apiKey);
    if (paths && paths.length > 0) params.set('paths', paths.join(','));
    const qs = params.toString();
    return `${wsBase}/models/${enc(id)}/subscribe${qs ? `?${qs}` : ''}`;
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  private async request<T>(
    method: string,
    path: string,
    body?: Json,
    opts?: { contentType?: string; headers?: Record<string, string> },
  ): Promise<T> {
    const headers: Record<string, string> = { ...(opts?.headers ?? {}) };
    if (body !== undefined) headers['Content-Type'] = opts?.contentType ?? 'application/json';
    if (this.apiKey) headers['Authorization'] = `Bearer ${this.apiKey}`;

    const res = await this.fetchImpl(`${this.baseUrl}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (!res.ok) {
      const text = await safeText(res);
      throw new ValemError(res.status, text);
    }
    if (res.status === 204) return undefined as T;
    const text = await safeText(res);
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }
}

// ── helpers ───────────────────────────────────────────────────────────────

function enc(segment: string): string {
  return encodeURIComponent(segment);
}

async function safeText(res: { text(): Promise<string> }): Promise<string> {
  try {
    return await res.text();
  } catch {
    return '';
  }
}

/** Converts a canonical JSON Path address ("$.a.b" / "$.items[0].qty") to an RFC 6901 pointer. */
export function addressToPointer(address: string): string {
  let a = address.startsWith('$.') ? address.slice(2) : address.startsWith('$') ? address.slice(1) : address;
  a = a.replace(/\[(\d+)\]/g, '.$1'); // items[0] -> items.0
  if (a === '') return '';
  return (
    '/' +
    a
      .split('.')
      .filter((s) => s.length > 0)
      .map((s) => s.replace(/~/g, '~0').replace(/\//g, '~1'))
      .join('/')
  );
}
