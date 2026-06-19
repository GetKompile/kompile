import { Component, OnInit, ViewChild } from '@angular/core';
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
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { ComputeGraphService } from '../../services/compute-graph.service';
import {
  ComputeGraph, ComputeNode, ComputeEdge, NodeExecutionType,
  GraphExecutionResult, ValidationResult
} from '../../models/compute-graph-models';

interface NodeTypeInfo {
  label: string;
  icon: string;
  blurb: string;
  scriptLabel: string;
  example: string;
  needsScript: boolean;
}

@Component({
  standalone: true,
  selector: 'app-compute-graph-editor',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatButtonModule, MatIconModule, MatChipsModule, MatExpansionModule,
    MatDividerModule, MatSnackBarModule, MatTooltipModule, MatStepperModule
  ],
  template: `
    <div class="editor-container">
      <div class="wizard-header">
        <div>
          <h3>Build a Compute Graph</h3>
          <p class="wizard-sub">
            Follow the steps to define your graph, add nodes, wire them together, then run it.
            You can jump back to any completed step to make changes.
          </p>
        </div>
        <button mat-stroked-button (click)="loadSample()" matTooltip="Replace the current graph with a small worked example">
          <mat-icon>auto_fix_high</mat-icon> Load Example
        </button>
      </div>

      <mat-stepper linear #stepper class="graph-wizard" [animationDuration]="'200ms'">

        <!-- Step 1: Define Graph -->
        <mat-step [completed]="graphDetailsValid" [editable]="true">
          <ng-template matStepLabel>Define graph</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>device_hub</mat-icon>
              <div>
                <strong>What is a compute graph?</strong>
                <p>
                  A compute graph is a set of <em>nodes</em> connected by <em>edges</em>. Each node runs a
                  small piece of logic &mdash; JavaScript, Python, a Drools rule, or a SpEL expression &mdash;
                  and passes its result downstream. The engine works out a safe execution order from the
                  edges and runs each node in a sandbox. Start by giving the graph an identity.
                </p>
              </div>
            </div>

            <div class="form-row">
              <mat-form-field appearance="outline" class="flex-field">
                <mat-label>Graph ID</mat-label>
                <input matInput [(ngModel)]="graph.id" placeholder="order-scoring">
                <mat-hint>Required. Unique, stable identifier &mdash; use lowercase-with-dashes.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline" class="flex-field">
                <mat-label>Display name</mat-label>
                <input matInput [(ngModel)]="graph.name" placeholder="Order Scoring Pipeline">
                <mat-hint>Optional. A friendly label shown in lists and results.</mat-hint>
              </mat-form-field>
            </div>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Description</mat-label>
              <textarea matInput [(ngModel)]="graph.description" rows="2"
                        placeholder="Scores incoming orders and routes them by risk level."></textarea>
              <mat-hint>Optional. Explain what the graph does and what it expects as input.</mat-hint>
            </mat-form-field>

            <div class="step-actions">
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext [disabled]="!graphDetailsValid">
                Next: add nodes <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
            <p class="step-gate-hint" *ngIf="!graphDetailsValid">Enter a Graph ID to continue.</p>
          </div>
        </mat-step>

        <!-- Step 2: Add Nodes -->
        <mat-step [completed]="nodesValid" [editable]="true">
          <ng-template matStepLabel>Add nodes</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>hub</mat-icon>
              <div>
                <strong>Nodes do the work</strong>
                <p>
                  Add one node per unit of logic. Pick an <em>execution type</em> &mdash; each runs in its own
                  sandbox with the engine's resource limits (configure them under the Configuration tab).
                  A node's inputs come from the graph inputs and from upstream nodes via edges (next step).
                </p>
              </div>
            </div>

            <div class="type-legend">
              <div class="type-legend-item" *ngFor="let t of executionTypes">
                <mat-icon>{{nodeTypeInfo[t].icon}}</mat-icon>
                <div>
                  <span class="legend-label">{{nodeTypeInfo[t].label}}</span>
                  <span class="legend-blurb">{{nodeTypeInfo[t].blurb}}</span>
                </div>
              </div>
            </div>

            <mat-accordion class="nodes-accordion" multi>
              <mat-expansion-panel *ngFor="let node of graph.nodes; let i = index" [expanded]="i === graph.nodes.length - 1">
                <mat-expansion-panel-header>
                  <mat-panel-title>
                    <mat-icon class="node-type-icon">{{nodeTypeInfo[node.executionType].icon}}</mat-icon>
                    {{node.name || node.id || 'Node ' + (i + 1)}}
                  </mat-panel-title>
                  <mat-panel-description>
                    {{nodeTypeInfo[node.executionType].label}}
                  </mat-panel-description>
                </mat-expansion-panel-header>

                <div class="node-form">
                  <div class="form-row">
                    <mat-form-field appearance="outline" class="flex-field">
                      <mat-label>Node ID</mat-label>
                      <input matInput [(ngModel)]="node.id" placeholder="score">
                      <mat-hint>Referenced by edges. Keep it unique within the graph.</mat-hint>
                    </mat-form-field>
                    <mat-form-field appearance="outline" class="flex-field">
                      <mat-label>Name</mat-label>
                      <input matInput [(ngModel)]="node.name" placeholder="Score Order">
                    </mat-form-field>
                    <mat-form-field appearance="outline" class="type-field">
                      <mat-label>Execution type</mat-label>
                      <mat-select [(ngModel)]="node.executionType">
                        <mat-option *ngFor="let t of executionTypes" [value]="t">{{nodeTypeInfo[t].label}}</mat-option>
                      </mat-select>
                    </mat-form-field>
                  </div>

                  <div class="type-desc">
                    <mat-icon>info</mat-icon>
                    <span>{{nodeTypeInfo[node.executionType].blurb}}</span>
                  </div>

                  <ng-container *ngIf="nodeTypeInfo[node.executionType].needsScript">
                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>{{nodeTypeInfo[node.executionType].scriptLabel}}</mat-label>
                      <textarea matInput [(ngModel)]="node.script" rows="6" class="code-textarea"
                                placeholder="Write your code here..."></textarea>
                    </mat-form-field>
                    <div class="example-block">
                      <span class="example-label">Example</span>
                      <pre>{{nodeTypeInfo[node.executionType].example}}</pre>
                    </div>
                  </ng-container>

                  <p class="passthrough-note" *ngIf="!nodeTypeInfo[node.executionType].needsScript">
                    <mat-icon>arrow_forward</mat-icon>
                    A passthrough node needs no code &mdash; it copies its inputs straight to its outputs.
                  </p>

                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Parameters (JSON)</mat-label>
                    <textarea matInput [(ngModel)]="nodeParamsJson[i]" rows="2" class="code-textarea"
                              placeholder='{"threshold": 0.5}'
                              (blur)="parseNodeParams(i)"></textarea>
                    <mat-hint>Optional. Static values made available to the node as variables, alongside its inputs.</mat-hint>
                  </mat-form-field>

                  <mat-expansion-panel class="advanced-panel">
                    <mat-expansion-panel-header>
                      <mat-panel-title><mat-icon class="adv-icon">tune</mat-icon> Advanced: data bindings</mat-panel-title>
                    </mat-expansion-panel-header>
                    <p class="advanced-hint">
                      Bindings rename data as it enters and leaves the node. Leave empty to use input/output keys as-is.
                      Resource limits (CPU, memory, permissions) are inherited from the engine defaults in the Configuration tab.
                    </p>
                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Input bindings (JSON)</mat-label>
                      <textarea matInput [(ngModel)]="nodeInputBindingsJson[i]" rows="2" class="code-textarea"
                                placeholder='{"localName": "incomingKey"}'
                                (blur)="parseNodeBindings(i)"></textarea>
                      <mat-hint>Maps an incoming data key to the variable name the script sees.</mat-hint>
                    </mat-form-field>
                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Output bindings (JSON)</mat-label>
                      <textarea matInput [(ngModel)]="nodeOutputBindingsJson[i]" rows="2" class="code-textarea"
                                placeholder='{"publishedKey": "localResult"}'
                                (blur)="parseNodeBindings(i)"></textarea>
                      <mat-hint>Maps a value the node produces to the key it is published under.</mat-hint>
                    </mat-form-field>
                  </mat-expansion-panel>

                  <div class="node-actions">
                    <button mat-stroked-button color="warn" (click)="removeNode(i)">
                      <mat-icon>delete</mat-icon> Remove node
                    </button>
                  </div>
                </div>
              </mat-expansion-panel>
            </mat-accordion>

            <button mat-stroked-button color="primary" class="add-btn" (click)="addNode()">
              <mat-icon>add_circle</mat-icon> Add node
            </button>
            <p *ngIf="graph.nodes.length === 0" class="empty-hint">
              No nodes yet. Add at least one node to build the graph.
            </p>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext [disabled]="!nodesValid">
                Next: connect flow <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
            <p class="step-gate-hint" *ngIf="!nodesValid">
              Add at least one node, and give every node an ID and code (passthrough nodes need no code).
            </p>
          </div>
        </mat-step>

        <!-- Step 3: Connect Flow -->
        <mat-step [completed]="true" [editable]="true">
          <ng-template matStepLabel>Connect flow</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>account_tree</mat-icon>
              <div>
                <strong>Edges define order and data flow</strong>
                <p>
                  An edge runs its source node <em>before</em> its target. Optionally add a
                  <strong>condition</strong> (a SpEL expression) so the edge only fires when it evaluates true,
                  and a <strong>data mapping</strong> so a source output is fed into a named target input.
                  Nodes with no incoming edge are entry points and receive the graph inputs directly.
                </p>
              </div>
            </div>

            <p *ngIf="graph.nodes.length < 2" class="empty-hint">
              You need at least two nodes before you can connect them. A single-node graph runs that node on its own.
            </p>

            <div *ngFor="let edge of graph.edges; let i = index" class="edge-card">
              <div class="edge-route">
                <mat-form-field appearance="outline" class="edge-field">
                  <mat-label>From (runs first)</mat-label>
                  <mat-select [(ngModel)]="edge.sourceNodeId">
                    <mat-option *ngFor="let n of graph.nodes" [value]="n.id">{{n.name || n.id}}</mat-option>
                  </mat-select>
                </mat-form-field>
                <mat-icon class="edge-arrow">arrow_forward</mat-icon>
                <mat-form-field appearance="outline" class="edge-field">
                  <mat-label>To (runs after)</mat-label>
                  <mat-select [(ngModel)]="edge.targetNodeId">
                    <mat-option *ngFor="let n of graph.nodes" [value]="n.id">{{n.name || n.id}}</mat-option>
                  </mat-select>
                </mat-form-field>
                <button mat-icon-button color="warn" (click)="removeEdge(i)" matTooltip="Remove edge">
                  <mat-icon>close</mat-icon>
                </button>
              </div>
              <div class="edge-detail">
                <mat-form-field appearance="outline" class="condition-field">
                  <mat-label>Condition (SpEL, optional)</mat-label>
                  <input matInput [(ngModel)]="edge.condition" placeholder="#score > 0.5">
                  <mat-hint>Edge only activates when this is true. Leave empty to always activate.</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline" class="mapping-field">
                  <mat-label>Data mapping (JSON, optional)</mat-label>
                  <input matInput [(ngModel)]="edgeMappingJson[i]" placeholder='{"_result": "score"}'
                         (blur)="parseEdgeMapping(i)">
                  <mat-hint>{{ edgeMapExample }} &mdash; feed a source node output into a named target input.</mat-hint>
                </mat-form-field>
              </div>
            </div>

            <button mat-stroked-button color="primary" class="add-btn" (click)="addEdge()"
                    [disabled]="graph.nodes.length < 2">
              <mat-icon>add_circle</mat-icon> Add edge
            </button>
            <p *ngIf="graph.edges.length === 0 && graph.nodes.length >= 2" class="empty-hint">
              No edges yet. Without edges, nodes run independently in an arbitrary order.
            </p>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext>
                Next: inputs &amp; run <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 4: Inputs & Review -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Inputs &amp; run</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>play_circle</mat-icon>
              <div>
                <strong>Provide inputs, review, then run</strong>
                <p>
                  The input JSON is passed to the entry nodes. Validate first to catch missing nodes,
                  bad references, or cycles, then execute. Results appear below the wizard.
                </p>
              </div>
            </div>

            <div class="review-summary">
              <div class="summary-chip"><mat-icon>tag</mat-icon> {{graph.id || '(no id)'}}</div>
              <div class="summary-chip"><mat-icon>hub</mat-icon> {{graph.nodes.length}} node(s)</div>
              <div class="summary-chip"><mat-icon>account_tree</mat-icon> {{graph.edges.length}} edge(s)</div>
            </div>
            <div class="review-nodes">
              <mat-chip-set>
                <mat-chip *ngFor="let n of graph.nodes">
                  <mat-icon matChipAvatar>{{nodeTypeInfo[n.executionType].icon}}</mat-icon>
                  {{n.name || n.id}}
                </mat-chip>
              </mat-chip-set>
            </div>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Graph inputs (JSON)</mat-label>
              <textarea matInput [(ngModel)]="inputsJson" rows="4" class="code-textarea"
                        placeholder='{"x": 5, "y": 3}'></textarea>
              <mat-hint>The starting data handed to entry nodes. Use &#123;&#125; for none.</mat-hint>
            </mat-form-field>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-stroked-button (click)="validateGraph()">
                <mat-icon>check_circle</mat-icon> Validate
              </button>
              <button mat-raised-button color="primary" (click)="executeGraph()" [disabled]="executing">
                <mat-icon>{{executing ? 'hourglass_empty' : 'play_arrow'}}</mat-icon>
                {{executing ? 'Running...' : 'Execute graph'}}
              </button>
            </div>
          </div>
        </mat-step>
      </mat-stepper>

      <!-- Validation Result -->
      <mat-card class="editor-card result-area" *ngIf="validationResult">
        <mat-card-header>
          <mat-card-title>
            <mat-icon [class]="validationResult.valid ? 'status-ok' : 'status-err'">
              {{validationResult.valid ? 'check_circle' : 'error'}}
            </mat-icon>
            Validation
          </mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p *ngIf="validationResult.valid" class="status-ok">Graph is valid and ready to run.</p>
          <p *ngIf="!validationResult.valid" class="error-text">{{validationResult.errors}}</p>
        </mat-card-content>
      </mat-card>

      <!-- Results -->
      <mat-card class="editor-card result-area" *ngIf="lastResult">
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
    </div>
  `,
  styles: [`
    .editor-container { padding: 16px; max-width: 980px; }
    .wizard-header {
      display: flex; justify-content: space-between; align-items: flex-start; gap: 16px;
      margin-bottom: 12px;
    }
    .wizard-header h3 { margin: 0; font-size: 18px; }
    .wizard-sub { margin: 4px 0 0; font-size: 12px; color: #999; max-width: 640px; }
    .graph-wizard { background: transparent; }
    .step-body { padding: 8px 4px 4px; }

    .wizard-intro {
      display: flex; gap: 12px; align-items: flex-start;
      background: rgba(144,202,249,0.08); border: 1px solid rgba(144,202,249,0.25);
      border-radius: 8px; padding: 12px 14px; margin-bottom: 16px;
    }
    .wizard-intro mat-icon { color: #90caf9; flex-shrink: 0; }
    .wizard-intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .wizard-intro p { margin: 0; font-size: 12.5px; color: #bbb; line-height: 1.5; }

    .type-legend {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 8px;
      margin-bottom: 16px;
    }
    .type-legend-item {
      display: flex; gap: 8px; align-items: flex-start; padding: 8px 10px;
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.06); border-radius: 6px;
    }
    .type-legend-item mat-icon { color: #90caf9; font-size: 20px; width: 20px; height: 20px; }
    .legend-label { display: block; font-size: 12.5px; font-weight: 500; }
    .legend-blurb { display: block; font-size: 11px; color: #999; line-height: 1.4; margin-top: 2px; }

    .nodes-accordion { display: block; margin-bottom: 12px; }
    .form-row { display: flex; gap: 12px; align-items: flex-start; flex-wrap: wrap; }
    .flex-field { flex: 1; min-width: 180px; }
    .type-field { width: 220px; }
    .full-width { width: 100%; }
    .code-textarea { font-family: var(--font-family-monospace, 'JetBrains Mono', monospace); font-size: 13px; }
    .node-form { padding: 8px 0; }
    .node-actions { display: flex; justify-content: flex-end; padding-top: 4px; }
    .node-type-icon { margin-right: 8px; font-size: 20px; }

    .type-desc {
      display: flex; gap: 6px; align-items: flex-start; font-size: 12px; color: #aaa;
      margin: -4px 0 12px; line-height: 1.45;
    }
    .type-desc mat-icon { font-size: 16px; width: 16px; height: 16px; color: #90caf9; margin-top: 1px; }

    .example-block {
      background: #1e1e1e; border-radius: 4px; padding: 8px 10px; margin: -8px 0 12px;
      border-left: 3px solid #90caf9;
    }
    .example-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; color: #90caf9; }
    .example-block pre {
      margin: 4px 0 0; color: #d4d4d4; font-size: 12px; white-space: pre-wrap;
      font-family: var(--font-family-monospace, 'JetBrains Mono', monospace);
    }
    .passthrough-note {
      display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa;
      background: #1e1e2e; border-radius: 6px; padding: 8px 10px; margin: 0 0 12px;
    }
    .passthrough-note mat-icon { font-size: 18px; width: 18px; height: 18px; color: #81c784; }

    .advanced-panel { background: #181826 !important; margin: 4px 0 12px; box-shadow: none !important; border: 1px solid rgba(255,255,255,0.06); }
    .adv-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 6px; vertical-align: middle; }
    .advanced-hint { font-size: 12px; color: #999; margin: 4px 0 12px; line-height: 1.5; }

    .add-btn { margin: 4px 0 8px; }
    .empty-hint { color: #999; font-style: italic; padding: 8px 0; font-size: 12.5px; }

    .edge-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.06); border-radius: 8px;
      padding: 12px 14px 4px; margin-bottom: 10px;
    }
    .edge-route { display: flex; align-items: center; gap: 8px; }
    .edge-field { flex: 1; }
    .edge-arrow { color: #666; }
    .edge-detail { display: flex; gap: 12px; flex-wrap: wrap; }
    .condition-field { flex: 1; min-width: 220px; }
    .mapping-field { flex: 1; min-width: 220px; }

    .step-actions { display: flex; align-items: center; gap: 8px; padding-top: 12px; }
    .spacer { flex: 1; }
    .step-gate-hint { font-size: 12px; color: #ffb74d; margin: 6px 0 0; }

    .review-summary { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
    .summary-chip {
      display: flex; align-items: center; gap: 6px; font-size: 13px;
      background: #1e1e2e; border-radius: 16px; padding: 6px 12px;
    }
    .summary-chip mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }
    .review-nodes { margin-bottom: 16px; }

    .result-area { margin-top: 16px; }
    .editor-card { margin-bottom: 16px; }
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
  `]
})
export class ComputeGraphEditorComponent implements OnInit {
  @ViewChild('stepper') stepper?: MatStepper;

