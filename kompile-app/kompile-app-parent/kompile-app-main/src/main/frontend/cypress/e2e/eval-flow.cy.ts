/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Evaluation E2E Flow Tests
 * Tests eval debugger and managed eval suite APIs against a running backend:
 * - Evaluator status and types
 * - Eval suite CRUD
 * - Eval run execution
 */

describe('Evaluation Flows', () => {

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════════ Eval Debugger ═══════════════════════

  describe('Eval Debugger API', () => {
    it('should return evaluator status', () => {
      cy.apiGet('/eval-debugger/status').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('object');
      });
    });

    it('should return available evaluator types', () => {
      cy.apiGet('/eval-debugger/evaluator-types').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        if (res.body.length > 0) {
          expect(res.body[0]).to.be.a('string');
        }
      });
    });

    it('should return available LLM providers', () => {
      cy.apiGet('/eval-debugger/llm-providers').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('should return eval run history', () => {
      cy.apiGet('/eval-debugger/runs').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });
  });

  // ═══════════════════════ Managed Eval Suites ═══════════════════════

  describe('Managed Eval Suite CRUD', () => {
    const suiteName = `e2e-eval-suite-${Date.now()}`;
    let suiteId: number | null = null;

    after(() => {
      if (suiteId) {
        cy.apiDelete(`/eval-sets/${suiteId}`);
      }
    });

    it('should list eval suites', () => {
      cy.apiGet('/eval-sets').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('should create a new eval suite', () => {
      cy.apiPost('/eval-sets', {
        name: suiteName,
        description: 'E2E test evaluation suite'
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 201]);
        if (res.status === 200 || res.status === 201) {
          expect(res.body).to.have.property('id');
          expect(res.body).to.have.property('name', suiteName);
          suiteId = res.body.id;
        }
      });
    });

    it('should retrieve the created suite', () => {
      if (!suiteId) return;
      cy.apiGet(`/eval-sets/${suiteId}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('id', suiteId);
        expect(res.body).to.have.property('name', suiteName);
      });
    });

    it('should return empty results for new suite', () => {
      if (!suiteId) return;
      cy.apiGet(`/eval-sets/${suiteId}/results`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(0);
      });
    });

    it('should delete the suite', () => {
      if (!suiteId) return;
      cy.apiDelete(`/eval-sets/${suiteId}`).then((res) => {
        expect(res.status).to.be.oneOf([200, 204]);
        suiteId = null;
      });
    });

    it('should return 404 after deletion', () => {
      // Use the original suiteName to verify it's gone
      cy.apiGet('/eval-sets').then((res) => {
        const found = res.body.find((s: any) => s.name === suiteName);
        expect(found).to.be.undefined;
      });
    });
  });

  // ═══════════════════════ Eval Run Validation ═══════════════════════

  describe('Eval Run Input Validation', () => {
    it('should reject run-single with missing required fields', () => {
      cy.apiPost('/eval-debugger/run-single', {}).then((res) => {
        expect(res.status).to.be.oneOf([400, 500]);
      });
    });

    it('should reject run-batch with empty cases array', () => {
      cy.apiPost('/eval-debugger/run-batch', {
        evaluatorType: 'RELEVANCE',
        cases: []
      }).then((res) => {
        expect(res.status).to.be.oneOf([400, 500]);
      });
    });
  });
});
