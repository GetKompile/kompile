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

import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphHealthService, GraphHealthSnapshot, GraphComparison } from '../../services/graph-health.service';

/**
 * Surfaces a fact sheet's knowledge-graph health (graph-as-asset Phase 7): the live metric vector,
 * a persisted time series ("take snapshot"), and a cross-graph comparison. Also shows the Phase-6
 * ontology conformance score carried in the snapshot.
 */
@Component({
  selector: 'app-graph-health-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-health-panel.component.html',
  styleUrls: ['./graph-health-panel.component.css']
})
export class GraphHealthPanelComponent implements OnChanges {
  @Input() factSheetId: number | null = null;

  current: GraphHealthSnapshot | null = null;
  history: GraphHealthSnapshot[] = [];
  comparison: GraphComparison | null = null;
  compareToId: number | null = null;

  loading = false;
  snapshotting = false;
  comparing = false;

  constructor(private health: GraphHealthService, private snackBar: MatSnackBar) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      this.current = null;
      this.history = [];
      this.comparison = null;
      if (this.factSheetId != null) {
        this.refresh();
      }
    }
  }

  refresh(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.loading = true;
    this.health.getHealth(this.factSheetId).subscribe({
      next: (s) => { this.current = s; this.loading = false; },
      error: (err) => { this.loading = false; this.error('Failed to load health', err); }
    });
    this.health.getHistory(this.factSheetId).subscribe({
      next: (h) => (this.history = h),
      error: () => { /* history is best-effort */ }
    });
  }

  takeSnapshot(): void {
    if (this.factSheetId == null) {
      return;
    }
    this.snapshotting = true;
    this.health.takeSnapshot(this.factSheetId).subscribe({
      next: (s) => {
        this.snapshotting = false;
        this.current = s;
        this.snackBar.open('Health snapshot saved', 'OK', { duration: 2500 });
        this.refresh();
      },
      error: (err) => { this.snapshotting = false; this.error('Snapshot failed', err); }
    });
  }

  runCompare(): void {
    if (this.factSheetId == null || this.compareToId == null) {
      return;
    }
    this.comparing = true;
    this.health.compare(this.factSheetId, this.compareToId).subscribe({
      next: (c) => { this.comparison = c; this.comparing = false; },
      error: (err) => { this.comparing = false; this.error('Compare failed', err); }
    });
  }

  /** Format a 0..1 fraction as a percentage, or em-dash when null. */
  pct(v: number | null | undefined): string {
    return v == null ? '—' : (v * 100).toFixed(1) + '%';
  }

  nodeTypeEntries(s: GraphHealthSnapshot): { type: string; count: number }[] {
    return Object.entries(s.nodesByType || {}).map(([type, count]) => ({ type, count: count as number }));
  }

  private error(prefix: string, err: any): void {
    const msg = err?.error?.message || err?.message || 'unknown error';
    this.snackBar.open(`${prefix}: ${msg}`, 'Dismiss', { duration: 5000 });
  }
}
