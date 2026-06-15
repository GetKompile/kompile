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
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  GraphBuilderInfo,
  ExtractionJob,
  StartJobRequest,
  JobStatistics,
  ExtractionLog,
  TripleProposal,
  ManualProposalRequest,
  RejectRequest,
  BulkActionRequest,
  BulkRejectRequest,
  Page,
  ProposalStatus
} from '../models/graph-builder-models';

@Injectable({
  providedIn: 'root'
})
export class KnowledgeGraphBuilderService extends BaseService {

  private readonly apiPath = '/knowledge-graph/builder';

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BUILDER DISCOVERY
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all available graph builders
   */
  getBuilders(): Observable<GraphBuilderInfo[]> {
    return this.http.get<GraphBuilderInfo[]>(`${this.backendUrl}${this.apiPath}/builders`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific builder by ID
   */
  getBuilder(builderId: string): Observable<GraphBuilderInfo> {
    return this.http.get<GraphBuilderInfo>(`${this.backendUrl}${this.apiPath}/builders/${builderId}`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JOB MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Start a new extraction job
   */
  startJob(request: StartJobRequest): Observable<ExtractionJob> {
    return this.http.post<ExtractionJob>(`${this.backendUrl}${this.apiPath}/jobs`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get job status
   */
  getJob(jobId: string): Observable<ExtractionJob> {
    return this.http.get<ExtractionJob>(`${this.backendUrl}${this.apiPath}/jobs/${jobId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get jobs for a fact sheet
   */
  getJobs(factSheetId: number, page: number = 0, size: number = 20): Observable<Page<ExtractionJob>> {
    const params = new HttpParams()
      .set('factSheetId', factSheetId.toString())
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<Page<ExtractionJob>>(`${this.backendUrl}${this.apiPath}/jobs`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel a running job
   */
  cancelJob(jobId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}${this.apiPath}/jobs/${jobId}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get job statistics
   */
  getJobStatistics(jobId: string): Observable<JobStatistics> {
    return this.http.get<JobStatistics>(`${this.backendUrl}${this.apiPath}/jobs/${jobId}/statistics`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EXTRACTION LOGS (Full LLM Transparency)
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get extraction logs for a job (with full prompts and responses)
   */
  getJobLogs(jobId: string, page: number = 0, size: number = 50): Observable<Page<ExtractionLog>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<Page<ExtractionLog>>(`${this.backendUrl}${this.apiPath}/jobs/${jobId}/logs`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get extraction log for a specific chunk
   */
  getLogForChunk(jobId: string, chunkId: string): Observable<ExtractionLog> {
    return this.http.get<ExtractionLog>(`${this.backendUrl}${this.apiPath}/jobs/${jobId}/logs/${chunkId}`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PROPOSALS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get proposals with flexible filtering
   */
  getProposals(
    options: {
      jobId?: string;
      factSheetId?: number;
      status?: ProposalStatus;
      query?: string;
      page?: number;
      size?: number;
    }
  ): Observable<Page<TripleProposal>> {
    let params = new HttpParams()
      .set('page', (options.page || 0).toString())
      .set('size', (options.size || 50).toString());

    if (options.jobId) {
      params = params.set('jobId', options.jobId);
    }
    if (options.factSheetId) {
      params = params.set('factSheetId', options.factSheetId.toString());
    }
    if (options.status) {
      params = params.set('status', options.status);
    }
    if (options.query) {
      params = params.set('query', options.query);
    }

    return this.http.get<Page<TripleProposal>>(`${this.backendUrl}${this.apiPath}/proposals`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific proposal
   */
  getProposal(proposalId: string): Observable<TripleProposal> {
    return this.http.get<TripleProposal>(`${this.backendUrl}${this.apiPath}/proposals/${proposalId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Accept a proposal (creates nodes and edges in the graph)
   * @param proposalId the proposal to accept
   * @param reviewedBy who reviewed this proposal
   * @param storageType optional storage type ("jpa", "neo4j"), uses FactSheet setting if not specified
   */
  acceptProposal(proposalId: string, reviewedBy: string = 'user', storageType?: string): Observable<any> {
    let params = new HttpParams().set('reviewedBy', reviewedBy);
    if (storageType) {
      params = params.set('storageType', storageType);
    }
    return this.http.post<any>(`${this.backendUrl}${this.apiPath}/proposals/${proposalId}/accept`, {}, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Reject a proposal
   */
  rejectProposal(proposalId: string, request?: RejectRequest): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}${this.apiPath}/proposals/${proposalId}/reject`, request || {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Bulk accept proposals
   * @param proposalIds the proposals to accept
   * @param reviewedBy who reviewed these proposals
   * @param storageType optional storage type ("jpa", "neo4j"), uses FactSheet setting if not specified
   */
  bulkAcceptProposals(proposalIds: string[], reviewedBy: string = 'user', storageType?: string): Observable<any> {
    const request: BulkActionRequest = { proposalIds, reviewedBy, storageType };
    return this.http.post<any>(`${this.backendUrl}${this.apiPath}/proposals/bulk-accept`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Bulk reject proposals
   */
  bulkRejectProposals(proposalIds: string[], reviewedBy: string = 'user', reason?: string): Observable<any> {
    const request: BulkRejectRequest = { proposalIds, reviewedBy, reason };
    return this.http.post<any>(`${this.backendUrl}${this.apiPath}/proposals/bulk-reject`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a manual proposal
   */
  createManualProposal(request: ManualProposalRequest): Observable<TripleProposal> {
    return this.http.post<TripleProposal>(`${this.backendUrl}${this.apiPath}/proposals/manual`, request)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPER METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get pending proposals count for a fact sheet
   */
  getPendingProposalsCount(factSheetId: number): Observable<number> {
    return new Observable(observer => {
      this.getProposals({ factSheetId, status: 'PENDING', page: 0, size: 1 }).subscribe({
        next: (page) => {
          observer.next(page.totalElements);
          observer.complete();
        },
        error: (err) => observer.error(err)
      });
    });
  }

  /**
   * Accept all pending proposals for a job
   * @param jobId the job ID
   * @param reviewedBy who reviewed these proposals
   * @param storageType optional storage type, uses FactSheet setting if not specified
   */
  acceptAllPending(jobId: string, reviewedBy: string = 'user', storageType?: string): Observable<any> {
    return new Observable(observer => {
      this.getProposals({ jobId, status: 'PENDING', page: 0, size: 1000 }).subscribe({
        next: (page) => {
          const proposalIds = page.content.map(p => p.proposalId);
          if (proposalIds.length === 0) {
            observer.next({ accepted: 0, total: 0 });
            observer.complete();
            return;
          }
          this.bulkAcceptProposals(proposalIds, reviewedBy, storageType).subscribe({
            next: (result) => {
              observer.next(result);
              observer.complete();
            },
            error: (err) => observer.error(err)
          });
        },
        error: (err) => observer.error(err)
      });
    });
  }

  /**
   * Reject all pending proposals for a job
   */
  rejectAllPending(jobId: string, reviewedBy: string = 'user', reason?: string): Observable<any> {
    return new Observable(observer => {
      this.getProposals({ jobId, status: 'PENDING', page: 0, size: 1000 }).subscribe({
        next: (page) => {
          const proposalIds = page.content.map(p => p.proposalId);
          if (proposalIds.length === 0) {
            observer.next({ rejected: 0, total: 0 });
            observer.complete();
            return;
          }
          this.bulkRejectProposals(proposalIds, reviewedBy, reason).subscribe({
            next: (result) => {
              observer.next(result);
              observer.complete();
            },
            error: (err) => observer.error(err)
          });
        },
        error: (err) => observer.error(err)
      });
    });
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
    console.error('KnowledgeGraphBuilderService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
