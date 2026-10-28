import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import path from 'path';

// One self-contained HTML file per page, every script and style inlined: the card is served as an
// MCP App resource into a sandboxed iframe with no network, and the share page is served as a single
// file too. vite-plugin-singlefile takes one input per build, so `--mode share` builds the second page.
// valem-mcp packages dist/model-card.html and dist/share.html.
export default defineConfig(({ mode }) => ({
  plugins: [react(), viteSingleFile()],
  resolve: {
    alias: {
      'valem-view-react': path.resolve(__dirname, '../valem-view-react/src/index.ts'),
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: mode !== 'share',
    rollupOptions: {
      input: path.resolve(__dirname, mode === 'share' ? 'share.html' : 'model-card.html'),
    },
  },
  test: {
    environment: 'jsdom',
  },
}));
