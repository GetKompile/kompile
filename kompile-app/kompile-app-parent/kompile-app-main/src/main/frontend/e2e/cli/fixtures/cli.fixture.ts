/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * CLI Test Fixture
 * Helpers for spawning the kompile-cli native binary,
 * reading stdout/stderr, writing to stdin, and asserting on output.
 */

import { test as base, expect } from '@playwright/test';
import { ChildProcess, spawn, spawnSync } from 'child_process';
import * as path from 'path';
import * as fs from 'fs';

/** Resolve the kompile-cli binary path relative to the repo root */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..', '..', '..', '..', '..');
const CLI_BINARY = path.join(REPO_ROOT, 'kompile-cli', 'target', 'kompile-cli');

/** Known benign startup warnings emitted by the native image */
const STARTUP_NOISE = [
  'CLI: No pipeline step schemas found',
  'CLI Transform: No step schemas found',
  'kompile-cli-versions.properties not found',
];

/**
 * Result from a synchronous CLI invocation.
 */
export interface CliResult {
  /** Combined stdout text */
  stdout: string;
  /** Combined stderr text */
  stderr: string;
  /** Process exit code */
  exitCode: number;
  /** stdout with ANSI codes stripped */
  cleanStdout: string;
  /** stderr with ANSI codes stripped */
  cleanStderr: string;
}

/** Strip ANSI escape sequences from text */
export function stripAnsi(text: string): string {
  // eslint-disable-next-line no-control-regex
  return text.replace(/\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])/g, '');
}

/** Remove known startup noise lines from output */
export function stripStartupNoise(text: string): string {
  return text
    .split('\n')
    .filter(line => !STARTUP_NOISE.some(noise => line.includes(noise)))
    .join('\n');
}

/**
 * Run the kompile CLI synchronously and return captured output.
 * Good for non-interactive commands (--help, info, build app --skipMavenBuild, etc.)
 */
export function runCli(args: string[], options?: {
  cwd?: string;
  timeout?: number;
  env?: Record<string, string>;
  input?: string;
}): CliResult {
  const cwd = options?.cwd ?? '/tmp';
  const timeout = options?.timeout ?? 30_000;

  const result = spawnSync(CLI_BINARY, args, {
    cwd,
    timeout,
    maxBuffer: 10 * 1024 * 1024,    // 10MB
    encoding: 'utf-8',
    env: {
      ...process.env,
      ...options?.env,
      // Disable jline's terminal detection for non-interactive runs
      TERM: 'dumb',
    },
    input: options?.input,
  });

  const stdout = result.stdout?.toString() ?? '';
  const stderr = result.stderr?.toString() ?? '';
  const exitCode = result.status ?? 1;

  return {
    stdout,
    stderr,
    exitCode,
    cleanStdout: stripAnsi(stripStartupNoise(stdout)),
    cleanStderr: stripAnsi(stripStartupNoise(stderr)),
  };
}

/**
 * Interactive CLI session — spawns the binary with piped stdin/stdout/stderr,
 * allowing tests to write input and wait for expected output patterns.
 */
export class CliSession {
  private proc: ChildProcess;
  private stdoutBuf = '';
  private stderrBuf = '';
  private closed = false;

  constructor(args: string[], options?: {
    cwd?: string;
    env?: Record<string, string>;
  }) {
    this.proc = spawn(CLI_BINARY, args, {
      cwd: options?.cwd ?? '/tmp',
      stdio: ['pipe', 'pipe', 'pipe'],
      env: {
        ...process.env,
        ...options?.env,
        TERM: 'dumb',
      },
    });

    this.proc.stdout!.on('data', (chunk: Buffer) => {
      this.stdoutBuf += chunk.toString();
    });

    this.proc.stderr!.on('data', (chunk: Buffer) => {
      this.stderrBuf += chunk.toString();
    });

    this.proc.on('close', () => {
      this.closed = true;
    });
  }

  /** All stdout accumulated so far */
  get stdout(): string { return this.stdoutBuf; }

