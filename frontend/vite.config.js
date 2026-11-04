import { defineConfig } from 'vite';
export default defineConfig({
  server: {proxy:{'/api':{target:process.env.ICI_API_URL || 'http://127.0.0.1:8081',changeOrigin:false}}},
});
