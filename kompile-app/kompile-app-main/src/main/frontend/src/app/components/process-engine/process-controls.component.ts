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
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  ProcessEngineService,
  ControlDefinition,
  ControlAttestation,
  SpelResult
} from '../../services/process-engine.service';

type ViewMode = 'list' | 'create' | 'detail';

@Component({
  standalone: true,
  selector: 'app-process-controls',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatChipsModule, MatDividerModule,
    MatTooltipModule
  ],
  template: `
    <div class="controls-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
          <mat-icon>add</mat-icon> New Control
        </button>
        <button mat-button (click)="backToList()" *ngIf="viewMode !== 'list'">
          <mat-icon>arrow_back</mat-icon> Back to List
        </button>
        <button mat-icon-button (click)="loadControls()" matTooltip="Refresh" *ngIf="viewMode === 'list'">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <!-- List View -->
      <div *ngIf="viewMode === 'list'" class="list-view">
        <div *ngIf="controls.length === 0" class="empty-state">
          <mat-icon class="empty-icon">verified_user</mat-icon>
          <p>No control definitions found</p>
          <button mat-raised-button color="primary" (click)="startCreate()">
            <mat-icon>add</mat-icon> Create First Control
          </button>
        </div>

        <div class="controls-grid" *ngIf="controls.length > 0">
          <div *ngFor="let ctrl of controls" class="control-card" (click)="viewDetail(ctrl)">
            <div class="card-header">
              <mat-icon class="ctrl-icon" [class.hard-gate]="ctrl.gateType === 'HARD'">
                {{ ctrl.gateType === 'HARD' ? 'gpp_bad' : 'gpp_good' }}
              </mat-icon>
              <div class="card-title-block">
                <span class="card-name">{{ ctrl.name }}</span>
                <span class="card-desc" *ngIf="ctrl.description">{{ ctrl.description }}</span>
              </div>
              <mat-chip-set>
                <mat-chip [class]="'gate-chip gate-' + (ctrl.gateType || 'SOFT').toLowerCase()">
                  {{ ctrl.gateType || 'SOFT' }}
                </mat-chip>
              </mat-chip-set>
            </div>
            <mat-divider></mat-divider>
            <div class="card-stats">
              <div class="stat" *ngIf="ctrl.severity">
                <mat-icon [class]="'severity-' + ctrl.severity.toLowerCase()">
                  {{ getSeverityIcon(ctrl.severity) }}
                </mat-icon>
                <span>{{ ctrl.severity }}</span>
              </div>
              <div class="stat" *ngIf="ctrl.expression">
                <mat-icon>code</mat-icon>
                <span class="expr-preview">{{ ctrl.expression | slice:0:40 }}{{ (ctrl.expression?.length || 0) > 40 ? '...' : '' }}</span>
              </div>
              <div class="stat" *ngIf="ctrl.regulatoryReference">
                <mat-icon>policy</mat-icon>
                <span>{{ ctrl.regulatoryReference }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Create View -->
      <div *ngIf="viewMode === 'create'" class="create-view">
        <h3>Create Control Definition</h3>
        <p class="hint">Define a control gate that evaluates a SpEL expression against run data.</p>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="newControl.name" placeholder="e.g. sox-revenue-threshold">
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Description</mat-label>
          <input matInput [(ngModel)]="newControl.description" placeholder="What this control checks">
        </mat-form-field>

        <div class="form-row">
          <mat-form-field appearance="outline">
            <mat-label>Gate Type</mat-label>
            <mat-select [(ngModel)]="newControl.gateType">
              <mat-option value="HARD">HARD (blocks run)</mat-option>
              <mat-option value="SOFT">SOFT (warning only)</mat-option>
            </mat-select>
          </mat-form-field>

          <mat-form-field appearance="outline">
            <mat-label>Severity</mat-label>
            <mat-select [(ngModel)]="newControl.severity">
              <mat-option value="LOW">LOW</mat-option>
              <mat-option value="MEDIUM">MEDIUM</mat-option>
              <mat-option value="HIGH">HIGH</mat-option>
              <mat-option value="CRITICAL">CRITICAL</mat-option>
            </mat-select>
          </mat-form-field>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>SpEL Expression</mat-label>
          <textarea matInput [(ngModel)]="newControl.expression" rows="3" class="expr-editor"
                    placeholder="e.g. #data['amount'] < 10000"></textarea>
        </mat-form-field>

        <!-- Inline SpEL Test Panel -->
        <div class="test-panel" *ngIf="newControl.expression?.trim()">
          <div class="test-panel-header">
            <mat-icon>science</mat-icon>
            <span>Test Expression</span>
          </div>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Test Context JSON</mat-label>
            <textarea matInput [(ngModel)]="testContextJson" rows="3" class="expr-editor"
                      placeholder='{"runData": {"amount": 5000}}'></textarea>
          </mat-form-field>
          <button mat-stroked-button (click)="testExpression()" [disabled]="testingExpr">
            <mat-icon>play_arrow</mat-icon> {{ testingExpr ? 'Testing...' : 'Test' }}
          </button>
          <div *ngIf="testResult" class="test-result" [class.test-pass]="!testResult.error" [class.test-fail]="testResult.error">
            <mat-icon>{{ testResult.error ? 'error' : 'check_circle' }}</mat-icon>
            <div class="test-result-info">
              <span class="test-verdict" *ngIf="!testResult.error">
                Result: {{ testResult.result }} ({{ testResult.type }})
              </span>
              <span class="test-verdict" *ngIf="testResult.error">
                Error: {{ testResult.error }}
              </span>
            </div>
          </div>
        </div>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Regulatory Reference (optional)</mat-label>
          <input matInput [(ngModel)]="newControl.regulatoryReference" placeholder="e.g. SOX Section 404">
        </mat-form-field>

        <div class="editor-actions">
          <button mat-button (click)="backToList()">Cancel</button>
          <button mat-raised-button color="primary" (click)="createControl()" [disabled]="saving || !newControl.name?.trim()">
            <mat-icon>save</mat-icon> {{ saving ? 'Saving...' : 'Create' }}
          </button>
        </div>
      </div>

      <!-- Detail View -->
      <div *ngIf="viewMode === 'detail' && selectedControl" class="detail-view">
        <div class="detail-header">
          <mat-icon class="detail-icon" [class.hard-gate]="selectedControl.gateType === 'HARD'">
            {{ selectedControl.gateType === 'HARD' ? 'gpp_bad' : 'gpp_good' }}
          </mat-icon>
          <div class="detail-title-block">
            <h3>{{ selectedControl.name }}</h3>
            <span class="detail-desc" *ngIf="selectedControl.description">{{ selectedControl.description }}</span>
          </div>
          <mat-chip-set>
            <mat-chip [class]="'gate-chip gate-' + (selectedControl.gateType || 'SOFT').toLowerCase()">
              {{ selectedControl.gateType || 'SOFT' }}
            </mat-chip>
            <mat-chip *ngIf="selectedControl.severity"
                      [class]="'severity-chip severity-chip-' + selectedControl.severity.toLowerCase()">
              {{ selectedControl.severity }}
            </mat-chip>
          </mat-chip-set>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <div class="detail-section" *ngIf="selectedControl.expression">
          <div class="section-label">Expression</div>
          <pre class="expression-block">{{ selectedControl.expression }}</pre>
        </div>

        <div class="detail-section" *ngIf="selectedControl.regulatoryReference">
          <div class="section-label">Regulatory Reference</div>
          <span class="ref-value">{{ selectedControl.regulatoryReference }}</span>
        </div>

        <div class="detail-section" *ngIf="selectedControl.inputKeys?.length">
          <div class="section-label">Input Keys</div>
          <mat-chip-set>
            <mat-chip *ngFor="let key of selectedControl.inputKeys" class="input-key-chip">{{ key }}</mat-chip>
          </mat-chip-set>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <!-- Evaluate Control -->
        <div class="evaluate-section">
          <div class="section-label">Evaluate Against Run Data</div>
          <div class="eval-form">
            <mat-form-field appearance="outline">
              <mat-label>Workflow Run ID</mat-label>
              <input matInput [(ngModel)]="evalRunId" placeholder="run-id">
            </mat-form-field>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Data JSON</mat-label>
              <textarea matInput [(ngModel)]="evalData" rows="4" class="expr-editor"
                        placeholder='{"amount": 5000, "approved": true}'></textarea>
            </mat-form-field>
            <button mat-raised-button color="accent" (click)="evaluateControl()"
                    [disabled]="evaluating || !evalRunId.trim() || !evalData.trim()">
              <mat-icon>play_arrow</mat-icon> {{ evaluating ? 'Evaluating...' : 'Evaluate' }}
            </button>
          </div>

          <div *ngIf="evalResult" class="eval-result" [class.eval-pass]="evalResult.passed" [class.eval-fail]="!evalResult.passed">
            <mat-icon>{{ evalResult.passed ? 'check_circle' : 'error' }}</mat-icon>
            <div class="eval-result-info">
              <span class="eval-verdict">{{ evalResult.passed ? 'PASSED' : 'FAILED' }}</span>
              <span class="eval-time" *ngIf="evalResult.evaluatedAt">{{ evalResult.evaluatedAt }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .controls-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }

    .controls-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .control-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      cursor: pointer; transition: border-color 0.2s;
    }
    .control-card:hover { border-color: rgba(239,83,80,0.4); }
    .card-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
    .ctrl-icon { font-size: 24px; width: 24px; height: 24px; color: #81c784; flex-shrink: 0; }
    .ctrl-icon.hard-gate { color: #ef5350; }
    .card-title-block { flex: 1; min-width: 0; }
    .card-name { display: block; font-weight: 500; font-size: 14px; }
    .card-desc { display: block; font-size: 11px; color: #888; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .card-stats { display: flex; gap: 16px; padding: 10px 14px; flex-wrap: wrap; }
    .stat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }
    .stat mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .expr-preview { font-family: monospace; font-size: 11px; }

    .gate-chip { font-size: 11px !important; min-height: 22px !important; font-weight: 600 !important; }
    .gate-hard { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }
    .gate-soft { background: rgba(255,183,77,0.2) !important; color: #ffb74d !important; }

    .severity-low { color: #81c784; }
    .severity-medium { color: #ffb74d; }
    .severity-high { color: #ff8a65; }
    .severity-critical { color: #ef5350; }

    .severity-chip { font-size: 10px !important; min-height: 20px !important; }
    .severity-chip-low { background: rgba(129,199,132,0.15) !important; color: #81c784 !important; }
    .severity-chip-medium { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; }
    .severity-chip-high { background: rgba(255,138,101,0.15) !important; color: #ff8a65 !important; }
    .severity-chip-critical { background: rgba(239,83,80,0.2) !important; color: #ef5350 !important; }

    .create-view { max-width: 700px; }
    .create-view h3 { margin: 0 0 4px; }
    .hint { font-size: 12px; color: #aaa; margin: 0 0 16px; }
    .full-width { width: 100%; }
    .form-row { display: flex; gap: 16px; }
    .form-row mat-form-field { flex: 1; }
    .expr-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
    .detail-icon { font-size: 36px; width: 36px; height: 36px; color: #81c784; flex-shrink: 0; }
    .detail-icon.hard-gate { color: #ef5350; }
    .detail-title-block { flex: 1; }
    .detail-title-block h3 { margin: 0; font-size: 18px; }
    .detail-desc { font-size: 12px; color: #888; }
    .section-divider { margin: 16px 0; }

    .detail-section { margin-bottom: 16px; }
    .section-label { font-size: 12px; font-weight: 600; color: #90caf9; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 6px; }
    .expression-block {
      background: #111; padding: 10px 12px; border-radius: 6px;
      font-size: 12px; font-family: 'JetBrains Mono', monospace; white-space: pre-wrap; margin: 0;
    }
    .ref-value { font-size: 13px; color: #ce93d8; }
    .input-key-chip { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; font-size: 11px !important; min-height: 22px !important; font-family: monospace; }

    .evaluate-section { margin-top: 8px; }
    .eval-form { display: flex; flex-direction: column; gap: 8px; max-width: 600px; }
    .eval-form button { align-self: flex-start; }

    .eval-result {
      display: flex; align-items: center; gap: 10px; padding: 12px;
      border-radius: 8px; margin-top: 12px; max-width: 600px;
    }
    .eval-pass { background: rgba(129,199,132,0.1); border: 1px solid rgba(129,199,132,0.3); }
    .eval-pass mat-icon { color: #81c784; font-size: 28px; width: 28px; height: 28px; }
    .eval-fail { background: rgba(239,83,80,0.1); border: 1px solid rgba(239,83,80,0.3); }
    .eval-fail mat-icon { color: #ef5350; font-size: 28px; width: 28px; height: 28px; }
    .eval-result-info { display: flex; flex-direction: column; }
    .eval-verdict { font-weight: 600; font-size: 14px; }
    .eval-time { font-size: 11px; color: #888; }

    /* SpEL Test Panel */
    .test-panel {
      background: rgba(144,202,249,0.05); border: 1px solid rgba(144,202,249,0.15);
      border-radius: 8px; padding: 12px; margin-bottom: 16px;
    }
    .test-panel-header { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500; color: #90caf9; margin-bottom: 8px; }
    .test-panel-header mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .test-result {
      display: flex; align-items: center; gap: 8px; padding: 8px 12px;
      border-radius: 6px; margin-top: 8px;
    }
    .test-pass { background: rgba(129,199,132,0.1); border: 1px solid rgba(129,199,132,0.3); }
    .test-pass mat-icon { color: #81c784; }
    .test-fail { background: rgba(239,83,80,0.1); border: 1px solid rgba(239,83,80,0.3); }
    .test-fail mat-icon { color: #ef5350; }
    .test-result-info { font-size: 12px; }
    .test-verdict { font-weight: 500; }
  `]
})
export class ProcessControlsComponent implements OnInit {
  viewMode: ViewMode = 'list';
  controls: ControlDefinition[] = [];
  selectedControl: ControlDefinition | null = null;
  saving = false;

