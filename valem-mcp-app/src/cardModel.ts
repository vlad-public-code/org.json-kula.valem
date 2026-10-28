/**
 * The card's pure logic: what a tool result carries, how edits accumulate into the inputs the server
 * recomputes from, and the short summary the card hands back to the assistant. Kept free of React and
 * of the host bridge so it can be tested on its own.
 */

/** One constraint or schema finding, with the field paths it belongs to (empty for a global rule). */
export interface Issue {
  id: string;
  message: string;
  paths: string[];
}

/** What `render_model` puts under `_meta["valem/card"]`. */
export interface CardPayload {
  spec: CardSpec;
  specHash: string;
  inputs: Inputs;
  state: Record<string, unknown>;
  meta: Record<string, unknown>;
  flags: Issue[];
  /** Where this server's share page lives; absent when it hosts none. */
  shareBase?: string;
}

/** The parts of a ModelSpec the card reads; the rest is passed back to the server untouched. */
export interface CardSpec {
  id: string;
  version?: string;
  derivations?: { path: string }[];
  viewDefinition?: unknown;
  [key: string]: unknown;
}

/** Canonical path → value; every edit since the card opened, applied on top of the spec's defaults. */
export type Inputs = Record<string, unknown>;

/** `valem_recompute`'s structured result. */
export type RecomputeResult =
  | { specHash: string; state: Record<string, unknown>; meta: Record<string, unknown>; flags: Issue[] }
  | { specHash: string; rejected: 'constraint' | 'schema'; issues: Issue[] }
  | { error: 'spec_unknown' };

export const CARD_META_KEY = 'valem/card';

/** Reads the card payload out of a tool result, or null when the result has none (an error). */
export function payloadOf(result: { _meta?: Record<string, unknown> } | undefined): CardPayload | null {
  const p = result?._meta?.[CARD_META_KEY] as CardPayload | undefined;
  return p && typeof p === 'object' && p.specHash && p.spec ? p : null;
}

