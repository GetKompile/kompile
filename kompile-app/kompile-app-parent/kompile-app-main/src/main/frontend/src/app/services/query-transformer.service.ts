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
  QueryTransformerConfig,
  TransformerType,
  TransformerPreset,
  ToggleResponse
} from '../models/rag-management.models';

/**
 * Service for managing query transformer configuration.
 */
@Injectable({
  providedIn: 'root'
})
export class QueryTransformerService {
  private readonly baseUrl = `${backendUrl}/query-transformer`;

  constructor(private http: HttpClient) {}

  /**
   * Gets the current query transformer configuration.
   */
  getConfig(): Observable<QueryTransformerConfig> {
    return this.http.get<QueryTransformerConfig>(`${this.baseUrl}/config`);
  }

  /**
   * Updates the query transformer configuration.
   */
  updateConfig(config: Partial<QueryTransformerConfig>): Observable<QueryTransformerConfig> {
    return this.http.put<QueryTransformerConfig>(`${this.baseUrl}/config`, config);
  }

  /**
   * Toggles query transformer on or off.
   */
  toggle(enabled: boolean): Observable<ToggleResponse> {
    return this.http.post<ToggleResponse>(`${this.baseUrl}/toggle`, { enabled });
  }

  /**
   * Sets the transformer type.
   */
  setType(type: string): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/type/${type}`, {});
  }

  /**
   * Gets available transformer types with descriptions.
   */
  getTransformerTypes(): Observable<TransformerType[]> {
    return this.http.get<TransformerType[]>(`${this.baseUrl}/types`);
  }

  /**
   * Gets available presets.
   */
  getPresets(): Observable<TransformerPreset[]> {
    return this.http.get<TransformerPreset[]>(`${this.baseUrl}/presets`);
  }

  /**
   * Applies a preset configuration.
   */
  applyPreset(preset: string): Observable<QueryTransformerConfig> {
    return this.http.post<QueryTransformerConfig>(`${this.baseUrl}/preset/${preset}`, {});
  }

  /**
   * Helper to create a default configuration object.
   */
  createDefaultConfig(): QueryTransformerConfig {
    return {
      available: true,
      enabled: true,
      type: 'passthrough',
      maxQueries: 3,
      includeOriginal: true,
      systemPrompt: null,
      temperature: 0.7,
      maxTokens: 256
    };
  }
}
