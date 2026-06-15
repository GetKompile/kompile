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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { backendUrl } from '../../services/base.service';
import { interval, Subscription } from 'rxjs';

export interface SystemInfoResponse {
  version?: {
    app?: string;
    springBoot?: string;
    java?: string;
    os?: string;
    arch?: string;
  };
  homeDirectory?: string;
  homeExists?: boolean;
  installedTools?: { [key: string]: boolean };
  runtime?: {
    totalMemory?: number;
    freeMemory?: number;
    maxMemory?: number;
    availableProcessors?: number;
    uptime?: string;
  };
}

@Component({
  standalone: true,
  selector: 'app-system-info',
  templateUrl: './system-info.component.html',
  styleUrls: ['./system-info.component.css'],
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDividerModule,
    MatTooltipModule,
    MatProgressBarModule
  ]
})
export class SystemInfoComponent implements OnInit, OnDestroy {
  info: SystemInfoResponse | null = null;
  loading = false;
  error: string | null = null;
  lastRefreshed: Date | null = null;

  private refreshSub?: Subscription;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.loadInfo();
    // Auto-refresh every 30 seconds
    this.refreshSub = interval(30000).subscribe(() => this.loadInfo());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadInfo(): void {
    this.loading = true;
    this.error = null;
    this.http.get<SystemInfoResponse>(`${backendUrl}/system/info`).subscribe({
      next: (data) => {
        this.info = data;
        this.loading = false;
        this.lastRefreshed = new Date();
      },
      error: (err) => {
        this.error = err?.message || 'Failed to load system information';
        this.loading = false;
      }
    });
  }

  formatBytes(bytes: number | undefined): string {
    if (bytes == null || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return `${value.toFixed(1)} ${units[unit]}`;
  }

  memoryUsedPercent(): number {
    const max = this.info?.runtime?.maxMemory;
    const free = this.info?.runtime?.freeMemory;
    const total = this.info?.runtime?.totalMemory;
    if (!max || !total) return 0;
    const used = total - (free || 0);
    return Math.round((used / max) * 100);
  }

  get memoryUsedBytes(): number {
    const total = this.info?.runtime?.totalMemory || 0;
    const free = this.info?.runtime?.freeMemory || 0;
    return total - free;
  }

  formatUptime(seconds: number | undefined): string {
    if (seconds == null || seconds < 0) return 'N/A';
    const d = Math.floor(seconds / 86400);
    const h = Math.floor((seconds % 86400) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    const parts: string[] = [];
    if (d > 0) parts.push(`${d}d`);
    if (h > 0) parts.push(`${h}h`);
    if (m > 0) parts.push(`${m}m`);
    parts.push(`${s}s`);
    return parts.join(' ');
  }

  installedToolEntries(): Array<{ name: string; installed: boolean }> {
    if (!this.info?.installedTools) return [];
    return Object.entries(this.info.installedTools).map(([name, installed]) => ({ name, installed }));
  }

  versionFields(): Array<{ label: string; value: string }> {
    if (!this.info) return [];
    const v = this.info.version;
    return [
      { label: 'App Version', value: v?.app || 'N/A' },
      { label: 'Spring Boot', value: v?.springBoot || 'N/A' },
      { label: 'Java Version', value: v?.java || 'N/A' },
      { label: 'Operating System', value: v?.os || 'N/A' },
      { label: 'Architecture', value: v?.arch || 'N/A' }
    ];
  }
}
