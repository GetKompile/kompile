/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Navigation & Routing E2E Tests
 * Tests sidebar nav rail, URL routing, deep linking, nav collapse,
 * developer tools toggle, and active state indicators.
 */

describe('Navigation & Routing', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    cy.visit('/');
  });

  // ═══════════════════════════ App Shell Structure ═══════════════════════════

  describe('App Shell', () => {
    it('should render the nav rail sidebar', () => {
      cy.get('.nav-rail').should('exist').and('be.visible');
    });

    it('should render the brand logo', () => {
      cy.get('.nav-brand').should('exist');
      cy.get('.nav-brand-icon').should('contain.text', 'K');
    });

    it('should render the top bar with model status and KB selector', () => {
      cy.get('.top-bar').should('exist').and('be.visible');
      cy.get('app-model-status-indicator').should('exist');
      cy.get('.kb-selector').should('exist');
    });

    it('should render the main content area with router-outlet', () => {
      cy.get('.content-area').should('exist');
      cy.get('router-outlet').should('exist');
    });

    it('should show core nav items: Chat, Knowledge, Tools, Settings', () => {
      cy.get('.nav-items .nav-item').should('have.length.gte', 4);
      cy.get('.nav-items').contains('Chat');
      cy.get('.nav-items').contains('Knowledge');
      cy.get('.nav-items').contains('Tools');
      cy.get('.nav-items').contains('Settings');
    });
  });

  // ═══════════════════════════ Default Route ═══════════════════════════

  describe('Default Route', () => {
    it('should redirect to /#/chat on root visit', () => {
      cy.visit('/');
      cy.url().should('include', '#/chat');
    });

    it('should render the unified chat component on default route', () => {
      cy.get('app-unified-chat').should('exist');
    });

    it('should mark Chat nav item as active', () => {
      cy.get('.nav-item[href*="chat"]').should('have.class', 'active');
    });
  });

  // ═══════════════════════════ Navigation Links ═══════════════════════════

  describe('Nav Item Navigation', () => {
    it('should navigate to Knowledge (/#/knowledge) and render fact sheet manager', () => {
      cy.get('.nav-item').contains('Knowledge').click();
      cy.url().should('include', '#/knowledge');
      cy.get('app-fact-sheet-manager').should('exist');
      cy.get('.nav-item[href*="knowledge"]').should('have.class', 'active');
    });

    it('should navigate to Tools (/#/tools) and render tools hub', () => {
      cy.get('.nav-item').contains('Tools').click();
      cy.url().should('include', '#/tools');
      cy.get('app-tools-hub').should('exist');
      cy.get('.nav-item[href*="tools"]').should('have.class', 'active');
    });

    it('should navigate to Settings (/#/settings) and render settings', () => {
      cy.get('.nav-item').contains('Settings').click();
      cy.url().should('include', '#/settings');
      cy.get('app-settings').should('exist');
      cy.get('.nav-item[href*="settings"]').should('have.class', 'active');
    });

    it('should navigate back to Chat', () => {
      cy.get('.nav-item').contains('Tools').click();
      cy.url().should('include', '#/tools');

      cy.get('.nav-item').contains('Chat').click();
      cy.url().should('include', '#/chat');
      cy.get('app-unified-chat').should('exist');
    });

    it('should only have one active nav item at a time', () => {
      cy.get('.nav-item').contains('Knowledge').click();
      cy.get('.nav-item.active').should('have.length', 1);
      cy.get('.nav-item.active').should('contain.text', 'Knowledge');
    });
  });

  // ═══════════════════════════ Deep Linking ═══════════════════════════

  describe('Deep Linking', () => {
    it('should load Chat directly via /#/chat', () => {
      cy.visit('/#/chat');
      cy.get('app-unified-chat').should('exist');
      cy.get('.nav-item[href*="chat"]').should('have.class', 'active');
    });

    it('should load Knowledge directly via /#/knowledge', () => {
      cy.visit('/#/knowledge');
      cy.get('app-fact-sheet-manager').should('exist');
      cy.get('.nav-item[href*="knowledge"]').should('have.class', 'active');
    });

    it('should load Tools directly via /#/tools', () => {
      cy.visit('/#/tools');
      cy.get('app-tools-hub').should('exist');
    });

    it('should load Settings directly via /#/settings', () => {
      cy.visit('/#/settings');
      cy.get('app-settings').should('exist');
    });

    it('should redirect unknown routes to chat', () => {
      cy.visit('/#/nonexistent-page');
      cy.url().should('include', '#/chat');
      cy.get('app-unified-chat').should('exist');
    });
  });

  // ═══════════════════════════ Browser History ═══════════════════════════

  describe('Browser History', () => {
    it('should support browser back navigation', () => {
      cy.get('.nav-item').contains('Knowledge').click();
      cy.url().should('include', '#/knowledge');

      cy.get('.nav-item').contains('Tools').click();
      cy.url().should('include', '#/tools');

      cy.go('back');
      cy.url().should('include', '#/knowledge');
      cy.get('app-fact-sheet-manager').should('exist');
    });

    it('should support browser forward navigation', () => {
      cy.get('.nav-item').contains('Knowledge').click();
      cy.get('.nav-item').contains('Tools').click();
      cy.go('back');
      cy.url().should('include', '#/knowledge');

      cy.go('forward');
      cy.url().should('include', '#/tools');
      cy.get('app-tools-hub').should('exist');
    });
  });

  // ═══════════════════════════ Nav Collapse ═══════════════════════════

  describe('Nav Rail Collapse', () => {
    it('should collapse nav when collapse button is clicked', () => {
      cy.get('.nav-rail').should('not.have.class', 'collapsed');

      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-rail').should('have.class', 'collapsed');
    });

    it('should expand nav when collapse button is clicked again', () => {
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-rail').should('have.class', 'collapsed');

      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-rail').should('not.have.class', 'collapsed');
    });

    it('should hide nav labels when collapsed', () => {
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-label').should('not.exist');
    });

    it('should show nav labels when expanded', () => {
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-label').should('exist');
    });

    it('should still navigate correctly when collapsed', () => {
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-item[href*="knowledge"]').click();
      cy.url().should('include', '#/knowledge');
      cy.get('app-fact-sheet-manager').should('exist');
    });

    it('should persist collapse state across page reloads', () => {
      cy.get('.nav-collapse-btn').click();
      cy.get('.nav-rail').should('have.class', 'collapsed');

      cy.reload();
      cy.get('.nav-rail').should('have.class', 'collapsed');

      // Clean up: expand again
      cy.get('.nav-collapse-btn').click();
    });
  });

  // ═══════════════════════════ Developer Tools Toggle ═══════════════════════════

  describe('Developer Tools Toggle', () => {
    it('should not show Developer and KClaw nav items by default', () => {
      cy.get('.nav-item').contains('Developer').should('not.exist');
      cy.get('.nav-item').contains('KClaw').should('not.exist');
    });

    it('should show Developer and KClaw after clicking Show Dev button', () => {
      cy.get('.nav-footer').contains('Show Dev').click();
      cy.get('.nav-item').contains('Developer').should('exist');
      cy.get('.nav-item').contains('KClaw').should('exist');
    });

    it('should navigate to Developer page when dev tools enabled', () => {
      cy.get('.nav-footer').contains('Show Dev').click();
      cy.get('.nav-item').contains('Developer').click();
      cy.url().should('include', '#/developer');
      cy.get('app-developer-hub').should('exist');
    });

    it('should navigate to KClaw page when dev tools enabled', () => {
      cy.get('.nav-footer').contains('Show Dev').click();
      cy.get('.nav-item').contains('KClaw').click();
      cy.url().should('include', '#/kclaw');
      cy.get('app-kclaw-hub').should('exist');
    });

    it('should hide Developer and KClaw after clicking Hide Dev', () => {
      cy.get('.nav-footer').contains('Show Dev').click();
      cy.get('.nav-item').contains('Developer').should('exist');

      cy.get('.nav-footer').contains('Hide Dev').click();
      cy.get('.nav-item').contains('Developer').should('not.exist');
      cy.get('.nav-item').contains('KClaw').should('not.exist');
    });

    it('should persist developer tools state across reloads', () => {
      cy.get('.nav-footer').contains('Show Dev').click();
      cy.get('.nav-item').contains('Developer').should('exist');

      cy.reload();
      cy.get('.nav-item').contains('Developer').should('exist');

      // Clean up
      cy.get('.nav-footer').contains('Hide Dev').click();
    });
  });

  // ═══════════════════════════ Brand Click ═══════════════════════════

  describe('Brand Click', () => {
    it('should navigate to chat when brand logo is clicked', () => {
      cy.get('.nav-item').contains('Tools').click();
      cy.url().should('include', '#/tools');

      cy.get('.nav-brand').click();
      cy.url().should('include', '#/chat');
    });
  });
});
