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

export interface VerifyResponse {
  atom: string;
  verdict: 'SUPPORTED' | 'REFUTED' | 'UNKNOWN';
  confidence: number;
  band: StrengthBand;
  opinion?: OpinionDto;
  provenance?: string;
  evidence?: string[];
  computedAt?: string;
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
}

export interface ExplainResponse {
  atom: string;
  trail?: ReasoningTrailDto;
  derivation?: DerivationTreeNodeDto;
  naturalLanguageSummary?: string;
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

  assert(req: AssertRequest): Observable<AssertResponse> {
    return this.http.post<AssertResponse>(`${this.base}/assert`, req);
  }

  batchVerify(req: BatchVerifyRequest): Observable<BatchVerifyResponse> {
    return this.http.post<BatchVerifyResponse>(`${this.backendUrl}/kb/verify/batch`, req);
  }
}
