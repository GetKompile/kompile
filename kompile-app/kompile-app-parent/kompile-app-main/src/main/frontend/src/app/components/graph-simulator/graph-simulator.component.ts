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

import { Component, EventEmitter, NgZone, OnDestroy, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { FactSheetService } from '../../services/fact-sheet.service';
import {
  GraphSimulatorService,
  SimRunSnapshot,
  SimScenario,
  SimTickReport,
  SimTruthOverlay,
  StartSimRunRequest
} from '../../services/graph-simulator.service';

/**
 * Graph Simulator ("Graph Lab") — hydrate a sandbox fact-sheet graph from a synthetic scenario,
 * run the existing reasoning cascade over it, and watch what patterns it learns in the EXISTING
 * graph visualizer, scored against the scenario's planted ground truth.
 * See docs/architecture/graph-simulator-design.md.
 */
@Component({
  selector: 'app-graph-simulator',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatTooltipModule,
    GraphVisualizerComponent
  ],
  templateUrl: './graph-simulator.component.html',
  styleUrls: ['./graph-simulator.component.css']
})
export class GraphSimulatorComponent implements OnInit, OnDestroy {

  /** Ask the host to show the sim sheet's graph (Graphs hub jumps to its Visualizer sub-tab). */
  @Output() navigateToTab = new EventEmitter<string>();

  readonly stages = ['DERIVATION', 'PRUNE_COMPACT', 'GNN_SCORING', 'ONTOLOGY_CONFORMANCE', 'HEALTH'];
  readonly bandOrder = ['ESTABLISHED', 'HIGH', 'PROBABLE', 'SPECULATIVE', 'SUPPRESSED'];

  scenarios: SimScenario[] = [];
  selectedScenario: SimScenario | null = null;
  paramValues: Record<string, number> = {};
  seed = 42;
  mode: 'ALL' | 'STEP' | 'PLAY' = 'PLAY';
  reasonEveryK = 1;
  stageEnabled: Record<string, boolean> = {};
  dryRun = false;
  starting = false;

  runs: SimRunSnapshot[] = [];
  activeRun: SimRunSnapshot | null = null;
  truth: SimTruthOverlay | null = null;
  /** nodeId → recovery status; drives the visualizer's ground-truth compare tint. */
  truthNodeMap: Record<string, string> | null = null;
  revealTruth = false;
  showViz = true;

  private eventSource: EventSource | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  constructor(private simulator: GraphSimulatorService,
              private factSheetService: FactSheetService,
              private snackBar: MatSnackBar,
              private zone: NgZone) {
    this.stages.forEach(s => this.stageEnabled[s] = true);
  }

  ngOnInit(): void {
    this.simulator.scenarios().subscribe({
      next: list => {
        this.scenarios = list;
        if (list.length > 0) {
          this.selectScenario(list[0]);
        }
      },
      error: () => this.snackBar.open('Failed to load simulator scenarios', 'Dismiss', { duration: 4000 })
    });
    this.refreshRuns();
    // Belt-and-braces refresh while a run is actively working (SSE is the primary signal).
    this.pollTimer = setInterval(() => {
      if (this.activeRun && ['HYDRATING', 'REASONING', 'SCORING'].includes(this.activeRun.status)) {
        this.refreshActiveRun();
      }
    }, 10000);
  }

  ngOnDestroy(): void {
    this.closeStream();
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    if (this.pollTimer) clearInterval(this.pollTimer);
  }

  // ── setup rail ────────────────────────────────────────────────────────────────

  selectScenario(scenario: SimScenario): void {
    this.selectedScenario = scenario;
    this.paramValues = {};
    scenario.params.forEach(p => this.paramValues[p.key] = p.defaultValue);
  }

  onScenarioSelected(id: string): void {
    const scenario = this.scenarios.find(s => s.id === id);
    if (scenario) this.selectScenario(scenario);
  }

