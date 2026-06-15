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
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import {
  ProcessEngineService,
  OntologySchema,
  EntityTypeDefinition
} from '../../services/process-engine.service';

type ViewMode = 'list' | 'create' | 'detail';

@Component({
  standalone: true,
  selector: 'app-process-ontology',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSnackBarModule,
    MatExpansionModule, MatChipsModule, MatDividerModule
  ],
  template: `
    <div class="ontology-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
          <mat-icon>add</mat-icon> New Ontology
        </button>
        <button mat-button (click)="viewMode = 'list'" *ngIf="viewMode !== 'list'">
          <mat-icon>arrow_back</mat-icon> Back to List
        </button>
        <button mat-icon-button (click)="loadOntologies()" matTooltip="Refresh" *ngIf="viewMode === 'list'">
          <mat-icon>refresh</mat-icon>
        </button>
      </div>

      <!-- List View -->
      <div *ngIf="viewMode === 'list'" class="list-view">
        <div *ngIf="ontologies.length === 0" class="empty-state">
          <mat-icon class="empty-icon">schema</mat-icon>
          <p>No ontology schemas found</p>
          <button mat-raised-button color="primary" (click)="startCreate()">
            <mat-icon>add</mat-icon> Create First Ontology
          </button>
        </div>

        <div class="ontology-grid" *ngIf="ontologies.length > 0">
          <div *ngFor="let ont of ontologies" class="ontology-card" (click)="viewDetail(ont)">
            <div class="card-header">
              <mat-icon class="ont-icon">schema</mat-icon>
              <div class="card-title-block">
                <span class="card-name">{{ ont.name }}</span>
                <span class="card-version">v{{ ont.version || 1 }}</span>
              </div>
            </div>
            <mat-divider></mat-divider>
            <div class="card-stats">
              <div class="stat">
                <mat-icon>category</mat-icon>
                <span>{{ ont.entityTypes?.length || 0 }} entity types</span>
              </div>
              <div class="stat">
                <mat-icon>rule</mat-icon>
                <span>{{ countRules(ont) }} rules</span>
              </div>
            </div>
            <div class="card-id" *ngIf="ont.id">ID: {{ ont.id }}</div>
          </div>
        </div>
      </div>

      <!-- Create View -->
      <div *ngIf="viewMode === 'create'" class="create-view">
        <h3>Create Ontology Schema</h3>
        <p class="hint">Paste a valid ontology JSON object below. It must include at least a <code>name</code> field.</p>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Ontology JSON</mat-label>
          <textarea matInput [(ngModel)]="createJson" rows="24" class="json-editor"
                    placeholder='{"name": "my-ontology", "entityTypes": [...], "globalRules": [...]}'>
          </textarea>
        </mat-form-field>

        <div class="editor-actions">
          <button mat-button (click)="viewMode = 'list'">Cancel</button>
          <button mat-raised-button color="primary" (click)="createOntology()" [disabled]="saving || !createJson.trim()">
            <mat-icon>save</mat-icon> {{ saving ? 'Saving...' : 'Create' }}
          </button>
        </div>
      </div>

      <!-- Detail View -->
      <div *ngIf="viewMode === 'detail' && selectedOntology" class="detail-view">
        <div class="detail-header">
          <mat-icon class="detail-icon">schema</mat-icon>
          <div>
            <h3>{{ selectedOntology.name }}</h3>
            <span class="detail-version">Version {{ selectedOntology.version || 1 }}</span>
          </div>
        </div>

        <mat-divider class="section-divider"></mat-divider>

        <!-- Entity Types -->
        <div class="section-heading">
          <mat-icon>category</mat-icon>
          <span>Entity Types ({{ selectedOntology.entityTypes?.length || 0 }})</span>
        </div>

        <mat-accordion *ngIf="selectedOntology.entityTypes?.length">
          <mat-expansion-panel *ngFor="let et of selectedOntology.entityTypes" class="entity-panel">
            <mat-expansion-panel-header>
              <mat-panel-title>{{ et.name }}</mat-panel-title>
              <mat-panel-description>
                {{ et.fields?.length || 0 }} fields &bull; {{ et.rules?.length || 0 }} rules
              </mat-panel-description>
            </mat-expansion-panel-header>

            <p *ngIf="et.description" class="et-description">{{ et.description }}</p>

            <!-- Fields -->
            <div *ngIf="et.fields?.length" class="fields-section">
              <div class="sub-heading">Fields</div>
              <div class="fields-list">
                <div *ngFor="let f of et.fields" class="field-item">
                  <span class="field-name">{{ f.name }}</span>
                  <mat-chip-set>
                    <mat-chip class="chip-type">{{ f.type }}</mat-chip>
                    <mat-chip *ngIf="f.required" class="chip-required">required</mat-chip>
                  </mat-chip-set>
                  <span class="field-desc" *ngIf="f.description">{{ f.description }}</span>
                </div>
              </div>
            </div>

            <!-- Rules -->
            <div *ngIf="et.rules?.length" class="rules-section">
              <div class="sub-heading">Rules</div>
              <div class="rules-list">
                <div *ngFor="let r of et.rules" class="rule-item">
                  <mat-icon class="rule-icon" [class.rule-warn]="r.severity === 'WARNING'" [class.rule-error]="r.severity === 'ERROR'">
                    {{ r.severity === 'ERROR' ? 'error' : 'warning' }}
                  </mat-icon>
                  <div class="rule-info">
                    <span class="rule-name">{{ r.name }}</span>
                    <span class="rule-expr" *ngIf="r.expression">{{ r.expression }}</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="panel-actions">
              <button mat-stroked-button (click)="validateSample(et)" [disabled]="validating">
                <mat-icon>check_circle</mat-icon> Validate Sample
              </button>
            </div>

            <div *ngIf="validationResults[et.name]" class="validation-results">
              <div *ngIf="validationResults[et.name].length === 0" class="valid-ok">
                <mat-icon>check_circle</mat-icon> No validation errors
              </div>
              <div *ngFor="let vr of validationResults[et.name]" class="validation-error">
                <mat-icon>error_outline</mat-icon> {{ vr.name }}: {{ vr.description || vr.expression }}
              </div>
            </div>
          </mat-expansion-panel>
        </mat-accordion>

        <div *ngIf="!selectedOntology.entityTypes?.length" class="empty-sub">
          No entity types defined.
        </div>

        <!-- Global Rules -->
        <div *ngIf="selectedOntology.globalRules?.length" class="global-rules-section">
          <mat-divider class="section-divider"></mat-divider>
          <div class="section-heading">
            <mat-icon>rule</mat-icon>
            <span>Global Rules ({{ selectedOntology.globalRules?.length }})</span>
          </div>
          <div class="rules-list">
            <div *ngFor="let r of selectedOntology.globalRules" class="rule-item">
              <mat-icon class="rule-icon" [class.rule-warn]="r.severity === 'WARNING'" [class.rule-error]="r.severity === 'ERROR'">
                {{ r.severity === 'ERROR' ? 'error' : 'warning' }}
              </mat-icon>
              <div class="rule-info">
                <span class="rule-name">{{ r.name }}</span>
                <span class="rule-expr" *ngIf="r.expression">{{ r.expression }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .ontology-container { padding: 12px 0; }
    .section-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
    .empty-state { text-align: center; padding: 60px 20px; color: #999; }
    .empty-icon { font-size: 56px; width: 56px; height: 56px; color: #555; display: block; margin: 0 auto 12px; }

    .ontology-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
    .ontology-card {
      background: #1e1e2e; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px;
      cursor: pointer; transition: border-color 0.2s;
    }
    .ontology-card:hover { border-color: rgba(129,199,132,0.5); }
    .card-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
    .ont-icon { color: #81c784; font-size: 24px; width: 24px; height: 24px; flex-shrink: 0; }
    .card-title-block { flex: 1; }
    .card-name { display: block; font-weight: 500; font-size: 14px; }
    .card-version { font-size: 11px; color: #888; }
    .card-stats { display: flex; gap: 16px; padding: 10px 14px; }
    .stat { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #aaa; }
    .stat mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .card-id { padding: 4px 14px 10px; font-size: 10px; color: #666; font-family: monospace; }

    .create-view { max-width: 900px; }
    .create-view h3 { margin: 0 0 4px; }
    .hint { font-size: 12px; color: #aaa; margin: 0 0 16px; }
    .hint code { background: #2a2a3e; padding: 2px 6px; border-radius: 4px; }
    .full-width { width: 100%; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    .detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
    .detail-icon { font-size: 36px; width: 36px; height: 36px; color: #81c784; }
    .detail-header h3 { margin: 0; font-size: 18px; }
    .detail-version { font-size: 12px; color: #888; }
    .section-divider { margin: 16px 0; }

    .section-heading { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 500; margin: 16px 0 8px; }
    .section-heading mat-icon { font-size: 18px; width: 18px; height: 18px; color: #90caf9; }

    .entity-panel { background: #1e1e2e !important; margin-bottom: 4px !important; }
    .et-description { font-size: 12px; color: #aaa; margin: 0 0 12px; }

    .sub-heading { font-size: 12px; font-weight: 600; color: #90caf9; margin: 12px 0 6px; text-transform: uppercase; letter-spacing: 0.5px; }
    .fields-list { display: flex; flex-direction: column; gap: 4px; }
    .field-item { display: flex; align-items: center; gap: 10px; padding: 5px 0; font-size: 13px; }
    .field-name { font-weight: 500; min-width: 120px; }
    .field-desc { font-size: 11px; color: #888; }
    .chip-type { background: rgba(144,202,249,0.15) !important; color: #90caf9 !important; font-size: 11px !important; min-height: 22px !important; }
    .chip-required { background: rgba(255,183,77,0.15) !important; color: #ffb74d !important; font-size: 11px !important; min-height: 22px !important; }

    .rules-list { display: flex; flex-direction: column; gap: 4px; }
    .rule-item { display: flex; align-items: flex-start; gap: 8px; padding: 5px 0; font-size: 13px; }
    .rule-icon { font-size: 18px; width: 18px; height: 18px; color: #888; flex-shrink: 0; margin-top: 1px; }
    .rule-warn { color: #ffb74d !important; }
    .rule-error { color: #ef5350 !important; }
    .rule-info { display: flex; flex-direction: column; }
    .rule-name { font-weight: 500; }
    .rule-expr { font-size: 11px; color: #888; font-family: monospace; margin-top: 2px; }

    .panel-actions { margin-top: 12px; }
    .validation-results { margin-top: 8px; }
    .valid-ok { display: flex; align-items: center; gap: 6px; color: #81c784; font-size: 13px; }
    .valid-ok mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .validation-error { display: flex; align-items: flex-start; gap: 6px; color: #ef5350; font-size: 12px; margin-top: 4px; }
    .validation-error mat-icon { font-size: 16px; width: 16px; height: 16px; flex-shrink: 0; }

    .global-rules-section { margin-top: 8px; }
    .empty-sub { font-size: 13px; color: #666; padding: 8px 0; }
  `]
})
export class ProcessOntologyComponent implements OnInit {
  viewMode: ViewMode = 'list';
  ontologies: OntologySchema[] = [];
  selectedOntology: OntologySchema | null = null;
  createJson = '';
  saving = false;
  validating = false;
  validationResults: Record<string, any[]> = {};

