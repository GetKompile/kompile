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
import { GraphOntologyService, GraphConformanceReport } from '../../services/graph-ontology.service';
import { ProcessEngineService, OntologySchema } from '../../services/process-engine.service';

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
    MatSnackBarModule
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

  constructor(private ontologyService: GraphOntologyService,
              private processEngine: ProcessEngineService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadOntologies();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      this.report = null;
      if (this.factSheetId != null) {
        this.loadConformance();
      }
    }
  }

  private loadOntologies(): void {
    this.processEngine.listOntologies().subscribe({
      next: (o) => (this.ontologies = o || []),
      error: () => { /* listing is best-effort; binding can still be inspected */ }
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

  pct(v: number | null | undefined): string {
    return v == null ? '—' : (v * 100).toFixed(1) + '%';
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
