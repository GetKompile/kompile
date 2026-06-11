import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ComputeGraphService } from '../../services/compute-graph.service';
import {
  ComputeGraph, ComputeNode, ComputeEdge, NodeExecutionType,
  GraphExecutionResult, ValidationResult
} from '../../models/compute-graph-models';

@Component({
  standalone: true,
  selector: 'app-compute-graph-editor',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatChipsModule, MatExpansionModule,
    MatDividerModule, MatSnackBarModule, MatTooltipModule
  ],
  template: `
    <div class="editor-container">
      <!-- Graph Metadata -->
      <mat-card class="editor-card">
        <mat-card-header>
          <mat-card-title>Graph Definition</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="form-row">
            <mat-form-field appearance="outline" class="flex-field">
              <mat-label>Graph ID</mat-label>
              <input matInput [(ngModel)]="graph.id" placeholder="my-graph">
            </mat-form-field>
            <mat-form-field appearance="outline" class="flex-field">
              <mat-label>Graph Name</mat-label>
              <input matInput [(ngModel)]="graph.name" placeholder="My Compute Graph">
            </mat-form-field>
          </div>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Description</mat-label>
            <input matInput [(ngModel)]="graph.description" placeholder="What this graph does...">
          </mat-form-field>
        </mat-card-content>
      </mat-card>

      <!-- Nodes -->
      <mat-card class="editor-card">
        <mat-card-header>
          <mat-card-title>
            Nodes ({{graph.nodes.length}})
            <button mat-icon-button color="primary" (click)="addNode()" matTooltip="Add Node">
              <mat-icon>add_circle</mat-icon>
            </button>
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <mat-accordion>
            <mat-expansion-panel *ngFor="let node of graph.nodes; let i = index">
              <mat-expansion-panel-header>
                <mat-panel-title>
                  <mat-icon class="node-type-icon">{{getNodeIcon(node.executionType)}}</mat-icon>
                  {{node.name || node.id || 'Node ' + (i + 1)}}
                </mat-panel-title>
                <mat-panel-description>
                  {{node.executionType}}
                </mat-panel-description>
              </mat-expansion-panel-header>

              <div class="node-form">
                <div class="form-row">
                  <mat-form-field appearance="outline" class="flex-field">
                    <mat-label>Node ID</mat-label>
                    <input matInput [(ngModel)]="node.id" placeholder="node-1">
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="flex-field">
                    <mat-label>Name</mat-label>
                    <input matInput [(ngModel)]="node.name" placeholder="My Node">
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="type-field">
                    <mat-label>Type</mat-label>
                    <mat-select [(ngModel)]="node.executionType">
                      <mat-option *ngFor="let t of executionTypes" [value]="t">{{t}}</mat-option>
                    </mat-select>
                  </mat-form-field>
                </div>

                <mat-form-field appearance="outline" class="full-width"
                                *ngIf="node.executionType !== 'PASSTHROUGH'">
                  <mat-label>Script / Rule Code</mat-label>
                  <textarea matInput [(ngModel)]="node.script" rows="6"
                            class="code-textarea"
                            [placeholder]="getPlaceholder(node.executionType)"></textarea>
                </mat-form-field>

                <mat-form-field appearance="outline" class="full-width">
                  <mat-label>Parameters (JSON)</mat-label>
                  <textarea matInput [(ngModel)]="nodeParamsJson[i]" rows="2"
                            class="code-textarea"
                            placeholder='{"key": "value"}'
                            (blur)="parseNodeParams(i)"></textarea>
                </mat-form-field>

                <div class="node-actions">
                  <button mat-stroked-button color="warn" (click)="removeNode(i)">
                    <mat-icon>delete</mat-icon> Remove
                  </button>
                </div>
              </div>
            </mat-expansion-panel>
          </mat-accordion>
        </mat-card-content>
      </mat-card>

      <!-- Edges -->
      <mat-card class="editor-card">
        <mat-card-header>
          <mat-card-title>
            Edges ({{graph.edges.length}})
            <button mat-icon-button color="primary" (click)="addEdge()" matTooltip="Add Edge"
                    [disabled]="graph.nodes.length < 2">
              <mat-icon>add_circle</mat-icon>
            </button>
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div *ngFor="let edge of graph.edges; let i = index" class="edge-row">
            <mat-form-field appearance="outline" class="edge-field">
              <mat-label>From</mat-label>
              <mat-select [(ngModel)]="edge.sourceNodeId">
                <mat-option *ngFor="let n of graph.nodes" [value]="n.id">{{n.name || n.id}}</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-icon class="edge-arrow">arrow_forward</mat-icon>
            <mat-form-field appearance="outline" class="edge-field">
              <mat-label>To</mat-label>
              <mat-select [(ngModel)]="edge.targetNodeId">
                <mat-option *ngFor="let n of graph.nodes" [value]="n.id">{{n.name || n.id}}</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="condition-field">
              <mat-label>Condition (SpEL)</mat-label>
              <input matInput [(ngModel)]="edge.condition" placeholder="#score > 0.5">
            </mat-form-field>
            <button mat-icon-button color="warn" (click)="removeEdge(i)" matTooltip="Remove Edge">
              <mat-icon>close</mat-icon>
            </button>
          </div>
          <p *ngIf="graph.edges.length === 0" class="empty-hint">
            No edges defined. Add edges to connect nodes and define data flow.
          </p>
        </mat-card-content>
      </mat-card>

      <!-- Inputs & Execute -->
      <mat-card class="editor-card">
        <mat-card-header>
          <mat-card-title>Execute</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Graph Inputs (JSON)</mat-label>
            <textarea matInput [(ngModel)]="inputsJson" rows="3"
                      class="code-textarea"
                      placeholder='{"x": 10, "y": 20}'></textarea>
          </mat-form-field>
        </mat-card-content>
        <mat-card-actions>
          <button mat-raised-button color="primary" (click)="executeGraph()" [disabled]="executing">
            <mat-icon>play_arrow</mat-icon> Execute Graph
          </button>
          <button mat-stroked-button (click)="validateGraph()">
            <mat-icon>check_circle</mat-icon> Validate
          </button>
          <button mat-stroked-button (click)="loadSample()">
            <mat-icon>auto_fix_high</mat-icon> Load Sample
          </button>
        </mat-card-actions>
      </mat-card>

      <!-- Results -->
      <mat-card class="editor-card" *ngIf="lastResult">
        <mat-card-header>
          <mat-card-title>
            <mat-icon [class]="lastResult.status === 'COMPLETED' ? 'status-ok' : 'status-err'">
              {{lastResult.status === 'COMPLETED' ? 'check_circle' : 'error'}}
            </mat-icon>
            Execution Result
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="result-header">
            <span><strong>ID:</strong> {{lastResult.executionId}}</span>
            <span><strong>Status:</strong> {{lastResult.status}}</span>
            <span><strong>Duration:</strong> {{lastResult.totalDuration}}</span>
            <span><strong>Order:</strong> {{lastResult.executionOrder.join(' -> ')}}</span>
          </div>
          <mat-divider></mat-divider>

          <h4>Final Outputs</h4>
          <pre class="result-json">{{lastResult.finalOutputs | json}}</pre>

          <h4>Node Results</h4>
          <div *ngFor="let nodeId of getNodeResultKeys()" class="node-result">
            <div class="node-result-header">
              <mat-icon [class]="lastResult.nodeResults[nodeId].status === 'COMPLETED' ? 'status-ok' : 'status-err'">
                {{lastResult.nodeResults[nodeId].status === 'COMPLETED' ? 'check_circle' : 'error'}}
              </mat-icon>
              <strong>{{nodeId}}</strong>
              <span class="node-status">({{lastResult.nodeResults[nodeId].status}})</span>
            </div>
            <pre class="result-json" *ngIf="lastResult.nodeResults[nodeId].outputs">{{lastResult.nodeResults[nodeId].outputs | json}}</pre>
            <div class="error-text" *ngIf="lastResult.nodeResults[nodeId].error">
              {{lastResult.nodeResults[nodeId].error}}
            </div>
            <pre class="console-output" *ngIf="lastResult.nodeResults[nodeId].consoleOutput">{{lastResult.nodeResults[nodeId].consoleOutput}}</pre>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Validation Result -->
      <mat-card class="editor-card" *ngIf="validationResult">
        <mat-card-header>
          <mat-card-title>
            <mat-icon [class]="validationResult.valid ? 'status-ok' : 'status-err'">
              {{validationResult.valid ? 'check_circle' : 'error'}}
            </mat-icon>
            Validation
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p *ngIf="validationResult.valid" class="status-ok">Graph is valid.</p>
          <p *ngIf="!validationResult.valid" class="error-text">{{validationResult.errors}}</p>
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .editor-container { padding: 16px; max-width: 960px; }
    .editor-card { margin-bottom: 16px; }
    .form-row { display: flex; gap: 12px; align-items: flex-start; }
    .flex-field { flex: 1; }
    .type-field { width: 200px; }
    .full-width { width: 100%; }
    .code-textarea { font-family: var(--font-family-monospace, 'JetBrains Mono', monospace); font-size: 13px; }
    .node-form { padding: 8px 0; }
    .node-actions { display: flex; justify-content: flex-end; padding-top: 4px; }
    .node-type-icon { margin-right: 8px; font-size: 20px; }
    .edge-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
    .edge-field { flex: 1; }
    .condition-field { flex: 1.5; }
    .edge-arrow { color: #666; }
    .empty-hint { color: #999; font-style: italic; padding: 8px 0; }
    .result-header { display: flex; gap: 16px; flex-wrap: wrap; padding: 8px 0; }
    .result-json {
      background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace); font-size: 12px;
      overflow-x: auto; white-space: pre-wrap; max-height: 300px; overflow-y: auto;
    }
    .console-output {
      background: #0d1117; color: #8b949e; padding: 8px; border-radius: 4px;
      font-family: var(--font-family-monospace, monospace); font-size: 12px;
    }
    .node-result { margin: 8px 0; padding: 8px; border: 1px solid #333; border-radius: 4px; }
    .node-result-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
    .node-status { color: #999; }
    .status-ok { color: #4caf50; }
    .status-err { color: #f44336; }
    .error-text { color: #f44336; font-family: var(--font-family-monospace, monospace); }
    mat-card-actions { padding: 8px 16px; display: flex; gap: 8px; }
  `]
})
export class ComputeGraphEditorComponent implements OnInit {
  executionTypes: NodeExecutionType[] = [
    'JAVASCRIPT', 'PYTHON', 'DROOLS_RULE', 'DROOLS_INFERENCE', 'EXPRESSION', 'PASSTHROUGH'
  ];

