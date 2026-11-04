import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const __dirname = new URL('.', import.meta.url).pathname.replace(/\/$/, '');

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': __dirname + '/src',
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // The AI assistant is a separate service; if it is down only the assistant is affected, never the shop.
      '/ai-api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-api/, ''),
      },
    },
  },
});