  constructor(
    private processEngineService: ProcessEngineService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadOntologies();
  }

  loadOntologies(): void {
    this.processEngineService.listOntologies().subscribe({
      next: (result) => { this.ontologies = result; },
      error: () => { this.ontologies = []; }
    });
  }

  startCreate(): void {
    this.viewMode = 'create';
    this.createJson = '';
  }

  createOntology(): void {
    let schema: OntologySchema;
    try {
      schema = JSON.parse(this.createJson);
    } catch {
      this.snackBar.open('Invalid JSON', 'Close', { duration: 3000 });
      return;
    }
    this.saving = true;
    this.processEngineService.createOntology(schema).subscribe({
      next: (result) => {
        this.snackBar.open('Ontology created: ' + result.name, 'Close', { duration: 3000 });
        this.ontologies = [...this.ontologies, result];
        this.saving = false;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.snackBar.open('Failed to create: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.saving = false;
      }
    });
  }

  viewDetail(ont: OntologySchema): void {
    if (!ont.id) {
      this.selectedOntology = ont;
      this.viewMode = 'detail';
      return;
    }
    this.processEngineService.getOntology(ont.id, ont.version ?? 1).subscribe({
      next: (result) => {
        this.selectedOntology = result;
        this.viewMode = 'detail';
      },
      error: () => {
        // Fall back to local copy
        this.selectedOntology = ont;
        this.viewMode = 'detail';
      }
    });
  }

  validateSample(et: EntityTypeDefinition): void {
    if (!this.selectedOntology?.id) {
      this.snackBar.open('Ontology must be saved before validating', 'Close', { duration: 3000 });
      return;
    }
    // Build a minimal sample from field definitions
    const sample: Record<string, any> = {};
    (et.fields || []).forEach(f => {
      if (f.type === 'string') sample[f.name] = '';
      else if (f.type === 'number' || f.type === 'integer') sample[f.name] = 0;
      else if (f.type === 'boolean') sample[f.name] = false;
      else sample[f.name] = null;
    });

    this.validating = true;
    this.processEngineService.validateData(
      this.selectedOntology.id!,
      et.name,
      this.selectedOntology.version ?? 1,
      sample
    ).subscribe({
      next: (violations) => {
        this.validationResults[et.name] = violations;
        this.validating = false;
      },
      error: (err) => {
        this.snackBar.open('Validation call failed: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
        this.validating = false;
      }
    });
  }

  countRules(ont: OntologySchema): number {
    const global = ont.globalRules?.length ?? 0;
    const entity = (ont.entityTypes || []).reduce((sum, et) => sum + (et.rules?.length ?? 0), 0);
    return global + entity;
  }
}
