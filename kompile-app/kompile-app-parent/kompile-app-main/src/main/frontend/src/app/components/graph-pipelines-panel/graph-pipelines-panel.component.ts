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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphPipelinesService, GraphUpdatePipelineConfig } from '../../services/graph-pipelines.service';

const DEFAULT_STEPS = '[\n  { "step": "EXTRACT_GRAPH", "params": {} }\n]';

/**
 * CRUD UI for channel→graph update pipelines (graph-as-asset Phase 2/8): an inbound channel message
 * (slack/email/…) runs the configured steps to update the graph without a full crawl. Steps and the
 * optional message filter are JSON (edited as text here); channels are a comma-separated list.
 */
@Component({
  selector: 'app-graph-pipelines-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-pipelines-panel.component.html',
  styleUrls: ['./graph-pipelines-panel.component.css']
})
export class GraphPipelinesPanelComponent implements OnInit {
  @Input() factSheetId: number | null = null;

  pipelines: GraphUpdatePipelineConfig[] = [];
  loading = false;
  saving = false;
  showCreate = false;

  draft: GraphUpdatePipelineConfig = this.blank();
  draftSteps = DEFAULT_STEPS;
  draftFilter = '';

  constructor(private svc: GraphPipelinesService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.refresh();
  }

  private blank(): GraphUpdatePipelineConfig {
    return { pipelineName: '', enabled: true, triggerChannels: '', requireApproval: false, priority: 0 };
  }

  refresh(): void {
    this.loading = true;
    this.svc.list().subscribe({
      next: (p) => { this.pipelines = p; this.loading = false; },
      error: (e) => { this.loading = false; this.error('Failed to load pipelines', e); }
    });
  }

  openCreate(): void {
    this.draft = this.blank();
    this.draftSteps = DEFAULT_STEPS;
    this.draftFilter = '';
    this.showCreate = true;
  }

  create(): void {
    if (!this.draft.pipelineName || !this.draft.pipelineName.trim()) {
      return;
    }
    if (!this.validJsonOrBlank(this.draftSteps, 'Processing steps') ||
        !this.validJsonOrBlank(this.draftFilter, 'Filter')) {
      return;
    }
    const cfg: GraphUpdatePipelineConfig = {
      ...this.draft,
      processingSteps: this.draftSteps.trim() || undefined,
      filterJson: this.draftFilter.trim() || undefined
    };
    if (this.factSheetId != null && cfg.targetFactSheetId == null) {
      cfg.targetFactSheetId = this.factSheetId;
    }
    this.saving = true;
    this.svc.create(cfg).subscribe({
      next: () => { this.saving = false; this.showCreate = false; this.refresh(); this.ok('Pipeline created'); },
      error: (e) => { this.saving = false; this.error('Create failed', e); }
    });
  }

  toggle(p: GraphUpdatePipelineConfig): void {
    if (!p.pipelineId) {
      return;
    }
    this.svc.setEnabled(p.pipelineId, !p.enabled).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Toggle failed', e)
    });
  }

  remove(p: GraphUpdatePipelineConfig): void {
    if (!p.pipelineId) {
      return;
    }
    this.svc.delete(p.pipelineId).subscribe({
      next: () => this.refresh(),
      error: (e) => this.error('Delete failed', e)
    });
  }

  /** Human-readable step chain from the processingSteps JSON. */
  stepsSummary(p: GraphUpdatePipelineConfig): string {
    if (!p.processingSteps) {
      return '—';
    }
    try {
      const arr = JSON.parse(p.processingSteps);
      if (Array.isArray(arr)) {
        const names = arr.map((s) => s && s.step).filter((x) => !!x);
        return names.length ? names.join(' → ') : '—';
      }
    } catch {
      /* fall through */
    }
    return '(custom)';
  }

  private validJsonOrBlank(text: string, label: string): boolean {
    if (!text || !text.trim()) {
      return true;
    }
    try {
      JSON.parse(text);
      return true;
    } catch {
      this.snackBar.open(`${label} must be valid JSON`, 'Dismiss', { duration: 5000 });
      return false;
    }
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
