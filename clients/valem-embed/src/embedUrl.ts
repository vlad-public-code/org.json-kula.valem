// Pure helpers for composing the sandbox `/embed.html` URL from a spec or a pre-encoded reference.
// Kept DOM-free so they unit-test without a renderer. The wire format matches the sandbox's
// share.ts: `<fmt>.<blob>` where fmt `0` is plain (this package never compresses — a large spec
// should use the `embedRef` produced by the sandbox's Embed dialog, which does).

export type EmbedMode = 'interactive' | 'readonly';
export type EmbedTheme = 'light' | 'dark' | 'auto';

export interface EmbedUrlOptions {
  endpoint: string;
  mode: EmbedMode;
  theme: EmbedTheme;
  attribution: boolean;
  view?: string;
}

function base64url(bytes: Uint8Array): string {
  let bin = '';
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    bin += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  // btoa is a global in every evergreen browser and in Node 18+ (this package's floor).
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Encodes a spec object into a plain (fmt=0) share fragment. */
export function encodeSpec(spec: Record<string, unknown>): string {
  const name = typeof spec.id === 'string' && spec.id.trim() ? spec.id.trim() : 'model';
  const payload = { v: 1, mode: 'spec', name, spec };
  const utf8 = new TextEncoder().encode(JSON.stringify(payload));
  return `0.${base64url(utf8)}`;
}

/** Builds the full `/embed.html?…#e=<fragment>` URL. Non-default options only are serialized. */
export function buildEmbedSrc(fragment: string, o: EmbedUrlOptions): string {
  const params = new URLSearchParams();
  if (o.mode !== 'interactive') params.set('mode', o.mode);
  if (o.theme !== 'auto') params.set('theme', o.theme);
  if (!o.attribution) params.set('attribution', '0');
  if (o.view) params.set('view', o.view);
  const query = params.toString();
  const base = o.endpoint.replace(/\/$/, '');
  return `${base}/embed.html${query ? `?${query}` : ''}#e=${fragment}`;
}
