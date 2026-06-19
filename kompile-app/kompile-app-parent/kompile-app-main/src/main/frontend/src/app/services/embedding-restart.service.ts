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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface EmbeddingRestartStatus {
  autoRestartEnabled: boolean;
  nativeCrashThreshold: number;
  restartsPaused: boolean;
  consecutiveNativeCrashes: number;
  pausedReason: string | null;
  lastCrashReason: string | null;
  subprocessRunning: boolean;
  modelAvailable: boolean;
}

export interface EmbeddingRestartConfig {
  autoRestartEnabled: boolean;
  nativeCrashThreshold: number;
}

@Injectable({
  providedIn: 'root'
})
export class EmbeddingRestartService {
  private readonly apiUrl = `${backendUrl}/embedding-restart`;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<EmbeddingRestartStatus> {
    return this.http.get<EmbeddingRestartStatus>(`${this.apiUrl}/status`);
  }

  saveConfig(config: EmbeddingRestartConfig): Observable<EmbeddingRestartStatus> {
    return this.http.put<EmbeddingRestartStatus>(`${this.apiUrl}/config`, config);
  }

  resume(): Observable<EmbeddingRestartStatus> {
    return this.http.post<EmbeddingRestartStatus>(`${this.apiUrl}/resume`, {});
  }
}
