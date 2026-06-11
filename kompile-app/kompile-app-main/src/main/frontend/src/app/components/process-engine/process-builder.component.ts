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

import { Component, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import {
  ProcessDefinition,
  ProcessPhase,
  ProcessStep
} from '../../services/process-engine.service';

type JsonMapField =
  | 'toolArguments'
  | 'httpHeaders'
  | 'excelCellOverrides'
  | 'pipelineInputMapping'
  | 'workflowInputMapping';

@Component({
  standalone: true,
  selector: 'app-process-builder',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatChipsModule, MatDividerModule, MatExpansionModule,
    MatTooltipModule, MatSlideToggleModule, DragDropModule
  ],
  template: `
    <div class="builder-container">
      <!-- Process Name & Ontology -->
      <div class="builder-header-fields">
        <mat-form-field appearance="outline" class="name-field">
          <mat-label>Process Name</mat-label>
          <input matInput [(ngModel)]="definition.name" placeholder="e.g. quarterly-compliance-review">
        </mat-form-field>
        <mat-form-field appearance="outline" class="ontology-field">
          <mat-label>Ontology Schema ID (optional)</mat-label>
          <input matInput [(ngModel)]="definition.ontologySchemaId" placeholder="ontology-id">
        </mat-form-field>
      </div>

      <!-- Phases -->
      <div class="phases-section">
        <div class="section-header">
          <span class="section-title">
            <mat-icon>layers</mat-icon> Phases ({{ phases.length }})
          </span>
          <button mat-stroked-button (click)="addPhase()">
            <mat-icon>add</mat-icon> Add Phase
          </button>
        </div>

        <div cdkDropList (cdkDropListDropped)="dropPhase($event)" class="phases-list">
          <mat-expansion-panel *ngFor="let phase of phases; let pi = index"
                               cdkDrag class="phase-panel">
            <mat-expansion-panel-header>
              <mat-panel-title>
                <mat-icon cdkDragHandle class="drag-handle">drag_indicator</mat-icon>
                <span class="phase-order">{{ pi + 1 }}</span>
                <span>{{ phase.name || 'Untitled Phase' }}</span>
              </mat-panel-title>
              <mat-panel-description>
                {{ phase.steps?.length || 0 }} steps
                <button mat-icon-button (click)="removePhase(pi); $event.stopPropagation()" matTooltip="Remove phase" class="remove-btn">
                  <mat-icon>delete_outline</mat-icon>
                </button>
              </mat-panel-description>
            </mat-expansion-panel-header>

            <!-- Phase Fields -->
            <div class="phase-fields">
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Phase Name</mat-label>
                <input matInput [(ngModel)]="phase.name" placeholder="e.g. Data Collection">
              </mat-form-field>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Phase ID</mat-label>
                <input matInput [(ngModel)]="phase.id" placeholder="e.g. phase-data-collection">
              </mat-form-field>
            </div>

            <!-- Steps within Phase -->
            <div class="steps-section">
              <div class="steps-header">
                <span class="sub-title">Steps ({{ phase.steps?.length || 0 }})</span>
                <button mat-stroked-button (click)="addStep(phase)" class="add-step-btn">
                  <mat-icon>add</mat-icon> Add Step
                </button>
              </div>

              <div cdkDropList (cdkDropListDropped)="dropStep($event, phase)" class="steps-list">
                <div *ngFor="let step of phase.steps; let si = index" cdkDrag class="step-card">
                  <div class="step-card-header">
                    <mat-icon cdkDragHandle class="drag-handle">drag_indicator</mat-icon>
                    <mat-chip-set>
                      <mat-chip [class]="'step-type-chip step-' + (step.stepType || 'AUTO').toLowerCase()">
                        {{ step.stepType || 'AUTO' }}
                      </mat-chip>
                    </mat-chip-set>
                    <span class="step-card-name">{{ step.name || 'Untitled Step' }}</span>
                    <button mat-icon-button (click)="removeStep(phase, si)" matTooltip="Remove step" class="remove-btn">
                      <mat-icon>delete_outline</mat-icon>
                    </button>
                  </div>

                  <div class="step-fields">
                    <div class="field-row">
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Step Name</mat-label>
                        <input matInput [(ngModel)]="step.name" placeholder="e.g. Extract Revenue Data">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Step ID</mat-label>
                        <input matInput [(ngModel)]="step.id" placeholder="e.g. step-extract-revenue">
                      </mat-form-field>
                    </div>

                    <div class="field-row">
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Step Type</mat-label>
                        <mat-select [(ngModel)]="step.stepType">
                          <mat-option value="AUTO">AUTO</mat-option>
                          <mat-option value="TOOL_CALL">TOOL_CALL</mat-option>
                          <mat-option value="HTTP_CALL">HTTP_CALL</mat-option>
                          <mat-option value="SCRIPT">SCRIPT</mat-option>
                          <mat-option value="EXCEL_COMPUTE">EXCEL_COMPUTE</mat-option>
                          <mat-option value="PIPELINE">PIPELINE</mat-option>
                          <mat-option value="CAMEL_ROUTE">CAMEL_ROUTE</mat-option>
                          <mat-option value="DROOLS_RULE">DROOLS_RULE</mat-option>
                          <mat-option value="DROOLS_INFERENCE">DROOLS_INFERENCE</mat-option>
                          <mat-option value="DROOLS_DECISION_TABLE">DROOLS_DECISION_TABLE</mat-option>
                          <mat-option value="WORKFLOW">WORKFLOW</mat-option>
                          <mat-option value="APPROVE">APPROVE</mat-option>
                          <mat-option value="HUMAN">HUMAN</mat-option>
                          <mat-option value="CONTROL_GATE">CONTROL_GATE</mat-option>
                        </mat-select>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="flex-field" *ngIf="step.stepType === 'AUTO'">
                        <mat-label>Agent Spec ID (optional)</mat-label>
                        <input matInput [(ngModel)]="step.agentSpecId" placeholder="agent-id">
                      </mat-form-field>
                    </div>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Description</mat-label>
                      <input matInput [(ngModel)]="step.description" placeholder="What this step does">
                    </mat-form-field>

                    <div class="field-row">
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Input Keys (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.inputKeys?.join(', ')"
                               (ngModelChange)="step.inputKeys = splitKeys($event)" placeholder="key1, key2">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Output Keys (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.outputKeys?.join(', ')"
                               (ngModelChange)="step.outputKeys = splitKeys($event)" placeholder="result1, result2">
                      </mat-form-field>
                    </div>

                    <div class="field-row">
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Depends On (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.dependsOn?.join(', ')"
                               (ngModelChange)="step.dependsOn = splitKeys($event)" placeholder="step-a, step-b">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Branch Label</mat-label>
                        <input matInput [(ngModel)]="step.conditionLabel" placeholder="Approved">
                      </mat-form-field>
                    </div>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Condition Expression</mat-label>
                      <input matInput [(ngModel)]="step.conditionExpression" placeholder="#decision == 'Approved'">
                    </mat-form-field>

                    <div class="field-row">
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Required Roles (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.requiredRoles?.join(', ')"
                               (ngModelChange)="step.requiredRoles = splitKeys($event)" placeholder="finance, approver">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="flex-field">
                        <mat-label>Required Permissions (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.requiredPermissions?.join(', ')"
                               (ngModelChange)="step.requiredPermissions = splitKeys($event)" placeholder="invoice.approve">
                      </mat-form-field>
                    </div>

                    <!-- APPROVE-specific fields -->
                    <div *ngIf="step.stepType === 'APPROVE'" class="conditional-fields">
                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Approver Pool (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.approvalPolicy?.approverPool?.join(', ')"
                               (ngModelChange)="ensureApprovalPolicy(step); step.approvalPolicy!.approverPool = splitKeys($event)"
                               placeholder="user1@example.com, manager@example.com">
                      </mat-form-field>
                    </div>

                    <!-- CONTROL_GATE-specific fields -->
                    <div *ngIf="step.stepType === 'CONTROL_GATE'" class="conditional-fields">
                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Control IDs (comma-separated)</mat-label>
                        <input matInput [ngModel]="step.controlIds?.join(', ')"
                               (ngModelChange)="step.controlIds = splitKeys($event)"
                               placeholder="control-1, control-2">
                      </mat-form-field>
                    </div>

                    <!-- TOOL_CALL-specific fields -->
                    <div *ngIf="step.stepType === 'TOOL_CALL'" class="conditional-fields">
                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Tool Name</mat-label>
                        <input matInput [(ngModel)]="step.toolName" placeholder="rag_query">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Tool Arguments JSON</mat-label>
                        <textarea matInput rows="4"
                                  [ngModel]="jsonMapText(step, 'toolArguments')"
                                  (ngModelChange)="setJsonMap(step, 'toolArguments', $event)"
                                  placeholder='{"query": "#question", "maxResults": "5"}'></textarea>
                      </mat-form-field>
                    </div>

                    <!-- HTTP_CALL-specific fields -->
                    <div *ngIf="step.stepType === 'HTTP_CALL'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="method-field">
                          <mat-label>Method</mat-label>
                          <mat-select [(ngModel)]="step.httpMethod">
                            <mat-option value="GET">GET</mat-option>
                            <mat-option value="POST">POST</mat-option>
                            <mat-option value="PUT">PUT</mat-option>
                            <mat-option value="PATCH">PATCH</mat-option>
                            <mat-option value="DELETE">DELETE</mat-option>
                          </mat-select>
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>URL</mat-label>
                          <input matInput [(ngModel)]="step.httpUrl" placeholder="https://service.example/api">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Headers JSON</mat-label>
                        <textarea matInput rows="3"
                                  [ngModel]="jsonMapText(step, 'httpHeaders')"
                                  (ngModelChange)="setJsonMap(step, 'httpHeaders', $event)"
                                  placeholder='{"Authorization": "#apiToken"}'></textarea>
                      </mat-form-field>
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Body SpEL</mat-label>
                          <input matInput [(ngModel)]="step.httpBody" placeholder="#payload">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Response Key</mat-label>
                          <input matInput [(ngModel)]="step.httpResponseKey" placeholder="httpResponse">
                        </mat-form-field>
                      </div>
                    </div>

                    <!-- SCRIPT-specific fields -->
                    <div *ngIf="step.stepType === 'SCRIPT'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Language</mat-label>
                          <mat-select [(ngModel)]="step.scriptLanguage">
                            <mat-option value="javascript">javascript</mat-option>
                            <mat-option value="python">python</mat-option>
                          </mat-select>
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Output Key</mat-label>
                          <input matInput [(ngModel)]="step.scriptOutputKey" placeholder="scriptResult">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Script Body</mat-label>
                        <textarea matInput rows="6" [(ngModel)]="step.scriptBody"></textarea>
                      </mat-form-field>
                    </div>

                    <!-- EXCEL_COMPUTE-specific fields -->
                    <div *ngIf="step.stepType === 'EXCEL_COMPUTE'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Target Language</mat-label>
                          <mat-select [(ngModel)]="step.excelTargetLanguage">
                            <mat-option value="javascript">javascript</mat-option>
                            <mat-option value="python">python</mat-option>
                          </mat-select>
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Output Key</mat-label>
                          <input matInput [(ngModel)]="step.excelOutputKey" placeholder="excelResult">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Spreadsheet Graph JSON</mat-label>
                        <textarea matInput rows="5" [(ngModel)]="step.excelGraphJson"></textarea>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Cell Overrides JSON</mat-label>
                        <textarea matInput rows="3"
                                  [ngModel]="jsonMapText(step, 'excelCellOverrides')"
                                  (ngModelChange)="setJsonMap(step, 'excelCellOverrides', $event)"
                                  placeholder='{"Sheet1_A1": 100}'></textarea>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Generated Code Override</mat-label>
                        <textarea matInput rows="5" [(ngModel)]="step.excelGeneratedCode"></textarea>
                      </mat-form-field>
                    </div>

                    <!-- PIPELINE-specific fields -->
                    <div *ngIf="step.stepType === 'PIPELINE'" class="conditional-fields">
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Pipeline Definition JSON</mat-label>
                        <textarea matInput rows="6" [(ngModel)]="step.pipelineDefinitionJson"></textarea>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Input Mapping JSON</mat-label>
                        <textarea matInput rows="3"
                                  [ngModel]="jsonMapText(step, 'pipelineInputMapping')"
                                  (ngModelChange)="setJsonMap(step, 'pipelineInputMapping', $event)"
                                  placeholder='{"customerName": "name"}'></textarea>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Output Key</mat-label>
                        <input matInput [(ngModel)]="step.pipelineOutputKey" placeholder="pipelineResult">
                      </mat-form-field>
                    </div>

                    <!-- CAMEL_ROUTE-specific fields -->
                    <div *ngIf="step.stepType === 'CAMEL_ROUTE'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Route ID</mat-label>
                          <input matInput [(ngModel)]="step.camelRouteId" placeholder="route-id">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Output Key</mat-label>
                          <input matInput [(ngModel)]="step.camelOutputKey" placeholder="camelResult">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Inline Route</mat-label>
                        <textarea matInput rows="6" [(ngModel)]="step.camelInlineScript"></textarea>
                      </mat-form-field>
                    </div>

                    <!-- DROOLS_RULE / DROOLS_INFERENCE-specific fields -->
                    <div *ngIf="step.stepType === 'DROOLS_RULE' || step.stepType === 'DROOLS_INFERENCE'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Agenda Group</mat-label>
                          <input matInput [(ngModel)]="step.droolsAgendaGroup" placeholder="validation">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Max Firings</mat-label>
                          <input matInput type="number" min="1" [(ngModel)]="step.droolsMaxFirings">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Output Key</mat-label>
                          <input matInput [(ngModel)]="step.droolsOutputKey" placeholder="rulesResult">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Decision Key</mat-label>
                          <input matInput [(ngModel)]="step.droolsDecisionKey" placeholder="decision">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>DRL Source</mat-label>
                        <textarea matInput rows="7" [(ngModel)]="step.droolsRuleDrl"></textarea>
                      </mat-form-field>
                    </div>

                    <!-- DROOLS_DECISION_TABLE-specific fields -->
                    <div *ngIf="step.stepType === 'DROOLS_DECISION_TABLE'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Input Type</mat-label>
                          <mat-select [(ngModel)]="step.droolsInputType">
                            <mat-option value="CSV">CSV</mat-option>
                            <mat-option value="XLS">XLS</mat-option>
                            <mat-option value="XLSX">XLSX</mat-option>
                          </mat-select>
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Worksheet</mat-label>
                          <input matInput [(ngModel)]="step.droolsWorksheetName" placeholder="Rules">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Output Key</mat-label>
                          <input matInput [(ngModel)]="step.droolsOutputKey" placeholder="decisionResult">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Decision Key</mat-label>
                          <input matInput [(ngModel)]="step.droolsDecisionKey" placeholder="decision">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Decision Table</mat-label>
                        <textarea matInput rows="7" [(ngModel)]="step.droolsDecisionTable"></textarea>
                      </mat-form-field>
                    </div>

                    <!-- WORKFLOW-specific fields -->
                    <div *ngIf="step.stepType === 'WORKFLOW'" class="conditional-fields">
                      <div class="field-row">
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Engine</mat-label>
                          <mat-select [(ngModel)]="step.workflowEngineType">
                            <mat-option value="xircuits">xircuits</mat-option>
                            <mat-option value="n8n">n8n</mat-option>
                          </mat-select>
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Workflow Name</mat-label>
                          <input matInput [(ngModel)]="step.workflowName" placeholder="workflow-name">
                        </mat-form-field>
                        <mat-form-field appearance="outline" class="flex-field">
                          <mat-label>Timeout Seconds</mat-label>
                          <input matInput type="number" min="1" [(ngModel)]="step.workflowTimeoutSeconds">
                        </mat-form-field>
                      </div>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Input Mapping JSON</mat-label>
                        <textarea matInput rows="3"
                                  [ngModel]="jsonMapText(step, 'workflowInputMapping')"
                                  (ngModelChange)="setJsonMap(step, 'workflowInputMapping', $event)"
                                  placeholder='{"question": "query"}'></textarea>
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width">
                        <mat-label>Output Key</mat-label>
                        <input matInput [(ngModel)]="step.workflowOutputKey" placeholder="workflowResult">
                      </mat-form-field>
                      <mat-form-field appearance="outline" class="full-width code-field">
                        <mat-label>Inline Workflow JSON</mat-label>
                        <textarea matInput rows="7" [(ngModel)]="step.workflowInlineContent"></textarea>
                      </mat-form-field>
                    </div>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Depends On (step IDs, comma-separated)</mat-label>
                      <input matInput [ngModel]="step.dependsOn?.join(', ')"
                             (ngModelChange)="step.dependsOn = splitKeys($event)" placeholder="step-id-1, step-id-2">
                    </mat-form-field>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Graph Node IDs (comma-separated, optional)</mat-label>
                      <input matInput [ngModel]="step.graphNodeIds?.join(', ')"
                             (ngModelChange)="step.graphNodeIds = splitKeys($event)" placeholder="node-1, node-2">
                    </mat-form-field>
                  </div>
                </div>
              </div>

              <div *ngIf="!phase.steps?.length" class="empty-steps">
                No steps yet. Click "Add Step" to begin.
              </div>
            </div>
          </mat-expansion-panel>
        </div>

        <div *ngIf="phases.length === 0" class="empty-phases">
          <mat-icon>layers</mat-icon>
          <p>No phases defined. Click "Add Phase" to start building.</p>
        </div>
      </div>

      <mat-divider class="section-divider"></mat-divider>

      <!-- Preview & Save -->
      <div class="builder-footer">
        <mat-slide-toggle [(ngModel)]="showPreview">Preview JSON</mat-slide-toggle>
        <button mat-raised-button color="primary" (click)="emitSave()" [disabled]="!definition.name?.trim() || phases.length === 0">
          <mat-icon>save</mat-icon> Save as Draft
        </button>
      </div>

      <pre *ngIf="showPreview" class="json-preview">{{ getPreviewJson() }}</pre>
    </div>
  `,
  styles: [`
    .builder-container { padding: 12px 0; }
    .builder-header-fields { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
    .name-field { flex: 1; min-width: 200px; }
    .ontology-field { flex: 1; min-width: 200px; }

    .phases-section { margin-bottom: 16px; }
    .section-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
    .section-title { display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 500; }
    .section-title mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }

    .phases-list { display: flex; flex-direction: column; gap: 8px; }
    .phase-panel { background: #1e1e2e !important; margin-bottom: 4px !important; }
    .drag-handle { cursor: grab; color: #555; font-size: 18px; width: 18px; height: 18px; margin-right: 6px; }
    .phase-order {
      display: inline-flex; align-items: center; justify-content: center;
      width: 22px; height: 22px; border-radius: 50%; background: rgba(144,202,249,0.2);
      color: #90caf9; font-size: 12px; font-weight: 600; margin-right: 8px; flex-shrink: 0;
    }

    .phase-fields { margin-bottom: 16px; }
    .full-width { width: 100%; }
    .field-row { display: flex; gap: 12px; }
    .flex-field { flex: 1; min-width: 150px; }
    .method-field { width: 120px; min-width: 120px; }

    .steps-section { margin-top: 8px; }
    .steps-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
    .sub-title { font-size: 12px; font-weight: 600; color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px; }
    .add-step-btn { font-size: 12px; }

    .steps-list { display: flex; flex-direction: column; gap: 6px; }
    .step-card {
      background: #141420; border: 1px solid rgba(255,255,255,0.06); border-radius: 6px;
      padding: 10px 12px;
    }
    .step-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
    .step-card-name { flex: 1; font-weight: 500; font-size: 13px; }

    .step-type-chip { font-size: 10px !important; min-height: 20px !important; font-weight: 700 !important; }
    .step-auto { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .step-tool_call { background: rgba(77,208,225,0.2) !important; color: #4dd0e1 !important; }
    .step-http_call { background: rgba(100,181,246,0.2) !important; color: #64b5f6 !important; }
    .step-script { background: rgba(174,213,129,0.2) !important; color: #aed581 !important; }
    .step-excel_compute { background: rgba(129,199,132,0.2) !important; color: #81c784 !important; }
    .step-pipeline { background: rgba(149,117,205,0.2) !important; color: #9575cd !important; }
    .step-camel_route { background: rgba(255,138,101,0.2) !important; color: #ff8a65 !important; }
    .step-drools_rule { background: rgba(240,98,146,0.2) !important; color: #f06292 !important; }
    .step-drools_inference { background: rgba(236,64,122,0.2) !important; color: #ec407a !important; }
    .step-drools_decision_table { background: rgba(244,143,177,0.2) !important; color: #f48fb1 !important; }
    .step-workflow { background: rgba(77,182,172,0.2) !important; color: #4db6ac !important; }
    .step-approve { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .step-human { background: rgba(144,202,249,0.2) !important; color: #90caf9 !important; }
    .step-control_gate { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }

    .step-fields { display: flex; flex-direction: column; gap: 4px; }
    .conditional-fields { border-left: 3px solid rgba(255,183,77,0.3); padding-left: 12px; margin: 4px 0; }
    .code-field textarea {
      font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
      font-size: 12px;
      line-height: 1.45;
      min-height: 76px;
    }

    .remove-btn { color: #ef5350; }
    .remove-btn mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .empty-phases, .empty-steps { text-align: center; padding: 24px; color: #666; font-size: 13px; }
    .empty-phases mat-icon { font-size: 40px; width: 40px; height: 40px; color: #444; display: block; margin: 0 auto 8px; }

    .section-divider { margin: 16px 0; }
    .builder-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }

    .json-preview {
      background: #111; padding: 12px; border-radius: 6px;
      font-size: 11px; font-family: 'JetBrains Mono', monospace;
      white-space: pre-wrap; max-height: 400px; overflow-y: auto; margin-top: 12px;
    }

    /* CDK drag-drop feedback */
    .cdk-drag-preview { box-shadow: 0 4px 20px rgba(0,0,0,0.5); opacity: 0.9; }
    .cdk-drag-placeholder { opacity: 0.3; }
    .cdk-drag-animating { transition: transform 200ms ease; }
  `]
})
export class ProcessBuilderComponent {
  @Output() saved = new EventEmitter<ProcessDefinition>();

