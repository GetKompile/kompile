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

import {
  Component,
  Inject,
  OnInit,
  OnDestroy,
  ChangeDetectionStrategy,
  ChangeDetectorRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subscription, interval } from 'rxjs';
import { takeWhile } from 'rxjs/operators';
import {
  UnifiedCrawlService,
  StartJobWithFilesConfig,
  StartJobWithFilesResponse,
  JobDetail,
  JobSummary,
  PipelineStepProgress,
  DocumentGraphProgress,
  CrawlStageEvent,
  GraphExtractionConfig,
  VectorIndexConfig
} from '../../../services/unified-crawl.service';

export interface DocumentCrawlDialogData {
  factSheetId?: number | null;
}

type DialogPhase = 'upload' | 'running' | 'completed' | 'failed';

@Component({
  selector: 'app-document-crawl-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
    MatDividerModule,
    MatSlideToggleModule,
    MatSnackBarModule
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <!-- ═══════════════════ HEADER ═══════════════════ -->
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon class="title-icon">manage_search</mat-icon>
      <span *ngIf="phase === 'upload'">Crawl Documents</span>
      <span *ngIf="phase === 'running'">Crawl In Progress</span>
      <span *ngIf="phase === 'completed'">Crawl Complete</span>
      <span *ngIf="phase === 'failed'">Crawl Failed</span>
    </h2>

    <mat-dialog-content class="dialog-content">

      <!-- ═══════════════════ UPLOAD PHASE ═══════════════════ -->
      <div *ngIf="phase === 'upload'" class="upload-phase">

        <!-- Drop Zone -->
        <div class="drop-zone"
             [class.drop-zone-active]="isDragOver"
             [class.drop-zone-has-files]="selectedFiles.length > 0"
             (click)="triggerFileInput()"
             (dragover)="onDragOver($event)"
             (dragleave)="onDragLeave($event)"
             (drop)="onDrop($event)">

          <input type="file" #fileInput multiple hidden
                 (change)="onFileSelected($event)" accept="*/*">

          <div *ngIf="selectedFiles.length === 0" class="drop-zone-empty">
            <mat-icon class="upload-icon">cloud_upload</mat-icon>
            <div class="drop-text">Drag and drop files here</div>
            <div class="drop-subtext">or click to browse</div>
            <button mat-stroked-button color="primary" class="choose-btn"
                    (click)="$event.stopPropagation(); triggerFileInput()">
              <mat-icon>folder_open</mat-icon> Choose Files
            </button>
          </div>

          <div *ngIf="selectedFiles.length > 0" class="selected-files"
               (click)="$event.stopPropagation()">
            <div class="file-list">
              <div *ngFor="let file of selectedFiles; let i = index" class="file-item">
                <mat-icon class="file-icon">insert_drive_file</mat-icon>
                <span class="file-name">{{ file.name }}</span>
                <span class="file-size">{{ formatFileSize(file.size) }}</span>
                <button mat-icon-button class="remove-btn"
                        (click)="removeFile(i); $event.stopPropagation()"
                        matTooltip="Remove file">
                  <mat-icon>close</mat-icon>
                </button>
              </div>
            </div>
            <button mat-stroked-button class="add-more-btn"
                    (click)="triggerFileInput(); $event.stopPropagation()">
              <mat-icon>add</mat-icon> Add More
            </button>
          </div>
        </div>

        <!-- Job Name -->
        <mat-form-field appearance="outline" class="full-width name-field">
          <mat-label>Job Name (optional)</mat-label>
          <input matInput [(ngModel)]="jobName" placeholder="e.g. Q4 Reports Crawl">
        </mat-form-field>

        <mat-divider></mat-divider>

        <!-- Pipeline Options -->
        <div class="pipeline-options">
          <h4>Pipeline Options</h4>

          <div class="option-row">
            <mat-slide-toggle [(ngModel)]="graphEnabled" color="primary">
              Graph Extraction
            </mat-slide-toggle>
            <span class="option-desc">Extract entities and relationships via LLM</span>
          </div>

          <div class="option-row">
            <mat-slide-toggle [(ngModel)]="vectorEnabled" color="primary">
              Vector Indexing
            </mat-slide-toggle>
            <span class="option-desc">Embed and index document chunks for RAG</span>
          </div>
        </div>
      </div>

      <!-- ═══════════════════ PROGRESS PHASE ═══════════════════ -->
      <div *ngIf="phase !== 'upload'" class="progress-phase">

        <!-- Overall Progress -->
        <div class="overall-progress">
          <div class="progress-header">
            <span class="phase-label">
              <mat-icon [class.spinning-icon]="phase === 'running'">
                {{ phase === 'running' ? 'sync' : phase === 'completed' ? 'check_circle' : 'error' }}
              </mat-icon>
              {{ jobDetail?.currentPhase || 'Starting...' }}
            </span>
            <span class="progress-pct" *ngIf="jobDetail">
              {{ jobDetail.progressPercent }}%
            </span>
          </div>
          <mat-progress-bar
            [mode]="getProgressMode()"
            [value]="jobDetail?.progressPercent || 0"
            [color]="phase === 'failed' ? 'warn' : 'primary'">
          </mat-progress-bar>
          <div class="elapsed-time" *ngIf="jobDetail">
            {{ formatElapsed(jobDetail.elapsedMs) }}
          </div>
        </div>

        <!-- Current File -->
        <div class="current-file" *ngIf="jobDetail?.currentFile">
          <mat-icon>description</mat-icon>
          <span>Processing: <strong>{{ jobDetail!.currentFile }}</strong></span>
        </div>

        <!-- Stats Grid -->
        <div class="stats-grid" *ngIf="jobDetail">
          <div class="stat-card">
            <div class="stat-value">{{ jobDetail.documentsDiscovered }}</div>
            <div class="stat-label">Discovered</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ jobDetail.documentsLoaded }}</div>
            <div class="stat-label">Loaded</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ jobDetail.chunksCreated }}</div>
            <div class="stat-label">Chunks</div>
          </div>
          <div class="stat-card" *ngIf="graphEnabled">
            <div class="stat-value">{{ jobDetail.entitiesExtracted }}</div>
            <div class="stat-label">Entities</div>
          </div>
          <div class="stat-card" *ngIf="graphEnabled">
            <div class="stat-value">{{ jobDetail.relationshipsExtracted }}</div>
            <div class="stat-label">Relations</div>
          </div>
          <div class="stat-card" *ngIf="vectorEnabled">
            <div class="stat-value">{{ jobDetail.chunksEmbedded }}</div>
            <div class="stat-label">Embedded</div>
          </div>
          <div class="stat-card" *ngIf="vectorEnabled">
            <div class="stat-value">{{ jobDetail.documentsIndexed }}</div>
            <div class="stat-label">Indexed</div>
          </div>
          <div class="stat-card" *ngIf="jobDetail.errorCount > 0">
            <div class="stat-value error-text">{{ jobDetail.errorCount }}</div>
            <div class="stat-label">Errors</div>
          </div>
        </div>

        <!-- Pipeline Steps -->
        <div class="pipeline-steps" *ngIf="jobDetail?.pipelineSteps?.length">
          <h4>Pipeline Steps</h4>
          <div *ngFor="let step of jobDetail!.pipelineSteps" class="pipeline-step">
            <div class="step-header">
              <mat-icon class="step-icon" [ngClass]="getStepStatusClass(step.status)">
                {{ getStepIcon(step.status) }}
              </mat-icon>
              <span class="step-name">{{ step.displayName }}</span>
              <span class="step-counts" *ngIf="step.totalItems > 0">
                {{ step.completedItems }}/{{ step.totalItems }}
              </span>
              <span class="step-throughput" *ngIf="getStepThroughput(step)">
                {{ getStepThroughput(step) }}
              </span>
              <span class="step-elapsed" *ngIf="step.elapsedMs > 0">
                {{ formatElapsed(step.elapsedMs) }}
              </span>
            </div>
            <mat-progress-bar
              *ngIf="step.status === 'RUNNING' || step.status === 'BACKPRESSURE'"
              [mode]="step.progressPercent > 0 ? 'determinate' : 'indeterminate'"
              [value]="step.progressPercent"
              class="step-progress">
            </mat-progress-bar>
            <div class="step-message" *ngIf="step.message">{{ step.message }}</div>
          </div>
        </div>

        <!-- Per-Document Progress -->
        <div class="document-progress" *ngIf="jobDetail?.documentProgress?.length">
          <h4>Document Progress</h4>
          <div *ngFor="let doc of jobDetail!.documentProgress" class="doc-item">
            <div class="doc-header">
              <mat-icon class="doc-icon" [ngClass]="getDocStatusClass(doc.status || '')">
                {{ getDocIcon(doc.status || '') }}
              </mat-icon>
              <span class="doc-name" [matTooltip]="doc.sourcePath || ''">
                {{ doc.fileName || doc.documentKey }}
              </span>
              <mat-chip class="doc-phase-chip" *ngIf="doc.phase">
                {{ doc.phase }}
              </mat-chip>
            </div>
            <div class="doc-stats">
              <span *ngIf="(doc.chunksCreated || 0) > 0">{{ doc.chunksCreated }} chunks</span>
              <span *ngIf="(doc.entitiesExtracted || 0) > 0">{{ doc.entitiesExtracted }} entities</span>
              <span *ngIf="(doc.relationshipsExtracted || 0) > 0">{{ doc.relationshipsExtracted }} rels</span>
              <span *ngIf="(doc.chunksEmbedded || 0) > 0">{{ doc.chunksEmbedded }} embedded</span>
            </div>
            <div class="doc-error" *ngIf="doc.errorMessage">
              <mat-icon>warning</mat-icon> {{ doc.errorMessage }}
            </div>
          </div>
        </div>

        <!-- Recent Events -->
        <div class="recent-events" *ngIf="jobDetail?.recentEvents?.length">
          <h4>Activity</h4>
          <div *ngFor="let event of getLastEvents(8)" class="event-item"
               [ngClass]="'event-' + (event.level || 'INFO').toLowerCase()">
            <span *ngIf="event.timestamp" class="event-time">{{ formatEventTime(event.timestamp) }}</span>
            <mat-chip class="event-phase-chip">{{ event.phase }}</mat-chip>
            <span class="event-message">{{ event.message }}</span>
          </div>
        </div>

        <!-- Error Message -->
        <div class="error-banner" *ngIf="phase === 'failed' && jobDetail?.errorMessage">
          <mat-icon>error</mat-icon>
          <span>{{ jobDetail!.errorMessage }}</span>
        </div>
      </div>
    </mat-dialog-content>

    <!-- ═══════════════════ ACTIONS ═══════════════════ -->
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()" *ngIf="phase === 'upload'">Cancel</button>

      <button mat-raised-button color="primary"
              *ngIf="phase === 'upload'"
              [disabled]="selectedFiles.length === 0 || isSubmitting"
              (click)="startCrawl()">
        <mat-icon>play_arrow</mat-icon>
        {{ isSubmitting ? 'Starting...' : 'Start Crawl' }}
      </button>

      <button mat-raised-button color="warn"
              *ngIf="phase === 'running'"
              (click)="cancelJob()">
        <mat-icon>stop</mat-icon> Cancel Job
      </button>

      <button mat-button
              *ngIf="phase === 'completed' || phase === 'failed'"
              (click)="dialogRef.close(jobId)">
        Close
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .dialog-title {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      font-size: 20px;
    }
    .title-icon { color: #1976d2; }
    .dialog-content {
      min-width: 550px;
      max-width: 700px;
      max-height: 70vh;
      overflow-y: auto;
      padding: 16px 0;
    }

    /* ─── Upload Phase ─── */
    .drop-zone {
      border: 2px dashed #ccc;
      border-radius: 12px;
      padding: 32px;
      text-align: center;
      cursor: pointer;
      transition: all 0.2s;
      margin-bottom: 16px;
      background: #fafafa;
    }
    .drop-zone:hover { border-color: #1976d2; background: #f0f7ff; }
    .drop-zone-active { border-color: #1976d2; background: #e3f2fd; }
    .drop-zone-has-files { border-style: solid; cursor: default; padding: 16px; }
    .drop-zone-empty { display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .upload-icon { font-size: 48px; width: 48px; height: 48px; color: #999; }
    .drop-text { font-size: 16px; font-weight: 500; color: #555; }
    .drop-subtext { font-size: 13px; color: #999; }
    .choose-btn { margin-top: 8px; }
    .selected-files { text-align: left; }
    .file-list { max-height: 200px; overflow-y: auto; }
    .file-item {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 8px;
      border-radius: 6px;
      margin-bottom: 4px;
    }
    .file-item:hover { background: #f5f5f5; }
    .file-icon { color: #1976d2; font-size: 20px; width: 20px; height: 20px; }
    .file-name { flex: 1; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .file-size { font-size: 12px; color: #999; white-space: nowrap; }
    .remove-btn { width: 28px !important; height: 28px !important; line-height: 28px !important; }
    .remove-btn mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .add-more-btn { margin-top: 8px; }
    .full-width { width: 100%; }
    .name-field { margin-top: 12px; }

    .pipeline-options { margin-top: 16px; }
    .pipeline-options h4 { margin: 0 0 12px 0; font-size: 14px; color: #555; }
    .option-row {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
    }
    .option-desc { font-size: 12px; color: #888; }

    /* ─── Progress Phase ─── */
    .progress-phase { padding-top: 8px; }
    .overall-progress { margin-bottom: 16px; }
    .progress-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 8px;
    }
    .phase-label {
      display: flex;
      align-items: center;
      gap: 6px;
      font-weight: 500;
      font-size: 14px;
    }
    .progress-pct { font-weight: 600; font-size: 14px; color: #1976d2; }
    .elapsed-time { font-size: 12px; color: #999; margin-top: 4px; text-align: right; }

    .current-file {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      color: #666;
      margin-bottom: 16px;
      padding: 8px 12px;
      background: #f5f5f5;
      border-radius: 6px;
    }
    .current-file mat-icon { font-size: 18px; width: 18px; height: 18px; color: #999; }

    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(90px, 1fr));
      gap: 8px;
      margin-bottom: 16px;
    }
    .stat-card {
      text-align: center;
      padding: 10px 6px;
      background: #f9f9f9;
      border-radius: 8px;
      border: 1px solid #eee;
    }
    .stat-value { font-size: 20px; font-weight: 700; color: #333; }
    .stat-label { font-size: 11px; color: #888; margin-top: 2px; }
    .error-text { color: #d32f2f !important; }

    /* Pipeline Steps */
    .pipeline-steps { margin-bottom: 16px; }
    .pipeline-steps h4 { margin: 0 0 8px 0; font-size: 14px; color: #555; }
    .pipeline-step {
      margin-bottom: 8px;
      padding: 8px 10px;
      background: #fafafa;
      border-radius: 6px;
      border-left: 3px solid #ddd;
    }
    .pipeline-step:has(.step-running) { border-left-color: #1976d2; }
    .step-header {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
    }
    .step-icon { font-size: 18px; width: 18px; height: 18px; }
    .step-running { color: #1976d2; }
    .step-completed { color: #4caf50; }
    .step-failed { color: #d32f2f; }
    .step-pending { color: #bbb; }
    .step-name { flex: 1; font-weight: 500; }
    .step-counts { font-size: 12px; color: #666; }
    .step-elapsed { font-size: 11px; color: #999; }
    .step-progress { margin-top: 6px; }
    .step-message { font-size: 12px; color: #888; margin-top: 4px; }

    /* Document Progress */
    .document-progress { margin-bottom: 16px; }
    .document-progress h4 { margin: 0 0 8px 0; font-size: 14px; color: #555; }
    .doc-item {
      padding: 8px 10px;
      margin-bottom: 6px;
      background: #fafafa;
      border-radius: 6px;
    }
    .doc-header {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
    }
    .doc-icon { font-size: 18px; width: 18px; height: 18px; }
    .doc-completed { color: #4caf50; }
    .doc-running { color: #1976d2; }
    .doc-failed { color: #d32f2f; }
    .doc-pending { color: #bbb; }
    .doc-name { flex: 1; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .doc-phase-chip { font-size: 10px !important; min-height: 20px !important; padding: 0 8px !important; }
    .doc-stats {
      display: flex;
      gap: 12px;
      font-size: 12px;
      color: #666;
      margin-top: 4px;
      padding-left: 26px;
    }
    .doc-error {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: #d32f2f;
      margin-top: 4px;
      padding-left: 26px;
    }
    .doc-error mat-icon { font-size: 14px; width: 14px; height: 14px; }

    /* Recent Events */
    .recent-events { margin-bottom: 16px; }
    .recent-events h4 { margin: 0 0 8px 0; font-size: 14px; color: #555; }
    .event-item {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      padding: 4px 0;
    }
    .event-phase-chip { font-size: 10px !important; min-height: 18px !important; padding: 0 6px !important; }
    .event-message { color: #555; }
    .event-warn .event-message { color: #e65100; }
    .event-error .event-message { color: #d32f2f; }

    .error-banner {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      padding: 12px;
      background: #fbe9e7;
      border-radius: 8px;
      color: #d32f2f;
      font-size: 13px;
      margin-top: 12px;
    }
    .error-banner mat-icon { flex-shrink: 0; }

    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }
    .spinning-icon { animation: spin 1.5s linear infinite; }
  `]
})
export class DocumentCrawlDialogComponent implements OnInit, OnDestroy {

  phase: DialogPhase = 'upload';
  selectedFiles: File[] = [];
  isDragOver = false;
  jobName = '';
  graphEnabled = true;
  vectorEnabled = true;
  isSubmitting = false;

  jobId: string | null = null;
  jobDetail: JobDetail | null = null;

  private pollSub: Subscription | null = null;
  private alive = true;

  constructor(
    public dialogRef: MatDialogRef<DocumentCrawlDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DocumentCrawlDialogData,
    private crawlService: UnifiedCrawlService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {}

  ngOnDestroy(): void {
    this.alive = false;
    this.pollSub?.unsubscribe();
  }

  // ─── File handling ───

  triggerFileInput(): void {
    const input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.accept = '*/*';
    input.onchange = (e: any) => {
      const files = e.target?.files;
      if (files) {
        for (let i = 0; i < files.length; i++) {
          this.selectedFiles.push(files[i]);
        }
        this.cdr.markForCheck();
      }
    };
    input.click();
  }

  onFileSelected(event: any): void {
    const files = event.target?.files;
    if (files) {
      for (let i = 0; i < files.length; i++) {
        this.selectedFiles.push(files[i]);
      }
      this.cdr.markForCheck();
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
    const files = event.dataTransfer?.files;
    if (files) {
      for (let i = 0; i < files.length; i++) {
        this.selectedFiles.push(files[i]);
      }
      this.cdr.markForCheck();
    }
  }

  removeFile(index: number): void {
    this.selectedFiles.splice(index, 1);
    this.cdr.markForCheck();
  }

  // ─── Crawl lifecycle ───

  startCrawl(): void {
    if (this.selectedFiles.length === 0 || this.isSubmitting) return;
    this.isSubmitting = true;
    this.cdr.markForCheck();

    const config: StartJobWithFilesConfig = {};
    if (this.jobName) config.name = this.jobName;
    if (this.data?.factSheetId) config.factSheetId = this.data.factSheetId;
    config.graphExtraction = { enabled: this.graphEnabled } as GraphExtractionConfig;
    config.vectorIndex = { enabled: this.vectorEnabled } as VectorIndexConfig;

    this.crawlService.startJobWithFiles(this.selectedFiles, config).subscribe({
      next: (resp: StartJobWithFilesResponse) => {
        this.jobId = resp.jobId;
        this.phase = 'running';
        this.isSubmitting = false;
        this.startPolling();
        this.cdr.markForCheck();
      },
      error: (err: any) => {
        this.isSubmitting = false;
        this.snackBar.open(
          'Failed to start crawl: ' + (err.error?.error || err.message || 'Unknown error'),
          'Dismiss', { duration: 5000 }
        );
        this.cdr.markForCheck();
      }
    });
  }

  cancelJob(): void {
    if (!this.jobId) return;
    this.crawlService.cancelJob(this.jobId).subscribe({
      next: () => {
        this.snackBar.open('Crawl job cancelled', 'OK', { duration: 3000 });
        this.phase = 'failed';
        this.cdr.markForCheck();
      },
      error: () => {
        this.snackBar.open('Failed to cancel job', 'Dismiss', { duration: 3000 });
      }
    });
  }

  onCancel(): void {
    this.dialogRef.close(null);
  }

  // ─── Polling ───

  private startPolling(): void {
    this.pollSub = interval(3000)
      .pipe(takeWhile(() => this.alive && this.phase === 'running'))
      .subscribe(() => this.refreshJob());
    // Immediate first poll
    this.refreshJob();
  }

  private refreshJob(): void {
    if (!this.jobId) return;
    this.crawlService.getJob(this.jobId).subscribe({
      next: (detail: JobDetail) => {
        this.jobDetail = detail;
        const status = detail.status;
        if (status === 'COMPLETED' || status === 'COMPLETED_PENDING_EMBEDDING') {
          this.phase = 'completed';
          this.pollSub?.unsubscribe();
        } else if (status === 'FAILED' || status === 'CANCELLED') {
          this.phase = 'failed';
          this.pollSub?.unsubscribe();
        }
        this.cdr.markForCheck();
      },
      error: () => {
        // Transient error — keep polling
      }
    });
  }

  // ─── Helpers ───

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0) + ' ' + units[i];
  }

  formatElapsed(ms: number): string {
    if (!ms || ms <= 0) return '';
    const secs = Math.floor(ms / 1000);
    if (secs < 60) return secs + 's';
    const mins = Math.floor(secs / 60);
    const remSecs = secs % 60;
    if (mins < 60) return mins + 'm ' + remSecs + 's';
    const hrs = Math.floor(mins / 60);
    return hrs + 'h ' + (mins % 60) + 'm';
  }

  /** Get throughput for a pipeline step as items/sec or items/min */
  getStepThroughput(step: PipelineStepProgress): string {
    if (!step.elapsedMs || step.elapsedMs < 1000 || step.completedItems <= 0) return '';
    const rate = step.completedItems / (step.elapsedMs / 1000);
    if (rate >= 1) return `${rate.toFixed(1)}/s`;
    const perMin = rate * 60;
    return `${perMin.toFixed(1)}/min`;
  }

  /** Format event timestamp as relative time */
  formatEventTime(timestamp: string): string {
    if (!timestamp) return '';
    const diff = Date.now() - new Date(timestamp).getTime();
    if (diff < 1000) return 'now';
    if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
    if (diff < 3600000) return `${Math.floor(diff / 60000)}m ago`;
    return `${Math.floor(diff / 3600000)}h ago`;
  }

  getStepIcon(status: string): string {
    switch (status) {
      case 'RUNNING': case 'BACKPRESSURE': return 'sync';
      case 'COMPLETED': return 'check_circle';
      case 'FAILED': return 'error';
      case 'SKIPPED': return 'skip_next';
      case 'DEFERRED': return 'schedule';
      case 'CANCELLED': return 'cancel';
      default: return 'radio_button_unchecked';
    }
  }

  getStepStatusClass(status: string): string {
    switch (status) {
      case 'RUNNING': case 'BACKPRESSURE': return 'step-running';
      case 'COMPLETED': return 'step-completed';
      case 'FAILED': return 'step-failed';
      default: return 'step-pending';
    }
  }

  getDocIcon(status: string): string {
    if (!status) return 'radio_button_unchecked';
    const s = status.toUpperCase();
    if (s === 'COMPLETED' || s === 'DONE') return 'check_circle';
    if (s === 'FAILED' || s === 'ERROR') return 'error';
    if (s === 'PROCESSING' || s === 'RUNNING' || s === 'LOADING' || s === 'CHUNKING' ||
        s === 'EMBEDDING' || s === 'EXTRACTING') return 'sync';
    return 'radio_button_unchecked';
  }

  getDocStatusClass(status: string): string {
    if (!status) return 'doc-pending';
    const s = status.toUpperCase();
    if (s === 'COMPLETED' || s === 'DONE') return 'doc-completed';
    if (s === 'FAILED' || s === 'ERROR') return 'doc-failed';
    if (s === 'PROCESSING' || s === 'RUNNING' || s === 'LOADING' || s === 'CHUNKING' ||
        s === 'EMBEDDING' || s === 'EXTRACTING') return 'doc-running';
    return 'doc-pending';
  }

  getProgressMode(): 'determinate' | 'indeterminate' | 'query' | 'buffer' {
    const pct = this.jobDetail?.progressPercent;
    return pct != null && pct > 0 ? 'determinate' : 'indeterminate';
  }

  getLastEvents(n: number): CrawlStageEvent[] {
    if (!this.jobDetail?.recentEvents) return [];
    return this.jobDetail.recentEvents.slice(-n);
  }
}
