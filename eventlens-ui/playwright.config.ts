import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: '../tests/e2e',
  use: {
    baseURL: 'http://127.0.0.1:9090',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    cwd: __dirname,
    reuseExistingServer: true,
    url: 'http://127.0.0.1:4173',
  },
});
