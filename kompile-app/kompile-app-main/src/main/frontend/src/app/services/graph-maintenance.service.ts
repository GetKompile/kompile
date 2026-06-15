/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface HealthReport {
  factSheetId: number | null;
  totalNodes: number;
  totalEdges: number;
  nodeCountsByType: Record<string, number>;
  edgeCountsByType: Record<string, number>;
  entityTypeDistribution: Record<string, number>;
  orphanEntityCount: number;
  blankTitleCount: number;
  lowConfidenceCount: number;
  danglingEdgeCount: number;
  weakEdgeCount: number;
  duplicateEdgeCount: number;
  issues: string[];
}

export interface PruneRequest {
  dryRun?: boolean;
  confidenceThreshold?: number;
  edgeWeightThreshold?: number;
}

export interface PruneResult {
  dryRun: boolean;
  factSheetId: number | null;
  nodesPruned: number;
  edgesPruned: number;
  details: PruneDetail[];
}

export interface PruneDetail {
  nodeId: string;
  title: string;
  reason: string;
  info: string;
}

export interface ValidateResult {
  dryRun: boolean;
  factSheetId: number | null;
  issuesFound: number;
  details: ValidateDetail[];
}

export interface ValidateDetail {
  id: string;
  action: string;
  description: string;
}

export interface RelabelRequest {
  dryRun?: boolean;
  fromType: string;
  toType: string;
  titlePattern?: string;
}

export interface RelabelResult {
  dryRun: boolean;
  factSheetId: number | null;
  fromType: string;
  toType: string;
  relabeledCount: number;
  details: RelabelDetail[];
}

export interface RelabelDetail {
  nodeId: string;
  title: string;
  oldType: string;
  newType: string;
}

export interface LabelCount {
  label: string;
  count: number;
}

export interface BulkDeleteRequest {
  dryRun?: boolean;
  nodeType?: string;
  entityType?: string;
  maxConfidence?: number;
  orphansOnly?: boolean;
  titlePattern?: string;
}

export interface BulkDeleteResult {
  dryRun: boolean;
  factSheetId: number | null;
  deletedCount: number;
  details: BulkDeleteDetail[];
}

export interface BulkDeleteDetail {
  nodeId: string;
  title: string;
  nodeType: string;
  entityType: string;
}

export interface EdgeCleanupRequest {
  dryRun?: boolean;
  removeDangling?: boolean;
  removeDuplicates?: boolean;
  minWeight?: number;
  edgeTypes?: string[];
}

export interface EdgeCleanupResult {
  dryRun: boolean;
  factSheetId: number | null;
  danglingRemoved: number;
  duplicatesRemoved: number;
  weakRemoved: number;
}

// ── New maintenance subsystem types ──────────────────────────────────────────

export interface TaskReport {
  task: string;
  itemsScanned: number;
  itemsAffected: number;
  itemsSkipped: number;
  warnings: string[];
  elapsed: number;
}

export interface MaintenanceReport {
  reportId: string;
  factSheetId: number;
  startedAt: string;
  completedAt: string;
  dryRun: boolean;
  taskReports: Record<string, TaskReport>;
  preSnapshot: GraphSnapshot | null;
  postSnapshot: GraphSnapshot | null;
}

export interface OrphanScanResult {
  orphanNodeIds: number[];
  totalNodes: number;
  orphanCount: number;
  orphanDetails: string[];
}

export interface Contradiction {
  sourceEntityId: number;
  targetEntityId: number;
  conflictingEdgeIds: number[];
  contradictionType: string;
  description: string;
}

export interface ProvenanceCheck {
  entityId: number;
  sourceDocIds: string[];
  validSources: number;
  invalidSources: number;
  allSourcesInvalid: boolean;
}

export interface GraphSnapshot {
  snapshotId: string;
  factSheetId: number;
  createdAt: string;
  reason: string;
  nodeCount: number;
  edgeCount: number;
  storagePath: string;
}

export interface SchedulerStatus {
  enabled: boolean;
  targetFactSheetId: number | null;
}

