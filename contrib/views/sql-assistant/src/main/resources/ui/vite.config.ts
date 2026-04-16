import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'

export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    proxy: {
      '/api': {
        target: 'https://192.168.50.1:8442',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
