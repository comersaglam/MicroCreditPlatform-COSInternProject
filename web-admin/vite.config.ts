import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend is published on 4010 (docker-compose maps it there because app-pos's debug
// build already points at that port). Proxying rather than calling it absolutely means the
// browser sees same-origin requests in development, so nothing here depends on the CORS
// config -- which is also why main.py's CORS has a test of its own rather than being
// assumed to work.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/admin': { target: 'http://localhost:4010', changeOrigin: true },
      '/health': { target: 'http://localhost:4010', changeOrigin: true },
    },
  },
});