@Injectable({
  providedIn: 'root'
})
export class GraphMaintenanceService {
  private baseUrl = `${backendUrl.replace('/api', '')}/api/graph/maintenance`;
  private newBaseUrl = `${backendUrl.replace('/api', '')}/api/graph-maintenance`;

  constructor(private http: HttpClient) {}

  private params(factSheetId?: number | null): HttpParams {
    let params = new HttpParams();
    if (factSheetId != null) {
      params = params.set('factSheetId', factSheetId.toString());
    }
    return params;
  }

  // ── Legacy endpoints (backward compat) ─────────────────────────────────────

  // Health
  getHealthReport(factSheetId?: number | null): Observable<HealthReport> {
    return this.http.get<HealthReport>(`${this.baseUrl}/health`, { params: this.params(factSheetId) });
  }

  // Prune
  prune(factSheetId: number | null, request: PruneRequest): Observable<PruneResult> {
    return this.http.post<PruneResult>(`${this.baseUrl}/prune`, request, { params: this.params(factSheetId) });
  }

  prunePreview(factSheetId: number | null, request: PruneRequest): Observable<PruneResult> {
    return this.http.post<PruneResult>(`${this.baseUrl}/prune/preview`, request, { params: this.params(factSheetId) });
  }

  // Validate
  validate(factSheetId: number | null, dryRun: boolean): Observable<ValidateResult> {
    let params = this.params(factSheetId);
    params = params.set('dryRun', dryRun.toString());
    return this.http.post<ValidateResult>(`${this.baseUrl}/validate`, {}, { params });
  }

  validatePreview(factSheetId: number | null): Observable<ValidateResult> {
    return this.http.post<ValidateResult>(`${this.baseUrl}/validate/preview`, {}, { params: this.params(factSheetId) });
  }

  // Relabel
  relabel(factSheetId: number | null, request: RelabelRequest): Observable<RelabelResult> {
    return this.http.post<RelabelResult>(`${this.baseUrl}/relabel`, request, { params: this.params(factSheetId) });
  }

  relabelPreview(factSheetId: number | null, request: RelabelRequest): Observable<RelabelResult> {
    return this.http.post<RelabelResult>(`${this.baseUrl}/relabel/preview`, request, { params: this.params(factSheetId) });
  }

  // Labels
  getLabels(factSheetId?: number | null): Observable<LabelCount[]> {
    return this.http.get<LabelCount[]>(`${this.baseUrl}/labels`, { params: this.params(factSheetId) });
  }

  // Bulk Delete
  bulkDelete(factSheetId: number | null, request: BulkDeleteRequest): Observable<BulkDeleteResult> {
    return this.http.post<BulkDeleteResult>(`${this.baseUrl}/bulk-delete`, request, { params: this.params(factSheetId) });
  }

  bulkDeletePreview(factSheetId: number | null, request: BulkDeleteRequest): Observable<BulkDeleteResult> {
    return this.http.post<BulkDeleteResult>(`${this.baseUrl}/bulk-delete/preview`, request, { params: this.params(factSheetId) });
  }

  // Edge Cleanup
  edgeCleanup(factSheetId: number | null, request: EdgeCleanupRequest): Observable<EdgeCleanupResult> {
    return this.http.post<EdgeCleanupResult>(`${this.baseUrl}/edge-cleanup`, request, { params: this.params(factSheetId) });
  }

  edgeCleanupPreview(factSheetId: number | null, request: EdgeCleanupRequest): Observable<EdgeCleanupResult> {
    return this.http.post<EdgeCleanupResult>(`${this.baseUrl}/edge-cleanup/preview`, request, { params: this.params(factSheetId) });
  }

  // ── New maintenance subsystem endpoints ────────────────────────────────────

  // Full maintenance run
  runFullMaintenance(factSheetId: number, dryRun: boolean = false): Observable<MaintenanceReport> {
    let params = new HttpParams().set('dryRun', dryRun.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/run`, {}, { params });
  }

  // TTL sweep
  runTtlSweep(factSheetId: number, dryRun: boolean = false, ttlDays: number = 90): Observable<MaintenanceReport> {
    let params = new HttpParams()
      .set('dryRun', dryRun.toString())
      .set('ttlDays', ttlDays.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/prune/ttl`, {}, { params });
  }

