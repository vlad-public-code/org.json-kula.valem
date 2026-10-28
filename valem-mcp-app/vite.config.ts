import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import path from 'path';

// One self-contained HTML file: the host serves it as an MCP App resource into a sandboxed iframe
// with no network, so every script and style has to be inlined. valem-mcp packages dist/model-card.html.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  resolve: {
    alias: {
      'valem-view-react': path.resolve(__dirname, '../valem-view-react/src/index.ts'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: path.resolve(__dirname, 'model-card.html'),
    },
  },
  test: {
    environment: 'jsdom',
  },
});
