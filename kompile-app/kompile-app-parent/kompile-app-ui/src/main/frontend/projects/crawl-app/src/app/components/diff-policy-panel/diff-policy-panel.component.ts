/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCheckboxModule } from '@angular/material/checkbox';

import { DiffViewComponent } from '../diff-view/diff-view.component';
import {
  DiffPolicyService, DiffPolicyViolation, DiffPolicyRules, PathRule
} from '@shared/services/diff-policy.service';
import { DiffIndexService, DiffIndexEntry } from '@shared/services/diff-index.service';

/**
 * Governance view over captured agent file-changes: scan the mined diffs against
 * policy (path-of-concern globs + reused content rules), then browse the resulting
 * violations by file/agent/severity/detector and open the offending diff.
 */
@Component({
  selector: 'app-diff-policy-panel',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatTooltipModule, MatProgressBarModule, MatCheckboxModule,
    DiffViewComponent
  ],
  templateUrl: './diff-policy-panel.component.html',
  styleUrls: ['./diff-policy-panel.component.css']
})
export class DiffPolicyPanelComponent implements OnInit {

  violations: DiffPolicyViolation[] = [];
  stats: Record<string, any> = {};
  loadingViolations = false;
  scanning = false;
  lastScan: Record<string, any> | null = null;

  // Filters (filePath accepts a glob).
  fileFilter = '';
  agentFilter = '';
  severityFilter = '';
  detectorFilter = '';

  // Scan scope.
  scanFilePath = '';
  scanAgent = '';
  scanSince = '';
  scanUntil = '';
  useLlm = false;
  llmAvailable = false;

  // Rules editor.
  showRules = false;
  rules: DiffPolicyRules = { pathRules: [], contentRulesText: '' };
  savingRules = false;

  // View options + selection.
  viewMode: 'unified' | 'split' = 'split';
  selectedViolation: DiffPolicyViolation | null = null;
  selectedEntry: DiffIndexEntry | null = null;
  loadingEntry = false;

  readonly severities = ['critical', 'error', 'warning', 'info'];

  constructor(
    private policyService: DiffPolicyService,
    private diffService: DiffIndexService
  ) {}

  ngOnInit(): void {
    this.loadRules();
    this.loadStats();
    this.loadViolations();
  }

  // ── Load ─────────────────────────────────────────────────────────

  loadViolations(): void {
    this.loadingViolations = true;
    this.selectedViolation = null;
    this.selectedEntry = null;
    this.policyService.listViolations({
      filePath: this.fileFilter || undefined,
      agent: this.agentFilter || undefined,
      severity: this.severityFilter || undefined,
      detector: this.detectorFilter || undefined,
      limit: 500
    }).subscribe({
      next: (v) => { this.violations = v; this.loadingViolations = false; },
      error: () => { this.violations = []; this.loadingViolations = false; }
    });
  }

  loadStats(): void {
    this.policyService.stats().subscribe({
      next: (s) => this.stats = s || {},
      error: () => this.stats = {}
    });
  }

  loadRules(): void {
    this.policyService.getRules().subscribe({
      next: (r) => {
        this.rules = { pathRules: r.pathRules || [], contentRulesText: r.contentRulesText || '' };
        this.llmAvailable = !!r.llmAvailable;
      },
      error: () => this.rules = { pathRules: [], contentRulesText: '' }
    });
  }

  // ── Scan ─────────────────────────────────────────────────────────

  runScan(): void {
    this.scanning = true;
    this.policyService.scan({
      filePath: this.scanFilePath || undefined,
      agent: this.scanAgent || undefined,
      since: this.scanSince || undefined,
      until: this.scanUntil || undefined,
      useLlm: this.useLlm
    }).subscribe({
      next: (summary) => {
        this.lastScan = summary;
        this.scanning = false;
        this.loadStats();
        this.loadViolations();
      },
      error: () => { this.scanning = false; }
    });
  }

  clearViolations(): void {
    this.policyService.clearViolations().subscribe(() => {
      this.violations = [];
      this.selectedViolation = null;
      this.selectedEntry = null;
      this.loadStats();
    });
  }

  // ── Rules editing ────────────────────────────────────────────────

  addPathRule(): void {
    this.rules.pathRules = [...this.rules.pathRules,
      { glob: '', severity: 'critical', description: '' }];
  }

  removePathRule(index: number): void {
    this.rules.pathRules = this.rules.pathRules.filter((_, i) => i !== index);
  }

  saveRules(): void {
    this.savingRules = true;
    const cleaned: PathRule[] = this.rules.pathRules.filter(r => r.glob && r.glob.trim());
    this.policyService.saveRules({ pathRules: cleaned, contentRulesText: this.rules.contentRulesText })
      .subscribe({
        next: () => { this.savingRules = false; this.loadRules(); },
        error: () => { this.savingRules = false; }
      });
  }

  // ── Selection ────────────────────────────────────────────────────

  selectViolation(v: DiffPolicyViolation): void {
    this.selectedViolation = v;
    this.selectedEntry = null;
    this.loadingEntry = true;
    this.diffService.getEntry(v.diffEntryId).subscribe({
      next: (e) => { this.selectedEntry = e; this.loadingEntry = false; },
      error: () => { this.selectedEntry = null; this.loadingEntry = false; }
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────

  get agentOptions(): string[] {
    return Array.from(new Set(this.violations.map(v => v.agent).filter(Boolean))).sort();
  }

  severityColor(s: string | null | undefined): string {
    switch ((s || '').toLowerCase()) {
      case 'critical': return '#b1001c';
      case 'error': return '#cb2431';
      case 'warning': return '#b08800';
      case 'info': return '#0969da';
      default: return '#6b7280';
    }
  }

  severityIcon(s: string | null | undefined): string {
    switch ((s || '').toLowerCase()) {
      case 'critical': return 'gpp_bad';
      case 'error': return 'error';
      case 'warning': return 'warning';
      case 'info': return 'info';
      default: return 'help';
    }
  }

  detectorIcon(d: string | null | undefined): string {
    switch ((d || '').toLowerCase()) {
      case 'path': return 'folder_off';
      case 'rule': return 'rule';
      case 'llm': return 'smart_toy';
      default: return 'policy';
    }
  }

  getFileName(filePath: string | null | undefined): string {
    if (!filePath) return '';
    const parts = filePath.split('/');
    return parts[parts.length - 1];
  }

  getAgentIcon(agent: string): string {
    const icons: Record<string, string> = {
      'claude-code': 'smart_toy', 'codex': 'psychology', 'opencode': 'code',
      'qwen': 'auto_awesome', 'cline': 'terminal', 'cursor': 'mouse',
      'continue': 'play_arrow', 'aider': 'build', 'gemini': 'diamond',
      'kompile': 'memory', 'pi': 'circle'
    };
    return icons[agent] || 'smart_toy';
  }

  formatDate(d: string | null | undefined): string {
    if (!d) return '';
    const date = new Date(d);
    if (isNaN(date.getTime())) return d;
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  severityCount(sev: string): number {
    const by = this.stats?.['bySeverity'] as Record<string, number> | undefined;
    return by?.[sev] || 0;
  }
}