  executionTypes: NodeExecutionType[] = [
    'JAVASCRIPT', 'PYTHON', 'DROOLS_RULE', 'DROOLS_INFERENCE', 'EXPRESSION', 'PASSTHROUGH'
  ];

  nodeTypeInfo: Record<NodeExecutionType, NodeTypeInfo> = {
    JAVASCRIPT: {
      label: 'JavaScript', icon: 'javascript', needsScript: true, scriptLabel: 'JavaScript code',
      blurb: 'Run a JavaScript snippet. The final expression — or a returned object — becomes the node output. Inputs are available as variables by name.',
      example: 'x * 2 + y\n// or return multiple values:\n({ score: x + y, ok: x > y })'
    },
    PYTHON: {
      label: 'Python', icon: 'code', needsScript: true, scriptLabel: 'Python code',
      blurb: 'Run a Python snippet in the embedded interpreter. Assign to result (or set variables) to produce outputs.',
      example: 'result = x * 2 + y'
    },
    DROOLS_RULE: {
      label: 'Drools Rule', icon: 'gavel', needsScript: true, scriptLabel: 'Drools rule (DRL)',
      blurb: 'Evaluate Drools (DRL) rules over the node facts. Call $f.setOutput("key", value) in the then-block to emit outputs. Requires the Drools backend.',
      example: 'rule "score"\n  when\n    $f : NodeFacts()\n  then\n    $f.setOutput("ok", true);\nend'
    },
    DROOLS_INFERENCE: {
      label: 'Drools Inference', icon: 'psychology', needsScript: true, scriptLabel: 'Drools rules (DRL)',
      blurb: 'Like Drools Rule, but inference is on so rules chain — facts asserted by one rule can fire others. Requires the Drools + inference backends.',
      example: 'rule "derive"\n  when\n    $f : NodeFacts(value > 10)\n  then\n    insert(new Tag("high"));\nend'
    },
    EXPRESSION: {
      label: 'Expression (SpEL)', icon: 'functions', needsScript: true, scriptLabel: 'SpEL expression',
      blurb: 'A single Spring Expression Language expression. Reference inputs with #name. Sandboxed and fast — no scripting backend required.',
      example: '#x * 2 + #y'
    },
    PASSTHROUGH: {
      label: 'Passthrough', icon: 'arrow_forward', needsScript: false, scriptLabel: '',
      blurb: 'Forwards inputs to outputs unchanged. Useful as a fan-in/join point or a labeled checkpoint. No code required.',
      example: ''
    }
  };

