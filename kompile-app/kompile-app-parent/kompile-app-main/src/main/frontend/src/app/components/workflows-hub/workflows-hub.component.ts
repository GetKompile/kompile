import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatCardModule } from '@angular/material/card';
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { WorkflowService } from '../../services/workflow.service';
import { ComputeArtifact } from '../../models/compute-graph-models';
import {
  WorkflowDetail,
  WorkflowInspection,
  WorkflowInspectionNode,
  WorkflowArgument,
  WorkflowEngineType,
  WorkflowExecutionResult,
  WorkflowNodeResult
} from '../../models/workflow-models';

type ViewMode = 'list' | 'create' | 'edit' | 'inspect' | 'execute';

interface EngineOption {
  value: WorkflowEngineType;
  title: string;
  language: string;
  icon: string;
  blurb: string;
  bestFor: string;
}

@Component({
  standalone: true,
  selector: 'app-workflows-hub',
  imports: [
    CommonModule, FormsModule,
    MatTabsModule, MatIconModule, MatButtonModule, MatSnackBarModule,
    MatChipsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressSpinnerModule, MatTooltipModule, MatDividerModule, MatCardModule,
    MatStepperModule
  ],
  template: `
    <div class="workflows-container">
      <!-- Header -->
      <div class="workflows-header">
        <div class="header-left">
          <mat-icon class="header-icon">account_tree</mat-icon>
          <div>
            <h3>Workflow Manager</h3>
            <p class="header-sub">Create, inspect, and execute Xircuits and n8n workflows</p>
          </div>
        </div>
        <div class="header-actions">
          <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
            <mat-icon>add</mat-icon> New Workflow
          </button>
          <button mat-button (click)="viewMode = 'list'" *ngIf="viewMode !== 'list'">
            <mat-icon>arrow_back</mat-icon> Back to List
          </button>
        </div>
      </div>

      <!-- List View -->
      <div *ngIf="viewMode === 'list'" class="list-view">
        <div class="filter-bar">
          <mat-form-field appearance="outline" class="filter-select">
            <mat-label>Engine Type</mat-label>
            <mat-select [(value)]="filterEngine" (selectionChange)="loadWorkflows()">
              <mat-option value="all">All</mat-option>
              <mat-option value="xircuits">Xircuits (Python)</mat-option>
              <mat-option value="n8n">n8n (JavaScript)</mat-option>
            </mat-select>
          </mat-form-field>
          <button mat-icon-button (click)="loadWorkflows()" matTooltip="Refresh">
            <mat-icon>refresh</mat-icon>
          </button>
        </div>

        <div *ngIf="loading" class="loading-container">
          <mat-spinner diameter="40"></mat-spinner>
          <span>Loading workflows...</span>
        </div>

        <div *ngIf="!loading && workflows.length === 0" class="empty-state">
          <mat-icon class="empty-icon">folder_open</mat-icon>
          <p>No workflows yet</p>
          <p class="empty-sub">
            A workflow is a saved graph of steps run by the Xircuits (Python) or n8n (JavaScript) engine.
            Create one with the guided wizard &mdash; it walks you through picking an engine, naming it,
            and supplying the definition.
          </p>
          <button mat-raised-button color="primary" (click)="startCreate()">
            <mat-icon>add</mat-icon> Create Your First Workflow
          </button>
        </div>

        <div *ngIf="!loading && workflows.length > 0" class="workflow-grid">
          <div *ngFor="let wf of workflows" class="workflow-card" (click)="openWorkflow(wf)">
            <div class="card-header">
              <mat-icon [class]="getEngineClass(wf)">
                {{getEngineIcon(wf)}}
              </mat-icon>
              <div class="card-title-block">
                <span class="card-name">{{getWorkflowName(wf)}}</span>
                <span class="card-engine">{{getEngineLabel(wf)}}</span>
              </div>
              <div class="card-actions" (click)="$event.stopPropagation()">
                <button mat-icon-button matTooltip="Inspect" (click)="inspectWorkflow(wf)">
                  <mat-icon>visibility</mat-icon>
                </button>
                <button mat-icon-button matTooltip="Execute" (click)="executeWorkflow(wf)">
                  <mat-icon>play_arrow</mat-icon>
                </button>
                <button mat-icon-button matTooltip="Delete" color="warn" (click)="deleteWorkflow(wf)">
                  <mat-icon>delete</mat-icon>
                </button>
              </div>
            </div>
            <div class="card-body">
              <span class="card-desc" *ngIf="wf.metadata?.['description']">{{wf.metadata?.['description']}}</span>
              <div class="card-meta">
                <span *ngIf="wf.sizeBytes">{{formatSize(wf.sizeBytes)}}</span>
                <span *ngIf="wf.metadata?.['updatedAt']">{{formatDate(wf.metadata?.['updatedAt'])}}</span>
              </div>
              <div class="card-tags" *ngIf="wf.metadata?.['tags']?.length">
                <mat-chip-set>
                  <mat-chip *ngFor="let tag of wf.metadata?.['tags']">{{tag}}</mat-chip>
                </mat-chip-set>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Create / Edit Wizard -->
      <div *ngIf="viewMode === 'create' || viewMode === 'edit'" class="editor-view">
        <h3>{{isEdit ? 'Edit Workflow: ' + editName : 'Create New Workflow'}}</h3>
        <p class="wizard-sub" *ngIf="isEdit">
          The engine and name are fixed once a workflow exists. Jump straight to <strong>Definition</strong> to change its steps.
        </p>

        <mat-stepper linear #wfStepper class="wf-wizard" [animationDuration]="'200ms'">

          <!-- Step 1: Choose engine -->
          <mat-step [completed]="true" [editable]="true">
            <ng-template matStepLabel>Choose engine</ng-template>
            <div class="step-body">
              <div class="wizard-intro">
                <mat-icon>category</mat-icon>
                <div>
                  <strong>Which engine runs this workflow?</strong>
                  <p>The engine determines the file format and how steps execute. Pick the one that matches your definition.</p>
                </div>
              </div>

              <div class="engine-cards">
                <div *ngFor="let opt of engineOptions" class="engine-card"
                     [class.selected]="editEngine === opt.value" [class.locked]="isEdit"
                     (click)="selectEngine(opt.value)">
                  <div class="engine-card-head">
                    <mat-icon [class]="opt.value === 'xircuits' ? 'engine-xircuits' : 'engine-n8n'">{{opt.icon}}</mat-icon>
                    <div>
                      <span class="engine-title">{{opt.title}}</span>
                      <span class="engine-lang">{{opt.language}}</span>
                    </div>
                    <mat-icon class="check" *ngIf="editEngine === opt.value">check_circle</mat-icon>
                  </div>
                  <p class="engine-blurb">{{opt.blurb}}</p>
                  <p class="engine-bestfor"><strong>Best for:</strong> {{opt.bestFor}}</p>
                </div>
              </div>
              <p class="locked-note" *ngIf="isEdit"><mat-icon>lock</mat-icon> Engine can't be changed after creation.</p>

              <div class="step-actions">
                <span class="spacer"></span>
                <button mat-raised-button color="primary" matStepperNext>Next: details <mat-icon>arrow_forward</mat-icon></button>
              </div>
            </div>
          </mat-step>

          <!-- Step 2: Details -->
          <mat-step [completed]="detailsValid" [editable]="true">
            <ng-template matStepLabel>Details</ng-template>
            <div class="step-body">
              <div class="wizard-intro">
                <mat-icon>badge</mat-icon>
                <div>
                  <strong>Name and describe the workflow</strong>
                  <p>The name identifies the workflow on disk and in the API path, so keep it filename-safe.</p>
                </div>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Workflow name</mat-label>
                <input matInput [(ngModel)]="editName" placeholder="order-intake" [disabled]="isEdit">
                <mat-hint>Required. Letters, numbers, dots, dashes and underscores only &mdash; no spaces.</mat-hint>
              </mat-form-field>
              <p class="field-error" *ngIf="!isEdit && editName.trim() && !nameValid">
                Name may only contain letters, numbers, <code>.</code> <code>-</code> <code>_</code>.
              </p>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Description</mat-label>
                <input matInput [(ngModel)]="editDescription" placeholder="What does this workflow do?">
                <mat-hint>Optional. Shown on the workflow card.</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Tags</mat-label>
                <input matInput [(ngModel)]="editTagsStr" placeholder="automation, etl, data-pipeline">
                <mat-hint>Optional. Comma-separated labels for grouping and search.</mat-hint>
              </mat-form-field>

              <div class="step-actions">
                <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                <span class="spacer"></span>
                <button mat-raised-button color="primary" matStepperNext [disabled]="!detailsValid">
                  Next: definition <mat-icon>arrow_forward</mat-icon>
                </button>
              </div>
              <p class="step-gate-hint" *ngIf="!detailsValid">Enter a valid name to continue.</p>
            </div>
          </mat-step>

          <!-- Step 3: Definition -->
          <mat-step [completed]="definitionValid" [editable]="true">
            <ng-template matStepLabel>Definition</ng-template>
            <div class="step-body">
              <div class="wizard-intro">
                <mat-icon>data_object</mat-icon>
                <div>
                  <strong>Provide the {{engineLabel(editEngine)}} definition</strong>
                  <p>
                    Paste the workflow JSON exported from your editor, or start from a template and edit it.
                    Use <em>Inspect</em> to see the nodes, trigger, and required credentials the engine detects.
                  </p>
                </div>
              </div>

              <div class="definition-toolbar">
                <button mat-stroked-button (click)="loadTemplate()">
                  <mat-icon>note_add</mat-icon> Insert {{engineLabel(editEngine)}} starter template
                </button>
                <button mat-stroked-button (click)="inspectInline()" [disabled]="!definitionValid || inspecting">
                  <mat-icon>{{inspecting ? 'hourglass_empty' : 'visibility'}}</mat-icon> Inspect
                </button>
                <span class="json-status" [class.ok]="!jsonError && editContent.trim()" [class.bad]="jsonError">
                  <mat-icon>{{jsonError ? 'error' : (editContent.trim() ? 'check_circle' : 'edit')}}</mat-icon>
                  {{jsonError ? jsonError : (editContent.trim() ? 'Valid JSON' : 'Empty')}}
                </span>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Workflow JSON</mat-label>
                <textarea matInput [ngModel]="editContent" (ngModelChange)="editContent = $event; checkJson()" rows="18"
                          class="json-editor" placeholder="Paste workflow JSON here, or use the starter template above..."></textarea>
              </mat-form-field>

              <div class="inline-inspect" *ngIf="inlineInspection">
                <h4><mat-icon>visibility</mat-icon> Inspection</h4>
                <div class="inspect-summary">
                  <div class="summary-item"><mat-icon>hub</mat-icon><span>{{inlineInspection.nodeCount}} nodes</span></div>
                  <div class="summary-item" *ngIf="inlineInspection.linkCount != null"><mat-icon>link</mat-icon><span>{{inlineInspection.linkCount}} links</span></div>
                  <div class="summary-item" *ngIf="inlineInspection.edgeCount != null"><mat-icon>link</mat-icon><span>{{inlineInspection.edgeCount}} edges</span></div>
                  <div class="summary-item" *ngIf="inlineInspection.triggerNode"><mat-icon>bolt</mat-icon><span>Trigger: {{inlineInspection.triggerNode}}</span></div>
                </div>
                <div class="inspect-creds" *ngIf="inlineInspection.requiredCredentialTypes?.length">
                  <mat-icon>key</mat-icon> Requires credentials:
                  <mat-chip-set>
                    <mat-chip *ngFor="let cred of inlineInspection.requiredCredentialTypes" color="warn" highlighted>{{cred}}</mat-chip>
                  </mat-chip-set>
                </div>
              </div>

              <div class="step-actions">
                <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                <span class="spacer"></span>
                <button mat-raised-button color="primary" matStepperNext [disabled]="!definitionValid">
                  Next: review <mat-icon>arrow_forward</mat-icon>
                </button>
              </div>
              <p class="step-gate-hint" *ngIf="!definitionValid">Paste valid workflow JSON to continue.</p>
            </div>
          </mat-step>

          <!-- Step 4: Review & Save -->
          <mat-step [editable]="true">
            <ng-template matStepLabel>Review &amp; save</ng-template>
            <div class="step-body">
              <div class="wizard-intro">
                <mat-icon>fact_check</mat-icon>
                <div>
                  <strong>Review and save</strong>
                  <p>Confirm the details below, then save. You can run the workflow from the list afterwards.</p>
                </div>
              </div>

              <div class="review-grid">
                <div class="review-row"><span>Engine</span><strong>{{engineLabel(editEngine)}}</strong></div>
                <div class="review-row"><span>Name</span><strong>{{editName || '(none)'}}</strong></div>
                <div class="review-row"><span>Description</span><strong>{{editDescription || '(none)'}}</strong></div>
                <div class="review-row"><span>Tags</span><strong>{{editTagsStr || '(none)'}}</strong></div>
                <div class="review-row"><span>Definition size</span><strong>{{editContent.length}} chars</strong></div>
                <div class="review-row" *ngIf="inlineInspection"><span>Detected nodes</span><strong>{{inlineInspection.nodeCount}}</strong></div>
              </div>

              <div class="step-actions">
                <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                <span class="spacer"></span>
                <button mat-button (click)="viewMode = 'list'">Cancel</button>
                <button mat-raised-button color="primary" (click)="saveWorkflow()"
                        [disabled]="!detailsValid || !definitionValid || saving">
                  <mat-icon>save</mat-icon> {{saving ? 'Saving...' : 'Save workflow'}}
                </button>
              </div>
            </div>
          </mat-step>
        </mat-stepper>
      </div>

      <!-- Inspect View -->
      <div *ngIf="viewMode === 'inspect' && inspection" class="inspect-view">
        <h3>Workflow Inspection: {{inspection.name || inspectingName}}</h3>
        <div class="wizard-intro">
          <mat-icon>visibility</mat-icon>
          <div>
            <strong>What the engine sees</strong>
            <p>
              A read-only breakdown of the workflow's structure &mdash; its steps, how they connect, the trigger
              that starts it, and any credentials it expects. Use this to verify a definition before running it.
            </p>
          </div>
        </div>

        <div class="inspect-summary">
          <div class="summary-item">
            <mat-icon>{{inspection.engineType === 'xircuits' ? 'psychology' : 'webhook'}}</mat-icon>
            <span>{{inspection.engineType === 'xircuits' ? 'Xircuits (Python)' : 'n8n (JavaScript)'}}</span>
          </div>
          <div class="summary-item">
            <mat-icon>hub</mat-icon>
            <span>{{inspection.nodeCount}} nodes</span>
          </div>
          <div class="summary-item" *ngIf="inspection.linkCount != null">
            <mat-icon>link</mat-icon>
            <span>{{inspection.linkCount}} links</span>
          </div>
          <div class="summary-item" *ngIf="inspection.edgeCount != null">
            <mat-icon>link</mat-icon>
            <span>{{inspection.edgeCount}} edges</span>
          </div>
          <div class="summary-item" *ngIf="inspection.triggerNode">
            <mat-icon>bolt</mat-icon>
            <span>Trigger: {{inspection.triggerNode}}</span>
          </div>
        </div>

        <!-- Xircuits Arguments -->
        <div *ngIf="inspection.arguments?.length" class="inspect-section">
          <h4><mat-icon>input</mat-icon> Arguments</h4>
          <p class="section-hint">Inputs this workflow accepts. Supply matching keys in the input JSON when you execute it.</p>
          <div class="args-list">
            <div *ngFor="let arg of inspection.arguments" class="arg-item">
              <span class="arg-name">{{arg.name || arg.fullName}}</span>
              <span class="arg-type" *ngIf="arg.type">{{arg.type}}</span>
            </div>
          </div>
        </div>

        <!-- n8n Credential Requirements -->
        <div *ngIf="inspection.requiredCredentialTypes?.length" class="inspect-section">
          <h4><mat-icon>key</mat-icon> Required Credentials</h4>
          <p class="section-hint">The workflow won't run until these credential types are configured.</p>
          <mat-chip-set>
            <mat-chip *ngFor="let cred of inspection.requiredCredentialTypes" color="warn" highlighted>
              {{cred}}
            </mat-chip>
          </mat-chip-set>
        </div>

        <!-- Nodes List -->
        <div class="inspect-section">
          <h4><mat-icon>hub</mat-icon> Nodes</h4>
          <p class="section-hint">Every step in the workflow. The highlighted node is the trigger that starts execution.</p>
          <div class="nodes-list">
            <div *ngFor="let node of inspection.nodes" class="node-item"
                 [class.trigger-node]="node.isTrigger" [class.disabled-node]="node.disabled">
              <mat-icon *ngIf="node.isTrigger" class="trigger-icon">bolt</mat-icon>
              <mat-icon *ngIf="!node.isTrigger">circle</mat-icon>
              <div class="node-info">
                <span class="node-name">{{node.name}}</span>
                <span class="node-type">{{node.type || node.componentType || ''}}</span>
              </div>
              <mat-icon *ngIf="node.disabled" class="disabled-icon" matTooltip="Disabled">block</mat-icon>
            </div>
          </div>
        </div>
      </div>

      <!-- Execute View -->
      <div *ngIf="viewMode === 'execute'" class="execute-view">
        <h3>Execute Workflow: {{executingName}}</h3>

        <!-- Input Parameters -->
        <div class="execute-inputs" *ngIf="!executionResult">
          <div class="wizard-intro">
            <mat-icon>play_circle</mat-icon>
            <div>
              <strong>Run the workflow</strong>
              <p>
                Optionally pass an input JSON object &mdash; its keys map to the workflow's arguments
                (run <em>Inspect</em> first to see them). The run is aborted if it exceeds the timeout.
              </p>
            </div>
          </div>

          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Input JSON (optional)</mat-label>
            <textarea matInput [(ngModel)]="executeInputJson" rows="6" class="json-editor"
                      placeholder='{"key": "value"}'></textarea>
            <mat-hint>Leave empty to run with no inputs.</mat-hint>
          </mat-form-field>

          <mat-form-field appearance="outline" class="field-timeout">
            <mat-label>Timeout (seconds)</mat-label>
            <input matInput type="number" [(ngModel)]="executeTimeout" placeholder="300">
            <mat-hint>Maximum run time before the workflow is cancelled.</mat-hint>
          </mat-form-field>

          <div class="execute-actions">
            <button mat-button (click)="viewMode = 'list'">Cancel</button>
            <button mat-raised-button color="primary" (click)="runExecution()" [disabled]="executing">
              <mat-icon>{{executing ? 'hourglass_empty' : 'play_arrow'}}</mat-icon>
              {{executing ? 'Running...' : 'Execute'}}
            </button>
          </div>
        </div>

        <!-- Execution Results -->
        <div *ngIf="executionResult" class="execution-results">
          <div class="result-header" [class]="'status-' + executionResult.executionStatus.toLowerCase()">
            <mat-icon>{{executionResult.executionStatus === 'COMPLETED' ? 'check_circle' : 'error'}}</mat-icon>
            <span>{{executionResult.executionStatus}}</span>
            <span class="exec-id">ID: {{executionResult.executionId}}</span>
          </div>

          <div *ngIf="executionResult.error" class="result-error">
            <mat-icon>warning</mat-icon>
            <span>{{executionResult.error}}</span>
          </div>

          <div *ngIf="executionResult.executionOrder?.length" class="result-section">
            <h4>Execution Order</h4>
            <div class="exec-order">
              <span *ngFor="let nodeId of executionResult.executionOrder; let last = last" class="order-step">
                {{nodeId}}<mat-icon *ngIf="!last">arrow_forward</mat-icon>
              </span>
            </div>
          </div>

          <div *ngIf="executionResult.nodeResults" class="result-section">
            <h4>Node Results</h4>
            <div *ngFor="let entry of getNodeResultEntries()" class="node-result">
              <div class="nr-header" [class]="'status-' + entry.value.status.toLowerCase()">
                <span class="nr-id">{{entry.key}}</span>
                <span class="nr-status">{{entry.value.status}}</span>
                <span class="nr-duration" *ngIf="entry.value.duration">{{entry.value.duration}}</span>
              </div>
              <pre *ngIf="entry.value.consoleOutput" class="nr-console">{{entry.value.consoleOutput}}</pre>
              <pre *ngIf="entry.value.outputs" class="nr-outputs">{{entry.value.outputs | json}}</pre>
              <div *ngIf="entry.value.error" class="nr-error">{{entry.value.error}}</div>
            </div>
          </div>

          <div *ngIf="executionResult.outputs" class="result-section">
            <h4>Final Outputs</h4>
            <pre class="result-json">{{executionResult.outputs | json}}</pre>
          </div>

          <button mat-raised-button (click)="executionResult = null" class="run-again-btn">
            <mat-icon>replay</mat-icon> Run Again
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .workflows-container { height: 100%; padding: 0 16px 16px; }
    .workflows-header {
      display: flex; justify-content: space-between; align-items: center;
      padding: 16px 4px; border-bottom: 1px solid rgba(255,255,255,0.12);
    }
    .header-left { display: flex; align-items: center; gap: 12px; }
    .header-icon { font-size: 32px; width: 32px; height: 32px; color: #81c784; }
    .header-left h3 { margin: 0; font-size: 18px; }
    .header-sub { margin: 2px 0 0; font-size: 12px; color: #999; }

    .filter-bar { display: flex; align-items: center; gap: 12px; padding: 16px 0 0; }
    .filter-select { width: 220px; }

    .loading-container { display: flex; align-items: center; gap: 12px; padding: 40px; justify-content: center; }
    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-state .empty-sub { max-width: 520px; margin: 4px auto 16px; font-size: 12.5px; line-height: 1.5; color: #888; }
    .empty-icon { font-size: 64px; width: 64px; height: 64px; color: #555; }

    .workflow-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 12px; padding: 16px 0; }
    .workflow-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      cursor: pointer; transition: border-color 0.2s;
    }
    .workflow-card:hover { border-color: rgba(129,199,132,0.5); }
    .card-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
    .card-header > mat-icon { font-size: 24px; width: 24px; height: 24px; flex-shrink: 0; }
    .engine-xircuits { color: #81c784; }
    .engine-n8n { color: #ffb74d; }
    .card-title-block { flex: 1; min-width: 0; }
    .card-name { display: block; font-weight: 500; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .card-engine { font-size: 11px; color: #888; }
    .card-actions { display: flex; gap: 2px; }
    .card-actions button { transform: scale(0.85); }
    .card-body { padding: 0 14px 12px; }
    .card-desc { font-size: 12px; color: #aaa; display: block; margin-bottom: 6px; }
    .card-meta { display: flex; gap: 12px; font-size: 11px; color: #666; }
    .card-tags { margin-top: 6px; }
    .card-tags mat-chip { font-size: 11px; min-height: 22px; }

    .editor-view, .inspect-view, .execute-view { padding: 16px 0; }
    .editor-view h3, .inspect-view h3, .execute-view h3 { margin: 0 0 8px; }
    .wizard-sub { margin: 0 0 12px; font-size: 12px; color: #999; }
    .wf-wizard { background: transparent; }
    .step-body { padding: 8px 4px 4px; }
    .full-width { width: 100%; }
    .field-timeout { width: 200px; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .execute-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .wizard-intro {
      display: flex; gap: 12px; align-items: flex-start;
      background: rgba(129,199,132,0.08); border: 1px solid rgba(129,199,132,0.25);
      border-radius: 8px; padding: 12px 14px; margin-bottom: 16px;
    }
    .wizard-intro mat-icon { color: #81c784; flex-shrink: 0; }
    .wizard-intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .wizard-intro p { margin: 0; font-size: 12.5px; color: #bbb; line-height: 1.5; }

    .engine-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .engine-card {
      background: #1e1e2e; border: 2px solid rgba(255,255,255,0.08); border-radius: 8px;
      padding: 14px; cursor: pointer; transition: border-color 0.15s, background 0.15s;
    }
    .engine-card:hover { border-color: rgba(129,199,132,0.4); }
    .engine-card.selected { border-color: #81c784; background: rgba(129,199,132,0.06); }
    .engine-card.locked { cursor: default; opacity: 0.85; }
    .engine-card.locked:hover { border-color: rgba(255,255,255,0.08); }
    .engine-card.locked.selected { border-color: #81c784; }
    .engine-card-head { display: flex; align-items: center; gap: 10px; }
    .engine-card-head > mat-icon { font-size: 28px; width: 28px; height: 28px; }
    .engine-title { display: block; font-weight: 500; font-size: 14px; }
    .engine-lang { display: block; font-size: 11px; color: #888; }
    .engine-card-head .check { margin-left: auto; color: #81c784; font-size: 22px; width: 22px; height: 22px; }
    .engine-blurb { font-size: 12.5px; color: #bbb; line-height: 1.5; margin: 10px 0 6px; }
    .engine-bestfor { font-size: 12px; color: #999; margin: 0; }
    .engine-bestfor strong { color: #bbb; }
    .locked-note { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #888; margin-top: 10px; }
    .locked-note mat-icon { font-size: 16px; width: 16px; height: 16px; }

    .field-error { color: #ef5350; font-size: 12px; margin: -8px 0 8px; }
    .field-error code { background: rgba(255,255,255,0.08); padding: 0 4px; border-radius: 3px; }

    .definition-toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
    .json-status { display: flex; align-items: center; gap: 4px; font-size: 12px; color: #888; margin-left: auto; }
    .json-status mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .json-status.ok { color: #81c784; }
    .json-status.bad { color: #ef5350; }

    .inline-inspect {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      padding: 12px 14px; margin: 4px 0 12px;
    }
    .inline-inspect h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 8px; font-size: 13px; }
    .inline-inspect h4 mat-icon { font-size: 18px; width: 18px; height: 18px; color: #81c784; }
    .inspect-creds { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 8px; font-size: 12.5px; color: #ffb74d; }
    .inspect-creds mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .step-actions { display: flex; align-items: center; gap: 8px; padding-top: 12px; }
    .spacer { flex: 1; }
    .step-gate-hint { font-size: 12px; color: #ffb74d; margin: 6px 0 0; }

    .review-grid {
      display: grid; grid-template-columns: 1fr; gap: 1px;
      background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.06); border-radius: 8px; overflow: hidden;
    }
    .review-row { display: flex; justify-content: space-between; gap: 16px; padding: 10px 14px; background: #1e1e2e; font-size: 13px; }
    .review-row span { color: #aaa; }
    .review-row strong { font-weight: 500; text-align: right; word-break: break-word; }

    .inspect-summary {
      display: flex; gap: 20px; flex-wrap: wrap; padding: 12px 16px;
      background: #1e1e2e; border-radius: 8px; margin-bottom: 16px;
    }
    .summary-item { display: flex; align-items: center; gap: 6px; font-size: 13px; }
    .summary-item mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }

    .inspect-section { margin-bottom: 20px; }
    .inspect-section h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 4px; font-size: 14px; }
    .inspect-section h4 mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .section-hint { font-size: 12px; color: #999; margin: 0 0 8px; line-height: 1.4; }

    .args-list { display: flex; flex-wrap: wrap; gap: 8px; }
    .arg-item {
      background: #252540; padding: 6px 12px; border-radius: 6px; font-size: 13px;
      display: flex; gap: 8px; align-items: center;
    }
    .arg-name { font-weight: 500; }
    .arg-type { color: #81c784; font-size: 11px; background: rgba(129,199,132,0.1); padding: 2px 6px; border-radius: 4px; }

    .nodes-list { display: flex; flex-direction: column; gap: 4px; }
    .node-item {
      display: flex; align-items: center; gap: 8px; padding: 6px 12px;
      background: #1e1e2e; border-radius: 6px; font-size: 13px;
    }
    .node-item mat-icon { font-size: 16px; width: 16px; height: 16px; color: #666; }
    .trigger-node { border-left: 3px solid #ffb74d; }
    .trigger-icon { color: #ffb74d !important; }
    .disabled-node { opacity: 0.5; }
    .disabled-icon { color: #ef5350 !important; margin-left: auto; }
    .node-info { flex: 1; }
    .node-name { font-weight: 500; }
    .node-type { margin-left: 8px; font-size: 11px; color: #888; }

    .result-header {
      display: flex; align-items: center; gap: 8px; padding: 12px 16px;
      border-radius: 8px; font-size: 15px; font-weight: 500; margin-bottom: 12px;
    }
    .status-completed { background: rgba(129,199,132,0.15); color: #81c784; }
    .status-failed { background: rgba(239,83,80,0.15); color: #ef5350; }
    .status-running { background: rgba(144,202,249,0.15); color: #90caf9; }
    .exec-id { margin-left: auto; font-size: 11px; color: #888; font-weight: 400; }

    .result-error {
      display: flex; align-items: flex-start; gap: 8px; padding: 12px;
      background: rgba(239,83,80,0.1); border-radius: 6px; margin-bottom: 12px;
      color: #ef5350; font-size: 13px;
    }
    .result-section { margin-bottom: 16px; }
    .result-section h4 { margin: 0 0 8px; font-size: 14px; }
    .exec-order { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .order-step {
      display: flex; align-items: center; gap: 4px;
      background: #252540; padding: 4px 10px; border-radius: 4px; font-size: 12px; font-family: monospace;
    }
    .order-step mat-icon { font-size: 14px; width: 14px; height: 14px; color: #666; }

    .node-result { margin-bottom: 8px; border: 1px solid rgba(255,255,255,0.06); border-radius: 6px; overflow: hidden; }
    .nr-header { display: flex; align-items: center; gap: 8px; padding: 8px 12px; font-size: 13px; }
    .nr-id { font-family: monospace; font-weight: 500; }
    .nr-status { margin-left: auto; }
    .nr-duration { font-size: 11px; color: #888; }
    .nr-console, .nr-outputs, .result-json {
      margin: 0; padding: 10px 12px; background: #111; font-size: 12px;
      font-family: monospace; white-space: pre-wrap; overflow-x: auto; max-height: 300px;
    }
    .nr-error { padding: 8px 12px; color: #ef5350; font-size: 12px; }

    .run-again-btn { margin-top: 12px; }
  `]
})
export class WorkflowsHubComponent implements OnInit {
  @ViewChild('wfStepper') wfStepper?: MatStepper;

