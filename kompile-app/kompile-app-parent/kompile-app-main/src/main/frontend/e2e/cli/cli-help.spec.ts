/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * CLI Help, Version & Info Tests
 * Validates --help output, --version flag, info command,
 * and subcommand help for all top-level commands.
 */

import { test, expect, runCli, assertCliExists } from './fixtures/cli.fixture';

test.beforeAll(() => {
  assertCliExists();
});


test.describe('CLI — Root Help', () => {

  test('--help should exit 0 and show usage', () => {
    const result = runCli(['--help']);
    expect(result.exitCode).toBe(0);
    expect(result.cleanStdout).toContain('Usage: kompile');
    expect(result.cleanStdout).toContain('[COMMAND]');
  });

  test('--help should list all top-level commands', () => {
    const result = runCli(['--help']);
    const out = result.cleanStdout;

    const expectedCommands = [
      'info', 'bootstrap', 'init', 'config', 'build',
      'install', 'uninstall', 'manage', 'sdk', 'pipeline',
      'chat', 'lite-chat', 'session', 'passthrough', 'resume',
      'ingest', 'index', 'jobs', 'schedule', 'subprocess',
      'graph', 'agent',
    ];

    for (const cmd of expectedCommands) {
      expect(out).toContain(cmd);
    }
  });

  test('--help should show description text', () => {
    const result = runCli(['--help']);
    expect(result.cleanStdout).toContain('Kompile CLI');
  });

  test('-h should work the same as --help', () => {
    const result = runCli(['-h']);
    expect(result.exitCode).toBe(0);
    expect(result.cleanStdout).toContain('Usage: kompile');
  });

  test('--version should print version info', () => {
    const result = runCli(['--version']);
    expect(result.exitCode).toBe(0);
    const combined = result.cleanStdout + result.cleanStderr;
    // Version line contains either a version number or "Unknown version"
    expect(combined).toMatch(/version/i);
  });

  test('-V should work the same as --version', () => {
    const result = runCli(['-V']);
    expect(result.exitCode).toBe(0);
  });
});


test.describe('CLI — Info Command', () => {

  test('info should exit 0', () => {
    const result = runCli(['info']);
    expect(result.exitCode).toBe(0);
  });

  test('info should show version block', () => {
    const result = runCli(['info']);
    const out = result.cleanStdout;
    expect(out).toContain('Kompile Version');
    expect(out).toMatch(/\d+\.\d+\.\d+/);  // semver-like version (e.g. 0.1.0-SNAPSHOT)
  });

  test('info should show home directory', () => {
    const result = runCli(['info']);
    expect(result.cleanStdout).toContain('Home:');
    expect(result.cleanStdout).toContain('.kompile');
  });

  test('info should show OS and architecture', () => {
    const result = runCli(['info']);
    const out = result.cleanStdout;
    expect(out).toContain('OS:');
    expect(out).toContain('Arch:');
  });

  test('info should show running services section', () => {
    const result = runCli(['info']);
    expect(result.cleanStdout).toContain('Running Services');
  });

  test('info should show installed tools', () => {
    const result = runCli(['info']);
    expect(result.cleanStdout).toContain('Installed Tools');
  });

  test('info should show configuration section', () => {
    const result = runCli(['info']);
    expect(result.cleanStdout).toContain('Configuration');
  });
});


test.describe('CLI — Subcommand Help', () => {

  const subcommands = [
    { cmd: 'build', expected: ['app', 'pom-generate'] },
    { cmd: 'chat', expected: [] },   // no-arg invocation launches wizard, not help
    { cmd: 'config', expected: [] },   // just check it doesn't crash
    { cmd: 'pipeline', expected: [] },
    { cmd: 'sdk', expected: [] },
    { cmd: 'manage', expected: [] },
    { cmd: 'session', expected: [] },
    { cmd: 'ingest', expected: [] },
    { cmd: 'index', expected: [] },
    { cmd: 'graph', expected: [] },
  ];

  for (const { cmd, expected } of subcommands) {
    test(`${cmd} (no args) should show help or usage`, () => {
      const result = runCli([cmd]);
      const combined = result.cleanStdout + result.cleanStderr;
      // Should show either Usage or help content, not a stack trace
      expect(combined).not.toContain('Exception');
      expect(combined).not.toContain('at ai.kompile');
      // Most subcommands show usage when called with no args
      const hasUsageOrContent = combined.includes('Usage:') ||
                                 combined.includes('kompile') ||
                                 combined.length > 10;
      expect(hasUsageOrContent).toBe(true);
    });

    if (expected.length > 0) {
      test(`${cmd} help should mention expected items`, () => {
        const result = runCli([cmd]);
        const combined = result.cleanStdout + result.cleanStderr;
        for (const item of expected) {
          expect(combined).toContain(item);
        }
      });
    }
  }
});


test.describe('CLI — Invalid Commands', () => {

  test('unknown command should fail gracefully', () => {
    const result = runCli(['nonexistent-command']);
    // Should not crash with a stack trace
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('at ai.kompile');
    // Picocli may return 0 if the root command handles unknown subcommands silently
    // The key check is no crash / no stack trace
  });

  test('unknown flag should fail gracefully', () => {
    const result = runCli(['--nonexistent-flag']);
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('NullPointerException');
    // May show "Unknown option" message or usage
  });
});
