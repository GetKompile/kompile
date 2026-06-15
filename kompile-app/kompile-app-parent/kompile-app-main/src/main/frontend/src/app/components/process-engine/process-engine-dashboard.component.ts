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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ProcessEngineService, WorkflowRun } from '../../services/process-engine.service';
import { ProcessAttributionService } from '../../services/process-attribution.service';
import { ProcessOntologyComponent } from './process-ontology.component';
import { ProcessDefinitionsComponent } from './process-definitions.component';
import { ProcessRunsComponent } from './process-runs.component';
import { ProcessApprovalsComponent } from './process-approvals.component';
import { ProcessControlsComponent } from './process-controls.component';
import { ProcessRunDetailComponent } from './process-run-detail.component';
import { GraphNodePopoverComponent } from './graph-node-popover.component';
import { ExcelArtifactComponent } from './excel-artifact.component';
import { ProcessDiagramComponent } from './process-diagram.component';
import { ProcessDiscoverySuggestionsComponent } from './process-discovery-suggestions.component';

@Component({
  standalone: true,
  selector: 'app-process-engine-dashboard',
  imports: [
    CommonModule, FormsModule,
    MatTabsModule, MatIconModule, MatButtonModule,
    MatChipsModule, MatSnackBarModule,
    ProcessOntologyComponent,
    ProcessDefinitionsComponent,
    ProcessRunsComponent,
    ProcessApprovalsComponent,
    ProcessControlsComponent,
    ProcessRunDetailComponent,
    GraphNodePopoverComponent,
    ExcelArtifactComponent,
    ProcessDiagramComponent,
    ProcessDiscoverySuggestionsComponent
  ],
  template: `
    <div class="dashboard-container">
      <!-- Header -->
      <div class="dashboard-header">
        <div class="header-left">
          <mat-icon class="header-icon">hub</mat-icon>
          <div>
            <h2>Process Engine</h2>
            <p class="header-subtitle">Ontology, HITL workflows, controls, and compliance</p>
          </div>
        </div>
        <div class="header-badges">
          <mat-chip-set>
            <mat-chip class="badge-chip badge-runs" *ngIf="activeRunsCount >= 0">
              <mat-icon>play_circle</mat-icon>
              {{ activeRunsCount }} active run{{ activeRunsCount !== 1 ? 's' : '' }}
            </mat-chip>
            <mat-chip class="badge-chip badge-approvals"
                      *ngIf="pendingApprovalsCount > 0">
              <mat-icon>approval</mat-icon>
              {{ pendingApprovalsCount }} pending approval{{ pendingApprovalsCount !== 1 ? 's' : '' }}
            </mat-chip>
            <mat-chip class="badge-chip badge-risk"
                      *ngIf="highRiskRunsCount > 0">
              <mat-icon>warning</mat-icon>
              {{ highRiskRunsCount }} high-risk
            </mat-chip>
          </mat-chip-set>
        </div>
      </div>

      <!-- Tab Group -->
      <mat-tab-group class="pe-tabs" animationDuration="150ms">

        <!-- Ontologies Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">schema</mat-icon>
            Ontologies
          </ng-template>
          <div class="tab-content">
            <app-process-ontology></app-process-ontology>
          </div>
        </mat-tab>

        <!-- Discovery Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">auto_awesome</mat-icon>
            Discovery
            <span class="tab-badge" *ngIf="pendingSuggestionsCount > 0">{{ pendingSuggestionsCount }}</span>
          </ng-template>
          <div class="tab-content">
            <app-process-discovery-suggestions></app-process-discovery-suggestions>
          </div>
        </mat-tab>

        <!-- Processes Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">account_tree</mat-icon>
            Processes
          </ng-template>
          <div class="tab-content">
            <app-process-definitions></app-process-definitions>
          </div>
        </mat-tab>

        <!-- Runs Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">play_circle</mat-icon>
            Runs
            <span class="tab-badge" *ngIf="activeRunsCount > 0">{{ activeRunsCount }}</span>
          </ng-template>
          <div class="tab-content">
            <app-process-runs></app-process-runs>
          </div>
        </mat-tab>

        <!-- Approvals Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">approval</mat-icon>
            Approvals
            <span class="tab-badge tab-badge-warn" *ngIf="pendingApprovalsCount > 0">{{ pendingApprovalsCount }}</span>
          </ng-template>
          <div class="tab-content">
            <app-process-approvals></app-process-approvals>
          </div>
        </mat-tab>

        <!-- Controls Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">verified_user</mat-icon>
            Controls
          </ng-template>
          <div class="tab-content">
            <app-process-controls></app-process-controls>
          </div>
        </mat-tab>

        <!-- Diagrams Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">schema</mat-icon>
            Diagrams
          </ng-template>
          <div class="tab-content tab-content-full">
            <app-process-diagram></app-process-diagram>
          </div>
        </mat-tab>

        <!-- Excel Tab -->
        <mat-tab>
          <ng-template mat-tab-label>
            <mat-icon class="tab-icon">table_chart</mat-icon>
            Excel
          </ng-template>
          <div class="tab-content">
            <div class="excel-input-area" *ngIf="!excelGraphJson">
              <p>Paste a SpreadsheetGraph JSON below to convert Excel formulas to code:</p>
              <textarea
                class="graph-json-input"
                [(ngModel)]="excelGraphJsonInput"
                placeholder='{"workbookName": "...", "cells": {...}, "dependencies": [...], "namedRanges": {}}'
                rows="6">
              </textarea>
              <button mat-raised-button color="primary" (click)="loadExcelGraph()"
                      [disabled]="!excelGraphJsonInput">
                <mat-icon>upload</mat-icon> Load Graph
              </button>
            </div>
            <app-excel-artifact
              *ngIf="excelGraphJson"
              [spreadsheetGraphJson]="excelGraphJson"
              (codeSaved)="onExcelCodeSaved($event)">
            </app-excel-artifact>
            <button mat-button *ngIf="excelGraphJson" (click)="excelGraphJson = ''"
                    style="margin-top: 8px;">
              <mat-icon>arrow_back</mat-icon> Load Different Graph
            </button>
          </div>
        </mat-tab>

      </mat-tab-group>
    </div>
  `,
  styles: [`
    .dashboard-container { height: 100%; display: flex; flex-direction: column; padding: 0 16px 16px; }

    .dashboard-header {
      display: flex; justify-content: space-between; align-items: center;
      padding: 16px 4px; border-bottom: 1px solid rgba(255,255,255,0.12);
      flex-wrap: wrap; gap: 12px;
    }
    .header-left { display: flex; align-items: center; gap: 12px; }
    .header-icon { font-size: 36px; width: 36px; height: 36px; color: #ce93d8; }
    .header-left h2 { margin: 0; font-size: 20px; }
    .header-subtitle { margin: 2px 0 0; font-size: 12px; color: #999; }

    .header-badges { display: flex; align-items: center; }
    .badge-chip { font-size: 12px !important; font-weight: 500 !important; gap: 4px; }
    .badge-chip mat-icon { font-size: 16px !important; width: 16px !important; height: 16px !important; }
    .badge-runs { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; }
    .badge-approvals { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }
    .badge-risk { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }

    .pe-tabs { flex: 1; }
    .tab-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 4px; vertical-align: middle; }

    .tab-badge {
      display: inline-flex; align-items: center; justify-content: center;
      min-width: 18px; height: 18px; border-radius: 9px;
      background: rgba(144,202,249,0.25); color: #90caf9;
      font-size: 11px; font-weight: 600; padding: 0 4px; margin-left: 6px;
    }
    .tab-badge-warn { background: rgba(255,183,77,0.25) !important; color: #ffb74d !important; }

    .tab-content { padding: 16px 4px 0; min-height: 300px; }
    .tab-content-full { height: calc(100vh - 220px); }

    .excel-input-area { max-width: 800px; }
    .excel-input-area p { color: #bbb; margin-bottom: 8px; }
    .graph-json-input {
      width: 100%;
      font-family: 'Roboto Mono', monospace;
      font-size: 12px;
      padding: 12px;
      border: 1px solid #555;
      border-radius: 4px;
      background: #1e1e1e;
      color: #d4d4d4;
      resize: vertical;
      margin-bottom: 8px;
    }
  `]
})
export class ProcessEngineDashboardComponent implements OnInit {
  activeRunsCount = 0;
  pendingApprovalsCount = 0;
  pendingSuggestionsCount = 0;
  highRiskRunsCount = 0;

