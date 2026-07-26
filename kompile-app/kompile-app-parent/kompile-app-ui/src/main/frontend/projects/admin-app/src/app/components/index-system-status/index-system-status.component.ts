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

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Subscription, Subject, timer } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { IndexBrowserService } from '@shared/services/index-browser.service';
import { CrossIndexService } from '@shared/services/cross-index.service';
import { FactSheetService } from '@shared/services/fact-sheet.service';
import { WebSocketService } from '@shared/services/websocket.service';
import { MatSnackBar } from '@angular/material/snack-bar';
import { IndexBrowserStatus, ModelStatusUpdate, FactSheet } from '@shared/models/api-models';

@Component({
  selector: 'app-index-system-status',
  standalone: false,
  templateUrl: './index-system-status.component.html',
  styleUrls: ['./index-system-status.component.css']
})
export class IndexSystemStatusComponent implements OnInit, OnDestroy {

  // ─── Status state ────────────────────────────────────────────────────────────
  indexBrowserStatus: IndexBrowserStatus | null = null;
  isLoadingStatus = false;

  // ─── Cross-index state ───────────────────────────────────────────────────────
  crossIndexPendingCount = 0;
  activeFactSheetId = 1;

  // ─── Embedding model polling ─────────────────────────────────────────────────
  private embeddingModelPollingSub: Subscription | null = null;
  private readonly embeddingModelPollIntervalSlow = 3000;
  private readonly embeddingModelPollIntervalFast = 500;
  private currentPollingFast = false;

  // ─── WebSocket ───────────────────────────────────────────────────────────────
  private destroy$ = new Subject<void>();
  private modelStatusSubscribed = false;

  get isPollingEmbeddingModel(): boolean {
    return this.embeddingModelPollingSub !== null;
  }

  constructor(
    private indexBrowserService: IndexBrowserService,
    private crossIndexService: CrossIndexService,
    private factSheetService: FactSheetService,
    private websocketService: WebSocketService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.loadCrossIndexSummary();
    this.subscribeToModelStatusUpdates();

    // Track active fact sheet so cross-index summary stays current
    this.factSheetService.activeSheet$.pipe(takeUntil(this.destroy$)).subscribe((sheet: FactSheet | null) => {
      if (sheet) {
        this.activeFactSheetId = sheet.id as number || 1;
        this.loadStatus();
        this.loadCrossIndexSummary();
        this.cdr.detectChanges();
      }
    });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.stopEmbeddingModelPolling();
  }

  // ─── Status ──────────────────────────────────────────────────────────────────

  loadStatus(): void {
    this.isLoadingStatus = true;
    this.indexBrowserService.getIndexBrowserStatus().subscribe({
      next: (status) => {
        const wasNotInitialized = this.indexBrowserStatus &&
          (this.indexBrowserStatus as any).embeddingModelInitialized === false;
        const isNowInitialized = (status as any).embeddingModelInitialized === true;

        this.indexBrowserStatus = status;
        this.isLoadingStatus = false;
        this.cdr.detectChanges();

        if (wasNotInitialized && isNowInitialized) {
          this.snackBar.open('Embedding model is now ready!', 'Close', {
            duration: 5000,
            panelClass: ['snackbar-success']
          });
        }

        const embeddingNotInitialized = (status as any).embeddingModelInitialized === false;
        const embeddingLoading = (status as any).embeddingModelLoading === true;

        if (embeddingNotInitialized || embeddingLoading) {
          this.startEmbeddingModelPolling(embeddingLoading);
        } else if (!embeddingNotInitialized && !embeddingLoading && this.embeddingModelPollingSub) {
          this.stopEmbeddingModelPolling();
        }

        if (status.warning) {
          this.snackBar.open(status.warning, 'Close', {
            duration: 10000,
            panelClass: ['snackbar-warning']
          });
        }
      },
      error: (err) => {
        this.isLoadingStatus = false;
        this.snackBar.open(`Error loading status: ${err.message || 'Server error'}`, 'Close', {
          duration: 5000,
          panelClass: ['snackbar-error']
        });
      }
    });
  }

  // ─── Cross-index summary ─────────────────────────────────────────────────────

  loadCrossIndexSummary(): void {
    this.crossIndexService.getCrossIndexSummaryForFactSheet(this.activeFactSheetId).subscribe({
      next: (summary) => {
        this.crossIndexPendingCount =
          (summary.partiallyIndexedDocuments || 0) +
          (summary.notIndexedDocuments || 0) +
          (summary.outOfSyncDocuments || 0);
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.warn('Could not load cross-index summary:', err);
      }
    });
  }

  // ─── Status helpers (template-bound) ─────────────────────────────────────────

