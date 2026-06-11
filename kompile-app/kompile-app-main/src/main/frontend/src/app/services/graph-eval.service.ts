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
import {
  GraphEvalStatus,
  GraphEvalRequest,
  GraphEvalResponse
} from '../models/graph-eval.models';

@Injectable({
  providedIn: 'root'
})
export class GraphEvalService {
  private readonly baseUrl = `${backendUrl}/graph-eval`;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<GraphEvalStatus> {
    return this.http.get<GraphEvalStatus>(`${this.baseUrl}/status`);
  }

  runEvaluation(request: GraphEvalRequest): Observable<GraphEvalResponse> {
    return this.http.post<GraphEvalResponse>(`${this.baseUrl}/run`, request);
  }

  evaluateOnly(request: any): Observable<GraphEvalResponse> {
    return this.http.post<GraphEvalResponse>(`${this.baseUrl}/evaluate`, request);
  }
}
