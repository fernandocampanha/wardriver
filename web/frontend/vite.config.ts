import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// @ts-ignore
import { mockApi } from './mock/mockApi.mjs';

declare const process: { env: Record<string, string | undefined> };

export default defineConfig({

  plugins: [react(), ...(process.env.MOCK ? [mockApi()] : [])],
  build: { outDir: 'dist', sourcemap: false, target: 'es2022' },

  server: { proxy: { '/api': 'http://127.0.0.1:8080' } },
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'], css: false },
});
