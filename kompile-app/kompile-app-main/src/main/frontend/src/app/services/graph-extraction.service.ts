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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';

/**
 * Graph extraction configuration model.
 */
export interface GraphExtractionConfig {
  // Extraction settings
  enabled?: boolean;
  batchSize?: number;
  schemaEnforcement?: string;
  entityTypes?: string[];
  relationshipTypes?: string[];
  maxEntitiesPerChunk?: number;
  maxRelationshipsPerChunk?: number;

  // Entity Extraction Model settings
  extractionModelProvider?: string;  // e.g., "openai", "anthropic", "ollama", "default"
  extractionModelName?: string;      // e.g., "gpt-4o", "claude-3-5-sonnet", "llama3.2"
  extractionTemperature?: number;    // 0.0 to 2.0, lower = more deterministic
  extractionMaxTokens?: number;      // Max tokens for extraction response
  customExtractionPrompt?: string;   // Optional custom prompt template

  // Deduplication settings
  deduplicationEnabled?: boolean;
  similarityThreshold?: number;

  // Neo4j settings
  neo4jEnabled?: boolean;
  neo4jUri?: string;
  neo4jUsername?: string;
  neo4jPassword?: string;
  neo4jDatabase?: string;
}

/**
 * Graph extraction status response.
 */
export interface GraphExtractionStatus {
  enabled: boolean;
  batchSize: number;
  schemaEnforcement: string;
  neo4jEnabled: boolean;
  neo4jConnected: boolean;
}

/**
 * Schema mode option.
 */
export interface SchemaMode {
  value: string;
  label: string;
  description: string;
}

/**
 * Model info returned from a provider.
 */
export interface ModelInfo {
  id: string;
  name: string;
  description?: string;
  contextWindow?: number;
  supportsTools?: boolean;
}

/**
 * Model provider discovered from classpath via modular LLM system.
 * Providers are dynamically discovered and expose their available models.
 */
export interface ModelProvider {
  id: string;
  name: string;
  available: boolean;
  supportsStreaming?: boolean;
  maxTokens?: number;
  priority?: number;
  supportsModelListing?: boolean;
  models?: ModelInfo[];
}

/**
 * Service for managing graph extraction configuration via REST API.
 */
@Injectable({
  providedIn: 'root'
})
export class GraphExtractionService extends BaseService {

  private readonly apiPath = '/graph-extraction';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Get current graph extraction configuration.
   */
  getConfig(): Observable<GraphExtractionConfig> {
    return this.http.get<GraphExtractionConfig>(`${this.backendUrl}${this.apiPath}/config`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update graph extraction configuration (full update).
   */
  updateConfig(config: GraphExtractionConfig): Observable<GraphExtractionConfig> {
    return this.http.put<GraphExtractionConfig>(`${this.backendUrl}${this.apiPath}/config`, config)
      .pipe(catchError(this.handleError));
  }

  /**
   * Partially update graph extraction configuration.
   */
  patchConfig(config: Partial<GraphExtractionConfig>): Observable<GraphExtractionConfig> {
    return this.http.patch<GraphExtractionConfig>(`${this.backendUrl}${this.apiPath}/config`, config)
      .pipe(catchError(this.handleError));
  }

  /**
   * Reset configuration to defaults.
   */
  resetConfig(): Observable<GraphExtractionConfig> {
    return this.http.post<GraphExtractionConfig>(`${this.backendUrl}${this.apiPath}/config/reset`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Toggle entity extraction enabled/disabled.
   */
  toggleEnabled(): Observable<GraphExtractionConfig> {
    return this.http.post<GraphExtractionConfig>(`${this.backendUrl}${this.apiPath}/config/toggle`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get current status.
   */
  getStatus(): Observable<GraphExtractionStatus> {
    return this.http.get<GraphExtractionStatus>(`${this.backendUrl}${this.apiPath}/status`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get available schema enforcement modes.
   */
  getSchemaModes(): Observable<SchemaMode[]> {
    return this.http.get<SchemaMode[]>(`${this.backendUrl}${this.apiPath}/schema-modes`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get suggested entity types.
   */
  getSuggestedEntityTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}${this.apiPath}/suggested-entity-types`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get suggested relationship types.
   */
  getSuggestedRelationshipTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}${this.apiPath}/suggested-relationship-types`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get available model providers for entity extraction.
   */
  getModelProviders(): Observable<ModelProvider[]> {
    return this.http.get<ModelProvider[]>(`${this.backendUrl}${this.apiPath}/model-providers`)
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
    console.error('GraphExtractionService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
