// ui/vite.config.ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    // NOTE: do NOT hand-split node_modules into manual vendor chunks here. Doing so
    // (e.g. a separate "react" chunk) breaks React's CJS->ESM interop across chunk
    // boundaries — a vendor module initializes before React resolves and throws
    // "can't access property 'PureComponent' of undefined". Vite's default chunking
    // is React-safe; the perf win comes from the per-page lazy imports in App.tsx
    // (recharts, monaco and each page load on demand), which keeps the initial
    // bundle small without the interop hazard.
    chunkSizeWarningLimit: 1200,
  },
  server: {
    proxy: {
      // Proxyfying API requests to dev backend server
      '/api': {
        target: 'https://192.168.50.1:8442',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})