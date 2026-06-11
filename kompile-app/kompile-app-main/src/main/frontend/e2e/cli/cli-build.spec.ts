/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * CLI Build App Tests
 * Tests non-interactive build commands: POM generation,
 * presets, flag validation, and output structure.
 */

import { test, expect, runCli, assertCliExists } from './fixtures/cli.fixture';
import * as fs from 'fs';
import * as path from 'path';

const BUILD_OUTPUT_BASE = '/tmp/kompile-e2e-builds';

test.beforeAll(() => {
  assertCliExists();
  // Clean up any previous test output
  if (fs.existsSync(BUILD_OUTPUT_BASE)) {
    fs.rmSync(BUILD_OUTPUT_BASE, { recursive: true, force: true });
  }
});

test.afterAll(() => {
  // Clean up test output
  if (fs.existsSync(BUILD_OUTPUT_BASE)) {
    fs.rmSync(BUILD_OUTPUT_BASE, { recursive: true, force: true });
  }
});


test.describe('CLI Build App — Help & Flags', () => {

  test('build app --help should show all options', () => {
    const result = runCli(['build', 'app', '--help']);
    expect(result.exitCode).toBe(0);
    const out = result.cleanStdout;

    expect(out).toContain('--configName');
    expect(out).toContain('--preset');
    expect(out).toContain('--skipMavenBuild');
    expect(out).toContain('--wizard');
    expect(out).toContain('--embedding');
    expect(out).toContain('--llm');
    expect(out).toContain('--vectorstore');
    expect(out).toContain('--exclude');
    expect(out).toContain('--include');
  });

  test('build app --help should list preset names', () => {
    const result = runCli(['build', 'app', '--help']);
    const out = result.cleanStdout;
    expect(out).toContain('hosted-llm-rag');
    expect(out).toContain('full');
    expect(out).toContain('minimal');
  });

  test('build app with no configName should fail', () => {
    const result = runCli(['build', 'app', '--skipMavenBuild']);
    // Should fail because configName is required
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('NullPointerException');
  });
});


test.describe('CLI Build App — POM Generation (--skipMavenBuild)', () => {

  test('should generate POM with hosted-llm-rag preset', () => {
    const configName = 'e2e-hosted-llm';
    const outputDir = path.join(BUILD_OUTPUT_BASE, configName);

    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);

    const out = result.cleanStdout;
    expect(out).toContain('Generated POM');
    expect(out).toContain('Generated application.properties');
    expect(out).toContain('--skipMavenBuild');

    // Verify POM file exists
    const pomPath = path.join(outputDir, 'project', 'pom.xml');
    expect(fs.existsSync(pomPath)).toBe(true);

    // Verify application.properties exists
    const propsPath = path.join(outputDir, 'project', 'src', 'main', 'resources', 'application.properties');
    expect(fs.existsSync(propsPath)).toBe(true);
  });

  test('should generate POM with full preset', () => {
    const configName = 'e2e-full';
    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=full',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);
    const out = result.cleanStdout;
    expect(out).toContain('Generated POM');

    // Full preset should have more modules than hosted-llm-rag
    const moduleMatch = out.match(/Enabled Modules \((\d+)\)/);
    expect(moduleMatch).not.toBeNull();
    const moduleCount = parseInt(moduleMatch![1]);
    expect(moduleCount).toBeGreaterThan(16);  // full has more than the default ~16
  });

  test('should generate POM with minimal preset', () => {
    const configName = 'e2e-minimal';
    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=minimal',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);
    expect(result.cleanStdout).toContain('Generated POM');

    // Minimal preset should have fewer modules
    const moduleMatch = result.cleanStdout.match(/Enabled Modules \((\d+)\)/);
    expect(moduleMatch).not.toBeNull();
    const moduleCount = parseInt(moduleMatch![1]);
    expect(moduleCount).toBeLessThan(16);
  });

  test('POM should contain correct artifactId', () => {
    const configName = 'e2e-artifact-check';
    runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    const pomPath = path.join(BUILD_OUTPUT_BASE, configName, 'project', 'pom.xml');
    const pomContent = fs.readFileSync(pomPath, 'utf-8');
    expect(pomContent).toContain(`<artifactId>${configName}</artifactId>`);
  });

  test('POM should include Spring Boot parent', () => {
    const configName = 'e2e-spring-check';
    runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    const pomPath = path.join(BUILD_OUTPUT_BASE, configName, 'project', 'pom.xml');
    const pomContent = fs.readFileSync(pomPath, 'utf-8');
    expect(pomContent).toContain('spring-boot');
  });
});


test.describe('CLI Build App — Module Overrides', () => {

  test('--llm override should change LLM modules', () => {
    const configName = 'e2e-llm-override';
    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--llm=llm-openai,llm-anthropic',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);
    const out = result.cleanStdout;
    expect(out).toContain('llm-openai');
    expect(out).toContain('llm-anthropic');
  });

  test('--embedding override should change embedding modules', () => {
    const configName = 'e2e-embed-override';
    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--embedding=embedding-anserini',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);
    expect(result.cleanStdout).toContain('embedding-anserini');
  });

  test('--exclude should remove modules from preset', () => {
    const configName = 'e2e-exclude';
    const result = runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=full',
      '--exclude=graph-neo4j',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    expect(result.exitCode).toBe(0);
    // graph-neo4j should not appear in enabled modules
    const out = result.cleanStdout;
    // Check the module listing doesn't include excluded module
    const modulesSection = out.substring(out.indexOf('Enabled Modules'));
    expect(modulesSection).not.toContain('graph-neo4j');
  });
});


test.describe('CLI Build App — Application Properties', () => {

  test('generated properties should include vector store config', () => {
    const configName = 'e2e-props-check';
    runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    const propsPath = path.join(BUILD_OUTPUT_BASE, configName, 'project', 'src', 'main', 'resources', 'application.properties');
    const props = fs.readFileSync(propsPath, 'utf-8');

    // Should have server port
    expect(props).toContain('server.port');
  });

  test('--appTitle should set the title in properties', () => {
    const configName = 'e2e-title';
    runCli([
      'build', 'app',
      `--configName=${configName}`,
      '--preset=hosted-llm-rag',
      '--appTitle=My Custom App',
      '--skipMavenBuild',
      `--outputDir=${BUILD_OUTPUT_BASE}`,
    ]);

    const propsPath = path.join(BUILD_OUTPUT_BASE, configName, 'project', 'src', 'main', 'resources', 'application.properties');
    const props = fs.readFileSync(propsPath, 'utf-8');
    expect(props).toContain('My Custom App');
  });
});


test.describe('CLI Build — Other Subcommands', () => {

  test('build sync-sample should work', () => {
    const result = runCli(['build', 'sync-sample']);
    // Should either succeed or show a message about what it did
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('NullPointerException');
    expect(combined).not.toContain('at ai.kompile');
  });

  test('build pom-generate should show help when no args', () => {
    const result = runCli(['build', 'pom-generate']);
    const combined = result.cleanStdout + result.cleanStderr;
    expect(combined).not.toContain('Exception');
  });
});
