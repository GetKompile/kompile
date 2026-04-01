import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheSummary, CheckpointInfo } from '../../models/kvcache-models';

@Component({
  standalone: true,
  selector: 'app-checkpoint-manager',
  imports: [
    CommonModule, FormsModule, MatTableModule, MatButtonModule, MatIconModule,
    MatSelectModule, MatFormFieldModule, MatInputModule, MatTooltipModule,
    MatSnackBarModule, MatChipsModule
  ],
  template: `
    <div class="checkpoint-manager">
      <div class="controls">
        <mat-form-field>
          <mat-label>Select Cache</mat-label>
          <mat-select [(ngModel)]="selectedCache" (selectionChange)="loadCheckpoints()">
            <mat-option *ngFor="let c of caches" [value]="c.name">{{ c.name }}</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field>
          <mat-label>Checkpoint Label</mat-label>
          <input matInput [(ngModel)]="newLabel" placeholder="e.g. before-finetune">
        </mat-form-field>

        <button mat-raised-button color="primary" (click)="createCheckpoint()"
                [disabled]="!selectedCache">
          <mat-icon>add_circle</mat-icon> Create Checkpoint
        </button>
      </div>

      <table mat-table [dataSource]="checkpoints" *ngIf="checkpoints.length > 0" class="cp-table">
        <ng-container matColumnDef="label">
          <th mat-header-cell *matHeaderCellDef>Label</th>
          <td mat-cell *matCellDef="let cp">{{ cp.label }}</td>
        </ng-container>
        <ng-container matColumnDef="created">
          <th mat-header-cell *matHeaderCellDef>Created</th>
          <td mat-cell *matCellDef="let cp">{{ cp.createdAt | date:'short' }}</td>
        </ng-container>
        <ng-container matColumnDef="tokens">
          <th mat-header-cell *matHeaderCellDef>Tokens</th>
          <td mat-cell *matCellDef="let cp">{{ cp.tokenCount }}</td>
        </ng-container>
        <ng-container matColumnDef="size">
          <th mat-header-cell *matHeaderCellDef>Size</th>
          <td mat-cell *matCellDef="let cp">{{ kvCacheService.formatBytes(cp.sizeBytes) }}</td>
        </ng-container>
        <ng-container matColumnDef="disk">
          <th mat-header-cell *matHeaderCellDef>On Disk</th>
          <td mat-cell *matCellDef="let cp">
            <mat-icon [class.on-disk]="cp.onDisk">{{ cp.onDisk ? 'cloud_done' : 'cloud_off' }}</mat-icon>
          </td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef>Actions</th>
          <td mat-cell *matCellDef="let cp">
            <button mat-icon-button (click)="restore(cp.id)" matTooltip="Restore">
              <mat-icon>restore</mat-icon>
            </button>
            <button mat-icon-button (click)="saveToDisk(cp.id)" matTooltip="Save to disk" *ngIf="!cp.onDisk">
              <mat-icon>save</mat-icon>
            </button>
            <button mat-icon-button (click)="rollback(cp.id)" matTooltip="Rollback to this point" color="accent">
              <mat-icon>undo</mat-icon>
            </button>
            <button mat-icon-button color="warn" (click)="deleteCheckpoint(cp.id)" matTooltip="Delete">
              <mat-icon>delete</mat-icon>
            </button>
          </td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
      </table>

      <div *ngIf="checkpoints.length === 0 && selectedCache" class="empty-state">
        <p>No checkpoints for this cache. Create one to save cache state.</p>
      </div>
    </div>
  `,
  styles: [`
    .controls { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
    .controls mat-form-field { min-width: 200px; }
    .cp-table { width: 100%; }
    .on-disk { color: #2e7d32; }
    .empty-state { text-align: center; padding: 24px; color: #888; }
  `]
})
export class CheckpointManagerComponent implements OnInit {
  caches: KVCacheSummary[] = [];
  selectedCache = '';
  newLabel = '';
  checkpoints: CheckpointInfo[] = [];
  displayedColumns = ['label', 'created', 'tokens', 'size', 'disk', 'actions'];

  constructor(public kvCacheService: KVCacheService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.kvCacheService.listCaches().subscribe(c => {
      this.caches = c;
      if (c.length > 0) {
        this.selectedCache = c[0].name;
        this.loadCheckpoints();
      }
    });
  }

  loadCheckpoints(): void {
    if (!this.selectedCache) return;
    this.kvCacheService.listCheckpoints(this.selectedCache).subscribe({
      next: cps => this.checkpoints = cps,
      error: () => this.checkpoints = []
    });
  }

  createCheckpoint(): void {
    this.kvCacheService.createCheckpoint(this.selectedCache, this.newLabel || undefined).subscribe({
      next: cp => {
        this.snackBar.open(`Checkpoint '${cp.label}' created`, 'Close', { duration: 2000 });
        this.newLabel = '';
        this.loadCheckpoints();
      },
      error: e => this.snackBar.open('Failed: ' + (e.error?.error || e.message), 'Close', { duration: 3000 })
    });
  }

  restore(id: string): void {
    this.kvCacheService.restoreCheckpoint(this.selectedCache, id).subscribe({
      next: () => this.snackBar.open('Checkpoint restored', 'Close', { duration: 2000 }),
      error: e => this.snackBar.open('Restore failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  saveToDisk(id: string): void {
    this.kvCacheService.saveCheckpointToDisk(this.selectedCache, id).subscribe({
      next: () => {
        this.snackBar.open('Saved to disk', 'Close', { duration: 2000 });
        this.loadCheckpoints();
      },
      error: e => this.snackBar.open('Save failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  rollback(id: string): void {
    this.kvCacheService.rollbackCheckpoint(this.selectedCache, id).subscribe({
      next: () => {
        this.snackBar.open('Rolled back', 'Close', { duration: 2000 });
        this.loadCheckpoints();
      },
      error: e => this.snackBar.open('Rollback failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  deleteCheckpoint(id: string): void {
    this.kvCacheService.deleteCheckpoint(this.selectedCache, id).subscribe({
      next: () => {
        this.snackBar.open('Checkpoint deleted', 'Close', { duration: 2000 });
        this.loadCheckpoints();
      },
      error: e => this.snackBar.open('Delete failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }
}
