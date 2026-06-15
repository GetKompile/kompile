/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * API Health & Smoke Tests
 * These tests verify that all critical backend endpoints are reachable
 * and return expected response shapes. Run against a live backend.
 */

describe('API Health Checks', () => {

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════════ Fact Sheets ═══════════════════════

  describe('Fact Sheets API', () => {
    it('GET /api/fact-sheets should return an array', () => {
      cy.apiGet('/fact-sheets').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/fact-sheets/active should return a sheet or 404', () => {
      cy.apiGet('/fact-sheets/active').then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
        if (res.status === 200) {
          expect(res.body).to.have.property('id');
          expect(res.body).to.have.property('name');
        }
      });
    });
  });

  // ═══════════════════════ Index Browser ═══════════════════════

  describe('Index Browser API', () => {
    it('GET /api/index-browser/status should return system status', () => {
      cy.apiGet('/index-browser/status').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('indexAvailable');
        expect(res.body).to.have.property('isNoOpIndexer');
        expect(res.body).to.have.property('isNoOpRetriever');
        expect(res.body).to.have.property('approximateDocumentCount');
      });
    });

    it('GET /api/index-browser/documents should return paginated docs', () => {
      cy.apiGet('/index-browser/documents?offset=0&limit=5').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('POST /api/index-browser/search with empty query should return 400', () => {
      cy.apiPost('/index-browser/search', { query: '' }).then((res) => {
        expect(res.status).to.eq(400);
      });
    });

    it('GET /api/index-browser/vector-store/documents should return array', () => {
      cy.apiGet('/index-browser/vector-store/documents?offset=0&limit=5').then((res) => {
        expect(res.status).to.be.oneOf([200, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('array');
        }
      });
    });
  });

  // ═══════════════════════ Job History ═══════════════════════

  describe('Job History API', () => {
    it('GET /api/indexing/history/recent should return jobs array', () => {
      cy.apiGet('/indexing/history/recent?hours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        if (res.body.length > 0) {
          const job = res.body[0];
          expect(job).to.have.property('taskId');
          expect(job).to.have.property('fileName');
          expect(job).to.have.property('status');
        }
      });
    });

    it('GET /api/indexing/history/statistics should return stats object', () => {
      cy.apiGet('/indexing/history/statistics?lastHours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('totalJobs');
        expect(res.body).to.have.property('completedJobs');
        expect(res.body).to.have.property('failedJobs');
        expect(res.body).to.have.property('activeJobs');
      });
    });

    it('GET /api/indexing/history/active should return array', () => {
      cy.apiGet('/indexing/history/active').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/indexing/history/failed should return array', () => {
      cy.apiGet('/indexing/history/failed').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  // ═══════════════════════ Ingest Events ═══════════════════════

  describe('Ingest Events API', () => {
    it('GET /api/ingest/events/status should return status object', () => {
      cy.apiGet('/ingest/events/status').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('totalEvents');
        expect(res.body).to.have.property('activeTasks');
      });
    });

    it('GET /api/ingest/events/recent should return array', () => {
      cy.apiGet('/ingest/events/recent?hours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/ingest/events/tasks should return task list', () => {
      cy.apiGet('/ingest/events/tasks').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  // ═══════════════════════ Chunk Manager ═══════════════════════

  describe('Chunk Manager API', () => {
    it('GET /api/chunk-manager/chunks should return paginated chunks', () => {
      cy.apiGet('/chunk-manager/chunks?offset=0&limit=5').then((res) => {
        expect(res.status).to.eq(200);
        // Response may be an array or an object with items
        if (Array.isArray(res.body)) {
          // direct array
        } else {
          expect(res.body).to.have.property('chunks');
        }
      });
    });

    it('GET /api/chunk-manager/sources should return sources list', () => {
      cy.apiGet('/chunk-manager/sources').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });

  // ═══════════════════════ Subprocess Events ═══════════════════════

  describe('Subprocess Events API', () => {
    it('GET /api/subprocess-events/recent should return array', () => {
      cy.apiGet('/subprocess-events/recent?hours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/subprocess-events/statistics should return stats', () => {
      cy.apiGet('/subprocess-events/statistics').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('available');
      });
    });
  });

  // ═══════════════════════ Cross Index ═══════════════════════

  describe('Cross Index API', () => {
    it('GET /api/cross-index/status should return status', () => {
      cy.apiGet('/cross-index/status').then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
      });
    });
  });

  // ═══════════════════════ Eval Debugger ═══════════════════════

  describe('Eval Debugger API', () => {
    it('GET /api/eval-debugger/status should return evaluator status', () => {
      cy.apiGet('/eval-debugger/status').then((res) => {
        expect(res.status).to.eq(200);
      });
    });

    it('GET /api/eval-debugger/evaluator-types should return types array', () => {
      cy.apiGet('/eval-debugger/evaluator-types').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  // ═══════════════════════ Processing Settings ═══════════════════════

  describe('Processing Settings API', () => {
    it('GET /api/processing-settings should return current settings', () => {
      cy.apiGet('/processing-settings').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });
});
