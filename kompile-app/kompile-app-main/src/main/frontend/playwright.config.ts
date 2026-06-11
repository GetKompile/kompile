/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: process.env['CI'] ? 1 : undefined,
  reporter: [
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
    ['list']
  ],
  timeout: 30_000,
  expect: {
    timeout: 10_000
  },
  use: {
    baseURL: process.env['BASE_URL'] || 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  /* Start the backend+frontend server before tests if not already running */
  webServer: process.env['NO_SERVER'] ? undefined : {
    command: 'echo "Expects kompile app running on port 8080"',
    url: 'http://localhost:8080',
    reuseExistingServer: true,
    timeout: 5_000,
  },
});