  definition: Partial<ProcessDefinition> = { name: '' };
  phases: ProcessPhase[] = [];
  showPreview = false;

  private nextPhaseOrder = 1;
  private jsonMapCache: Record<string, string> = {};

  addPhase(): void {
    const id = 'phase-' + this.nextPhaseOrder;
    this.phases.push({
      id,
      name: '',
      order: this.nextPhaseOrder++,
      steps: []
    });
  }

  removePhase(index: number): void {
    this.phases.splice(index, 1);
    this.reorderPhases();
  }

  dropPhase(event: CdkDragDrop<ProcessPhase[]>): void {
    moveItemInArray(this.phases, event.previousIndex, event.currentIndex);
    this.reorderPhases();
  }

  addStep(phase: ProcessPhase): void {
    if (!phase.steps) phase.steps = [];
    const stepNum = phase.steps.length + 1;
    phase.steps.push({
      id: phase.id + '-step-' + stepNum,
      name: '',
      stepType: 'AUTO'
    });
  }

  removeStep(phase: ProcessPhase, index: number): void {
    phase.steps?.splice(index, 1);
  }

  dropStep(event: CdkDragDrop<ProcessStep[]>, phase: ProcessPhase): void {
    if (phase.steps) {
      moveItemInArray(phase.steps, event.previousIndex, event.currentIndex);
    }
  }

