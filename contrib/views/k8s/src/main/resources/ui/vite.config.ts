// ui/vite.config.ts
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  base: './',
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