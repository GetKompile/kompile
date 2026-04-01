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

import { Component, OnInit, OnDestroy, Input, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { KnowledgeGraphBuilderService } from '../../services/knowledge-graph-builder.service';
import {
  GraphBuilderInfo,
  ExtractionJob,
  TripleProposal,
  ExtractionLog,
  StartJobRequest,
  BuilderConfig,
  ProposalStatus,
  JobStatus,
  Page,
  PROPOSAL_STATUS_COLORS,
  JOB_STATUS_COLORS,
  BUILDER_TYPE_ICONS,
  DEFAULT_ENTITY_TYPES,
  ParsedEntity,
  ParsedRelationship
} from '../../models/graph-builder-models';

@Component({
  selector: 'app-knowledge-graph-builder',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './knowledge-graph-builder.component.html',
  styleUrls: ['./knowledge-graph-builder.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class KnowledgeGraphBuilderComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  @Input() factSheetId!: number;

  // Available builders
  builders: GraphBuilderInfo[] = [];
  selectedBuilderId: string | null = null;
  selectedBuilder: GraphBuilderInfo | null = null;

  // Builder configuration
  builderConfig: BuilderConfig = {
    entityTypes: [...DEFAULT_ENTITY_TYPES],
    temperature: 0.0,
    minConfidence: 0.6
  };

  // Jobs
  jobs: ExtractionJob[] = [];
  selectedJob: ExtractionJob | null = null;

  // Proposals
  proposals: TripleProposal[] = [];
  selectedProposals: Set<string> = new Set();
  proposalStatusFilter: ProposalStatus | 'ALL' = 'ALL';
  proposalSearchQuery = '';
  proposalsPage = 0;
  proposalsPageSize = 20;
  proposalsTotalElements = 0;

  // Extraction logs
  extractionLogs: ExtractionLog[] = [];
  selectedLog: ExtractionLog | null = null;
  logsPage = 0;
  logsPageSize = 20;
  logsTotalElements = 0;

  // UI State
  loading = false;
  error: string | null = null;
  viewMode: 'overview' | 'jobs' | 'proposals' | 'logs' | 'config' = 'overview';
  showConfigDialog = false;
  autoRefresh = true;
  refreshIntervalSeconds = 5;

  // Confirmation dialogs
  showBulkActionDialog = false;
  bulkActionType: 'accept' | 'reject' = 'accept';
  bulkRejectReason = '';

  // Manual proposal form
  showManualProposalForm = false;
  manualProposal = {
    subjectName: '',
    subjectType: 'CONCEPT',
    predicateName: '',
    objectName: '',
    objectType: 'CONCEPT',
    description: '',
    autoAccept: false
  };

  constructor(
    private builderService: KnowledgeGraphBuilderService,
    private cdr: ChangeDetectorRef
  ) { }

  ngOnInit(): void {
    this.loadBuilders();
    this.loadJobs();

    if (this.autoRefresh) {
      this.startAutoRefresh();
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DATA LOADING
  // ═══════════════════════════════════════════════════════════════════════════

  loadBuilders(): void {
    this.builderService.getBuilders().subscribe({
      next: (builders) => {
        this.builders = builders;
        if (builders.length > 0 && !this.selectedBuilderId) {
          this.selectBuilder(builders[0].id);
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Failed to load builders';
        console.error(err);
        this.cdr.markForCheck();
      }
    });
  }

  loadJobs(): void {
    if (!this.factSheetId) return;

    this.builderService.getJobs(this.factSheetId).subscribe({
      next: (page) => {
        this.jobs = page.content;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load jobs:', err);
        this.cdr.markForCheck();
      }
    });
  }

  loadProposals(): void {
    if (!this.factSheetId) return;

    const options: any = {
      factSheetId: this.factSheetId,
      page: this.proposalsPage,
      size: this.proposalsPageSize
    };

    if (this.proposalStatusFilter !== 'ALL') {
      options.status = this.proposalStatusFilter;
    }
    if (this.proposalSearchQuery) {
      options.query = this.proposalSearchQuery;
    }
    if (this.selectedJob) {
      options.jobId = this.selectedJob.jobId;
    }

    this.builderService.getProposals(options).subscribe({
      next: (page) => {
        this.proposals = page.content;
        this.proposalsTotalElements = page.totalElements;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load proposals:', err);
        this.cdr.markForCheck();
      }
    });
  }

  loadExtractionLogs(): void {
    if (!this.selectedJob) return;

    this.builderService.getJobLogs(this.selectedJob.jobId, this.logsPage, this.logsPageSize).subscribe({
      next: (page) => {
        this.extractionLogs = page.content;
        this.logsTotalElements = page.totalElements;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load extraction logs:', err);
        this.cdr.markForCheck();
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // BUILDER SELECTION & CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════

  selectBuilder(builderId: string): void {
    this.selectedBuilderId = builderId;
    this.selectedBuilder = this.builders.find(b => b.id === builderId) || null;
    this.cdr.markForCheck();
  }

  openConfigDialog(): void {
    this.showConfigDialog = true;
    this.cdr.markForCheck();
  }

  closeConfigDialog(): void {
    this.showConfigDialog = false;
    this.cdr.markForCheck();
  }

  saveConfig(): void {
    this.closeConfigDialog();
  }

  addEntityType(type: string): void {
    if (type && !this.builderConfig.entityTypes?.includes(type)) {
      this.builderConfig.entityTypes = [...(this.builderConfig.entityTypes || []), type];
      this.cdr.markForCheck();
    }
  }

  removeEntityType(type: string): void {
    this.builderConfig.entityTypes = (this.builderConfig.entityTypes || []).filter(t => t !== type);
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JOB MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  startJob(): void {
    if (!this.selectedBuilderId || !this.factSheetId) return;

    this.loading = true;
    this.error = null;
    this.cdr.markForCheck();

    const request: StartJobRequest = {
      factSheetId: this.factSheetId,
      builderType: this.selectedBuilderId,
      config: this.builderConfig
    };

    this.builderService.startJob(request).subscribe({
      next: (job) => {
        this.jobs = [job, ...this.jobs];
        this.selectedJob = job;
        this.loading = false;
        this.viewMode = 'jobs';
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = 'Failed to start extraction job';
        this.loading = false;
        console.error(err);
        this.cdr.markForCheck();
      }
    });
  }

  selectJob(job: ExtractionJob): void {
    this.selectedJob = job;
    this.loadProposals();
    if (job.builderType?.toLowerCase().includes('llm')) {
      this.loadExtractionLogs();
    }
    this.cdr.markForCheck();
  }

  cancelJob(job: ExtractionJob): void {
    this.builderService.cancelJob(job.jobId).subscribe({
      next: () => {
        job.status = 'CANCELLED';
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to cancel job:', err);
      }
    });
  }

  refreshJobStatus(job: ExtractionJob): void {
    this.builderService.getJob(job.jobId).subscribe({
      next: (updated) => {
        const index = this.jobs.findIndex(j => j.jobId === updated.jobId);
        if (index >= 0) {
          this.jobs[index] = updated;
        }
        if (this.selectedJob?.jobId === updated.jobId) {
          this.selectedJob = updated;
        }
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to refresh job status:', err);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PROPOSAL ACTIONS
  // ═══════════════════════════════════════════════════════════════════════════

  toggleProposalSelection(proposalId: string): void {
    if (this.selectedProposals.has(proposalId)) {
      this.selectedProposals.delete(proposalId);
    } else {
      this.selectedProposals.add(proposalId);
    }
    this.cdr.markForCheck();
  }

  selectAllProposals(): void {
    this.proposals.forEach(p => this.selectedProposals.add(p.proposalId));
    this.cdr.markForCheck();
  }

  deselectAllProposals(): void {
    this.selectedProposals.clear();
    this.cdr.markForCheck();
  }

  acceptProposal(proposal: TripleProposal): void {
    this.builderService.acceptProposal(proposal.proposalId).subscribe({
      next: (result) => {
        proposal.status = 'ACCEPTED';
        proposal.subjectNodeId = result.subjectNodeId;
        proposal.objectNodeId = result.objectNodeId;
        proposal.edgeId = result.edgeId;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to accept proposal:', err);
      }
    });
  }

  rejectProposal(proposal: TripleProposal, reason?: string): void {
    this.builderService.rejectProposal(proposal.proposalId, { reason }).subscribe({
      next: () => {
        proposal.status = 'REJECTED';
        proposal.rejectionReason = reason;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to reject proposal:', err);
      }
    });
  }

  openBulkActionDialog(type: 'accept' | 'reject'): void {
    if (this.selectedProposals.size === 0) return;
    this.bulkActionType = type;
    this.bulkRejectReason = '';
    this.showBulkActionDialog = true;
    this.cdr.markForCheck();
  }

  closeBulkActionDialog(): void {
    this.showBulkActionDialog = false;
    this.cdr.markForCheck();
  }

  performBulkAction(): void {
    const proposalIds = Array.from(this.selectedProposals);

    if (this.bulkActionType === 'accept') {
      this.builderService.bulkAcceptProposals(proposalIds).subscribe({
        next: () => {
          this.loadProposals();
          this.selectedProposals.clear();
          this.closeBulkActionDialog();
        },
        error: (err) => {
          console.error('Failed to bulk accept:', err);
        }
      });
    } else {
      this.builderService.bulkRejectProposals(proposalIds, 'user', this.bulkRejectReason).subscribe({
        next: () => {
          this.loadProposals();
          this.selectedProposals.clear();
          this.closeBulkActionDialog();
        },
        error: (err) => {
          console.error('Failed to bulk reject:', err);
        }
      });
    }
  }

  acceptAllPending(): void {
    if (!this.selectedJob) return;
    this.builderService.acceptAllPending(this.selectedJob.jobId).subscribe({
      next: () => {
        this.loadProposals();
      },
      error: (err) => {
        console.error('Failed to accept all pending:', err);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MANUAL PROPOSAL
  // ═══════════════════════════════════════════════════════════════════════════

  openManualProposalForm(): void {
    this.showManualProposalForm = true;
    this.resetManualProposalForm();
    this.cdr.markForCheck();
  }

  closeManualProposalForm(): void {
    this.showManualProposalForm = false;
    this.cdr.markForCheck();
  }

  resetManualProposalForm(): void {
    this.manualProposal = {
      subjectName: '',
      subjectType: 'CONCEPT',
      predicateName: '',
      objectName: '',
      objectType: 'CONCEPT',
      description: '',
      autoAccept: false
    };
  }

  submitManualProposal(): void {
    if (!this.manualProposal.subjectName || !this.manualProposal.predicateName || !this.manualProposal.objectName) {
      return;
    }

    this.builderService.createManualProposal({
      factSheetId: this.factSheetId,
      ...this.manualProposal
    }).subscribe({
      next: (proposal) => {
        this.proposals = [proposal, ...this.proposals];
        this.closeManualProposalForm();
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to create manual proposal:', err);
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EXTRACTION LOGS
  // ═══════════════════════════════════════════════════════════════════════════

  selectLog(log: ExtractionLog): void {
    this.selectedLog = log;
    this.cdr.markForCheck();
  }

  closeLogDetail(): void {
    this.selectedLog = null;
    this.cdr.markForCheck();
  }

  parseEntities(json: string | undefined): ParsedEntity[] {
    if (!json) return [];
    try {
      return JSON.parse(json);
    } catch {
      return [];
    }
  }

  parseRelationships(json: string | undefined): ParsedRelationship[] {
    if (!json) return [];
    try {
      return JSON.parse(json);
    } catch {
      return [];
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // VIEW MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  setViewMode(mode: 'overview' | 'jobs' | 'proposals' | 'logs' | 'config'): void {
    this.viewMode = mode;
    if (mode === 'proposals') {
      this.loadProposals();
    } else if (mode === 'logs') {
      this.loadExtractionLogs();
    }
    this.cdr.markForCheck();
  }

  onProposalFilterChange(): void {
    this.proposalsPage = 0;
    this.loadProposals();
  }

  onProposalSearch(): void {
    this.proposalsPage = 0;
    this.loadProposals();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // AUTO REFRESH
  // ═══════════════════════════════════════════════════════════════════════════

  startAutoRefresh(): void {
    interval(this.refreshIntervalSeconds * 1000)
      .pipe(
        takeUntil(this.destroy$),
        filter(() => this.autoRefresh)
      )
      .subscribe(() => {
        this.loadJobs();
        if (this.selectedJob && this.selectedJob.status === 'RUNNING') {
          this.refreshJobStatus(this.selectedJob);
        }
      });
  }

  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;
    this.cdr.markForCheck();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PAGINATION
  // ═══════════════════════════════════════════════════════════════════════════

  get proposalsTotalPages(): number {
    return Math.ceil(this.proposalsTotalElements / this.proposalsPageSize);
  }

  proposalsNextPage(): void {
    if (this.proposalsPage < this.proposalsTotalPages - 1) {
      this.proposalsPage++;
      this.loadProposals();
    }
  }

  proposalsPrevPage(): void {
    if (this.proposalsPage > 0) {
      this.proposalsPage--;
      this.loadProposals();
    }
  }

  get logsTotalPages(): number {
    return Math.ceil(this.logsTotalElements / this.logsPageSize);
  }

  logsNextPage(): void {
    if (this.logsPage < this.logsTotalPages - 1) {
      this.logsPage++;
      this.loadExtractionLogs();
    }
  }

  logsPrevPage(): void {
    if (this.logsPage > 0) {
      this.logsPage--;
      this.loadExtractionLogs();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITIES
  // ═══════════════════════════════════════════════════════════════════════════

  getJobStatusColor(status: JobStatus): string {
    return JOB_STATUS_COLORS[status] || '#9E9E9E';
  }

  getProposalStatusColor(status: ProposalStatus): string {
    return PROPOSAL_STATUS_COLORS[status] || '#9E9E9E';
  }

  getBuilderIcon(type: string): string {
    return BUILDER_TYPE_ICONS[type as keyof typeof BUILDER_TYPE_ICONS] || 'extension';
  }

  getRunningJobsCount(): number {
    return this.jobs.filter(j => j.status === 'RUNNING').length;
  }

  getPendingProposalsCount(): number {
    return this.proposals.filter(p => p.status === 'PENDING').length;
  }

  formatTimestamp(timestamp: string | undefined): string {
    if (!timestamp) return '-';
    return new Date(timestamp).toLocaleString();
  }

  formatConfidence(confidence: number): string {
    return (confidence * 100).toFixed(1) + '%';
  }

  trackByJobId(index: number, job: ExtractionJob): string {
    return job.jobId;
  }

  trackByProposalId(index: number, proposal: TripleProposal): string {
    return proposal.proposalId;
  }

  trackByLogId(index: number, log: ExtractionLog): number {
    return log.id;
  }
}
