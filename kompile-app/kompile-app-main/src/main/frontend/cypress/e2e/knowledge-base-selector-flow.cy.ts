/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Knowledge Base Selector E2E Tests
 * Tests the KB selector in the top bar: listing, switching, creating,
 * and integration with the Knowledge page.
 */

describe('Knowledge Base Selector', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    cy.visit('/');
  });

  // ═══════════════════════════ Selector Presence ═══════════════════════════

  describe('KB Selector Display', () => {
    it('should display the KB selector in the top bar', () => {
      cy.get('.kb-selector').should('exist').and('be.visible');
    });

    it('should display the KB selector button with a name', () => {
      cy.get('.kb-selector-btn').should('exist');
      cy.get('.kb-name').should('exist');
    });

    it('should have an expand arrow icon', () => {
      cy.get('.kb-arrow').should('exist');
    });
  });

  // ═══════════════════════════ Dropdown Menu ═══════════════════════════

  describe('KB Dropdown Menu', () => {
    it('should open menu when selector button is clicked', () => {
      cy.get('.kb-selector-btn').click();
      cy.get('.kb-menu').should('exist');
    });

    it('should show "Switch Knowledge Base" header in menu', () => {
      cy.get('.kb-selector-btn').click();
      cy.get('.menu-header').should('contain.text', 'Switch Knowledge Base');
    });

    it('should show "New Knowledge Base" option in menu', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').should('exist');
    });

    it('should show existing knowledge bases from API', () => {
      // First ensure there's at least one KB via API
      cy.apiGet('/fact-sheets').then(res => {
        if (res.status === 200 && res.body.length > 0) {
          cy.get('.kb-selector-btn').click();
          // At least one KB should appear in the menu
          cy.get('.kb-menu button[mat-menu-item]').should('have.length.gte', 2); // KBs + "New" button
        }
      });
    });
  });

  // ═══════════════════════════ Create Knowledge Base ═══════════════════════════

  describe('Create Knowledge Base Dialog', () => {
    const testKbName = `E2E Test KB ${Date.now()}`;

    it('should open create dialog when "New Knowledge Base" is clicked', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();
      cy.get('.dialog-overlay').should('exist');
      cy.get('.dialog-content').should('contain.text', 'Create New Knowledge Base');
    });

    it('should have name input and description textarea', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();

      cy.get('.dialog-content input[matInput]').should('exist');
      cy.get('.dialog-content textarea[matInput]').should('exist');
    });

    it('should have Create button disabled when name is empty', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();

      cy.get('.dialog-actions button').contains('Create').should('be.disabled');
    });

    it('should close dialog on Cancel', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();
      cy.get('.dialog-overlay').should('exist');

      cy.get('.dialog-actions button').contains('Cancel').click();
      cy.get('.dialog-overlay').should('not.exist');
    });

    it('should close dialog when clicking outside', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();
      cy.get('.dialog-overlay').should('exist');

      // Click on the overlay (outside the dialog content)
      cy.get('.dialog-overlay').click('topLeft');
      cy.get('.dialog-overlay').should('not.exist');
    });

    it('should create a new knowledge base', () => {
      cy.get('.kb-selector-btn').click();
      cy.contains('New Knowledge Base').click();

      cy.get('.dialog-content input[matInput]').type(testKbName);
      cy.get('.dialog-content textarea[matInput]').type('Created by e2e test');
      cy.get('.dialog-actions button').contains('Create').should('not.be.disabled');
      cy.get('.dialog-actions button').contains('Create').click();

      // Dialog should close
      cy.get('.dialog-overlay').should('not.exist');

      // The new KB should appear in the selector (may take a moment)
      cy.get('.kb-name', { timeout: 5000 }).should('contain.text', testKbName);

      // Clean up: delete the test KB via API
      cy.apiGet('/fact-sheets').then(res => {
        if (res.status === 200) {
          const testSheet = res.body.find((s: any) => s.name === testKbName);
          if (testSheet) {
            cy.apiDelete(`/fact-sheets/${testSheet.id}`);
          }
        }
      });
    });
  });

  // ═══════════════════════════ Switch Knowledge Base ═══════════════════════════

  describe('Switch Knowledge Base', () => {
    it('should switch active KB when a different one is clicked', () => {
      cy.apiGet('/fact-sheets').then(res => {
        if (res.status === 200 && res.body.length >= 2) {
          const sheets = res.body;
          const currentName = sheets[0].name;

          cy.get('.kb-selector-btn').click();

          // Click a different KB
          cy.get('.kb-menu button[mat-menu-item]').contains(sheets[1].name).click();

          // The selector should now show the new KB name
          cy.get('.kb-name', { timeout: 5000 }).should('contain.text', sheets[1].name);
        } else {
          cy.log('Skipping: need at least 2 knowledge bases');
        }
      });
    });
  });

  // ═══════════════════════════ KB Selector Across Routes ═══════════════════════════

  describe('KB Selector Across Routes', () => {
    it('should be visible on every route', () => {
      // Chat
      cy.visit('/#/chat');
      cy.get('.kb-selector').should('be.visible');

      // Knowledge
      cy.visit('/#/knowledge');
      cy.get('.kb-selector').should('be.visible');

      // Tools
      cy.visit('/#/tools');
      cy.get('.kb-selector').should('be.visible');

      // Settings
      cy.visit('/#/settings');
      cy.get('.kb-selector').should('be.visible');
    });

    it('should show the same active KB on all routes', () => {
      cy.get('.kb-name').invoke('text').then(kbName => {
        const name = kbName.trim();

        cy.visit('/#/knowledge');
        cy.get('.kb-name').should('contain.text', name);

        cy.visit('/#/tools');
        cy.get('.kb-name').should('contain.text', name);
      });
    });
  });

  // ═══════════════════════════ API Integration ═══════════════════════════

  describe('Fact Sheets API Integration', () => {
    it('GET /api/fact-sheets should return array', () => {
      cy.apiGet('/fact-sheets').then(res => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/fact-sheets/active should return active sheet or 404', () => {
      cy.apiGet('/fact-sheets/active').then(res => {
        expect(res.status).to.be.oneOf([200, 404]);
        if (res.status === 200) {
          expect(res.body).to.have.property('id');
          expect(res.body).to.have.property('name');
        }
      });
    });

    it('should create and delete a knowledge base via API', () => {
      const name = `API Test KB ${Date.now()}`;

      cy.apiPost('/fact-sheets', { name, description: 'API test', color: '#1976d2', icon: 'folder' }).then(res => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id');
        expect(res.body.name).to.eq(name);

        const id = res.body.id;

        // Delete
        cy.apiDelete(`/fact-sheets/${id}`).then(delRes => {
          expect(delRes.status).to.be.oneOf([200, 204]);
        });

        // Verify gone
        cy.apiGet(`/fact-sheets/${id}`).then(getRes => {
          expect(getRes.status).to.eq(404);
        });
      });
    });
  });
});
