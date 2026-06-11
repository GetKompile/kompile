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

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

// ─── Interfaces ───────────────────────────────────────────────────────────────

export interface OntologySchema {
  id?: string;
  name: string;
  version?: number;
  templateId?: string;
  createdAt?: string;
  updatedAt?: string;
  updatedBy?: string;
  entityTypes?: EntityTypeDefinition[];
  relationshipTypes?: RelationshipTypeDefinition[];
  globalRules?: ValidationRule[];
  metadata?: Record<string, any>;
}

export interface EntityTypeDefinition {
  name: string;
  description?: string;
  classification?: string;
  fields?: FieldDefinition[];
  rules?: ValidationRule[];
}

export interface FieldDefinition {
  name: string;
  type: string;
  required?: boolean;
  description?: string;
}

export interface RelationshipTypeDefinition {
  name: string;
  sourceEntityType: string;
  targetEntityType: string;
  cardinality?: string;
  description?: string;
}

export interface ValidationRule {
  name: string;
  type?: string;
  severity?: string;
  expression?: string;
  description?: string;
}

export interface ProcessDefinition {
  id?: string;
  name: string;
  version?: number;
  ontologySchemaId?: string;
  ontologyVersion?: number;
  status?: string;
  approvedBy?: string;
  approvedAt?: string;
  phases?: ProcessPhase[];
  controls?: ControlDefinitionRef[];
  agentSpecs?: string[];
  metadata?: Record<string, any>;
  /** ID of the parent process if this is a sub-process. */
  parentProcessId?: string;
  /** IDs of child (sub) processes nested within this process. */
  childProcessIds?: string[];
}

export interface ProcessPhase {
  id: string;
  name: string;
  order: number;
  steps?: ProcessStep[];
}

export interface ProcessStep {
  id: string;
  name: string;
  description?: string;
  stepType: 'AUTO' | 'APPROVE' | 'HUMAN' | 'CONTROL_GATE' | 'TOOL_CALL' | 'HTTP_CALL' | 'SCRIPT' | 'EXCEL_COMPUTE' | 'PIPELINE' | 'CAMEL_ROUTE' | 'DROOLS_RULE' | 'DROOLS_INFERENCE' | 'DROOLS_DECISION_TABLE' | 'WORKFLOW';
  inputKeys?: string[];
  outputKeys?: string[];
  agentSpecId?: string;
  approvalPolicy?: ApprovalPolicy;
  executionExpressions?: Record<string, string>;
  toolName?: string;
  toolArguments?: Record<string, string>;
  httpMethod?: string;
  httpUrl?: string;
  httpHeaders?: Record<string, string>;
  httpBody?: string;
  httpResponseKey?: string;
  scriptLanguage?: string;
  scriptBody?: string;
  scriptOutputKey?: string;
  excelGraphJson?: string;
  excelCellOverrides?: Record<string, any>;
  excelTargetLanguage?: string;
  excelOutputKey?: string;
  excelGeneratedCode?: string;
  pipelineDefinitionJson?: string;
  pipelineInputMapping?: Record<string, string>;
  pipelineOutputKey?: string;
  camelRouteId?: string;
  camelInlineScript?: string;
  camelOutputKey?: string;
  droolsRuleDrl?: string;
  droolsAgendaGroup?: string;
  droolsMaxFirings?: number;
  droolsDecisionTable?: string;
  droolsInputType?: 'CSV' | 'XLS' | 'XLSX';
  droolsWorksheetName?: string;
  droolsOutputKey?: string;
  droolsDecisionKey?: string;
  workflowEngineType?: 'xircuits' | 'n8n';
  workflowName?: string;
  workflowInlineContent?: string;
  workflowTimeoutSeconds?: number;
  workflowInputMapping?: Record<string, string>;
  workflowOutputKey?: string;
  controlIds?: string[];
  dependsOn?: string[];
  conditionExpression?: string;
  conditionLabel?: string;
  requiredRoles?: string[];
  requiredPermissions?: string[];
  graphNodeIds?: string[];
  confidence?: number;
  metadata?: Record<string, any>;
}

