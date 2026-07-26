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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  ChunkListResponse,
  ChunkDetail,
  SourceListResponse,
  OperationResponse,
  DuplicateAnalysisResponse,
  DeduplicationRequest,
  DeduplicationResult,
  ExportRequest,
  ExportResponse,
  DeleteBySourceRequest,
  ClearAllRequest,
  ClearTokenResponse,
  DeduplicationStrategy,
  ChunkEditDetail,
  ChunkUpdateRequest,
  ChunkUpdateResponse
} from '../models/chunk-manager.models';

/**
 * Service for chunk management operations.
 * Provides API methods for browsing, managing, exporting, and deduplicating chunks.
 */
@Injectable({
  providedIn: 'root'
})
export class ChunkManagerService extends BaseService {

  private readonly apiPath = '/chunk-manager';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Get the full API URL for chunk manager endpoints.
   */
  private getUrl(endpoint: string): string {
    return `${this.backendUrl}${this.apiPath}${endpoint}`;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LISTING & BROWSING
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * List chunks with pagination.
   */
  listChunks(offset: number = 0, limit: number = 20, sourceId?: string): Observable<ChunkListResponse> {
    let params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());

    if (sourceId) {
      params = params.set('sourceId', sourceId);
    }

    return this.http.get<ChunkListResponse>(this.getUrl('/chunks'), { params });
  }

  /**
   * Get detailed information about a single chunk.
   */
  getChunk(id: string): Observable<ChunkDetail> {
    return this.http.get<ChunkDetail>(this.getUrl(`/chunks/${encodeURIComponent(id)}`));
  }

  /**
   * List unique source documents.
   */
  listSources(): Observable<SourceListResponse> {
    return this.http.get<SourceListResponse>(this.getUrl('/sources'));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DELETE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Delete a single chunk.
   */
  deleteChunk(id: string): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(this.getUrl(`/chunks/${encodeURIComponent(id)}`));
  }

  /**
   * Delete multiple chunks.
   */
  deleteChunks(ids: string[]): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(this.getUrl('/chunks'), { body: ids });
  }

  /**
   * Delete all chunks from a specific source.
   */
  deleteBySource(sourceId: string): Observable<OperationResponse> {
    const request: DeleteBySourceRequest = { sourceId };
    return this.http.delete<OperationResponse>(this.getUrl('/chunks/by-source'), { body: request });
  }

  /**
   * Generate a confirmation token for clearing all chunks.
   */
  generateClearToken(): Observable<ClearTokenResponse> {
    return this.http.post<ClearTokenResponse>(this.getUrl('/clear-all/token'), {});
  }

  /**
   * Clear all chunks from the vector store.
   */
  clearAll(confirmationToken: string): Observable<OperationResponse> {
    const request: ClearAllRequest = { confirmationToken };
    return this.http.delete<OperationResponse>(this.getUrl('/clear-all'), { body: request });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DEDUPLICATION
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Analyze duplicates without removing them.
   */
  analyzeDuplicates(strategy: DeduplicationStrategy = 'content_hash'): Observable<DuplicateAnalysisResponse> {
    const params = new HttpParams().set('strategy', strategy);
    return this.http.get<DuplicateAnalysisResponse>(this.getUrl('/duplicates'), { params });
  }

  /**
   * Remove duplicate chunks.
   */
  deduplicate(request: DeduplicationRequest): Observable<DeduplicationResult> {
    return this.http.post<DeduplicationResult>(this.getUrl('/deduplicate'), request);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EXPORT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Export chunks to markdown format.
   */
  exportChunks(request: ExportRequest): Observable<ExportResponse> {
    return this.http.post<ExportResponse>(this.getUrl('/export'), request);
  }

  /**
   * Download chunks as a markdown file.
   */
  downloadExport(request: ExportRequest): Observable<Blob> {
    return this.http.post(this.getUrl('/export/download'), request, {
      responseType: 'blob'
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CHUNK EDITING
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get chunk details for editing with full metadata.
   */
  getChunkForEdit(id: string): Observable<ChunkEditDetail> {
    return this.http.get<ChunkEditDetail>(this.getUrl(`/chunks/${encodeURIComponent(id)}/edit`));
  }

  /**
   * Update a chunk's content and metadata.
   */
  updateChunk(id: string, request: ChunkUpdateRequest): Observable<ChunkUpdateResponse> {
    return this.http.put<ChunkUpdateResponse>(this.getUrl(`/chunks/${encodeURIComponent(id)}`), request);
  }

  /**
   * Get available semantic types.
   */
  getSemanticTypes(): Observable<{ types: string[] }> {
    return this.http.get<{ types: string[] }>(this.getUrl('/semantic-types'));
  }

  /**
   * Get available entity types.
   */
  getEntityTypes(): Observable<{ types: string[] }> {
    return this.http.get<{ types: string[] }>(this.getUrl('/entity-types'));
  }
}
