/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy, AfterViewInit, Input, ChangeDetectionStrategy, ChangeDetectorRef, NgZone, ViewChild, ElementRef } from '@angular/core';
import { Subject, interval } from 'rxjs';
import { takeUntil, startWith, debounceTime } from 'rxjs/operators';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  AuditLogEntry,
  AuditSearchCriteria,
  AuditStats,
  AuditEventType,
  AuditEntityType,
  PagedResult
} from '../../../../models/orchestrator-models';

@Component({
  standalone: false,
  selector: 'app-audit-log-viewer',
  templateUrl: './audit-log-viewer.component.html',
  styleUrls: ['./audit-log-viewer.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AuditLogViewerComponent implements OnInit, OnDestroy, AfterViewInit {
  @Input() instanceId: string = '';
  @ViewChild('searchInput') searchInputRef!: ElementRef<HTMLInputElement>;

  // Data
  auditLogs: AuditLogEntry[] = [];
  stats: AuditStats | null = null;
  totalElements = 0;
  totalPages = 0;
  currentPage = 0;
  pageSize = 50;

  // Filter state
  searchCriteria: AuditSearchCriteria = {};
  eventTypes: AuditEventType[] = [];
  entityTypes: AuditEntityType[] = [];

  // UI state
  loading = false;
  loadingStats = false;
  error: string | null = null;
  autoRefresh = false;
  expandedRows: Set<number> = new Set();

  // Columns to display
  displayedColumns = ['timestamp', 'eventType', 'entityType', 'action', 'message', 'actor', 'duration', 'status'];

  private destroy$ = new Subject<void>();
  private refreshInterval$ = new Subject<void>();
  private searchSubject$ = new Subject<string>();
  private searchInputListener: ((e: Event) => void) | null = null;

  constructor(
    private orchestratorService: OrchestratorService,
    private cdr: ChangeDetectorRef,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    // Setup debounced search
    this.searchSubject$.pipe(
      debounceTime(300),
      takeUntil(this.destroy$)
    ).subscribe(search => {
      this.ngZone.run(() => {
        this.searchCriteria.search = search || undefined;
        this.currentPage = 0;
        this.loadAuditLogs();
      });
    });

    this.loadEventTypes();
    this.loadEntityTypes();
    this.loadStats();
    this.loadAuditLogs();
  }

  ngAfterViewInit(): void {
    // Attach native event listener to search input to bypass Angular change detection
    if (this.searchInputRef?.nativeElement) {
      this.searchInputListener = (e: Event) => {
        const value = (e.target as HTMLInputElement).value;
        this.searchSubject$.next(value);
      };
      this.ngZone.runOutsideAngular(() => {
        this.searchInputRef.nativeElement.addEventListener('input', this.searchInputListener!);
      });
    }
  }

  ngOnDestroy(): void {
    // Clean up native event listener
    if (this.searchInputListener && this.searchInputRef?.nativeElement) {
      this.searchInputRef.nativeElement.removeEventListener('input', this.searchInputListener);
      this.searchInputListener = null;
    }

    this.destroy$.next();
    this.destroy$.complete();
    this.refreshInterval$.complete();
  }

  // Load dropdown options
  private loadEventTypes(): void {
    this.orchestratorService.getAuditEventTypes(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (types) => {
          this.eventTypes = types;
          this.cdr.markForCheck();
        },
        error: () => {
          // Use default types if endpoint fails
          this.eventTypes = [
            'STATE_CHANGE', 'TASK_LIFECYCLE', 'LLM_INTERACTION',
            'WORKFLOW_LIFECYCLE', 'TRIGGER_FIRED', 'HOOK_EXECUTED',
            'ERROR', 'RECOVERY', 'CONFIGURATION_CHANGE', 'ORCHESTRATOR_LIFECYCLE'
          ] as AuditEventType[];
          this.cdr.markForCheck();
        }
      });
  }

  private loadEntityTypes(): void {
    this.orchestratorService.getAuditEntityTypes(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (types) => {
          this.entityTypes = types;
          this.cdr.markForCheck();
        },
        error: () => {
          // Use default types if endpoint fails
          this.entityTypes = [
            'ORCHESTRATOR', 'STATE', 'TASK', 'WORKFLOW',
            'WORKFLOW_STEP', 'LLM_SESSION', 'TRIGGER', 'HOOK', 'CONFIGURATION'
          ] as AuditEntityType[];
          this.cdr.markForCheck();
        }
      });
  }

  // Load data
  loadAuditLogs(): void {
    if (!this.instanceId) return;

    this.loading = true;
    this.error = null;

    const hasFilters = this.searchCriteria.eventType ||
                       this.searchCriteria.entityType ||
                       this.searchCriteria.fromTime ||
                       this.searchCriteria.toTime ||
                       this.searchCriteria.search ||
                       this.searchCriteria.actorId ||
                       this.searchCriteria.errorsOnly;

    const request = hasFilters
      ? this.orchestratorService.searchAuditLogs(this.instanceId, this.searchCriteria, this.currentPage, this.pageSize)
      : this.orchestratorService.getAuditLogs(this.instanceId, this.currentPage, this.pageSize);

    request.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result: PagedResult<AuditLogEntry>) => {
          this.auditLogs = result.content;
          this.totalElements = result.totalElements;
          this.totalPages = result.totalPages;
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = 'Failed to load audit logs: ' + (err.error?.message || err.message);
          this.loading = false;
          this.cdr.markForCheck();
        }
      });
  }

  loadStats(): void {
    if (!this.instanceId) return;

    this.loadingStats = true;
    this.orchestratorService.getAuditStats(this.instanceId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats) => {
          this.stats = stats;
          this.loadingStats = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.stats = null;
          this.loadingStats = false;
          this.cdr.markForCheck();
        }
      });
  }

  // Filter actions
  applyFilters(): void {
    this.currentPage = 0;
    this.loadAuditLogs();
  }

  clearFilters(): void {
    this.searchCriteria = {};
    this.currentPage = 0;
    this.loadAuditLogs();
  }

  setEventTypeFilter(eventType: AuditEventType | ''): void {
    this.searchCriteria.eventType = eventType || undefined;
    this.applyFilters();
  }

  setEntityTypeFilter(entityType: AuditEntityType | ''): void {
    this.searchCriteria.entityType = entityType || undefined;
    this.applyFilters();
  }

  setDateRange(from: string, to: string): void {
    this.searchCriteria.fromTime = from || undefined;
    this.searchCriteria.toTime = to || undefined;
    this.applyFilters();
  }

  // Search is handled by native event listener and debounced Subject
  // This method is kept for programmatic access if needed
  setSearchText(search: string): void {
    this.searchSubject$.next(search);
  }

  toggleErrorsOnly(): void {
    this.searchCriteria.errorsOnly = !this.searchCriteria.errorsOnly;
    this.applyFilters();
  }

  // Pagination
  goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages) {
      this.currentPage = page;
      this.loadAuditLogs();
    }
  }

  nextPage(): void {
    this.goToPage(this.currentPage + 1);
  }

  previousPage(): void {
    this.goToPage(this.currentPage - 1);
  }

  // Auto-refresh
  toggleAutoRefresh(): void {
    this.autoRefresh = !this.autoRefresh;

    if (this.autoRefresh) {
      interval(5000)
        .pipe(
          takeUntil(this.refreshInterval$),
          takeUntil(this.destroy$)
        )
        .subscribe(() => {
          this.loadAuditLogs();
          this.loadStats();
        });
    } else {
      this.refreshInterval$.next();
    }
  }

  // Row expansion
  toggleRowExpansion(id: number): void {
    if (this.expandedRows.has(id)) {
      this.expandedRows.delete(id);
    } else {
      this.expandedRows.add(id);
    }
  }

  isRowExpanded(id: number): boolean {
    return this.expandedRows.has(id);
  }

  // Export
  exportLogs(format: 'json' | 'csv'): void {
    this.orchestratorService.exportAuditLogs(this.instanceId, format, this.searchCriteria)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (blob) => {
          const url = window.URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `audit-logs-${this.instanceId}-${new Date().toISOString().slice(0, 10)}.${format}`;
          a.click();
          window.URL.revokeObjectURL(url);
        },
        error: (err) => {
          this.error = 'Failed to export: ' + (err.error?.message || err.message);
          this.cdr.markForCheck();
        }
      });
  }

  // TrackBy function for ngFor performance
  trackByLogId(index: number, log: AuditLogEntry): number {
    return log.id;
  }

  // Helpers
  getEventTypeClass(eventType: AuditEventType): string {
    switch (eventType) {
      case 'ERROR':
      case 'RECOVERY':
        return 'event-error';
      case 'STATE_CHANGE':
        return 'event-state';
      case 'TASK_LIFECYCLE':
        return 'event-task';
      case 'LLM_INTERACTION':
        return 'event-llm';
      case 'WORKFLOW_LIFECYCLE':
        return 'event-workflow';
      case 'TRIGGER_FIRED':
      case 'HOOK_EXECUTED':
        return 'event-trigger';
      case 'CONFIGURATION_CHANGE':
        return 'event-config';
      default:
        return 'event-default';
    }
  }

  formatDuration(ms: number | undefined): string {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
  }

  formatTimestamp(timestamp: string): string {
    if (!timestamp) return '-';
    const date = new Date(timestamp);
    return date.toLocaleString();
  }

  parseDetails(detailsJson: string | undefined): any {
    if (!detailsJson) return null;
    try {
      return JSON.parse(detailsJson);
    } catch {
      return null;
    }
  }

  getEventTypeCount(eventsByType: Record<string, number> | undefined): number {
    if (!eventsByType) return 0;
    return Object.keys(eventsByType).length;
  }

  clearError(): void {
    this.error = null;
  }

  refresh(): void {
    this.loadAuditLogs();
    this.loadStats();
  }
}
