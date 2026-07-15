import type { ModelInfo, MutationResponse, DerivationTrace, Snapshot, SpecEvolution, LlmInteraction } from './types';
import type { EvaluatedView } from 'valem-view-react';

// ── LLM progress streaming ────────────────────────────────────────────────────

export type LlmProgressEventData =
  | { type: 'llm_requesting'; attempt: number }
  | { type: 'tool_calling'; tool: string; detail: string }
  | { type: 'tool_completed'; tool: string; resultSummary: string }
  | { type: 'validating'; attempt: number }
  | { type: 'validation_failed'; attempt: number; errors: string[] }
  | { type: 'test_running'; attempt: number }
  | { type: 'test_failed'; attempt: number; failCount: number }
  | { type: 'retrying'; attempt: number; maxAttempts: number };

export type GenerateStreamDone =
  | { valid: true; spec: unknown }
  | { valid: false; errors: { location: string; message: string }[]; rawResponse: string };

export type EvolveAiStreamDone =
  | { version: string; spec: unknown }
  | { error: string };

/** Parses an SSE stream from a fetch response body. Calls {@code onEvent} for each named event. */
async function consumeSseStream(
  res: Response,
  onEvent: (name: string, data: unknown) => void
): Promise<void> {
  const reader = res.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() ?? '';
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim();
        if (raw) {
          try { onEvent(eventName, JSON.parse(raw)); } catch { /* ignore malformed */ }
        }
        eventName = '';
      }
    }
  }
}

/**
 * Runs SpecGenerator.generate() with streaming progress via SSE.
 * Returns a cancel function; call it to abort.
 */
export function streamGenerate(
  modelId: string,
  domainDescription: string,
  includeView: boolean,
  onProgress: (e: LlmProgressEventData) => void,
  onDone: (result: GenerateStreamDone) => void,
  onError: (message: string) => void
): () => void {
  const controller = new AbortController();
  (async () => {
    const res = await fetch('/models/generate/stream', {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modelId, domainDescription, includeView }),
    });
    if (!res.ok) {
      const text = await res.text();
      onError(text);
      return;
    }
    await consumeSseStream(res, (name, data) => {
      if (name === 'progress') onProgress(data as LlmProgressEventData);
      else if (name === 'done') onDone(data as GenerateStreamDone);
      else if (name === 'error') onError((data as { message: string }).message);
    });
  })().catch(err => { if (err.name !== 'AbortError') onError(String(err)); });
  return () => controller.abort();
}

/**
 * Runs SpecGenerator.generateEvolution() with streaming progress via SSE.
 * Returns a cancel function; call it to abort.
 */
export function streamEvolveAi(
  modelId: string,
  description: string,
  onProgress: (e: LlmProgressEventData) => void,
  onDone: (result: EvolveAiStreamDone) => void,
  onError: (message: string) => void
): () => void {
  const controller = new AbortController();
  (async () => {
    const res = await fetch(`/models/${modelId}/spec/evolve/ai/stream`, {
      method: 'POST',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ description }),
    });
    if (!res.ok) {
      const text = await res.text();
      onError(text);
      return;
    }
    await consumeSseStream(res, (name, data) => {
      if (name === 'progress') onProgress(data as LlmProgressEventData);
      else if (name === 'done') onDone(data as EvolveAiStreamDone);
      else if (name === 'error') onError((data as { message: string }).message);
    });
  })().catch(err => { if (err.name !== 'AbortError') onError(String(err)); });
  return () => controller.abort();
}

async function request<T>(method: string, path: string, body?: unknown, contentType = 'application/json', extraHeaders?: Record<string, string>): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': contentType } : {}),
      ...extraHeaders,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const text = await res.text();
    let msg: string;
    try { msg = JSON.stringify(JSON.parse(text), null, 2); }
    catch { msg = text; }
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export class ApiError {
  constructor(public readonly status: number, public readonly body: string) {}
  toString() { return `HTTP ${this.status}: ${this.body}`; }
}

export const api = {
  listModels: () => request<string[]>('GET', '/models'),

  getModel: (id: string) => request<ModelInfo>('GET', `/models/${id}`),

  createModel: (spec: unknown) => request<{ id: string; created: boolean }>('POST', '/models', spec),

  getSpec: (id: string) => request<unknown>('GET', `/models/${id}/spec`),

  getState: (id: string) => request<Record<string, unknown>>('GET', `/models/${id}/state`),

  mutate: (id: string, mutations: Record<string, unknown>, viewId?: string) =>
    request<MutationResponse>('POST', `/models/${id}/mutations`, mutations, 'application/json',
      viewId ? { 'X-View': viewId } : undefined),

  patchMutate: (id: string, ops: unknown[], viewId?: string) =>
    request<MutationResponse>('POST', `/models/${id}/mutations/patch`, ops, 'application/json-patch+json',
      viewId ? { 'X-View': viewId } : undefined),

  effectiveSchema: (id: string, path: string) =>
    request<Record<string, unknown>>('GET', `/models/${id}/schema/${encodeURIComponent(path)}`),

  explain: (id: string, path: string) =>
    request<DerivationTrace[]>('GET', `/models/${id}/explain/${encodeURIComponent(path)}`),

  snapshot: (id: string) => request<Snapshot>('POST', `/models/${id}/snapshot`),

  restore: (id: string, snap: Snapshot) =>
    request<void>('POST', `/models/${id}/restore`, snap),

  evolveSpec: (id: string, evolution: SpecEvolution) =>
    request<{ id: string; version: string }>('POST', `/models/${id}/spec/evolve`, evolution),

  deleteModel: (id: string) => request<void>('DELETE', `/models/${id}`),

  previewPrompt: (modelId: string, domainDescription: string, buildUI = false) =>
    request<{ prompt: string }>('POST', '/models/generate/preview', { modelId, domainDescription, includeView: buildUI }),

  generateFromPrompt: (modelId: string, prompt: string) =>
    request<{ valid: boolean; spec?: unknown; errors?: unknown[]; rawResponse?: string }>(
      'POST', '/models/generate', { modelId, prompt }),

  // Generate from a plain description; the server builds the prompt and never returns it (keeps the
  // raw prompt out of the browser — it appears only in the LLM interaction log). Used by the sandbox.
  generateFromDescription: (modelId: string, domainDescription: string, buildUI = true) =>
    request<{ valid: boolean; spec?: unknown; errors?: unknown[]; rawResponse?: string }>(
      'POST', '/models/generate', { modelId, domainDescription, includeView: buildUI }),

  previewEvolutionPrompt: (modelId: string, currentSpec: unknown, evolutionRequest: string, updateUI = false) =>
    request<{ prompt: string }>('POST', '/models/generate/evolution/preview', {
      modelId, currentSpec, evolutionRequest, includeView: updateUI,
    }),

  generateEvolutionFromPrompt: (modelId: string, prompt: string) =>
    request<{ valid: boolean; evolution?: unknown; error?: string; rawResponse?: string }>(
      'POST', '/models/generate/evolution', { modelId, prompt }),

  getView: (id: string, viewId?: string) =>
    request<EvaluatedView>('GET', viewId ? `/models/${id}/view/${encodeURIComponent(viewId)}` : `/models/${id}/view`),

  getLlmInteractions: () => request<LlmInteraction[]>('GET', '/llm/interactions'),
};
