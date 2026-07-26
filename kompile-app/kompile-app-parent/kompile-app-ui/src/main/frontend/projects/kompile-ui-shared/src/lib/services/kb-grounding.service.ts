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

export type StrengthBand = 'ESTABLISHED' | 'HIGH' | 'PROBABLE' | 'SPECULATIVE' | 'SUPPRESSED';

export function confidenceToStrengthBand(confidence: number): StrengthBand {
  if (confidence >= 0.85) return 'ESTABLISHED';
  if (confidence >= 0.70) return 'HIGH';
  if (confidence >= 0.50) return 'PROBABLE';
  if (confidence >= 0.30) return 'SPECULATIVE';
  return 'SUPPRESSED';
}

export interface OpinionDto {
  belief: number;
  disbelief: number;
  uncertainty: number;
  expectation: number;
}

export interface VerifyRequest {
  atom: string;
  factSheetId?: number | null;
}

export interface GroundingMeta {
  factSheetId: number;
  asOf: string;
  stale: boolean;
  stalenessBudgetMs: number;
  kbVersion: number;
  sessionId?: string;
}

export interface VerifyResponse {
  verdict: 'SUPPORTED' | 'REFUTED' | 'UNKNOWN';
  confidence: number;
  evidenceAtoms: string[];
  activatedRules: string[];
  derivationDepth: number;
  sourceProvenance: string[];
  calibratedConfidence: number;
  strengthBand: StrengthBand;
  meta: GroundingMeta;
}

export interface QueryRequest {
  predicate: string;
  args: string[];
  factSheetId?: number | null;
}

export interface QueryBinding {
  variable: string;
  value: string;
  confidence: number;
}

export interface QueryResponse {
  predicate: string;
  bindings: QueryBinding[][];
  totalBindings: number;
}

export interface ExplainRequest {
  atom: string;
  factSheetId?: number | null;
  depth?: number;
}

export interface ConfidenceBreakdownDto {
  pslScore?: number;
  mebnScore?: number;
  embeddingScore?: number;
  groundingScore?: number;
  fusedScore?: number;
}

export interface DerivationTreeNodeDto {
  atom: string;
  /** Human-readable display label; present when the backend resolved the atom key to an entity title. */
  title?: string;
  confidence: number;
  rule?: string;
  source?: string;
  children?: DerivationTreeNodeDto[];
}

export interface EntailmentRecordDto {
  conclusion: string;
  confidence: number;
  rule?: string;
}

export interface ReasoningTrailDto {
  targetId: string;
  question: string;
  confidence: number;
  breakdown?: ConfidenceBreakdownDto;
  derivationTree?: DerivationTreeNodeDto;
  entailments?: EntailmentRecordDto[];
  evidence?: string[];
  activatedRules?: string[];
  inferenceMode?: string;
  naturalLanguageSummary?: string;
  computedAt?: string;
  stale?: boolean;
}

export interface ExplainResponse {
  atom: string;
  verdict?: string;
  confidence?: number;
  summary?: string;
  derivation?: string;
  trail?: ReasoningTrailDto;
  naturalLanguageSummary?: string;
  meta?: GroundingMeta;
}

// Unified explain endpoint — POST /api/explain — GROUNDING | HYBRID | CAUSAL modes
export interface UnifiedExplainRequest {
  target: string;
  factSheetId?: number | null;
  depth?: number;
  mode?: 'GROUNDING' | 'HYBRID' | 'CAUSAL';
  sessionId?: string;
}

export interface UnifiedExplainResponse {
  targetId: string;
  inferenceMode: string;
  verdict?: string;
  confidence: number;
  naturalLanguageSummary?: string;
  derivationTreeJson?: string;
  evidence?: string[];
  activatedRules?: string[];
  computedAt?: string;
  trail?: ReasoningTrailDto;
}

export interface AssertRequest {
  atom: string;
  value: number;
  source?: string;
  factSheetId?: number | null;
}

export interface AssertResponse {
  atom: string;
  accepted: boolean;
  previousValue?: number;
  newValue: number;
  message?: string;
}

export interface BatchVerifyRequest {
  factSheetId?: number | null;
  atomKeys: string[];
  minConfidence?: number;
}
export interface BatchVerifyResultSummary {
  verdict: string;
  confidence: number;
  evidenceCount: number;
  evaluatedAt: string;
  calibratedConfidence?: number;
  strengthBand?: StrengthBand;
}
export interface BatchVerifyResponse {
  results: Record<string, BatchVerifyResultSummary>;
  totalCount: number;
  factSheetId: number;
  timestamp: string;
}

@Injectable({ providedIn: 'root' })
export class KbGroundingService extends BaseService {
  private readonly base = `${this.backendUrl}/kb-grounding`;

  constructor(private http: HttpClient) {
    super();
  }

  verify(req: VerifyRequest): Observable<VerifyResponse> {
    return this.http.post<VerifyResponse>(`${this.base}/verify`, req);
  }

  query(req: QueryRequest): Observable<QueryResponse> {
    return this.http.post<QueryResponse>(`${this.base}/query`, req);
  }

  explain(req: ExplainRequest): Observable<ExplainResponse> {
    return this.http.post<ExplainResponse>(`${this.base}/explain`, req);
  }

  unifiedExplain(req: UnifiedExplainRequest): Observable<UnifiedExplainResponse> {
    return this.http.post<UnifiedExplainResponse>(`${this.backendUrl}/explain`, req);
  }

  assert(req: AssertRequest): Observable<AssertResponse> {
    return this.http.post<AssertResponse>(`${this.base}/assert`, req);
  }

  batchVerify(req: BatchVerifyRequest): Observable<BatchVerifyResponse> {
    return this.http.post<BatchVerifyResponse>(`${this.backendUrl}/kb/verify/batch`, req);
  }
}
