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
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import {
  CrossIndexSummary,
  CrossIndexStatistics,
  IndexedDocumentResponse,
  IndexedDocumentDetail,
  IndexedPassageResponse,
  PassageStatusResponse,
  SyncRequest,
  SyncJobResponse,
  SyncJobStatusResponse,
  AutoSyncConfigRequest,
  AutoSyncConfigResponse,
  OverallIndexStatus
} from '../models/api-models';

/**
 * Service for cross-index tracking and synchronization.
 * Provides methods to check what has/hasn't been indexed across
 * keyword index, vector store, and knowledge graph.
 */
@Injectable({
  providedIn: 'root'
})
export class CrossIndexService {
  private readonly baseUrl = `${environment.apiUrl}/cross-index`;

  constructor(private http: HttpClient) {}

  // ═══════════════════════════════════════════════════════════════════════════
  // STATUS & SUMMARY
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get cross-index summary for the active fact sheet.
   */
  getCrossIndexSummary(): Observable<CrossIndexSummary> {
    return this.http.get<CrossIndexSummary>(`${this.baseUrl}/status`);
  }

  /**
   * Get cross-index summary for a specific fact sheet.
   */
  getCrossIndexSummaryForFactSheet(factSheetId: number): Observable<CrossIndexSummary> {
    return this.http.get<CrossIndexSummary>(`${this.baseUrl}/status/${factSheetId}`);
  }

  /**
   * Get detailed statistics for a fact sheet.
   */
  getStatistics(factSheetId: number): Observable<CrossIndexStatistics> {
    return this.http.get<CrossIndexStatistics>(`${this.baseUrl}/statistics/${factSheetId}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DOCUMENT QUERIES
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get paginated list of indexed documents with optional filtering.
   */
  getDocuments(
    factSheetId: number,
    offset: number = 0,
    limit: number = 20,
    status?: OverallIndexStatus,
    search?: string
  ): Observable<IndexedDocumentResponse> {
    let params = new HttpParams()
      .set('factSheetId', factSheetId.toString())
      .set('offset', offset.toString())
      .set('limit', limit.toString());

    if (status) {
      params = params.set('status', status);
    }
    if (search) {
      params = params.set('search', search);
    }

    return this.http.get<IndexedDocumentResponse>(`${this.baseUrl}/documents`, { params });
  }

  /**
   * Get detailed information for a specific document including its passages.
   */
  getDocumentDetail(documentId: number): Observable<IndexedDocumentDetail> {
    return this.http.get<IndexedDocumentDetail>(`${this.baseUrl}/documents/${documentId}`);
  }

  /**
   * Get documents that need synchronization.
   */
  getDocumentsNeedingSync(factSheetId: number): Observable<IndexedDocumentResponse> {
    return this.http.get<IndexedDocumentResponse>(`${this.baseUrl}/documents/needing-sync`, {
      params: new HttpParams().set('factSheetId', factSheetId.toString())
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PASSAGE QUERIES
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get paginated list of passages for a document.
   */
  getPassages(
    documentId: number,
    offset: number = 0,
    limit: number = 50
  ): Observable<IndexedPassageResponse> {
    const params = new HttpParams()
      .set('documentId', documentId.toString())
      .set('offset', offset.toString())
      .set('limit', limit.toString());

    return this.http.get<IndexedPassageResponse>(`${this.baseUrl}/passages`, { params });
  }

  /**
   * Check status for a list of chunk IDs.
   * Useful for checking if search results are fully indexed.
   */
  checkPassageStatus(chunkIds: string[]): Observable<PassageStatusResponse> {
    return this.http.post<PassageStatusResponse>(`${this.baseUrl}/passages/check-status`, {
      chunkIds
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SYNC OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Trigger synchronization to vector store.
   */
  syncToVectorStore(factSheetId: number): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync/vector-store`, null, {
      params: new HttpParams().set('factSheetId', factSheetId.toString())
    });
  }

  /**
   * Trigger synchronization to knowledge graph.
   */
  syncToKnowledgeGraph(factSheetId: number): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync/knowledge-graph`, null, {
      params: new HttpParams().set('factSheetId', factSheetId.toString())
    });
  }

  /**
   * Trigger full synchronization to all indexes.
   */
  syncAll(factSheetId: number): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync/all`, null, {
      params: new HttpParams().set('factSheetId', factSheetId.toString())
    });
  }

  /**
   * Start a sync operation with custom options.
   */
  startSync(request: SyncRequest): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync`, request);
  }

  /**
   * Sync specific documents to selected indexes.
   */
  syncDocuments(
    documentIds: number[],
    targets: ('VECTOR_STORE' | 'KNOWLEDGE_GRAPH')[]
  ): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync/documents`, {
      documentIds,
      targets
    });
  }

  /**
   * Sync specific passages to selected indexes.
   */
  syncPassages(
    chunkIds: string[],
    targets: ('VECTOR_STORE' | 'KNOWLEDGE_GRAPH')[]
  ): Observable<SyncJobResponse> {
    return this.http.post<SyncJobResponse>(`${this.baseUrl}/sync/passages`, {
      chunkIds,
      targets
    });
  }

  /**
   * Get status of a sync job.
   */
  getSyncJobStatus(jobId: string): Observable<SyncJobStatusResponse> {
    return this.http.get<SyncJobStatusResponse>(`${this.baseUrl}/sync/${jobId}`);
  }

  /**
   * Cancel a running sync job.
   */
  cancelSyncJob(jobId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/sync/${jobId}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // AUTO-SYNC CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get auto-sync configuration for a fact sheet.
   */
  getAutoSyncConfig(factSheetId: number): Observable<AutoSyncConfigResponse> {
    return this.http.get<AutoSyncConfigResponse>(`${this.baseUrl}/config/auto-sync`, {
      params: new HttpParams().set('factSheetId', factSheetId.toString())
    });
  }

  /**
   * Update auto-sync configuration for a fact sheet.
   */
  updateAutoSyncConfig(request: AutoSyncConfigRequest): Observable<AutoSyncConfigResponse> {
    return this.http.put<AutoSyncConfigResponse>(`${this.baseUrl}/config/auto-sync`, request);
  }

  /**
   * Enable or disable auto-sync for a fact sheet.
   */
  setAutoSyncEnabled(factSheetId: number, enabled: boolean): Observable<AutoSyncConfigResponse> {
    return this.updateAutoSyncConfig({
      factSheetId,
      enabled
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ACTIVE SYNCS & FRESHNESS
  // ═══════════════════════════════════════════════════════════════════════════

  getActiveSyncs(): Observable<any[]> {
    return this.http.get<any[]>(`${this.baseUrl}/sync/active`);
  }

  markDocumentStale(documentId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/documents/${documentId}/mark-stale`, {});
  }

  scanFreshness(factSheetId: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/fact-sheets/${factSheetId}/scan-freshness`, {});
  }
}
