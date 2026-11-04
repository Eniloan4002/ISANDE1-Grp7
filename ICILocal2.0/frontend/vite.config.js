import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server:{host:'127.0.0.1',proxy:{'/api':{target:process.env.ICI_API_URL||'http://127.0.0.1:8081',changeOrigin:false}}},
  test: {
    include:['src/test/**/*.test.jsx'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
  },
});