  graph: ComputeGraph = {
    id: '', name: '', description: '',
    nodes: [], edges: [], globalParameters: {}
  };

  nodeParamsJson: string[] = [];
  nodeInputBindingsJson: string[] = [];
  nodeOutputBindingsJson: string[] = [];
  edgeMappingJson: string[] = [];
  inputsJson = '{}';
  // Held as a property (not inline in the template) so the literal { } braces aren't
  // parsed as an Angular ICU message in the mat-hint text.
  edgeMapExample = '{ "sourceOutput": "targetInput" }';
  executing = false;
  lastResult: GraphExecutionResult | null = null;
  validationResult: ValidationResult | null = null;

  constructor(
    private computeGraphService: ComputeGraphService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {}

  get graphDetailsValid(): boolean {
    return !!this.graph.id && this.graph.id.trim().length > 0;
  }

  get nodesValid(): boolean {
    return this.graph.nodes.length > 0 && this.graph.nodes.every(n =>
      !!n.id && n.id.trim().length > 0 &&
      (n.executionType === 'PASSTHROUGH' || (!!n.script && n.script.trim().length > 0))
    );
  }

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
    this.nodeInputBindingsJson.push('{}');
    this.nodeOutputBindingsJson.push('{}');
  }

  removeNode(index: number): void {
    const nodeId = this.graph.nodes[index].id;
    this.graph.nodes.splice(index, 1);
    this.nodeParamsJson.splice(index, 1);
    this.nodeInputBindingsJson.splice(index, 1);
    this.nodeOutputBindingsJson.splice(index, 1);
    const keep = this.graph.edges.map(e => e.sourceNodeId !== nodeId && e.targetNodeId !== nodeId);
    this.graph.edges = this.graph.edges.filter((_, i) => keep[i]);
    this.edgeMappingJson = this.edgeMappingJson.filter((_, i) => keep[i]);
  }

