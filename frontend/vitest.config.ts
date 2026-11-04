import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config';

/** Unit/component tests run in jsdom against the same aliases and React plugin as the app. `npm run build` is unaffected. */
export default mergeConfig(viteConfig, defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    testTimeout: 30_000,        // typing into MUI inputs in jsdom is slow on a cold start
    restoreMocks: true,
  },
}));
