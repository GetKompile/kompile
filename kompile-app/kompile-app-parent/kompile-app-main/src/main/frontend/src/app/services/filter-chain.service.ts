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
  FilterChainConfig,
  FilterConfig,
  FiltersResponse,
  ToggleFilterResponse,
  ConfigUpdateResponse,
  FilterChainStatus
} from '../models/filter-chain.models';

/**
 * Service for managing filter chain configuration.
 */
@Injectable({
  providedIn: 'root'
})
export class FilterChainService {
  private readonly baseUrl = `${backendUrl}/filterchain`;

  constructor(private http: HttpClient) {}

  /**
   * Gets the current filter chain configuration.
   */
  getConfig(): Observable<FilterChainConfig> {
    return this.http.get<FilterChainConfig>(`${this.baseUrl}/config`);
  }

  /**
   * Updates the filter chain configuration.
   */
  updateConfig(config: Partial<FilterChainConfig>): Observable<ConfigUpdateResponse> {
    return this.http.put<ConfigUpdateResponse>(`${this.baseUrl}/config`, config);
  }

  /**
   * Gets the filter chain status.
   */
  getStatus(): Observable<FilterChainStatus> {
    return this.http.get<FilterChainStatus>(`${this.baseUrl}/status`);
  }

  /**
   * Gets configured and available filters.
   */
  getFilters(): Observable<FiltersResponse> {
    return this.http.get<FiltersResponse>(`${this.baseUrl}/filters`);
  }

  /**
   * Adds a new filter.
   */
  addFilter(filter: FilterConfig): Observable<ConfigUpdateResponse> {
    return this.http.post<ConfigUpdateResponse>(`${this.baseUrl}/filters`, filter);
  }

  /**
   * Updates an existing filter.
   */
  updateFilter(filterId: string, filter: FilterConfig): Observable<ConfigUpdateResponse> {
    return this.http.put<ConfigUpdateResponse>(`${this.baseUrl}/filters/${filterId}`, filter);
  }

  /**
   * Deletes a filter.
   */
  deleteFilter(filterId: string): Observable<ConfigUpdateResponse> {
    return this.http.delete<ConfigUpdateResponse>(`${this.baseUrl}/filters/${filterId}`);
  }

  /**
   * Toggles a filter's enabled state.
   */
  toggleFilter(filterId: string): Observable<ToggleFilterResponse> {
    return this.http.post<ToggleFilterResponse>(`${this.baseUrl}/filters/${filterId}/toggle`, {});
  }

  /**
   * Toggles the entire filter chain on or off.
   */
  toggleEnabled(enabled: boolean): Observable<ToggleFilterResponse> {
    return this.http.post<ToggleFilterResponse>(`${this.baseUrl}/toggle`, { enabled });
  }

  /**
   * Resets configuration to defaults.
   */
  resetConfig(): Observable<ConfigUpdateResponse> {
    return this.http.post<ConfigUpdateResponse>(`${this.baseUrl}/reset`, {});
  }

  /**
   * Helper to create a default filter configuration.
   */
  createDefaultFilter(): FilterConfig {
    return {
      id: '',
      name: '',
      type: 'LOCAL',
      enabled: true,
      priority: 100,
      phases: ['PRE_RETRIEVAL'],
      settings: {}
    };
  }

  /**
   * Helper to create a default HTTP filter configuration.
   */
  createHttpFilter(): FilterConfig {
    return {
      id: '',
      name: '',
      type: 'HTTP',
      enabled: true,
      priority: 100,
      phases: ['PRE_RETRIEVAL'],
      remoteConfig: {
        endpoint: '',
        httpMethod: 'POST',
        timeoutMs: 10000,
        retries: 1,
        retryDelayMs: 1000,
        headers: {},
        verifySsl: true
      },
      settings: {}
    };
  }

  /**
   * Helper to create a default MCP filter configuration.
   */
  createMcpFilter(): FilterConfig {
    return {
      id: '',
      name: '',
      type: 'MCP',
      enabled: true,
      priority: 100,
      phases: ['PRE_RETRIEVAL'],
      remoteConfig: {
        endpoint: '',
        mcpToolName: '',
        timeoutMs: 10000
      },
      settings: {}
    };
  }

  /**
   * Gets phase display name.
   */
  getPhaseDisplayName(phase: string): string {
    switch (phase) {
      case 'PRE_RETRIEVAL': return 'Pre-Retrieval';
      case 'POST_RETRIEVAL': return 'Post-Retrieval';
      case 'PRE_LLM': return 'Pre-LLM';
      case 'POST_LLM': return 'Post-LLM';
      default: return phase;
    }
  }

  /**
   * Gets type display name.
   */
  getTypeDisplayName(type: string): string {
    switch (type) {
      case 'LOCAL': return 'Built-in';
      case 'HTTP': return 'HTTP Remote';
      case 'MCP': return 'MCP Remote';
      default: return type;
    }
  }
}
