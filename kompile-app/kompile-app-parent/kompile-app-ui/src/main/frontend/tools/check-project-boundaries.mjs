#!/usr/bin/env node
/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

/**
 * Enforces the persona boundary inside the Angular workspace.
 *
 * The whole point of the split is that an end-user bundle physically cannot contain an admin
 * panel. Angular resolves `imports:` arrays at build time, so a single stray import from
 * chat-app into admin-app would drag that component — and its transitive panel graph — straight
 * into the chat bundle. Nothing else catches that: the build succeeds, the tests pass, and the
 * only symptom is a bigger bundle that happens to ship the admin console.
 *
 * Three rules, checked against where files actually sit right now (not against any move
 * manifest, so it keeps working as the workspace evolves):
 *
 *   1. A relative import inside project P must resolve to a file inside project P.
 *   2. kompile-ui-shared must not import `@shared/...` — the library addresses itself with
 *      relative paths. Aliasing into yourself hides rule 1 from this check.
 *   3. Every `@shared/...` specifier must resolve to a file that exists.
 *   4. Every relative import must resolve to a file that exists.
 *
 * Rule 1 also covers the shared -> app direction, which is the dangerous one: it would mean a
 * component every persona loads pulling in one that only admin should have.
 *
 * Rule 4 exists because carving one project into four silently changes what `../../..` means. An
 * import that walked out of `src/app/` into a sibling `environments/` still points at a legal
 * in-project path afterwards — the directory just isn't there any more. Rule 1 waves that through
 * (right project, wrong file), so without rule 4 the first report is a build error.
 *
 * Usage: node tools/check-project-boundaries.mjs [--verbose]
 * Exits 1 on any violation.
 */

import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const FRONTEND = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PROJECTS = join(FRONTEND, 'projects');
const SHARED = 'kompile-ui-shared';
const SHARED_LIB = join(PROJECTS, SHARED, 'src', 'lib');
const VERBOSE = process.argv.includes('--verbose');

/** Matches `from './x'`, `import './x'`, and `import('./x')` — static and dynamic alike. */
const RELATIVE_IMPORT = /(?:from\s+|import\s+|import\(\s*)(['"])(\.[^'"]+)\1/g;
const SHARED_IMPORT = /(?:from\s+|import\s+|import\(\s*)(['"])(@shared\/[^'"]+)\1/g;

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'dist' || entry === '.angular') continue;
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, out);
    else if (entry.endsWith('.ts')) out.push(path);
  }
  return out;
}

/** Project a path belongs to, or null when it is outside projects/. */
function projectOf(path) {
  const rel = relative(PROJECTS, path);
  if (rel.startsWith('..') || rel.startsWith(sep)) return null;
  return rel.split(sep)[0];
}

/**
 * A TS module specifier may point at `x.ts` or at a directory holding `index.ts`. The extension-ful
 * form covers `import data from './x.json'` and the handful of `.js` shims.
 */
function resolves(base) {
  return existsSync(`${base}.ts`) || existsSync(join(base, 'index.ts')) || existsSync(`${base}.d.ts`) ||
    (existsSync(base) && statSync(base).isFile());
}

if (!existsSync(PROJECTS)) {
  console.error(`no projects/ directory under ${FRONTEND} — nothing to check`);
  process.exit(1);
}

const violations = [];
const dangling = [];
let files = 0;
let sharedImports = 0;
let relativeImports = 0;

for (const path of walk(PROJECTS)) {
  const project = projectOf(path);
  if (!project) continue;
  files++;
  const src = readFileSync(path, 'utf8');
  const shortPath = relative(FRONTEND, path);

  for (const [, , spec] of src.matchAll(RELATIVE_IMPORT)) {
    relativeImports++;
    const target = resolve(dirname(path), spec);
    const targetProject = projectOf(target);
    if (targetProject !== project) {
      violations.push(
        `${shortPath}\n      imports '${spec}'\n      -> ${targetProject ?? 'outside projects/'}` +
        ` (this file is in ${project}; use @shared/... for library code)`
      );
      continue;
    }
    if (!resolves(target)) {
      dangling.push(
        `${shortPath}\n      imports '${spec}'\n      -> no such file at ${relative(FRONTEND, target)}` +
        ` (in-project path, but nothing is there — did it stay behind in another project?)`
      );
    }
  }

  for (const [, , spec] of src.matchAll(SHARED_IMPORT)) {
    sharedImports++;
    if (project === SHARED) {
      violations.push(
        `${shortPath}\n      imports '${spec}'\n      -> ${SHARED} must address itself with relative paths, not @shared/...`
      );
      continue;
    }
    if (!resolves(join(SHARED_LIB, spec.slice('@shared/'.length)))) {
      dangling.push(`${shortPath}\n      imports '${spec}'\n      -> no such file under projects/${SHARED}/src/lib`);
    }
  }
}

if (VERBOSE) {
  console.log(`scanned ${files} .ts files across ${readdirSync(PROJECTS).length} projects`);
  console.log(`@shared/... specifiers: ${sharedImports}`);
  console.log(`relative specifiers: ${relativeImports}`);
}

for (const d of dangling) console.error(`  DANGLING  ${d}`);
for (const v of violations) console.error(`  BOUNDARY  ${v}`);

const failures = violations.length + dangling.length;
if (failures > 0) {
  console.error(
    `\nproject boundary check FAILED: ${violations.length} boundary, ${dangling.length} dangling.` +
    `\nSee docs/architecture/ui-persona-boundary.md for which project a component belongs to.`
  );
  process.exit(1);
}

console.log(
  `project boundary check OK — ${files} files, ${sharedImports} @shared + ${relativeImports} relative` +
  ` imports, 0 violations`
);