export interface ApprovalPolicy {
  approverPool?: string[];
  mode?: string;
  allowPartialApproval?: boolean;
  dollarThreshold?: number;
  maxAutoCorrections?: number;
}

export interface ControlDefinitionRef {
  controlId: string;
  triggerAfterStep: string;
}

export interface WorkflowRun {
  id?: string;
  processDefinitionId: string;
  processVersion?: number;
  status?: string;
  startedAt?: string;
  completedAt?: string;
  stepExecutions?: StepExecution[];
  pendingApprovals?: ApprovalRequest[];
  controlResults?: ControlAttestation[];
  runData?: Record<string, any>;
  graphNodeIds?: string[];
  metrics?: Record<string, any>;
}

export interface StepExecution {
  stepId: string;
  stepName?: string;
  status: string;
  startedAt?: string;
  completedAt?: string;
  executedBy?: string;
  inputs?: Record<string, any>;
  outputs?: Record<string, any>;
  inputHash?: string;
  outputHash?: string;
  evidenceReliedOn?: string[];
  graphNodeIds?: string[];
  error?: string;
}

export interface ControlDefinition {
  id?: string;
  name: string;
  description?: string;
  gateType?: 'HARD' | 'SOFT';
  expression?: string;
  triggerAfterStep?: string;
  inputKeys?: string[];
  regulatoryReference?: string;
  severity?: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
}

export interface ApprovalRequest {
  id?: string;
  workflowRunId?: string;
  stepId?: string;
  stepName?: string;
  status?: string;
  createdAt?: string;
  slaDeadline?: string;
  assignedTo?: string;
  items?: any[];
  context?: Record<string, any>;
}

export interface ApprovalResponse {
  requestId: string;
  respondedBy: string;
  respondedAt?: string;
  action: 'APPROVE' | 'APPROVE_WITH_EDITS' | 'REJECT' | 'ESCALATE' | 'DELEGATE' | 'REQUEST_INFO';
  comment?: string;
  delegateTo?: string;
}

export interface ControlAttestation {
  id?: string;
  controlId: string;
  workflowRunId?: string;
  evaluatedAt?: string;
  passed: boolean;
  expressionEvaluated?: string;
  inputValues?: Record<string, any>;
  inputHash?: string;
  evaluatedBy?: string;
}

export interface SpelResult {
  result: any;
  type: string;
  error: string | null;
}

export interface ExcelConversionResult {
  code: string;
  language: string;
  workbookName: string;
  inputCells: string[];
  outputCells: string[];
  formulaCount: number;
  dependencyCount: number;
}

export interface SubmissionManifest {
  id?: string;
  workflowRunId?: string;
  sourceRegion?: string;
  authoritativeFileId?: string;
  authoritativeFileName?: string;
  fileContentHash?: string;
  versionAssertedBy?: string;
}

