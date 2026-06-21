/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, OnChanges, SimpleChanges, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  ProcessMiningService,
  MiningSuggestion,
  ProcessCausalModel,
  DeclareConstraint,
  InferenceResult,
  PerformanceAnalysis,
  MiningPreview
} from '../../services/process-mining.service';
import { backendUrl } from '../../services/base.service';

@Component({
  selector: 'app-process-mining-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatTabsModule,
    MatChipsModule,
    MatCardModule,
    MatDividerModule,
    MatCheckboxModule
  ],
  template: `
    <div class="process-mining-panel">
      <div class="panel-header">
        <mat-icon>timeline</mat-icon>
        <h3>Process Mining</h3>
        <p class="subtitle">LLM-free process discovery from the knowledge graph.</p>
      </div>

      <!-- Controls bar -->
      <div class="controls-bar">
        <mat-form-field appearance="outline" class="fsid-field">
          <mat-label>Fact Sheet ID</mat-label>
          <input matInput type="number" [(ngModel)]="factSheetId">
        </mat-form-field>
        <mat-form-field appearance="outline" class="anchor-field">
          <mat-label>Anchor Type (optional)</mat-label>
          <input matInput [(ngModel)]="anchorType" placeholder="e.g. EVENT">
        </mat-form-field>
        <button mat-raised-button color="primary" (click)="loadAll()" [disabled]="anyLoading">
          <mat-spinner diameter="18" *ngIf="anyLoading"></mat-spinner>
          <mat-icon *ngIf="!anyLoading">play_arrow</mat-icon>
          Mine
        </button>
      </div>

      <!-- Inner tab group -->
      <mat-tab-group class="mining-tabs">

        <!-- Preview tab -->
        <mat-tab label="Preview">
          <div class="tab-content" *ngIf="!previewLoading && !preview">
            <p class="empty-msg">Click "Mine" to run process discovery.</p>
          </div>
          <div class="tab-content" *ngIf="previewLoading">
            <mat-spinner diameter="32"></mat-spinner>
          </div>
          <div class="tab-content" *ngIf="!previewLoading && preview">
            <mat-card class="stat-card">
              <mat-card-content>
                <div class="stats-grid">
                  <div class="stat-item">
                    <span class="stat-value">{{ preview.cases }}</span>
                    <span class="stat-label">Cases</span>
                  </div>
                  <div class="stat-item">
                    <span class="stat-value">{{ preview.variants }}</span>
                    <span class="stat-label">Variants</span>
                  </div>
                  <div class="stat-item">
                    <span class="stat-value">{{ preview.activities.length }}</span>
                    <span class="stat-label">Activities</span>
                  </div>
                  <div class="stat-item">
                    <span class="stat-value">{{ preview.directlyFollowsArcs }}</span>
                    <span class="stat-label">DFG Arcs</span>
                  </div>
                </div>
                <mat-divider></mat-divider>
                <div class="activities-list">
                  <span class="section-label">Activities</span>
                  <mat-chip-set>
                    <mat-chip *ngFor="let a of preview.activities">{{ a }}</mat-chip>
                  </mat-chip-set>
                </div>
                <div *ngIf="preview.processTree" class="process-tree">
                  <span class="section-label">Process Tree</span>
                  <pre class="tree-text">{{ preview.processTree }}</pre>
                </div>
              </mat-card-content>
            </mat-card>
          </div>
        </mat-tab>

        <!-- Discover tab -->
        <mat-tab label="Discover">
          <div class="tab-content" *ngIf="!discoverLoading && !suggestion">
            <p class="empty-msg">Click "Mine" to discover a process.</p>
          </div>
          <div class="tab-content" *ngIf="discoverLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!discoverLoading && suggestion">
            <mat-card>
              <mat-card-header>
                <mat-card-title>{{ suggestion.name }}</mat-card-title>
                <mat-card-subtitle>{{ suggestion.description }}</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <p><strong>Confidence:</strong> {{ (suggestion.confidence * 100).toFixed(0) }}%</p>
                <p><strong>Source:</strong> {{ suggestion.discoverySource }}</p>
                <div *ngFor="let phase of suggestion.phases" class="phase-block">
                  <h4>{{ phase.name }}</h4>
                  <p>{{ phase.description }}</p>
                  <ul>
                    <li *ngFor="let step of phase.steps">{{ step.name }} ({{ step.stepType }})</li>
                  </ul>
                </div>
              </mat-card-content>
            </mat-card>
          </div>
        </mat-tab>

        <!-- Mermaid tab -->
        <mat-tab label="Mermaid">
          <div class="tab-content" *ngIf="!mermaidLoading && !mermaidResult">
            <p class="empty-msg">Click "Mine" to generate Mermaid diagrams.</p>
          </div>
          <div class="tab-content" *ngIf="mermaidLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content mermaid-content" *ngIf="!mermaidLoading && mermaidResult">
            <div class="mermaid-section">
              <div class="mermaid-header">
                <strong>Directly-Follows Graph (Mermaid)</strong>
                <button mat-icon-button (click)="copyToClipboard(mermaidResult!.dfg)" matTooltip="Copy">
                  <mat-icon>content_copy</mat-icon>
                </button>
              </div>
              <pre class="mermaid-code">{{ mermaidResult!.dfg }}</pre>
            </div>
            <mat-divider></mat-divider>
            <div class="mermaid-section">
              <div class="mermaid-header">
                <strong>Process Tree (Mermaid)</strong>
                <button mat-icon-button (click)="copyToClipboard(mermaidResult!.tree)" matTooltip="Copy">
                  <mat-icon>content_copy</mat-icon>
                </button>
              </div>
              <pre class="mermaid-code">{{ mermaidResult!.tree }}</pre>
            </div>
          </div>
        </mat-tab>

        <!-- BPMN tab -->
        <mat-tab label="BPMN">
          <div class="tab-content">
            <div class="bpmn-actions">
              <button mat-raised-button color="primary" (click)="renderBpmn()" [disabled]="!factSheetId || bpmnLoading">
                <mat-spinner diameter="18" *ngIf="bpmnLoading"></mat-spinner>
                <mat-icon *ngIf="!bpmnLoading">visibility</mat-icon>
                View BPMN
              </button>
              <button mat-stroked-button (click)="downloadBpmn()" [disabled]="!factSheetId">
                <mat-icon>download</mat-icon>
                Download .bpmn
              </button>
            </div>
            <div *ngIf="bpmnError" class="bpmn-error">{{ bpmnError }}</div>
            <div #bpmnContainer class="bpmn-canvas" [style.display]="bpmnRendered ? 'block' : 'none'"></div>
          </div>
        </mat-tab>

        <!-- Causal tab -->
        <mat-tab label="Causal">
          <div class="tab-content" *ngIf="!causalLoading && !causalModel">
            <p class="empty-msg">Click "Mine" to analyze causal dependencies.</p>
          </div>
          <div class="tab-content" *ngIf="causalLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!causalLoading && causalModel">
            <table mat-table [dataSource]="causalModel.dependencies" class="data-table">
              <ng-container matColumnDef="from">
                <th mat-header-cell *matHeaderCellDef>From</th>
                <td mat-cell *matCellDef="let d">{{ d.from }}</td>
              </ng-container>
              <ng-container matColumnDef="to">
                <th mat-header-cell *matHeaderCellDef>To</th>
                <td mat-cell *matCellDef="let d">{{ d.to }}</td>
              </ng-container>
              <ng-container matColumnDef="dependency">
                <th mat-header-cell *matHeaderCellDef>Dependency</th>
                <td mat-cell *matCellDef="let d">{{ d.dependency.toFixed(3) }}</td>
              </ng-container>
              <ng-container matColumnDef="significant">
                <th mat-header-cell *matHeaderCellDef>Significant</th>
                <td mat-cell *matCellDef="let d">
                  <mat-icon [style.color]="d.significant ? '#27ae60' : '#e74c3c'">
                    {{ d.significant ? 'check_circle' : 'cancel' }}
                  </mat-icon>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="['from', 'to', 'dependency', 'significant']"></tr>
              <tr mat-row *matRowDef="let r; columns: ['from', 'to', 'dependency', 'significant']"></tr>
            </table>
          </div>
        </mat-tab>

        <!-- PSL tab -->
        <mat-tab label="PSL">
          <div class="tab-content" *ngIf="!pslLoading && !pslResult">
            <p class="empty-msg">Click "Mine" to run PSL inference.</p>
          </div>
          <div class="tab-content" *ngIf="pslLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!pslLoading && pslResult">
            <p><strong>Ground rules:</strong> {{ pslResult.groundRules }} | <strong>Iterations:</strong> {{ pslResult.iterations }}</p>
            <table mat-table [dataSource]="activationEntries" class="data-table">
              <ng-container matColumnDef="activity">
                <th mat-header-cell *matHeaderCellDef>Activity</th>
                <td mat-cell *matCellDef="let e">{{ e.key }}</td>
              </ng-container>
              <ng-container matColumnDef="truth">
                <th mat-header-cell *matHeaderCellDef>Soft Truth</th>
                <td mat-cell *matCellDef="let e">{{ e.value.toFixed(3) }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="['activity', 'truth']"></tr>
              <tr mat-row *matRowDef="let r; columns: ['activity', 'truth']"></tr>
            </table>
          </div>
        </mat-tab>

        <!-- Bayesian tab -->
        <mat-tab label="Bayesian">
          <div class="tab-content" *ngIf="!bayesianLoading && !bayesianResult">
            <p class="empty-msg">Click "Mine" to run Bayesian inference.</p>
          </div>
          <div class="tab-content" *ngIf="bayesianLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!bayesianLoading && bayesianResult">
            <table mat-table [dataSource]="posteriorEntries" class="data-table">
              <ng-container matColumnDef="activity">
                <th mat-header-cell *matHeaderCellDef>Activity</th>
                <td mat-cell *matCellDef="let e">{{ e.key }}</td>
              </ng-container>
              <ng-container matColumnDef="posterior">
                <th mat-header-cell *matHeaderCellDef>P(active)</th>
                <td mat-cell *matCellDef="let e">{{ e.value.toFixed(3) }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="['activity', 'posterior']"></tr>
              <tr mat-row *matRowDef="let r; columns: ['activity', 'posterior']"></tr>
            </table>
          </div>
        </mat-tab>

        <!-- Performance tab -->
        <mat-tab label="Performance">
          <div class="tab-content" *ngIf="!perfLoading && !perfResult">
            <p class="empty-msg">Click "Mine" to analyze bottlenecks.</p>
          </div>
          <div class="tab-content" *ngIf="perfLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!perfLoading && perfResult">
            <table mat-table [dataSource]="perfResult.arcs" class="data-table">
              <ng-container matColumnDef="from">
                <th mat-header-cell *matHeaderCellDef>From</th>
                <td mat-cell *matCellDef="let a">{{ a.from }}</td>
              </ng-container>
              <ng-container matColumnDef="to">
                <th mat-header-cell *matHeaderCellDef>To</th>
                <td mat-cell *matCellDef="let a">{{ a.to }}</td>
              </ng-container>
              <ng-container matColumnDef="count">
                <th mat-header-cell *matHeaderCellDef>Count</th>
                <td mat-cell *matCellDef="let a">{{ a.count }}</td>
              </ng-container>
              <ng-container matColumnDef="median">
                <th mat-header-cell *matHeaderCellDef>Median (s)</th>
                <td mat-cell *matCellDef="let a">{{ a.medianSeconds.toFixed(2) }}</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="['from', 'to', 'count', 'median']"></tr>
              <tr mat-row *matRowDef="let r; columns: ['from', 'to', 'count', 'median']"></tr>
            </table>
          </div>
        </mat-tab>

        <!-- Declare tab -->
        <mat-tab label="Declare">
          <div class="tab-content" *ngIf="!declareLoading && !declareResult">
            <p class="empty-msg">Click "Mine" to extract Declare constraints.</p>
          </div>
          <div class="tab-content" *ngIf="declareLoading"><mat-spinner diameter="32"></mat-spinner></div>
          <div class="tab-content" *ngIf="!declareLoading && declareResult">
            <table mat-table [dataSource]="declareResult" class="data-table">
              <ng-container matColumnDef="template">
                <th mat-header-cell *matHeaderCellDef>Template</th>
                <td mat-cell *matCellDef="let c">{{ c.template }}</td>
              </ng-container>
              <ng-container matColumnDef="activityA">
                <th mat-header-cell *matHeaderCellDef>Activity A</th>
                <td mat-cell *matCellDef="let c">{{ c.activityA }}</td>
              </ng-container>
              <ng-container matColumnDef="support">
                <th mat-header-cell *matHeaderCellDef>Support</th>
                <td mat-cell *matCellDef="let c">{{ (c.support * 100).toFixed(0) }}%</td>
              </ng-container>
              <ng-container matColumnDef="confidence">
                <th mat-header-cell *matHeaderCellDef>Confidence</th>
                <td mat-cell *matCellDef="let c">{{ (c.confidence * 100).toFixed(0) }}%</td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="['template', 'activityA', 'support', 'confidence']"></tr>
              <tr mat-row *matRowDef="let r; columns: ['template', 'activityA', 'support', 'confidence']"></tr>
            </table>
          </div>
        </mat-tab>

      </mat-tab-group>
    </div>
  `,
  styleUrls: ['./process-mining-panel.component.css']
})
export class ProcessMiningPanelComponent implements OnChanges, OnDestroy {
  @Input() factSheetIdInput: number | null = null;

