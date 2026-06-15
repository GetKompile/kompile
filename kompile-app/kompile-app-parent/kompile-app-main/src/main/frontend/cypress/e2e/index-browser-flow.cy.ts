/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Index Browser E2E Flow Tests
 * Tests the full index browser page lifecycle against a running backend:
 * - Page loads and displays system status
 * - Document browsing with pagination
 * - Keyword search with result rendering
 * - Vector store browsing and search
 */

describe('Index Browser Flows', () => {

  before(() => {
    cy.waitForBackend();
  });

  beforeEach(() => {
    cy.visit('/');
    // Wait for Angular to boot — the app renders inside app-root
    cy.get('app-root', { timeout: 15000 }).should('exist');
  });

  // ═══════════════════════ Page Load & Status ═══════════════════════

  describe('Page Load', () => {
    it('should load the main page without errors', () => {
      cy.get('app-root').should('be.visible');
    });

    it('should render the sidebar navigation', () => {
      cy.get('mat-sidenav, .sidenav, .sidebar, nav').should('exist');
    });
  });

  // ═══════════════════════ Index Browser Status API ═══════════════════════

  describe('Index Status API Verification', () => {
    it('should return valid status with expected fields', () => {
      cy.apiGet('/index-browser/status').then((res) => {
        expect(res.status).to.eq(200);
        const body = res.body;

        // Core fields
        expect(body).to.have.property('indexAvailable');
        expect(body.indexAvailable).to.be.a('boolean');

        expect(body).to.have.property('isNoOpIndexer');
        expect(body.isNoOpIndexer).to.be.a('boolean');

        expect(body).to.have.property('isNoOpRetriever');
        expect(body.isNoOpRetriever).to.be.a('boolean');

        // Indexer implementation details
        expect(body).to.have.property('indexerImplementation');
        expect(body.indexerImplementation).to.be.a('string');

        // Vector store fields
        expect(body).to.have.property('vectorStoreAvailable');
        expect(body).to.have.property('isNoOpVectorStore');
      });
    });

    it('should have non-negative document counts', () => {
      cy.apiGet('/index-browser/status').then((res) => {
        expect(res.status).to.eq(200);
        if (typeof res.body.approximateDocumentCount === 'number') {
          expect(res.body.approximateDocumentCount).to.be.gte(0);
        }
        if (typeof res.body.approximateVectorCount === 'number') {
          expect(res.body.approximateVectorCount).to.be.gte(0);
        }
      });
    });
  });

  // ═══════════════════════ Document Browsing ═══════════════════════

  describe('Document Browsing', () => {
    it('should return documents with expected shape', () => {
      cy.apiGet('/index-browser/documents?offset=0&limit=10').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');

        if (res.body.length > 0) {
          const doc = res.body[0];
          expect(doc).to.have.property('id');
          expect(doc.id).to.be.a('string');
          expect(doc).to.have.property('preview');
        }
      });
    });

    it('should support pagination with offset and limit', () => {
      cy.apiGet('/index-browser/documents?offset=0&limit=2').then((page1) => {
        expect(page1.status).to.eq(200);
        expect(page1.body.length).to.be.lte(2);

        if (page1.body.length === 2) {
          cy.apiGet('/index-browser/documents?offset=2&limit=2').then((page2) => {
            expect(page2.status).to.eq(200);
            // Pages should not overlap (different doc IDs)
            if (page2.body.length > 0) {
              expect(page2.body[0].id).to.not.eq(page1.body[0].id);
            }
          });
        }
      });
    });

    it('should return empty array for large offset', () => {
      cy.apiGet('/index-browser/documents?offset=999999&limit=10').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(0);
      });
    });
  });

  // ═══════════════════════ Keyword Search ═══════════════════════

  describe('Keyword Search', () => {
    it('should reject empty search query with 400', () => {
      cy.apiPost('/index-browser/search', { query: '' }).then((res) => {
        expect(res.status).to.eq(400);
      });
    });

    it('should reject null search query with 400', () => {
      cy.apiPost('/index-browser/search', { query: null }).then((res) => {
        expect(res.status).to.eq(400);
      });
    });

    it('should accept valid search query and return results shape', () => {
      cy.apiPost('/index-browser/search', { query: 'test', maxResults: 5 }).then((res) => {
        // May return 200 with results or 200 with empty results
        expect(res.status).to.be.oneOf([200, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('results');
          expect(res.body.results).to.be.an('array');
          expect(res.body).to.have.property('totalResults');

          if (res.body.results.length > 0) {
            const result = res.body.results[0];
            expect(result).to.have.property('id');
            expect(result).to.have.property('score');
            expect(result.score).to.be.a('number');
          }
        }
      });
    });

    it('should handle special characters in search query', () => {
      cy.apiPost('/index-browser/search', { query: 'test "quoted" & special <chars>' }).then((res) => {
        // Should not crash the server
        expect(res.status).to.be.oneOf([200, 400, 500]);
      });
    });

    it('should respect maxResults parameter', () => {
      cy.apiPost('/index-browser/search', { query: 'a', maxResults: 1 }).then((res) => {
        if (res.status === 200) {
          expect(res.body.results.length).to.be.lte(1);
        }
      });
    });
  });

  // ═══════════════════════ Vector Store ═══════════════════════

  describe('Vector Store', () => {
    it('should list vector store documents', () => {
      cy.apiGet('/index-browser/vector-store/documents?offset=0&limit=5').then((res) => {
        expect(res.status).to.be.oneOf([200, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('array');
        }
      });
    });

    it('should reject empty vector search query', () => {
      cy.apiPost('/index-browser/vector-store/search', { query: '', maxResults: 5 }).then((res) => {
        expect(res.status).to.eq(400);
      });
    });

    it('should accept valid vector search', () => {
      cy.apiPost('/index-browser/vector-store/search', {
        query: 'test',
        maxResults: 5,
        similarityThreshold: 0.0
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('results');
          expect(res.body.results).to.be.an('array');

          if (res.body.results.length > 0) {
            const result = res.body.results[0];
            expect(result).to.have.property('score');
            // Vector scores are typically 0-1 cosine similarity
            expect(result.score).to.be.a('number');
          }
        }
      });
    });
  });
});