  graph: ComputeGraph = {
    id: '', name: '', description: '',
    nodes: [], edges: [], globalParameters: {}
  };

  nodeParamsJson: string[] = [];
  inputsJson = '{}';
  executing = false;
  lastResult: GraphExecutionResult | null = null;
  validationResult: ValidationResult | null = null;

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {}

  addNode(): void {
    const idx = this.graph.nodes.length + 1;
    this.graph.nodes.push({
      id: `node-${idx}`,
      name: `Node ${idx}`,
      executionType: 'JAVASCRIPT',
      script: '',
      parameters: {}
    });
    this.nodeParamsJson.push('{}');
  }

  removeNode(index: number): void {
    const nodeId = this.graph.nodes[index].id;
    this.graph.nodes.splice(index, 1);
    this.nodeParamsJson.splice(index, 1);
    this.graph.edges = this.graph.edges.filter(
      e => e.sourceNodeId !== nodeId && e.targetNodeId !== nodeId
    );
  }

  addEdge(): void {
    this.graph.edges.push({
      sourceNodeId: this.graph.nodes[0]?.id || '',
      targetNodeId: this.graph.nodes[1]?.id || ''
    });
  }

  removeEdge(index: number): void {
    this.graph.edges.splice(index, 1);
  }

  parseNodeParams(index: number): void {
    try {
      this.graph.nodes[index].parameters = JSON.parse(this.nodeParamsJson[index] || '{}');
    } catch (_) {}
  }