  viewMode: ViewMode = 'list';
  workflows: ComputeArtifact[] = [];
  loading = false;
  saving = false;
  executing = false;
  inspecting = false;
  filterEngine: string = 'all';

  engineOptions: EngineOption[] = [
    {
      value: 'xircuits', title: 'Xircuits', language: 'Python', icon: 'psychology',
      blurb: 'Visual Python workflows built from components. Each node is a Python step; the engine runs them in order, passing typed data along the links.',
      bestFor: 'ML / data pipelines and anything that calls Python libraries.'
    },
    {
      value: 'n8n', title: 'n8n', language: 'JavaScript', icon: 'webhook',
      blurb: 'Event-driven automations of nodes connected to a trigger. Great for wiring APIs, webhooks, and SaaS tools together with little or no code.',
      bestFor: 'Integrations, notifications, and API-to-API automation.'
    }
  ];

  // Editor state
  editEngine: WorkflowEngineType = 'xircuits';
  editName = '';
  editDescription = '';
  editTagsStr = '';
  editContent = '';
  jsonError: string | null = null;
  inlineInspection: WorkflowInspection | null = null;

  // Inspect state
  inspection: WorkflowInspection | null = null;
  inspectingName = '';

  // Execute state
  executingEngine: WorkflowEngineType = 'xircuits';
  executingName = '';
  executeInputJson = '';
  executeTimeout = 300;
  executionResult: WorkflowExecutionResult | null = null;

