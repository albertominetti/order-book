import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const DEV_API_TARGET = 'http://localhost:8080'

export default defineConfig({
  base: '/app/',
  plugins: [vue()],
  build: {
    outDir: '../target/classes/static/app',
    emptyOutDir: true,
    sourcemap: false
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: DEV_API_TARGET,
        changeOrigin: true
      }
    }
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: DEV_API_TARGET,
        changeOrigin: true
      }
    }
  }
})
