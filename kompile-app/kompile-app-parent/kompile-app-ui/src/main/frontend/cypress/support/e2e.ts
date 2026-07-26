/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

// This export makes the file a module, required for `declare global` augmentation
export {};

// Type augmentation for custom commands — must come before usage
declare global {
  namespace Cypress {
    /** Which of the three kompile apps an API call is aimed at. */
    type KompilePersona = 'admin' | 'chat' | 'crawl';

    interface Chainable {
      waitForBackend(timeout?: number): Chainable<Response<any>>;
      apiGet(path: string): Chainable<Response<any>>;
      apiPost(path: string, body?: object): Chainable<Response<any>>;
      apiDelete(path: string): Chainable<Response<any>>;
      /** GET against a named app rather than the one under test. */
      apiGetFrom(persona: KompilePersona, path: string): Chainable<Response<any>>;
      /** POST against a named app rather than the one under test. */
      apiPostTo(persona: KompilePersona, path: string, body?: object): Chainable<Response<any>>;
      topNav(label: string): Chainable<JQuery<HTMLElement>>;
    }
  }
}

/**
 * API root of a named app. `cypress.config.ts` publishes one per persona so a spec can reach the
 * process that actually mounts an endpoint — the admin console no longer answers `/api/ingest`,
 * and the chat app no longer answers `/api/nd4j`.
 */
function apiRootFor(persona: Cypress.KompilePersona): string {
  const root = Cypress.env(`${persona}Api`);
  if (!root) {
    throw new Error(`No API root configured for persona '${persona}' — check cypress.config.ts`);
  }
  return root;
}

// Global before-each: suppress WebSocket/STOMP errors from the app
beforeEach(() => {
  cy.on('uncaught:exception', (err: Error) => {
    if (
      err.message.includes('WebSocket') ||
      err.message.includes('STOMP') ||
      err.message.includes('sockjs')
    ) {
      return false;
    }
    return true;
  });
});

// Custom command: wait for API to be reachable
Cypress.Commands.add('waitForBackend', (timeout = 30000) => {
  cy.request({
    url: `${Cypress.env('apiUrl')}/fact-sheets/active`,
    failOnStatusCode: false,
    timeout
  }).its('status').should('be.oneOf', [200, 404]);
});

// Custom command: API GET with base URL
Cypress.Commands.add('apiGet', (path: string) => {
  return cy.request({
    method: 'GET',
    url: `${Cypress.env('apiUrl')}${path}`,
    failOnStatusCode: false
  });
});

// Custom command: API POST with base URL
Cypress.Commands.add('apiPost', (path: string, body?: object) => {
  return cy.request({
    method: 'POST',
    url: `${Cypress.env('apiUrl')}${path}`,
    body,
    failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' }
  });
});

// Custom command: API DELETE with base URL
Cypress.Commands.add('apiDelete', (path: string) => {
  return cy.request({
    method: 'DELETE',
    url: `${Cypress.env('apiUrl')}${path}`,
    failOnStatusCode: false
  });
});

// Custom command: API GET against a named app (cross-persona checks)
Cypress.Commands.add('apiGetFrom', (persona: Cypress.KompilePersona, path: string) => {
  return cy.request({
    method: 'GET',
    url: `${apiRootFor(persona)}${path}`,
    failOnStatusCode: false
  });
});

// Custom command: API POST against a named app (cross-persona checks)
Cypress.Commands.add('apiPostTo', (persona: Cypress.KompilePersona, path: string, body?: object) => {
  return cy.request({
    method: 'POST',
    url: `${apiRootFor(persona)}${path}`,
    body,
    failOnStatusCode: false,
    headers: { 'Content-Type': 'application/json' }
  });
});

// Custom command: click/select a top-level Material nav tab by visible label
Cypress.Commands.add('topNav', (label: string) => {
  return cy.contains('nav[mat-tab-nav-bar] a', label);
});
