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
import { Observable, BehaviorSubject } from 'rxjs';
import { tap } from 'rxjs/operators';

/**
 * Staging service configuration.
 */
export interface StagingServiceConfig {
  id?: number;
  name: string;
  endpointUrl: string;
  apiKey?: string;
  active: boolean;
  verified: boolean;
  lastVerifiedAt?: string;
  lastError?: string;
  connectionTimeoutMs: number;
  readTimeoutMs: number;
  autoSync: boolean;
  syncIntervalMinutes: number;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * DTO for creating/updating configurations.
 */
export interface StagingServiceConfigDto {
  name: string;
  endpointUrl: string;
  apiKey?: string;
  active?: boolean;
  connectionTimeoutMs?: number;
  readTimeoutMs?: number;
  autoSync?: boolean;
  syncIntervalMinutes?: number;
  description?: string;
}

/**
 * Connection test result.
 */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
  modelCount: number;
  version: string;
}

/**
 * Service for managing staging service configurations.
 */
@Injectable({
  providedIn: 'root'
})
export class StagingConfigService {

  private baseUrl: string;
  private configsSubject = new BehaviorSubject<StagingServiceConfig[]>([]);
  private activeConfigSubject = new BehaviorSubject<StagingServiceConfig | null>(null);
  private loadingSubject = new BehaviorSubject<boolean>(false);

  configs$ = this.configsSubject.asObservable();
  activeConfig$ = this.activeConfigSubject.asObservable();
  loading$ = this.loadingSubject.asObservable();

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/staging-config`;
    } else {
      this.baseUrl = '/api/staging-config';
    }
  }

  /**
   * Load all configurations.
   */
  loadConfigs(): Observable<StagingServiceConfig[]> {
    this.loadingSubject.next(true);
    return this.http.get<StagingServiceConfig[]>(`${this.baseUrl}/configs`).pipe(
      tap({
        next: (configs) => {
          this.configsSubject.next(configs);
          const active = configs.find(c => c.active);
          this.activeConfigSubject.next(active || null);
          this.loadingSubject.next(false);
        },
        error: () => this.loadingSubject.next(false)
      })
    );
  }

  /**
   * Get a specific configuration.
   */
  getConfig(id: number): Observable<StagingServiceConfig> {
    return this.http.get<StagingServiceConfig>(`${this.baseUrl}/configs/${id}`);
  }

  /**
   * Get the active configuration.
   */
  getActiveConfig(): Observable<StagingServiceConfig> {
    return this.http.get<StagingServiceConfig>(`${this.baseUrl}/configs/active`);
  }

  /**
   * Create a new configuration.
   */
  createConfig(config: StagingServiceConfigDto): Observable<StagingServiceConfig> {
    return this.http.post<StagingServiceConfig>(`${this.baseUrl}/configs`, config).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Update an existing configuration.
   */
  updateConfig(id: number, config: StagingServiceConfigDto): Observable<StagingServiceConfig> {
    return this.http.put<StagingServiceConfig>(`${this.baseUrl}/configs/${id}`, config).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Delete a configuration.
   */
  deleteConfig(id: number): Observable<any> {
    return this.http.delete(`${this.baseUrl}/configs/${id}`).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Set a configuration as active.
   */
  activateConfig(id: number): Observable<StagingServiceConfig> {
    return this.http.post<StagingServiceConfig>(`${this.baseUrl}/configs/${id}/activate`, {}).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Test connection to a specific configuration.
   */
  testConnection(id: number): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.baseUrl}/configs/${id}/test`, {}).pipe(
      tap(() => this.loadConfigs().subscribe())
    );
  }

  /**
   * Test connection to the active configuration.
   */
  testActiveConnection(): Observable<ConnectionTestResult> {
    return this.http.post<ConnectionTestResult>(`${this.baseUrl}/test`, {});
  }

  // ==================== Remote Staging Operations ====================

  /**
   * Get the model registry from the remote staging service.
   */
  getRemoteRegistry(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/registry`);
  }

  /**
   * Get the model catalog from the remote staging service.
   */
  getRemoteCatalog(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/catalog`);
  }

  /**
   * Get staging status from the remote staging service.
   */
  getRemoteStagingStatus(): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/status`);
  }

  /**
   * Stage a model on the remote staging service.
   */
  stageRemoteModel(modelId: string, autoPromote: boolean = false): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/stage/${modelId}?autoPromote=${autoPromote}`, {});
  }

  /**
   * Promote a staged model on the remote staging service.
   */
  promoteRemoteModel(modelId: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/remote/promote/${modelId}`, {});
  }

  /**
   * Get a specific model from the remote registry.
   */
  getRemoteModel(modelId: string): Observable<any> {
    return this.http.get(`${this.baseUrl}/remote/model/${modelId}`);
  }

  /**
   * Refresh the configuration list.
   */
  refresh(): void {
    this.loadConfigs().subscribe();
  }
}
