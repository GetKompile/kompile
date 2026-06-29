/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { GraphOntologyService, GraphConformanceReport, OwlReasoningStatus } from '../../services/graph-ontology.service';
import { ProcessEngineService, OntologySchema, DeriveOntologyRequest } from '../../services/process-engine.service';
import { of } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';

/**
 * Bind a governing ontology to a fact sheet's graph and show the conformance report (graph-as-asset
 * Phase 6/8): bound ontology + conformance score, plus the non-conforming ENTITY nodes and edges.
 */
@Component({
  selector: 'app-graph-ontology-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDividerModule
  ],
  templateUrl: './graph-ontology-panel.component.html',
  styleUrls: ['./graph-ontology-panel.component.css']
})
export class GraphOntologyPanelComponent implements OnInit, OnChanges {
  @Input() factSheetId: number | null = null;

  ontologies: OntologySchema[] = [];
  report: GraphConformanceReport | null = null;
  selectedOntologyId: string | null = null;

  loading = false;
  binding = false;

  // OWL reasoning status
  owlStatus: OwlReasoningStatus | null = null;
  owlLoading = false;
  classifying = false;
  owlClassifying = false;
  inducingTypes = false;

  // D4: "Derive from graph" state
  deriving = false;
  derivedDraft: OntologySchema | null = null;
  deriveError: string | null = null;

