/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Playwright config for CLI (non-browser) tests.
 * These tests spawn the kompile-cli native binary and interact
 * with stdin/stdout — no browser or webServer is needed.
 */

import { defineConfig } from '@playwright/test';
import * as path from 'path';

export default defineConfig({
  testDir: './e2e/cli',
  fullyParallel: false,          // CLI tests share filesystem state; run sequentially
  forbidOnly: !!process.env['CI'],
  retries: 0,
  workers: 1,
  reporter: [
    ['html', { outputFolder: 'playwright-report-cli', open: 'never' }],
    ['list']
  ],
  timeout: 60_000,               // CLI commands can be slow (native image startup)
  expect: {
    timeout: 15_000
  },
  // No webServer — these are pure CLI process tests
});
