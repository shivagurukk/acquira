import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

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
          // MUI X DataGrid + date pickers are the single heaviest deps.
          if (id.includes('@mui/x-data-grid') || id.includes('@mui/x-date-pickers')) {
            return 'vendor-mui-x';
          }
          // MUI core + emotion styling engine.
          if (id.includes('@mui/') || id.includes('@emotion/')) {
            return 'vendor-mui';
          }
          // Animation lib — only some pages use it.
          if (id.includes('framer-motion')) {
            return 'vendor-motion';
          }
          // Drag-resize grid for Data Explorer only.
          if (id.includes('react-grid-layout') || id.includes('react-resizable') || id.includes('react-draggable')) {
            return 'vendor-grid';
          }
          // Icon sets — large but tree-shaken; group what survives.
          if (id.includes('lucide-react') || id.includes('@heroicons') || id.includes('@mui/icons-material')) {
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
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        secure: false
      }
    }
  }
})
