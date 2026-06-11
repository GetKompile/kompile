/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  ProcessEngineService,
  OntologySchema,
  EntityTypeDefinition,
  ValidationRule,
  ProcessDefinition,
  ProcessPhase,
  ProcessStep,
  WorkflowRun,
  StepExecution,
  ApprovalRequest,
  ApprovalResponse,
  ControlAttestation,
  SubmissionManifest
} from './process-engine.service';
import { backendUrl } from './base.service';

// ─── Test data helpers ────────────────────────────────────────────────────────

function makeOntologySchema(overrides: Partial<OntologySchema> = {}): OntologySchema {
  return {
    id: 'schema-001',
    name: 'Financial Compliance Schema',
    version: 1,
    entityTypes: [
      {
        name: 'Transaction',
        description: 'A financial transaction',
        fields: [
          { name: 'amount', type: 'number', required: true },
          { name: 'currency', type: 'string', required: true }
        ]
      }
    ],
    relationshipTypes: [
      {
        name: 'INVOLVES',
        sourceEntityType: 'Transaction',
        targetEntityType: 'Party',
        cardinality: 'MANY_TO_MANY'
      }
    ],
    globalRules: [],
    metadata: {},
    ...overrides
  };
}

function makeProcessDefinition(overrides: Partial<ProcessDefinition> = {}): ProcessDefinition {
  const phase: ProcessPhase = {
    id: 'phase-1',
    name: 'Validation',
    order: 1,
    steps: [
      {
        id: 'step-1',
        name: 'Auto-validate transaction',
        stepType: 'AUTO',
        inputKeys: ['transaction'],
        outputKeys: ['validationResult']
      } as ProcessStep,
      {
        id: 'step-2',
        name: 'Human review',
        stepType: 'APPROVE',
        inputKeys: ['validationResult'],
        approvalPolicy: {
          approverPool: ['compliance-team'],
          mode: 'ANY',
          dollarThreshold: 10000
        }
      } as ProcessStep
    ]
  };

  return {
    id: 'proc-001',
    name: 'Transaction Review Process',
    version: 1,
    ontologySchemaId: 'schema-001',
    ontologyVersion: 1,
    status: 'DRAFT',
    phases: [phase],
    agentSpecs: [],
    metadata: {},
    ...overrides
  };
}

function makeWorkflowRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 'run-001',
    processDefinitionId: 'proc-001',
    processVersion: 1,
    status: 'RUNNING',
    startedAt: '2026-05-17T10:00:00Z',
    stepExecutions: [],
    pendingApprovals: [],
    controlResults: [],
    runData: {},
    metrics: {},
    ...overrides
  };
}

function makeApprovalRequest(overrides: Partial<ApprovalRequest> = {}): ApprovalRequest {
  return {
    id: 'approval-001',
    workflowRunId: 'run-001',
    stepId: 'step-2',
    stepName: 'Human review',
    status: 'PENDING',
    createdAt: '2026-05-17T10:05:00Z',
    slaDeadline: '2026-05-18T10:05:00Z',
    assignedTo: 'compliance-team',
    items: [],
    context: {},
    ...overrides
  };
}

function makeApprovalResponse(overrides: Partial<ApprovalResponse> = {}): ApprovalResponse {
  return {
    requestId: 'approval-001',
    respondedBy: 'alice@example.com',
    respondedAt: '2026-05-17T11:00:00Z',
    action: 'APPROVE',
    comment: 'Looks good',
    ...overrides
  };
}

function makeControlAttestation(overrides: Partial<ControlAttestation> = {}): ControlAttestation {
  return {
    id: 'attest-001',
    controlId: 'ctrl-001',
    workflowRunId: 'run-001',
    evaluatedAt: '2026-05-17T10:10:00Z',
    passed: true,
    expressionEvaluated: 'amount < 1000000',
    inputValues: { amount: 500 },
    inputHash: 'sha256:abc123',
    evaluatedBy: 'system',
    ...overrides
  };
}

