import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  retries: 0,
  use: {
    baseURL: 'http://localhost:4200',
    headless: true,
    navigationTimeout: 30_000,
    viewport: { width: 1280, height: 800 },
  },
});