  getNodeIcon(type: NodeExecutionType): string {
    switch (type) {
      case 'JAVASCRIPT': return 'javascript';
      case 'PYTHON': return 'code';
      case 'DROOLS_RULE': return 'gavel';
      case 'DROOLS_INFERENCE': return 'psychology';
      case 'EXPRESSION': return 'functions';
      case 'PASSTHROUGH': return 'arrow_forward';
      default: return 'device_hub';
    }
  }

  getPlaceholder(type: NodeExecutionType): string {
    switch (type) {
      case 'JAVASCRIPT': return 'x * 2 + y  // or ({result: x + y})';
      case 'PYTHON': return 'result = x * 2 + y';
      case 'DROOLS_RULE': return 'rule "my rule"\\n  when\\n    $f : NodeFacts()\\n  then\\n    $f.setOutput("result", ...);\\nend';
      case 'EXPRESSION': return '#x * 2 + #y';
      default: return '';
    }
  }

  executeGraph(): void {
    this.executing = true;
    this.lastResult = null;
    this.validationResult = null;
    let inputs: any = {};
    try {
      inputs = JSON.parse(this.inputsJson || '{}');
    } catch (e) {
      this.snackBar.open('Invalid inputs JSON', 'Dismiss', { duration: 3000 });
      this.executing = false;
      return;
    }
    // Parse all node params before execution
    this.graph.nodes.forEach((_, i) => this.parseNodeParams(i));

    this.computeGraphService.executeGraph(this.graph, inputs).subscribe({
      next: (result) => {
        this.lastResult = result;
        this.executing = false;
      },
      error: (err) => {
        this.snackBar.open('Execution failed: ' + (err.error?.error || err.message), 'Dismiss', { duration: 5000 });
        this.executing = false;
      }
    });
  }

