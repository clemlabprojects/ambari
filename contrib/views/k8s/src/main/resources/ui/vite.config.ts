// ui/vite.config.ts
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react-swc'
import fs from 'node:fs'
import path from 'node:path'

// The code editor (Monaco) is shipped with the view, from the pinned monaco-editor package, instead of being fetched
// from a CDN at run time (see src/monacoLocal.ts): its AMD build is copied to dist/monaco/vs, and served from
// node_modules by the dev server.
const MONACO_VS = path.resolve(__dirname, 'node_modules/monaco-editor/min/vs')

function localMonaco(): Plugin {
  return {
    name: 'kdps-local-monaco',
    configureServer(server) {
      server.middlewares.use('/monaco/vs', (req, res, next) => {
        const file = path.join(MONACO_VS, decodeURIComponent((req.url || '').split('?')[0]))
        if (!file.startsWith(MONACO_VS + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) return next()
        res.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : 'application/javascript')
        fs.createReadStream(file).pipe(res)
      })
    },
    writeBundle(options) {
      const outDir = options.dir || path.resolve(__dirname, 'dist')
      fs.cpSync(MONACO_VS, path.join(outDir, 'monaco/vs'), { recursive: true })
    },
  }
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react(), localMonaco()],
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