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

import {
  Component,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
  ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClientModule } from '@angular/common/http';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { CodeGraphService } from '../../services/code-graph.service';
import { GraphCanvasComponent } from '../graph-visualizer/graph-canvas.component';
import { D3VisualizationData, D3Node, D3Link, NodeLevel, EdgeType } from '../../models/graph-models';

export type ActiveTab = 'build' | 'search' | 'directories' | 'stats' | 'visualize' | 'analysis';

@Component({
  selector: 'app-code-graph-builder',
  standalone: true,
  imports: [CommonModule, FormsModule, HttpClientModule, GraphCanvasComponent],
  templateUrl: './code-graph-builder.component.html',
  styleUrls: ['./code-graph-builder.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CodeGraphBuilderComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  // ─── Form fields ──────────────────────────────────────────────────────────

  projectId = 'default';
  directoryPath = '';
  newDirectoryPath = '';
  newDirectoryDisplay = '';
  newDirectoryDescription = '';
  newDirectoryTags = '';
  newDirectoryInclude = '';
  newDirectoryExclude = '';
  searchQuery = '';
  searchType = '';
  searchMaxResults = 20;
  useGraphSearch = false;
  callersQuery = '';

  // ─── Unused functions filters ─────────────────────────────────────────────
  unusedVisibility = '';
  unusedLanguage = '';
  unusedIncludeTests = false;
  unusedMaxResults = 50;

  // ─── Results & data ───────────────────────────────────────────────────────

  buildResult: any = null;
  searchResults: any[] = [];
  directories: any[] = [];
  stats: any = null;
  graphStats: any = null;
  projectLanguages: string[] = [];
  unusedFunctions: any[] = [];
  loadingUnused = false;
  callersResults: any[] = [];
  loadingCallers = false;
  ensuringConnectivity = false;
  exportingGraph = false;

  // ─── Shortest path ────────────────────────────────────────────────────
  spathFrom = '';
  spathTo = '';
  spathEdgeType = '';
  spathResult: any = null;
  loadingSpath = false;

  // ─── Composite queries ─────────────────────────────────────────────────
  impactFqn = '';
  impactResult: any = null;
  loadingImpact = false;

  depsFqn = '';
  depsMaxDepth = 3;
  depsResult: any = null;
  loadingDeps = false;

  componentScope = '';
  componentResult: any = null;
  loadingComponent = false;

  dossierFqn = '';
  dossierResult: any = null;
  loadingDossier = false;

  localExportFocus = '';
  localExportFormat = 'svg';
  localExportDepth = 2;
  exportingLocal = false;

  // ─── Test coverage & code paths ───────────────────────────────────────
  testFrameworksResult: any = null;
  loadingTestFrameworks = false;
  testCoverageResult: any = null;
  loadingTestCoverage = false;
  testsForFqn = '';
  testsForResult: any = null;
  loadingTestsFor = false;
  codePathsFqn = '';
  codePathsMaxDepth = 5;
  codePathsResult: any = null;
  loadingCodePaths = false;

  // ─── Visualization ──────────────────────────────────────────────────────
  graphData: D3VisualizationData | null = null;
  filteredGraphData: D3VisualizationData | null = null;
  loadingGraph = false;
  vizMaxNodes = 200;
  vizSymbolFqn = '';
  vizFilePath = '';

  // ─── Node detail panel ─────────────────────────────────────────────────
  selectedNode: D3Node | null = null;

  // ─── Entity type filter ────────────────────────────────────────────────
  entityTypeFilters: Record<string, boolean> = {
    CLASS: true, INTERFACE: true, ENUM: true, METHOD: true,
    FUNCTION: true, FIELD: true, CONSTANT: true, MODULE: true,
    IMPORT: false, VARIABLE: true, FILE: false, PACKAGE: true, TYPE_ALIAS: true
  };
  allEntityTypes = ['CLASS', 'INTERFACE', 'ENUM', 'METHOD', 'FUNCTION',
    'FIELD', 'CONSTANT', 'MODULE', 'IMPORT', 'VARIABLE', 'FILE', 'PACKAGE', 'TYPE_ALIAS'];

  // ─── UI state ─────────────────────────────────────────────────────────────

  building = false;
  searching = false;
  loadingDirs = false;
  loadingStats = false;
  removingPath: string | null = null;
  addingDirectory = false;
  error: string | null = null;
  successMessage: string | null = null;
  activeTab: ActiveTab = 'build';

  constructor(
    private codeGraphService: CodeGraphService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadDirectories();
    this.loadStats();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ─── Tab navigation ───────────────────────────────────────────────────────

  setActiveTab(tab: ActiveTab): void {
    this.activeTab = tab;
    this.clearMessages();
    if (tab === 'directories') {
      this.loadDirectories();
    } else if (tab === 'stats') {
      this.loadStats();
    } else if (tab === 'visualize' && !this.graphData) {
      this.loadVisualization();
    }
  }

  // ─── Build ────────────────────────────────────────────────────────────────

  buildGraph(): void {
    if (!this.directoryPath.trim()) {
      this.error = 'Please enter a directory path.';
      this.cdr.markForCheck();
      return;
    }
    if (!this.projectId.trim()) {
      this.error = 'Please enter a project ID.';
      this.cdr.markForCheck();
      return;
    }

    this.building = true;
    this.buildResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .buildGraph(this.projectId.trim(), this.directoryPath.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.buildResult = result;
          this.successMessage = 'Graph built successfully.';
          this.building = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.message ?? 'Failed to build graph.';
          this.building = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Search ───────────────────────────────────────────────────────────────

  doSearch(): void {
    if (!this.searchQuery.trim()) {
      this.error = 'Please enter a search query.';
      this.cdr.markForCheck();
      return;
    }

    this.searching = true;
    this.searchResults = [];
    this.clearMessages();
    this.cdr.markForCheck();

    const obs = this.useGraphSearch
      ? this.codeGraphService.searchGraph(
          this.projectId.trim(),
          this.searchQuery.trim(),
          this.searchMaxResults
        )
      : this.codeGraphService.searchCode(
          this.projectId.trim(),
          this.searchQuery.trim(),
          this.searchType || undefined,
          this.searchMaxResults
        );

    obs.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results: any) => {
          this.searchResults = Array.isArray(results)
            ? results
            : (results?.codeEntities ?? []).concat(results?.graphNodes ?? []);
          this.searching = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.message ?? 'Search failed.';
          this.searching = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Directories ──────────────────────────────────────────────────────────

  loadDirectories(): void {
    if (!this.projectId.trim()) { return; }
    this.loadingDirs = true;
    this.cdr.markForCheck();

    this.codeGraphService
      .listDirectories(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (dirs) => {
          this.directories = dirs ?? [];
          this.loadingDirs = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load directories:', err);
          this.directories = [];
          this.loadingDirs = false;
          this.cdr.markForCheck();
        }
      });
  }

  addDirectory(): void {
    if (!this.newDirectoryPath.trim()) {
      this.error = 'Please enter a directory path to add.';
      this.cdr.markForCheck();
      return;
    }

    this.addingDirectory = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .addDirectory(
        this.projectId.trim(),
        this.newDirectoryPath.trim(),
        {
          displayName: this.newDirectoryDisplay.trim() || undefined,
          description: this.newDirectoryDescription.trim() || undefined,
          tags: this.newDirectoryTags.trim() || undefined,
          includePatterns: this.newDirectoryInclude.trim() || undefined,
          excludePatterns: this.newDirectoryExclude.trim() || undefined
        }
      )
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.successMessage = `Directory "${this.newDirectoryPath}" added.`;
          this.newDirectoryPath = '';
          this.newDirectoryDisplay = '';
          this.newDirectoryDescription = '';
          this.newDirectoryTags = '';
          this.newDirectoryInclude = '';
          this.newDirectoryExclude = '';
          this.addingDirectory = false;
          this.loadDirectories();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.message ?? 'Failed to add directory.';
          this.addingDirectory = false;
          this.cdr.markForCheck();
        }
      });
  }

  removeDirectory(path: string): void {
    this.removingPath = path;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .removeDirectory(this.projectId.trim(), path)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.successMessage = `Directory removed.`;
          this.removingPath = null;
          this.loadDirectories();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.message ?? 'Failed to remove directory.';
          this.removingPath = null;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Statistics ───────────────────────────────────────────────────────────

  loadStats(): void {
    if (!this.projectId.trim()) { return; }
    this.loadingStats = true;
    this.cdr.markForCheck();
    this.loadLanguages();

    this.codeGraphService
      .getStatistics(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (s) => {
          this.stats = s;
          this.loadingStats = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load statistics:', err);
          this.stats = null;
          this.loadingStats = false;
          this.cdr.markForCheck();
        }
      });

    this.codeGraphService
      .getGraphStatistics(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (gs) => {
          this.graphStats = gs;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.error('Failed to load graph statistics:', err);
          this.graphStats = null;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  clearMessages(): void {
    this.error = null;
    this.successMessage = null;
  }

  /** Returns the entity count entries from stats for display. */
  getEntityEntries(): { type: string; count: number }[] {
    if (!this.stats?.entityCounts) { return []; }
    return Object.entries(this.stats.entityCounts).map(([type, count]) => ({
      type,
      count: count as number
    }));
  }

  /** Returns the relation count entries from stats for display. */
  getRelationEntries(): { type: string; count: number }[] {
    if (!this.stats?.relationCounts) { return []; }
    return Object.entries(this.stats.relationCounts).map(([type, count]) => ({
      type,
      count: count as number
    }));
  }

  /** Track-by for ngFor over result arrays. */
  trackByIndex(index: number): number {
    return index;
  }

  // ─── Languages ────────────────────────────────────────────────────────

  loadLanguages(): void {
    if (!this.projectId.trim()) return;
    this.codeGraphService.getProjectLanguages(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.projectLanguages = res?.languages ?? [];
          this.cdr.markForCheck();
        },
        error: () => { this.projectLanguages = []; this.cdr.markForCheck(); }
      });
  }

  // ─── Unused functions ─────────────────────────────────────────────────

  loadUnusedFunctions(): void {
    if (!this.projectId.trim()) return;
    this.loadingUnused = true;
    this.unusedFunctions = [];
    this.cdr.markForCheck();

    const opts: any = { maxResults: this.unusedMaxResults };
    if (this.unusedVisibility) opts.visibility = this.unusedVisibility;
    if (this.unusedLanguage) opts.language = this.unusedLanguage;
    if (this.unusedIncludeTests) opts.includeTests = true;

    this.codeGraphService.getUnusedFunctions(this.projectId.trim(), opts)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.unusedFunctions = res?.results ?? [];
          this.loadingUnused = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to load unused functions.';
          this.loadingUnused = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Callers lookup ───────────────────────────────────────────────────

  lookupCallers(): void {
    if (!this.callersQuery.trim()) {
      this.error = 'Please enter a function/method name.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingCallers = true;
    this.callersResults = [];
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getCallers(this.projectId.trim(), this.callersQuery.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.callersResults = res?.callers ?? res ?? [];
          this.loadingCallers = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to look up callers.';
          this.loadingCallers = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Ensure connectivity ──────────────────────────────────────────────

  ensureConnectivity(): void {
    this.ensuringConnectivity = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.ensureConnectivity(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.successMessage = res?.message ?? 'Connectivity ensured.';
          this.ensuringConnectivity = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to ensure connectivity.';
          this.ensuringConnectivity = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Shortest path ────────────────────────────────────────────────────

  findShortestPath(): void {
    if (!this.spathFrom.trim() || !this.spathTo.trim()) {
      this.error = 'Please enter both source and target FQNs.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingSpath = true;
    this.spathResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getShortestPath(
      this.projectId.trim(),
      this.spathFrom.trim(),
      this.spathTo.trim(),
      this.spathEdgeType || undefined
    ).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => {
          this.spathResult = res;
          this.loadingSpath = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.error?.error ?? 'Shortest path query failed.';
          this.loadingSpath = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Server-side export ──────────────────────────────────────────────

  exportServerSide(format: string): void {
    this.exportingGraph = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.exportGraph(this.projectId.trim(), format, this.vizMaxNodes)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (blob: Blob) => {
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `code-graph-${this.projectId}.${format}`;
          a.click();
          URL.revokeObjectURL(url);
          this.exportingGraph = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? `Failed to export as ${format}.`;
          this.exportingGraph = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Visualization ────────────────────────────────────────────────────

  loadVisualization(): void {
    this.loadingGraph = true;
    this.graphData = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .getVisualization(this.projectId.trim(), this.vizMaxNodes)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.graphData = this.transformCodeGraph(response);
          this.applyFilters();
          this.loadingGraph = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? err?.message ?? 'Failed to load visualization.';
          this.loadingGraph = false;
          this.cdr.markForCheck();
        }
      });
  }

  loadSymbolGraph(): void {
    if (!this.vizSymbolFqn.trim()) return;
    this.loadingGraph = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .getSymbolGraph(this.projectId.trim(), this.vizSymbolFqn.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.graphData = this.transformCodeGraph(response);
          this.applyFilters();
          this.loadingGraph = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to load symbol graph.';
          this.loadingGraph = false;
          this.cdr.markForCheck();
        }
      });
  }

  loadFileGraph(): void {
    if (!this.vizFilePath.trim()) return;
    this.loadingGraph = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService
      .getFileGraph(this.projectId.trim(), this.vizFilePath.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.graphData = this.transformCodeGraph(response);
          this.applyFilters();
          this.loadingGraph = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to load file graph.';
          this.loadingGraph = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── Node selection ──────────────────────────────────────────────────

  onNodeSelected(node: D3Node | null): void {
    this.selectedNode = node;
    this.cdr.markForCheck();
  }

  closeDetail(): void {
    this.selectedNode = null;
    this.cdr.markForCheck();
  }

  onNodeDoubleClicked(node: D3Node): void {
    const fqn = node.fqn || node.id;
    if (fqn) {
      this.vizSymbolFqn = fqn;
      this.loadSymbolGraph();
    }
  }

  // ─── Entity type filtering ─────────────────────────────────────────

  onFilterChanged(): void {
    this.applyFilters();
    this.cdr.markForCheck();
  }

  toggleAllFilters(on: boolean): void {
    for (const key of this.allEntityTypes) {
      this.entityTypeFilters[key] = on;
    }
    this.applyFilters();
    this.cdr.markForCheck();
  }

  private applyFilters(): void {
    if (!this.graphData) {
      this.filteredGraphData = null;
      return;
    }
    const enabledTypes = new Set(
      Object.entries(this.entityTypeFilters)
        .filter(([, v]) => v)
        .map(([k]) => k)
    );

    const nodes = this.graphData.nodes.filter(n =>
      !n.symbolType || enabledTypes.has(n.symbolType)
    );
    const nodeIds = new Set(nodes.map(n => n.id));
    const links = this.graphData.links.filter(l => {
      const src = typeof l.source === 'string' ? l.source : (l.source as any).id;
      const tgt = typeof l.target === 'string' ? l.target : (l.target as any).id;
      return nodeIds.has(src) && nodeIds.has(tgt);
    });
    this.filteredGraphData = { nodes, links };
  }

  // ─── Composite: impact analysis ──────────────────────────────────────

  runImpactAnalysis(): void {
    if (!this.impactFqn.trim()) {
      this.error = 'Please enter a symbol FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingImpact = true;
    this.impactResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getImpactAnalysis(this.projectId.trim(), this.impactFqn.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.impactResult = res; this.loadingImpact = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Impact analysis failed.'; this.loadingImpact = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Composite: dependency tree ────────────────────────────────────────

  runDependencyTree(): void {
    if (!this.depsFqn.trim()) {
      this.error = 'Please enter a root symbol FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingDeps = true;
    this.depsResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getDependencyTree(this.projectId.trim(), this.depsFqn.trim(), this.depsMaxDepth)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.depsResult = res; this.loadingDeps = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Dependency tree failed.'; this.loadingDeps = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Composite: component map ──────────────────────────────────────────

  runComponentMap(): void {
    if (!this.componentScope.trim()) {
      this.error = 'Please enter a file path or package FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingComponent = true;
    this.componentResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getComponentMap(this.projectId.trim(), this.componentScope.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.componentResult = res; this.loadingComponent = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Component map failed.'; this.loadingComponent = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Composite: symbol dossier ─────────────────────────────────────────

  runSymbolDossier(): void {
    if (!this.dossierFqn.trim()) {
      this.error = 'Please enter a symbol FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingDossier = true;
    this.dossierResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getSymbolDossier(this.projectId.trim(), this.dossierFqn.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.dossierResult = res; this.loadingDossier = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Symbol dossier failed.'; this.loadingDossier = false; this.cdr.markForCheck(); }
      });
  }

  // ──�� Composite: localized graph export ─────────────────────────────────

  exportLocalGraph(): void {
    if (!this.localExportFocus.trim()) {
      this.error = 'Please enter a symbol FQN or file path.';
      this.cdr.markForCheck();
      return;
    }
    this.exportingLocal = true;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.exportLocalizedGraph(
      this.projectId.trim(),
      this.localExportFocus.trim(),
      this.localExportFormat,
      this.localExportDepth
    ).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (blob: Blob) => {
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          const safeName = this.localExportFocus.replace(/[^a-zA-Z0-9._-]/g, '_');
          a.download = `local-graph-${safeName}.${this.localExportFormat}`;
          a.click();
          URL.revokeObjectURL(url);
          this.exportingLocal = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? `Failed to export localized graph.`;
          this.exportingLocal = false;
          this.cdr.markForCheck();
        }
      });
  }

  // ─── SVG export ───────────────────────────��────────────────────────

  // ─── Test coverage: frameworks ──────────────────────────────────────

  loadTestFrameworks(): void {
    this.loadingTestFrameworks = true;
    this.testFrameworksResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getTestFrameworks(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.testFrameworksResult = res; this.loadingTestFrameworks = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Failed to detect test frameworks.'; this.loadingTestFrameworks = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Test coverage: report ────────────────────────────────────────────

  loadTestCoverage(): void {
    this.loadingTestCoverage = true;
    this.testCoverageResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getTestCoverage(this.projectId.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.testCoverageResult = res; this.loadingTestCoverage = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Failed to load test coverage.'; this.loadingTestCoverage = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Test coverage: tests for symbol ──────────────────────────────────

  findTestsForSymbol(): void {
    if (!this.testsForFqn.trim()) {
      this.error = 'Please enter a symbol FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingTestsFor = true;
    this.testsForResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getTestsForSymbol(this.projectId.trim(), this.testsForFqn.trim())
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.testsForResult = res; this.loadingTestsFor = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Failed to find tests.'; this.loadingTestsFor = false; this.cdr.markForCheck(); }
      });
  }

  // ─── Code paths ───────────────────────────────────────────────────────

  traceCodePaths(): void {
    if (!this.codePathsFqn.trim()) {
      this.error = 'Please enter an entry point FQN.';
      this.cdr.markForCheck();
      return;
    }
    this.loadingCodePaths = true;
    this.codePathsResult = null;
    this.clearMessages();
    this.cdr.markForCheck();

    this.codeGraphService.getCodePaths(this.projectId.trim(), this.codePathsFqn.trim(), this.codePathsMaxDepth)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (res) => { this.codePathsResult = res; this.loadingCodePaths = false; this.cdr.markForCheck(); },
        error: (err) => { this.error = err?.error?.message ?? 'Failed to trace code paths.'; this.loadingCodePaths = false; this.cdr.markForCheck(); }
      });
  }

  exportSvg(): void {
    // Canvas-based graph cannot export as SVG directly; export as PNG instead
    this.exportPng();
  }

  exportPng(): void {
    const canvasEl = document.querySelector('app-graph-canvas canvas') as HTMLCanvasElement;
    if (!canvasEl) return;
    const a = document.createElement('a');
    a.href = canvasEl.toDataURL('image/png');
    a.download = `code-graph-${this.projectId}.png`;
    a.click();
  }

  /**
   * Adapts the code-indexer graph response ({nodes, edges, metadata}) to the
   * D3VisualizationData format expected by graph-canvas.
   */
  private transformCodeGraph(response: any): D3VisualizationData {
    const CODE_NODE_TYPE_MAP: Record<string, NodeLevel> = {
      'SOURCE': 'SOURCE',
      'DOCUMENT': 'DOCUMENT',
      'SNIPPET': 'SNIPPET',
      'ENTITY': 'ENTITY',
      'CUSTOM': 'CUSTOM'
    };

    const CODE_EDGE_TYPE_MAP: Record<string, EdgeType> = {
      'hierarchical': 'HIERARCHICAL',
      'contains': 'HIERARCHICAL',
      'embedding_similarity': 'EMBEDDING_SIMILARITY',
      'shared_entity': 'SHARED_ENTITY',
      'user_defined': 'USER_DEFINED',
      'cross_source': 'CROSS_SOURCE',
      'citation': 'CITATION',
      'temporal': 'TEMPORAL',
      'extends': 'SHARED_ENTITY',
      'implements': 'SHARED_ENTITY',
      'calls': 'CROSS_SOURCE',
      'imports': 'CROSS_SOURCE',
      'depends_on': 'CROSS_SOURCE'
    };

    const nodes: D3Node[] = (response.nodes || []).map((n: any) => ({
      id: n.id || n.nodeId || n.externalId,
      type: CODE_NODE_TYPE_MAP[n.type] || CODE_NODE_TYPE_MAP[n.nodeType] || 'ENTITY' as NodeLevel,
      label: n.label || n.title || n.name || n.id,
      title: n.title || n.name,
      description: n.description || n.docComment,
      childCount: n.childCount || 0,
      edgeCount: n.edgeCount || 0,
      filePath: n.filePath,
      fqn: n.fullyQualifiedName || n.fqn,
      docComment: n.docComment,
      language: n.language,
      symbolType: n.entityType || n.symbolType,
      signature: n.signature,
      startLine: n.startLine,
      endLine: n.endLine
    }));

    const links: D3Link[] = (response.edges || response.links || []).map((e: any, i: number) => ({
      id: e.id || e.edgeId || `e${i}`,
      source: e.source || e.sourceNodeId || e.sourceId || e.fromId,
      target: e.target || e.targetNodeId || e.targetId || e.toId,
      type: CODE_EDGE_TYPE_MAP[(e.type || e.edgeType || '').toLowerCase()] || 'SHARED_ENTITY' as EdgeType,
      weight: e.weight || 1.0,
      label: e.label || e.type || e.edgeType
    }));

    return { nodes, links };
  }
}
