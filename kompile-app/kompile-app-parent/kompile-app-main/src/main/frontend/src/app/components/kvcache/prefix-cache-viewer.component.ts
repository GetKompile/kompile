import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatSortModule } from '@angular/material/sort';
import { KVCacheService } from '../../services/kvcache.service';
import { PrefixCacheStats, PrefixEntry } from '../../models/kvcache-models';

@Component({
  standalone: true,
  selector: 'app-prefix-cache-viewer',
  imports: [
    CommonModule, MatCardModule, MatTableModule, MatButtonModule,
    MatIconModule, MatSnackBarModule, MatSortModule
  ],
  template: `
    <div class="prefix-viewer">
      <!-- Stats cards -->
      <div class="stat-cards">
        <mat-card class="stat-card">
          <div class="stat-value">{{ prefixStats?.totalEntries || 0 }} / {{ prefixStats?.maxEntries || 0 }}</div>
          <div class="stat-label">Entries (used/max)</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ (prefixStats?.hitRate || 0) * 100 | number:'1.1-1' }}%</div>
          <div class="stat-label">Hit Rate</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ prefixStats?.totalLookups || 0 }}</div>
          <div class="stat-label">Total Lookups</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ prefixStats?.totalHits || 0 }}</div>
          <div class="stat-label">Total Hits</div>
        </mat-card>
      </div>

      <!-- Actions -->
      <div class="actions">
        <button mat-raised-button (click)="refresh()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
        <button mat-raised-button color="primary" (click)="save()">
          <mat-icon>save</mat-icon> Save to Disk
        </button>
        <button mat-raised-button (click)="load()">
          <mat-icon>cloud_download</mat-icon> Load from Disk
        </button>
      </div>

      <!-- Entries table -->
      <table mat-table [dataSource]="entries" *ngIf="entries.length > 0" matSort class="entries-table">
        <ng-container matColumnDef="prefixHash">
          <th mat-header-cell *matHeaderCellDef mat-sort-header>Prefix Hash</th>
          <td mat-cell *matCellDef="let e">{{ e.prefixHash }}</td>
        </ng-container>
        <ng-container matColumnDef="tokenCount">
          <th mat-header-cell *matHeaderCellDef mat-sort-header>Tokens</th>
          <td mat-cell *matCellDef="let e">{{ e.tokenCount }}</td>
        </ng-container>
        <ng-container matColumnDef="blockCount">
          <th mat-header-cell *matHeaderCellDef mat-sort-header>Blocks</th>
          <td mat-cell *matCellDef="let e">{{ e.blockCount }}</td>
        </ng-container>
        <ng-container matColumnDef="accessCount">
          <th mat-header-cell *matHeaderCellDef mat-sort-header>Accesses</th>
          <td mat-cell *matCellDef="let e">{{ e.accessCount }}</td>
        </ng-container>
        <ng-container matColumnDef="lastAccessed">
          <th mat-header-cell *matHeaderCellDef mat-sort-header>Last Accessed</th>
          <td mat-cell *matCellDef="let e">{{ e.lastAccessed | date:'medium' }}</td>
        </ng-container>

        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
      </table>
    </div>
  `,
  styles: [`
    .stat-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
    .stat-card { text-align: center; padding: 16px; }
    .stat-value { font-size: 20px; font-weight: 600; color: #1565c0; }
    .stat-label { font-size: 12px; color: #666; margin-top: 4px; }
    .actions { display: flex; gap: 8px; margin-bottom: 16px; }
    .entries-table { width: 100%; }
  `]
})
export class PrefixCacheViewerComponent implements OnInit {
  prefixStats: PrefixCacheStats | null = null;
  entries: PrefixEntry[] = [];
  displayedColumns = ['prefixHash', 'tokenCount', 'blockCount', 'accessCount', 'lastAccessed'];

  constructor(private kvCacheService: KVCacheService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.kvCacheService.getPrefixCacheStats().subscribe({
      next: s => this.prefixStats = s,
      error: () => this.prefixStats = null
    });
    this.kvCacheService.getPrefixCacheEntries().subscribe({
      next: e => this.entries = e,
      error: () => this.entries = []
    });
  }

  save(): void {
    this.kvCacheService.savePrefixCache().subscribe({
      next: () => this.snackBar.open('Prefix cache saved to disk', 'Close', { duration: 2000 }),
      error: e => this.snackBar.open('Save failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }

  load(): void {
    this.kvCacheService.loadPrefixCache().subscribe({
      next: () => {
        this.snackBar.open('Prefix cache loaded from disk', 'Close', { duration: 2000 });
        this.refresh();
      },
      error: e => this.snackBar.open('Load failed: ' + e.message, 'Close', { duration: 3000 })
    });
  }
}
