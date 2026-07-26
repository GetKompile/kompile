/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Navigation & App Shell E2E Tests
 *
 * All three SPAs render the same chrome — `AppShellComponent` in `kompile-ui-shared` — and differ
 * only in the `navItems` their AppComponent feeds it and whether the project explorer is on. So the
 * chrome assertions run identically under every persona, and the tab assertions are driven from the
 * table below, which mirrors `projects/<persona>-app/src/app/app.component.ts`.
 *
 * Routing is hash-based (`RouterModule.forRoot(routes, { useHash: true })`) in all three apps, so a
 * tab click moves the URL fragment; the shell is no longer the *ngIf swap it used to be.
 *
 * The last block is the UI mirror of the API boundary check in `api-health.cy.ts`: a persona must
 * not offer another persona's tabs. That is what catches a shared-library nav item leaking a
 * Developer or Enforcer entry into an end-user build, where the tab would render and then 404.
 */

interface PersonaTab {
  label: string;
  route: string;
  selector: string;
}

interface PersonaShell {
  /** Where `''` redirects to — the view a cold `cy.visit('/')` must land on. */
  home: string;
  /** Project browsing is an end-user surface; the admin console turns the explorer off. */
  projectExplorer: boolean;
  tabs: PersonaTab[];
}

const SHELL: Record<Cypress.KompilePersona, PersonaShell> = {
  chat: {
    home: '/chat',
    projectExplorer: true,
    tabs: [
      { label: 'Chat',        route: '/chat',        selector: 'app-unified-chat' },
      { label: 'Project',     route: '/project',     selector: 'app-project-page' },
      { label: 'Fact Sheets', route: '/fact-sheets', selector: 'app-fact-sheet-page' },
      { label: 'Graph',       route: '/graph',       selector: 'app-graph-page' }
    ]
  },
  crawl: {
    home: '/crawl',
    projectExplorer: true,
    tabs: [
      { label: 'Crawl',       route: '/crawl',       selector: 'app-unified-crawl' },
      { label: 'Fact Sheets', route: '/fact-sheets', selector: 'app-fact-sheet-page' },
      { label: 'Data',        route: '/data',        selector: 'app-tools-hub' },
      { label: 'Graph',       route: '/graph',       selector: 'app-graph-page' }
    ]
  },
  admin: {
    home: '/developer',
    projectExplorer: false,
    tabs: [
      { label: 'Fact Sheets', route: '/fact-sheets', selector: 'app-fact-sheet-page' },
      { label: 'Graph',       route: '/graph',       selector: 'app-graph-page' },
      { label: 'Developer',   route: '/developer',   selector: 'app-developer-hub' },
      { label: 'Agents',      route: '/agents',      selector: 'app-kclaw-hub' },
      { label: 'Enforcer',    route: '/enforcer',    selector: 'app-enforcer-hub' }
    ]
  }
};

const persona: Cypress.KompilePersona = Cypress.env('persona');
const shell = SHELL[persona];

/** Tabs that belong to some other persona and must not appear in this build. */
const foreignTabs = Object.entries(SHELL)
  .filter(([name]) => name !== persona)
  .flatMap(([, other]) => other.tabs.map(t => t.label))
  .filter(label => !shell.tabs.some(t => t.label === label));

describe(`Navigation & App Shell (${persona})`, () => {

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
      cy.get('nav[mat-tab-nav-bar] a').should('have.length', shell.tabs.length);
    });

    it('should render the content area and main panel', () => {
      cy.get('.content-with-explorer').should('exist');
      cy.get('main').should('exist');
    });

    it(`should ${shell.projectExplorer ? 'render' : 'omit'} the project explorer`, () => {
      cy.get('app-project-explorer').should(shell.projectExplorer ? 'exist' : 'not.exist');
    });

    it('should show all top-level tabs', () => {
      shell.tabs.forEach((t) => cy.topNav(t.label).should('be.visible'));
    });
  });

  // ═══════════════════════════ Default View ═══════════════════════════

  describe('Default View', () => {
    it(`should boot to ${shell.home}`, () => {
      const landing = shell.tabs.find(t => t.route === shell.home)!;
      cy.hash().should('eq', `#${shell.home}`);
      cy.get(`main ${landing.selector}`, { timeout: 10000 }).should('exist');
    });
  });

  // ═══════════════════════════ Tab Navigation ═══════════════════════════

  describe('Tab Navigation', () => {
    shell.tabs.forEach((t) => {
      it(`should navigate to ${t.label} and render ${t.selector}`, () => {
        cy.topNav(t.label).click();
        cy.hash().should('eq', `#${t.route}`);
        cy.get(`main ${t.selector}`, { timeout: 10000 }).should('exist');
      });
    });

    it('should render exactly one primary view at a time', () => {
      const [first, second] = shell.tabs;

      cy.topNav(second.label).click();
      cy.get(`main ${second.selector}`, { timeout: 10000 }).should('exist');
      cy.get(`main ${first.selector}`).should('not.exist');

      cy.topNav(first.label).click();
      cy.get(`main ${first.selector}`, { timeout: 10000 }).should('exist');
      cy.get(`main ${second.selector}`).should('not.exist');
    });
  });

  // ═══════════════════════════ Persona Boundary ═══════════════════════════

  describe('Persona boundary', () => {
    it(`should not offer another persona's tabs`, () => {
      foreignTabs.forEach((label) => {
        cy.contains('nav[mat-tab-nav-bar] a', label).should('not.exist');
      });
    });

    it('should not route to a surface this app does not serve', () => {
      // The wildcard route redirects unknown paths home rather than rendering a dead view, so an
      // admin deep-link handed to an end-user app lands on that app's own landing page.
      const foreignRoute = persona === 'admin' ? '/crawl' : '/developer';
      cy.visit(`/#${foreignRoute}`);
      cy.hash().should('eq', `#${shell.home}`);
    });
  });
});
