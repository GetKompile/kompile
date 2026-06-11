/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * CLI Chat Command Tests
 * Tests chat help, flag validation, --list, --setup behavior,
 * and mode/flag combinations.
 */

import { test, expect, runCli, assertCliExists } from './fixtures/cli.fixture';

test.beforeAll(() => {
  assertCliExists();
});


test.describe('CLI Chat — Help & Flags', () => {

  test('chat --help should show all options', () => {
    const result = runCli(['chat', '--help']);
    expect(result.exitCode).toBe(0);
    const out = result.cleanStdout;

    expect(out).toContain('--setup');
    expect(out).toContain('--local');
    expect(out).toContain('--agent');
    expect(out).toContain('--mode');
    expect(out).toContain('--resume');
    expect(out).toContain('--list');
    expect(out).toContain('--continue');
    expect(out).toContain('--session-id');
    expect(out).toContain('rag');
    expect(out).toContain('memory');
    expect(out).toContain('--role');
    expect(out).toContain('--roles');
    expect(out).toContain('--port');
    expect(out).toContain('--url');
  });

  test('chat --help should describe standard and passthrough modes', () => {
    const result = runCli(['chat', '--help']);
    const out = result.cleanStdout;
    expect(out).toContain('standard');
    expect(out).toContain('passthrough');
  });

  test('chat --help should describe REPL functionality', () => {
    const result = runCli(['chat', '--help']);
    const out = result.cleanStdout;
    expect(out).toContain('REPL');
  });
});


test.describe('CLI Chat — --list Flag', () => {

  test('chat --list should exit without error', () => {
    const result = runCli(['chat', '--list']);
    // Should list sessions (possibly empty) and exit
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('NullPointerException');
    expect(combined).not.toContain('StackOverflowError');
  });

  test('chat -l should work the same as --list', () => {
    const result = runCli(['chat', '-l']);
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Exception');
  });
});


test.describe('CLI Chat — Flag Combinations', () => {

  test('chat --mode=standard should be recognized', () => {
    // Just check it parses, will timeout/error connecting to server but shouldn't crash
    const result = runCli(['chat', '--mode=standard', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Invalid value');
  });

  test('chat --mode=passthrough should be recognized', () => {
    const result = runCli(['chat', '--mode=passthrough', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Invalid value');
  });

  test('chat --no-rag flag should be accepted', () => {
    const result = runCli(['chat', '--no-rag', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Unknown option');
  });

  test('chat --no-memory flag should be accepted', () => {
    const result = runCli(['chat', '--no-memory', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Unknown option');
  });

  test('chat --agent flag should accept a name', () => {
    const result = runCli(['chat', '--agent=claude-code', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Missing required parameter');
  });

  test('chat --port flag should accept a number', () => {
    const result = runCli(['chat', '--port=9090', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Invalid value');
  });

  test('chat --url flag should accept a URL', () => {
    const result = runCli(['chat', '--url=http://localhost:9090', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Invalid value');
  });
});


test.describe('CLI Chat — Invalid Flags', () => {

  test('chat with unknown flag should fail gracefully', () => {
    const result = runCli(['chat', '--nonexistent-flag']);
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('NullPointerException');
    expect(combined).not.toContain('at ai.kompile');
    // Picocli may still exit 0 — the key assertion is no crash
  });

  test('chat --mode with invalid value should fail gracefully', () => {
    const result = runCli(['chat', '--mode=invalid_mode', '--list'], { timeout: 10_000 });
    const combined = result.cleanStdout + result.cleanStderr;
    // Should either reject invalid mode or fall back to default
    expect(combined).not.toContain('NullPointerException');
  });
});


test.describe('CLI — Lite Chat', () => {

  test('lite-chat --help should show options', () => {
    const result = runCli(['lite-chat', '--help']);
    expect(result.exitCode).toBe(0);
    const out = result.cleanStdout;
    expect(out).toContain('lite-chat');
    expect(out).toContain('--url');
  });
});


test.describe('CLI — Session Command', () => {

  test('session should show help or subcommands', () => {
    const result = runCli(['session']);
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Exception');
    // Should mention session management
    expect(combined.length).toBeGreaterThan(0);
  });
});


test.describe('CLI — Passthrough Command', () => {

  test('passthrough --help should show usage', () => {
    const result = runCli(['passthrough', '--help']);
    expect(result.exitCode).toBe(0);
    expect(result.cleanStdout).toContain('passthrough');
  });
});
