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

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface MemoryWatchdogStatus {
  enabled: boolean;
  memoryPressureDetected: boolean;
  currentMemoryUsagePercent: number;
  memoryThresholdPercent: number;
  memoryCriticalPercent: number;
  runningJobCount: number;
  jobsMarkedForStopCount: number;
  checkIntervalMs: number;
  consecutiveHighMemoryChecks: number;
  lastMemoryPressureTime?: string;
  runningJobs: WatchdogJob[];
  status: string;
}

export interface WatchdogJob {
  taskId: string;
  fileName: string;
  startTime: string;
  markedForStop: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class MemoryPoolService {
  private readonly poolUrl = `${backendUrl}/memory-pools`;
  private readonly watchdogUrl = `${backendUrl}/system/memory-watchdog`;

  constructor(private http: HttpClient) {}

  // Memory Pool endpoints
  getPoolConfig(): Observable<any> {
    return this.http.get(`${this.poolUrl}/config`);
  }

  updatePoolConfig(config: any): Observable<any> {
    return this.http.put(`${this.poolUrl}/config`, config);
  }

  resetPoolConfig(): Observable<any> {
    return this.http.post(`${this.poolUrl}/config/reset`, {});
  }

  getPoolStatus(): Observable<any> {
    return this.http.get(`${this.poolUrl}/status`);
  }

  // Memory Watchdog endpoints
  getWatchdogStatus(): Observable<MemoryWatchdogStatus> {
    return this.http.get<MemoryWatchdogStatus>(this.watchdogUrl);
  }

  setWatchdogEnabled(enabled: boolean): Observable<any> {
    const params = new HttpParams().set('enabled', enabled.toString());
    return this.http.post(`${this.watchdogUrl}/enabled`, {}, { params });
  }

  setWatchdogCheckInterval(intervalMs: number): Observable<any> {
    const params = new HttpParams().set('intervalMs', intervalMs.toString());
    return this.http.post(`${this.watchdogUrl}/check-interval`, {}, { params });
  }
}