  factSheetId: number | null = null;
  anchorType = '';

  previewLoading = false;
  preview: MiningPreview | null = null;

  discoverLoading = false;
  suggestion: MiningSuggestion | null = null;

  mermaidLoading = false;
  mermaidResult: { dfg: string; tree: string } | null = null;

  causalLoading = false;
  causalModel: ProcessCausalModel | null = null;

  pslLoading = false;
  pslResult: InferenceResult | null = null;

  bayesianLoading = false;
  bayesianResult: InferenceResult | null = null;

  perfLoading = false;
  perfResult: PerformanceAnalysis | null = null;

  declareLoading = false;
  declareResult: DeclareConstraint[] | null = null;

  bpmnLoading = false;
  bpmnRendered = false;
  bpmnError: string | null = null;
  private bpmnViewerInstance: any = null;

  @ViewChild('bpmnContainer') bpmnContainer!: ElementRef;

  private readonly apiBase = backendUrl;
  private destroy$ = new Subject<void>();

  constructor(
    private miningService: ProcessMiningService,
    private snackBar: MatSnackBar
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetIdInput'] && this.factSheetIdInput) {
      this.factSheetId = this.factSheetIdInput;
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.bpmnViewerInstance) {
      this.bpmnViewerInstance.destroy();
      this.bpmnViewerInstance = null;
    }
  }

  get anyLoading(): boolean {
    return this.previewLoading || this.discoverLoading || this.mermaidLoading ||
      this.causalLoading || this.pslLoading || this.bayesianLoading || this.perfLoading || this.declareLoading;
  }

  get activationEntries(): { key: string; value: number }[] {
    if (!this.pslResult?.activation) return [];
    return Object.entries(this.pslResult.activation).map(([key, value]) => ({ key, value: value as number }));
  }

  get posteriorEntries(): { key: string; value: number }[] {
    if (!this.bayesianResult?.posteriors) return [];
    return Object.entries(this.bayesianResult.posteriors).map(([key, value]) => ({ key, value: value as number }));
  }

  loadAll(): void {
    if (!this.factSheetId) {
      this.snackBar.open('Please enter a Fact Sheet ID', 'Dismiss', { duration: 2000 });
      return;
    }
    const at = this.anchorType || undefined;
    this.loadPreview(at);
    this.loadDiscover(at);
    this.loadMermaid(at);
    this.loadCausal(at);
    this.loadPsl(at);
    this.loadBayesian(at);
    this.loadPerformance(at);
    this.loadDeclare(at);
  }

  private loadPreview(at?: string): void {
    this.previewLoading = true;
    this.miningService.preview(this.factSheetId!, 0, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: MiningPreview) => { this.previewLoading = false; this.preview = r; },
      error: (_err: unknown) => { this.previewLoading = false; this.snackBar.open('Preview failed', 'Dismiss', { duration: 3000 }); }
    });
  }

  private loadDiscover(at?: string): void {
    this.discoverLoading = true;
    this.miningService.discover(this.factSheetId!, 0, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: MiningSuggestion) => { this.discoverLoading = false; this.suggestion = r; },
      error: (_err: unknown) => { this.discoverLoading = false; }
    });
  }

  private loadMermaid(at?: string): void {
    this.mermaidLoading = true;
    this.miningService.mermaid(this.factSheetId!, 0, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: { dfg: string; tree: string }) => { this.mermaidLoading = false; this.mermaidResult = r; },
      error: (_err: unknown) => { this.mermaidLoading = false; }
    });
  }

  private loadCausal(at?: string): void {
    this.causalLoading = true;
    this.miningService.causal(this.factSheetId!, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: ProcessCausalModel) => { this.causalLoading = false; this.causalModel = r; },
      error: (_err: unknown) => { this.causalLoading = false; }
    });
  }

  private loadPsl(at?: string): void {
    this.pslLoading = true;
    this.miningService.psl(this.factSheetId!, [], at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: InferenceResult) => { this.pslLoading = false; this.pslResult = r; },
      error: (_err: unknown) => { this.pslLoading = false; }
    });
  }

  private loadBayesian(at?: string): void {
    this.bayesianLoading = true;
    this.miningService.bayesian(this.factSheetId!, [], at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: InferenceResult) => { this.bayesianLoading = false; this.bayesianResult = r; },
      error: (_err: unknown) => { this.bayesianLoading = false; }
    });
  }

  private loadPerformance(at?: string): void {
    this.perfLoading = true;
    this.miningService.performance(this.factSheetId!, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: PerformanceAnalysis) => { this.perfLoading = false; this.perfResult = r; },
      error: (_err: unknown) => { this.perfLoading = false; }
    });
  }

  private loadDeclare(at?: string): void {
    this.declareLoading = true;
    this.miningService.declareConstraints(this.factSheetId!, 0.1, 0.9, at).pipe(takeUntil(this.destroy$)).subscribe({
      next: (r: DeclareConstraint[]) => { this.declareLoading = false; this.declareResult = r; },
      error: (_err: unknown) => { this.declareLoading = false; }
    });
  }

  downloadBpmn(): void {
    if (!this.factSheetId) return;
    const url = `${this.apiBase}/process/mining/bpmn?factSheetId=${this.factSheetId}`;
    const link = document.createElement('a');
    link.href = url;
    link.download = `process-${this.factSheetId}.bpmn`;
    link.click();
  }

  async renderBpmn(): Promise<void> {
    if (!this.factSheetId) return;
    this.bpmnLoading = true;
    this.bpmnError = null;
    try {
      // Lazy-load bpmn-js
      const BpmnJS = (await import('bpmn-js')).default;

      // Fetch XML from backend
      const url = `${this.apiBase}/process/mining/bpmn?factSheetId=${this.factSheetId}` +
        (this.anchorType ? `&anchorType=${encodeURIComponent(this.anchorType)}` : '');
      const response = await fetch(url);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const xml = await response.text();

      // Destroy previous instance
      if (this.bpmnViewerInstance) {
        this.bpmnViewerInstance.destroy();
        this.bpmnViewerInstance = null;
      }

      // Create viewer
      this.bpmnViewerInstance = new BpmnJS({
        container: this.bpmnContainer.nativeElement
      });

      await this.bpmnViewerInstance.importXML(xml);
      const canvas = this.bpmnViewerInstance.get('canvas');
      canvas.zoom('fit-viewport');

      this.bpmnRendered = true;
    } catch (err: any) {
      this.bpmnError = err?.message || 'Failed to render BPMN diagram';
    } finally {
      this.bpmnLoading = false;
    }
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.snackBar.open('Copied!', 'OK', { duration: 1500 });
    });
  }
}
