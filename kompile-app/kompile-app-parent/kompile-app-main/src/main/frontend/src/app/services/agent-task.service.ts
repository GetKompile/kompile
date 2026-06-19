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
import { BaseService } from './base.service';

export type KclawTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface KclawTask {
  id: string;
  engine: string;
  agentId?: string;
  task: string;
  status: KclawTaskStatus;
  output?: string;
  outputFile?: string;
  error?: string;
  channel?: string;
  channelTarget?: string;
  dbSessionId?: string;
  createdAt: number;
  startedAt: number;
  finishedAt: number;
}

export interface KclawTaskRequest {
  engine?: string;
  agentId?: string;
  task: string;
  model?: string;
  channel?: string;
  channelTarget?: string;
  async?: boolean;
}

/** Client for the kclaw task runner — POST /api/kclaw/tasks (run agent, do work, save output). */
@Injectable({ providedIn: 'root' })
export class AgentTaskService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  list(): Observable<KclawTask[]> {
    return this.http.get<KclawTask[]>(`${this.backendUrl}/kclaw/tasks`);
  }

  get(id: string): Observable<KclawTask> {
    return this.http.get<KclawTask>(`${this.backendUrl}/kclaw/tasks/${encodeURIComponent(id)}`);
  }

  output(id: string): Observable<string> {
    return this.http.get(`${this.backendUrl}/kclaw/tasks/${encodeURIComponent(id)}/output`,
      { responseType: 'text' });
  }

  submit(req: KclawTaskRequest): Observable<KclawTask> {
    return this.http.post<KclawTask>(`${this.backendUrl}/kclaw/tasks`, req);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/kclaw/tasks/${encodeURIComponent(id)}`);
  }
}
