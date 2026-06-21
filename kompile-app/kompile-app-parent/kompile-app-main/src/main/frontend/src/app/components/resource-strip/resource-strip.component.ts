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

import { Component, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { WebSocketService } from '../../services/websocket.service';
import { SystemResourcesResponse, Nd4jDeviceMemory } from '../../models/api-models';

/**
 * Compact real-time strip showing per-device GPU memory plus global system
 * resources (RAM, CPU). Reuses the existing system-resources WebSocket stream
 * ({@link WebSocketService#subscribeToSystemResources}) already used elsewhere,
 * so it adds no new backend wiring — just surfaces it inside the crawl panes.
 */
@Component({
  standalone: true,
  selector: 'app-resource-strip',
  imports: [CommonModule, MatIconModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="resource-strip" *ngIf="res">
      <span class="res-item gpu" *ngFor="let d of gpuDevices()"
            [matTooltip]="(d.name || ('CUDA device ' + d.deviceId)) + (d.computeCapability ? ' · cc ' + d.computeCapability : '')">
        <mat-icon inline>memory</mat-icon>
        GPU{{ d.deviceId }} {{ fmtMB(d.usedMemoryMB) }}/{{ fmtMB(d.totalMemoryMB) }}
        <span class="pct" *ngIf="d.usagePercent != null">{{ d.usagePercent | number:'1.0-0' }}%</span>
      </span>

      <span class="res-item ram" *ngIf="sysRam as ram" matTooltip="Global system RAM (used / total)">
        <mat-icon inline>developer_board</mat-icon>
        RAM {{ fmtGB(ram.usedMB) }}/{{ fmtGB(ram.totalMB) }} GB
        <span class="pct">{{ ram.usagePercent | number:'1.0-0' }}%</span>
      </span>

      <span class="res-item cpu" *ngIf="res.cpu" matTooltip="System CPU load across all cores">
        <mat-icon inline>speed</mat-icon>
        CPU {{ cpuPercent() }}%
        <span class="cores">· {{ res.cpu.availableProcessors }} cores</span>
      </span>

      <span class="res-item backend" *ngIf="res.nd4j?.backend as backend" matTooltip="Active ND4J compute backend (multi-backend routes ops to CPU/CUDA by data location)">
        <mat-icon inline>developer_mode</mat-icon>
        {{ backend }}
      </span>
    </div>
  `,
  styles: [`
    .resource-strip {
      display: flex; flex-wrap: wrap; gap: 14px; align-items: center;
      padding: 6px 10px; margin: 4px 0 10px;
      border: 1px solid var(--mat-divider-color, rgba(0,0,0,.12));
      border-radius: 6px; font-size: 12px;
      background: var(--mat-app-surface-variant, rgba(0,0,0,.03));
    }
    .res-item { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
    .res-item mat-icon { font-size: 16px; width: 16px; height: 16px; opacity: .7; }
    .res-item .pct, .res-item .cores { opacity: .65; }
    .res-item.gpu { color: var(--mat-sys-primary, #1565c0); font-weight: 500; }
  `]
})
export class ResourceStripComponent implements OnInit, OnDestroy {
  res: SystemResourcesResponse | null = null;
  private sub?: Subscription;

  constructor(private ws: WebSocketService, private cdr: ChangeDetectorRef) {}

  ngOnInit(): void {
    this.sub = this.ws.subscribeToSystemResources().subscribe({
      next: (r) => { this.res = r; this.cdr.markForCheck(); },
      error: () => { /* non-fatal: strip stays hidden */ }
    });
  }

  ngOnDestroy(): void {
    // Only drop our local subscription; the shared WS topic may have other consumers.
    this.sub?.unsubscribe();
  }

  gpuDevices(): Nd4jDeviceMemory[] {
    return this.res?.nd4j?.devices ?? [];
  }

  get sysRam() {
    return this.res?.memory?.system;
  }

  fmtMB(mb?: number): string {
    if (mb == null) return '?';
    return mb >= 1024 ? (mb / 1024).toFixed(1) + 'G' : Math.round(mb) + 'M';
  }

  fmtGB(mb?: number): string {
    return mb == null ? '?' : (mb / 1024).toFixed(1);
  }

  cpuPercent(): string {
    const v = this.res?.cpu?.systemCpuLoad;
    const n = typeof v === 'string' ? parseFloat(v) : (v ?? 0);
    if (isNaN(n)) return 'n/a';
    return (n <= 1 ? n * 100 : n).toFixed(0);
  }
}
