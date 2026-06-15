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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';
import {
  KGEmbeddingJob,
  TrainRequest,
  EntityEmbedding,
  EmbeddingStats,
  TripleRequest,
  PredictRequest,
  ScoreResult,
  EmbeddingScore,
  AlgorithmInfo,
  Page,
  KGEmbeddingSettings,
  TrainConfig,
  GraphRAGConfig,
  Neo4jConfig,
  ConnectionTestResult,
  GraphBuildingConfig
} from '../models/kg-embedding-models';

/**
 * Service for Knowledge Graph Embeddings.
 * Provides methods for training, managing embeddings, and link prediction.
 */
@Injectable({
  providedIn: 'root'
})
export class KGEmbeddingsService {
  private readonly baseUrl = `${backendUrl}/knowledge-graph/embeddings`;

  constructor(private http: HttpClient) {}

  // ═══════════════════════════════════════════════════════════════════════════
  // TRAINING JOBS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Starts a new training job.
   */
  startTraining(request: TrainRequest): Observable<KGEmbeddingJob> {
    return this.http.post<KGEmbeddingJob>(`${this.baseUrl}/train`, request);
  }

  /**
   * Gets a training job by ID.
   */
  getJob(jobId: string): Observable<KGEmbeddingJob> {
    return this.http.get<KGEmbeddingJob>(`${this.baseUrl}/jobs/${jobId}`);
  }

  /**
   * Gets jobs for a fact sheet (paginated).
   */
  getJobs(factSheetId: number, page: number = 0, size: number = 20): Observable<Page<KGEmbeddingJob>> {
    const params = new HttpParams()
      .set('factSheetId', factSheetId.toString())
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<Page<KGEmbeddingJob>>(`${this.baseUrl}/jobs`, { params });
  }

  /**
   * Cancels a running training job.
   */
  cancelJob(jobId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/jobs/${jobId}/cancel`, {});
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EMBEDDINGS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Gets entity embeddings for a fact sheet (paginated).
   */
  getEntityEmbeddings(
    factSheetId: number,
    search?: string,
    page: number = 0,
    size: number = 50
  ): Observable<EntityEmbedding[]> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    if (search) {
      params = params.set('search', search);
    }
    return this.http.get<EntityEmbedding[]>(`${this.baseUrl}/entities/${factSheetId}`, { params });
  }

  /**
   * Gets a single entity embedding.
   */
  getEntityEmbedding(factSheetId: number, nodeId: string): Observable<EntityEmbedding> {
    return this.http.get<EntityEmbedding>(`${this.baseUrl}/entities/${factSheetId}/${nodeId}`);
  }

  /**
   * Gets embedding statistics for a fact sheet.
   */
  getStats(factSheetId: number): Observable<EmbeddingStats> {
    return this.http.get<EmbeddingStats>(`${this.baseUrl}/stats/${factSheetId}`);
  }

  /**
   * Clears all embeddings for a fact sheet.
   */
  clearEmbeddings(factSheetId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/clear/${factSheetId}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LINK PREDICTION
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Scores a triple (head, relation, tail).
   * Lower scores indicate more plausible triples.
   */
  scoreTriple(request: TripleRequest): Observable<ScoreResult> {
    return this.http.post<ScoreResult>(`${this.baseUrl}/score`, request);
  }

  /**
   * Predicts tails given head and relation.
   */
  predictTails(request: PredictRequest): Observable<EmbeddingScore[]> {
    return this.http.post<EmbeddingScore[]>(`${this.baseUrl}/predict/tails`, request);
  }

  /**
   * Predicts heads given relation and tail.
   */
  predictHeads(request: PredictRequest): Observable<EmbeddingScore[]> {
    return this.http.post<EmbeddingScore[]>(`${this.baseUrl}/predict/heads`, request);
  }

  /**
   * Predicts relations given head and tail.
   */
  predictRelations(request: PredictRequest): Observable<EmbeddingScore[]> {
    return this.http.post<EmbeddingScore[]>(`${this.baseUrl}/predict/relations`, request);
  }

  /**
   * Finds similar entities to the given entity.
   */
  findSimilarEntities(factSheetId: number, entityName: string, topK: number = 10): Observable<EmbeddingScore[]> {
    const params = new HttpParams().set('topK', topK.toString());
    return this.http.get<EmbeddingScore[]>(
      `${this.baseUrl}/similar/entities/${factSheetId}/${encodeURIComponent(entityName)}`,
      { params }
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ALGORITHMS INFO
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Gets available embedding algorithms.
   */
  getAlgorithms(): Observable<AlgorithmInfo[]> {
    return this.http.get<AlgorithmInfo[]>(`${this.baseUrl}/algorithms`);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Gets the complete KG embedding configuration.
   */
  getConfig(): Observable<KGEmbeddingSettings> {
    return this.http.get<KGEmbeddingSettings>(`${this.baseUrl}/config`);
  }

  /**
   * Updates the complete KG embedding configuration.
   */
  updateConfig(config: KGEmbeddingSettings): Observable<KGEmbeddingSettings> {
    return this.http.put<KGEmbeddingSettings>(`${this.baseUrl}/config`, config);
  }

  /**
   * Resets configuration to defaults.
   */
  resetConfig(): Observable<KGEmbeddingSettings> {
    return this.http.post<KGEmbeddingSettings>(`${this.baseUrl}/config/reset`, {});
  }

  /**
   * Gets TransE training configuration.
   */
  getTransEConfig(): Observable<TrainConfig> {
    return this.http.get<TrainConfig>(`${this.baseUrl}/config/transe`);
  }

  /**
   * Updates TransE training configuration.
   */
  updateTransEConfig(config: TrainConfig): Observable<KGEmbeddingSettings> {
    return this.http.put<KGEmbeddingSettings>(`${this.baseUrl}/config/transe`, config);
  }

  /**
   * Gets RotatE training configuration.
   */
  getRotatEConfig(): Observable<TrainConfig> {
    return this.http.get<TrainConfig>(`${this.baseUrl}/config/rotate`);
  }

  /**
   * Updates RotatE training configuration.
   */
  updateRotatEConfig(config: TrainConfig): Observable<KGEmbeddingSettings> {
    return this.http.put<KGEmbeddingSettings>(`${this.baseUrl}/config/rotate`, config);
  }

  /**
   * Gets GraphRAG configuration.
   */
  getGraphRAGConfig(): Observable<GraphRAGConfig> {
    return this.http.get<GraphRAGConfig>(`${this.baseUrl}/config/graphrag`);
  }

  /**
   * Updates GraphRAG configuration.
   */
  updateGraphRAGConfig(config: GraphRAGConfig): Observable<KGEmbeddingSettings> {
    return this.http.put<KGEmbeddingSettings>(`${this.baseUrl}/config/graphrag`, config);
  }

  /**
   * Gets Neo4j configuration.
   */
  getNeo4jConfig(): Observable<Neo4jConfig> {
    return this.http.get<Neo4jConfig>(`${this.baseUrl}/config/neo4j`);
  }

  /**
   * Updates Neo4j configuration.
   */
  updateNeo4jConfig(config: Neo4jConfig): Observable<KGEmbeddingSettings> {
    return this.http.put<KGEmbeddingSettings>(`${this.baseUrl}/config/neo4j`, config);
  }

  /**
   * Tests Neo4j connection with the provided configuration.
   */
  testNeo4jConnection(config: Neo4jConfig): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.baseUrl}/config/neo4j/test`, config);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Creates a default training request.
   */
  createDefaultTrainRequest(factSheetId: number, algorithm: string = 'TRANSE'): TrainRequest {
    const defaults = algorithm === 'ROTATE' ? {
      embeddingDim: 100,
      epochs: 100,
      learningRate: 0.001,
      batchSize: 512,
      margin: 6.0,
      negativeSamples: 256
    } : {
      embeddingDim: 100,
      epochs: 100,
      learningRate: 0.01,
      batchSize: 1024,
      margin: 1.0,
      negativeSamples: 10
    };

    return {
      factSheetId,
      algorithm,
      ...defaults
    };
  }

