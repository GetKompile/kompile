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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';

export interface AgentInfo {
  id: string;
  description: string;
  supportedContentTypes: string[];
}

export interface MergeStrategyInfo {
  name: string;
  description: string;
}

export interface ChunkInput {
  id?: string;
  text: string;
  metadata?: Record<string, any>;
}

export interface ExtractionConfig {
  entityTypes?: string[];
  relationshipTypes?: string[];
  minConfidence?: number;
  options?: Record<string, any>;
}

export interface ExtractionRequest {
  factSheetId?: number;
  chunkTexts?: ChunkInput[];
  agentIds?: string[];
  mergeStrategy?: string;
  config?: ExtractionConfig;
}

export interface AgentContribution {
  entitiesExtracted: number;
  relationsExtracted: number;
  entitiesRetained: number;
  relationsRetained: number;
  extractionTimeMs: number;
  entityTypes: string[];
  relationTypes: string[];
}

export interface ExtractedEntity {
  id: string;
  title: string;
  type: string;
  description: string;
  confidence: number;
}

export interface ExtractedRelation {
  source: string;
  target: string;
  type: string;
  description: string;
  confidence: number;
  weight: number;
}

export interface ExtractionResponse {
  totalEntities: number;
  totalRelations: number;
  totalTimeMs: number;
  strategy: string;
  contributions: Record<string, AgentContribution>;
  entities: ExtractedEntity[];
  relations: ExtractedRelation[];
}

export interface PersistenceSummary {
  entitiesCreated: number;
  entitiesSkipped: number;
  edgesCreated: number;
  edgesSkipped: number;
  errors: string[];
}

export interface ExtractAndPersistResponse {
  extraction: ExtractionResponse;
  persistence: PersistenceSummary;
  error: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class MultiAgentGraphService extends BaseService {

  private readonly apiPath = '/graph/multi-agent';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * List all registered extraction agents.
   */
  getAgents(): Observable<AgentInfo[]> {
    return this.http.get<AgentInfo[]>(`${this.backendUrl}${this.apiPath}/agents`)
      .pipe(catchError(this.handleError));
  }

  /**
   * List all available merge strategies with descriptions.
   */
  getStrategies(): Observable<MergeStrategyInfo[]> {
    return this.http.get<MergeStrategyInfo[]>(`${this.backendUrl}${this.apiPath}/strategies`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Run multi-agent extraction (in-memory only, no persistence).
   */
  extract(request: ExtractionRequest): Observable<ExtractionResponse> {
    return this.http.post<ExtractionResponse>(`${this.backendUrl}${this.apiPath}/extract`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Run multi-agent extraction and persist results to the knowledge graph.
   */
  extractAndPersist(request: ExtractionRequest): Observable<ExtractAndPersistResponse> {
    return this.http.post<ExtractAndPersistResponse>(
      `${this.backendUrl}${this.apiPath}/extract-and-persist`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      const serverError = error.error;
      if (serverError && (serverError.error || serverError.message)) {
        errorMessage = `${serverError.error || serverError.message}`;
      } else if (error.message) {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }
    console.error('MultiAgentGraphService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
