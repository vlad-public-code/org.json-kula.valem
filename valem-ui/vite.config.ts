import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

/**
 * Where the dev server proxies API calls. Overridable so a second backend — a different build, a
 * different storage profile — can be driven without editing this file or stopping the one already
 * on 8080. That is also what lets the e2e suite target a specific server.
 */
const backend = process.env.VALEM_BACKEND ?? 'http://localhost:8080';

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