  addEdge(): void {
    this.graph.edges.push({
      sourceNodeId: this.graph.nodes[0]?.id || '',
      targetNodeId: this.graph.nodes[1]?.id || ''
    });
    this.edgeMappingJson.push('{}');
  }

  removeEdge(index: number): void {
    this.graph.edges.splice(index, 1);
    this.edgeMappingJson.splice(index, 1);
  }

  parseNodeParams(index: number): void {
    try {
      this.graph.nodes[index].parameters = JSON.parse(this.nodeParamsJson[index] || '{}');
    } catch (_) {}
  }

  parseNodeBindings(index: number): void {
    try {
      const ib = JSON.parse(this.nodeInputBindingsJson[index] || '{}');
      this.graph.nodes[index].inputBindings = Object.keys(ib).length ? ib : undefined;
    } catch (_) {}
    try {
      const ob = JSON.parse(this.nodeOutputBindingsJson[index] || '{}');
      this.graph.nodes[index].outputBindings = Object.keys(ob).length ? ob : undefined;
    } catch (_) {}
  }

  parseEdgeMapping(index: number): void {
    try {
      const dm = JSON.parse(this.edgeMappingJson[index] || '{}');
      this.graph.edges[index].dataMapping = Object.keys(dm).length ? dm : undefined;
    } catch (_) {}
  }