  /**
   * Creates a predict request.
   */
  createPredictRequest(
    factSheetId: number,
    head?: string,
    relation?: string,
    tail?: string,
    topK: number = 10
  ): PredictRequest {
    return { factSheetId, head, relation, tail, topK };
  }

  /**
   * Polls job status until completion or failure.
   * Returns an observable that emits job updates.
   */
  pollJobStatus(jobId: string, intervalMs: number = 2000): Observable<KGEmbeddingJob> {
    return new Observable(observer => {
      const poll = () => {
        this.getJob(jobId).subscribe({
          next: job => {
            observer.next(job);
            if (job.status === 'COMPLETED' || job.status === 'FAILED' || job.status === 'CANCELLED') {
              observer.complete();
            } else {
              setTimeout(poll, intervalMs);
            }
          },
          error: err => observer.error(err)
        });
      };
      poll();
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // GRAPH BUILDING CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Gets graph building configuration for a fact sheet.
   */
  getGraphBuildingConfig(factSheetId: number): Observable<GraphBuildingConfig> {
    return this.http.get<GraphBuildingConfig>(`${this.baseUrl}/config/graph-building/${factSheetId}`);
  }

  /**
   * Updates graph building configuration for a fact sheet.
   */
  updateGraphBuildingConfig(factSheetId: number, config: GraphBuildingConfig): Observable<GraphBuildingConfig> {
    return this.http.put<GraphBuildingConfig>(`${this.baseUrl}/config/graph-building/${factSheetId}`, config);
  }

  /**
   * Gets available LLM providers for graph extraction.
   */
  getLlmProviders(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/config/llm-providers`);
  }

  /**
   * Gets available models for a specific LLM provider.
   */
  getLlmModels(provider: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/config/llm-models/${provider}`);
  }
}
