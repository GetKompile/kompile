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

export interface ModelSchedulerConfig {
  [key: string]: any;
}

export interface ModelSchedulerStatus {
  scheduler: any;
  continuousBatcher: any;
}

@Injectable({
  providedIn: 'root'
})
export class ModelSchedulerService {
  private readonly apiUrl = `${backendUrl}/model-scheduler`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<ModelSchedulerConfig> {
    return this.http.get<ModelSchedulerConfig>(`${this.apiUrl}/config`);
  }

  updateConfig(config: ModelSchedulerConfig): Observable<ModelSchedulerConfig> {
    return this.http.put<ModelSchedulerConfig>(`${this.apiUrl}/config`, config);
  }

  resetConfig(): Observable<ModelSchedulerConfig> {
    return this.http.post<ModelSchedulerConfig>(`${this.apiUrl}/config/reset`, {});
  }

  getStatus(): Observable<ModelSchedulerStatus> {
    return this.http.get<ModelSchedulerStatus>(`${this.apiUrl}/status`);
  }
}
