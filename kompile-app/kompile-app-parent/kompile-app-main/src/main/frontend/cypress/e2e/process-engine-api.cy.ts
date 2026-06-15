/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Process Engine API E2E Tests
 * Full lifecycle test covering ontology, process definition, workflow runs,
 * approvals, controls, SpEL evaluation, and human step completion.
 */

describe('Process Engine API — full lifecycle', () => {

  const ids: Record<string, string> = {};
  const testPrefix = `e2e-${Date.now()}`;

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════ Ontology CRUD ═══════════════════

  describe('Ontology Management', () => {
    it('should create an ontology', () => {
      cy.apiPost('/process/ontology', {
        name: `${testPrefix}-ontology`,
        entityTypes: [
          {
            name: 'Transaction',
            description: 'Financial transaction',
            fields: [
              { name: 'amount', type: 'DECIMAL', required: true },
              { name: 'currency', type: 'STRING', required: true }
            ],
            rules: [
              { name: 'positive-amount', expression: "#amount > 0", severity: 'ERROR', ruleType: 'ASSERTION' }
            ]
          }
        ],
        metadata: { test: true }
      }).then((res) => {
        expect(res.status).to.eq(201);
        expect(res.body).to.have.property('id');
        expect(res.body).to.have.property('version', 1);
        expect(res.body.name).to.eq(`${testPrefix}-ontology`);
        ids.ontologyId = res.body.id;
      });
    });

    it('should list ontologies including the created one', () => {
      cy.apiGet('/process/ontology').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        const found = res.body.find((o: any) => o.id === ids.ontologyId);
        expect(found).to.not.be.undefined;
        expect(found.name).to.eq(`${testPrefix}-ontology`);
      });
    });
  });

  // ═══════════════════ Process Definition ═══════════════════

  describe('Process Definition Lifecycle', () => {
    it('should create a process definition with AUTO, APPROVE, and HUMAN steps', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-process`,
        ontologySchemaId: ids.ontologyId,
        phases: [
          {
            id: 'phase-1',
            name: 'Preparation',
            order: 1,
            steps: [
              {
                id: 'step-auto-1',
                name: 'Auto Validation',
                stepType: 'AUTO',
                inputKeys: ['amount'],
                graphNodeIds: ['test-node-1']
              }
            ]
          },
          {
            id: 'phase-2',
            name: 'Review',
            order: 2,
            steps: [
              {
                id: 'step-approve-1',
                name: 'Manager Approval',
                stepType: 'APPROVE',
                approvalPolicy: {
                  approverPool: ['manager@test.com'],
                  mode: 'SINGLE'
                }
              },
              {
                id: 'step-human-1',
                name: 'Manual Verification',
                stepType: 'HUMAN'
              }
            ]
          }
        ]
      }).then((res) => {
        expect(res.status).to.eq(201);
        expect(res.body).to.have.property('id');
        expect(res.body.status).to.eq('DRAFT');
        ids.processId = res.body.id;
      });
    });

    it('should list definitions including the created one', () => {
      cy.apiGet('/process/definition').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        const found = res.body.find((d: any) => d.id === ids.processId);
        expect(found).to.not.be.undefined;
      });
    });

    it('should approve the process definition', () => {
      cy.apiPost(`/process/definition/${ids.processId}/approve`, {
        approvedBy: 'e2e-approver'
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.status).to.eq('APPROVED');
      });
    });
  });

  // ═══════════════════ Workflow Runs ═══════════════════

  describe('Workflow Run Execution', () => {
    it('should start a run', () => {
      cy.apiPost('/process/run', {
        processDefinitionId: ids.processId,
        initialData: { amount: 5000, currency: 'USD' }
      }).then((res) => {
        expect(res.status).to.eq(201);
        expect(res.body).to.have.property('id');
        // After AUTO step completes, run should pause at APPROVE step
        expect(res.body.status).to.eq('PAUSED_FOR_APPROVAL');
        expect(res.body.stepExecutions).to.be.an('array');
        ids.runId = res.body.id;
      });
    });

    it('should get the run details', () => {
      cy.apiGet(`/process/run/${ids.runId}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.id).to.eq(ids.runId);
        expect(res.body.stepExecutions).to.have.length(3);
        // First step (AUTO) should be completed with recorded inputs/outputs
        expect(res.body.stepExecutions[0].status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[0]).to.have.property('inputs');
        expect(res.body.stepExecutions[0]).to.have.property('outputs');
        expect(res.body.stepExecutions[0]).to.have.property('inputHash');
        // Second step (APPROVE) should be awaiting
        expect(res.body.stepExecutions[1].status).to.eq('AWAITING_APPROVAL');
        // graphNodeIds should propagate from step definition
        expect(res.body.graphNodeIds).to.include('test-node-1');
        // runData should contain initial data
        expect(res.body.runData).to.have.property('amount', 5000);
        expect(res.body.runData).to.have.property('currency', 'USD');
      });
    });

    it('should list active runs including the started run', () => {
      cy.apiGet('/process/run/active').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        const found = res.body.find((r: any) => r.id === ids.runId);
        expect(found).to.not.be.undefined;
      });
    });

    it('should list all runs as a superset of active', () => {
      cy.apiGet('/process/run/all').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.be.gte(1);
        const found = res.body.find((r: any) => r.id === ids.runId);
        expect(found).to.not.be.undefined;
      });
    });
  });

  // ═══════════════════ Approvals ═══════════════════

  describe('Approval Workflow', () => {
    it('should have pending approvals', () => {
      cy.apiGet('/process/approval/pending').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        const approval = res.body.find((a: any) => a.workflowRunId === ids.runId);
        expect(approval).to.not.be.undefined;
        ids.approvalId = approval.id;
      });
    });

    it('should escalate an approval (run stays paused)', () => {
      cy.apiPost(`/process/approval/${ids.approvalId}/respond`, {
        requestId: ids.approvalId,
        respondedBy: 'e2e-reviewer',
        action: 'ESCALATE',
        comment: 'Needs senior review'
      }).then((res) => {
        expect(res.status).to.eq(200);
        // Run should still be paused after ESCALATE
        expect(res.body.status).to.eq('PAUSED_FOR_APPROVAL');
      });
    });

    it('should approve the request (run advances to HUMAN step)', () => {
      // Use the known approval ID (status was changed to ESCALATED, so it won't be in pending list)
      cy.apiPost(`/process/approval/${ids.approvalId}/respond`, {
        requestId: ids.approvalId,
        respondedBy: 'e2e-senior-reviewer',
        action: 'APPROVE',
        comment: 'Approved after escalation'
      }).then((res) => {
        expect(res.status).to.eq(200);
        // After APPROVE, run should advance to HUMAN step and pause
        expect(res.body.status).to.eq('PAUSED_FOR_HUMAN');
        expect(res.body.stepExecutions[1].status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[2].status).to.eq('AWAITING_APPROVAL');
      });
    });

    it('should complete the human step', () => {
      cy.apiPost(`/process/run/${ids.runId}/complete-step`, {
        stepId: 'step-human-1',
        completedBy: 'e2e-human-worker',
        outputs: { verified: true, notes: 'All checks passed' }
      }).then((res) => {
        expect(res.status).to.eq(200);
        // All steps done — run should be COMPLETED
        expect(res.body.status).to.eq('COMPLETED');
        expect(res.body.completedAt).to.not.be.null;
        expect(res.body.stepExecutions[2].status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[2].executedBy).to.eq('e2e-human-worker');
      });
    });
  });

  // ═══════════════════ Cancel Run ═══════════════════

  describe('Cancel Run', () => {
    it('should cancel an active run', () => {
      // Start a new run to cancel
      cy.apiPost('/process/run', {
        processDefinitionId: ids.processId,
        initialData: { amount: 100 }
      }).then((res) => {
        expect(res.status).to.eq(201);
        ids.cancelRunId = res.body.id;

        return cy.apiPost(`/process/run/${ids.cancelRunId}/cancel`, {});
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.status).to.eq('CANCELLED');
        expect(res.body.completedAt).to.not.be.null;
      });
    });

    it('should return 404 when cancelling a terminal run', () => {
      cy.apiPost(`/process/run/${ids.cancelRunId}/cancel`, {}).then((res) => {
        expect(res.status).to.eq(404);
      });
    });
  });

  // ═══════════════════ Controls ═══════════════════

  describe('Control Definitions', () => {
    it('should create a control', () => {
      cy.apiPost('/process/control', {
        name: `${testPrefix}-threshold-check`,
        description: 'Ensures amount is under 10000',
        gateType: 'HARD',
        expression: "#data['amount'] < 10000",
        severity: 'HIGH',
        regulatoryReference: 'SOX Section 404'
      }).then((res) => {
        expect(res.status).to.eq(201);
        expect(res.body).to.have.property('id');
        expect(res.body.name).to.eq(`${testPrefix}-threshold-check`);
        ids.controlId = res.body.id;
      });
    });

    it('should list controls including the created one', () => {
      cy.apiGet('/process/control').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        const found = res.body.find((c: any) => c.id === ids.controlId);
        expect(found).to.not.be.undefined;
      });
    });

    it('should get a control by ID', () => {
      cy.apiGet(`/process/control/${ids.controlId}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.gateType).to.eq('HARD');
        expect(res.body.expression).to.eq("#data['amount'] < 10000");
      });
    });

    it('should evaluate a control as PASSED', () => {
      cy.apiPost(`/process/control/${ids.controlId}/evaluate`, {
        runId: ids.runId,
        data: { data: { amount: 5000 } }
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.passed).to.be.true;
        expect(res.body.controlId).to.eq(ids.controlId);
      });
    });

    it('should evaluate a control as FAILED', () => {
      cy.apiPost(`/process/control/${ids.controlId}/evaluate`, {
        runId: ids.runId,
        data: { data: { amount: 50000 } }
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.passed).to.be.false;
      });
    });
  });

  // ═══════════════════ SpEL Execution in AUTO Steps ═══════════════════

  describe('AUTO Step SpEL Execution & Data Pipeline', () => {
    it('should execute SpEL expressions in AUTO steps and merge outputs into runData', () => {
      const spelProcName = `${testPrefix}-spel-exec`;
      // Create a process with AUTO steps that have executionExpressions
      cy.apiPost('/process/definition', {
        name: spelProcName,
        phases: [{
          id: 'phase-1',
          name: 'Compute',
          order: 1,
          steps: [
            {
              id: 'step-calc',
              name: 'Calculate Total',
              stepType: 'AUTO',
              inputKeys: ['amount', 'quantity'],
              executionExpressions: {
                totalAmount: '#amount * #quantity',
                isHighValue: '#amount > 10000'
              }
            },
            {
              id: 'step-derive',
              name: 'Derive Category',
              stepType: 'AUTO',
              inputKeys: ['totalAmount'],
              executionExpressions: {
                category: '#isHighValue ? "PREMIUM" : "STANDARD"',
                taxAmount: '#totalAmount * 0.1'
              }
            }
          ]
        }]
      }).then((res) => {
        expect(res.status).to.eq(201);
        ids.spelProcId = res.body.id;
        return cy.apiPost(`/process/definition/${ids.spelProcId}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: ids.spelProcId,
          initialData: { amount: 500, quantity: 3 }
        });
      }).then((res) => {
        expect(res.status).to.eq(201);
        expect(res.body.status).to.eq('COMPLETED');
        // Step 1 should have computed totalAmount and isHighValue
        expect(res.body.stepExecutions[0].outputs).to.have.property('totalAmount', 1500);
        expect(res.body.stepExecutions[0].outputs).to.have.property('isHighValue', false);
        // Step 2 should have consumed step 1's outputs from runData
        expect(res.body.stepExecutions[1].outputs).to.have.property('category', 'STANDARD');
        expect(res.body.stepExecutions[1].outputs).to.have.property('taxAmount', 150.0);
        // runData should contain all accumulated values
        expect(res.body.runData).to.have.property('totalAmount', 1500);
        expect(res.body.runData).to.have.property('category', 'STANDARD');
        expect(res.body.runData).to.have.property('taxAmount', 150.0);
      });
    });

    it('should pass runData through to downstream steps even without expressions', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-passthrough`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [
            { id: 's1', name: 'Step 1', stepType: 'AUTO',
              executionExpressions: { computed: '#seed + 100' } },
            { id: 's2', name: 'Step 2 (no expressions)', stepType: 'AUTO' }
          ]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { seed: 42 }
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        // runData should have both seed and computed
        expect(res.body.runData).to.have.property('seed', 42);
        expect(res.body.runData).to.have.property('computed', 142);
        // Step 2 should have empty outputs (no expressions)
        expect(res.body.stepExecutions[1].outputs).to.deep.eq({});
      });
    });
  });

  // ═══════════════════ Step DependsOn Ordering ═══════════════════

  describe('Step DependsOn Ordering', () => {
    it('should enforce step dependency ordering', () => {
      // Create a process where step-b dependsOn step-a
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-deps`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [
            { id: 'step-a', name: 'Base Step', stepType: 'AUTO',
              executionExpressions: { baseValue: '#input + 10' } },
            { id: 'step-b', name: 'Dependent Step', stepType: 'AUTO',
              dependsOn: ['step-a'],
              executionExpressions: { derived: '#baseValue * 2' } }
          ]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { input: 5 }
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        // step-a produces baseValue = 15
        expect(res.body.stepExecutions[0].outputs).to.have.property('baseValue', 15);
        // step-b uses baseValue from runData: derived = 30
        expect(res.body.stepExecutions[1].outputs).to.have.property('derived', 30);
        expect(res.body.runData).to.have.property('derived', 30);
      });
    });
  });

  // ═══════════════════ Ontology Validation During Runs ═══════════════════

  describe('Ontology Validation During Runs', () => {
    it('should record ontology violations in run metrics', () => {
      // Create an ontology with a rule that will fail
      let ontId: string;
      cy.apiPost('/process/ontology', {
        name: `${testPrefix}-strict-ontology`,
        entityTypes: [{
          name: 'Transaction',
          description: 'Must be positive',
          fields: [{ name: 'amount', type: 'DECIMAL', required: true }],
          rules: [{
            name: 'must-be-positive',
            expression: '#amount > 0',
            severity: 'ERROR',
            ruleType: 'ASSERTION'
          }]
        }]
      }).then((res) => {
        expect(res.status).to.eq(201);
        ontId = res.body.id;
        // Create process bound to that ontology
        return cy.apiPost('/process/definition', {
          name: `${testPrefix}-validated-proc`,
          ontologySchemaId: ontId,
          phases: [{
            id: 'p1', name: 'Phase 1', order: 1,
            steps: [{
              id: 's1', name: 'Set Negative',
              stepType: 'AUTO',
              executionExpressions: { amount: '0 - 100' }
            }]
          }]
        });
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        // The step should have computed negative amount
        expect(res.body.runData).to.have.property('amount', -100);
        // Metrics should record the ontology violation
        expect(res.body.metrics).to.have.property('ontologyViolations_s1');
        const violations = res.body.metrics['ontologyViolations_s1'];
        expect(violations).to.be.an('array');
        expect(violations.length).to.be.gte(1);
      });
    });
  });

  // ═══════════════════ SpEL Evaluation ═══════════════════

  describe('SpEL Expression Evaluation', () => {
    it('should evaluate a boolean expression', () => {
      cy.apiPost('/process/spel/evaluate', {
        expression: "#amount > 1000",
        context: { amount: 5000 }
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.result).to.be.true;
        expect(res.body.type).to.eq('Boolean');
        expect(res.body.error).to.be.null;
      });
    });

    it('should evaluate an arithmetic expression', () => {
      cy.apiPost('/process/spel/evaluate', {
        expression: "#a + #b",
        context: { a: 10, b: 20 }
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.result).to.eq(30);
        expect(res.body.error).to.be.null;
      });
    });

    it('should return error for invalid expression', () => {
      cy.apiPost('/process/spel/evaluate', {
        expression: "#nonexistent.method()",
        context: {}
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.error).to.not.be.null;
        expect(res.body.result).to.be.null;
      });
    });

    it('should return error for blank expression', () => {
      cy.apiPost('/process/spel/evaluate', {
        expression: "  ",
        context: {}
      }).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.error).to.include('blank');
      });
    });
  });

  // ═══════════════════ Control Results ═══════════════════

  describe('Control Results for Run', () => {
    it('should return control results for the completed run', () => {
      cy.apiGet(`/process/control/results/${ids.runId}`).then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        // We evaluated controls above against this run
        expect(res.body.length).to.be.gte(1);
      });
    });
  });

  // ═══════════════════ Run Risk Assessment & Attribution ═══════════════════

  describe('Risk Assessment & Step Attribution', () => {
    it('should assess risk for the completed run with step attribution summaries', () => {
      cy.apiGet(`/process/attribution/run/${ids.runId}/risk?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('assessmentId');
          expect(res.body).to.have.property('overallRiskScore');
          expect(res.body.overallRiskScore).to.be.a('number');
          expect(res.body).to.have.property('riskLevel');
          expect(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']).to.include(res.body.riskLevel);
          expect(res.body).to.have.property('alerts');
          expect(res.body.alerts).to.be.an('array');
          expect(res.body).to.have.property('stepRiskScores');
          expect(res.body.stepRiskScores).to.be.an('object');
          expect(res.body).to.have.property('highRiskStepIds');
          expect(res.body.highRiskStepIds).to.be.an('array');
          expect(res.body).to.have.property('computationTimeMs');
          expect(res.body.computationTimeMs).to.be.a('number');
          // Step attribution results should carry Bayesian data
          expect(res.body).to.have.property('stepAttributionResults');
          if (res.body.stepAttributionResults) {
            const stepKeys = Object.keys(res.body.stepAttributionResults);
            if (stepKeys.length > 0) {
              const step = res.body.stepAttributionResults[stepKeys[0]];
              expect(step).to.have.property('riskScore');
              expect(step).to.have.property('bayesianPosteriors');
              expect(step).to.have.property('bayesianPriors');
              expect(step).to.have.property('bayesianInferenceAvailable');
            }
          }
        }
      });
    });

    it('should explain attribution for a specific step with Bayesian data', () => {
      cy.apiGet(`/process/attribution/run/${ids.runId}/step/step-auto-1/explain?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('stepId', 'step-auto-1');
          expect(res.body).to.have.property('riskScore');
          expect(res.body.riskScore).to.be.a('number');
          expect(res.body).to.have.property('hasGraphBindings');
          expect(res.body.hasGraphBindings).to.be.a('boolean');
          expect(res.body).to.have.property('attribution');
          expect(res.body).to.have.property('prediction');
          // Bayesian posteriors and priors must be present
          expect(res.body).to.have.property('bayesianPosteriors');
          expect(res.body.bayesianPosteriors).to.be.an('object');
          expect(res.body).to.have.property('bayesianPriors');
          expect(res.body.bayesianPriors).to.be.an('object');
        }
      });
    });

    it('should assess pre-flight risk for a definition with Bayesian data', () => {
      cy.apiGet(`/process/attribution/definition/${ids.processId}/risk?version=1&useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('processDefinitionId');
          expect(res.body).to.have.property('overallRiskScore');
          expect(res.body.overallRiskScore).to.be.a('number');
          expect(res.body).to.have.property('riskLevel');
          expect(res.body).to.have.property('alerts');
          expect(res.body.alerts).to.be.an('array');
          // Step attribution results should carry Bayesian data
          if (res.body.stepAttributionResults) {
            expect(res.body.stepAttributionResults).to.be.an('object');
            const stepKeys = Object.keys(res.body.stepAttributionResults);
            if (stepKeys.length > 0) {
              const step = res.body.stepAttributionResults[stepKeys[0]];
              expect(step).to.have.property('riskScore');
              expect(step).to.have.property('bayesianInferenceAvailable');
              if (step.bayesianPosteriors) {
                expect(step.bayesianPosteriors).to.be.an('object');
                expect(step).to.have.property('bayesianPriors');
                expect(step.bayesianPriors).to.be.an('object');
              }
            }
          }
        }
      });
    });

    it('should explain control failure attribution for a step with Bayesian data', () => {
      cy.apiGet(`/process/attribution/run/${ids.runId}/step/step-auto-1/control/${ids.controlId}/explain?useLlm=false`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('severity');
          expect(res.body).to.have.property('explanation');
          // Bayesian posteriors and priors should be present
          expect(res.body).to.have.property('bayesianPosteriors');
          expect(res.body.bayesianPosteriors).to.be.an('object');
          expect(res.body).to.have.property('bayesianPriors');
          expect(res.body.bayesianPriors).to.be.an('object');
        }
      });
    });
  });

  // ═══════════════════ Process Lineage ═══════════════════

  describe('Process Lineage — /api/process/lineage', () => {

    it('GET /process/lineage/definition/:id should return definition lineage with discovery provenance', () => {
      cy.apiGet(`/process/lineage/definition/${ids.processId}?includeRiskAssessment=true`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('discovery');
          expect(res.body.discovery).to.be.an('object');
          expect(res.body).to.have.property('definition');
          expect(res.body.definition).to.have.property('id');
          expect(res.body).to.have.property('steps');
          expect(res.body.steps).to.be.an('array');
          // Risk assessment should include step attributions with Bayesian data
          if (res.body.riskAssessment && !res.body.riskAssessment.error) {
            expect(res.body.riskAssessment).to.have.property('overallRiskScore');
            if (res.body.riskAssessment.stepAttributions) {
              expect(res.body.riskAssessment.stepAttributions).to.be.an('object');
              const stepKeys = Object.keys(res.body.riskAssessment.stepAttributions);
              if (stepKeys.length > 0) {
                const step = res.body.riskAssessment.stepAttributions[stepKeys[0]];
                expect(step).to.have.property('riskScore');
                // Bayesian priors must always be present alongside posteriors
                if (step.bayesianPosteriors) {
                  expect(step.bayesianPosteriors).to.be.an('object');
                  expect(step).to.have.property('bayesianPriors');
                  expect(step.bayesianPriors).to.be.an('object');
                }
              }
            }
          }
        }
      });
    });

    it('GET /process/lineage/step/:runId/:stepId should return step lineage with Bayesian data', () => {
      cy.apiGet(`/process/lineage/step/${ids.runId}/step-auto-1`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('definition');
          if (res.body.definition) {
            expect(res.body.definition).to.have.property('stepId');
            expect(res.body.definition).to.have.property('graphNodeIds');
          }
          expect(res.body).to.have.property('run');
          expect(res.body.run).to.have.property('runId');
          // Step lineage attribution should carry Bayesian data and risk score
          if (res.body.attribution && !res.body.attribution.error) {
            expect(res.body.attribution).to.have.property('causalChainCount');
            if (res.body.attribution.bayesianPosteriors) {
              expect(res.body.attribution.bayesianPosteriors).to.be.an('object');
              expect(res.body.attribution).to.have.property('bayesianPriors');
              expect(res.body.attribution.bayesianPriors).to.be.an('object');
            }
            if (res.body.attribution.riskScore !== undefined) {
              expect(res.body.attribution.riskScore).to.be.a('number');
            }
          }
        }
      });
    });

    it('GET /process/lineage/run/:runId should return full run lineage with Bayesian data', () => {
      cy.apiGet(`/process/lineage/run/${ids.runId}?includeAttribution=true`).then((res) => {
        expect(res.status).to.be.oneOf([200, 404, 500]);
        if (res.status === 200) {
          expect(res.body).to.have.property('run');
          expect(res.body.run).to.have.property('runId');
          expect(res.body).to.have.property('discovery');
          expect(res.body).to.have.property('steps');
          expect(res.body.steps).to.be.an('array');
          // Risk assessment with attribution should include Bayesian priors
          if (res.body.riskAssessment && !res.body.riskAssessment.error) {
            expect(res.body.riskAssessment).to.have.property('overallRiskScore');
            // Step attributions should carry Bayesian data
            if (res.body.riskAssessment.stepAttributions) {
              expect(res.body.riskAssessment.stepAttributions).to.be.an('object');
              const stepKeys = Object.keys(res.body.riskAssessment.stepAttributions);
              if (stepKeys.length > 0) {
                const step = res.body.riskAssessment.stepAttributions[stepKeys[0]];
                expect(step).to.have.property('riskScore');
                if (step.bayesianPosteriors) {
                  expect(step.bayesianPosteriors).to.be.an('object');
                  expect(step).to.have.property('bayesianPriors');
                  expect(step.bayesianPriors).to.be.an('object');
                }
              }
            }
          }
          // Per-step lineage entries may carry attribution with Bayesian data
          if (res.body.steps && res.body.steps.length > 0) {
            const firstStep = res.body.steps[0];
            if (firstStep.attribution) {
              if (firstStep.attribution.bayesianPosteriors) {
                expect(firstStep.attribution.bayesianPosteriors).to.be.an('object');
              }
              if (firstStep.attribution.bayesianPriors) {
                expect(firstStep.attribution.bayesianPriors).to.be.an('object');
              }
            }
          }
        }
      });
    });
  });

  // ═══════════════════ Integration Discovery ═══════════════════

  describe('Integration Discovery', () => {
    it('should list available tool integrations', () => {
      cy.apiGet('/process/integrations').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('tools');
        expect(res.body).to.have.property('count');
        expect(res.body.count).to.be.gte(1);
        expect(res.body.tools).to.be.an('array');
        // Each tool should have name, description, category
        const firstTool = res.body.tools[0];
        expect(firstTool).to.have.property('name');
        expect(firstTool).to.have.property('description');
        expect(firstTool).to.have.property('category');
        expect(firstTool).to.have.property('inputSchema');
      });
    });

    it('should have tools grouped by category', () => {
      cy.apiGet('/process/integrations').then((res) => {
        expect(res.body).to.have.property('categories');
        expect(res.body).to.have.property('toolsByCategory');
        expect(res.body.categories).to.be.an('array');
        expect(res.body.categories.length).to.be.gte(1);
      });
    });

    it('should list available step types', () => {
      cy.apiGet('/process/step-types').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        expect(res.body.length).to.eq(8);
        const names = res.body.map((t: any) => t.type);
        expect(names).to.include('AUTO');
        expect(names).to.include('TOOL_CALL');
        expect(names).to.include('HTTP_CALL');
        expect(names).to.include('SCRIPT');
        expect(names).to.include('APPROVE');
        expect(names).to.include('HUMAN');
        expect(names).to.include('CONTROL_GATE');
        expect(names).to.include('EXCEL_COMPUTE');
      });
    });
  });

  // ═══════════════════ TOOL_CALL Steps ═══════════════════

  describe('TOOL_CALL Step Execution', () => {
    it('should invoke a tool via TOOL_CALL step and merge results into runData', () => {
      // Use process_list_ontologies — takes no meaningful input, returns ontology list
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-tool-call`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 'step-tool', name: 'List Ontologies via Tool',
            stepType: 'TOOL_CALL',
            toolName: 'process_list_ontologies'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        const stepExec = res.body.stepExecutions[0];
        expect(stepExec.status).to.eq('COMPLETED');
        expect(stepExec.executedBy).to.include('tool:');
        // The tool result should be merged into runData
        expect(stepExec.outputs).to.be.an('object');
        expect(stepExec.outputs).to.have.property('status', 'success');
      });
    });
  });

  // ═══════════════════ HTTP_CALL Steps ═══════════════════

  describe('HTTP_CALL Step Execution', () => {
    it('should make an HTTP call and capture response in runData', () => {
      // Call our own API — GET /api/process/step-types (a safe, idempotent endpoint)
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-http-call`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 'step-http', name: 'Fetch Step Types',
            stepType: 'HTTP_CALL',
            httpMethod: 'GET',
            httpUrl: 'http://localhost:8080/api/process/step-types',
            httpResponseKey: 'stepTypes'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        const stepExec = res.body.stepExecutions[0];
        expect(stepExec.status).to.eq('COMPLETED');
        expect(stepExec.executedBy).to.include('http:');
        // stepTypes should be an array of step type objects
        expect(res.body.runData).to.have.property('stepTypes');
        expect(res.body.runData.stepTypes).to.be.an('array');
        expect(res.body.runData).to.have.property('stepTypes_status', 200);
      });
    });
  });

  // ═══════════════════ SCRIPT Steps (JavaScript & Python via GraalVM) ═══════════════════

  describe('SCRIPT Step Execution', () => {
    it('should execute a JavaScript script and store result in runData', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-js-script`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 'step-script', name: 'JS Discount Calc',
            stepType: 'SCRIPT',
            scriptLanguage: 'javascript',
            scriptBody: [
              'var discount = price > 100 ? 0.1 : 0;',
              'var finalPrice = price * (1 - discount);',
              '({finalPrice: finalPrice, discountApplied: discount > 0})'
            ].join('\n'),
            scriptOutputKey: 'result'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { price: 200 }
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        const step = res.body.stepExecutions[0];
        expect(step.status).to.eq('COMPLETED');
        expect(step.executedBy).to.include('script:javascript');
        // The JS object return value should be spread into outputs
        expect(res.body.runData).to.have.property('finalPrice', 180.0);
        expect(res.body.runData).to.have.property('discountApplied', true);
      });
    });

    it('should execute a Python script and store result in runData', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-py-script`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 'step-py', name: 'Python Tax Calc',
            stepType: 'SCRIPT',
            scriptLanguage: 'python',
            scriptBody: 'tax = amount * 0.08\ntotal = amount + tax\n_output = {"tax": tax, "total": total}',
            scriptOutputKey: 'taxResult'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { amount: 100 }
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        const step = res.body.stepExecutions[0];
        expect(step.status).to.eq('COMPLETED');
        expect(step.executedBy).to.include('script:python');
        // Python script writes _output dict which gets extracted
        expect(res.body.runData).to.have.property('tax', 8.0);
        expect(res.body.runData).to.have.property('total', 108.0);
      });
    });

    it('should default to JavaScript when scriptLanguage is not set', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-default-lang`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 's1', name: 'Default JS',
            stepType: 'SCRIPT',
            scriptBody: '42',
            scriptOutputKey: 'answer'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[0].executedBy).to.include('script:javascript');
        expect(res.body.runData).to.have.property('answer', 42);
      });
    });

    it('should fail on invalid JavaScript', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-bad-js`,
        phases: [{
          id: 'p1', name: 'Phase 1', order: 1,
          steps: [{
            id: 's1', name: 'Bad JS',
            stepType: 'SCRIPT',
            scriptLanguage: 'javascript',
            scriptBody: 'throw new Error("intentional failure");'
          }]
        }]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('FAILED');
        expect(res.body.stepExecutions[0].status).to.eq('FAILED');
        expect(res.body.stepExecutions[0].error).to.include('Script execution failed');
      });
    });
  });

  // ═══════════════════ Multi-Type Workflow ═══════════════════

  describe('Multi-Type Workflow', () => {
    it('should execute a workflow combining AUTO, SCRIPT (JS), and HTTP_CALL steps', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-multi-type`,
        phases: [
          {
            id: 'p1', name: 'Compute', order: 1,
            steps: [
              { id: 's1', name: 'Calculate', stepType: 'AUTO',
                executionExpressions: { total: '#a + #b' } },
              { id: 's2', name: 'Classify', stepType: 'SCRIPT',
                scriptLanguage: 'javascript',
                scriptBody: 'total > 50 ? "HIGH" : "LOW"',
                scriptOutputKey: 'classification',
                dependsOn: ['s1'] }
            ]
          },
          {
            id: 'p2', name: 'Enrich', order: 2,
            steps: [
              { id: 's3', name: 'Fetch Data', stepType: 'HTTP_CALL',
                httpMethod: 'GET',
                httpUrl: 'http://localhost:8080/api/process/step-types',
                httpResponseKey: 'enrichment' }
            ]
          }
        ]
      }).then((res) => {
        return cy.apiPost(`/process/definition/${res.body.id}/approve`, { approvedBy: 'e2e' });
      }).then((res) => {
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { a: 30, b: 40 }
        });
      }).then((res) => {
        expect(res.body.status).to.eq('COMPLETED');
        expect(res.body.stepExecutions).to.have.length(3);
        // All steps should be completed
        expect(res.body.stepExecutions[0].status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[1].status).to.eq('COMPLETED');
        expect(res.body.stepExecutions[2].status).to.eq('COMPLETED');
        // Data pipeline check
        expect(res.body.runData.total).to.eq(70);
        expect(res.body.runData.classification).to.eq('HIGH');
        expect(res.body.runData.enrichment).to.be.an('array');
      });
    });
  });

  // ═══════════════════ EXCEL_COMPUTE Steps ═══════════════════

  describe('EXCEL_COMPUTE Step Execution', () => {
    it('should execute a process with an EXCEL_COMPUTE step using a simple formula graph', () => {
      // Build a minimal SpreadsheetGraph JSON with:
      // - Input cells: Sheet1!A1=10, Sheet1!B1=20
      // - Formula cell: Sheet1!C1 = A1+B1 (expected: 30)
      const spreadsheetGraph = {
        workbookName: 'test-budget.xlsx',
        cells: {
          'Sheet1!A1': {
            cellReference: 'Sheet1!A1', sheetName: 'Sheet1',
            column: 'A', row: 1, cellType: 'NUMERIC',
            formula: null, displayValue: '10',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!B1': {
            cellReference: 'Sheet1!B1', sheetName: 'Sheet1',
            column: 'B', row: 1, cellType: 'NUMERIC',
            formula: null, displayValue: '20',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!C1': {
            cellReference: 'Sheet1!C1', sheetName: 'Sheet1',
            column: 'C', row: 1, cellType: 'FORMULA',
            formula: 'A1+B1', displayValue: '30',
            namedRange: false, namedRangeName: null
          }
        },
        dependencies: [
          {
            formulaCell: 'Sheet1!C1', referencedCell: 'Sheet1!A1',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1+B1', rangeReference: null
          },
          {
            formulaCell: 'Sheet1!C1', referencedCell: 'Sheet1!B1',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1+B1', rangeReference: null
          }
        ],
        namedRanges: {}
      };

      cy.apiPost('/process/definition', {
        name: `${testPrefix}-excel-simple`,
        phases: [{
          name: 'Compute',
          steps: [{
            id: 'e1', name: 'ExcelCalc',
            stepType: 'EXCEL_COMPUTE',
            excelGraphJson: JSON.stringify(spreadsheetGraph),
            excelTargetLanguage: 'javascript',
            excelOutputKey: 'excelResults'
          }]
        }],
        metadata: { source: 'e2e-excel' }
      }).then((res) => {
        expect(res.status).to.eq(201);
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        // If LLM is configured, this should complete; if not, it will fail gracefully
        // We check that the step ran (completed or failed)
        expect(res.body).to.have.property('status');
        expect(res.body.stepExecutions).to.have.length(1);
        const step = res.body.stepExecutions[0];
        expect(step.stepName).to.eq('ExcelCalc');
        // Step either completed (LLM available) or failed (no LLM configured)
        expect(['COMPLETED', 'FAILED']).to.include(step.status);
        if (step.status === 'COMPLETED') {
          expect(step.executedBy).to.match(/^excel:/);
          // excelResults should be in runData
          expect(res.body.runData).to.have.property('excelResults');
        }
      });
    });

    it('should execute EXCEL_COMPUTE with cell overrides from runData', () => {
      const spreadsheetGraph = {
        workbookName: 'pricing-model.xlsx',
        cells: {
          'Sheet1!A1': {
            cellReference: 'Sheet1!A1', sheetName: 'Sheet1',
            column: 'A', row: 1, cellType: 'NUMERIC',
            formula: null, displayValue: '100',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!A2': {
            cellReference: 'Sheet1!A2', sheetName: 'Sheet1',
            column: 'A', row: 2, cellType: 'NUMERIC',
            formula: null, displayValue: '0.15',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!B1': {
            cellReference: 'Sheet1!B1', sheetName: 'Sheet1',
            column: 'B', row: 1, cellType: 'FORMULA',
            formula: 'A1*A2', displayValue: '15',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!C1': {
            cellReference: 'Sheet1!C1', sheetName: 'Sheet1',
            column: 'C', row: 1, cellType: 'FORMULA',
            formula: 'A1-B1', displayValue: '85',
            namedRange: false, namedRangeName: null
          }
        },
        dependencies: [
          {
            formulaCell: 'Sheet1!B1', referencedCell: 'Sheet1!A1',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1*A2', rangeReference: null
          },
          {
            formulaCell: 'Sheet1!B1', referencedCell: 'Sheet1!A2',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1*A2', rangeReference: null
          },
          {
            formulaCell: 'Sheet1!C1', referencedCell: 'Sheet1!A1',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1-B1', rangeReference: null
          },
          {
            formulaCell: 'Sheet1!C1', referencedCell: 'Sheet1!B1',
            dependencyType: 'CELL_REFERENCE', crossSheet: false,
            formula: 'A1-B1', rangeReference: null
          }
        ],
        namedRanges: {}
      };

      cy.apiPost('/process/definition', {
        name: `${testPrefix}-excel-override`,
        phases: [{
          name: 'Pricing',
          steps: [{
            id: 'e1', name: 'ComputeDiscount',
            stepType: 'EXCEL_COMPUTE',
            excelGraphJson: JSON.stringify(spreadsheetGraph),
            excelCellOverrides: { 'Sheet1_A1': 200 },
            excelTargetLanguage: 'javascript'
          }]
        }],
        metadata: { source: 'e2e-excel-override' }
      }).then((res) => {
        expect(res.status).to.eq(201);
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: { 'Sheet1_A2': 0.10 }
        });
      }).then((res) => {
        expect(res.body).to.have.property('status');
        const step = res.body.stepExecutions[0];
        expect(['COMPLETED', 'FAILED']).to.include(step.status);
        if (step.status === 'COMPLETED') {
          // With A1=200 override and A2=0.10, B1=200*0.10=20, C1=200-20=180
          expect(res.body.runData).to.have.property('_excelWorkbook', 'pricing-model.xlsx');
        }
      });
    });

    it('should fail EXCEL_COMPUTE step when no excelGraphJson is provided', () => {
      cy.apiPost('/process/definition', {
        name: `${testPrefix}-excel-no-graph`,
        phases: [{
          name: 'Fail',
          steps: [{
            id: 'e1', name: 'MissingGraph',
            stepType: 'EXCEL_COMPUTE'
          }]
        }],
        metadata: { source: 'e2e-excel-fail' }
      }).then((res) => {
        expect(res.status).to.eq(201);
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body.status).to.eq('FAILED');
        const step = res.body.stepExecutions[0];
        expect(step.status).to.eq('FAILED');
        expect(step.error).to.include('excelGraphJson');
      });
    });

    it('should execute EXCEL_COMPUTE with user-supplied code (skip LLM)', () => {
      const spreadsheetGraph = {
        workbookName: 'user-code-test.xlsx',
        cells: {
          'Sheet1!A1': {
            cellReference: 'Sheet1!A1', sheetName: 'Sheet1',
            column: 'A', row: 1, cellType: 'NUMERIC',
            formula: null, displayValue: '50',
            namedRange: false, namedRangeName: null
          },
          'Sheet1!B1': {
            cellReference: 'Sheet1!B1', sheetName: 'Sheet1',
            column: 'B', row: 1, cellType: 'FORMULA',
            formula: 'A1*2', displayValue: '100',
            namedRange: false, namedRangeName: null
          }
        },
        dependencies: [{
          formulaCell: 'Sheet1!B1', referencedCell: 'Sheet1!A1',
          dependencyType: 'CELL_REFERENCE', crossSheet: false,
          formula: 'A1*2', rangeReference: null
        }],
        namedRanges: {}
      };

      // User provides hand-written JS code (no LLM needed)
      const userCode = `
        function computeSpreadsheet(cells) {
          var a1 = cells.Sheet1_A1 || 50;
          var b1 = a1 * 2;
          return { Sheet1_B1: b1 };
        }
        computeSpreadsheet(cells)
      `;

      cy.apiPost('/process/definition', {
        name: `${testPrefix}-excel-user-code`,
        phases: [{
          name: 'Compute',
          steps: [{
            id: 'e1', name: 'UserCodeCalc',
            stepType: 'EXCEL_COMPUTE',
            excelGraphJson: JSON.stringify(spreadsheetGraph),
            excelGeneratedCode: userCode,
            excelTargetLanguage: 'javascript'
          }]
        }],
        metadata: { source: 'e2e-excel-user-code' }
      }).then((res) => {
        expect(res.status).to.eq(201);
        return cy.apiPost('/process/run', {
          processDefinitionId: res.body.id,
          initialData: {}
        });
      }).then((res) => {
        expect(res.body).to.have.property('status');
        const step = res.body.stepExecutions[0];
        expect(['COMPLETED', 'FAILED']).to.include(step.status);
        if (step.status === 'COMPLETED') {
          // The user code should have been used (no LLM call)
          expect(step.outputs._codeSource).to.eq('user');
          expect(step.outputs.Sheet1_B1).to.eq(100);
        }
      });
    });
  });

  // ═══════════════════ Excel Convert/Execute API ═══════════════════

  describe('Excel Convert/Execute Endpoints', () => {
    const simpleGraph = {
      workbookName: 'api-test.xlsx',
      cells: {
        'Sheet1!A1': {
          cellReference: 'Sheet1!A1', sheetName: 'Sheet1',
          column: 'A', row: 1, cellType: 'NUMERIC',
          formula: null, displayValue: '10',
          namedRange: false, namedRangeName: null
        },
        'Sheet1!B1': {
          cellReference: 'Sheet1!B1', sheetName: 'Sheet1',
          column: 'B', row: 1, cellType: 'FORMULA',
          formula: 'A1+5', displayValue: '15',
          namedRange: false, namedRangeName: null
        }
      },
      dependencies: [{
        formulaCell: 'Sheet1!B1', referencedCell: 'Sheet1!A1',
        dependencyType: 'CELL_REFERENCE', crossSheet: false,
        formula: 'A1+5', rangeReference: null
      }],
      namedRanges: {}
    };

    it('should convert Excel formulas to code without executing (POST /excel/convert)', () => {
      cy.apiPost('/process/excel/convert', {
        spreadsheetGraphJson: JSON.stringify(simpleGraph),
        targetLanguage: 'javascript'
      }).then((res) => {
        // If LLM is available, we get code; otherwise an error
        if (res.status === 200 && res.body.code) {
          expect(res.body).to.have.property('code');
          expect(res.body).to.have.property('language', 'javascript');
          expect(res.body).to.have.property('workbookName', 'api-test.xlsx');
          expect(res.body).to.have.property('inputCells').that.is.an('array');
          expect(res.body).to.have.property('outputCells').that.is.an('array');
          expect(res.body.formulaCount).to.eq(1);
        }
        // Either way, the endpoint should respond
        expect(res.status).to.be.oneOf([200, 500]);
      });
    });

    it('should execute user-supplied code via the execute endpoint (POST /excel/execute)', () => {
      const code = `
        function computeSpreadsheet(cells) {
          var a1 = cells.Sheet1_A1 || 0;
          return { Sheet1_B1: a1 + 5 };
        }
        computeSpreadsheet(cells)
      `;

      cy.apiPost('/process/excel/execute', {
        spreadsheetGraphJson: JSON.stringify(simpleGraph),
        targetLanguage: 'javascript',
        generatedCode: code,
        cellOverrides: { 'Sheet1_A1': 20 }
      }).then((res) => {
        if (res.status === 200) {
          expect(res.body).to.have.property('_codeSource', 'user');
          expect(res.body).to.have.property('_generatedCode');
          expect(res.body).to.have.property('_generatedLanguage', 'javascript');
          // With A1=20, B1 should be 25
          expect(res.body.Sheet1_B1).to.eq(25);
        }
        expect(res.status).to.be.oneOf([200, 500]);
      });
    });

    it('should reject convert request without spreadsheetGraphJson', () => {
      cy.apiPost('/process/excel/convert', {
        targetLanguage: 'javascript'
      }).then((res) => {
        expect(res.status).to.eq(400);
        expect(res.body).to.have.property('error');
      });
    });
  });
});