  validateGraph(): void {
    this.validationResult = null;
    this.computeGraphService.validateGraph(this.graph).subscribe({
      next: (result) => this.validationResult = result,
      error: (err) => this.snackBar.open('Validation failed: ' + err.message, 'Dismiss', { duration: 3000 })
    });
  }

  getNodeResultKeys(): string[] {
    return this.lastResult?.nodeResults ? Object.keys(this.lastResult.nodeResults) : [];
  }

  loadSample(): void {
    this.graph = {
      id: 'sample-math',
      name: 'Math Pipeline',
      description: 'A sample graph that computes sum, product, and a combined score',
      nodes: [
        { id: 'sum', name: 'Sum', executionType: 'JAVASCRIPT', script: 'x + y' },
        { id: 'product', name: 'Product', executionType: 'JAVASCRIPT', script: 'x * y' },
        { id: 'combine', name: 'Combine', executionType: 'JAVASCRIPT', script: '({score: sum + product, label: sum > product ? "sum-dominant" : "product-dominant"})' }
      ],
      edges: [
        { sourceNodeId: 'sum', targetNodeId: 'combine', dataMapping: { '_result': 'sum' } },
        { sourceNodeId: 'product', targetNodeId: 'combine', dataMapping: { '_result': 'product' } }
      ],
      globalParameters: {}
    };
    this.nodeParamsJson = ['{}', '{}', '{}'];
    this.inputsJson = '{"x": 5, "y": 3}';
    this.snackBar.open('Sample graph loaded', 'OK', { duration: 1500 });
  }
}
