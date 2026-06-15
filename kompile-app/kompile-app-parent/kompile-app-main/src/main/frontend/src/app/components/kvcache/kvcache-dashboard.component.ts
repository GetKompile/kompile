import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheStatus } from '../../models/kvcache-models';
import { CacheBrowserComponent } from './cache-browser.component';
import { CacheConfigComponent } from './cache-config.component';
import { CacheStatsComponent } from './cache-stats.component';
import { CheckpointManagerComponent } from './checkpoint-manager.component';
import { PrefixCacheViewerComponent } from './prefix-cache-viewer.component';
import { Subscription } from 'rxjs';

@Component({
  standalone: true,
  selector: 'app-kvcache-dashboard',
  imports: [
    CommonModule, MatTabsModule, MatIconModule, MatButtonModule, MatSlideToggleModule, MatSnackBarModule,
    CacheBrowserComponent, CacheConfigComponent, CacheStatsComponent,
    CheckpointManagerComponent, PrefixCacheViewerComponent
  ],
  template: `
    <div class="kvcache-dashboard">
      <div class="dashboard-header">
        <div class="header-info">
          <mat-icon>memory</mat-icon>
          <h3>KV Cache Management</h3>
          <mat-slide-toggle
            [checked]="status?.enabled || false"
            (change)="toggleEnabled($event.checked)"
            color="primary">
            {{ status?.enabled ? 'Enabled' : 'Disabled' }}
          </mat-slide-toggle>
          <span *ngIf="status?.enabled" class="cache-count">{{ status?.cacheCount || 0 }} caches</span>
        </div>
      </div>

      <mat-tab-group class="cache-tabs">
        <mat-tab label="Configuration">
          <div class="tab-body">
            <app-cache-config (configChanged)="refreshStatus()"></app-cache-config>
          </div>
        </mat-tab>
        <mat-tab label="Cache Browser" *ngIf="status?.enabled">
          <div class="tab-body">
            <app-cache-browser></app-cache-browser>
          </div>
        </mat-tab>
        <mat-tab label="Statistics" *ngIf="status?.enabled">
          <div class="tab-body">
            <app-cache-stats></app-cache-stats>
          </div>
        </mat-tab>
        <mat-tab label="Checkpoints" *ngIf="status?.enabled && status?.checkpointsEnabled">
          <div class="tab-body">
            <app-checkpoint-manager></app-checkpoint-manager>
          </div>
        </mat-tab>
        <mat-tab label="Prefix Cache" *ngIf="status?.enabled && status?.prefixCacheEnabled">
          <div class="tab-body">
            <app-prefix-cache-viewer></app-prefix-cache-viewer>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .kvcache-dashboard { padding: 8px 0; }
    .dashboard-header { display: flex; align-items: center; margin-bottom: 16px; }
    .header-info { display: flex; align-items: center; gap: 12px; }
    .header-info h3 { margin: 0; }
    .cache-count { font-size: 13px; color: #666; }
    .tab-body { padding: 16px 0; }
    .cache-tabs { margin-top: 8px; }
  `]
})
export class KVCacheDashboardComponent implements OnInit, OnDestroy {
  status: KVCacheStatus | null = null;
  private sub?: Subscription;

  constructor(private kvCacheService: KVCacheService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.refreshStatus();
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  refreshStatus(): void {
    this.sub?.unsubscribe();
    this.sub = this.kvCacheService.getStatus().subscribe({
      next: s => this.status = s,
      error: () => this.status = { enabled: false, cacheCount: 0, checkpointsEnabled: false, prefixCacheEnabled: false }
    });
  }

  toggleEnabled(enabled: boolean): void {
    const call = enabled ? this.kvCacheService.enable() : this.kvCacheService.disable();
    call.subscribe({
      next: () => {
        this.snackBar.open(`KV Cache ${enabled ? 'enabled' : 'disabled'}`, 'Close', { duration: 2000 });
        this.refreshStatus();
      },
      error: e => {
        this.snackBar.open('Failed: ' + (e.error?.error || e.message), 'Close', { duration: 3000 });
      }
    });
  }
}
