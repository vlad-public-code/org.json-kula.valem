import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * Kept separate from `vite.config.ts` because that one is a library build: it carries a `build.lib`
 * entry and the `dts` plugin, neither of which a test run should pay for or be affected by.
 *
 * These tests cover the logic that lives *inside* components — chip de-duplication, step clamping
 * and rounding, number formatting, tab selection, the bind-wins rule — which the e2e suite can
 * only reach indirectly and `tsc` cannot see at all.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
