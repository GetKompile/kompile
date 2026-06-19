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
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphRulesService, GraphRuleConfig } from '../../services/graph-rules.service';

/**
 * CRUD UI for the reactive graph-rules engine (graph-as-asset Phase 4/8): list rules, create
 * CHANGESET-threshold or per-MUTATION rules, toggle enabled, delete. The action (LOG / WEBHOOK)
 * fires when the rule matches a graph change.
 */
@Component({
  selector: 'app-graph-rules-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-rules-panel.component.html',
  styleUrls: ['./graph-rules-panel.component.css']
})
export class GraphRulesPanelComponent implements OnInit {
  /** When set, new rules default to this fact-sheet scope. */
  @Input() factSheetId: number | null = null;

  rules: GraphRuleConfig[] = [];
  loading = false;
  showCreate = false;
  draft: GraphRuleConfig = this.blank();

  constructor(private svc: GraphRulesService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.refresh();
  }

  private blank(): GraphRuleConfig {
    return { name: '', triggerType: 'CHANGESET', actionType: 'LOG', enabled: true };
  }

  refresh(): void {
    this.loading = true;
    this.svc.list().subscribe({
      next: (r) => { this.rules = r; this.loading = false; },
      error: (e) => { this.loading = false; this.error('Failed to load rules', e); }
    });
  }

  openCreate(): void {
    this.draft = this.blank();
    this.showCreate = true;
  }

  create(): void {
    if (!this.draft.name || !this.draft.name.trim()) {
      return;
    }
    const payload: GraphRuleConfig = { ...this.draft };
    if (this.factSheetId != null && payload.factSheetId == null) {
      payload.factSheetId = this.factSheetId;
    }
    this.svc.create(payload).subscribe({
      next: () => { this.showCreate = false; this.draft = this.blank(); this.refresh(); this.ok('Rule created'); },
      error: (e) => this.error('Create failed', e)
    });
  }

  toggle(rule: GraphRuleConfig): void {
    if (!rule.ruleId) {
      return;
    }
    this.svc.setEnabled(rule.ruleId, !rule.enabled).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Toggle failed', e)
    });
  }

  remove(rule: GraphRuleConfig): void {
    if (!rule.ruleId) {
      return;
    }
    this.svc.delete(rule.ruleId).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Delete failed', e)
    });
  }

  /** Human-readable summary of a rule's match condition. */
  condition(r: GraphRuleConfig): string {
    if (r.triggerType === 'MUTATION') {
      const parts = [r.onMutationType, r.onEntityKind, r.onEntityType].filter((x) => !!x);
      return parts.length ? parts.join(' / ') : 'any mutation';
    }
    const parts: string[] = [];
    if (r.minNodesCreated != null) parts.push('≥' + r.minNodesCreated + ' nodes');
    if (r.minEdgesCreated != null) parts.push('≥' + r.minEdgesCreated + ' edges');
    return parts.length ? parts.join(', ') : 'any changeset';
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