  /** All stderr accumulated so far */
  get stderr(): string { return this.stderrBuf; }

  /** stdout with ANSI codes stripped */
  get cleanStdout(): string { return stripAnsi(this.stdoutBuf); }

  /** Whether the process has exited */
  get isClosed(): boolean { return this.closed; }

  /** The process exit code (null if still running) */
  get exitCode(): number | null { return this.proc.exitCode; }

  /**
   * Write a line to stdin (appends newline).
   */
  writeLine(text: string): void {
    if (this.closed) throw new Error('CLI session already closed');
    this.proc.stdin!.write(text + '\n');
  }

  /**
   * Write raw text to stdin (no newline appended).
   */
  write(text: string): void {
    if (this.closed) throw new Error('CLI session already closed');
    this.proc.stdin!.write(text);
  }

  /**
   * Wait until stdout contains the given pattern, or timeout.
   * Returns the full stdout at the time of match.
   */
  async waitForOutput(pattern: string | RegExp, timeoutMs = 15_000): Promise<string> {
    const start = Date.now();
    const check = typeof pattern === 'string'
      ? (text: string) => stripAnsi(text).includes(pattern)
      : (text: string) => pattern.test(stripAnsi(text));

    while (Date.now() - start < timeoutMs) {
      if (check(this.stdoutBuf)) {
        return this.stdoutBuf;
      }
      if (this.closed) {
        throw new Error(
          `CLI exited before matching "${pattern}". ` +
          `stdout: ${this.cleanStdout.slice(-500)}\nstderr: ${stripAnsi(this.stderrBuf).slice(-500)}`
        );
      }
      await sleep(50);
    }

    throw new Error(
      `Timeout waiting for "${pattern}" (${timeoutMs}ms). ` +
      `stdout so far: ${this.cleanStdout.slice(-1000)}`
    );
  }

  /**
   * Wait until stderr contains the given pattern.
   */
  async waitForStderr(pattern: string | RegExp, timeoutMs = 15_000): Promise<string> {
    const start = Date.now();
    const check = typeof pattern === 'string'
      ? (text: string) => stripAnsi(text).includes(pattern)
      : (text: string) => pattern.test(stripAnsi(text));

    while (Date.now() - start < timeoutMs) {
      if (check(this.stderrBuf)) return this.stderrBuf;
      if (this.closed) {
        throw new Error(
          `CLI exited before matching stderr "${pattern}". ` +
          `stderr: ${stripAnsi(this.stderrBuf).slice(-500)}`
        );
      }
      await sleep(50);
    }

    throw new Error(
      `Timeout waiting for stderr "${pattern}" (${timeoutMs}ms). ` +
      `stderr so far: ${stripAnsi(this.stderrBuf).slice(-1000)}`
    );
  }

  /**
   * Wait for the process to exit and return the exit code.
   */
  async waitForExit(timeoutMs = 30_000): Promise<number> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      if (this.closed) return this.proc.exitCode ?? -1;
      await sleep(50);
    }
    this.kill();
    throw new Error(`CLI did not exit within ${timeoutMs}ms`);
  }

  /**
   * Send Ctrl+C (SIGINT) to the process.
   */
  interrupt(): void {
    if (!this.closed) {
      this.proc.kill('SIGINT');
    }
  }

  /**
   * Kill the process.
   */
  kill(): void {
    if (!this.closed) {
      this.proc.kill('SIGKILL');
    }
  }

  /**
   * Close stdin (signals EOF).
   */
  closeStdin(): void {
    this.proc.stdin!.end();
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/** Verify the CLI binary exists before running any test */
export function assertCliExists(): void {
  if (!fs.existsSync(CLI_BINARY)) {
    throw new Error(
      `kompile-cli binary not found at ${CLI_BINARY}. ` +
      `Build it with: cd kompile-cli && mvn clean package -Pnative`
    );
  }
}

/** Export the binary path for reference in tests */
export const cliBinaryPath = CLI_BINARY;

/** Re-export Playwright test and expect */
export { base as test, expect };