  start(): void {
    if (!this.selectedScenario || this.starting) return;
    const enabled = this.stages.filter(s => this.stageEnabled[s]);
    const request: StartSimRunRequest = {
      scenarioId: this.selectedScenario.id,
      seed: this.seed,
      params: this.paramValues,
      mode: this.mode,
      reasonEveryK: this.mode === 'ALL' ? 0 : this.reasonEveryK,
      enabledStages: enabled.length === this.stages.length ? [] : enabled,
      dryRun: this.dryRun
    };
    this.starting = true;
    this.simulator.start(request).subscribe({
      next: run => {
        this.starting = false;
        this.attach(run);
        this.refreshRuns();
        this.snackBar.open(`Run ${run.runId} started on sandbox sheet #${run.factSheetId}`,
          'OK', { duration: 4000 });
      },
      error: err => {
        this.starting = false;
        this.snackBar.open('Start failed: ' + (err?.error?.error || err?.message || 'unknown error'),
          'Dismiss', { duration: 6000 });
      }
    });
  }

  // ── run attachment + live updates ─────────────────────────────────────────────

  attach(run: SimRunSnapshot): void {
    this.activeRun = run;
    this.truth = null;
    this.revealTruth = false;
    this.openStream(run.runId);
    this.bumpVisualizer();
  }

  refreshRuns(): void {
    this.simulator.runs().subscribe(list => this.runs = list);
  }

  refreshActiveRun(): void {
    if (!this.activeRun) return;
    const id = this.activeRun.runId;
    this.simulator.run(id).subscribe({
      next: run => {
        if (this.activeRun?.runId !== id) return;
        const scoreChanged = JSON.stringify(run.lastScore) !== JSON.stringify(this.activeRun.lastScore);
        this.activeRun = run;
        if (scoreChanged) {
          this.bumpVisualizer();
          if (this.revealTruth) this.loadTruth();
        }
      },
      error: () => { /* run may have been disposed elsewhere */ }
    });
  }

  private openStream(runId: string): void {
    this.closeStream();
    const source = new EventSource(this.simulator.streamUrl(runId));
    ['started', 'progress', 'phase_change', 'source_complete', 'decision']
      .forEach(name => source.addEventListener(name, () => this.zone.run(() => this.scheduleRefresh())));
    ['completed', 'error', 'cancelled']
      .forEach(name => source.addEventListener(name, () => this.zone.run(() => {
        this.scheduleRefresh();
        this.refreshRuns();
      })));
    source.onerror = () => { /* EventSource auto-reconnects; polling covers gaps */ };
    this.eventSource = source;
  }

