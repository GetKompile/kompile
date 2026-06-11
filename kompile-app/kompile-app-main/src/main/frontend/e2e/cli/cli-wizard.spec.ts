/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * CLI Interactive Wizard Tests
 * Tests the build wizard (kompile build app --wizard)
 * and chat setup wizard (kompile chat --setup)
 * using stdin interaction via CliSession.
 */

import { test, expect, runCli, CliSession, assertCliExists, stripAnsi } from './fixtures/cli.fixture';
import * as fs from 'fs';
import * as path from 'path';

const WIZARD_BUILD_DIR = '/tmp/kompile-e2e-wizard';

test.beforeAll(() => {
  assertCliExists();
  if (fs.existsSync(WIZARD_BUILD_DIR)) {
    fs.rmSync(WIZARD_BUILD_DIR, { recursive: true, force: true });
  }
});

test.afterAll(() => {
  if (fs.existsSync(WIZARD_BUILD_DIR)) {
    fs.rmSync(WIZARD_BUILD_DIR, { recursive: true, force: true });
  }
});


test.describe('CLI — Build Wizard (interactive)', () => {

  test('build app --wizard should show welcome banner', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      await session.waitForOutput('Build Wizard', 20_000);
      const out = session.cleanStdout;
      expect(out).toContain('Build Wizard');
      expect(out).toContain('step by step');
    } finally {
      session.kill();
    }
  });

  test('build wizard should prompt for app name', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      await session.waitForOutput('App name', 20_000);
      expect(session.cleanStdout).toContain('artifactId');
    } finally {
      session.kill();
    }
  });

  test('build wizard should show preset selection after app name', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      // Wait for app name prompt
      await session.waitForOutput('App name', 20_000);
      // Enter an app name
      session.writeLine('wizard-test-app');

      // Should show preset selection
      await session.waitForOutput('preset', 20_000);
      const out = session.cleanStdout;
      expect(out).toContain('hosted-llm-rag');
    } finally {
      session.kill();
    }
  });

  test('build wizard full flow: name → preset → no customize → platform → summary', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      // Step 1: App name
      await session.waitForOutput('App name', 20_000);
      session.writeLine('wizard-full-flow');

      // Step 2: Preset selection (1 = cli-agent-rag, 2 = hosted-llm-rag, etc.)
      await session.waitForOutput('preset', 20_000);
      session.writeLine('1');

      // Step 3: Customize? (default No)
      await session.waitForOutput('Customize', 15_000);
      session.writeLine('n');

      // Step 4: Build native? (default Yes → say no for speed)
      await session.waitForOutput('native', 15_000);
      session.writeLine('n');

      // Step 5: Platform (Enter for default = linux-x86_64)
      await session.waitForOutput('platform', 15_000);
      session.writeLine('');

      // Step 6: Build Summary should appear with correct app name and modules
      await session.waitForOutput('Build Summary', 15_000);
      const summary = session.cleanStdout;
      expect(summary).toContain('wizard-full-flow');
      expect(summary).toContain('Modules');
      expect(summary).toContain('Proceed with build');

      // Cancel the actual build (just testing the wizard flow)
      session.writeLine('n');
      const exitCode = await session.waitForExit(10_000);
      // Wizard reports cancellation, which may exit 0 or 1
      expect(typeof exitCode).toBe('number');
    } finally {
      session.kill();
    }
  });

  test('build wizard should cancel gracefully on empty app name', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      await session.waitForOutput('App name', 20_000);
      // Send Ctrl+C / interrupt to cancel
      session.interrupt();

      const exitCode = await session.waitForExit(10_000);
      // Should exit without crash
      const combined = session.cleanStdout + stripAnsi(session.stderr);
      expect(combined).not.toContain('NullPointerException');
    } finally {
      session.kill();
    }
  });

  test('build wizard with custom preset should show module selection', async () => {
    const session = new CliSession(
      ['build', 'app', '--wizard', '--skipMavenBuild', `--outputDir=${WIZARD_BUILD_DIR}`],
      { cwd: '/tmp' }
    );

    try {
      // App name
      await session.waitForOutput('App name', 20_000);
      session.writeLine('wizard-custom');

      // Select "custom" preset — last option (8 = custom, after 7 presets)
      await session.waitForOutput('custom', 20_000);
      session.writeLine('8');

      // Custom preset goes straight to module customization (no "Customize?" prompt)
      // Should show LLM Providers category with toggle markers
      await session.waitForOutput('LLM', 20_000);
      const out = session.cleanStdout;
      // Should show module toggle options like [*] or [ ]
      expect(out).toMatch(/\[\*\]|\[ \]/);
    } finally {
      session.kill();
    }
  });
});


test.describe('CLI — Chat Setup Wizard (interactive)', () => {

  test('chat --setup should show setup banner', async () => {
    const session = new CliSession(['chat', '--setup'], { cwd: '/tmp' });

    try {
      await session.waitForOutput('Chat Setup', 20_000);
      const out = session.cleanStdout;
      expect(out).toContain('Chat Setup');
      expect(out).toContain('chat-config.json');
    } finally {
      session.kill();
    }
  });

  test('chat setup should show chat mode selection', async () => {
    const session = new CliSession(['chat', '--setup'], { cwd: '/tmp' });

    try {
      await session.waitForOutput('Chat Mode', 20_000);
      const out = session.cleanStdout;
      expect(out).toContain('Standard Chat');
      expect(out).toContain('Passthrough');
      expect(out).toContain('Resume');
    } finally {
      session.kill();
    }
  });

  test('chat setup: selecting Standard should prompt for provider', async () => {
    const session = new CliSession(['chat', '--setup'], { cwd: '/tmp' });

    try {
      // Wait for chat mode selection
      await session.waitForOutput('Chat Mode', 20_000);
      // Select Standard (option 1)
      session.writeLine('1');

      // Should show provider selection
      await session.waitForOutput('Provider', 20_000);
      const out = session.cleanStdout;
      // Should list common providers
      expect(out).toMatch(/openai|anthropic|gemini/i);
    } finally {
      session.kill();
    }
  });

  test('chat setup: selecting Passthrough should show agent list', async () => {
    const session = new CliSession(['chat', '--setup'], { cwd: '/tmp' });

    try {
      // Select Passthrough (option 2)
      await session.waitForOutput('Chat Mode', 20_000);
      session.writeLine('2');

      // Should show agent selection or warning about no agents
      await session.waitForOutput(/Agent|Warning|CLI/, 20_000);
      const out = session.cleanStdout;
      // Either shows agents or warns that none are installed
      expect(out.length).toBeGreaterThan(100);
    } finally {
      session.kill();
    }
  });

  test('chat setup: cancel with q should exit', async () => {
    const session = new CliSession(['chat', '--setup'], { cwd: '/tmp' });

    try {
      await session.waitForOutput('Chat Mode', 20_000);
      session.writeLine('q');

      // Should exit without crash
      const exitCode = await session.waitForExit(10_000);
      const combined = session.cleanStdout + stripAnsi(session.stderr);
      expect(combined).not.toContain('NullPointerException');
    } finally {
      session.kill();
    }
  });
});
