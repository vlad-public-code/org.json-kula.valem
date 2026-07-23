import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

/**
 * Where the dev server proxies API calls. Overridable so a second backend — a different build, a
 * different storage profile — can be driven without editing this file or stopping the one already
 * on 8080. That is also what lets the e2e suite target a specific server.
 */
const backend = process.env.VALEM_BACKEND ?? 'http://localhost:8080';

/**
 * The `/models/{id}/subscribe` WebSocket handshake is same-origin-gated on the backend
 * (WebSocketConfig defaults to same-origin when `valem.websocket.allowed-origins` is unset). Behind
 * this dev proxy the browser's Origin stays `localhost:<uiPort>` while `changeOrigin` only rewrites
 * Host, so the upgrade reads as cross-origin and Spring rejects it — the live view then silently
 * never updates. Rewriting Origin to the backend on the WS upgrade makes it same-origin again.
 *
 * Dev-only: in production `valem-web` serves the UI and API from one origin, so there is no proxy
 * and no mismatch. Doing it here rather than requiring an allowed-origins flag on the backend keeps
 * `npm run dev` working against any backend out of the box.
 */
function rewriteWsOrigin(proxy: { on: (e: string, cb: (r: { setHeader: (k: string, v: string) => void }) => void) => void }) {
  proxy.on('proxyReqWs', proxyReq => proxyReq.setHeader('origin', backend));
}

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      'valem-view-react': path.resolve(__dirname, '../valem-view-react/src/index.ts'),
    },
  },
  server: {
    port: Number(process.env.VALEM_UI_PORT ?? 5173),
    proxy: {
      '/models': {
        target: backend,
        changeOrigin: true,
        ws: true,
        configure: rewriteWsOrigin,
      },
      '/blobs': {
        target: backend,
        changeOrigin: true,
      },
      '/llm': {
        target: backend,
        changeOrigin: true,
      },
    },
  },
});
