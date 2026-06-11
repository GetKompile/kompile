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
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import {
  ToolCallCatalogService,
  ToolCallEntry,
  ToolCallSearchResult,
  ToolCallStats,
  ToolCallFilterOptions,
  ToolCallGroupedResult
} from '../../services/tool-call-catalog.service';

@Component({
  standalone: true,
  selector: 'app-tool-call-catalog',
  templateUrl: './tool-call-catalog.component.html',
  styleUrls: ['./tool-call-catalog.component.css'],
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCardModule, MatChipsModule, MatTooltipModule,
    MatProgressSpinnerModule, MatProgressBarModule, MatTableModule,
    MatMenuModule, MatDividerModule
  ]
})
export class ToolCallCatalogComponent implements OnInit {
  // Search and filter state
  searchQuery = '';
  selectedTool: string | null = null;
  selectedCategory: string | null = null;
  selectedAgent: string | null = null;
  selectedSource: string | null = null;
  selectedSession: string | null = null;
  selectedProject: string | null = null;

  // Sort state
  sortBy = 'timestamp';
  sortDir = 'desc';

  // Results
  results: ToolCallEntry[] = [];
  totalCount = 0;
  page = 0;
  pageSize = 50;
  totalPages = 0;
  loading = false;

  // Grouped results
  groupedResult: ToolCallGroupedResult | null = null;
  groupBy: string | null = null;

  // Stats
  stats: ToolCallStats | null = null;
  statsLoading = false;

  // Filter options
  filterOptions: ToolCallFilterOptions | null = null;

  // Expanded row
  expandedId: string | null = null;

  // Indexing state
  indexing = false;
  indexResult: Record<string, any> | null = null;

  // View mode: table, cards, or grouped
  viewMode: 'table' | 'cards' | 'grouped' = 'table';

  sortOptions = [
    { value: 'timestamp', label: 'Time' },
    { value: 'toolName', label: 'Tool Name' },
    { value: 'category', label: 'Category' },
    { value: 'agent', label: 'Agent' },
    { value: 'project', label: 'Project' },
    { value: 'source', label: 'Source' }
  ];

  groupByOptions = [
    { value: 'category', label: 'Category' },
    { value: 'project', label: 'Project' },
    { value: 'agent', label: 'Agent' },
    { value: 'tool', label: 'Tool' },
    { value: 'source', label: 'Source' },
    { value: 'session', label: 'Session' }
  ];

  constructor(private catalogService: ToolCallCatalogService) {}

  ngOnInit(): void {
    this.loadFilterOptions();
    this.loadStats();
    this.doSearch();
  }

