import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { KVCacheService } from '../../services/kvcache.service';
import { KVCacheStats, KVCacheSummary, StatsSample } from '../../models/kvcache-models';
import { Subscription, interval } from 'rxjs';

@Component({
  standalone: true,
  selector: 'app-cache-stats',
  imports: [
    CommonModule, FormsModule, MatCardModule, MatSelectModule, MatFormFieldModule,
    MatIconModule, MatButtonModule, MatSlideToggleModule
  ],
  template: `
    <div class="cache-stats">
      <!-- Aggregate stats cards -->
      <div class="stat-cards">
        <mat-card class="stat-card">
          <div class="stat-value">{{ kvCacheService.formatBytes(aggregateStats?.memoryUsedBytes || 0) }}</div>
          <div class="stat-label">Total Memory</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ caches.length }}</div>
          <div class="stat-label">Active Caches</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ aggregateStats?.totalAppends || 0 }}</div>
          <div class="stat-label">Total Appends</div>
        </mat-card>
        <mat-card class="stat-card">
          <div class="stat-value">{{ aggregateStats?.totalEvictions || 0 }}</div>
          <div class="stat-label">Total Evictions</div>
        </mat-card>
      </div>

      <!-- Cache selector -->
      <div class="controls">
        <mat-form-field *ngIf="caches.length > 0">
          <mat-label>Select Cache</mat-label>
          <mat-select [(ngModel)]="selectedCache" (selectionChange)="loadStats()">
            <mat-option *ngFor="let c of caches" [value]="c.name">{{ c.name }} ({{ c.type }})</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-slide-toggle [(ngModel)]="autoRefresh" (change)="toggleAutoRefresh()">
          Auto-refresh (2s)
        </mat-slide-toggle>
      </div>

      <!-- Per-cache stats -->
      <div *ngIf="stats" class="detail-stats">
        <div class="stat-cards">
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ (stats.memoryUtilization * 100).toFixed(1) }}%</div>
            <div class="stat-label">Memory Utilization</div>
          </mat-card>
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ kvCacheService.formatBytes(stats.memoryUsedBytes || 0) }} / {{ kvCacheService.formatBytes(stats.memoryCapacityBytes || 0) }}</div>
            <div class="stat-label">Memory Used / Capacity</div>
          </mat-card>
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ stats.activeSequences }}</div>
            <div class="stat-label">Active Sequences</div>
          </mat-card>
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ stats.freeBlocks }} / {{ stats.totalBlocks }}</div>
            <div class="stat-label">Free / Total Blocks</div>
          </mat-card>
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ (stats.hitRate * 100).toFixed(1) }}%</div>
            <div class="stat-label">Hit Rate ({{ stats.hitCount || 0 }} / {{ (stats.hitCount || 0) + (stats.missCount || 0) }})</div>
          </mat-card>
          <mat-card class="stat-card mini">
            <div class="stat-value">{{ stats.totalFrees || 0 }}</div>
            <div class="stat-label">Total Frees</div>
          </mat-card>
        </div>

        <!-- Per-sequence token counts -->
        <mat-card *ngIf="stats.perSequenceTokenCounts && getSequenceKeys().length > 0" class="sequence-card">
          <mat-card-header><mat-card-title>Per-Sequence Token Counts</mat-card-title></mat-card-header>
          <mat-card-content>
            <div class="sequence-grid">
              <div *ngFor="let seqIdx of getSequenceKeys()" class="sequence-item">
                <span class="seq-label">Seq {{ seqIdx }}</span>
                <span class="seq-value">{{ stats.perSequenceTokenCounts![seqIdx] }} tokens</span>
              </div>
            </div>
          </mat-card-content>
        </mat-card>

        <!-- Time-series charts -->
        <div class="charts-grid">
          <mat-card class="chart-card">
            <mat-card-header><mat-card-title>Memory Usage</mat-card-title></mat-card-header>
            <mat-card-content>
              <canvas #memoryChart width="400" height="200"></canvas>
            </mat-card-content>
          </mat-card>
          <mat-card class="chart-card">
            <mat-card-header><mat-card-title>Operations/sec (Appends + Evictions)</mat-card-title></mat-card-header>
            <mat-card-content>
              <canvas #opsChart width="400" height="200"></canvas>
            </mat-card-content>
          </mat-card>
        </div>
      </div>

      <div *ngIf="caches.length === 0" class="empty-state">
        <mat-icon>bar_chart</mat-icon>
        <p>No caches to show statistics for.</p>
      </div>
    </div>
  `,
  styles: [`
    .stat-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 16px; }
    .stat-card { text-align: center; padding: 16px; }
    .stat-card.mini { padding: 12px; }
    .stat-value { font-size: 24px; font-weight: 600; color: #1565c0; }
    .stat-card.mini .stat-value { font-size: 16px; }
    .stat-label { font-size: 12px; color: #666; margin-top: 4px; }
    .controls { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .controls mat-form-field { min-width: 250px; }
    .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .chart-card canvas { width: 100%; height: 200px; }
    .detail-stats { margin-top: 16px; }
    .sequence-card { margin-bottom: 16px; }
    .sequence-grid { display: flex; flex-wrap: wrap; gap: 8px; }
    .sequence-item {
      display: flex; gap: 6px; align-items: center;
      padding: 4px 10px; background: #e3f2fd; border-radius: 4px; font-size: 12px;
    }
    .seq-label { font-weight: 600; color: #1565c0; }
    .seq-value { color: #333; }
    .empty-state {
      text-align: center; padding: 40px; color: #888;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }
    .empty-state mat-icon { font-size: 48px; width: 48px; height: 48px; }
  `]
})
export class CacheStatsComponent implements OnInit, OnDestroy, AfterViewInit {
  @ViewChild('memoryChart') memoryChartRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('opsChart') opsChartRef?: ElementRef<HTMLCanvasElement>;