  // Excel tab state
  excelGraphJsonInput = '';
  excelGraphJson = '';

  constructor(
    private processEngineService: ProcessEngineService,
    private processAttributionService: ProcessAttributionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadCounts();
  }

  private loadCounts(): void {
    this.processEngineService.listActiveRuns().subscribe({
      next: (runs) => {
        this.activeRunsCount = runs.length;
        this.assessRunRisks(runs);
      },
      error: () => { this.activeRunsCount = 0; }
    });

    this.processEngineService.getPendingApprovals().subscribe({
      next: (approvals) => { this.pendingApprovalsCount = approvals.length; },
      error: () => { this.pendingApprovalsCount = 0; }
    });

    this.processEngineService.listStoredSuggestions(undefined, true).subscribe({
      next: (response) => { this.pendingSuggestionsCount = response.count || 0; },
      error: () => { this.pendingSuggestionsCount = 0; }
    });
  }

  private assessRunRisks(runs: WorkflowRun[]): void {
    if (runs.length === 0) {
      this.highRiskRunsCount = 0;
      return;
    }
    let highCount = 0;
    let responded = 0;
    const total = Math.min(runs.length, 10); // Cap at 10 to avoid overload
    for (let i = 0; i < total; i++) {
      const run = runs[i];
      if (!run.id) { responded++; continue; }
      this.processAttributionService.assessRunRisk(run.id, false).subscribe({
        next: (result) => {
          if (result.riskLevel === 'HIGH' || result.riskLevel === 'CRITICAL') {
            highCount++;
          }
          responded++;
          if (responded >= total) {
            this.highRiskRunsCount = highCount;
          }
        },
        error: () => {
          responded++;
          if (responded >= total) {
            this.highRiskRunsCount = highCount;
          }
        }
      });
    }
  }

  loadExcelGraph(): void {
    try {
      JSON.parse(this.excelGraphJsonInput);
      this.excelGraphJson = this.excelGraphJsonInput;
    } catch {
      this.snackBar.open('Invalid JSON', 'Close', { duration: 3000 });
    }
  }

  onExcelCodeSaved(event: { code: string; language: string }): void {
    this.snackBar.open(
      `${event.language} code saved (${event.code.length} chars)`,
      'Close',
      { duration: 3000 }
    );
  }
}