/** `$.items.0.qty` → `$.items[0].qty`; the server accepts only canonical addresses. */
export function toCanonical(path: string): string {
  const rooted = path.startsWith('$') ? path : `$.${path}`;
  return rooted.replace(/\.(\d+)(?=\.|\[|$)/g, '[$1]');
}

/**
 * Adds an edit to the inputs. A write to a path replaces any earlier write at or under it (setting a
 * whole list after editing one row must not let the old row edit win), and the new key goes last so
 * the server applies it after anything it overrides.
 */
export function mergeInputs(prev: Inputs, mutations: Record<string, unknown>): Inputs {
  const next: Inputs = { ...prev };
  for (const [raw, value] of Object.entries(mutations)) {
    const key = toCanonical(raw);
    for (const existing of Object.keys(next)) {
      if (existing === key || existing.startsWith(`${key}.`) || existing.startsWith(`${key}[`)) {
        delete next[existing];
      }
    }
    next[key] = value;
  }
  return next;
}

/** Field errors keyed by bound path, and the messages with no field to sit beside. */
export function splitIssues(issues: Issue[]): { fieldErrors: Record<string, string>; formErrors: string[] } {
  const fieldErrors: Record<string, string> = {};
  const formErrors: string[] = [];
  for (const i of issues) {
    const message = i.message || i.id;
    if (i.paths.length) for (const p of i.paths) fieldErrors[p] = message;
    else formErrors.push(message);
  }
  return { fieldErrors, formErrors };
}

/** The value at a canonical path in a state document (`$.a.b[0].c`). */
export function valueAt(state: unknown, path: string): unknown {
  const segments = path.replace(/^\$\.?/, '').match(/[^.[\]]+/g) ?? [];
  let node: unknown = state;
  for (const s of segments) {
    if (node == null || typeof node !== 'object') return undefined;
    node = (node as Record<string, unknown>)[s];
  }
  return node;
}

const MAX_SUMMARY_OUTPUTS = 20;

/**
 * What the assistant is told after each edit, so "why is VAT 62?" or "add a weekend surcharge" uses
 * the values on screen rather than the ones it first rendered with.
 */
export function summaryText(spec: CardSpec, inputs: Inputs, state: Record<string, unknown>, flags: Issue[]): string {
  const lines = [`The user edited the Valem card for ${spec.id}${spec.version ? ` ${spec.version}` : ''}.`];
  const entries = Object.entries(inputs);
  lines.push(entries.length
    ? `Inputs changed from the defaults: ${entries.map(([k, v]) => `${k} = ${JSON.stringify(v)}`).join(', ')}.`
    : 'All inputs are at their defaults.');
  const outputs = (spec.derivations ?? []).map(d => d.path).filter(p => !p.includes('[*]'));
  if (outputs.length) {
    lines.push('Current results:');
    for (const p of outputs.slice(0, MAX_SUMMARY_OUTPUTS)) lines.push(`- ${p} = ${JSON.stringify(valueAt(state, p) ?? null)}`);
    if (outputs.length > MAX_SUMMARY_OUTPUTS) lines.push(`- … and ${outputs.length - MAX_SUMMARY_OUTPUTS} more`);
  }
  if (flags.length) lines.push(`Flagged rules: ${flags.map(f => f.message || f.id).join('; ')}.`);
  lines.push('To change the model, call render_model with the revised spec and these inputs.');
  return lines.join('\n');
}

/**
 * A copy of the state with one canonical path set, creating objects and arrays along the way. Edits
 * show at once while the server recomputes; its answer then replaces the whole state.
 */
export function setAt(state: Record<string, unknown>, path: string, value: unknown): Record<string, unknown> {
  const segments = path.replace(/^\$\.?/, '').match(/[^.[\]]+/g) ?? [];
  if (!segments.length) return (value && typeof value === 'object' ? value : state) as Record<string, unknown>;
  const write = (node: unknown, i: number): unknown => {
    const key = segments[i];
    const isIndex = /^\d+$/.test(key);
    const copy: Record<string, unknown> | unknown[] = Array.isArray(node)
      ? [...node]
      : node && typeof node === 'object' ? { ...(node as Record<string, unknown>) } : isIndex ? [] : {};
    const child = (copy as Record<string, unknown>)[key];
    (copy as Record<string, unknown>)[key] = i === segments.length - 1 ? value : write(child, i + 1);
    return copy;
  };
  return write(state, 0) as Record<string, unknown>;
}

/** A tool result as the card reads it, from the host bridge or straight from the endpoint. */
export interface ToolResult {
  content?: { type: string; text?: string }[];
  structuredContent?: unknown;
  _meta?: Record<string, unknown>;
  isError?: boolean;
}

/** The share-link fragment format; matches ModelCardTools.SHARE_FORMAT on the server. */
export const SHARE_FORMAT = 'v1.';

/** What a share link carries. */
export interface SharedModel {
  spec: CardSpec;
  inputs: Inputs;
}

function toBase64Url(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(text: string): Uint8Array {
  const b64 = text.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, c => c.charCodeAt(0));
}

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const out = new Blob([bytes as Uint8Array<ArrayBuffer>]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

/** `base#v1.<base64url(deflate-raw(JSON))>`, the same encoding the server uses for render_model's link. */
export async function shareUrl(base: string, model: SharedModel): Promise<string> {
  const json = new TextEncoder().encode(JSON.stringify({ spec: model.spec, inputs: model.inputs }));
  return `${base}#${SHARE_FORMAT}${toBase64Url(await pipe(json, new CompressionStream('deflate-raw')))}`;
}

/** Reads a share-link fragment (with or without its `#`); null when it is not one. */
export async function readShare(fragment: string): Promise<SharedModel | null> {
  const body = fragment.replace(/^#/, '');
  if (!body.startsWith(SHARE_FORMAT)) return null;
  try {
    const json = await pipe(fromBase64Url(body.slice(SHARE_FORMAT.length)), new DecompressionStream('deflate-raw'));
    const parsed = JSON.parse(new TextDecoder().decode(json)) as Partial<SharedModel>;
    if (!parsed.spec || typeof parsed.spec !== 'object') return null;
    return { spec: parsed.spec, inputs: parsed.inputs ?? {} };
  } catch {
    return null;
  }
}
