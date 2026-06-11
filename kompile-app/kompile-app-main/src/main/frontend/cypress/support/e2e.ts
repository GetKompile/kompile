/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 */

// This export makes the file a module, required for `declare global` augmentation
export {};

// Type augmentation for custom commands — must come before usage
declare global {
  namespace Cypress {
    interface Chainable {
      waitForBackend(timeout?: number): Chainable<Response<any>>;
      apiGet(path: string): Chainable<Response<any>>;
      apiPost(path: string, body?: object): Chainable<Response<any>>;
      apiDelete(path: string): Chainable<Response<any>>;
    }
  }
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
