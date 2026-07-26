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

/** Mirrors ai.kompile.core.graphrag.partition.PartitionCoverageReport.SubjectCoverage. */
export interface SubjectCoverage {
  partitionId: string;
  subject: string | null;
  entityId: string | null;
  groupId: string | null;
  category: string | null;
  timeWindow: string | null;
  policyVersion: string | null;
  snapshotId: string | null;
  phase: string;
  round: number;
  frontierExhausted: boolean;
  covered: number;
  admitted: number;
  excluded: number;
  coverage: number;
  provisionallyComplete: boolean;
  completeWithGaps: boolean;
  byState: { [state: string]: number };
  byChannel: { [channel: string]: number };
  /** Chunk ids the partition still owes work on — the gap, named rather than counted. */
  outstanding: string[];
  deferred: string[];
  inaccessible: string[];
  invalidated: string[];
  pins: { [name: string]: string };
  summary: string;
}

/** Mirrors ai.kompile.core.graphrag.partition.PartitionCoverageReport. */
export interface PartitionCoverageReport {
  scope: string;
  partitions: number;
  complete: number;
  completeWithGaps: number;
  open: number;
  covered: number;
  admitted: number;
  excluded: number;
  outstanding: number;
  deferred: number;
  inaccessible: number;
  invalidated: number;
  coverage: number;
  byState: { [state: string]: number };
  byChannel: { [channel: string]: number };
  subjects: SubjectCoverage[];
}

/** Mirrors ai.kompile.core.graphrag.partition.DocumentCoverageReport.ChunkCoverage. */
export interface ChunkCoverage {
  chunkId: string;
  state: string;
  channel: string | null;
  heldBy: string[];
}

/** Mirrors ai.kompile.core.graphrag.partition.DocumentCoverageReport. */
export interface DocumentCoverageReport {
  documentId: string;
  partitions: number;
  chunks: number;
  read: number;
  admitted: number;
  excluded: number;
  outstanding: number;
  inaccessible: number;
  invalidated: number;
  coverage: number;
  byState: { [state: string]: number };
  subjects: string[];
  chunkDetail: ChunkCoverage[];
}

/**
 * Reads the durable entity-partition coverage claims (/api/graph/partitions).
 *
 * <p>Two axes on purpose: by subject ("how much has been read about Acme") and by document ("how
 * much of this filing was read, and for whom"). Neither implies the other.</p>
 */
@Injectable({ providedIn: 'root' })
export class PartitionCoverageService extends BaseService {
  private readonly apiPath = '/graph/partitions';

  constructor(private http: HttpClient) {
    super();
  }

  /** Coverage over every partition recorded for this project. */
  getCoverage(): Observable<PartitionCoverageReport> {
    return this.http.get<PartitionCoverageReport>(`${this.backendUrl}${this.apiPath}/coverage`);
  }

  /** Coverage over the partitions discovered against one fact sheet. */
  getCoverageForFactSheet(factSheetId: number): Observable<PartitionCoverageReport> {
    return this.http.get<PartitionCoverageReport>(
      `${this.backendUrl}${this.apiPath}/coverage/fact-sheet/${factSheetId}`);
  }

  /** Coverage over the partitions recorded under one discovery policy version. */
  getCoverageForPolicy(policyVersion: string): Observable<PartitionCoverageReport> {
    return this.http.get<PartitionCoverageReport>(
      `${this.backendUrl}${this.apiPath}/coverage/policy/${encodeURIComponent(policyVersion)}`);
  }

  /** What became of one document: which of its chunks were read, and for which subjects. */
  getCoverageForDocument(documentId: string): Observable<DocumentCoverageReport> {
    return this.http.get<DocumentCoverageReport>(
      `${this.backendUrl}${this.apiPath}/coverage/document`,
      { params: new HttpParams().set('documentId', documentId) });
  }

  /** One partition's claim in full, including the chunk ids it did not read. */
  getPartition(partitionId: string): Observable<SubjectCoverage> {
    return this.http.get<SubjectCoverage>(
      `${this.backendUrl}${this.apiPath}/${encodeURIComponent(partitionId)}`);
  }
}
