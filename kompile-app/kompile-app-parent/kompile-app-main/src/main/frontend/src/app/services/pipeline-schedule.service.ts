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

export interface ScheduleInfo {
  scheduleId: string;
  type: string;
  cron: string;
  factSheetId?: number;
  suiteId?: string;
}

@Injectable({
  providedIn: 'root'
})
export class PipelineScheduleService {
  private readonly apiUrl = `${backendUrl}/schedules`;

  constructor(private http: HttpClient) {}

  listSchedules(): Observable<ScheduleInfo[]> {
    return this.http.get<ScheduleInfo[]>(this.apiUrl);
  }

  createStalenessCheck(cron: string, factSheetId: number): Observable<ScheduleInfo> {
    return this.http.post<ScheduleInfo>(`${this.apiUrl}/staleness-check`, { cron, factSheetId });
  }

  createReIngestion(cron: string, factSheetId: number): Observable<ScheduleInfo> {
    return this.http.post<ScheduleInfo>(`${this.apiUrl}/re-ingestion`, { cron, factSheetId });
  }

  createEvalSuite(cron: string, suiteId: string): Observable<ScheduleInfo> {
    return this.http.post<ScheduleInfo>(`${this.apiUrl}/eval-suite`, { cron, suiteId });
  }

  deleteSchedule(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
