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
import { BaseService } from './base.service';
import {
  EntityCategory,
  TaxonomyNode,
  EnrichmentJob,
  EnrichmentStatus,
  MassReassignRequest,
  CategoryPatch,
  MassEditResult,
  AutoLabelSuggestion,
  EnrichmentAuditEntry,
  AuditSummary,
  RevertResult
} from '../models/api-models';
import { GraphNode } from '../models/graph-models';

/**
 * Optional configuration passed to enrichment step operations.
 * All fields are optional — omit any field to use server-side defaults.
 */
export interface EnrichmentConfig {
  /** Override the maximum number of entities to process per batch. */
  batchSize?: number;
  /** Minimum confidence threshold (0–1) for keeping extracted entities. */
  minConfidence?: number;
  /** Whether to run in dry-run mode (preview changes without applying them). */
  dryRun?: boolean;
  /** Maximum number of entities to process in this run (null = unlimited). */
  limit?: number | null;
  /** Additional provider-specific key/value options. */
  [key: string]: unknown;
}

@Injectable({
  providedIn: 'root'
})
export class EnrichmentService extends BaseService {

  private readonly apiPath = '/enrichment';

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════
  // ENRICHMENT JOBS
  // ═══════════════════════════════════════════════════════════════

  startEnrichment(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/start`, config || {});
  }

  runClean(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/clean`, config || {});
  }

  runOrganize(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/organize`, config || {});
  }

  // ── Individual Step Runners ──────────────────────────────────

  runDedup(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/dedup`, config || {});
  }

  runPrune(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/prune`, config || {});
  }

  runValidate(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/validate`, config || {});
  }

  runNormalize(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/normalize`, config || {});
  }

  runDiscoverTaxonomy(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/discover-taxonomy`, config || {});
  }

  runCategorize(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/categorize`, config || {});
  }

  runGenerateProcesses(factSheetId: number, config?: EnrichmentConfig): Observable<EnrichmentJob> {
    return this.http.post<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/steps/generate-processes`, config || {});
  }

  getJob(jobId: string): Observable<EnrichmentJob> {
    return this.http.get<EnrichmentJob>(
      `${this.backendUrl}${this.apiPath}/jobs/${jobId}`);
  }

  getJobs(): Observable<EnrichmentJob[]> {
    return this.http.get<EnrichmentJob[]>(
      `${this.backendUrl}${this.apiPath}/jobs`);
  }

  cancelJob(jobId: string): Observable<void> {
    return this.http.post<void>(
      `${this.backendUrl}${this.apiPath}/jobs/${jobId}/cancel`, {});
  }

  getEnrichmentStatus(factSheetId: number): Observable<EnrichmentStatus> {
    return this.http.get<EnrichmentStatus>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/status`);
  }

  // ═══════════════════════════════════════════════════════════════
  // CUSTOM ENTITY CATEGORIES (CRUD)
  // ═══════════════════════════════════════════════════════════════

  getCategories(factSheetId: number, tree: boolean = false): Observable<EntityCategory[]> {
    const params = new HttpParams().set('tree', tree.toString());
    return this.http.get<EntityCategory[]>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories`, { params });
  }

  getCategory(factSheetId: number, categoryId: string): Observable<EntityCategory> {
    return this.http.get<EntityCategory>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}`);
  }

  createCategory(factSheetId: number, req: { label: string; description?: string; parentCategoryId?: string; color?: string }): Observable<EntityCategory> {
    return this.http.post<EntityCategory>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories`, req);
  }

  updateCategory(factSheetId: number, categoryId: string, req: { label?: string; description?: string; parentCategoryId?: string; color?: string }): Observable<EntityCategory> {
    return this.http.put<EntityCategory>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}`, req);
  }

  deleteCategory(factSheetId: number, categoryId: string): Observable<void> {
    return this.http.delete<void>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}`);
  }

  assignEntities(factSheetId: number, categoryId: string, entityNodeIds: string[]): Observable<void> {
    return this.http.post<void>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}/entities`,
      { entityNodeIds });
  }

  removeEntities(factSheetId: number, categoryId: string, entityNodeIds: string[]): Observable<void> {
    return this.http.delete<void>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}/entities`,
      { body: { entityNodeIds } });
  }

  getEntitiesInCategory(factSheetId: number, categoryId: string, offset: number = 0, limit: number = 50): Observable<GraphNode[]> {
    const params = new HttpParams().set('offset', offset.toString()).set('limit', limit.toString());
    return this.http.get<GraphNode[]>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/${categoryId}/entities`, { params });
  }

  // ═══════════════════════════════════════════════════════════════
  // MASS EDIT & AUTO-LABEL
  // ═══════════════════════════════════════════════════════════════

  massReassign(factSheetId: number, req: MassReassignRequest): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/mass-reassign`, req);
  }

  massUpdate(factSheetId: number, patches: CategoryPatch[]): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/mass-update`, { patches });
  }

  massDelete(factSheetId: number, categoryIds: string[]): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/mass-delete`, { categoryIds });
  }

  autoLabel(factSheetId: number, entityNodeIds?: string[], dryRun: boolean = true, minConfidence: number = 0.7): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/auto-label`,
      { entityNodeIds, dryRun, minConfidence });
  }

  applyAutoLabelSuggestions(factSheetId: number, suggestions: AutoLabelSuggestion[]): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/auto-label/apply`,
      { suggestions });
  }

  autoLabelViaAgent(factSheetId: number, agentName: string): Observable<MassEditResult> {
    return this.http.post<MassEditResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/categories/auto-label/agent`,
      { agentName });
  }

  getAvailableAgents(): Observable<{ name: string; description: string }[]> {
    return this.http.get<{ name: string; description: string }[]>(
      `${this.backendUrl}${this.apiPath}/agents`);
  }

  // ═══════════════════════════════════════════════════════════════
  // TAXONOMY (AUTO-DISCOVERED, READ-ONLY)
  // ═══════════════════════════════════════════════════════════════

  getTaxonomy(factSheetId: number): Observable<TaxonomyNode[]> {
    return this.http.get<TaxonomyNode[]>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/taxonomy`);
  }

  browseTaxonomy(factSheetId: number, parentId?: string): Observable<TaxonomyNode[]> {
    let params = new HttpParams();
    if (parentId) params = params.set('parentId', parentId);
    return this.http.get<TaxonomyNode[]>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/taxonomy/browse`, { params });
  }

  // ═══════════════════════════════════════════════════════════════
  // AUDIT LOG & REVERT
  // ═══════════════════════════════════════════════════════════════

  getAuditLog(factSheetId: number, jobId?: string, phase?: string, page: number = 0, size: number = 20): Observable<any> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    if (jobId) params = params.set('jobId', jobId);
    if (phase) params = params.set('phase', phase);
    return this.http.get<any>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit`, { params });
  }

  getAuditEntry(factSheetId: number, auditId: string): Observable<EnrichmentAuditEntry> {
    return this.http.get<EnrichmentAuditEntry>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit/${auditId}`);
  }

  getAuditSummary(factSheetId: number, jobId: string): Observable<AuditSummary> {
    const params = new HttpParams().set('jobId', jobId);
    return this.http.get<AuditSummary>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit/summary`, { params });
  }

  revertAction(factSheetId: number, auditId: string): Observable<RevertResult> {
    return this.http.post<RevertResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit/${auditId}/revert`, {});
  }

  revertPhase(factSheetId: number, enrichmentJobId: string, phase: string): Observable<RevertResult> {
    return this.http.post<RevertResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit/revert-phase`,
      { enrichmentJobId, phase });
  }

  revertJob(factSheetId: number, enrichmentJobId: string): Observable<RevertResult> {
    return this.http.post<RevertResult>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/audit/revert-job`,
      { enrichmentJobId });
  }

  // ═══════════════════════════════════════════════════════════════
  // TAXONOMY-AWARE SEARCH
  // ═══════════════════════════════════════════════════════════════

  searchByCategory(factSheetId: number, query?: string, categoryFilter?: string, maxResults: number = 50): Observable<GraphNode[]> {
    return this.http.post<GraphNode[]>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/search`,
      { query, categoryFilter, maxResults });
  }

  getCategoryFacets(factSheetId: number): Observable<{ [category: string]: number }> {
    return this.http.get<{ [category: string]: number }>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/search/facets`);
  }

  getEntityTypeFacets(factSheetId: number): Observable<{ [entityType: string]: number }> {
    return this.http.get<{ [entityType: string]: number }>(
      `${this.backendUrl}${this.apiPath}/${factSheetId}/search/entity-type-facets`);
  }
}