  doSearch(): void {
    if (this.viewMode === 'grouped') {
      this.doGroupedSearch();
      return;
    }
    this.loading = true;
    this.catalogService.search({
      q: this.searchQuery || undefined,
      tool: this.selectedTool || undefined,
      category: this.selectedCategory || undefined,
      agent: this.selectedAgent || undefined,
      source: this.selectedSource || undefined,
      session: this.selectedSession || undefined,
      project: this.selectedProject || undefined,
      sortBy: this.sortBy,
      sortDir: this.sortDir,
      page: this.page,
      pageSize: this.pageSize
    }).subscribe({
      next: (result: ToolCallSearchResult) => {
        this.results = result.results;
        this.totalCount = result.totalCount;
        this.totalPages = result.totalPages;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  doGroupedSearch(): void {
    this.loading = true;
    this.catalogService.grouped({
      groupBy: this.groupBy || 'category',
      q: this.searchQuery || undefined,
      tool: this.selectedTool || undefined,
      category: this.selectedCategory || undefined,
      agent: this.selectedAgent || undefined,
      source: this.selectedSource || undefined,
      session: this.selectedSession || undefined,
      project: this.selectedProject || undefined,
      sortBy: this.sortBy,
      sortDir: this.sortDir,
      limitPerGroup: 20
    }).subscribe({
      next: (result: ToolCallGroupedResult) => {
        this.groupedResult = result;
        this.totalCount = result.totalCount;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  loadStats(): void {
    this.statsLoading = true;
    this.catalogService.getStats().subscribe({
      next: (stats: ToolCallStats) => {
        this.stats = stats;
        this.statsLoading = false;
      },
      error: () => {
        this.statsLoading = false;
      }
    });
  }

  loadFilterOptions(): void {
    this.catalogService.getFilterOptions().subscribe({
      next: (options: ToolCallFilterOptions) => {
        this.filterOptions = options;
      }
    });
  }

  onSearch(): void {
    this.page = 0;
    this.doSearch();
  }

  clearFilters(): void {
    this.searchQuery = '';
    this.selectedTool = null;
    this.selectedCategory = null;
    this.selectedAgent = null;
    this.selectedSource = null;
    this.selectedSession = null;
    this.selectedProject = null;
    this.page = 0;
    this.doSearch();
  }

  setToolFilter(tool: string): void {
    this.selectedTool = this.selectedTool === tool ? null : tool;
    this.page = 0;
    this.doSearch();
  }

  setCategoryFilter(category: string): void {
    this.selectedCategory = this.selectedCategory === category ? null : category;
    this.page = 0;
    this.doSearch();
  }

  setAgentFilter(agent: string): void {
    this.selectedAgent = this.selectedAgent === agent ? null : agent;
    this.page = 0;
    this.doSearch();
  }

  setSourceFilter(source: string): void {
    this.selectedSource = this.selectedSource === source ? null : source;
    this.page = 0;
    this.doSearch();
  }

  setProjectFilter(project: string): void {
    this.selectedProject = this.selectedProject === project ? null : project;
    this.page = 0;
    this.doSearch();
  }

  onSortChange(): void {
    this.page = 0;
    this.doSearch();
  }

  toggleSortDir(): void {
    this.sortDir = this.sortDir === 'desc' ? 'asc' : 'desc';
    this.page = 0;
    this.doSearch();
  }

  setViewMode(mode: 'table' | 'cards' | 'grouped'): void {
    this.viewMode = mode;
    if (mode === 'grouped' && !this.groupBy) {
      this.groupBy = 'category';
    }
    this.page = 0;
    this.doSearch();
  }

  onGroupByChange(): void {
    this.doGroupedSearch();
  }

  toggleExpand(id: string): void {
    this.expandedId = this.expandedId === id ? null : id;
  }

  prevPage(): void {
    if (this.page > 0) {
      this.page--;
      this.doSearch();
    }
  }

  nextPage(): void {
    if (this.page < this.totalPages - 1) {
      this.page++;
      this.doSearch();
    }
  }

  formatTimestamp(ts: string): string {
    if (!ts) return '';
    try {
      const d = new Date(ts);
      return d.toLocaleString();
    } catch {
      return ts;
    }
  }

  getProjectShortName(project: string): string {
    if (!project) return '';
    const parts = project.replace(/\\/g, '/').split('/');
    return parts[parts.length - 1] || project;
  }

  getCategoryIcon(category: string): string {
    switch (category) {
      case 'filesystem': return 'folder';
      case 'shell': return 'terminal';
      case 'search': return 'search';
      case 'rag': return 'auto_stories';
      case 'agent': return 'smart_toy';
      case 'model': return 'psychology';
      case 'indexing': return 'inventory_2';
      case 'document': return 'description';
      case 'web': return 'language';
      case 'configuration': return 'settings';
      case 'notebook': return 'book';
      case 'planning': return 'checklist';
      default: return 'build';
    }
  }

  getSourceColor(source: string): string {
    switch (source) {
      case 'passthrough': return '#4caf50';
      case 'emulated-passthrough': return '#2196f3';
      case 'mcp': return '#ff9800';
      default: return '#9e9e9e';
    }
  }

  getTopToolEntries(): [string, number][] {
    if (!this.stats?.byTool) return [];
    return Object.entries(this.stats.byTool).slice(0, 8);
  }

  getTopCategoryEntries(): [string, number][] {
    if (!this.stats?.byCategory) return [];
    return Object.entries(this.stats.byCategory);
  }

  getTopProjectEntries(): [string, number][] {
    if (!this.stats?.byProject) return [];
    return Object.entries(this.stats.byProject).slice(0, 8);
  }

  getGroupKeys(): string[] {
    if (!this.groupedResult?.groups) return [];
    return Object.keys(this.groupedResult.groups);
  }

  getGroupEntries(key: string): ToolCallEntry[] {
    if (!this.groupedResult?.groups) return [];
    return this.groupedResult.groups[key] || [];
  }

  getGroupCount(key: string): number {
    if (!this.groupedResult?.groupCounts) return 0;
    return this.groupedResult.groupCounts[key] || 0;
  }

  indexTranscripts(source: string = 'all', reindex: boolean = false): void {
    this.indexing = true;
    this.indexResult = null;
    this.catalogService.indexTranscripts(source, reindex).subscribe({
      next: (result) => {
        this.indexResult = result;
        this.indexing = false;
        // Refresh data after indexing
        this.loadFilterOptions();
        this.loadStats();
        this.doSearch();
      },
      error: () => {
        this.indexing = false;
      }
    });
  }

  hasActiveFilters(): boolean {
    return !!(this.searchQuery || this.selectedTool || this.selectedCategory
      || this.selectedAgent || this.selectedSource || this.selectedSession
      || this.selectedProject);
  }
}
