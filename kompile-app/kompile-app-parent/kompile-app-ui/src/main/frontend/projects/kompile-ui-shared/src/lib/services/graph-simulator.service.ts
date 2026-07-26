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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { BaseService } from './base.service';

// ── Models (mirror SimulationRunService records) ───────────────────────────────

export interface SimScenarioParam {
  key: string;
  label: string;
  type: 'int' | 'double' | string;
  defaultValue: number;
  min: number;
  max: number;
}

export interface SimScenario {
  id: string;
  name: string;
  description: string;
  plants: string[];
  params: SimScenarioParam[];
}

export interface SimFamilyScore {
  family: string;
  truthCount: number;
  recoveredCount: number;
  truePositives: number;
  precision: number;
  recall: number;
  f1: number;
}

export interface SimScore {
  families: SimFamilyScore[];
  macroF1: number;
  ece: number;
  forbiddenViolations: number;
  hallucinatedCount: number;
  unanticipatedCount: number;
  calibration: { lo: number; hi: number; count: number; meanConfidence: number; accuracy: number }[];
}

export interface SimTickReport {
  tick: number;
  nodesApplied: number;
  edgesApplied: number;
  reasoned: boolean;
  hydration: Record<string, unknown>;
  learning: { bandCounts?: Record<string, number>; [k: string]: unknown };
  score: Partial<SimScore>;
  atMs: number;
}

export interface SimInferredFact {
  atomKey: string;
  value: number;
  confidence: number;
  rules: string[];
  supports: string[];
}

export interface SimRunSnapshot {
  runId: string;
  factSheetId: number;
  scenarioId: string;
  scenarioName: string;
  seed: number;
  status: string;
  mode: string;
  paused: boolean;
  ticksApplied: number;
  totalTicks: number;
  nodesCreated: number;
  edgesCreated: number;
  reasonEveryK: number;
  enabledStages: string[];
  dryRun: boolean;
  truthSummary: {
    expectedInferredEdges: number; duplicateSets: number; communities: number;
    expectedCausalLinks: number; forbiddenCausalLinks: number; corruptedEdges: number;
    generatingRules: string[];
  };
  timeline: SimTickReport[];
  lastScore: SimScore | null;
  inferredFacts: SimInferredFact[];
  transcript: string[];
  error?: string;
  createdAtMs: number;
}

export interface SimTruthEdge {
  family: string;
  sourceKey: string;
  targetKey: string;
  relationType: string;
  why: string;
  sourceNodeId?: string;
  targetNodeId?: string;
  status: 'pending' | 'recovered' | 'missed' | 'violation' | 'avoided' | string;
}

export interface SimTruthOverlay {
  edges: SimTruthEdge[];
  duplicateSets: { key: string; nodeId?: string }[][];
  communities: { key: string; nodeId?: string }[][];
  generatingRules: { rule: string; weight: number }[];
  hallucinatedEdges: {
    sourceKey: string; targetKey: string; relationType: string;
    confidence: number; sourceNodeId?: string; targetNodeId?: string;
  }[];
}

export interface StartSimRunRequest {
  scenarioId: string;
  seed?: number;
  params?: Record<string, number>;
  mode?: 'ALL' | 'STEP' | 'PLAY';
  reasonEveryK?: number;
  enabledStages?: string[];
  confidencePruneThreshold?: number;
  dryRun?: boolean;
  name?: string;
}

/**
 * REST + SSE client for the Graph Simulator ({@code /api/graph-sim}). Live progress rides the
 * existing crawl-events SSE channel keyed by runId — see {@link #streamUrl}.
 */
@Injectable({
  providedIn: 'root'
})
export class GraphSimulatorService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  scenarios(): Observable<SimScenario[]> {
    return this.http.get<SimScenario[]>(`${this.backendUrl}/graph-sim/scenarios`);
  }

  runs(): Observable<SimRunSnapshot[]> {
    return this.http.get<SimRunSnapshot[]>(`${this.backendUrl}/graph-sim/runs`);
  }

  run(runId: string): Observable<SimRunSnapshot> {
    return this.http.get<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs/${runId}`);
  }

  start(request: StartSimRunRequest): Observable<SimRunSnapshot> {
    return this.http.post<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs`, request);
  }

  step(runId: string): Observable<SimRunSnapshot> {
    return this.http.post<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs/${runId}/step`, {});
  }

  play(runId: string): Observable<SimRunSnapshot> {
    return this.http.post<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs/${runId}/play`, {});
  }

  pause(runId: string): Observable<SimRunSnapshot> {
    return this.http.post<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs/${runId}/pause`, {});
  }

  reason(runId: string): Observable<SimRunSnapshot> {
    return this.http.post<SimRunSnapshot>(`${this.backendUrl}/graph-sim/runs/${runId}/reason`, {});
  }

  groundTruth(runId: string): Observable<SimTruthOverlay> {
    return this.http.get<SimTruthOverlay>(`${this.backendUrl}/graph-sim/runs/${runId}/ground-truth`);
  }

  dispose(runId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/graph-sim/runs/${runId}`);
  }

  promote(runId: string): Observable<{ factSheetId: number; kept: boolean }> {
    return this.http.post<{ factSheetId: number; kept: boolean }>(
      `${this.backendUrl}/graph-sim/runs/${runId}/promote`, {});
  }

  /** SSE stream for one run — the sim publishes on the crawl-events channel under its runId. */
  streamUrl(runId: string): string {
    return `${this.backendUrl}/crawl-events/stream/${runId}`;
  }
}