  splitKeys(value: string): string[] {
    if (!value || !value.trim()) return [];
    return value.split(',').map(s => s.trim()).filter(s => s.length > 0);
  }

  ensureApprovalPolicy(step: ProcessStep): void {
    if (!step.approvalPolicy) {
      step.approvalPolicy = { approverPool: [] };
    }
  }

  jsonMapText(step: ProcessStep, field: JsonMapField): string {
    const cacheKey = this.jsonCacheKey(step, field);
    if (this.jsonMapCache[cacheKey] !== undefined) {
      return this.jsonMapCache[cacheKey];
    }
    const value = (step as any)[field];
    if (!value || Object.keys(value).length === 0) {
      return '';
    }
    return JSON.stringify(value, null, 2);
  }

  setJsonMap(step: ProcessStep, field: JsonMapField, value: string): void {
    const cacheKey = this.jsonCacheKey(step, field);
    this.jsonMapCache[cacheKey] = value;
    if (!value || !value.trim()) {
      delete (step as any)[field];
      delete this.jsonMapCache[cacheKey];
      return;
    }

    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        (step as any)[field] = parsed;
      }
    } catch {
      // Keep the user's partial JSON in the textarea until it becomes valid.
    }
  }

  emitSave(): void {
    const def: ProcessDefinition = {
      name: this.definition.name || '',
      ontologySchemaId: this.definition.ontologySchemaId,
      phases: this.phases.map((p, i) => ({ ...p, order: i + 1 }))
    };
    this.saved.emit(def);
  }

  getPreviewJson(): string {
    const def: ProcessDefinition = {
      name: this.definition.name || '',
      ontologySchemaId: this.definition.ontologySchemaId,
      phases: this.phases.map((p, i) => ({ ...p, order: i + 1 }))
    };
    return JSON.stringify(def, null, 2);
  }

  private reorderPhases(): void {
    this.phases.forEach((p, i) => { p.order = i + 1; });
  }

  private jsonCacheKey(step: ProcessStep, field: JsonMapField): string {
    return (step.id || step.name || 'step') + ':' + field;
  }
}
