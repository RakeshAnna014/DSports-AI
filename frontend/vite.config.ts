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
    },
  },
});
