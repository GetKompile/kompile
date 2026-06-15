import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { FormsModule } from '@angular/forms';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheSummary } from '../../models/kvcache-models';
import { Subscription, interval } from 'rxjs';

@Component({
  standalone: true,
  selector: 'app-cache-browser',
  imports: [
    CommonModule, MatTableModule, MatButtonModule, MatIconModule,
    MatProgressBarModule, MatTooltipModule, MatSnackBarModule,
    MatSlideToggleModule, FormsModule
  ],
  template: `
    <div class="cache-browser">
      <div class="toolbar">
        <button mat-raised-button color="primary" (click)="refresh()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
        <mat-slide-toggle [(ngModel)]="autoRefresh" (change)="toggleAutoRefresh()">
          Auto-refresh (5s)
        </mat-slide-toggle>
      </div>

      <div *ngIf="caches.length === 0" class="empty-state">
        <mat-icon>storage</mat-icon>
        <p>No active caches. Create one from the Configuration tab.</p>
      </div>

      <table mat-table [dataSource]="caches" *ngIf="caches.length > 0" class="cache-table">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Name</th>
          <td mat-cell *matCellDef="let c">{{ c.name }}</td>
        </ng-container>
        <ng-container matColumnDef="type">
          <th mat-header-cell *matHeaderCellDef>Type</th>
          <td mat-cell *matCellDef="let c">
            <span class="type-badge">{{ c.type }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="memory">
          <th mat-header-cell *matHeaderCellDef>Memory</th>
          <td mat-cell *matCellDef="let c">
            <div class="memory-cell">
              <span>{{ kvCacheService.formatBytes(c.memoryUsageBytes) }}</span>
              <mat-progress-bar mode="determinate"
                [value]="getMemoryPercent(c)"
                [color]="getMemoryPercent(c) > 80 ? 'warn' : 'primary'">
              </mat-progress-bar>
            </div>
          </td>
        </ng-container>
        <ng-container matColumnDef="sequences">
          <th mat-header-cell *matHeaderCellDef>Sequences</th>
          <td mat-cell *matCellDef="let c">{{ c.activeSequences }}</td>
        </ng-container>
        <ng-container matColumnDef="blocks">
          <th mat-header-cell *matHeaderCellDef>Blocks (free/total)</th>
          <td mat-cell *matCellDef="let c">{{ c.freeBlocks }} / {{ c.totalBlocks }}</td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef>Actions</th>
          <td mat-cell *matCellDef="let c">
            <button mat-icon-button color="warn" (click)="deleteCache(c.name)"
                    matTooltip="Delete cache">
              <mat-icon>delete</mat-icon>
            </button>
          </td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
      </table>
    </div>
  `,
  styles: [`
    .cache-browser { width: 100%; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .empty-state {
      text-align: center; padding: 40px; color: #888;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; }
    .cache-table { width: 100%; }
    .type-badge {
      padding: 2px 8px; border-radius: 4px; font-size: 12px;
      background: #e3f2fd; color: #1565c0;
    }
    .memory-cell { display: flex; flex-direction: column; gap: 4px; min-width: 120px; }
    .memory-cell span { font-size: 12px; }
    .memory-cell mat-progress-bar { height: 6px; }
  `]
})
export class CacheBrowserComponent implements OnInit, OnDestroy {
  caches: KVCacheSummary[] = [];
  displayedColumns = ['name', 'type', 'memory', 'sequences', 'blocks', 'actions'];
  autoRefresh = false;
  private refreshSub?: Subscription;

  constructor(public kvCacheService: KVCacheService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.refresh();
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  refresh(): void {
    this.kvCacheService.listCaches().subscribe({
      next: c => this.caches = c,
      error: e => this.snackBar.open('Failed to load caches: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  deleteCache(name: string): void {
    this.kvCacheService.destroyCache(name).subscribe({
      next: () => {
        this.snackBar.open(`Cache '${name}' deleted`, 'Close', { duration: 2000 });
        this.refresh();
      },
      error: e => this.snackBar.open('Delete failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  toggleAutoRefresh(): void {
    this.refreshSub?.unsubscribe();
    if (this.autoRefresh) {
      this.refreshSub = interval(5000).subscribe(() => this.refresh());
    }
  }

  getMemoryPercent(cache: KVCacheSummary): number {
    if (cache.totalBlocks === 0) return 0;
    return ((cache.totalBlocks - cache.freeBlocks) / cache.totalBlocks) * 100;
  }
}
