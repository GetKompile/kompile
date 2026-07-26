/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

import { defineConfig, devices } from '@playwright/test';
import { personaBaseUrl } from './e2e-endpoints';

/**
 * Browser e2e across the three persona apps.
 *
 * Each project pins its own `baseURL`, because `page.goto('/')` now means a different process per
 * persona: chat on :8081, the crawl manager on :8082, the admin console on :8080. Overrides come
 * from the same `KOMPILE_*_URL` environment variables the CLI reads, so one shell export points the
 * CLI, Cypress and Playwright at the same stack.
 *
 * `e2e/cli/**` is excluded here on purpose — those specs drive the CLI binary, not a browser, and
 * have their own config in `playwright-cli.config.ts`. Without the exclusion `testDir: './e2e'`
 * sweeps them into every browser project and runs them once per persona.
 */
export default defineConfig({
  testDir: './e2e',
  testIgnore: '**/cli/**',
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
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  projects: [
    {
      name: 'chat',
      testMatch: '**/chat/**/*.spec.ts',
      use: { ...devices['Desktop Chrome'], baseURL: personaBaseUrl('chat') },
    },
    {
      name: 'crawl',
      testMatch: '**/crawl/**/*.spec.ts',
      use: { ...devices['Desktop Chrome'], baseURL: personaBaseUrl('crawl') },
    },
    {
      name: 'admin',
      testMatch: '**/admin/**/*.spec.ts',
      use: { ...devices['Desktop Chrome'], baseURL: personaBaseUrl('admin') },
    },
  ],
  // No webServer: the three personas are separate processes and Playwright's webServer models one.
  // Bring the stack up with `kompile project serve` (or scripts/start-{app,chat,crawl-manager}.sh)
  // before running. The previous single-port echo command asserted only that :8080 answered, which
  // now says nothing about whether chat or the crawl manager are up.
});
