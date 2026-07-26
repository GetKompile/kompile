/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * API Health & Smoke Tests
 *
 * Verifies that the critical backend endpoints are reachable and return the expected response
 * shapes. Run against a live stack.
 *
 * Each family is asked of the app that mounts it: the shared surface via the app under test, the
 * crawl/ingest/indexing families of the crawl manager, and the admin-only families of the admin
 * console. The final section asserts the split from the other side — an end-user app must NOT
 * answer an admin API, and the admin console must NOT answer a crawl API. That direction is the
 * load-bearing one: without Spring Security, process separation is the only isolation there is,
 * and a stray `scanBasePackages` widening would silently undo it while every positive test above
 * still passed.
 */

describe('API Health Checks', () => {

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════════ Shared surface (every app) ═══════════════════════

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

    it('should serve fact sheets from every app, not just one', () => {
      // web-shared is a dependency of all three Boot apps. If this starts failing for one
      // persona, that app dropped the shared web module and its SPA lost project context.
      (['admin', 'chat', 'crawl'] as Cypress.KompilePersona[]).forEach((persona) => {
        cy.apiGetFrom(persona, '/fact-sheets').then((res) => {
          expect(res.status, `${persona} /api/fact-sheets`).to.eq(200);
        });
      });
    });
  });

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

  // ═══════════════════════ Crawl manager (:8082) ═══════════════════════

  describe('Job History API', () => {
    it('GET /api/indexing/history/recent should return jobs array', () => {
      cy.apiGetFrom('crawl', '/indexing/history/recent?hours=24').then((res) => {
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
      cy.apiGetFrom('crawl', '/indexing/history/statistics?lastHours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('totalJobs');
        expect(res.body).to.have.property('completedJobs');
        expect(res.body).to.have.property('failedJobs');
        expect(res.body).to.have.property('activeJobs');
      });
    });

    it('GET /api/indexing/history/active should return array', () => {
      cy.apiGetFrom('crawl', '/indexing/history/active').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/indexing/history/failed should return array', () => {
      cy.apiGetFrom('crawl', '/indexing/history/failed').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  describe('Ingest Events API', () => {
    it('GET /api/ingest/events/status should return status object', () => {
      cy.apiGetFrom('crawl', '/ingest/events/status').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('totalEvents');
        expect(res.body).to.have.property('activeTasks');
      });
    });

    it('GET /api/ingest/events/recent should return array', () => {
      cy.apiGetFrom('crawl', '/ingest/events/recent?hours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/ingest/events/tasks should return task list', () => {
      cy.apiGetFrom('crawl', '/ingest/events/tasks').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  describe('Chunk Manager API', () => {
    it('GET /api/chunk-manager/chunks should return paginated chunks', () => {
      cy.apiGetFrom('crawl', '/chunk-manager/chunks?offset=0&limit=5').then((res) => {
        expect(res.status).to.eq(200);
        // Response may be an array or an object with items
        if (!Array.isArray(res.body)) {
          expect(res.body).to.have.property('chunks');
        }
      });
    });

    it('GET /api/chunk-manager/sources should return sources list', () => {
      cy.apiGetFrom('crawl', '/chunk-manager/sources').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });

  describe('Cross Index API', () => {
    it('GET /api/cross-index/status should return status', () => {
      cy.apiGetFrom('crawl', '/cross-index/status').then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
      });
    });
  });

  describe('Unified Crawl API', () => {
    it('GET /api/unified-crawl/jobs/active should return the crawl manager job list', () => {
      // The generated auto-ingest workflow health-checks exactly this endpoint before POSTing a
      // crawl, so it has to be present and cheap on a stack with no crawls yet.
      cy.apiGetFrom('crawl', '/unified-crawl/jobs/active').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });

  // ═══════════════════════ Admin console (:8080) ═══════════════════════

  describe('Subprocess Events API', () => {
    it('GET /api/subprocess-events/recent should return array', () => {
      cy.apiGetFrom('admin', '/subprocess-events/recent?hours=24').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('GET /api/subprocess-events/statistics should return stats', () => {
      cy.apiGetFrom('admin', '/subprocess-events/statistics').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('available');
      });
    });
  });

  describe('Eval Debugger API', () => {
    it('GET /api/eval-debugger/status should return evaluator status', () => {
      cy.apiGetFrom('admin', '/eval-debugger/status').then((res) => {
        expect(res.status).to.eq(200);
      });
    });

    it('GET /api/eval-debugger/evaluator-types should return types array', () => {
      cy.apiGetFrom('admin', '/eval-debugger/evaluator-types').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  describe('Processing Settings API', () => {
    it('GET /api/processing-settings should return current settings', () => {
      cy.apiGetFrom('admin', '/processing-settings').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });

  // ═══════════════════════ Persona boundary ═══════════════════════

  describe('Persona boundary', () => {
    it('should not expose crawl/ingest APIs on the admin console', () => {
      ['/unified-crawl/jobs', '/ingest/events/status', '/chunk-manager/sources'].forEach((path) => {
        cy.apiGetFrom('admin', path).then((res) => {
          expect(res.status, `admin ${path}`).to.eq(404);
        });
      });
    });

    it('should not expose admin APIs on the end-user apps', () => {
      (['chat', 'crawl'] as Cypress.KompilePersona[]).forEach((persona) => {
        ['/nd4j/environment', '/eval-debugger/status', '/subprocess-events/statistics']
          .forEach((path) => {
            cy.apiGetFrom(persona, path).then((res) => {
              expect(res.status, `${persona} ${path}`).to.eq(404);
            });
          });
      });
    });

    it('should not expose the chat API on the crawl manager or the admin console', () => {
      (['admin', 'crawl'] as Cypress.KompilePersona[]).forEach((persona) => {
        cy.apiGetFrom(persona, '/agents/chat/health').then((res) => {
          expect(res.status, `${persona} /api/agents/chat/health`).to.eq(404);
        });
      });
    });

    it('should serve the chat API from the chat app', () => {
      cy.apiGetFrom('chat', '/agents/chat/health').then((res) => {
        expect(res.status).to.eq(200);
      });
    });
  });
});
