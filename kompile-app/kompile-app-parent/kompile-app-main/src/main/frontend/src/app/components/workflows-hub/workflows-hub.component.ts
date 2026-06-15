import { Component, OnInit } from '@angular/core';
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

@Component({
  standalone: true,
  selector: 'app-workflows-hub',
  imports: [
    CommonModule, FormsModule,
    MatTabsModule, MatIconModule, MatButtonModule, MatSnackBarModule,
    MatChipsModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressSpinnerModule, MatTooltipModule, MatDividerModule, MatCardModule
  ],
  template: `
    <div class="workflows-container">
      <!-- Header -->
      <div class="workflows-header">
        <div class="header-left">
          <mat-icon class="header-icon">account_tree</mat-icon>
          <div>
            <h3>Workflow Manager</h3>
            <p class="header-sub">Create, manage, inspect, and execute Xircuits and n8n workflows</p>
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
          <p>No workflows found</p>
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

      <!-- Create / Edit View -->
      <div *ngIf="viewMode === 'create' || viewMode === 'edit'" class="editor-view">
        <h3>{{viewMode === 'create' ? 'Create New Workflow' : 'Edit Workflow: ' + editName}}</h3>

        <div class="form-row">
          <mat-form-field appearance="outline" class="field-engine">
            <mat-label>Engine Type</mat-label>
            <mat-select [(value)]="editEngine" [disabled]="viewMode === 'edit'">
              <mat-option value="xircuits">Xircuits (Python)</mat-option>
              <mat-option value="n8n">n8n (JavaScript)</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="field-name">
            <mat-label>Workflow Name</mat-label>
            <input matInput [(ngModel)]="editName" placeholder="my-workflow" [disabled]="viewMode === 'edit'">
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Description</mat-label>
          <input matInput [(ngModel)]="editDescription" placeholder="What does this workflow do?">
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Tags (comma-separated)</mat-label>
          <input matInput [(ngModel)]="editTagsStr" placeholder="automation, etl, data-pipeline">
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Workflow JSON</mat-label>
          <textarea matInput [(ngModel)]="editContent" rows="20" class="json-editor"
                    placeholder="Paste workflow JSON here..."></textarea>
        </mat-form-field>

        <div class="editor-actions">
          <button mat-button (click)="viewMode = 'list'">Cancel</button>
          <button mat-raised-button (click)="inspectContent()" [disabled]="!editContent.trim()">
            <mat-icon>visibility</mat-icon> Inspect
          </button>
          <button mat-raised-button color="primary" (click)="saveWorkflow()"
                  [disabled]="!editName.trim() || !editContent.trim() || saving">
            <mat-icon>save</mat-icon> {{saving ? 'Saving...' : 'Save'}}
          </button>
        </div>
      </div>

      <!-- Inspect View -->
      <div *ngIf="viewMode === 'inspect' && inspection" class="inspect-view">
        <h3>Workflow Inspection: {{inspection.name || inspectingName}}</h3>

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
          <mat-chip-set>
            <mat-chip *ngFor="let cred of inspection.requiredCredentialTypes" color="warn" highlighted>
              {{cred}}
            </mat-chip>
          </mat-chip-set>
        </div>

        <!-- Nodes List -->
        <div class="inspect-section">
          <h4><mat-icon>hub</mat-icon> Nodes</h4>
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
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Input JSON (optional)</mat-label>
            <textarea matInput [(ngModel)]="executeInputJson" rows="6" class="json-editor"
                      placeholder='{"key": "value"}'></textarea>
          </mat-form-field>

          <mat-form-field appearance="outline" class="field-timeout">
            <mat-label>Timeout (seconds)</mat-label>
            <input matInput type="number" [(ngModel)]="executeTimeout" placeholder="300">
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
    .editor-view h3, .inspect-view h3, .execute-view h3 { margin: 0 0 16px; }
    .form-row { display: flex; gap: 12px; }
    .field-engine { width: 220px; }
    .field-name { flex: 1; }
    .field-timeout { width: 180px; }
    .full-width { width: 100%; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions, .execute-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .inspect-summary {
      display: flex; gap: 20px; flex-wrap: wrap; padding: 12px 16px;
      background: #1e1e2e; border-radius: 8px; margin-bottom: 16px;
    }
    .summary-item { display: flex; align-items: center; gap: 6px; font-size: 13px; }
    .summary-item mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }

    .inspect-section { margin-bottom: 20px; }
    .inspect-section h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 8px; font-size: 14px; }
    .inspect-section h4 mat-icon { font-size: 18px; width: 18px; height: 18px; }

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
  viewMode: ViewMode = 'list';
  workflows: ComputeArtifact[] = [];
  loading = false;
  saving = false;
  executing = false;
  filterEngine: string = 'all';

  // Editor state
  editEngine: WorkflowEngineType = 'xircuits';
  editName = '';
  editDescription = '';
  editTagsStr = '';
  editContent = '';

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

  inspectContent(): void {
    if (!this.editContent.trim()) return;
    this.workflowService.inspect(this.editEngine, this.editContent).subscribe({
      next: (result) => {
        this.inspection = result;
        this.inspectingName = this.editName;
        this.viewMode = 'inspect';
      },
      error: (err) => {
        this.snackBar.open('Inspect failed: ' + (err.error?.error || err.message), 'Close', { duration: 4000 });
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
    const body = {
      engineType: this.executingEngine,
      name: this.executingName,
      inputs,
      timeoutSeconds: this.executeTimeout
    };

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
