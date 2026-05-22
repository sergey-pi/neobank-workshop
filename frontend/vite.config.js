import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Proxy keeps the browser same-origin and avoids CORS preflight in dev.
// Each path prefix routes to the appropriate microservice.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api/v1/users': 'http://localhost:8081',
      '/api/v1/accounts': 'http://localhost:8082',
      '/api/v1/transactions': 'http://localhost:8082',
      '/api/v1/payments': 'http://localhost:8083',
    },
  },
});
