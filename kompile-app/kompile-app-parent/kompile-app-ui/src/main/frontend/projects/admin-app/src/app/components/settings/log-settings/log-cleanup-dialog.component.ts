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

import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

export interface LogCleanupDialogData {
  initialHours: number;
}

@Component({
  selector: 'app-log-cleanup-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatProgressSpinnerModule],
  template: `
    <div class="dialog-header">
      <mat-icon color="warn">delete_sweep</mat-icon>
      <h2 mat-dialog-title>Manual Log Cleanup</h2>
    </div>
    <mat-dialog-content>
      <p>Delete log entries older than:</p>
      <div class="dialog-input">
        <mat-form-field appearance="outline">
          <input matInput type="number" [(ngModel)]="cleanupHours" min="1" max="8760">
          <span matSuffix>hours</span>
        </mat-form-field>
        <span class="hint">(168 hours = 7 days)</span>
      </div>
      <div class="dialog-warning" *ngIf="cleanupHours < 24">
        <mat-icon>warning</mat-icon>
        <span>Deleting logs less than 24 hours old may affect debugging.</span>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="warn" (click)="onConfirm()">
        <mat-icon>delete</mat-icon>
        Delete Old Logs
      </button>
    </mat-dialog-actions>
  `,
  styles: [`.dialog-header { display: flex; align-items: center; gap: 12px; padding: 16px 24px 0 24px; }
    .dialog-header mat-icon { font-size: 28px; width: 28px; height: 28px; }
    .dialog-header h2 { margin: 0; font-size: 1.25rem; font-weight: 500; }
    mat-dialog-content { min-width: 360px; }
    .dialog-input { display: flex; align-items: center; gap: 12px; margin: 12px 0; }
    .hint { font-size: 13px; color: var(--text-secondary); }
    .dialog-warning { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: #fff3e0; border-radius: 4px; color: #e65100; font-size: 13px; }
    .dialog-warning mat-icon { font-size: 18px; width: 18px; height: 18px; }`]
})
export class LogCleanupDialogComponent {
  cleanupHours: number;

  constructor(
    private dialogRef: MatDialogRef<LogCleanupDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: LogCleanupDialogData
  ) {
    this.cleanupHours = data.initialHours;
  }

  onCancel(): void { this.dialogRef.close(null); }
  onConfirm(): void { this.dialogRef.close(this.cleanupHours); }
}
