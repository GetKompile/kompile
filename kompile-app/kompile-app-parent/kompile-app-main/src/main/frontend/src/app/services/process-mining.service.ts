/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

// ─── Response shapes (mirror the kompile-process-discovery mining endpoints) ───

export interface MiningPreview {
  factSheetId: number;
  cases: number;
  activities: string[];
  variants: number;
  directlyFollowsArcs: number;
  startActivities: Record<string, number>;
  endActivities: Record<string, number>;
  processTree: string;
}

export interface CausalDependency {
  from: string;
  to: string;
  forward: number;
  reverse: number;
  dependency: number;
  chiSquare: number;
  significant: boolean;
  type: string;
}

export interface ProcessCausalModel {
  dependencies: CausalDependency[];
  pslRules: string[];
}

export interface DeclareConstraint {
  template: string;
  activityA: string;
  activityB: string | null;
  support: number;
  confidence: number;
}

export interface InferenceResult {
  /** PSL soft-truth per activity. */
  activation?: Record<string, number>;
  /** Bayesian posterior P(active) per activity. */
  posteriors?: Record<string, number>;
  priors: Record<string, number>;
  groundRules?: number;
  iterations?: number;
  converged?: boolean;
  nodes?: number;
  edges?: number;
}

export interface MiningSuggestion {
  id: string;
  name: string;
  description: string;
  confidence: number;
  discoverySource: string;
  phases: Array<{ name: string; description: string; steps: Array<{ name: string; stepType: string }> }>;
  sourceGraphNodeIds: string[];
}

export interface ConformanceResult {
  fitness: number;
  precision: number;
  simplicity: number;
  modelArcs: number;
  logArcs: number;
  perfectFit: boolean;
}

/**
 * Client for the LLM-free process-mining engine (`/api/process/mining/*`): discover a process from a
 * fact sheet's graph, inspect the intermediate artifacts, and drive the causal/PSL/Bayesian couplings.
 */
@Injectable({ providedIn: 'root' })
export class ProcessMiningService extends BaseService {

  private readonly base = `${this.backendUrl}/process/mining`;

  constructor(private http: HttpClient) {
    super();
  }

  /** Mine a sound process and persist it as a suggestion. */
  discover(factSheetId: number, noise = 0): Observable<MiningSuggestion> {
    return this.http.get<MiningSuggestion>(`${this.base}/discover`, { params: this.params(factSheetId, { noise }) });
  }

  /** Event-log stats + directly-follows arc count + process-tree text. */
  preview(factSheetId: number, noise = 0): Observable<MiningPreview> {
    return this.http.get<MiningPreview>(`${this.base}/preview`, { params: this.params(factSheetId, { noise }) });
  }

  /** Mermaid source for the directly-follows process map and the process-tree blocks. */
  mermaid(factSheetId: number, noise = 0): Observable<{ dfg: string; tree: string }> {
    return this.http.get<{ dfg: string; tree: string }>(`${this.base}/mermaid`, { params: this.params(factSheetId, { noise }) });
  }

  /** Conformance of the discovered model to the log (fitness / precision / simplicity). */
  conformance(factSheetId: number, noise = 0): Observable<ConformanceResult> {
    return this.http.get<ConformanceResult>(`${this.base}/conformance`, { params: this.params(factSheetId, { noise }) });
  }

  /** χ²-tested directly-follows dependencies (typed) plus generated PSL rules. */
  causal(factSheetId: number): Observable<ProcessCausalModel> {
    return this.http.get<ProcessCausalModel>(`${this.base}/causal`, { params: this.params(factSheetId) });
  }

  /** Declarative (Declare/MINERful) constraints of the process. */
  declareConstraints(factSheetId: number, minSupport = 0.1, minConfidence = 0.9): Observable<DeclareConstraint[]> {
    return this.http.get<DeclareConstraint[]>(`${this.base}/declare`,
      { params: this.params(factSheetId, { minSupport, minConfidence }) });
  }

  /** Live HL-MRF (PSL) inference; optionally clamp some activities active. */
  psl(factSheetId: number, evidence: string[] = []): Observable<InferenceResult> {
    return this.http.get<InferenceResult>(`${this.base}/psl`, { params: this.withEvidence(factSheetId, evidence) });
  }

  /** Exact Bayesian (noisy-OR + variable elimination) inference. */
  bayesian(factSheetId: number, evidence: string[] = []): Observable<InferenceResult> {
    return this.http.get<InferenceResult>(`${this.base}/bayesian`, { params: this.withEvidence(factSheetId, evidence) });
  }

  private params(factSheetId: number, extra: Record<string, number | string> = {}): HttpParams {
    let params = new HttpParams().set('factSheetId', String(factSheetId));
    for (const [key, value] of Object.entries(extra)) {
      params = params.set(key, String(value));
    }
    return params;
  }

  private withEvidence(factSheetId: number, evidence: string[]): HttpParams {
    let params = this.params(factSheetId);
    for (const activity of evidence) {
      if (activity) {
        params = params.append('evidence', activity);
      }
    }
    return params;
  }
}
