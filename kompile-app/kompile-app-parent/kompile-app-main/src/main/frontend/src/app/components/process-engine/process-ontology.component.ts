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

import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import {
  ProcessEngineService,
  OntologySchema,
  EntityTypeDefinition,
  DeriveOntologyRequest,
  OntologyCandidatesResponse,
  DerivationJob
} from '../../services/process-engine.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphExtractionService, ModelProvider, ModelInfo } from '../../services/graph-extraction.service';
import { JobLogViewerComponent } from '../job-history/job-log-viewer/job-log-viewer.component';

type ViewMode = 'list' | 'create' | 'detail' | 'derivePrompt' | 'deriveWizard' | 'review' | 'generating';

@Component({
  standalone: true,
  selector: 'app-process-ontology',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatCardModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatProgressSpinnerModule, MatTooltipModule, MatSnackBarModule,
    MatExpansionModule, MatChipsModule, MatDividerModule,
    JobLogViewerComponent
  ],
  template: `
    <div class="ontology-container">
      <!-- Toolbar -->
      <div class="section-toolbar">
        <button mat-raised-button color="primary" (click)="startCreate()" *ngIf="viewMode === 'list'">
          <mat-icon>add</mat-icon> New Ontology
        </button>
        <button mat-stroked-button color="primary" (click)="openDerivePrompt()" *ngIf="viewMode === 'list'">
          <mat-icon>auto_awesome</mat-icon> Derive (AI Prompt)
        </button>
        <button mat-stroked-button (click)="openDeriveWizard()" *ngIf="viewMode === 'list'">
          <mat-icon>auto_fix_high</mat-icon> Derive (Wizard)
        </button>
        <button mat-button (click)="viewMode = 'list'" *ngIf="viewMode !== 'list' && viewMode !== 'generating'">
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
          <button mat-stroked-button color="primary" (click)="openDeriveWizard()" class="empty-derive-btn">
            <mat-icon>auto_fix_high</mat-icon> Derive from a Fact Sheet
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

      <!-- Derive: AI Prompt View -->
      <div *ngIf="viewMode === 'derivePrompt'" class="derive-view">
        <h3>Derive Ontology with an AI Prompt</h3>
        <p class="hint">
          Pick a fact sheet and (optionally) describe what the ontology should capture. The model reads
          the crawl's knowledge graph and proposes a schema you can review and edit before saving.
        </p>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Fact sheet</mat-label>
          <mat-select [(ngModel)]="promptForm.factSheetId">
            <mat-option *ngFor="let fs of factSheets" [value]="fs.id">{{ fs.name }}</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Ontology name (optional)</mat-label>
          <input matInput [(ngModel)]="promptForm.name" placeholder="Defaults to '<fact sheet> Ontology'">
        </mat-form-field>

        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Guidance (optional)</mat-label>
          <textarea matInput [(ngModel)]="promptForm.guidance" rows="5"
                    placeholder="e.g. Focus on financial reporting entities, approval controls, and the relationships between forecasts and actuals."></textarea>
        </mat-form-field>

        <!-- Agent / model picker -->
        <div class="model-picker">
          <mat-form-field appearance="outline" class="picker-field">
            <mat-label>Agent / provider</mat-label>
            <mat-select [(ngModel)]="promptForm.modelProvider" (selectionChange)="promptForm.modelName = undefined">
              <mat-option value="default">Default (configured LLM)</mat-option>
              <mat-option *ngFor="let p of modelProviders" [value]="p.id" [disabled]="!p.available">
                {{ p.name }}{{ p.available ? '' : ' (unavailable)' }}
              </mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="picker-field" *ngIf="modelsFor(promptForm.modelProvider).length">
            <mat-label>Model</mat-label>
            <mat-select [(ngModel)]="promptForm.modelName">
              <mat-option [value]="undefined">Provider default</mat-option>
              <mat-option *ngFor="let m of modelsFor(promptForm.modelProvider)" [value]="m.id">{{ m.name || m.id }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="picker-field"
                          *ngIf="promptForm.modelProvider !== 'default' && !modelsFor(promptForm.modelProvider).length">
            <mat-label>Model (free-form)</mat-label>
            <input matInput [(ngModel)]="promptForm.modelName" placeholder="provider default">
          </mat-form-field>
        </div>

        <div class="derive-options">
          <mat-form-field appearance="outline" class="num-field">
            <mat-label>Max entity types</mat-label>
            <input matInput type="number" min="1" max="40" [(ngModel)]="promptForm.maxEntityTypes">
          </mat-form-field>
          <mat-checkbox [(ngModel)]="promptForm.includeRelationships">Relationship types</mat-checkbox>
          <mat-checkbox [(ngModel)]="promptForm.includeValidationRules">Validation rules</mat-checkbox>
        </div>

        <div class="editor-actions">
          <button mat-button (click)="viewMode = 'list'">Cancel</button>
          <button mat-raised-button color="primary" (click)="runPromptDerive()"
                  [disabled]="deriving || !promptForm.factSheetId">
            <mat-icon>auto_awesome</mat-icon> {{ deriving ? 'Starting…' : 'Derive' }}
          </button>
        </div>
      </div>

      <!-- Derive: Wizard View -->
      <div *ngIf="viewMode === 'deriveWizard'" class="derive-view">
        <h3>Derive Ontology — Wizard</h3>
        <div class="wizard-steps">
          <span class="wstep" [class.active]="wizardStep === 1" [class.done]="wizardStep > 1">1. Source</span>
          <span class="wstep" [class.active]="wizardStep === 2" [class.done]="wizardStep > 2">2. Entity types</span>
          <span class="wstep" [class.active]="wizardStep === 3" [class.done]="wizardStep > 3">3. Options</span>
          <span class="wstep" [class.active]="wizardStep === 4">4. Generate</span>
        </div>

        <!-- Step 1: source -->
        <div *ngIf="wizardStep === 1" class="wizard-body">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Fact sheet</mat-label>
            <mat-select [(ngModel)]="wizardFactSheetId" (selectionChange)="onWizardFactSheetChange()">
              <mat-option *ngFor="let fs of factSheets" [value]="fs.id">{{ fs.name }}</mat-option>
            </mat-select>
          </mat-form-field>
          <div *ngIf="loadingCandidates" class="deriving-row">
            <mat-spinner diameter="20"></mat-spinner>
            <span>Loading crawl-graph candidates…</span>
          </div>
          <div *ngIf="candidates && !loadingCandidates" class="graph-summary">
            <div *ngIf="!candidates.graphAvailable" class="warn-banner">
              <mat-icon>warning</mat-icon>
              <span>This fact sheet has no knowledge graph yet. Build the graph (Tools → Knowledge Graph)
                for grounded derivation, or continue to let the model work from the name/description and your guidance.</span>
            </div>
            <div *ngIf="candidates.graphAvailable" class="stat-grid">
              <div><span class="stat-num">{{ candidates.entityCount }}</span><span class="stat-lbl">entities</span></div>
              <div><span class="stat-num">{{ candidates.documentCount }}</span><span class="stat-lbl">documents</span></div>
              <div><span class="stat-num">{{ candidates.distinctConcepts }}</span><span class="stat-lbl">concepts</span></div>
              <div><span class="stat-num">{{ candidates.totalEdges }}</span><span class="stat-lbl">edges</span></div>
            </div>
          </div>
        </div>

        <!-- Step 2: entity types -->
        <div *ngIf="wizardStep === 2" class="wizard-body">
          <p class="hint">Select the crawl concepts to seed as entity types. Leave all unselected to let the model decide.</p>
          <div *ngIf="!candidates?.candidateEntityTypes?.length" class="empty-sub">
            No concept candidates available — the model will infer entity types from the documents.
          </div>
          <div class="candidate-list" *ngIf="candidates?.candidateEntityTypes?.length">
            <div class="candidate-item" *ngFor="let c of candidates?.candidateEntityTypes"
                 [class.selected]="isSeedSelected(c.suggestedEntityName)" (click)="toggleSeed(c.suggestedEntityName)">
              <mat-icon class="cand-check" [class.on]="isSeedSelected(c.suggestedEntityName)">
                {{ isSeedSelected(c.suggestedEntityName) ? 'check_box' : 'check_box_outline_blank' }}
              </mat-icon>
              <span class="cand-name">{{ c.suggestedEntityName }}</span>
              <span class="cand-concept">{{ c.concept }}</span>
              <span class="cand-mentions">{{ c.mentions }} mentions &bull; {{ c.suggestedClassification }}</span>
            </div>
          </div>
          <div class="rel-hints" *ngIf="candidates?.relationshipHints?.length">
            <div class="sub-heading">Relationship signals in the graph</div>
            <mat-chip-set>
              <mat-chip *ngFor="let r of candidates?.relationshipHints">{{ r.type }} ({{ r.count }})</mat-chip>
            </mat-chip-set>
          </div>
        </div>

        <!-- Step 3: options -->
        <div *ngIf="wizardStep === 3" class="wizard-body">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Ontology name (optional)</mat-label>
            <input matInput [(ngModel)]="wizardName">
          </mat-form-field>

          <!-- Agent / model picker -->
          <div class="model-picker">
            <mat-form-field appearance="outline" class="picker-field">
              <mat-label>Agent / provider</mat-label>
              <mat-select [(ngModel)]="wizardModelProvider" (selectionChange)="wizardModelName = undefined">
                <mat-option value="default">Default (configured LLM)</mat-option>
                <mat-option *ngFor="let p of modelProviders" [value]="p.id" [disabled]="!p.available">
                  {{ p.name }}{{ p.available ? '' : ' (unavailable)' }}
                </mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="picker-field" *ngIf="modelsFor(wizardModelProvider).length">
              <mat-label>Model</mat-label>
              <mat-select [(ngModel)]="wizardModelName">
                <mat-option [value]="undefined">Provider default</mat-option>
                <mat-option *ngFor="let m of modelsFor(wizardModelProvider)" [value]="m.id">{{ m.name || m.id }}</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="picker-field"
                            *ngIf="wizardModelProvider !== 'default' && !modelsFor(wizardModelProvider).length">
              <mat-label>Model (free-form)</mat-label>
              <input matInput [(ngModel)]="wizardModelName" placeholder="provider default">
            </mat-form-field>
          </div>

          <div class="derive-options">
            <mat-form-field appearance="outline" class="num-field">
              <mat-label>Max entity types</mat-label>
              <input matInput type="number" min="1" max="40" [(ngModel)]="wizardMaxEntityTypes">
            </mat-form-field>
            <mat-checkbox [(ngModel)]="wizardIncludeRelationships">Relationship types</mat-checkbox>
            <mat-checkbox [(ngModel)]="wizardIncludeValidationRules">Validation rules</mat-checkbox>
          </div>
          <div class="focus-section">
            <div class="sub-heading">Focus on classifications (optional)</div>
            <mat-chip-set>
              <mat-chip *ngFor="let c of classificationOptions" [class.chip-active]="wizardFocus.includes(c)"
                        (click)="toggleFocus(c)">{{ c }}</mat-chip>
            </mat-chip-set>
          </div>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Extra guidance (optional)</mat-label>
            <textarea matInput [(ngModel)]="wizardGuidance" rows="3"></textarea>
          </mat-form-field>
        </div>

        <!-- Step 4: generate -->
        <div *ngIf="wizardStep === 4" class="wizard-body">
          <div class="review-summary">
            <p><strong>Fact sheet:</strong> {{ factSheetName(wizardFactSheetId) }}</p>
            <p><strong>Agent / model:</strong> {{ wizardModelProvider }}{{ wizardModelName ? ' · ' + wizardModelName : '' }}</p>
            <p><strong>Seed entity types:</strong> {{ selectedConcepts.size ? seedList() : '(model decides)' }}</p>
            <p>
              <strong>Max entity types:</strong> {{ wizardMaxEntityTypes }} &bull;
              <strong>Relationships:</strong> {{ wizardIncludeRelationships ? 'yes' : 'no' }} &bull;
              <strong>Rules:</strong> {{ wizardIncludeValidationRules ? 'yes' : 'no' }}
            </p>
            <p *ngIf="wizardFocus.length"><strong>Focus:</strong> {{ wizardFocus.join(', ') }}</p>
          </div>
        </div>

        <div class="editor-actions">
          <button mat-button (click)="viewMode = 'list'">Cancel</button>
          <button mat-button (click)="wizardBack()" [disabled]="wizardStep === 1 || deriving">
            <mat-icon>arrow_back</mat-icon> Back
          </button>
          <button mat-raised-button color="primary" *ngIf="wizardStep < 4" (click)="wizardNext()"
                  [disabled]="wizardStep === 1 && !wizardFactSheetId">
            Next <mat-icon>arrow_forward</mat-icon>
          </button>
          <button mat-raised-button color="primary" *ngIf="wizardStep === 4" (click)="runWizardDerive()"
                  [disabled]="deriving || !wizardFactSheetId">
            <mat-icon>auto_awesome</mat-icon> {{ deriving ? 'Starting…' : 'Generate' }}
          </button>
        </div>
      </div>

      <!-- Generating View (live logs + transcript) -->
      <div *ngIf="viewMode === 'generating'" class="derive-view">
        <h3>Generating Ontology…</h3>
        <div class="deriving-row">
          <mat-spinner diameter="20"></mat-spinner>
          <span>The agent is reading the crawl graph and generating the schema — live logs &amp; transcript below.</span>
        </div>
        <div class="live-panel" *ngIf="derivationTaskId as taskId">
          <app-job-log-viewer [taskId]="taskId" [isJobRunning]="generating" [logSource]="'ingest'"></app-job-log-viewer>
        </div>
        <div class="editor-actions">
          <button mat-button (click)="cancelGenerating()">Stop watching</button>
        </div>
      </div>

      <!-- Review (draft) View -->
      <div *ngIf="viewMode === 'review' && draftSchema" class="review-view">
        <div class="ai-banner">
          <mat-icon>auto_awesome</mat-icon>
          <span>Draft generated (<strong>{{ draftMethod }}</strong>) from fact sheet
            <strong>{{ draftSourceName }}</strong>. Review and edit below — nothing is saved until you click Save.</span>
        </div>

        <div class="section-heading">
          <mat-icon>category</mat-icon>
          <span>Entity Types ({{ draftSchema.entityTypes?.length || 0 }})</span>
        </div>
        <div class="draft-entity" *ngFor="let et of draftSchema.entityTypes">
          <span class="draft-et-name">{{ et.name }}</span>
          <mat-chip *ngIf="et.classification" class="chip-type">{{ et.classification }}</mat-chip>
          <span class="draft-et-meta">{{ et.fields?.length || 0 }} fields &bull; {{ et.rules?.length || 0 }} rules</span>
        </div>

        <div *ngIf="draftSchema.relationshipTypes?.length">
          <div class="section-heading">
            <mat-icon>share</mat-icon>
            <span>Relationship Types ({{ draftSchema.relationshipTypes?.length }})</span>
          </div>
          <div class="draft-rel" *ngFor="let rt of draftSchema.relationshipTypes">
            {{ rt.sourceEntityType }} —{{ rt.type }}→ {{ rt.targetEntityType }}
          </div>
        </div>

        <div class="section-heading">
          <mat-icon>code</mat-icon>
          <span>Editable JSON</span>
        </div>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Ontology JSON</mat-label>
          <textarea matInput [(ngModel)]="draftJson" rows="18" class="json-editor"></textarea>
        </mat-form-field>

        <div class="editor-actions">
          <button mat-button (click)="viewMode = 'list'">Discard</button>
          <button mat-raised-button color="primary" (click)="saveDraft()" [disabled]="saving || !draftJson.trim()">
            <mat-icon>save</mat-icon> {{ saving ? 'Saving…' : 'Save Ontology' }}
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
    .empty-derive-btn { margin-left: 8px; }

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

    .create-view, .derive-view, .review-view { max-width: 900px; }
    .create-view h3, .derive-view h3, .review-view h3 { margin: 0 0 4px; }
    .hint { font-size: 12px; color: #aaa; margin: 0 0 16px; }
    .hint code { background: #2a2a3e; padding: 2px 6px; border-radius: 4px; }
    .full-width { width: 100%; }
    .json-editor { font-family: 'JetBrains Mono', 'Fira Code', monospace; font-size: 12px; }
    .editor-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 8px; }

    /* Derivation */
    .num-field { width: 160px; }
    .derive-options { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; margin: 4px 0 8px; }
    .deriving-row { display: flex; align-items: center; gap: 10px; color: #90caf9; font-size: 13px; margin-top: 8px; }
    .model-picker { display: flex; gap: 12px; flex-wrap: wrap; }
    .picker-field { min-width: 240px; flex: 1; }
    .live-panel { margin: 12px 0; border: 1px solid rgba(255,255,255,0.08); border-radius: 8px; overflow: hidden; }

    .wizard-steps { display: flex; gap: 8px; margin: 12px 0 16px; flex-wrap: wrap; }
    .wstep { font-size: 12px; padding: 6px 12px; border-radius: 16px; background: #2a2a3e; color: #888; }
    .wstep.active { background: rgba(129,199,132,0.2); color: #81c784; font-weight: 600; }
    .wstep.done { color: #aaa; }
    .wizard-body { min-height: 180px; }

    .graph-summary { margin-top: 8px; }
    .stat-grid { display: flex; gap: 28px; }
    .stat-grid > div { display: flex; flex-direction: column; }
    .stat-num { font-size: 20px; font-weight: 600; color: #81c784; }
    .stat-lbl { font-size: 11px; color: #888; text-transform: uppercase; letter-spacing: 0.5px; }
    .warn-banner { display: flex; align-items: center; gap: 8px; background: rgba(255,183,77,0.12);
      color: #ffb74d; padding: 10px 12px; border-radius: 6px; font-size: 13px; }
    .warn-banner mat-icon { flex-shrink: 0; }

    .candidate-list { display: flex; flex-direction: column; gap: 4px; max-height: 360px; overflow-y: auto; }
    .candidate-item { display: flex; align-items: center; gap: 10px; padding: 6px 8px;
      border: 1px solid rgba(255,255,255,0.06); border-radius: 6px; cursor: pointer; }
    .candidate-item:hover { border-color: rgba(129,199,132,0.4); }
    .candidate-item.selected { border-color: rgba(129,199,132,0.6); background: rgba(129,199,132,0.08); }
    .cand-check { color: #777; font-size: 20px; width: 20px; height: 20px; }
    .cand-check.on { color: #81c784; }
    .cand-name { font-weight: 500; min-width: 160px; }
    .cand-concept { font-size: 12px; color: #aaa; flex: 1; }
    .cand-mentions { font-size: 11px; color: #888; white-space: nowrap; }
    .rel-hints { margin-top: 16px; }
    .focus-section { margin: 12px 0; }
    .chip-active { background: rgba(129,199,132,0.25) !important; color: #81c784 !important; }

    .review-summary p { margin: 4px 0; font-size: 13px; }
    .ai-banner { display: flex; align-items: center; gap: 10px; background: rgba(144,202,249,0.1);
      color: #90caf9; padding: 10px 12px; border-radius: 6px; font-size: 13px; margin-bottom: 16px; }
    .ai-banner mat-icon { flex-shrink: 0; }
    .draft-entity { display: flex; align-items: center; gap: 10px; padding: 4px 0; font-size: 13px; }
    .draft-et-name { font-weight: 500; min-width: 160px; }
    .draft-et-meta { font-size: 11px; color: #888; }
    .draft-rel { font-size: 12px; color: #aaa; font-family: monospace; padding: 2px 0; }

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
export class ProcessOntologyComponent implements OnInit, OnDestroy {
  viewMode: ViewMode = 'list';
  ontologies: OntologySchema[] = [];
  selectedOntology: OntologySchema | null = null;
  createJson = '';
  saving = false;
  validating = false;
  validationResults: Record<string, any[]> = {};

  // ── Derivation: shared ──────────────────────────────────────────────────────
  readonly classificationOptions = ['REFERENCE', 'TRANSACTIONAL', 'PATTERN', 'CONTROL', 'METRIC', 'ACTOR'];
  factSheets: FactSheet[] = [];
  modelProviders: ModelProvider[] = [];
  deriving = false;
  draftSchema: OntologySchema | null = null;
  draftJson = '';
  draftMethod = '';
  draftSourceName = '';

  // Live async-derivation state
  generating = false;
  derivationJobId: string | null = null;
  derivationTaskId: string | null = null;
  private jobPoll: any = null;

  // ── Derivation: AI prompt form ──────────────────────────────────────────────
  promptForm = this.defaultPromptForm();

  // ── Derivation: wizard ──────────────────────────────────────────────────────
  wizardStep = 1;
  wizardFactSheetId: number | null = null;
  wizardName = '';
  wizardGuidance = '';
  wizardMaxEntityTypes = 12;
  wizardIncludeRelationships = true;
  wizardIncludeValidationRules = true;
  wizardFocus: string[] = [];
  wizardModelProvider = 'default';
  wizardModelName: string | undefined = undefined;
  candidates: OntologyCandidatesResponse | null = null;
  loadingCandidates = false;
  selectedConcepts = new Set<string>();

  constructor(
    private processEngineService: ProcessEngineService,
    private factSheetService: FactSheetService,
    private graphExtractionService: GraphExtractionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadOntologies();
  }

  ngOnDestroy(): void {
    this.clearPoll();
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

  // ── Derivation: entry points ────────────────────────────────────────────────

  private defaultPromptForm() {
    return {
      factSheetId: null as number | null,
      name: '',
      guidance: '',
      maxEntityTypes: 12,
      includeRelationships: true,
      includeValidationRules: true,
      modelProvider: 'default' as string,
      modelName: undefined as string | undefined
    };
  }

  openDerivePrompt(): void {
    this.promptForm = this.defaultPromptForm();
    this.viewMode = 'derivePrompt';
    this.loadFactSheets();
    this.loadModelProviders();
  }

  openDeriveWizard(): void {
    this.resetWizard();
    this.viewMode = 'deriveWizard';
    this.loadFactSheets();
    this.loadModelProviders();
  }

  private loadFactSheets(): void {
    this.factSheetService.loadSheets().subscribe({
      next: (sheets) => { this.factSheets = sheets; },
      error: () => { this.factSheets = []; }
    });
  }

  private loadModelProviders(): void {
    this.graphExtractionService.getModelProviders().subscribe({
      next: (providers) => { this.modelProviders = providers || []; },
      error: () => { this.modelProviders = []; }
    });
  }

  /** Models available for the chosen provider id ('default' / unknown → none, i.e. provider default). */
  modelsFor(providerId: string | null | undefined): ModelInfo[] {
    if (!providerId || providerId === 'default') return [];
    return this.modelProviders.find(p => p.id === providerId)?.models ?? [];
  }

  // ── Derivation: AI prompt mode ──────────────────────────────────────────────

  runPromptDerive(): void {
    if (!this.promptForm.factSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Close', { duration: 3000 });
      return;
    }
    const req: DeriveOntologyRequest = {
      factSheetId: this.promptForm.factSheetId,
      name: this.promptForm.name?.trim() || undefined,
      guidance: this.promptForm.guidance?.trim() || undefined,
      maxEntityTypes: this.promptForm.maxEntityTypes,
      includeRelationships: this.promptForm.includeRelationships,
      includeValidationRules: this.promptForm.includeValidationRules,
      modelProvider: this.promptForm.modelProvider,
      modelName: this.promptForm.modelName || undefined
    };
    this.runDerive(req);
  }

  // ── Derivation: wizard mode ─────────────────────────────────────────────────

  private resetWizard(): void {
    this.wizardStep = 1;
    this.wizardFactSheetId = null;
    this.wizardName = '';
    this.wizardGuidance = '';
    this.wizardMaxEntityTypes = 12;
    this.wizardIncludeRelationships = true;
    this.wizardIncludeValidationRules = true;
    this.wizardFocus = [];
    this.wizardModelProvider = 'default';
    this.wizardModelName = undefined;
    this.candidates = null;
    this.selectedConcepts.clear();
  }

  wizardNext(): void { if (this.wizardStep < 4) this.wizardStep++; }
  wizardBack(): void { if (this.wizardStep > 1) this.wizardStep--; }

  onWizardFactSheetChange(): void {
    this.candidates = null;
    this.selectedConcepts.clear();
    if (this.wizardFactSheetId) {
      this.loadCandidates(this.wizardFactSheetId);
    }
  }

  private loadCandidates(factSheetId: number): void {
    this.loadingCandidates = true;
    this.processEngineService.getDerivationCandidates(factSheetId, 60).subscribe({
      next: (c) => { this.candidates = c; this.loadingCandidates = false; },
      error: (err) => {
        this.loadingCandidates = false;
        this.candidates = null;
        this.snackBar.open('Failed to load candidates: ' + (err.error?.error || err.message), 'Close', { duration: 4000 });
      }
    });
  }

  toggleSeed(name: string): void {
    if (this.selectedConcepts.has(name)) this.selectedConcepts.delete(name);
    else this.selectedConcepts.add(name);
  }

  isSeedSelected(name: string): boolean {
    return this.selectedConcepts.has(name);
  }

  toggleFocus(classification: string): void {
    const i = this.wizardFocus.indexOf(classification);
    if (i >= 0) this.wizardFocus.splice(i, 1);
    else this.wizardFocus.push(classification);
  }

  seedList(): string {
    return Array.from(this.selectedConcepts).join(', ');
  }

  factSheetName(id: number | null): string {
    if (id == null) return '(none selected)';
    return this.factSheets.find(fs => fs.id === id)?.name ?? '#' + id;
  }

  runWizardDerive(): void {
    if (!this.wizardFactSheetId) {
      this.snackBar.open('Select a fact sheet first', 'Close', { duration: 3000 });
      return;
    }
    const req: DeriveOntologyRequest = {
      factSheetId: this.wizardFactSheetId,
      name: this.wizardName?.trim() || undefined,
      guidance: this.wizardGuidance?.trim() || undefined,
      maxEntityTypes: this.wizardMaxEntityTypes,
      includeRelationships: this.wizardIncludeRelationships,
      includeValidationRules: this.wizardIncludeValidationRules,
      focusClassifications: this.wizardFocus.length ? this.wizardFocus : undefined,
      seedEntityTypes: this.selectedConcepts.size ? Array.from(this.selectedConcepts) : undefined,
      modelProvider: this.wizardModelProvider,
      modelName: this.wizardModelName || undefined
    };
    this.runDerive(req);
  }

  // ── Derivation: async run + live logs + review/save ─────────────────────────

  private runDerive(req: DeriveOntologyRequest): void {
    this.deriving = true;
    this.processEngineService.deriveOntologyAsync(req).subscribe({
      next: (job) => {
        this.deriving = false;
        this.derivationJobId = job.jobId;
        this.derivationTaskId = job.taskId;
        this.generating = true;
        this.viewMode = 'generating';
        this.pollJob();
      },
      error: (err) => {
        this.deriving = false;
        const msg = err.error?.error || err.error?.message || err.message;
        this.snackBar.open('Could not start derivation: ' + msg, 'Close', { duration: 5000 });
      }
    });
  }

  private pollJob(): void {
    this.clearPoll();
    this.jobPoll = setInterval(() => {
      // If the user navigated away, stop reacting.
      if (this.viewMode !== 'generating' || !this.derivationJobId) {
        this.clearPoll();
        return;
      }
      this.processEngineService.getDerivationJob(this.derivationJobId).subscribe({
        next: (job: DerivationJob) => {
          if (job.status === 'COMPLETED' && job.draft) {
            this.clearPoll();
            this.generating = false;
            this.openReview(job.draft);
          } else if (job.status === 'FAILED') {
            this.clearPoll();
            this.generating = false;
            this.snackBar.open('Derivation failed: ' + (job.error || 'unknown error'), 'Close', { duration: 6000 });
            this.viewMode = 'list';
          }
        },
        error: () => { /* transient — keep polling */ }
      });
    }, 2000);
  }

  private clearPoll(): void {
    if (this.jobPoll) {
      clearInterval(this.jobPoll);
      this.jobPoll = null;
    }
  }

  cancelGenerating(): void {
    this.clearPoll();
    this.generating = false;
    this.derivationJobId = null;
    this.derivationTaskId = null;
    this.viewMode = 'list';
  }

  private openReview(schema: OntologySchema): void {
    this.draftSchema = schema;
    this.draftJson = JSON.stringify(schema, null, 2);
    this.draftMethod = (schema.metadata?.['generationMethod'] as string) || 'unknown';
    this.draftSourceName = (schema.metadata?.['derivedFromFactSheetName'] as string) || '';
    this.viewMode = 'review';
  }

  saveDraft(): void {
    let schema: OntologySchema;
    try {
      schema = JSON.parse(this.draftJson);
    } catch {
      this.snackBar.open('Draft JSON is invalid', 'Close', { duration: 3000 });
      return;
    }
    this.saving = true;
    this.processEngineService.createOntology(schema).subscribe({
      next: (result) => {
        this.snackBar.open('Ontology created: ' + result.name, 'Close', { duration: 3000 });
        this.ontologies = [...this.ontologies, result];
        this.saving = false;
        this.draftSchema = null;
        this.viewMode = 'list';
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open('Failed to save: ' + (err.error?.message || err.message), 'Close', { duration: 4000 });
      }
    });
  }
}
