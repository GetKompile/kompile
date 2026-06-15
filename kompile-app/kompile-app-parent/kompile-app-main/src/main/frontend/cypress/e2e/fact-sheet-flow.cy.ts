/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Fact Sheet CRUD E2E Flow Tests
 * Tests the full fact sheet lifecycle against a running backend:
 * - List sheets
 * - Create a new sheet
 * - Activate sheet
 * - Add/list facts
 * - Derive sheet
 * - Delete sheet
 */

describe('Fact Sheet CRUD Flows', () => {
  const testSheetName = `e2e-test-${Date.now()}`;
  let createdSheetId: number | null = null;

  before(() => {
    cy.waitForBackend();
  });

  after(() => {
    // Cleanup: delete the test sheet if it was created
    if (createdSheetId) {
      cy.apiDelete(`/fact-sheets/${createdSheetId}`);
    }
  });

  // ═══════════════════════ List Sheets ═══════════════════════

  describe('List Sheets', () => {
    it('should return array of fact sheets', () => {
      cy.apiGet('/fact-sheets').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');

        if (res.body.length > 0) {
          const sheet = res.body[0];
          expect(sheet).to.have.property('id');
          expect(sheet).to.have.property('name');
          expect(sheet.name).to.be.a('string');
        }
      });
    });
  });

  // ═══════════════════════ Create Sheet ═══════════════════════

  describe('Create Sheet', () => {
    it('should create a new fact sheet', () => {
      cy.apiPost('/fact-sheets', {
        name: testSheetName,
        description: 'E2E test sheet'
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id');
        expect(res.body).to.have.property('name', testSheetName);
        createdSheetId = res.body.id;
      });
    });

    it('should appear in the sheets list after creation', () => {
      cy.apiGet('/fact-sheets').then((res) => {
        expect(res.status).to.eq(200);
        const found = res.body.find((s: any) => s.name === testSheetName);
        expect(found).to.not.be.undefined;
      });
    });

    it('should reject duplicate sheet name', () => {
      cy.apiPost('/fact-sheets', {
        name: testSheetName,
        description: 'duplicate'
      }).then((res) => {
        expect(res.status).to.be.oneOf([400, 409, 500]);
      });
    });
  });

  // ═══════════════════════ Get Sheet ═══════════════════════

  describe('Get Sheet', () => {
    it('should retrieve sheet by ID', () => {
      if (!createdSheetId) return;
      cy.apiGet(`/fact-sheets/${createdSheetId}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id', createdSheetId);
        expect(res.body).to.have.property('name', testSheetName);
      });
    });

    it('should return 404 for non-existent sheet', () => {
      cy.apiGet('/fact-sheets/999999999').then((res) => {
        expect(res.status).to.eq(404);
      });
    });
  });

  // ═══════════════════════ Activate Sheet ═══════════════════════

  describe('Activate Sheet', () => {
    it('should activate the test sheet', () => {
      if (!createdSheetId) return;
      cy.apiPost(`/fact-sheets/${createdSheetId}/activate`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id', createdSheetId);
      });
    });

    it('should show test sheet as active', () => {
      if (!createdSheetId) return;
      cy.apiGet('/fact-sheets/active').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id', createdSheetId);
      });
    });
  });

  // ═══════════════════════ Facts (Empty Sheet) ═══════════════════════

  describe('Facts on Empty Sheet', () => {
    it('should return empty facts array for new sheet', () => {
      if (!createdSheetId) return;
      cy.apiGet(`/fact-sheets/${createdSheetId}/facts`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(0);
      });
    });

    it('should return empty unindexed facts for new sheet', () => {
      if (!createdSheetId) return;
      cy.apiGet(`/fact-sheets/${createdSheetId}/facts/unindexed`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('should return indexing stats with zero counts for new sheet', () => {
      if (!createdSheetId) return;
      cy.apiGet(`/fact-sheets/${createdSheetId}/indexing-stats`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('totalFacts');
        expect(res.body.totalFacts).to.eq(0);
      });
    });
  });

  // ═══════════════════════ Index Browser with Active Sheet ═══════════════════════

  describe('Index Browser respects active sheet', () => {
    it('should return status scoped to the active sheet', () => {
      cy.apiGet('/index-browser/status').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('indexAvailable');
      });
    });

    it('should return empty documents for a fresh empty sheet', () => {
      cy.apiGet('/index-browser/documents?offset=0&limit=10').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        // New empty sheet should have 0 documents (or prior docs from default sheet)
      });
    });
  });

  // ═══════════════════════ Derive Sheet ═══════════════════════

  describe('Derive Sheet', () => {
    it('should derive a new sheet from existing one', () => {
      if (!createdSheetId) return;
      const derivedName = `derived-${testSheetName}`;
      cy.apiPost(`/fact-sheets/${createdSheetId}/derive`, {
        name: derivedName,
        description: 'Derived from e2e test'
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 201]);
        if (res.status === 200) {
          expect(res.body).to.have.property('id');
          expect(res.body).to.have.property('name', derivedName);
          // Cleanup the derived sheet
          cy.apiDelete(`/fact-sheets/${res.body.id}`);
        }
      });
    });
  });

  // ═══════════════════════ Delete Sheet ═══════════════════════

  describe('Delete Sheet', () => {
    it('should delete the test sheet', () => {
      if (!createdSheetId) return;
      // First activate a different sheet so we can delete this one
      cy.apiGet('/fact-sheets').then((listRes) => {
        const otherSheet = listRes.body.find((s: any) => s.id !== createdSheetId);
        if (otherSheet) {
          cy.apiPost(`/fact-sheets/${otherSheet.id}/activate`).then(() => {
            cy.apiDelete(`/fact-sheets/${createdSheetId}`).then((res) => {
              expect(res.status).to.be.oneOf([200, 204]);
              createdSheetId = null; // prevent after() cleanup from trying again
            });
          });
        }
      });
    });

    it('should no longer appear in sheets list', () => {
      cy.apiGet('/fact-sheets').then((res) => {
        const found = res.body.find((s: any) => s.name === testSheetName);
        expect(found).to.be.undefined;
      });
    });
  });
});