// ─── Service ──────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class ProcessEngineService extends BaseService {

  private readonly baseUrl = `${this.backendUrl}/process`;

  constructor(private http: HttpClient) {
    super();
  }

  // ── Ontology ──────────────────────────────────────────────────────────────

  listOntologies(): Observable<OntologySchema[]> {
    return this.http.get<OntologySchema[]>(`${this.baseUrl}/ontology`);
  }

  createOntology(schema: OntologySchema): Observable<OntologySchema> {
    return this.http.post<OntologySchema>(`${this.baseUrl}/ontology`, schema);
  }

  getOntology(id: string, version: number = 1): Observable<OntologySchema> {
    const params = new HttpParams().set('version', version.toString());
    return this.http.get<OntologySchema>(`${this.baseUrl}/ontology/${id}`, { params });
  }

  updateOntology(id: string, schema: OntologySchema): Observable<OntologySchema> {
    return this.http.put<OntologySchema>(`${this.baseUrl}/ontology/${id}`, schema);
  }

  validateData(
    ontologyId: string,
    entityType: string,
    version: number,
    data: Record<string, any>
  ): Observable<ValidationRule[]> {
    const params = new HttpParams()
      .set('entityType', entityType)
      .set('version', version.toString());
    return this.http.post<ValidationRule[]>(
      `${this.baseUrl}/ontology/${ontologyId}/validate`,
      data,
      { params }
    );
  }

  // ── Process Definitions ───────────────────────────────────────────────────

  listProcessDefinitions(): Observable<ProcessDefinition[]> {
    return this.http.get<ProcessDefinition[]>(`${this.baseUrl}/definition`);
  }

  createProcess(definition: ProcessDefinition): Observable<ProcessDefinition> {
    return this.http.post<ProcessDefinition>(`${this.baseUrl}/definition`, definition);
  }

  getProcess(id: string, version: number = 1): Observable<ProcessDefinition> {
    const params = new HttpParams().set('version', version.toString());
    return this.http.get<ProcessDefinition>(`${this.baseUrl}/definition/${id}`, { params });
  }

  approveProcess(id: string, approvedBy: string): Observable<ProcessDefinition> {
    return this.http.post<ProcessDefinition>(
      `${this.baseUrl}/definition/${id}/approve`,
      { approvedBy }
    );
  }

  // ── Workflow Runs ─────────────────────────────────────────────────────────

  startRun(processDefinitionId: string, initialData?: Record<string, any>): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(`${this.baseUrl}/run`, {
      processDefinitionId,
      initialData: initialData ?? {}
    });
  }

  getRun(id: string): Observable<WorkflowRun> {
    return this.http.get<WorkflowRun>(`${this.baseUrl}/run/${id}`);
  }

  listActiveRuns(): Observable<WorkflowRun[]> {
    return this.http.get<WorkflowRun[]>(`${this.baseUrl}/run/active`);
  }

  listAllRuns(): Observable<WorkflowRun[]> {
    return this.http.get<WorkflowRun[]>(`${this.baseUrl}/run/all`);
  }

  cancelRun(runId: string): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(`${this.baseUrl}/run/${runId}/cancel`, {});
  }

  completeHumanStep(
    runId: string,
    stepId: string,
    completedBy: string,
    outputs?: Record<string, any>
  ): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(
      `${this.baseUrl}/run/${runId}/complete-step`,
      { stepId, completedBy, outputs: outputs ?? {} }
    );
  }

  resumeAfterApproval(runId: string, response: ApprovalResponse): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(`${this.baseUrl}/run/${runId}/resume`, response);
  }

  // ── Approvals ─────────────────────────────────────────────────────────────

  getPendingApprovals(assignedTo?: string): Observable<ApprovalRequest[]> {
    let params = new HttpParams();
    if (assignedTo) {
      params = params.set('assignedTo', assignedTo);
    }
    return this.http.get<ApprovalRequest[]>(`${this.baseUrl}/approval/pending`, { params });
  }

  getApprovalRequest(id: string): Observable<ApprovalRequest> {
    return this.http.get<ApprovalRequest>(`${this.baseUrl}/approval/${id}`);
  }

  submitApproval(id: string, response: ApprovalResponse): Observable<WorkflowRun> {
    return this.http.post<WorkflowRun>(`${this.baseUrl}/approval/${id}/respond`, response);
  }

  // ── Controls ──────────────────────────────────────────────────────────────

  createControl(control: ControlDefinition): Observable<ControlDefinition> {
    return this.http.post<ControlDefinition>(`${this.baseUrl}/control`, control);
  }

  listControls(): Observable<ControlDefinition[]> {
    return this.http.get<ControlDefinition[]>(`${this.baseUrl}/control`);
  }

  getControl(id: string): Observable<ControlDefinition> {
    return this.http.get<ControlDefinition>(`${this.baseUrl}/control/${id}`);
  }

  evaluateControl(
    controlId: string,
    runId: string,
    data: Record<string, any>
  ): Observable<ControlAttestation> {
    return this.http.post<ControlAttestation>(
      `${this.baseUrl}/control/${controlId}/evaluate`,
      { runId, data }
    );
  }

  getControlResults(runId: string): Observable<ControlAttestation[]> {
    return this.http.get<ControlAttestation[]>(`${this.baseUrl}/control/results/${runId}`);
  }

  // ── SpEL Evaluation ───────────────────────────────────────────────────────

  evaluateSpelExpression(expression: string, context: Record<string, any>): Observable<SpelResult> {
    return this.http.post<SpelResult>(`${this.baseUrl}/spel/evaluate`, { expression, context });
  }

  // ── Excel Formula Conversion / Execution ──────────────────────────────────

  convertExcelFormulas(spreadsheetGraphJson: string, targetLanguage = 'javascript'): Observable<ExcelConversionResult> {
    return this.http.post<ExcelConversionResult>(`${this.baseUrl}/excel/convert`, {
      spreadsheetGraphJson, targetLanguage
    });
  }

  executeExcelFormulas(
    spreadsheetGraphJson: string,
    cellOverrides?: Record<string, any>,
    targetLanguage = 'javascript',
    generatedCode?: string
  ): Observable<Record<string, any>> {
    return this.http.post<Record<string, any>>(`${this.baseUrl}/excel/execute`, {
      spreadsheetGraphJson, cellOverrides, targetLanguage, generatedCode
    });
  }

  // ── Manifests ─────────────────────────────────────────────────────────────

  createManifest(manifest: SubmissionManifest): Observable<SubmissionManifest> {
    return this.http.post<SubmissionManifest>(`${this.baseUrl}/manifest`, manifest);
  }

  assertAuthoritativeVersion(
    manifestId: string,
    fileId: string,
    assertedBy: string
  ): Observable<SubmissionManifest> {
    return this.http.post<SubmissionManifest>(
      `${this.baseUrl}/manifest/${manifestId}/assert-version`,
      { fileId, assertedBy }
    );
  }

  // ── Process Discovery ─────────────────────────────────────────────────────

  discoverProcesses(graphNodeIds?: string[], options?: any): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/suggest`, { graphNodeIds, options });
  }

  analyzeEmailFlows(graphNodeIds?: string[]): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/email-flows`, { graphNodeIds });
  }

  analyzeExcelFlows(graphNodeIds?: string[]): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/excel-flows`, { graphNodeIds });
  }

  acceptSuggestion(suggestion: any): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/accept`, suggestion);
  }

  analyzeDocumentFlows(graphNodeIds?: string[]): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/document-flows`, { graphNodeIds });
  }

  analyzeCrossDocumentFlows(graphNodeIds?: string[]): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/process/discovery/cross-document-flows`, { graphNodeIds });
  }

  // ── Integration Discovery ─────────────────────────────────────────────────

  listIntegrations(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/integrations`);
  }

  listStepTypes(): Observable<Array<{ type: string; description: string }>> {
    return this.http.get<Array<{ type: string; description: string }>>(`${this.baseUrl}/step-types`);
  }

  // ── Run Inspection ────────────────────────────────────────────────────────

  getRunSteps(runId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/run/${runId}/steps`);
  }

  getRunContext(runId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/run/${runId}/context`);
  }

  getStepResult(runId: string, stepId: string): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/run/${runId}/step/${stepId}`);
  }

  updateRunData(runId: string, data: Record<string, any>): Observable<any> {
    return this.http.put<any>(`${this.baseUrl}/run/${runId}/data`, data);
  }

  // ── Email Value Extraction ────────────────────────────────────────────────

  extractEmailValues(emailBody: string, subject?: string, messageId?: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/email/extract-values/extract`, {
      body: emailBody, subject, messageId
    });
  }

  mapEmailValuesToCells(emailBody: string, spreadsheetGraphJson: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/email/extract-values/map-to-cells`, {
      body: emailBody, spreadsheetGraphJson
    });
  }
}
