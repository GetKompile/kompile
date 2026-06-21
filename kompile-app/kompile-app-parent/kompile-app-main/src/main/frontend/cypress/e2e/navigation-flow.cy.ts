/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Navigation & App Shell E2E Tests
 *
 * The shell is a top Material tab bar (`nav[mat-tab-nav-bar]`) that drives `activeTab`
 * state on AppComponent. There is NO Angular router / URL routing / deep-linking — clicking
 * a tab swaps the component rendered inside <main> via *ngIf (it does not change the URL hash).
 * All top-level tabs are always visible.
 *
 * NOTE: the previous version of this spec targeted an older shell (collapsible `.nav-rail`
 * sidebar, `router-outlet`, `#/...` deep links, a "Show Dev" toggle). None of that exists in
 * the current shell, so those tests were removed rather than re-selectored.
 */

const TABS: { label: string; selector: string }[] = [
  { label: 'Chat', selector: 'app-unified-chat' },
  { label: 'Project', selector: 'app-project-manager' },
  { label: 'Project Store', selector: 'app-project-store-panel' },
  { label: 'Fact Sheets', selector: 'app-fact-sheet-manager' },
  { label: 'Data', selector: 'app-tools-hub' },
  { label: 'Developer', selector: 'app-developer-hub' },
  { label: 'KClaw', selector: 'app-kclaw-hub' },
];

describe('Navigation & App Shell', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    cy.visit('/');
    cy.get('app-root', { timeout: 15000 }).should('exist');
  });

  // ═══════════════════════════ App Shell Structure ═══════════════════════════

  describe('App Shell', () => {
    it('should render the header with branding, model status, fact sheet selector, theme toggle', () => {
      cy.get('.app-container').should('exist');
      cy.get('header').should('exist').and('be.visible');
      cy.get('app-branding').should('exist');
      cy.get('app-model-status-indicator').should('exist');
      cy.get('.fact-sheet-selector').should('exist');
      cy.get('.theme-toggle-btn').should('exist');
    });

    it('should render the top Material tab navigation bar', () => {
      cy.get('nav[mat-tab-nav-bar]').should('exist').and('be.visible');
      cy.get('nav[mat-tab-nav-bar] a').should('have.length.gte', TABS.length);
    });

    it('should render the content area with project explorer and main panel', () => {
      cy.get('.content-with-explorer').should('exist');
      cy.get('app-project-explorer').should('exist');
      cy.get('main').should('exist');
    });

    it('should render the footer', () => {
      cy.get('footer').should('exist');
    });

    it('should show all top-level tabs', () => {
      TABS.forEach((t) => cy.topNav(t.label).should('be.visible'));
    });
  });

  // ═══════════════════════════ Default View ═══════════════════════════

  describe('Default View', () => {
    it('should boot to the Chat tab and render the unified chat component', () => {
      cy.get('main app-unified-chat').should('exist');
    });
  });

  // ═══════════════════════════ Tab Navigation ═══════════════════════════

  describe('Tab Navigation', () => {
    TABS.forEach((t) => {
      it(`should navigate to ${t.label} and render ${t.selector}`, () => {
        cy.topNav(t.label).click();
        cy.get(`main ${t.selector}`, { timeout: 10000 }).should('exist');
      });
    });

    it('should swap the rendered component when switching tabs', () => {
      cy.topNav('Data').click();
      cy.get('main app-tools-hub').should('exist');
      cy.get('main app-unified-chat').should('not.exist');

      cy.topNav('Chat').click();
      cy.get('main app-unified-chat').should('exist');
      cy.get('main app-tools-hub').should('not.exist');
    });

    it('should render exactly one primary view at a time', () => {
      cy.topNav('Fact Sheets').click();
      cy.get('main app-fact-sheet-manager').should('exist');
      cy.get('main app-unified-chat').should('not.exist');
      cy.get('main app-tools-hub').should('not.exist');
    });
  });
});