  constructor(private ontologyService: GraphOntologyService,
              private processEngine: ProcessEngineService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadOntologies();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      this.report = null;
      this.owlStatus = null;
      if (this.factSheetId != null) {
        this.loadConformance();
        this.loadOwlStatus();
      }
    }
  }

  loadOwlStatus(): void {
    if (this.factSheetId == null) return;
    this.owlLoading = true;
    this.ontologyService.owl(this.factSheetId).subscribe({
      next: (s) => { this.owlStatus = s; this.owlLoading = false; },
      error: () => { this.owlStatus = null; this.owlLoading = false; /* 404 / not available = show not-bound state */ }
    });
  }

  loadConformance(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.ontologyService.conformance(this.factSheetId).subscribe({
      next: (r) => { this.report = r; this.loading = false; },
      error: (e) => { this.loading = false; this.error('Failed to load conformance', e); }
    });
  }

  bind(): void {
    if (this.factSheetId == null || !this.selectedOntologyId) {
      return;
    }
    this.binding = true;
    this.ontologyService.bind(this.factSheetId, this.selectedOntologyId).subscribe({
      next: () => { this.binding = false; this.ok('Ontology bound'); this.loadConformance(); },
      error: (e) => { this.binding = false; this.error('Bind failed', e); }
    });
  }

  unbind(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.binding = true;
    this.ontologyService.unbind(this.factSheetId).subscribe({
      next: () => { this.binding = false; this.ok('Ontology unbound'); this.loadConformance(); },
      error: (e) => { this.binding = false; this.error('Unbind failed', e); }
    });
  }

  /** Generate crawl schema/type materialization (is-a realization + has-a closure) and persist it. */
  classify(): void {
    if (this.factSheetId == null) return;
    this.classifying = true;
    this.ontologyService.classify(this.factSheetId).subscribe({
      next: (r) => {
        this.classifying = false;
        if (!r.ontologyBound) {
          this.ok('No schema generated: no ontology bound and no entities to derive one from');
        } else {
          this.ok(`Generated schema types for ${r.entitiesClassified} entities, inferred ${r.edgesMaterialized} has-a edges`);
        }
        this.loadConformance();
        this.loadOwlStatus();
      },
      error: (e) => { this.classifying = false; this.error('Schema generation failed', e); }
    });
  }

  /** Run only OWL classification/materialization and persist inferred graph facts. */
  runOwlOnly(): void {
    if (this.factSheetId == null) return;
    this.owlClassifying = true;
    this.ontologyService.classifyOwlOnly(this.factSheetId).subscribe({
      next: (r) => {
        this.owlClassifying = false;
        if (!r.ontologyBound) {
          this.ok('OWL skipped: no ontology bound and no entities to derive one from');
        } else {
          this.ok(`OWL inferred ${r.inferredTypeCount} types and ${r.inferredRelationCount} relationships`);
        }
        this.loadConformance();
        this.loadOwlStatus();
      },
      error: (e) => { this.owlClassifying = false; this.error('OWL inference failed', e); }
    });
  }

  /**
   * Run the LLM schema update path explicitly. OWL runs before induction so the LLM sees current
   * typed evidence, and runs again after a schema change so inferred relationships surface in the UI.
   */
  induceTypes(): void {
    if (this.factSheetId == null || this.inducingTypes) return;
    const factSheetId = this.factSheetId;
    this.inducingTypes = true;

    this.ontologyService.classifyOwlOnly(factSheetId).pipe(
      switchMap((firstPass) => {
        if (!firstPass.ontologyBound) {
          return of({
            induction: { changed: false, aliasesAdded: 0, typesAdded: 0, version: 0 },
            classification: firstPass
          });
        }
        return this.ontologyService.induceTypes(factSheetId).pipe(
          switchMap((induction) => {
            if (!induction.changed) {
              return of({ induction, classification: firstPass });
            }
            return this.ontologyService.classifyOwlOnly(factSheetId).pipe(
              map((classification) => ({ induction, classification }))
            );
          })
        );
      })
    ).subscribe({
      next: ({ induction, classification }) => {
        this.inducingTypes = false;
        if (!classification.ontologyBound) {
          this.ok('LLM schema update skipped: no ontology bound and no entities to derive one from');
        } else if (induction.changed) {
          this.ok(`LLM schema update added ${induction.typesAdded} types and ${induction.aliasesAdded} aliases`);
        } else {
          this.ok('LLM schema update found no new schema gaps; OWL inference refreshed');
        }
        this.loadConformance();
        this.loadOwlStatus();
      },
      error: (e) => { this.inducingTypes = false; this.error('LLM schema update failed', e); }
    });
  }

  pct(v: number | null | undefined): string {
    return v == null ? '—' : (v * 100).toFixed(1) + '%';
  }

  /** Humanize an OWL sample-entailment string into plain English (falls back to the raw string). */
  entailmentProse(e: string): string {
    if (!e) return e;
    let m: RegExpMatchArray | null;
    if ((m = e.match(/^domain\(([^)]+)\)\s*⊑\s*(.+)$/))) return `${m[1].trim()} must start from a ${m[2].trim()}`;
    if ((m = e.match(/^range\(([^)]+)\)\s*⊑\s*(.+)$/))) return `${m[1].trim()} must point to a ${m[2].trim()}`;
    if ((m = e.match(/^(.+?)\s*⊑\s*(.+)$/))) return `${m[1].trim()} is a kind of ${m[2].trim()}`;
    if ((m = e.match(/^(.+?)\s*∈\s*(.+)$/))) return `${m[1].trim()} is a ${m[2].trim()}`;
    if ((m = e.match(/^(.+?)\s+is transitive/i))) return `${m[1].trim()} relationships chain transitively`;
    return e;
  }

  /**
   * D4: Derive an ontology from the current cold graph.
   * POSTs to POST /api/process/ontology/derive (body = DeriveOntologyRequest).
   * Returns an unsaved draft that can then be bound via the existing bind UI.
   */
  deriveFromGraph(): void {
    if (this.factSheetId == null || this.deriving) return;
    this.deriving = true;
    this.derivedDraft = null;
    this.deriveError = null;

    const request: DeriveOntologyRequest = {
      factSheetId: this.factSheetId,
      includeRelationships: true,
      includeValidationRules: false,
    };

    this.processEngine.deriveOntology(request).subscribe({
      next: (draft) => {
        this.deriving = false;
        this.derivedDraft = draft;
        // Refresh the ontology list so the draft (if saved externally) appears in the picker
        this.loadOntologies();
        this.ok('Ontology draft derived — review below and bind it to activate conformance checking.');
      },
      error: (e) => {
        this.deriving = false;
        this.deriveError = e?.error?.message || e?.message || 'Derivation failed';
        this.error('Derive failed', e);
      },
    });
  }

  /** D4: dismiss the derived draft panel. */
  dismissDraft(): void {
    this.derivedDraft = null;
    this.deriveError = null;
  }

  private loadOntologies(): void {
    this.processEngine.listOntologies().subscribe({
      next: (o) => (this.ontologies = o || []),
      error: () => { /* listing is best-effort; binding can still be inspected */ }
    });
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