  caches: KVCacheSummary[] = [];
  selectedCache = '';
  stats: KVCacheStats | null = null;
  aggregateStats: KVCacheStats | null = null;
  autoRefresh = false;
  private refreshSub?: Subscription;

  constructor(public kvCacheService: KVCacheService) {}

  ngOnInit(): void {
    this.kvCacheService.listCaches().subscribe(c => {
      this.caches = c;
      if (c.length > 0) {
        this.selectedCache = c[0].name;
        this.loadStats();
      }
    });
    this.kvCacheService.getAggregateStats().subscribe(s => this.aggregateStats = s);
  }

  ngAfterViewInit(): void {}

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadStats(): void {
    if (!this.selectedCache) return;
    this.kvCacheService.getCacheStats(this.selectedCache).subscribe({
      next: s => {
        this.stats = s;
        this.drawCharts(s.recentSamples || []);
      }
    });
    this.kvCacheService.getAggregateStats().subscribe(s => this.aggregateStats = s);
  }

  toggleAutoRefresh(): void {
    this.refreshSub?.unsubscribe();
    if (this.autoRefresh) {
      this.refreshSub = interval(2000).subscribe(() => this.loadStats());
    }
  }

  private drawCharts(samples: StatsSample[]): void {
    if (!samples.length) return;

    // Memory chart
    const memCanvas = this.memoryChartRef?.nativeElement;
    if (memCanvas) {
      const ctx = memCanvas.getContext('2d');
      if (ctx) this.drawLineChart(ctx, memCanvas, samples.map(s => s.memoryUsedBytes / (1024 * 1024)), '#1565c0', 'MB');
    }

    // Ops chart — appends (green) + evictions (red)
    const opsCanvas = this.opsChartRef?.nativeElement;
    if (opsCanvas) {
      const ctx = opsCanvas.getContext('2d');
      if (ctx) {
        this.drawDualLineChart(ctx, opsCanvas,
          samples.map(s => s.appendsPerSecond), '#2e7d32', 'appends/s',
          samples.map(s => s.evictionsPerSecond), '#c62828', 'evictions/s');
      }
    }
  }

  getSequenceKeys(): number[] {
    if (!this.stats?.perSequenceTokenCounts) return [];
    return Object.keys(this.stats.perSequenceTokenCounts).map(Number).sort((a, b) => a - b);
  }

  private drawDualLineChart(ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement,
    data1: number[], color1: string, label1: string,
    data2: number[], color2: string, label2: string): void {
    const w = canvas.width;
    const h = canvas.height;
    const pad = 40;
    ctx.clearRect(0, 0, w, h);
    if (data1.length < 2) return;
    const max = Math.max(...data1, ...data2, 1);
    const xStep = (w - pad * 2) / (data1.length - 1);
    // Grid
    ctx.strokeStyle = '#e0e0e0';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = pad + (h - 2 * pad) * (i / 4);
      ctx.beginPath(); ctx.moveTo(pad, y); ctx.lineTo(w - pad, y); ctx.stroke();
    }
    // Line 1
    ctx.strokeStyle = color1; ctx.lineWidth = 2; ctx.beginPath();
    data1.forEach((v, i) => { const x = pad + i * xStep; const y = h - pad - ((v / max) * (h - 2 * pad)); i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y); });
    ctx.stroke();
    // Line 2
    ctx.strokeStyle = color2; ctx.lineWidth = 2; ctx.beginPath();
    data2.forEach((v, i) => { const x = pad + i * xStep; const y = h - pad - ((v / max) * (h - 2 * pad)); i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y); });
    ctx.stroke();
    // Legend
    ctx.fillStyle = color1; ctx.font = '11px sans-serif'; ctx.fillText(label1, pad, 14);
    ctx.fillStyle = color2; ctx.fillText(label2, pad + ctx.measureText(label1).width + 20, 14);
  }

  private drawLineChart(ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement, data: number[], color: string, label: string): void {
    const w = canvas.width;
    const h = canvas.height;
    const pad = 40;

    ctx.clearRect(0, 0, w, h);

    if (data.length < 2) return;

    const max = Math.max(...data, 1);
    const xStep = (w - pad * 2) / (data.length - 1);

    // Grid
    ctx.strokeStyle = '#e0e0e0';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = pad + (h - 2 * pad) * (i / 4);
      ctx.beginPath();
      ctx.moveTo(pad, y);
      ctx.lineTo(w - pad, y);
      ctx.stroke();
    }

    // Line
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    data.forEach((v, i) => {
      const x = pad + i * xStep;
      const y = h - pad - ((v / max) * (h - 2 * pad));
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();

    // Label
    ctx.fillStyle = '#666';
    ctx.font = '11px sans-serif';
    ctx.fillText(label + ' (max: ' + max.toFixed(1) + ')', pad, 14);
  }
}