function makeSubmissionManifest(overrides: Partial<SubmissionManifest> = {}): SubmissionManifest {
  return {
    id: 'manifest-001',
    workflowRunId: 'run-001',
    sourceRegion: 'us-east-1',
    authoritativeFileId: 'file-001',
    authoritativeFileName: 'report-final.pdf',
    fileContentHash: 'sha256:deadbeef',
    versionAssertedBy: 'alice@example.com',
    ...overrides
  };
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('ProcessEngineService', () => {
  let service: ProcessEngineService;
  let httpMock: HttpTestingController;
  const baseUrl = `${backendUrl}/process`;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [ProcessEngineService]
    });
    service = TestBed.inject(ProcessEngineService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── Ontology ───────────────────────────────────────────────────────────────

  describe('createOntology()', () => {
    it('should POST to /process/ontology and return the created schema', () => {
      const input = makeOntologySchema({ id: undefined });
      const mockResponse = makeOntologySchema();

      service.createOntology(input).subscribe(result => {
        expect(result.id).toBe('schema-001');
        expect(result.name).toBe('Financial Compliance Schema');
        expect(result.entityTypes?.length).toBe(1);
      });

      const req = httpMock.expectOne(`${baseUrl}/ontology`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.name).toBe('Financial Compliance Schema');
      req.flush(mockResponse);
    });

    it('should handle 400 when schema is invalid', () => {
      const input = makeOntologySchema({ name: '' });

      service.createOntology(input).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/ontology`);
      req.flush({ error: 'Schema name is required' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  describe('getOntology()', () => {
    it('should GET /process/ontology/:id with version param and return the schema', () => {
      const mockSchema = makeOntologySchema();

      service.getOntology('schema-001', 1).subscribe(result => {
        expect(result.id).toBe('schema-001');
        expect(result.version).toBe(1);
        expect(result.entityTypes?.[0].name).toBe('Transaction');
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/ontology/schema-001` && r.params.get('version') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockSchema);
    });

    it('should default version to 1 when not specified', () => {
      const mockSchema = makeOntologySchema();

      service.getOntology('schema-001').subscribe();

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/ontology/schema-001` && r.params.get('version') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockSchema);
    });

    it('should handle 404 when schema does not exist', () => {
      service.getOntology('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(r => r.url === `${baseUrl}/ontology/nonexistent`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  describe('updateOntology()', () => {
    it('should PUT to /process/ontology/:id and return the updated schema', () => {
      const update = makeOntologySchema({ name: 'Updated Schema' });
      const mockResponse = makeOntologySchema({ name: 'Updated Schema', version: 2 });

      service.updateOntology('schema-001', update).subscribe(result => {
        expect(result.name).toBe('Updated Schema');
        expect(result.version).toBe(2);
      });

      const req = httpMock.expectOne(`${baseUrl}/ontology/schema-001`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body.name).toBe('Updated Schema');
      req.flush(mockResponse);
    });
  });

  describe('validateData()', () => {
    it('should POST to /process/ontology/:id/validate with entityType and version params', () => {
      const data = { amount: 500, currency: 'USD' };
      const mockRules: ValidationRule[] = [];

      service.validateData('schema-001', 'Transaction', 1, data).subscribe(result => {
        expect(Array.isArray(result)).toBeTrue();
        expect(result.length).toBe(0);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/ontology/schema-001/validate` &&
        r.params.get('entityType') === 'Transaction' &&
        r.params.get('version') === '1'
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body.amount).toBe(500);
      req.flush(mockRules);
    });

    it('should return violated rules when data fails validation', () => {
      const data = { amount: -1 };
      const mockRules: ValidationRule[] = [
        { name: 'positiveAmount', type: 'RANGE', severity: 'ERROR', expression: 'amount > 0' }
      ];

      service.validateData('schema-001', 'Transaction', 1, data).subscribe(result => {
        expect(result.length).toBe(1);
        expect(result[0].name).toBe('positiveAmount');
        expect(result[0].severity).toBe('ERROR');
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/ontology/schema-001/validate`
      );
      req.flush(mockRules);
    });
  });

  // ── Process Definitions ────────────────────────────────────────────────────

  describe('createProcess()', () => {
    it('should POST to /process/definition and return the created process definition', () => {
      const input = makeProcessDefinition({ id: undefined, status: undefined });
      const mockResponse = makeProcessDefinition();

      service.createProcess(input).subscribe(result => {
        expect(result.id).toBe('proc-001');
        expect(result.name).toBe('Transaction Review Process');
        expect(result.status).toBe('DRAFT');
        expect(result.phases?.length).toBe(1);
      });

      const req = httpMock.expectOne(`${baseUrl}/definition`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.name).toBe('Transaction Review Process');
      req.flush(mockResponse);
    });

    it('should handle 422 when process definition references unknown ontology', () => {
      const input = makeProcessDefinition({ ontologySchemaId: 'nonexistent-schema' });

      service.createProcess(input).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/definition`);
      req.flush(
        { error: 'Ontology schema not found' },
        { status: 422, statusText: 'Unprocessable Entity' }
      );
    });
  });

  describe('getProcess()', () => {
    it('should GET /process/definition/:id with version param and return the definition', () => {
      const mockDef = makeProcessDefinition();

      service.getProcess('proc-001', 1).subscribe(result => {
        expect(result.id).toBe('proc-001');
        expect(result.version).toBe(1);
        expect(result.phases?.[0].steps?.length).toBe(2);
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/definition/proc-001` && r.params.get('version') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockDef);
    });

    it('should default version to 1 when not specified', () => {
      service.getProcess('proc-001').subscribe();

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/definition/proc-001` && r.params.get('version') === '1'
      );
      expect(req.request.method).toBe('GET');
      req.flush(makeProcessDefinition());
    });
  });

  describe('approveProcess()', () => {
    it('should POST to /process/definition/:id/approve with approvedBy and return updated definition', () => {
      const mockResponse = makeProcessDefinition({ status: 'APPROVED', approvedBy: 'bob@example.com' });

      service.approveProcess('proc-001', 'bob@example.com').subscribe(result => {
        expect(result.status).toBe('APPROVED');
        expect(result.approvedBy).toBe('bob@example.com');
      });

      const req = httpMock.expectOne(`${baseUrl}/definition/proc-001/approve`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.approvedBy).toBe('bob@example.com');
      req.flush(mockResponse);
    });

    it('should handle 403 when approver lacks permission', () => {
      service.approveProcess('proc-001', 'unauthorized@example.com').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/definition/proc-001/approve`);
      req.flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    });
  });

  // ── Workflow Runs ──────────────────────────────────────────────────────────

  describe('startRun()', () => {
    it('should POST to /process/run with processDefinitionId and initialData', () => {
      const initialData = { transactionId: 'txn-abc', amount: 5000 };
      const mockRun = makeWorkflowRun({ runData: initialData });

      service.startRun('proc-001', initialData).subscribe(result => {
        expect(result.id).toBe('run-001');
        expect(result.processDefinitionId).toBe('proc-001');
        expect(result.status).toBe('RUNNING');
      });

      const req = httpMock.expectOne(`${baseUrl}/run`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.processDefinitionId).toBe('proc-001');
      expect(req.request.body.initialData.transactionId).toBe('txn-abc');
      req.flush(mockRun);
    });

    it('should send empty initialData when not provided', () => {
      const mockRun = makeWorkflowRun();

      service.startRun('proc-001').subscribe();

      const req = httpMock.expectOne(`${baseUrl}/run`);
      expect(req.request.body.initialData).toEqual({});
      req.flush(mockRun);
    });

    it('should handle 404 when process definition does not exist', () => {
      service.startRun('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/run`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  describe('getRun()', () => {
    it('should GET /process/run/:id and return the workflow run', () => {
      const mockRun = makeWorkflowRun({ status: 'COMPLETED', completedAt: '2026-05-17T12:00:00Z' });

      service.getRun('run-001').subscribe(result => {
        expect(result.id).toBe('run-001');
        expect(result.status).toBe('COMPLETED');
        expect(result.completedAt).toBe('2026-05-17T12:00:00Z');
      });

      const req = httpMock.expectOne(`${baseUrl}/run/run-001`);
      expect(req.request.method).toBe('GET');
      req.flush(mockRun);
    });

    it('should handle 404 when run does not exist', () => {
      service.getRun('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/run/nonexistent`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  describe('listActiveRuns()', () => {
    it('should GET /process/run/active and return a list of active workflow runs', () => {
      const mockRuns: WorkflowRun[] = [
        makeWorkflowRun({ id: 'run-001' }),
        makeWorkflowRun({ id: 'run-002', processDefinitionId: 'proc-002' })
      ];

      service.listActiveRuns().subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].id).toBe('run-001');
        expect(result[1].processDefinitionId).toBe('proc-002');
      });

      const req = httpMock.expectOne(`${baseUrl}/run/active`);
      expect(req.request.method).toBe('GET');
      req.flush(mockRuns);
    });

    it('should return empty list when no runs are active', () => {
      service.listActiveRuns().subscribe(result => {
        expect(result.length).toBe(0);
      });

      const req = httpMock.expectOne(`${baseUrl}/run/active`);
      req.flush([]);
    });
  });

  describe('resumeAfterApproval()', () => {
    it('should POST to /process/run/:runId/resume with the approval response', () => {
      const response = makeApprovalResponse();
      const mockRun = makeWorkflowRun({ status: 'RUNNING' });

      service.resumeAfterApproval('run-001', response).subscribe(result => {
        expect(result.id).toBe('run-001');
        expect(result.status).toBe('RUNNING');
      });

      const req = httpMock.expectOne(`${baseUrl}/run/run-001/resume`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.requestId).toBe('approval-001');
      expect(req.request.body.action).toBe('APPROVE');
      req.flush(mockRun);
    });
  });

  // ── Approvals ──────────────────────────────────────────────────────────────

  describe('getPendingApprovals()', () => {
    it('should GET /process/approval/pending and return pending approval requests', () => {
      const mockApprovals: ApprovalRequest[] = [
        makeApprovalRequest({ id: 'approval-001' }),
        makeApprovalRequest({ id: 'approval-002', stepId: 'step-3' })
      ];

      service.getPendingApprovals().subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].id).toBe('approval-001');
        expect(result[1].stepId).toBe('step-3');
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/approval/pending` && !r.params.has('assignedTo')
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockApprovals);
    });

    it('should include assignedTo param when provided', () => {
      const mockApprovals: ApprovalRequest[] = [
        makeApprovalRequest({ assignedTo: 'alice@example.com' })
      ];

      service.getPendingApprovals('alice@example.com').subscribe(result => {
        expect(result.length).toBe(1);
        expect(result[0].assignedTo).toBe('alice@example.com');
      });

      const req = httpMock.expectOne(r =>
        r.url === `${baseUrl}/approval/pending` &&
        r.params.get('assignedTo') === 'alice@example.com'
      );
      expect(req.request.method).toBe('GET');
      req.flush(mockApprovals);
    });

    it('should return empty list when no approvals are pending', () => {
      service.getPendingApprovals().subscribe(result => {
        expect(result.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url === `${baseUrl}/approval/pending`);
      req.flush([]);
    });
  });

  describe('getApprovalRequest()', () => {
    it('should GET /process/approval/:id and return the approval request', () => {
      const mockApproval = makeApprovalRequest();

      service.getApprovalRequest('approval-001').subscribe(result => {
        expect(result.id).toBe('approval-001');
        expect(result.status).toBe('PENDING');
        expect(result.stepName).toBe('Human review');
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/approval-001`);
      expect(req.request.method).toBe('GET');
      req.flush(mockApproval);
    });

    it('should handle 404 when approval request does not exist', () => {
      service.getApprovalRequest('nonexistent').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/nonexistent`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  describe('submitApproval()', () => {
    it('should POST to /process/approval/:id/respond with an approval response and return the updated run', () => {
      const response = makeApprovalResponse({ action: 'APPROVE' });
      const mockRun = makeWorkflowRun({ status: 'RUNNING' });

      service.submitApproval('approval-001', response).subscribe(result => {
        expect(result.id).toBe('run-001');
        expect(result.status).toBe('RUNNING');
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/approval-001/respond`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.action).toBe('APPROVE');
      expect(req.request.body.respondedBy).toBe('alice@example.com');
      req.flush(mockRun);
    });

    it('should submit a REJECT response and return the run in REJECTED state', () => {
      const response = makeApprovalResponse({ action: 'REJECT', comment: 'Does not meet threshold' });
      const mockRun = makeWorkflowRun({ status: 'REJECTED' });

      service.submitApproval('approval-001', response).subscribe(result => {
        expect(result.status).toBe('REJECTED');
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/approval-001/respond`);
      expect(req.request.body.action).toBe('REJECT');
      expect(req.request.body.comment).toBe('Does not meet threshold');
      req.flush(mockRun);
    });

    it('should submit a DELEGATE response with delegateTo field', () => {
      const response = makeApprovalResponse({
        action: 'DELEGATE',
        delegateTo: 'bob@example.com',
        comment: 'Escalating to compliance lead'
      });
      const mockRun = makeWorkflowRun({ status: 'WAITING_APPROVAL' });

      service.submitApproval('approval-001', response).subscribe(result => {
        expect(result.status).toBe('WAITING_APPROVAL');
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/approval-001/respond`);
      expect(req.request.body.action).toBe('DELEGATE');
      expect(req.request.body.delegateTo).toBe('bob@example.com');
      req.flush(mockRun);
    });

    it('should handle 403 when submitter is not authorized to approve', () => {
      const response = makeApprovalResponse({ respondedBy: 'unauthorized@example.com' });

      service.submitApproval('approval-001', response).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/approval/approval-001/respond`);
      req.flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    });
  });

  // ── Controls ───────────────────────────────────────────────────────────────

  describe('evaluateControl()', () => {
    it('should POST to /process/control/:controlId/evaluate and return a control attestation', () => {
      const data = { amount: 500, currency: 'USD' };
      const mockAttestation = makeControlAttestation({ passed: true });

      service.evaluateControl('ctrl-001', 'run-001', data).subscribe(result => {
        expect(result.controlId).toBe('ctrl-001');
        expect(result.passed).toBeTrue();
        expect(result.workflowRunId).toBe('run-001');
        expect(result.expressionEvaluated).toBe('amount < 1000000');
      });

      const req = httpMock.expectOne(`${baseUrl}/control/ctrl-001/evaluate`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.runId).toBe('run-001');
      expect(req.request.body.data.amount).toBe(500);
      req.flush(mockAttestation);
    });

    it('should return failed attestation when control expression is not met', () => {
      const data = { amount: 2000000 };
      const mockAttestation = makeControlAttestation({
        passed: false,
        inputValues: { amount: 2000000 }
      });

      service.evaluateControl('ctrl-001', 'run-001', data).subscribe(result => {
        expect(result.passed).toBeFalse();
        expect(result.inputValues?.['amount']).toBe(2000000);
      });

      const req = httpMock.expectOne(`${baseUrl}/control/ctrl-001/evaluate`);
      req.flush(mockAttestation);
    });
  });

  describe('getControlResults()', () => {
    it('should GET /process/control/results/:runId and return attestations for the run', () => {
      const mockResults: ControlAttestation[] = [
        makeControlAttestation({ id: 'attest-001', controlId: 'ctrl-001', passed: true }),
        makeControlAttestation({ id: 'attest-002', controlId: 'ctrl-002', passed: false })
      ];

      service.getControlResults('run-001').subscribe(result => {
        expect(result.length).toBe(2);
        expect(result[0].passed).toBeTrue();
        expect(result[1].passed).toBeFalse();
        expect(result[1].controlId).toBe('ctrl-002');
      });

      const req = httpMock.expectOne(`${baseUrl}/control/results/run-001`);
      expect(req.request.method).toBe('GET');
      req.flush(mockResults);
    });

    it('should return empty list when no controls have been evaluated for the run', () => {
      service.getControlResults('run-001').subscribe(result => {
        expect(result.length).toBe(0);
      });

      const req = httpMock.expectOne(`${baseUrl}/control/results/run-001`);
      req.flush([]);
    });
  });

  // ── Manifests ──────────────────────────────────────────────────────────────

  describe('createManifest()', () => {
    it('should POST to /process/manifest and return the created manifest', () => {
      const input = makeSubmissionManifest({ id: undefined });
      const mockResponse = makeSubmissionManifest();

      service.createManifest(input).subscribe(result => {
        expect(result.id).toBe('manifest-001');
        expect(result.workflowRunId).toBe('run-001');
        expect(result.authoritativeFileName).toBe('report-final.pdf');
      });

      const req = httpMock.expectOne(`${baseUrl}/manifest`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.workflowRunId).toBe('run-001');
      expect(req.request.body.authoritativeFileName).toBe('report-final.pdf');
      req.flush(mockResponse);
    });

    it('should handle 400 when manifest is missing required fields', () => {
      const input: SubmissionManifest = { authoritativeFileId: 'file-001' } as SubmissionManifest;

      service.createManifest(input).subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/manifest`);
      req.flush(
        { error: 'workflowRunId is required' },
        { status: 400, statusText: 'Bad Request' }
      );
    });
  });

  describe('assertAuthoritativeVersion()', () => {
    it('should POST to /process/manifest/:id/assert with fileId and assertedBy', () => {
      const mockResponse = makeSubmissionManifest({
        authoritativeFileId: 'file-v2',
        versionAssertedBy: 'bob@example.com'
      });

      service.assertAuthoritativeVersion('manifest-001', 'file-v2', 'bob@example.com').subscribe(result => {
        expect(result.id).toBe('manifest-001');
        expect(result.authoritativeFileId).toBe('file-v2');
        expect(result.versionAssertedBy).toBe('bob@example.com');
      });

      const req = httpMock.expectOne(`${baseUrl}/manifest/manifest-001/assert`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.fileId).toBe('file-v2');
      expect(req.request.body.assertedBy).toBe('bob@example.com');
      req.flush(mockResponse);
    });

    it('should handle 404 when manifest does not exist', () => {
      service.assertAuthoritativeVersion('nonexistent', 'file-001', 'alice@example.com').subscribe({
        error: err => expect(err).toBeTruthy()
      });

      const req = httpMock.expectOne(`${baseUrl}/manifest/nonexistent/assert`);
      req.flush('Not Found', { status: 404, statusText: 'Not Found' });
    });
  });

  // ── Process Discovery ──────────────────────────────────────────────────────

  describe('discoverProcesses()', () => {
    it('should POST to /process/discovery/suggest with graph node IDs', () => {
      const nodeIds = ['node-1', 'node-2'];
      const mockResponse = { suggestions: [{ name: 'Invoice Processing' }] };

      service.discoverProcesses(nodeIds).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/suggest`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.graphNodeIds).toEqual(nodeIds);
      req.flush(mockResponse);
    });

    it('should send undefined graphNodeIds when not provided', () => {
      service.discoverProcesses().subscribe();

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/suggest`);
      expect(req.request.body.graphNodeIds).toBeUndefined();
      req.flush({});
    });
  });

  describe('analyzeEmailFlows()', () => {
    it('should POST to /process/discovery/email-flows', () => {
      const nodeIds = ['email-node-1'];
      const mockResponse = { flows: [{ sender: 'alice', receiver: 'bob' }] };

      service.analyzeEmailFlows(nodeIds).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/email-flows`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.graphNodeIds).toEqual(nodeIds);
      req.flush(mockResponse);
    });
  });

  describe('analyzeExcelFlows()', () => {
    it('should POST to /process/discovery/excel-flows', () => {
      const nodeIds = ['excel-node-1'];
      const mockResponse = { flows: [{ workbook: 'budget.xlsx' }] };

      service.analyzeExcelFlows(nodeIds).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/excel-flows`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.graphNodeIds).toEqual(nodeIds);
      req.flush(mockResponse);
    });
  });

  describe('analyzeDocumentFlows()', () => {
    it('should POST to /process/discovery/document-flows', () => {
      const nodeIds = ['doc-node-1'];
      const mockResponse = { flows: [] };

      service.analyzeDocumentFlows(nodeIds).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/document-flows`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.graphNodeIds).toEqual(nodeIds);
      req.flush(mockResponse);
    });
  });

  describe('acceptSuggestion()', () => {
    it('should POST suggestion to /process/discovery/accept', () => {
      const suggestion = { name: 'Invoice Processing', phases: [] };
      const mockResponse = { id: 'proc-001', name: 'Invoice Processing' };

      service.acceptSuggestion(suggestion).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/process/discovery/accept`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(suggestion);
      req.flush(mockResponse);
    });
  });

  // ── Email Value Extraction ─────────────────────────────────────────────────

  describe('extractEmailValues()', () => {
    it('should POST email body to /email/extract-values/extract', () => {
      const emailBody = 'Please update cell B5 to $150,000';
      const subject = 'Q3 Budget Update';
      const mockResponse = { values: [{ type: 'currency', value: 150000 }] };

      service.extractEmailValues(emailBody, subject).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/email/extract-values/extract`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.body).toBe(emailBody);
      expect(req.request.body.subject).toBe(subject);
      req.flush(mockResponse);
    });

    it('should send optional messageId when provided', () => {
      service.extractEmailValues('body text', 'subject', 'msg-id-123').subscribe();

      const req = httpMock.expectOne(`${backendUrl}/email/extract-values/extract`);
      expect(req.request.body.messageId).toBe('msg-id-123');
      req.flush({});
    });
  });

  describe('mapEmailValuesToCells()', () => {
    it('should POST to /email/extract-values/map-to-cells', () => {
      const emailBody = 'Revenue for Q3 is $2.5M';
      const graphJson = '{"sheets":[]}';
      const mockResponse = { mappings: [{ cell: 'B5', value: 2500000 }] };

      service.mapEmailValuesToCells(emailBody, graphJson).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${backendUrl}/email/extract-values/map-to-cells`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.body).toBe(emailBody);
      expect(req.request.body.spreadsheetGraphJson).toBe(graphJson);
      req.flush(mockResponse);
    });
  });

  // ── Run Inspection ─────────────────────────────────────────────────────────

  describe('getRunSteps()', () => {
    it('should GET /process/run/{id}/steps', () => {
      const mockSteps = [{ stepId: 'step-1', status: 'COMPLETED' }];

      service.getRunSteps('run-001').subscribe(res => {
        expect(res).toEqual(mockSteps);
      });

      const req = httpMock.expectOne(`${baseUrl}/run/run-001/steps`);
      expect(req.request.method).toBe('GET');
      req.flush(mockSteps);
    });
  });

  describe('getRunContext()', () => {
    it('should GET /process/run/{id}/context', () => {
      const mockContext = { amount: 100, currency: 'USD' };

      service.getRunContext('run-001').subscribe(res => {
        expect(res).toEqual(mockContext);
      });

      const req = httpMock.expectOne(`${baseUrl}/run/run-001/context`);
      expect(req.request.method).toBe('GET');
      req.flush(mockContext);
    });
  });

  describe('getStepResult()', () => {
    it('should GET /process/run/{id}/step/{stepId}', () => {
      const mockResult = { outputs: { total: 42 } };

      service.getStepResult('run-001', 'step-1').subscribe(res => {
        expect(res).toEqual(mockResult);
      });

      const req = httpMock.expectOne(`${baseUrl}/run/run-001/step/step-1`);
      expect(req.request.method).toBe('GET');
      req.flush(mockResult);
    });
  });

  describe('updateRunData()', () => {
    it('should PUT data to /process/run/{id}/data', () => {
      const data = { amount: 200 };

      service.updateRunData('run-001', data).subscribe();

      const req = httpMock.expectOne(`${baseUrl}/run/run-001/data`);
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(data);
      req.flush({});
    });
  });

  // ── Excel Formula ──────────────────────────────────────────────────────────

  describe('convertExcelFormulas()', () => {
    it('should POST to /process/excel/convert', () => {
      const graphJson = '{"sheets":[{"name":"Sheet1"}]}';
      const mockResponse = { code: 'function calc() {}', language: 'javascript' };

      service.convertExcelFormulas(graphJson).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${baseUrl}/excel/convert`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.spreadsheetGraphJson).toBe(graphJson);
      expect(req.request.body.targetLanguage).toBe('javascript');
      req.flush(mockResponse);
    });
  });

  describe('executeExcelFormulas()', () => {
    it('should POST to /process/excel/execute with overrides', () => {
      const graphJson = '{"sheets":[]}';
      const overrides = { 'Sheet1!B5': 100 };
      const mockResponse = { 'Sheet1!C5': 200 };

      service.executeExcelFormulas(graphJson, overrides).subscribe(res => {
        expect(res).toEqual(mockResponse);
      });

      const req = httpMock.expectOne(`${baseUrl}/excel/execute`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.cellOverrides).toEqual(overrides);
      req.flush(mockResponse);
    });
  });

  // ── SpEL ───────────────────────────────────────────────────────────────────

  describe('evaluateSpelExpression()', () => {
    it('should POST to /process/spel/evaluate', () => {
      const mockResult = { result: 42, type: 'Integer', error: null };

      service.evaluateSpelExpression('#amount * 2', { amount: 21 }).subscribe(res => {
        expect(res).toEqual(mockResult);
      });

      const req = httpMock.expectOne(`${baseUrl}/spel/evaluate`);
      expect(req.request.method).toBe('POST');
      expect(req.request.body.expression).toBe('#amount * 2');
      req.flush(mockResult);
    });
  });
});
