import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Dev proxy. Default: every /api call goes to ONE backend (acquira.role=all).
// Set PDF_PORT and/or BATCH_PORT to run the UI against the split pods locally;
// the prefixes mirror deploy/k8s/base/07-ingress.yaml. Vite matches proxy keys in
// insertion order, so the specific prefixes go in before the '/api' catch-all.
function buildProxy() {
  const entry = (port) => ({ target: `http://localhost:${port}`, changeOrigin: true, secure: false })
  const proxy = {}
  if (process.env.PDF_PORT) {
    for (const p of ['/api/business/insights']) proxy[p] = entry(process.env.PDF_PORT)
  }
  if (process.env.BATCH_PORT) {
    for (const p of ['/api/batch', '/api/upload', '/api/interchange', '/api/admin/backups',
      '/api/admin/integration', '/api/admin/interchange-normalization', '/api/admin/merchant-dedup',
      '/api/admin/migration', '/api/admin/partitions']) proxy[p] = entry(process.env.BATCH_PORT)
  }
  // BACKEND_PORT lets a second dev pair run beside the default 8081.
  proxy['/api'] = entry(process.env.BACKEND_PORT || 8081)
  return proxy
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  build: {
    // Raise warning ceiling so the intentional vendor chunks below
    // don't spam the build log; real bloat still shows in the report.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // Split heavy, stable third-party libs into their own long-lived
        // chunks. They change rarely, so a returning user re-downloads only
        // the app code on each deploy instead of one giant vendor blob.
        manualChunks(id) {
          if (!id.includes('node_modules')) return;

          // React core — almost every chunk depends on it; keep it isolated
          // and singular (avoids duplicate React copies / invalid-hook bugs).
          if (id.match(/[\\/]node_modules[\\/](react|react-dom|react-router|react-router-dom|scheduler)[\\/]/)) {
            return 'vendor-react';
          }
          // Recharts pulls in d3-* — large and only used by chart pages.
          if (id.match(/[\\/]node_modules[\\/](recharts|d3-|victory-|internmap)/)) {
            return 'vendor-charts';
          }
          // Animation lib — only some pages use it.
          if (id.includes('framer-motion')) {
            return 'vendor-motion';
          }
          // Drag-resize grid for Data Explorer only.
          if (id.includes('react-grid-layout') || id.includes('react-resizable') || id.includes('react-draggable')) {
            return 'vendor-grid';
          }
          // Icon set — large but tree-shaken; group what survives.
          if (id.includes('lucide-react')) {
            return 'vendor-icons';
          }
          // Everything else (axios, date-fns, clsx, headlessui, etc.)
          return 'vendor-misc';
        },
      },
    },
  },
  // #25: Vitest configuration
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    css: true,
    // Must exceed the 15s async-query timeout set in setup.js, or a slow cold
    // import is reported as a bare test timeout instead of the real assertion.
    testTimeout: 40000,
  },
  server: {
    // Honor an assigned port (e.g. from a preview harness) so two dev
    // servers can run side by side; defaults to Vite's usual 5173.
    port: Number(process.env.PORT) || 5173,
    proxy: buildProxy()
  }
})
