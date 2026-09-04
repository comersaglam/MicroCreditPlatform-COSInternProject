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
    // NOT 5173, Vite's default, and the reason is a favicon.
    //
    // Chrome caches favicons per ORIGIN -- scheme+host+port -- not per file path. Another
    // project of this user's also runs on 5173, so its icon was still being painted on
    // this page even after ours was renamed and served correctly: same origin, cached
    // entry, no request made. Renaming the file could not fix that; only a different
    // origin can. 5174 is ours.
    port: 5174,
    // Fail loudly rather than sliding to 5175 if something else holds the port -- a panel
    // silently on another port is a demo spent typing URLs.
    strictPort: true,
    proxy: {
      '/admin': { target: 'http://localhost:4010', changeOrigin: true },
      '/health': { target: 'http://localhost:4010', changeOrigin: true },
    },
  },
});
