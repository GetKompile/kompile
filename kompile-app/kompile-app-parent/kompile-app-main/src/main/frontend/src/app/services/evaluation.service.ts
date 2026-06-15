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
  EvaluationConfig,
  AvailableEvaluators,
  EvaluationType,
  ToggleResponse
} from '../models/rag-management.models';

/**
 * Service for managing RAG evaluation configuration.
 */
@Injectable({
  providedIn: 'root'
})
export class EvaluationService {
  private readonly baseUrl = `${backendUrl}/evaluation`;

  constructor(private http: HttpClient) {}

  /**
   * Gets the current evaluation configuration.
   */
  getConfig(): Observable<EvaluationConfig> {
    return this.http.get<EvaluationConfig>(`${this.baseUrl}/config`);
  }

  /**
   * Updates the evaluation configuration.
   */
  updateConfig(config: Partial<EvaluationConfig>): Observable<EvaluationConfig> {
    return this.http.put<EvaluationConfig>(`${this.baseUrl}/config`, config);
  }

  /**
   * Gets available evaluators.
   */
  getAvailableEvaluators(): Observable<AvailableEvaluators> {
    return this.http.get<AvailableEvaluators>(`${this.baseUrl}/evaluators`);
  }

  /**
   * Gets available evaluation types with descriptions.
   */
  getEvaluationTypes(): Observable<EvaluationType[]> {
    return this.http.get<EvaluationType[]>(`${this.baseUrl}/types`);
  }

  /**
   * Toggles evaluation on or off.
   */
  toggle(enabled: boolean): Observable<ToggleResponse> {
    return this.http.post<ToggleResponse>(`${this.baseUrl}/toggle`, { enabled });
  }

  /**
   * Helper to create a default configuration object.
   */
  createDefaultConfig(): EvaluationConfig {
    return {
      available: true,
      enabled: false,
      async: true,
      defaultThreshold: 0.5,
      evaluators: {
        relevancy: { enabled: false, threshold: 0.5 },
        faithfulness: { enabled: false, threshold: 0.5 },
        answerCorrectness: {
          enabled: false,
          threshold: 0.5,
          semanticWeight: 0.5,
          factualWeight: 0.5
        },
        contextRelevancy: { enabled: false, threshold: 0.5 },
        hallucination: { enabled: false, threshold: 0.5 }
      }
    };
  }
}
