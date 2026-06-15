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
  GuardrailsConfig,
  AvailableGuardrails,
  ToggleResponse
} from '../models/rag-management.models';

/**
 * Service for managing guardrails configuration.
 */
@Injectable({
  providedIn: 'root'
})
export class GuardrailsService {
  private readonly baseUrl = `${backendUrl}/guardrails`;

  constructor(private http: HttpClient) {}

  /**
   * Gets the current guardrails configuration.
   */
  getConfig(): Observable<GuardrailsConfig> {
    return this.http.get<GuardrailsConfig>(`${this.baseUrl}/config`);
  }

  /**
   * Updates the guardrails configuration.
   */
  updateConfig(config: Partial<GuardrailsConfig>): Observable<GuardrailsConfig> {
    return this.http.put<GuardrailsConfig>(`${this.baseUrl}/config`, config);
  }

  /**
   * Gets available guardrails (input and output).
   */
  getAvailableGuardrails(): Observable<AvailableGuardrails> {
    return this.http.get<AvailableGuardrails>(`${this.baseUrl}/available`);
  }

  /**
   * Toggles guardrails on or off.
   */
  toggle(enabled: boolean): Observable<ToggleResponse> {
    return this.http.post<ToggleResponse>(`${this.baseUrl}/toggle`, { enabled });
  }

  /**
   * Helper to create a default configuration object.
   */
  createDefaultConfig(): GuardrailsConfig {
    return {
      available: true,
      enabled: false,
      maxRetries: 2,
      input: {
        promptInjection: { enabled: false, threshold: 0.7 },
        toxicity: { enabled: false, threshold: 0.7, categories: [] },
        pii: {
          enabled: false,
          detectEmail: true,
          detectPhone: true,
          detectSsn: true,
          detectCreditCard: true,
          blockOnDetection: true
        },
        topic: { enabled: false, allowedTopics: [], blockedTopics: [] }
      },
      output: {
        hallucination: { enabled: false, threshold: 0.7, supportsRetry: true },
        format: { enabled: false, expectedFormat: null, maxLength: 0, minLength: 0 },
        relevancy: { enabled: false, threshold: 0.5, supportsRetry: true }
      }
    };
  }
}
