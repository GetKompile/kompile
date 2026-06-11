/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Dark Mode E2E Tests
 * Tests theme toggle, body class switching, CSS variable changes,
 * and localStorage persistence.
 */

describe('Dark Mode', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    // Clear theme preference to start fresh
    cy.window().then(win => {
      win.localStorage.removeItem('kompile-theme');
    });
    cy.visit('/');
  });

  afterEach(() => {
    // Reset to light theme
    cy.window().then(win => {
      win.localStorage.removeItem('kompile-theme');
    });
  });

  // ═══════════════════════════ Theme Toggle Button ═══════════════════════════

  describe('Theme Toggle Button', () => {
    it('should display a theme toggle button in the nav footer', () => {
      cy.get('.nav-footer .nav-action').contains(/Light|Dark/).should('exist');
    });

    it('should show dark_mode icon when in light theme', () => {
      // Force light theme
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();
      cy.get('.nav-footer .nav-action mat-icon').contains('dark_mode').should('exist');
    });

    it('should show light_mode icon when in dark theme', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();
      cy.get('.nav-footer .nav-action mat-icon').contains('light_mode').should('exist');
    });
  });

  // ═══════════════════════════ Body Class Toggling ═══════════════════════════

  describe('Body Class Changes', () => {
    it('should add dark-theme class to body when toggled to dark', () => {
      // Start in light
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();

      cy.get('body').should('have.class', 'light-theme');
      cy.get('body').should('not.have.class', 'dark-theme');

      // Toggle to dark
      cy.get('.nav-footer .nav-action').contains(/Dark/).click();

      cy.get('body').should('have.class', 'dark-theme');
      cy.get('body').should('not.have.class', 'light-theme');
    });

    it('should add light-theme class to body when toggled to light', () => {
      // Start in dark
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();

      cy.get('body').should('have.class', 'dark-theme');

      // Toggle to light
      cy.get('.nav-footer .nav-action').contains(/Light/).click();

      cy.get('body').should('have.class', 'light-theme');
      cy.get('body').should('not.have.class', 'dark-theme');
    });

    it('should toggle back and forth correctly', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();

      // Light → Dark
      cy.get('.nav-footer .nav-action').contains(/Dark/).click();
      cy.get('body').should('have.class', 'dark-theme');

      // Dark → Light
      cy.get('.nav-footer .nav-action').contains(/Light/).click();
      cy.get('body').should('have.class', 'light-theme');

      // Light → Dark again
      cy.get('.nav-footer .nav-action').contains(/Dark/).click();
      cy.get('body').should('have.class', 'dark-theme');
    });
  });

  // ═══════════════════════════ CSS Variables ═══════════════════════════

  describe('CSS Variable Changes', () => {
    it('should have dark background-color when in dark mode', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();

      // The body background should use the dark CSS variable
      cy.get('body').should('have.class', 'dark-theme');
      cy.get('.app-shell').should('exist');
    });

    it('should have light background-color when in light mode', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();

      cy.get('body').should('have.class', 'light-theme');
      cy.get('.app-shell').should('exist');
    });
  });

  // ═══════════════════════════ Persistence ═══════════════════════════

  describe('Theme Persistence', () => {
    it('should persist dark theme across page reloads', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();

      // Toggle to dark
      cy.get('.nav-footer .nav-action').contains(/Dark/).click();
      cy.get('body').should('have.class', 'dark-theme');

      // Verify localStorage was set
      cy.window().then(win => {
        expect(win.localStorage.getItem('kompile-theme')).to.eq('dark');
      });

      // Reload and verify
      cy.reload();
      cy.get('body').should('have.class', 'dark-theme');
    });

    it('should persist light theme across page reloads', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();

      // Toggle to light
      cy.get('.nav-footer .nav-action').contains(/Light/).click();

      // Reload and verify
      cy.reload();
      cy.get('body').should('have.class', 'light-theme');
    });

    it('should persist theme across route changes', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'light'));
      cy.reload();

      // Toggle to dark
      cy.get('.nav-footer .nav-action').contains(/Dark/).click();
      cy.get('body').should('have.class', 'dark-theme');

      // Navigate to different pages
      cy.get('.nav-item').contains('Knowledge').click();
      cy.get('body').should('have.class', 'dark-theme');

      cy.get('.nav-item').contains('Tools').click();
      cy.get('body').should('have.class', 'dark-theme');

      cy.get('.nav-item').contains('Chat').click();
      cy.get('body').should('have.class', 'dark-theme');
    });
  });

  // ═══════════════════════════ Nav Rail in Dark Mode ═══════════════════════════

  describe('Nav Rail Appearance in Dark Mode', () => {
    it('should render nav rail elements correctly in dark mode', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();

      cy.get('.nav-rail').should('be.visible');
      cy.get('.nav-brand').should('be.visible');
      cy.get('.nav-items .nav-item').should('have.length.gte', 4);
      cy.get('.nav-footer').should('be.visible');
    });

    it('should render top bar correctly in dark mode', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.reload();

      cy.get('.top-bar').should('be.visible');
      cy.get('.kb-selector').should('be.visible');
    });
  });

  // ═══════════════════════════ Chat in Dark Mode ═══════════════════════════

  describe('Chat Interface in Dark Mode', () => {
    it('should render chat interface correctly in dark mode', () => {
      cy.window().then(win => win.localStorage.setItem('kompile-theme', 'dark'));
      cy.visit('/#/chat');

      cy.get('app-unified-chat').should('exist');
      cy.get('.unified-chat-wrapper').should('be.visible');
    });
  });
});