  getStatusColor(): string {
    if (!this.indexBrowserStatus) return 'warn';
    if (this.indexBrowserStatus.isNoOpIndexer || this.indexBrowserStatus.isNoOpRetriever) return 'warn';
    if (!this.indexBrowserStatus.indexAvailable) return 'warn';
    return 'primary';
  }

  getStatusMessage(): string {
    if (!this.indexBrowserStatus) return 'Loading status...';
    if (this.indexBrowserStatus.isNoOpIndexer && this.indexBrowserStatus.isNoOpRetriever) {
      return 'Using NoOp implementations - no functionality available';
    }
    if (this.indexBrowserStatus.isNoOpIndexer) return 'Using NoOp Indexer - document browsing not available';
    if (this.indexBrowserStatus.isNoOpRetriever) return 'Using NoOp Retriever - search functionality not available';
    if (!this.indexBrowserStatus.indexAvailable) return 'Index not available - may need to be built';
    return 'Index available and ready';
  }

  getLoadingPhaseDisplay(phase: string | undefined): string {
    if (!phase) return 'Starting';
    switch (phase) {
      case 'IDLE': return 'Waiting';
      case 'STARTING': return 'Starting';
      case 'LOOKING_UP_REGISTRY': return 'Registry Lookup';
      case 'LOADING_MODEL_FILES': return 'Loading Files';
      case 'CREATING_ENCODER': return 'Creating Encoder';
      case 'TESTING_ENCODER': return 'Testing';
      case 'COMPLETE': return 'Complete';
      case 'FAILED': return 'Failed';
      default: return phase;
    }
  }

  formatTokenCount(count: number): string {
    if (count >= 1_000_000) return (count / 1_000_000).toFixed(1) + 'M';
    if (count >= 1_000) return (count / 1_000).toFixed(1) + 'K';
    return String(count);
  }

  // ─── Embedding model polling ─────────────────────────────────────────────────

  private startEmbeddingModelPolling(fastPolling = false): void {
    if (this.embeddingModelPollingSub && this.currentPollingFast === fastPolling) return;
    if (this.embeddingModelPollingSub && this.currentPollingFast !== fastPolling) {
      this.stopEmbeddingModelPolling();
    }
    const interval = fastPolling ? this.embeddingModelPollIntervalFast : this.embeddingModelPollIntervalSlow;
    this.currentPollingFast = fastPolling;
    this.embeddingModelPollingSub = timer(interval, interval).subscribe(() => {
      if (!this.isLoadingStatus) this.loadStatus();
    });
  }

  private stopEmbeddingModelPolling(): void {
    if (this.embeddingModelPollingSub) {
      this.embeddingModelPollingSub.unsubscribe();
      this.embeddingModelPollingSub = null;
    }
  }

  // ─── WebSocket model status ───────────────────────────────────────────────────

  private subscribeToModelStatusUpdates(): void {
    if (this.modelStatusSubscribed) return;
    this.websocketService.connect();
    this.modelStatusSubscribed = true;

    this.websocketService.subscribeToModelStatus().pipe(takeUntil(this.destroy$)).subscribe({
      next: (status: ModelStatusUpdate) => this.handleModelStatusUpdate(status),
      error: (err) => console.error('[WS-MODEL] Error in model status WebSocket:', err)
    });
  }

  private handleModelStatusUpdate(status: ModelStatusUpdate): void {
    if (!status.embedding) return;

    const wasInitialized = (this.indexBrowserStatus as any)?.embeddingModelInitialized === true;
    const isNowInitialized = status.embedding.initialized && (status.embedding.dimensions || 0) > 0;

    if (this.indexBrowserStatus) {
      (this.indexBrowserStatus as any).embeddingModelInitialized = isNowInitialized;
      (this.indexBrowserStatus as any).embeddingModelLoading = status.embedding.loading;
      (this.indexBrowserStatus as any).embeddingModelLoadingPhase = status.embedding.loadingPhase;
      (this.indexBrowserStatus as any).embeddingModelLoadingMessage = status.embedding.loadingMessage;
      (this.indexBrowserStatus as any).embeddingDimensions = status.embedding.dimensions || 0;
    }

    if (!wasInitialized && isNowInitialized) {
      this.stopEmbeddingModelPolling();
      this.snackBar.open('Embedding model is now ready!', 'Close', {
        duration: 5000,
        panelClass: ['snackbar-success']
      });
      this.loadStatus();
    }

    if (status.staging && this.indexBrowserStatus) {
      (this.indexBrowserStatus as any).stagingConnected = status.staging.connected;
      (this.indexBrowserStatus as any).stagingError = status.staging.lastError;
    }

    this.cdr.detectChanges();
  }
}