  private closeStream(): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) return;
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      this.refreshActiveRun();
    }, 1200);
  }

  /** Re-create the embedded visualizer so it reloads the sim graph after a reasoning pass. */
  bumpVisualizer(): void {
    this.showViz = false;
    setTimeout(() => this.showViz = true, 50);
  }

  // ── run controls ──────────────────────────────────────────────────────────────

  step(): void {
    if (this.activeRun) this.simulator.step(this.activeRun.runId).subscribe(r => this.activeRun = r);
  }

  play(): void {
    if (this.activeRun) this.simulator.play(this.activeRun.runId).subscribe(r => this.activeRun = r);
  }

  pause(): void {
    if (this.activeRun) this.simulator.pause(this.activeRun.runId).subscribe(r => this.activeRun = r);
  }

  reasonNow(): void {
    if (this.activeRun) this.simulator.reason(this.activeRun.runId).subscribe(r => this.activeRun = r);
  }

  promote(): void {
    if (!this.activeRun) return;
    this.simulator.promote(this.activeRun.runId).subscribe(res => {
      this.snackBar.open(`Sandbox kept as fact sheet #${res.factSheetId}`, 'OK', { duration: 4000 });
      this.refreshRuns();
    });
  }

  dispose(run: SimRunSnapshot): void {
    if (!confirm(`Dispose run ${run.runId} and delete its sandbox sheet #${run.factSheetId}?`)) return;
    this.simulator.dispose(run.runId).subscribe({
      next: () => {
        if (this.activeRun?.runId === run.runId) {
          this.activeRun = null;
          this.truth = null;
          this.closeStream();
        }
        this.refreshRuns();
        this.snackBar.open('Run disposed', 'OK', { duration: 3000 });
      },
      error: err => this.snackBar.open('Dispose failed: ' + (err?.error?.error || 'unknown'),
        'Dismiss', { duration: 5000 })
    });
  }

  openInGraphsHub(): void {
    if (!this.activeRun) return;
    this.factSheetService.activateSheet(this.activeRun.factSheetId).subscribe({
      next: () => {
        this.navigateToTab.emit('visualizer');
        this.snackBar.open('Sandbox sheet activated — opening in the graph visualizer', 'OK', { duration: 3000 });
      },
      error: () => this.snackBar.open('Could not activate the sandbox sheet', 'Dismiss', { duration: 4000 })
    });
  }

  // ── ground truth ──────────────────────────────────────────────────────────────

  toggleTruth(): void {
    this.revealTruth = !this.revealTruth;
    if (this.revealTruth) this.loadTruth();
  }

  loadTruth(): void {
    if (!this.activeRun) return;
    this.simulator.groundTruth(this.activeRun.runId).subscribe(t => {
      this.truth = t;
      this.rebuildTruthNodeMap();
    });
  }

  /** Endpoint nodes of truth/hallucinated items, tinted by worst status (violation > hallucinated > missed > recovered). */
  private rebuildTruthNodeMap(): void {
    if (!this.truth) {
      this.truthNodeMap = null;
      return;
    }
    const rank: Record<string, number> = { recovered: 0, missed: 1, hallucinated: 2, violation: 3 };
    const map: Record<string, string> = {};
    const put = (nodeId: string | undefined, status: string) => {
      if (!nodeId) return;
      const current = map[nodeId];
      if (current === undefined || (rank[status] ?? -1) > (rank[current] ?? -1)) {
        map[nodeId] = status;
      }
    };
    for (const e of this.truth.edges) {
      if (e.status === 'pending' || e.status === 'avoided') continue;
      put(e.sourceNodeId, e.status);
      put(e.targetNodeId, e.status);
    }
    for (const h of this.truth.hallucinatedEdges) {
      put(h.sourceNodeId, 'hallucinated');
      put(h.targetNodeId, 'hallucinated');
    }
    this.truthNodeMap = map;
  }

  // ── template helpers ──────────────────────────────────────────────────────────

  statusClass(status: string | undefined): string {
    switch (status) {
      case 'HYDRATING': case 'REASONING': case 'SCORING': return 'status-working';
      case 'COMPLETED': return 'status-done';
      case 'ERROR': return 'status-error';
      case 'PAUSED': case 'WAITING': return 'status-paused';
      default: return 'status-idle';
    }
  }

  truthStatusIcon(status: string): string {
    switch (status) {
      case 'recovered': return 'check_circle';
      case 'missed': return 'help_outline';
      case 'violation': return 'warning';
      case 'avoided': return 'shield';
      default: return 'hourglass_empty';
    }
  }

  /** Band distribution of the latest reasoned tick, as proportional bar segments. */
  bandSegments(): { band: string; count: number; pct: number }[] {
    const report = this.lastReasonedReport();
    const bands = report?.learning?.bandCounts as Record<string, number> | undefined;
    if (!bands) return [];
    const total = Object.values(bands).reduce((a, b) => a + b, 0);
    if (total <= 0) return [];
    return this.bandOrder
      .filter(b => (bands[b] || 0) > 0)
      .map(b => ({ band: b, count: bands[b], pct: Math.round(100 * bands[b] / total) }));
  }

  lastReasonedReport(): SimTickReport | null {
    const timeline = this.activeRun?.timeline || [];
    for (let i = timeline.length - 1; i >= 0; i--) {
      if (timeline[i].reasoned) return timeline[i];
    }
    return null;
  }

  transcriptTail(): string[] {
    const t = this.activeRun?.transcript || [];
    return t.slice(Math.max(0, t.length - 14));
  }

  isWorking(): boolean {
    return !!this.activeRun && ['HYDRATING', 'REASONING', 'SCORING'].includes(this.activeRun.status);
  }
}