  // Orphan scan + prune
  findOrphans(factSheetId: number): Observable<OrphanScanResult> {
    return this.http.get<OrphanScanResult>(`${this.newBaseUrl}/${factSheetId}/orphans`);
  }

  pruneOrphans(factSheetId: number, dryRun: boolean = false, graceDays: number = 7): Observable<MaintenanceReport> {
    let params = new HttpParams()
      .set('dryRun', dryRun.toString())
      .set('graceDays', graceDays.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/prune/orphans`, {}, { params });
  }

  // Confidence pruning
  pruneByConfidence(factSheetId: number, dryRun: boolean = false,
                    minEntityConfidence: number = 0.3, minRelConfidence: number = 0.2): Observable<MaintenanceReport> {
    let params = new HttpParams()
      .set('dryRun', dryRun.toString())
      .set('minEntityConfidence', minEntityConfidence.toString())
      .set('minRelConfidence', minRelConfidence.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/prune/confidence`, {}, { params });
  }

  // Component pruning
  pruneSmallComponents(factSheetId: number, dryRun: boolean = false,
                       minComponentSize: number = 3): Observable<MaintenanceReport> {
    let params = new HttpParams()
      .set('dryRun', dryRun.toString())
      .set('minComponentSize', minComponentSize.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/prune/components`, {}, { params });
  }

  // Contradiction detection + resolution
  detectContradictions(factSheetId: number): Observable<Contradiction[]> {
    return this.http.get<Contradiction[]>(`${this.newBaseUrl}/${factSheetId}/contradictions`);
  }

  resolveContradictions(factSheetId: number, strategy: string = 'FLAG_FOR_REVIEW',
                        dryRun: boolean = false): Observable<MaintenanceReport> {
    let params = new HttpParams()
      .set('strategy', strategy)
      .set('dryRun', dryRun.toString());
    return this.http.post<MaintenanceReport>(`${this.newBaseUrl}/${factSheetId}/resolve`, {}, { params });
  }

  // Provenance validation
  validateProvenance(factSheetId: number): Observable<ProvenanceCheck[]> {
    return this.http.get<ProvenanceCheck[]>(`${this.newBaseUrl}/${factSheetId}/provenance`);
  }

  // Snapshots
  createSnapshot(factSheetId: number, reason: string = 'manual'): Observable<GraphSnapshot> {
    let params = new HttpParams().set('reason', reason);
    return this.http.post<GraphSnapshot>(`${this.newBaseUrl}/${factSheetId}/snapshot`, {}, { params });
  }

  listSnapshots(factSheetId: number): Observable<GraphSnapshot[]> {
    return this.http.get<GraphSnapshot[]>(`${this.newBaseUrl}/${factSheetId}/snapshots`);
  }

  // History
  getMaintenanceHistory(factSheetId: number, limit: number = 20): Observable<MaintenanceReport[]> {
    let params = new HttpParams().set('limit', limit.toString());
    return this.http.get<MaintenanceReport[]>(`${this.newBaseUrl}/${factSheetId}/history`, { params });
  }

  getAllMaintenanceHistory(limit: number = 20): Observable<MaintenanceReport[]> {
    let params = new HttpParams().set('limit', limit.toString());
    return this.http.get<MaintenanceReport[]>(`${this.newBaseUrl}/history`, { params });
  }

  // Scheduler
  enableScheduler(factSheetId: number): Observable<SchedulerStatus> {
    return this.http.post<SchedulerStatus>(`${this.newBaseUrl}/${factSheetId}/scheduler/enable`, {});
  }

  disableScheduler(): Observable<SchedulerStatus> {
    return this.http.post<SchedulerStatus>(`${this.newBaseUrl}/scheduler/disable`, {});
  }

  getSchedulerStatus(): Observable<SchedulerStatus> {
    return this.http.get<SchedulerStatus>(`${this.newBaseUrl}/scheduler/status`);
  }
}
