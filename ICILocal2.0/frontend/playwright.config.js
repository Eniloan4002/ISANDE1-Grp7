import { defineConfig } from '@playwright/test';
const port = process.env.ICI_TEST_PORT || '5173';
export default defineConfig({
  testDir: './tests', testMatch: '**/*.spec.js',
  use: {
    baseURL:`http://127.0.0.1:${port}`,headless:true,
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {},
  },
  webServer: {command:`npm run dev -- --port ${port} --strictPort`,url:`http://127.0.0.1:${port}`,reuseExistingServer:false},
});
