/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Where the three kompile apps live, for Cypress and Playwright alike.
 *
 * `kompile-app-main` is the admin console; chat and crawl are separate processes on their own
 * ports, so there is no longer a single `http://localhost:8080` that answers every API. A spec
 * has to be pointed at the app that actually mounts the endpoint it exercises — `/api/ingest`
 * on :8080 is a 404, not a failure of the feature.
 *
 * The environment variables are deliberately the same ones the CLI reads
 * (`KompileService.environmentVariable()`), so one export in a shell configures the CLI, the
 * Cypress run and the Playwright run together.
 */

export type KompilePersona = 'admin' | 'chat' | 'crawl';

export const KOMPILE_PERSONAS: readonly KompilePersona[] = ['admin', 'chat', 'crawl'];

/** Built-in ports, matching `KompileService.defaultPort()`. */
const DEFAULT_PORT: Record<KompilePersona, number> = {
  admin: 8080,
  chat: 8081,
  crawl: 8082
};

/** Overrides, matching `KompileService.environmentVariable()`. */
const URL_ENV: Record<KompilePersona, string> = {
  admin: 'KOMPILE_APP_URL',
  chat: 'KOMPILE_CHAT_URL',
  crawl: 'KOMPILE_CRAWL_URL'
};

function trimTrailingSlashes(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

/** Base URL of one app: `$KOMPILE_<PERSONA>_URL`, else `http://localhost:<default port>`. */
export function personaBaseUrl(persona: KompilePersona): string {
  const configured = process.env[URL_ENV[persona]];
  return configured && configured.trim()
    ? trimTrailingSlashes(configured)
    : `http://localhost:${DEFAULT_PORT[persona]}`;
}

/** API root of one app, e.g. `http://localhost:8082/api`. */
export function personaApiUrl(persona: KompilePersona): string {
  return `${personaBaseUrl(persona)}/api`;
}

/**
 * The app under test, from `KOMPILE_PERSONA`. Defaults to the admin console because that is the
 * one app always installed — an unset persona then behaves like the pre-split single server.
 */
export function targetPersona(fallback: KompilePersona = 'admin'): KompilePersona {
  const requested = (process.env['KOMPILE_PERSONA'] || '').trim().toLowerCase();
  return (KOMPILE_PERSONAS as readonly string[]).includes(requested)
    ? (requested as KompilePersona)
    : fallback;
}

/**
 * Absolute API roots for every app, for the specs that legitimately cross a boundary — a health
 * sweep, or a flow that ingests on the crawl manager and then reads the result through a surface
 * only the admin console mounts.
 */
export function allPersonaApiUrls(): Record<string, string> {
  return {
    adminApi: personaApiUrl('admin'),
    chatApi: personaApiUrl('chat'),
    crawlApi: personaApiUrl('crawl'),
    adminUrl: personaBaseUrl('admin'),
    chatUrl: personaBaseUrl('chat'),
    crawlUrl: personaBaseUrl('crawl')
  };
}
