import { Component, OnInit, OnDestroy } from '@angular/core';
import {
  TrainingHistoryService, TrainingJobHistory, TrainingJobStatus, TrainingFailureReason
} from '../../../services/training-history.service';

@Component({
  selector: 'app-training-history',
  standalone: false,
  templateUrl: './training-history.component.html',
  styleUrls: ['./training-history.component.css']
})
export class TrainingHistoryComponent implements OnInit, OnDestroy {
  jobs: TrainingJobHistory[] = [];
  activeJobs: TrainingJobHistory[] = [];
  statistics: Record<string, any> | null = null;
  selectedJob: TrainingJobHistory | null = null;
  loading = false;
  error: string | null = null;
  message: string | null = null;

  // Pagination
  currentPage = 0;
  pageSize = 20;
  totalElements = 0;
  totalPages = 0;

  // Filters
  statusFilter = '';
  typeFilter = '';

  private pollTimer: any;

  constructor(public svc: TrainingHistoryService) {}

  ngOnInit(): void {
    this.loadAll();
    this.pollTimer = setInterval(() => this.loadActive(), 5000);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
  }

  loadAll(): void {
    this.loading = true;
    this.error = null;
    Promise.all([
      this.svc.getHistory(this.currentPage, this.pageSize).toPromise(),
      this.svc.getActive().toPromise(),
      this.svc.getStatistics().toPromise()
    ]).then(([page, active, stats]) => {
      if (page) {
        this.jobs = page.content;
        this.totalElements = page.totalElements;
        this.totalPages = page.totalPages;
      }
      this.activeJobs = active || [];
      this.statistics = stats || null;
      this.loading = false;
    }).catch(err => {
      this.error = err.error?.message || err.message || 'Failed to load training history';
      this.loading = false;
    });
  }

  loadActive(): void {
    this.svc.getActive().subscribe(active => this.activeJobs = active);
  }

  changePage(delta: number): void {
    this.currentPage += delta;
    if (this.currentPage < 0) this.currentPage = 0;
    if (this.currentPage >= this.totalPages) this.currentPage = this.totalPages - 1;
    this.loadAll();
  }

  filterByStatus(status: string): void {
    if (!status) { this.loadAll(); return; }
    this.loading = true;
    this.svc.getByStatus(status).subscribe({
      next: jobs => { this.jobs = jobs; this.loading = false; },
      error: err => { this.error = err.message; this.loading = false; }
    });
  }

  filterByType(type: string): void {
    if (!type) { this.loadAll(); return; }
    this.loading = true;
    this.svc.getByType(type).subscribe({
      next: jobs => { this.jobs = jobs; this.loading = false; },
      error: err => { this.error = err.message; this.loading = false; }
    });
  }

  selectJob(job: TrainingJobHistory): void {
    this.selectedJob = this.selectedJob?.taskId === job.taskId ? null : job;
  }

  deleteJob(taskId: string): void {
    this.svc.deleteJob(taskId).subscribe({
      next: () => { this.message = `Deleted ${taskId}`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  cleanup(days: number): void {
    this.svc.cleanup(days).subscribe({
      next: (res: any) => { this.message = `Cleaned up ${res.deletedCount} records`; this.loadAll(); },
      error: err => this.error = err.message
    });
  }

  formatDuration(ms?: number): string {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    const s = Math.floor(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    const rs = s % 60;
    if (m < 60) return `${m}m ${rs}s`;
    const h = Math.floor(m / 60);
    return `${h}h ${m % 60}m`;
  }

  formatLoss(v?: number): string {
    return v != null ? v.toFixed(6) : '-';
  }

  getProgressPercent(job: TrainingJobHistory): number {
    if (job.totalEpochs && job.currentEpoch) {
      return Math.round((job.currentEpoch / job.totalEpochs) * 100);
    }
    if (job.totalSteps && job.currentStep) {
      return Math.round((job.currentStep / job.totalSteps) * 100);
    }
    return 0;
  }
}