  constructor(
    private workflowService: WorkflowService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadWorkflows();
  }

  get isEdit(): boolean {
    return this.viewMode === 'edit';
  }

  get nameValid(): boolean {
    return /^[A-Za-z0-9._-]+$/.test(this.editName.trim());
  }

  get detailsValid(): boolean {
    if (this.editName.trim().length === 0) return false;
    // An existing workflow's name is fixed (and may contain '/'); only enforce the
    // filename-safe rule when creating a brand-new workflow.
    return this.isEdit ? true : this.nameValid;
  }

  get definitionValid(): boolean {
    return this.editContent.trim().length > 0 && this.jsonError == null;
  }

  engineLabel(engine: WorkflowEngineType): string {
    return engine === 'xircuits' ? 'Xircuits (Python)' : 'n8n (JavaScript)';
  }

  selectEngine(engine: WorkflowEngineType): void {
    if (this.isEdit) return;
    this.editEngine = engine;
  }

  checkJson(): void {
    if (!this.editContent.trim()) {
      this.jsonError = null;
      return;
    }
    try {
      JSON.parse(this.editContent);
      this.jsonError = null;
    } catch (e: any) {
      this.jsonError = 'Invalid JSON: ' + (e?.message || 'parse error');
    }
  }

  loadTemplate(): void {
    if (this.editContent.trim() &&
        !confirm('Replace the current definition with the starter template?')) {
      return;
    }
    const template = this.editEngine === 'xircuits'
      ? {
          nodes: [
            { id: 'node-start', type: 'Start', name: 'Start', x: 100, y: 200 },
            { id: 'node-end', type: 'Finish', name: 'Finish', x: 400, y: 200 }
          ],
          links: [
            { id: 'link-1', source: 'node-start', target: 'node-end' }
          ]
        }
      : {
          name: this.editName || 'My Workflow',
          nodes: [
            {
              parameters: {},
              id: 'manual-trigger',
              name: 'Manual Trigger',
              type: 'n8n-nodes-base.manualTrigger',
              typeVersion: 1,
              position: [250, 300]
            }
          ],
          connections: {}
        };
    this.editContent = JSON.stringify(template, null, 2);
    this.checkJson();
    this.inlineInspection = null;
    this.snackBar.open('Starter template inserted — edit it to fit your workflow', 'OK', { duration: 2500 });
  }