  newControl: ControlDefinition = {
    name: '',
    gateType: 'SOFT',
    severity: 'MEDIUM'
  };

  // Evaluate state
  evalRunId = '';
  evalData = '';
  evaluating = false;
  evalResult: ControlAttestation | null = null;

  // SpEL test state (in create view)
  testContextJson = '{ "runData": {} }';
  testingExpr = false;
  testResult: SpelResult | null = null;

  constructor(
    private processEngineService: ProcessEngineService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadControls();
  }

  loadControls(): void {
    this.processEngineService.listControls().subscribe({
      next: (result) => { this.controls = result; },
      error: () => { this.controls = []; }
    });
  }

  startCreate(): void {
    this.viewMode = 'create';
    this.newControl = { name: '', gateType: 'SOFT', severity: 'MEDIUM' };
    this.testContextJson = '{ "runData": {} }';
    this.testResult = null;
  }

  backToList(): void {
    this.viewMode = 'list';
    this.evalResult = null;
  }

  createControl(): void {
    this.saving = true;
    this.processEngineService.createControl(this.newControl).subscribe({
      next: (result) => {
        this.snackBar.open('Control created: ' + result.name, 'Close', { duration: 3000 });
        this.controls = [...this.controls, result];
        this.saving = false;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.snackBar.open('Failed to create: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  viewDetail(ctrl: ControlDefinition): void {
    if (ctrl.id) {
      this.processEngineService.getControl(ctrl.id).subscribe({
        next: (result) => {
          this.selectedControl = result;
          this.viewMode = 'detail';
          this.evalResult = null;
        },
        error: () => {
          this.selectedControl = ctrl;
          this.viewMode = 'detail';
          this.evalResult = null;
        }
      });
    } else {
      this.selectedControl = ctrl;
      this.viewMode = 'detail';
      this.evalResult = null;
    }
  }

  evaluateControl(): void {
    if (!this.selectedControl?.id) return;
    let data: Record<string, any>;
    try {
      data = JSON.parse(this.evalData);
    } catch {
      this.snackBar.open('Invalid JSON', 'Close', { duration: 3000 });
      return;
    }
    this.evaluating = true;
    this.processEngineService.evaluateControl(this.selectedControl.id, this.evalRunId, data).subscribe({
      next: (result) => {
        this.evalResult = result;
        this.evaluating = false;
      },
      error: (err) => {
        this.snackBar.open('Evaluation failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.evaluating = false;
      }
    });
  }

  testExpression(): void {
    if (!this.newControl.expression?.trim()) return;
    let context: Record<string, any>;
    try {
      context = JSON.parse(this.testContextJson);
    } catch {
      this.snackBar.open('Invalid test context JSON', 'Close', { duration: 3000 });
      return;
    }
    this.testingExpr = true;
    this.testResult = null;
    this.processEngineService.evaluateSpelExpression(this.newControl.expression, context).subscribe({
      next: (result) => {
        this.testResult = result;
        this.testingExpr = false;
      },
      error: (err) => {
        this.testResult = { result: null, type: 'error', error: err.error?.message || err.message };
        this.testingExpr = false;
      }
    });
  }

  getSeverityIcon(severity: string): string {
    switch (severity) {
      case 'LOW': return 'info';
      case 'MEDIUM': return 'warning';
      case 'HIGH': return 'report_problem';
      case 'CRITICAL': return 'error';
      default: return 'info';
    }
  }
}
