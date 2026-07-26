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

import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ChunkManagerService } from '@shared/services/chunk-manager.service';
import { DeduplicationStrategy, KeepPolicy, DuplicateAnalysisResponse } from '@shared/models/chunk-manager.models';

export interface DedupDialogResult {
  strategy: DeduplicationStrategy;
  keepPolicy: KeepPolicy;
  dryRun: boolean;
}

@Component({
  selector: 'app-dedup-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule, MatDividerModule, MatProgressSpinnerModule],
  template: `
    <div class="dialog-header">
      <h3 mat-dialog-title>Deduplicate Chunks</h3>
      <button mat-icon-button (click)="onClose()"><mat-icon>close</mat-icon></button>
    </div>
    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Deduplication Strategy</mat-label>
        <mat-select [(ngModel)]="strategy">
          <mat-option value="content_hash">Content Hash (SHA-256) - Finds exact text duplicates</mat-option>
          <mat-option value="source_and_index">Source + Index - Finds re-indexed files</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Keep Policy</mat-label>
        <mat-select [(ngModel)]="keepPolicy">
          <mat-option value="first">Keep First (oldest)</mat-option>
          <mat-option value="latest">Keep Latest (newest)</mat-option>
        </mat-select>
      </mat-form-field>
      <mat-checkbox [(ngModel)]="dryRun">Dry Run (preview only, don't delete)</mat-checkbox>
      <div class="analysis-results" *ngIf="duplicateAnalysis">
        <mat-divider></mat-divider>
        <h4>Analysis Results</h4>
        <div class="result-stats">
          <div class="stat">
            <span class="stat-value">{{ duplicateAnalysis.totalDuplicateGroups }}</span>
            <span class="stat-label">Duplicate Groups</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ duplicateAnalysis.totalDuplicateChunks }}</span>
            <span class="stat-label">Total Duplicates</span>
          </div>
          <div class="stat">
            <span class="stat-value">{{ duplicateAnalysis.chunksToRemove }}</span>
            <span class="stat-label">To Remove</span>
          </div>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-stroked-button (click)="analyzeDuplicates()" [disabled]="isDeduplicating">
        <mat-icon>search</mat-icon> Analyze
      </button>
      <button mat-raised-button color="primary" (click)="onRun()" [disabled]="isDeduplicating">
        <mat-icon>{{ dryRun ? 'preview' : 'delete_sweep' }}</mat-icon>
        {{ dryRun ? 'Preview' : 'Remove Duplicates' }}
      </button>
    </mat-dialog-actions>
    <div class="dialog-loading" *ngIf="isDeduplicating">
      <mat-spinner diameter="24"></mat-spinner>
      <span>Processing...</span>
    </div>
  `,
  styles: [`.dialog-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 16px 16px 24px; border-bottom: 1px solid var(--border-color); }
    .dialog-header h3 { margin: 0; }
    mat-dialog-content { padding: 24px; display: flex; flex-direction: column; gap: 16px; min-width: 460px; }
    .full-width { width: 100%; }
    .analysis-results { margin-top: 8px; }
    .analysis-results h4 { margin: 16px 0 12px; color: var(--text-secondary); }
    .result-stats { display: flex; gap: 24px; }
    .stat { display: flex; flex-direction: column; align-items: center; }
    .stat-value { font-size: 28px; font-weight: 500; color: #1976d2; }
    .stat-label { font-size: 12px; color: var(--text-secondary); text-transform: uppercase; }
    .dialog-loading { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 16px; background: #fff3e0; }`]
})
export class DedupDialogComponent {
  strategy: DeduplicationStrategy = 'content_hash';
  keepPolicy: KeepPolicy = 'first';
  dryRun = true;
  duplicateAnalysis: DuplicateAnalysisResponse | null = null;
  isDeduplicating = false;

  constructor(
    private dialogRef: MatDialogRef<DedupDialogComponent>,
    private chunkManagerService: ChunkManagerService
  ) {}

  analyzeDuplicates(): void {
    this.isDeduplicating = true;
    this.chunkManagerService.analyzeDuplicates(this.strategy).subscribe({
      next: (response) => {
        this.duplicateAnalysis = response;
        this.isDeduplicating = false;
      },
      error: () => { this.isDeduplicating = false; }
    });
  }

  onRun(): void {
    const result: DedupDialogResult = {
      strategy: this.strategy,
      keepPolicy: this.keepPolicy,
      dryRun: this.dryRun
    };
    this.dialogRef.close(result);
  }

  onClose(): void { this.dialogRef.close(null); }
}
