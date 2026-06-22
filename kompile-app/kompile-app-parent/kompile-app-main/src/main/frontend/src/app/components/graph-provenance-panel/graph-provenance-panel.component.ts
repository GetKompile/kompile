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
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { GraphProvenanceService, NodeProvenance, ProvenancePurgeResult } from '../../services/graph-provenance.service';
import { GraphService } from '../../services/graph.service';
import { GraphNode } from '../../models/graph-models';
import { UnifiedCrawlService, JobSummary } from '../../services/unified-crawl.service';

/**
 * Provenance browsing + purge (graph-as-asset Phase 3/8): search for a node, trace it to its source
 * document / chunk / crawl run / extraction model, and purge all facts from a crawl run or source
 * document (with a dry-run preview).
 */
@Component({
  selector: 'app-graph-provenance-panel',
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
    MatCheckboxModule,
    MatProgressSpinnerModule,
    MatSnackBarModule
  ],
  templateUrl: './graph-provenance-panel.component.html',
  styleUrls: ['./graph-provenance-panel.component.css']
})
export class GraphProvenancePanelComponent implements OnInit {
  @Input() factSheetId: number | null = null;

  query = '';
  results: GraphNode[] = [];
  searching = false;

  selected: NodeProvenance | null = null;
  loadingProv = false;

  purgeBy: 'crawlRunId' | 'sourceDocumentId' = 'crawlRunId';
  purgeValue = '';
  purgeResult: ProvenancePurgeResult | null = null;
  purging = false;

  /** Crawl runs for the purge picker — jobId is the crawlRunId stored in node provenance metadata. */
  crawlRuns: JobSummary[] = [];
  loadingRuns = false;

  constructor(private provService: GraphProvenanceService,
              private graphService: GraphService,
              private unifiedCrawl: UnifiedCrawlService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadCrawlRuns();
  }

  loadCrawlRuns(): void {
    this.loadingRuns = true;
    this.unifiedCrawl.listJobs().subscribe({
      next: (jobs) => { this.crawlRuns = jobs || []; this.loadingRuns = false; },
      error: () => { this.crawlRuns = []; this.loadingRuns = false; }
    });
  }

  /** Switching the match dimension clears the value (a jobId is not a valid source-document id). */
  onPurgeByChange(): void {
    this.purgeValue = '';
    if (this.purgeBy === 'crawlRunId' && this.crawlRuns.length === 0) {
      this.loadCrawlRuns();
    }
  }

  search(): void {
    if (!this.query || !this.query.trim()) {
      return;
    }
    this.searching = true;
    this.graphService.searchNodes(this.query.trim()).subscribe({
      next: (n) => { this.results = n || []; this.searching = false; },
      error: (e) => { this.searching = false; this.error('Search failed', e); }
    });
  }

  select(node: GraphNode): void {
    this.loadingProv = true;
    this.selected = null;
    this.provService.getProvenance(node.nodeId).subscribe({
      next: (p) => { this.selected = p; this.loadingProv = false; },
      error: (e) => { this.loadingProv = false; this.error('Failed to load provenance', e); }
    });
  }

  provEntries(p: NodeProvenance): { key: string; value: any }[] {
    return Object.entries(p.provenance || {}).map(([key, value]) => ({ key, value }));
  }

  runPurge(dryRun: boolean): void {
    if (!this.purgeValue || !this.purgeValue.trim()) {
      return;
    }
    this.purging = true;
    this.purgeResult = null;
    const opts: any = { dryRun, factSheetId: this.factSheetId };
    opts[this.purgeBy] = this.purgeValue.trim();
    this.provService.purge(opts).subscribe({
      next: (r) => {
        this.purging = false;
        this.purgeResult = r;
        this.ok(dryRun ? 'Dry run complete' : 'Purge complete');
      },
      error: (e) => { this.purging = false; this.error('Purge failed', e); }
    });
  }

  purgeResultEntries(): { key: string; value: any }[] {
    return this.purgeResult ? Object.entries(this.purgeResult).map(([key, value]) => ({ key, value })) : [];
  }

  private ok(msg: string): void {
    this.snackBar.open(msg, 'OK', { duration: 2500 });
  }

  private error(prefix: string, e: any): void {
    this.snackBar.open(`${prefix}: ${e?.error?.message || e?.message || 'error'}`, 'Dismiss', { duration: 5000 });
  }
}
