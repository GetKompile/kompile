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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatBadgeModule } from '@angular/material/badge';
import {
  DiffIndexService,
  DiffIndexEntry,
  DiffProject,
  DiffAgent,
  DiffIndexStats,
  DiffSearchParams
} from '../../services/diff-index.service';

@Component({
  selector: 'app-diff-index-browser',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatCardModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatDividerModule,
    MatBadgeModule
  ],
  templateUrl: './diff-index-browser.component.html',
  styleUrls: ['./diff-index-browser.component.css']
})
export class DiffIndexBrowserComponent implements OnInit {
  // Search state
  searchParams: DiffSearchParams = { limit: 50 };
  searchResults: DiffIndexEntry[] = [];
  selectedEntry: DiffIndexEntry | null = null;

  // Aggregation data
  projects: DiffProject[] = [];
  agents: DiffAgent[] = [];
  stats: DiffIndexStats | null = null;

  // UI state
  activeTab = 0;
  loading = false;
  reindexing = false;

  // Filter options (populated from aggregation data)
  availableAgents: string[] = [];
  availableSources: string[] = [];

  constructor(private diffIndexService: DiffIndexService) {}

  ngOnInit(): void {
    this.loadStats();
    this.loadProjects();
    this.loadAgents();
    this.doSearch();
  }

  // ── Search ─────────────────────────────────────────────────────

  doSearch(): void {
    this.loading = true;
    this.diffIndexService.search(this.searchParams).subscribe({
      next: (results) => {
        this.searchResults = results;
        this.loading = false;
      },
      error: (err) => {
        console.error('Search failed:', err);
        this.searchResults = [];
        this.loading = false;
      }
    });
  }

  clearSearch(): void {
    this.searchParams = { limit: 50 };
    this.doSearch();
  }

  filterByAgent(agent: string): void {
    this.searchParams.agent = agent;
    this.activeTab = 0;
    this.doSearch();
  }

  filterByProject(projectDir: string): void {
    this.searchParams.projectDirectory = projectDir;
    this.activeTab = 0;
    this.doSearch();
  }

  selectEntry(entry: DiffIndexEntry): void {
    this.loading = true;
    this.diffIndexService.getEntry(entry.id).subscribe({
      next: (full) => {
        this.selectedEntry = full;
        this.loading = false;
      },
      error: () => {
        this.selectedEntry = entry;
        this.loading = false;
      }
    });
  }

  closeDetail(): void {
    this.selectedEntry = null;
  }

  // ── Aggregation ────────────────────────────────────────────────

  loadProjects(): void {
    this.diffIndexService.listProjects().subscribe({
      next: (projects) => this.projects = projects,
      error: () => this.projects = []
    });
  }

  loadAgents(): void {
    this.diffIndexService.listAgents().subscribe({
      next: (agents) => {
        this.agents = agents;
        this.availableAgents = agents.map(a => a.agent);
      },
      error: () => this.agents = []
    });
  }

  loadStats(): void {
    this.diffIndexService.getStats().subscribe({
      next: (stats) => {
        this.stats = stats;
        this.availableSources = Object.keys(stats.bySource || {});
      },
      error: () => this.stats = null
    });
  }

  // ── Reindex ────────────────────────────────────────────────────

  triggerReindex(): void {
    this.reindexing = true;
    this.diffIndexService.reindex().subscribe({
      next: () => {
        setTimeout(() => {
          this.reindexing = false;
          this.loadStats();
          this.loadProjects();
          this.loadAgents();
          this.doSearch();
        }, 3000);
      },
      error: () => this.reindexing = false
    });
  }

  // ── Helpers ────────────────────────────────────────────────────

  getAgentIcon(agent: string): string {
    switch (agent) {
      case 'claude-code': return 'smart_toy';
      case 'codex': return 'psychology';
      case 'opencode': return 'code';
      case 'qwen': return 'auto_awesome';
      case 'cline': return 'terminal';
      case 'cursor': return 'mouse';
      case 'continue': return 'play_arrow';
      case 'aider': return 'build';
      case 'gemini': return 'diamond';
      case 'kompile': return 'memory';
      case 'pi': return 'circle';
      default: return 'smart_toy';
    }
  }

  getDiffTypeIcon(diffType: string): string {
    switch (diffType) {
      case 'edit': return 'edit';
      case 'write': return 'note_add';
      case 'patch': return 'difference';
      default: return 'code';
    }
  }

  getDiffTypeColor(diffType: string): string {
    switch (diffType) {
      case 'edit': return '#ff9800';
      case 'write': return '#4caf50';
      case 'patch': return '#2196f3';
      default: return '#9e9e9e';
    }
  }

  getFileName(filePath: string): string {
    if (!filePath) return '';
    const parts = filePath.split('/');
    return parts[parts.length - 1];
  }

  getDirectory(filePath: string): string {
    if (!filePath) return '';
    const parts = filePath.split('/');
    return parts.slice(0, -1).join('/');
  }

  getProjectName(projectDir: string): string {
    if (!projectDir) return 'Unknown';
    const parts = projectDir.split('/');
    return parts[parts.length - 1];
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '';
    const date = new Date(dateStr);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }

  formatNumber(n: number): string {
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'K';
    return n.toString();
  }

  objectKeys(obj: Record<string, unknown>): string[] {
    return obj ? Object.keys(obj) : [];
  }
}
