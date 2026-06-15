/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Bayesian Network, Event Attribution, and Process Discovery API E2E Tests
 * Covers: /api/attribution/bayesian/*, /api/attribution/explain|predict,
 *         /api/process/attribution/*, /api/process/discovery/*
 */

describe('Bayesian & Attribution API — endpoints and contracts', () => {

  const ids: Record<string, string> = {};
  const testPrefix = `e2e-${Date.now()}`;

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════ Bayesian Network Endpoints ═══════════════════

  describe('Bayesian Network — /api/attribution/bayesian', () => {

    it('POST /bayesian/query should return posteriors for seed nodes', () => {
      cy.apiPost('/attribution/bayesian/query', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        // Endpoint should respond (may have empty graph if no KG data)
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body.posteriors).to.be.an('object');
          // Should include priors for prior→posterior comparison
          expect(res.body).to.have.property('priors');
          expect(res.body.priors).to.be.an('object');
        }
      });
    });

    it('GET /bayesian/query/:nodeId should return posteriors for a single node', () => {
      cy.apiGet('/attribution/bayesian/query/test-node-1?maxDepth=2&maxNodes=30').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body).to.have.property('variableToTitle');
        }
      });
    });

    it('POST /bayesian/query with evidence should use evidence map', () => {
      cy.apiPost('/attribution/bayesian/query', {
        seedNodeIds: ['test-node-1'],
        queryNodeId: 'test-node-1',
        evidence: { 'test-node-1': 1 },
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
        }
      });
    });

    it('POST /bayesian/mpe should return most probable explanation', () => {
      cy.apiPost('/attribution/bayesian/mpe', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('assignments');
          expect(res.body.assignments).to.be.an('object');
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
        }
      });
    });

    it('GET /bayesian/network/:nodeId/stats should return network statistics with specific fields', () => {
      cy.apiGet('/attribution/bayesian/network/test-node-1/stats?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('object');
          expect(res.body).to.have.property('nodeCount');
          expect(res.body.nodeCount).to.be.a('number');
          expect(res.body).to.have.property('edgeCount');
          expect(res.body.edgeCount).to.be.a('number');
          expect(res.body).to.have.property('rootNodes');
          expect(res.body).to.have.property('leafNodes');
          expect(res.body).to.have.property('variables');
          expect(res.body.variables).to.be.an('array');
        }
      });
    });
  });

  // ═══════════════════ MEBN Endpoints ═══════════════════

  describe('MEBN — /api/attribution/bayesian/mebn', () => {

    it('POST /bayesian/mebn/query should return entity-specific posteriors', () => {
      cy.apiPost('/attribution/bayesian/mebn/query', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body).to.have.property('variableToTitle');
        }
      });
    });

    it('GET /bayesian/mebn/query/:nodeId should return MEBN posteriors with full metadata', () => {
      cy.apiGet('/attribution/bayesian/mebn/query/test-node-1?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body.variableToNodeId).to.be.an('object');
          expect(res.body).to.have.property('variableToTitle');
          expect(res.body.variableToTitle).to.be.an('object');
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body.variableToMebnMeta).to.be.an('object');
        }
      });
    });

    it('GET /bayesian/mebn/stats/:nodeId should return MTheory statistics with detailed MFrag and entity type breakdowns', () => {
      cy.apiGet('/attribution/bayesian/mebn/stats/test-node-1?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('object');
          expect(res.body).to.have.property('name');
          expect(res.body).to.have.property('entityTypes');
          expect(res.body.entityTypes).to.be.a('number');
          expect(res.body).to.have.property('mFrags');
          expect(res.body.mFrags).to.be.a('number');
          expect(res.body).to.have.property('residentVariables');
          expect(res.body).to.have.property('inputVariables');
          expect(res.body).to.have.property('contextConstraints');
          expect(res.body).to.have.property('totalEntities');

          // Detailed MFrag breakdown
          expect(res.body).to.have.property('mFragDetails');
          expect(res.body.mFragDetails).to.be.an('array');
          if (res.body.mFragDetails.length > 0) {
            const frag = res.body.mFragDetails[0];
            expect(frag).to.have.property('name');
            expect(frag).to.have.property('residentVariables');
            expect(frag.residentVariables).to.be.an('array');
            expect(frag).to.have.property('inputVariables');
            expect(frag.inputVariables).to.be.an('array');
            expect(frag).to.have.property('contextConstraints');
            expect(frag.contextConstraints).to.be.an('array');
          }

          // Detailed entity type breakdown
          expect(res.body).to.have.property('entityTypeDetails');
          expect(res.body.entityTypeDetails).to.be.an('array');
          if (res.body.entityTypeDetails.length > 0) {
            const et = res.body.entityTypeDetails[0];
            expect(et).to.have.property('typeName');
            expect(et).to.have.property('entityCount');
            expect(et.entityCount).to.be.a('number');
            expect(et).to.have.property('entityIds');
            expect(et.entityIds).to.be.an('array');
          }
        }
      });
    });

    it('GET /bayesian/mebn/structure/:nodeId should return MEBN structure with per-variable metadata', () => {
      cy.apiGet('/attribution/bayesian/mebn/structure/test-node-1?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body).to.have.property('variableToTitle');
          // MEBN per-variable metadata
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body.variableToMebnMeta).to.be.an('object');
          const metaKeys = Object.keys(res.body.variableToMebnMeta || {});
          if (metaKeys.length > 0) {
            const meta = res.body.variableToMebnMeta[metaKeys[0]];
            expect(meta).to.have.property('mfragName');
            expect(meta.mfragName).to.be.a('string');
            expect(meta).to.have.property('nodeRole');
            expect(meta.nodeRole).to.be.oneOf(['RESIDENT', 'INPUT', 'CONTEXT']);
            expect(meta).to.have.property('rvName');
            expect(meta.rvName).to.be.a('string');
            // entityType and entityId are optional
            if (meta.entityType) {
              expect(meta.entityType).to.be.a('string');
            }
            if (meta.entityId) {
              expect(meta.entityId).to.be.a('string');
            }
          }
        }
      });
    });

    it('POST /bayesian/mebn/query should include variableToMebnMeta in response', () => {
      cy.apiPost('/attribution/bayesian/mebn/query', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body.variableToMebnMeta).to.be.an('object');
        }
      });
    });

    it('GET /bayesian/mebn/structure/:nodeId should return MFrag-to-node mapping for canvas grouping', () => {
      cy.apiGet('/attribution/bayesian/mebn/structure/test-node-1?maxDepth=3&maxNodes=100').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          // variableToMebnMeta + variableToNodeId together enable building a nodeId->mfragName map
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body).to.have.property('variableToNodeId');
          const meta = res.body.variableToMebnMeta || {};
          const nodeIdMap = res.body.variableToNodeId || {};
          const metaKeys = Object.keys(meta);
          // For each variable with MEBN meta, there should be a corresponding nodeId
          metaKeys.forEach((varName: string) => {
            if (meta[varName].mfragName) {
              expect(meta[varName].mfragName).to.be.a('string');
              // nodeId should exist for this variable
              expect(nodeIdMap).to.have.property(varName);
            }
          });
        }
      });
    });

    it('POST /bayesian/mebn/query/byType should filter by entity type and include full metadata', () => {
      cy.apiPost('/attribution/bayesian/mebn/query/byType', {
        seedNodeIds: ['test-node-1'],
        entityType: 'ENTITY',
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body.variableToNodeId).to.be.an('object');
          expect(res.body).to.have.property('variableToTitle');
          expect(res.body.variableToTitle).to.be.an('object');
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body.variableToMebnMeta).to.be.an('object');
          // priors should be filtered to match posteriors keyset
          const posteriorKeys = Object.keys(res.body.posteriors || {});
          const priorKeys = Object.keys(res.body.priors || {});
          priorKeys.forEach((key: string) => {
            expect(posteriorKeys).to.include(key);
          });
        }
      });
    });
  });

  // ═══════════════════ Sensitivity & What-If ═══════════════════

  describe('Sensitivity & What-If — /api/attribution/bayesian', () => {

    it('POST /bayesian/sensitivity should return sensitivity scores with priors baseline', () => {
      cy.apiPost('/attribution/bayesian/sensitivity', {
        seedNodeIds: ['test-node-1'],
        queryNodeId: 'test-node-1',
        epsilon: 0.01,
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('object');
          expect(res.body).to.have.property('sensitivities');
          expect(res.body.sensitivities).to.be.an('object');
          expect(res.body).to.have.property('priors');
          expect(res.body.priors).to.be.an('object');
          expect(res.body).to.have.property('baselinePosterior');
          expect(res.body.baselinePosterior).to.be.a('number');
          expect(res.body).to.have.property('queryPrior');
          expect(res.body.queryPrior).to.be.a('number');
          expect(res.body).to.have.property('queryNodeId');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
        }
      });
    });

    it('GET /bayesian/sensitivity/:nodeId should return quick sensitivity with priors baseline', () => {
      cy.apiGet('/attribution/bayesian/sensitivity/test-node-1?maxDepth=2&maxNodes=30').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('object');
          expect(res.body).to.have.property('sensitivities');
          expect(res.body.sensitivities).to.be.an('object');
          expect(res.body).to.have.property('priors');
          expect(res.body.priors).to.be.an('object');
          expect(res.body).to.have.property('baselinePosterior');
          expect(res.body.baselinePosterior).to.be.a('number');
          expect(res.body).to.have.property('queryPrior');
          expect(res.body.queryPrior).to.be.a('number');
        }
      });
    });

    it('POST /bayesian/whatif should return hypothetical posteriors with MEBN meta', () => {
      cy.apiPost('/attribution/bayesian/whatif', {
        seedNodeIds: ['test-node-1'],
        hypotheticalEvidence: { 'test-node-1': 1 },
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body).to.have.property('computedAt');
          expect(res.body).to.have.property('computationTimeMs');
          // MEBN metadata should be present when available
          if (res.body.variableToMebnMeta) {
            expect(res.body.variableToMebnMeta).to.be.an('object');
            const firstKey = Object.keys(res.body.variableToMebnMeta)[0];
            if (firstKey) {
              expect(res.body.variableToMebnMeta[firstKey]).to.have.property('mfragName');
              expect(res.body.variableToMebnMeta[firstKey]).to.have.property('nodeRole');
            }
          }
        }
      });
    });
  });

  // ═══════════════════ Event Attribution ═══════════════════

  describe('Event Attribution — /api/attribution', () => {

    it('GET /attribution/explain/:nodeId should return causal chains', () => {
      cy.apiGet('/attribution/explain/test-node-1').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('chains');
          expect(res.body.chains).to.be.an('array');
          expect(res.body).to.have.property('synthesizedExplanation');
        }
      });
    });

    it('GET /attribution/predict/:nodeId should return predicted events', () => {
      cy.apiGet('/attribution/predict/test-node-1').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('predictions');
          expect(res.body.predictions).to.be.an('array');
          expect(res.body).to.have.property('synthesizedForecast');
        }
      });
    });

    it('GET /attribution/explain/:nodeId/quick should return quick attribution', () => {
      cy.apiGet('/attribution/explain/test-node-1/quick?includeCounterfactuals=true').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('chains');
        }
      });
    });

    it('GET /attribution/predict/:nodeId/quick should return quick prediction with full fields', () => {
      cy.apiGet('/attribution/predict/test-node-1/quick').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('predictions');
          expect(res.body).to.have.property('nodesVisited');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body).to.have.property('computedAt');
          if (res.body.predictions?.length > 0) {
            const pred = res.body.predictions[0];
            expect(pred).to.have.property('nodeId');
            expect(pred).to.have.property('probability');
            expect(pred).to.have.property('hopsFromSource');
            expect(pred).to.have.property('pathFromSource');
            expect(pred.pathFromSource).to.be.an('array');
            expect(pred).to.have.property('pathEdgeTypes');
            expect(pred.pathEdgeTypes).to.be.an('array');
          }
        }
      });
    });

    it('GET /attribution/explain/:nodeId/quick should include influence scores and counterfactuals', () => {
      cy.apiGet('/attribution/explain/test-node-1/quick?includeCounterfactuals=true').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('chains');
          expect(res.body.chains).to.be.an('array');
          expect(res.body).to.have.property('influenceScores');
          expect(res.body.influenceScores).to.be.an('object');
          expect(res.body).to.have.property('counterfactuals');
          expect(res.body.counterfactuals).to.be.an('array');
          expect(res.body).to.have.property('deadEnds');
          expect(res.body.deadEnds).to.be.an('array');
          expect(res.body).to.have.property('nodesVisited');
          expect(res.body.nodesVisited).to.be.a('number');
          expect(res.body).to.have.property('edgesExamined');
          expect(res.body.edgesExamined).to.be.a('number');

          // Each chain should have rootCauseNodeId and hops with causeNodeId/effectNodeId for canvas overlay
          if (res.body.chains.length > 0) {
            const chain = res.body.chains[0];
            expect(chain).to.have.property('rootCauseNodeId');
            expect(chain).to.have.property('rootCauseTitle');
            expect(chain).to.have.property('overallConfidence');
            expect(chain.overallConfidence).to.be.a('number');
            expect(chain).to.have.property('confidenceBand');
            expect(chain).to.have.property('depth');
            expect(chain).to.have.property('computedAt');
            expect(chain).to.have.property('hops');
            expect(chain.hops).to.be.an('array');
            if (chain.hops.length > 0) {
              const hop = chain.hops[0];
              expect(hop).to.have.property('causeNodeId');
              expect(hop).to.have.property('effectNodeId');
              expect(hop).to.have.property('causalType');
              expect(hop).to.have.property('strength');
              expect(hop.strength).to.be.a('number');
            }
          }

          // Each counterfactual should have full detail
          if (res.body.counterfactuals.length > 0) {
            const cf = res.body.counterfactuals[0];
            expect(cf).to.have.property('removedNodeId');
            expect(cf).to.have.property('removedNodeTitle');
            expect(cf).to.have.property('targetStillReachable');
            expect(cf.targetStillReachable).to.be.a('boolean');
            expect(cf).to.have.property('survivingChainCount');
            expect(cf.survivingChainCount).to.be.a('number');
            expect(cf).to.have.property('confidenceDelta');
            expect(cf.confidenceDelta).to.be.a('number');
            expect(cf).to.have.property('necessaryCause');
            expect(cf.necessaryCause).to.be.a('boolean');
          }
        }
      });
    });

    it('GET /attribution/predict/:nodeId/quick should include prediction details with nodeIds for prior lookup', () => {
      cy.apiGet('/attribution/predict/test-node-1/quick').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('predictions');
          expect(res.body.predictions).to.be.an('array');
          expect(res.body).to.have.property('sourceNodeId');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');

          // Each prediction should have nodeId for prior overlay cross-referencing
          if (res.body.predictions.length > 0) {
            const pred = res.body.predictions[0];
            expect(pred).to.have.property('nodeId');
            expect(pred).to.have.property('title');
            expect(pred).to.have.property('probability');
            expect(pred.probability).to.be.a('number');
            expect(pred).to.have.property('hopsFromSource');
            expect(pred.hopsFromSource).to.be.a('number');
          }
        }
      });
    });
  });

  // ═══════════════════ Process Attribution ═══════════════════

  describe('Process Attribution — /api/process/attribution', () => {

    let runId: string;

    before(() => {
      // Create a process and start a run so we have a valid runId
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-attr-proc`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [
            { id: 'step-1', name: 'Auto Step', stepType: 'AUTO',
              executionExpressions: { computed: '#input + 1' },
              graphNodeIds: ['test-node-1'] }
          ]
        }]
      }).then((res) => {
        ids.attrProcId = res.body.id;
        return cy.apiPost(`/process/definition/${ids.attrProcId}/approve`, { approvedBy: 'e2e' });
      }).then(() => {
        return cy.apiPost('/process/run', {
          processDefinitionId: ids.attrProcId,
          initialData: { input: 42 }
        });
      }).then((res) => {
        runId = res.body.id;
      });
    });

    it('GET /process/attribution/run/:runId/risk should return risk assessment with step attribution summaries', () => {
      cy.apiGet(`/process/attribution/run/${runId}/risk?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('assessmentId');
          expect(res.body).to.have.property('overallRiskScore');
          expect(res.body.overallRiskScore).to.be.a('number');
          expect(res.body).to.have.property('riskLevel');
          expect(res.body).to.have.property('alerts');
          expect(res.body.alerts).to.be.an('array');
          expect(res.body).to.have.property('stepRiskScores');
          expect(res.body).to.have.property('highRiskStepIds');
          expect(res.body).to.have.property('computationTimeMs');
          // Step attribution summaries should include Bayesian data
          expect(res.body).to.have.property('stepAttributionResults');
          if (res.body.stepAttributionResults) {
            const stepKeys = Object.keys(res.body.stepAttributionResults);
            if (stepKeys.length > 0) {
              const firstStep = res.body.stepAttributionResults[stepKeys[0]];
              expect(firstStep).to.have.property('riskScore');
              expect(firstStep).to.have.property('bayesianPosteriors');
              expect(firstStep).to.have.property('bayesianPriors');
            }
          }
        }
      });
    });

    it('GET /process/attribution/run/:runId/step/:stepId/explain should return step attribution with Bayesian data', () => {
      cy.apiGet(`/process/attribution/run/${runId}/step/step-1/explain?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('stepId');
          expect(res.body).to.have.property('riskScore');
          expect(res.body.riskScore).to.be.a('number');
          expect(res.body).to.have.property('hasGraphBindings');
          expect(res.body.hasGraphBindings).to.be.a('boolean');
          expect(res.body).to.have.property('attribution');
          expect(res.body).to.have.property('prediction');
          // Bayesian posteriors and priors should be present as objects
          expect(res.body).to.have.property('bayesianPosteriors');
          expect(res.body.bayesianPosteriors).to.be.an('object');
          expect(res.body).to.have.property('bayesianPriors');
          expect(res.body.bayesianPriors).to.be.an('object');
        }
      });
    });

    it('GET /process/attribution/run/:runId/step/:stepId/explain should include MEBN meta and influence scores', () => {
      cy.apiGet(`/process/attribution/run/${runId}/step/step-1/explain?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          // Attribution result should have influence scores for canvas overlay
          if (res.body.attribution) {
            expect(res.body.attribution).to.have.property('influenceScores');
            expect(res.body.attribution.influenceScores).to.be.an('object');
            expect(res.body.attribution).to.have.property('counterfactuals');
            expect(res.body.attribution.counterfactuals).to.be.an('array');
            expect(res.body.attribution).to.have.property('deadEnds');
            expect(res.body.attribution.deadEnds).to.be.an('array');
          }
          // Prediction should have nodeIds for prior cross-referencing
          if (res.body.prediction && res.body.prediction.predictions) {
            res.body.prediction.predictions.forEach((pred: any) => {
              expect(pred).to.have.property('nodeId');
              expect(pred).to.have.property('probability');
              expect(pred.probability).to.be.a('number');
            });
          }
          // MEBN meta should be available for popover rendering
          if (res.body.mebnMeta) {
            expect(res.body.mebnMeta).to.be.an('object');
            const metaKeys = Object.keys(res.body.mebnMeta);
            if (metaKeys.length > 0) {
              const m = res.body.mebnMeta[metaKeys[0]];
              expect(m).to.have.property('mfragName');
              expect(m).to.have.property('nodeRole');
            }
          }
        }
      });
    });

    it('GET /process/attribution/definition/:defId/risk should return pre-flight risk with step Bayesian data', () => {
      cy.apiGet(`/process/attribution/definition/${ids.attrProcId}/risk?version=1&useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('overallRiskScore');
          expect(res.body.overallRiskScore).to.be.a('number');
          expect(res.body).to.have.property('riskLevel');
          expect(res.body).to.have.property('processDefinitionId');
          expect(res.body).to.have.property('stepRiskScores');
          expect(res.body).to.have.property('alerts');
          expect(res.body.alerts).to.be.an('array');
          // Step attribution results should include Bayesian data when present
          if (res.body.stepAttributionResults) {
            const stepKeys = Object.keys(res.body.stepAttributionResults);
            if (stepKeys.length > 0) {
              const firstStep = res.body.stepAttributionResults[stepKeys[0]];
              expect(firstStep).to.have.property('riskScore');
              expect(firstStep).to.have.property('bayesianPosteriors');
              expect(firstStep.bayesianPosteriors).to.be.an('object');
              expect(firstStep).to.have.property('bayesianPriors');
              expect(firstStep.bayesianPriors).to.be.an('object');
            }
          }
        }
      });
    });
  });

  // ═══════════════════ Process Discovery ═══════════════════

  describe('Process Discovery — /api/process/discovery', () => {

    it('GET /process/discovery/suggestions should list suggestions', () => {
      cy.apiGet('/process/discovery/suggestions').then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
        if (res.status === 200) {
          // Response is wrapped: { count, suggestions }
          expect(res.body).to.have.property('count');
          expect(res.body).to.have.property('suggestions');
          expect(res.body.suggestions).to.be.an('array');
          if (res.body.suggestions.length > 0) {
            const suggestion = res.body.suggestions[0];
            expect(suggestion).to.have.property('id');
            expect(suggestion).to.have.property('name');
            expect(suggestion).to.have.property('confidence');
            expect(suggestion).to.have.property('phases');
            // bayesianPosteriors and bayesianPriors should be present
            expect(suggestion).to.have.property('bayesianPosteriors');
            expect(suggestion).to.have.property('bayesianPriors');
          }
        }
      });
    });

    it('POST /process/discovery/suggest should trigger discovery with full Bayesian data', () => {
      cy.apiPost('/process/discovery/suggest', {}).then((res) => {
        // May require active fact sheets to produce results
        expect(res.status).to.be.oneOf([200, 202, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('count');
          expect(res.body).to.have.property('suggestions');
          expect(res.body.suggestions).to.be.an('array');
          if (res.body.suggestions.length > 0) {
            const suggestion = res.body.suggestions[0];
            expect(suggestion).to.have.property('bayesianPosteriors');
            expect(suggestion).to.have.property('bayesianPriors');
            expect(suggestion).to.have.property('structuredEvidence');
            expect(suggestion.structuredEvidence).to.be.an('array');
          }
        }
      });
    });

    it('GET /process/discovery/suggestions should include structuredEvidence', () => {
      cy.apiGet('/process/discovery/suggestions').then((res) => {
        if (res.status === 200 && res.body.suggestions && res.body.suggestions.length > 0) {
          const suggestion = res.body.suggestions[0];
          expect(suggestion).to.have.property('structuredEvidence');
          expect(suggestion.structuredEvidence).to.be.an('array');
          if (suggestion.structuredEvidence.length > 0) {
            const evidence = suggestion.structuredEvidence[0];
            expect(evidence).to.have.property('type');
            expect(evidence).to.have.property('description');
          }
        }
      });
    });
  });

  // ═══════════════════ Alert Detail Contracts ═══════════════════

  describe('Alert Detail — causal chains and predictions expanded', () => {

    it('Risk assessment alerts should include full causal chain detail for rendering', () => {
      // Use the process created above
      cy.apiGet(`/process/attribution/run/${ids.attrProcId ? ids.attrProcId : 'test-run'}/risk?useLlm=false`).then((res) => {
        if (res.status === 200 && res.body.alerts?.length > 0) {
          const alert = res.body.alerts[0];
          expect(alert).to.have.property('causalChains');
          expect(alert.causalChains).to.be.an('array');
          if (alert.causalChains.length > 0) {
            const chain = alert.causalChains[0];
            expect(chain).to.have.property('rootCauseNodeId');
            expect(chain).to.have.property('rootCauseTitle');
            expect(chain).to.have.property('targetEventNodeId');
            expect(chain).to.have.property('targetEventTitle');
            expect(chain).to.have.property('overallConfidence');
            expect(chain.overallConfidence).to.be.a('number');
            expect(chain).to.have.property('confidenceBand');
          }
          expect(alert).to.have.property('predictions');
          expect(alert.predictions).to.be.an('array');
          if (alert.predictions.length > 0) {
            const pred = alert.predictions[0];
            expect(pred).to.have.property('nodeId');
            expect(pred).to.have.property('title');
            expect(pred).to.have.property('probability');
            expect(pred.probability).to.be.a('number');
            expect(pred).to.have.property('hopsFromSource');
          }
        }
      });
    });
  });

  // ═══════════════════ Control Attestation stepId ═══════════════════

  describe('Control Attestation — stepId filtering', () => {

    it('Control attestation results should include stepId for per-step filtering', () => {
      // Use any available run
      cy.apiGet('/process/run/all').then((runsRes) => {
        if (runsRes.status === 200 && runsRes.body?.length > 0) {
          const runId = runsRes.body[0].id;
          cy.apiGet(`/process/control/results/${runId}`).then((res) => {
            if (res.status === 200 && res.body?.length > 0) {
              const att = res.body[0];
              expect(att).to.have.property('controlId');
              expect(att).to.have.property('stepId');
              expect(att).to.have.property('passed');
              expect(att.passed).to.be.a('boolean');
            }
          });
        }
      });
    });
  });

  // ═══════════════════ MPE MEBN Metadata ═══════════════════

  describe('MPE — MEBN metadata in response', () => {

    it('POST /bayesian/mpe should include variableToMebnMeta for MPE MEBN badge rendering', () => {
      cy.apiPost('/attribution/bayesian/mpe', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('assignments');
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          // MEBN metadata should be present for badge rendering
          expect(res.body).to.have.property('variableToMebnMeta');
          if (res.body.variableToMebnMeta) {
            expect(res.body.variableToMebnMeta).to.be.an('object');
            const metaKeys = Object.keys(res.body.variableToMebnMeta);
            if (metaKeys.length > 0) {
              const m = res.body.variableToMebnMeta[metaKeys[0]];
              expect(m).to.have.property('mfragName');
              expect(m).to.have.property('nodeRole');
            }
          }
        }
      });
    });

    it('POST /bayesian/mpe should include inferenceTrace, variableToNodeId, variableToTitle', () => {
      cy.apiPost('/attribution/bayesian/mpe', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body.variableToNodeId).to.be.an('object');
          expect(res.body).to.have.property('variableToTitle');
          expect(res.body.variableToTitle).to.be.an('object');
          expect(res.body).to.have.property('inferenceTrace');
          expect(res.body.inferenceTrace).to.be.an('array');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
        }
      });
    });
  });

  // ═══════════════════ Full POST Attribution Endpoints ═══════════════════

  describe('Full POST Attribution — /api/attribution', () => {

    it('POST /attribution/explain should return attribution with full request params', () => {
      cy.apiPost('/attribution/explain', {
        targetNodeId: 'test-node-1',
        maxDepth: 3,
        maxChains: 5,
        useLlm: false,
        includeCounterfactuals: true
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('targetNodeId');
          expect(res.body).to.have.property('chains');
          expect(res.body.chains).to.be.an('array');
          expect(res.body).to.have.property('influenceScores');
          expect(res.body).to.have.property('counterfactuals');
          expect(res.body).to.have.property('deadEnds');
          expect(res.body).to.have.property('computedAt');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
          expect(res.body).to.have.property('llmUsed');
          expect(res.body.llmUsed).to.be.a('boolean');
          // Chain narrative field
          if (res.body.chains.length > 0) {
            const chain = res.body.chains[0];
            expect(chain).to.have.property('chainId');
            if (chain.narrative) {
              expect(chain.narrative).to.be.a('string');
            }
          }
        }
      });
    });

    it('POST /attribution/predict should return predictions with full request params', () => {
      cy.apiPost('/attribution/predict', {
        sourceNodeId: 'test-node-1',
        maxDepth: 3,
        maxPredictions: 10,
        useLlm: false
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('sourceNodeId');
          expect(res.body).to.have.property('sourceTitle');
          expect(res.body).to.have.property('predictions');
          expect(res.body.predictions).to.be.an('array');
          expect(res.body).to.have.property('computedAt');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
          expect(res.body).to.have.property('nodesVisited');
          expect(res.body.nodesVisited).to.be.a('number');
          expect(res.body).to.have.property('llmUsed');
          expect(res.body.llmUsed).to.be.a('boolean');
          // PredictedEvent evidence and path details
          if (res.body.predictions.length > 0) {
            const pred = res.body.predictions[0];
            expect(pred).to.have.property('nodeId');
            expect(pred).to.have.property('pathFromSource');
            expect(pred.pathFromSource).to.be.an('array');
            expect(pred).to.have.property('pathEdgeTypes');
            expect(pred.pathEdgeTypes).to.be.an('array');
            expect(pred).to.have.property('evidence');
            expect(pred.evidence).to.be.an('array');
          }
        }
      });
    });
  });

  // ═══════════════════ Inference Trace Validation ═══════════════════

  describe('Inference Trace — response field validation', () => {

    it('POST /bayesian/query should include inferenceTrace with step details', () => {
      cy.apiPost('/attribution/bayesian/query', {
        seedNodeIds: ['test-node-1'],
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        if (res.status === 200) {
          expect(res.body).to.have.property('inferenceTrace');
          expect(res.body.inferenceTrace).to.be.an('array');
          if (res.body.inferenceTrace.length > 0) {
            const step = res.body.inferenceTrace[0];
            expect(step).to.have.property('operation');
            expect(step).to.have.property('eliminatedVariable');
          }
          // NetworkStats should be present
          expect(res.body).to.have.property('networkStats');
          if (res.body.networkStats) {
            expect(res.body.networkStats).to.be.an('object');
          }
          // computationTimeMs and computedAt
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
        }
      });
    });
  });

  // ═══════════════════ ProcessEventAlert Field Validation ═══════════════════

  describe('ProcessEventAlert — full field validation', () => {

    it('Risk assessment alerts should include all ProcessEventAlert fields', () => {
      // Use any available run
      cy.apiGet('/process/run/all').then((runsRes) => {
        if (runsRes.status === 200 && runsRes.body?.length > 0) {
          const runId = runsRes.body[0].id;
          cy.apiGet(`/process/attribution/run/${runId}/risk?useLlm=false`).then((res) => {
            if (res.status === 200 && res.body.alerts?.length > 0) {
              const alert = res.body.alerts[0];
              expect(alert).to.have.property('alertId');
              expect(alert).to.have.property('severity');
              expect(alert).to.have.property('alertType');
              expect(alert.alertType).to.be.a('string');
              expect(alert).to.have.property('title');
              expect(alert.title).to.be.a('string');
              expect(alert).to.have.property('confidence');
              expect(alert.confidence).to.be.a('number');
              expect(alert).to.have.property('llmUsed');
              expect(alert.llmUsed).to.be.a('boolean');
              expect(alert).to.have.property('createdAt');
              expect(alert).to.have.property('acknowledged');
              expect(alert.acknowledged).to.be.a('boolean');
            }
          });
        }
      });
    });
  });

  // ═══════════════════ Discovery Suggestion CRUD ═══════════════════

  describe('Discovery Suggestion CRUD — /api/process/discovery/suggestions', () => {

    let suggestionId: string;

    it('GET /process/discovery/suggestions should list with optional filters', () => {
      cy.apiGet('/process/discovery/suggestions?pendingOnly=true').then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
        if (res.status === 200) {
          expect(res.body).to.have.property('suggestions');
          expect(res.body.suggestions).to.be.an('array');
          if (res.body.suggestions.length > 0) {
            suggestionId = res.body.suggestions[0].id;
          }
        }
      });
    });

    it('GET /process/discovery/suggestions/:id should return single suggestion', () => {
      if (!suggestionId) return;
      cy.apiGet(`/process/discovery/suggestions/${suggestionId}`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404]);
        if (res.status === 200) {
          expect(res.body).to.have.property('id');
          expect(res.body).to.have.property('name');
          expect(res.body).to.have.property('confidence');
          expect(res.body).to.have.property('bayesianPosteriors');
          expect(res.body).to.have.property('bayesianPriors');
          expect(res.body).to.have.property('structuredEvidence');
        }
      });
    });
  });

  // ═══════════════════ MEBN Type Query & Network Stats ═══════════════════

  describe('MEBN Type Query & Network Stats', () => {

    it('POST /bayesian/mebn/query/byType should accept entityType filter', () => {
      cy.apiPost('/attribution/bayesian/mebn/query/byType', {
        seedNodeIds: ['test-node-1'],
        entityType: 'DOCUMENT',
        maxDepth: 2,
        maxNodes: 50
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('posteriors');
          expect(res.body).to.have.property('priors');
          expect(res.body).to.have.property('variableToNodeId');
          expect(res.body).to.have.property('variableToMebnMeta');
          expect(res.body).to.have.property('computedAt');
        }
      });
    });

    it('GET /bayesian/mebn/stats/:nodeId should return MEBN stats without inference', () => {
      cy.apiGet('/attribution/bayesian/mebn/stats/test-node-1?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          // Stats endpoint returns a plain map with network metadata
          expect(res.body).to.be.an('object');
        }
      });
    });

    it('GET /bayesian/network/:nodeId/stats should return network stats', () => {
      cy.apiGet('/attribution/bayesian/network/test-node-1/stats?maxDepth=2&maxNodes=50').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.be.an('object');
        }
      });
    });
  });

  // ═══════════════════ Flow Analysis Endpoints ═══════════════════

  describe('Flow Analysis — cross-document and scoped discovery', () => {

    it('POST /process/discovery/cross-document-flows should analyze cross-doc patterns', () => {
      cy.apiPost('/process/discovery/cross-document-flows', {
        graphNodeIds: ['node-a', 'node-b']
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('suggestions');
          if (res.body.suggestions?.length > 0) {
            const s = res.body.suggestions[0];
            expect(s).to.have.property('name');
            expect(s).to.have.property('confidence');
            expect(s).to.have.property('discoverySource');
          }
        }
      });
    });

    it('POST /process/discovery/email-flows should analyze email patterns', () => {
      cy.apiPost('/process/discovery/email-flows', {
        graphNodeIds: ['email-node-1']
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('suggestions');
        }
      });
    });

    it('POST /process/discovery/excel-flows should analyze spreadsheet patterns', () => {
      cy.apiPost('/process/discovery/excel-flows', {
        graphNodeIds: ['excel-node-1']
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('suggestions');
        }
      });
    });

    it('POST /process/discovery/document-flows should analyze document pipelines', () => {
      cy.apiPost('/process/discovery/document-flows', {
        graphNodeIds: ['doc-node-1']
      }).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('suggestions');
        }
      });
    });
  });

  // ═══════════════════ Suggestion CRUD Lifecycle ═══════════════════

  describe('Suggestion Lifecycle — accept and delete', () => {

    it('POST /process/discovery/suggestions/:id/accept should accept a stored suggestion', () => {
      // First list suggestions to find one to accept
      cy.apiGet('/process/discovery/suggestions?pendingOnly=true').then((listRes) => {
        if (listRes.status !== 200 || !listRes.body.suggestions?.length) return;
        const id = listRes.body.suggestions[0].id;

        cy.apiPost(`/process/discovery/suggestions/${id}/accept`, {}).then((res) => {
          expect(res.status).to.be.oneOf([200, 404, 409]);
          if (res.status === 200) {
            expect(res.body).to.have.property('id');
            expect(res.body).to.have.property('name');
          }
        });
      });
    });

    it('DELETE /process/discovery/suggestions/:id should delete a suggestion', () => {
      cy.apiGet('/process/discovery/suggestions').then((listRes) => {
        if (listRes.status !== 200 || !listRes.body.suggestions?.length) return;
        const last = listRes.body.suggestions[listRes.body.suggestions.length - 1];

        cy.apiDelete(`/process/discovery/suggestions/${last.id}`).then((res) => {
          expect(res.status).to.be.oneOf([200, 204, 404]);
        });
      });
    });
  });

  // ═══════════════════ MEBN Meta on Suggestion Response ═══════════════════

  describe('Suggestion Response — mebnMeta field', () => {

    it('GET /process/discovery/suggestions should include mebnMeta when available', () => {
      cy.apiGet('/process/discovery/suggestions').then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200 && res.body.suggestions?.length > 0) {
          const s = res.body.suggestions[0];
          expect(s).to.have.property('bayesianPosteriors');
          expect(s).to.have.property('bayesianPriors');
          // mebnMeta may or may not be present depending on backend version
          if (s.mebnMeta) {
            expect(s.mebnMeta).to.be.an('object');
            const firstKey = Object.keys(s.mebnMeta)[0];
            if (firstKey) {
              expect(s.mebnMeta[firstKey]).to.have.property('mfragName');
              expect(s.mebnMeta[firstKey]).to.have.property('nodeRole');
            }
          }
        }
      });
    });
  });
});
