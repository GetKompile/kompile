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

import { Component, Input, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphDiffService, EntityDiff } from '../../services/graph-diff.service';

/**
 * Semantic entity-level diff of a fact sheet's graph between two times (graph-as-asset Phase 5/8):
 * which entities were added / removed / had attributes changed, from the mutation-log snapshots.
 */
@Component({
  selector: 'app-graph-diff-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-diff-panel.component.html',
  styleUrls: ['./graph-diff-panel.component.css']
})
export class GraphDiffPanelComponent implements OnInit {
  @Input() factSheetId: number | null = null;

  from = '';
  to = '';
  diff: EntityDiff | null = null;
  loading = false;

  constructor(private diffService: GraphDiffService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    const now = new Date();
    const weekAgo = new Date(now.getTime() - 7 * 24 * 3600 * 1000);
    this.to = this.fmt(now);
    this.from = this.fmt(weekAgo);
  }

  compute(): void {
    if (this.factSheetId == null) {
      this.snackBar.open('Select a fact sheet first', 'OK', { duration: 3000 });
      return;
    }
    if (!this.from || !this.to) {
      return;
    }
    this.loading = true;
    this.diff = null;
    this.diffService.semanticDiff(this.factSheetId, this.from, this.to).subscribe({
      next: (d) => { this.diff = d; this.loading = false; },
      error: (e) => { this.loading = false; this.error('Diff failed', e); }
    });
  }

  private fmt(d: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