  private parseAll(): void {
    this.graph.nodes.forEach((_, i) => { this.parseNodeParams(i); this.parseNodeBindings(i); });
    this.graph.edges.forEach((_, i) => this.parseEdgeMapping(i));
  }

  getNodeIcon(type: NodeExecutionType): string {
    return this.nodeTypeInfo[type]?.icon || 'device_hub';
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
    this.parseAll();

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
    this.parseAll();
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
      description: 'A sample graph that computes a sum and a product, then combines them into a score.',
      nodes: [
        { id: 'sum', name: 'Sum', executionType: 'JAVASCRIPT', script: 'x + y', parameters: {} },
        { id: 'product', name: 'Product', executionType: 'JAVASCRIPT', script: 'x * y', parameters: {} },
        { id: 'combine', name: 'Combine', executionType: 'JAVASCRIPT', script: '({score: sum + product, label: sum > product ? "sum-dominant" : "product-dominant"})', parameters: {} }
      ],
      edges: [
        { sourceNodeId: 'sum', targetNodeId: 'combine', dataMapping: { '_result': 'sum' } },
        { sourceNodeId: 'product', targetNodeId: 'combine', dataMapping: { '_result': 'product' } }
      ],
      globalParameters: {}
    };
    this.nodeParamsJson = this.graph.nodes.map(n => JSON.stringify(n.parameters || {}));
    this.nodeInputBindingsJson = this.graph.nodes.map(n => JSON.stringify(n.inputBindings || {}));
    this.nodeOutputBindingsJson = this.graph.nodes.map(n => JSON.stringify(n.outputBindings || {}));
    this.edgeMappingJson = this.graph.edges.map(e => JSON.stringify(e.dataMapping || {}));
    this.inputsJson = '{"x": 5, "y": 3}';
    this.lastResult = null;
    this.validationResult = null;
    this.stepper?.reset();
    this.snackBar.open('Sample graph loaded', 'OK', { duration: 1500 });
  }
}
