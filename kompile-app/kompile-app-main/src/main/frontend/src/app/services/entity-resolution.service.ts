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

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';

export interface MatchCandidate {
  nodeIdA: string;
  nodeIdB: string;
  titleA: string;
  titleB: string;
  entityType: string;
  score: number;
  reasons: string[];
}

export interface CompactionPreview {
  candidateCount: number;
  threshold: number;
  candidates: MatchCandidate[];
}

export interface CompactionResult {
  originalEntityCount: number;
  finalEntityCount: number;
  entitiesMerged: number;
  edgesRedirected: number;
  componentsFound: number;
  elapsedMs: number;
  decisions: MergeDecision[];
}

export interface MergeDecision {
  canonicalNodeId: string;
  canonicalTitle: string;
  mergedNodeIds: string[];
  edgesRedirected: number;
  matchReasons: string[];
  highestScore: number;
  assemblySteps?: string[];
}

export interface MatchExplanation {
  nodeIdA: string;
  nodeIdB: string;
  wouldMerge: boolean;
  score: number;
  matchReasons: string[];
  blockers: string[];
}

export interface ResolutionConfig {
  defaultSimilarityThreshold: number;
  defaultEmbeddingThreshold: number;
  description: string;
  approach: string;
  signals: string[];
  attributeBehaviors: Record<string, string>;
  features?: Record<string, string>;
  defaultTypeHierarchy?: Record<string, string>;
  builtInCompatibleTypePairs?: string[];
}

export interface CompactionRequest {
  threshold?: number;
  factSheetId?: number;
  crossTypeMerging?: boolean;
  entityTypeCorrection?: boolean;
  crossLanguageResolution?: boolean;
  customCompatibleTypePairs?: string[];
  typeHierarchy?: Record<string, string>;
}

export interface TypeHierarchyInfo {
  defaultHierarchy: Record<string, string>;
  builtInCompatiblePairs: string[];
  effectiveCompatiblePairs: string[];
  effectiveEligibleTypes: string[];
}

export interface TypeHierarchyPreview {
  inputHierarchy: Record<string, string>;
  inputCustomPairs: string[];
  effectiveHierarchy: Record<string, string>;
  effectiveCompatiblePairs: string[];
  effectiveEligibleTypes: string[];
}

export interface DuplicateGroupMember {
  nodeId: string;
  title: string;
  entityType: string;
}

export interface DuplicateGroup {
  size: number;
  maxScore: number;
  entityType: string;
  members: DuplicateGroupMember[];
}

export interface DuplicateReport {
  duplicateGroupCount: number;
  totalDuplicateEntities: number;
  threshold: number;
  groups: DuplicateGroup[];
}

@Injectable({
  providedIn: 'root'
})
export class EntityResolutionService extends BaseService {

  private readonly apiPath = '/api/entity-resolution';

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════
  // GRAPH COMPACTION
  // ═══════════════════════════════════════════════════════════════

  compact(threshold: number = 0.85, factSheetId?: number): Observable<CompactionResult> {
    let params = new HttpParams().set('threshold', threshold.toString());
    if (factSheetId != null) {
      params = params.set('factSheetId', factSheetId.toString());
    }
    return this.http.post<CompactionResult>(`${this.backendUrl}${this.apiPath}/compact`, null, { params })
      .pipe(catchError(this.handleError));
  }

  compactAdvanced(request: CompactionRequest): Observable<CompactionResult> {
    return this.http.post<CompactionResult>(`${this.backendUrl}${this.apiPath}/compact/advanced`, request)
      .pipe(catchError(this.handleError));
  }

  previewCandidates(threshold: number = 0.85, factSheetId?: number): Observable<CompactionPreview> {
    let params = new HttpParams().set('threshold', threshold.toString());
    if (factSheetId != null) {
      params = params.set('factSheetId', factSheetId.toString());
    }
    return this.http.get<CompactionPreview>(`${this.backendUrl}${this.apiPath}/compact/preview`, { params })
      .pipe(catchError(this.handleError));
  }

  previewCandidatesAdvanced(request: CompactionRequest): Observable<CompactionPreview> {
    return this.http.post<CompactionPreview>(`${this.backendUrl}${this.apiPath}/compact/preview/advanced`, request)
      .pipe(catchError(this.handleError));
  }

  mergePair(nodeIdA: string, nodeIdB: string): Observable<CompactionResult> {
    const params = new HttpParams()
      .set('nodeIdA', nodeIdA)
      .set('nodeIdB', nodeIdB);
    return this.http.post<CompactionResult>(`${this.backendUrl}${this.apiPath}/merge`, null, { params })
      .pipe(catchError(this.handleError));
  }

  findDuplicates(threshold: number = 0.85, factSheetId?: number): Observable<DuplicateReport> {
    let params = new HttpParams().set('threshold', threshold.toString());
    if (factSheetId != null) {
      params = params.set('factSheetId', factSheetId.toString());
    }
    return this.http.get<DuplicateReport>(`${this.backendUrl}${this.apiPath}/duplicates`, { params })
      .pipe(catchError(this.handleError));
  }

  explain(nodeIdA: string, nodeIdB: string): Observable<MatchExplanation> {
    const params = new HttpParams()
      .set('nodeIdA', nodeIdA)
      .set('nodeIdB', nodeIdB);
    return this.http.get<MatchExplanation>(`${this.backendUrl}${this.apiPath}/explain`, { params })
      .pipe(catchError(this.handleError));
  }

  getConfig(): Observable<ResolutionConfig> {
    return this.http.get<ResolutionConfig>(`${this.backendUrl}${this.apiPath}/config`)
      .pipe(catchError(this.handleError));
  }

  getTypeHierarchy(): Observable<TypeHierarchyInfo> {
    return this.http.get<TypeHierarchyInfo>(`${this.backendUrl}${this.apiPath}/type-hierarchy`)
      .pipe(catchError(this.handleError));
  }

  previewTypeHierarchy(request: CompactionRequest): Observable<TypeHierarchyPreview> {
    return this.http.post<TypeHierarchyPreview>(`${this.backendUrl}${this.apiPath}/type-hierarchy/preview`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      const serverError = error.error;
      if (serverError && (serverError.error || serverError.message)) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${serverError.error || serverError.message}`;
      } else if (error.message) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      } else {
        errorMessage = `Error Code: ${error.status}\nMessage: Server error`;
      }
    }
    console.error('EntityResolutionService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
