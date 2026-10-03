import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
    css: false,
    include: ['src/**/*.test.{js,jsx}'],
    // Must exceed the 15s async-query timeout set in setup.js, or a slow cold
    // import is reported as a bare test timeout instead of the real assertion.
    testTimeout: 40000,
  },
});
