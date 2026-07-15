// Browsers cannot set custom headers on a WebSocket handshake, so a deployment that requires a
// token for /models/{id}/subscribe (the sandbox's per-session token, or valem-web's `valem.api.key`
// when configured) must receive it as a `?token=` query param instead. REST calls get this via a
// fetch interceptor the host app installs (e.g. the sandbox's installFetchInterceptor), but that
// can't reach a raw `new WebSocket(...)` call — so components that open one call buildSubscribeUrl()
// here instead, and a host app that needs a token calls setWsTokenProvider() once at bootstrap.
let tokenProvider: (() => string | null | undefined) | null = null;

/** Registers a function returning the current auth token (or null/undefined if none is needed). */
export function setWsTokenProvider(provider: () => string | null | undefined): void {
  tokenProvider = provider;
}

/** Builds the `/models/{id}/subscribe` WebSocket URL, appending `?token=` when a provider is set. */
export function buildSubscribeUrl(modelId: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const base = `${protocol}://${window.location.host}/models/${modelId}/subscribe`;
  const token = tokenProvider?.();
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
}