  inspectInline(): void {
    if (!this.definitionValid) return;
    this.inspecting = true;
    this.workflowService.inspect(this.editEngine, this.editContent).subscribe({
      next: (result) => {
        this.inlineInspection = result;
        this.inspecting = false;
      },
      error: (err) => {
        this.inspecting = false;
        this.snackBar.open('Inspect failed: ' + (err.error?.error || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  loadWorkflows(): void {
    this.loading = true;
    const engine = this.filterEngine === 'all' ? undefined : this.filterEngine as WorkflowEngineType;
    this.workflowService.listWorkflows(engine).subscribe({
      next: (data) => {
        this.workflows = data;
        this.loading = false;
      },
      error: (err) => {
        this.snackBar.open('Failed to load workflows: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.loading = false;
      }
    });
  }

  startCreate(): void {
    this.viewMode = 'create';
    this.editEngine = 'xircuits';
    this.editName = '';
    this.editDescription = '';
    this.editTagsStr = '';
    this.editContent = '';
    this.jsonError = null;
    this.inlineInspection = null;
    this.wfStepper?.reset();
  }

  openWorkflow(wf: ComputeArtifact): void {
    const parts = wf.id.split('/');
    if (parts.length < 2) return;
    const engineType = parts[0] as WorkflowEngineType;
    const name = parts.slice(1).join('/');

    this.workflowService.getWorkflow(engineType, name).subscribe({
      next: (detail) => {
        this.viewMode = 'edit';
        this.editEngine = detail.engineType as WorkflowEngineType;
        this.editName = detail.name;
        this.editContent = detail.content;
        this.editDescription = detail.metadata?.['description'] || '';
        const tags = detail.metadata?.['tags'];
        this.editTagsStr = Array.isArray(tags) ? tags.join(', ') : '';
        this.jsonError = null;
        this.inlineInspection = null;
        this.checkJson();
      },
      error: (err) => {
        this.snackBar.open('Failed to load workflow: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  saveWorkflow(): void {
    this.saving = true;
    const tags = this.editTagsStr.split(',').map(t => t.trim()).filter(t => t);
    const metadata: { [key: string]: any } = {};
    if (this.editDescription) metadata['description'] = this.editDescription;
    if (tags.length) metadata['tags'] = tags;

    this.workflowService.saveWorkflow(this.editEngine, this.editName, this.editContent, metadata).subscribe({
      next: () => {
        this.snackBar.open('Workflow saved', 'Close', { duration: 3000 });
        this.saving = false;
        this.viewMode = 'list';
        this.loadWorkflows();
      },
      error: (err) => {
        this.snackBar.open('Failed to save: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  deleteWorkflow(wf: ComputeArtifact): void {
    const parts = wf.id.split('/');
    if (parts.length < 2) return;
    const engineType = parts[0] as WorkflowEngineType;
    const name = parts.slice(1).join('/');

    if (!confirm(`Delete workflow "${wf.name}"?`)) return;

    this.workflowService.deleteWorkflow(engineType, name).subscribe({
      next: () => {
        this.snackBar.open('Workflow deleted', 'Close', { duration: 3000 });
        this.loadWorkflows();
      },
      error: (err) => {
        this.snackBar.open('Failed to delete: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  inspectWorkflow(wf: ComputeArtifact): void {
    const parts = wf.id.split('/');
    if (parts.length < 2) return;
    const engineType = parts[0] as WorkflowEngineType;
    const name = parts.slice(1).join('/');

    this.workflowService.getWorkflow(engineType, name).subscribe({
      next: (detail) => {
        this.inspectingName = detail.name;
        this.workflowService.inspect(engineType, detail.content).subscribe({
          next: (result) => {
            this.inspection = result;
            this.viewMode = 'inspect';
          },
          error: (err) => {
            this.snackBar.open('Inspect failed: ' + (err.error?.error || err.message), 'Close', { duration: 4000 });
          }
        });
      },
      error: (err) => {
        this.snackBar.open('Failed to load: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  executeWorkflow(wf: ComputeArtifact): void {
    const parts = wf.id.split('/');
    if (parts.length < 2) return;
    this.executingEngine = parts[0] as WorkflowEngineType;
    this.executingName = parts.slice(1).join('/');
    this.executeInputJson = '';
    this.executeTimeout = 300;
    this.executionResult = null;
    this.viewMode = 'execute';
  }

  runExecution(): void {
    this.executing = true;
    let inputs: { [key: string]: any } = {};
    if (this.executeInputJson.trim()) {
      try {
        inputs = JSON.parse(this.executeInputJson);
      } catch {
        this.snackBar.open('Invalid input JSON', 'Close', { duration: 3000 });
        this.executing = false;
        return;
      }
    }

    // Use the REST workflow execute endpoint via the compute graph engine
    this.workflowService['http'].post<WorkflowExecutionResult>(
      `${this.workflowService.backendUrl}/compute-graph/execute`,
      {
        graph: {
          id: 'exec-' + Date.now(),
          name: 'Execute ' + this.executingName,
          nodes: [{
            id: 'workflow-node',
            name: this.executingName,
            executionType: this.executingEngine === 'xircuits' ? 'XIRCUITS' : 'N8N',
            script: '',
            parameters: { timeoutSeconds: this.executeTimeout }
          }],
          edges: []
        },
        inputs
      }
    ).subscribe({
      next: (result) => {
        this.executionResult = {
          executionId: result.executionId || 'unknown',
          executionStatus: result.executionStatus || (result as any).status || 'UNKNOWN',
          executionOrder: result.executionOrder || [],
          outputs: result.outputs || (result as any).finalOutputs,
          error: result.error,
          nodeResults: result.nodeResults,
          status: 'success'
        };
        this.executing = false;
      },
      error: (err) => {
        this.snackBar.open('Execution failed: ' + (err.error?.message || err.message), 'Close', { duration: 5000 });
        this.executing = false;
      }
    });
  }

  // Helpers

  getEngineClass(wf: ComputeArtifact): string {
    return wf.contentType?.includes('xircuits') ? 'engine-xircuits' : 'engine-n8n';
  }

  getEngineIcon(wf: ComputeArtifact): string {
    return wf.contentType?.includes('xircuits') ? 'psychology' : 'webhook';
  }

  getEngineLabel(wf: ComputeArtifact): string {
    return wf.contentType?.includes('xircuits') ? 'Xircuits' : 'n8n';
  }

  getWorkflowName(wf: ComputeArtifact): string {
    return wf.name || wf.id;
  }

  getNodeResultEntries(): { key: string; value: WorkflowNodeResult }[] {
    if (!this.executionResult?.nodeResults) return [];
    return Object.entries(this.executionResult.nodeResults).map(([key, value]) => ({ key, value }));
  }

  formatSize(bytes: number | undefined): string {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  formatDate(dateStr: string | undefined): string {
    if (!dateStr) return '';
    try {
      return new Date(dateStr).toLocaleDateString();
    } catch {
      return dateStr;
    }
  }
}
