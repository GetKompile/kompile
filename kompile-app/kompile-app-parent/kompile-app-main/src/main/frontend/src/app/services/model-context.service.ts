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
import { BehaviorSubject, Observable } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import { ModelRegistryService } from './model-registry.service';
import { ActiveModelContext } from '../models/api-models';

/**
 * Service for fetching and caching the active model context.
 * Provides visibility into which models are powering embedding, reranking, etc.
 */
@Injectable({
  providedIn: 'root'
})
export class ModelContextService extends BaseService {

  private readonly apiUrl: string;

  private contextSubject = new BehaviorSubject<ActiveModelContext | null>(null);
  public context$: Observable<ActiveModelContext | null> = this.contextSubject.asObservable();

  private loadingSubject = new BehaviorSubject<boolean>(false);
  public loading$: Observable<boolean> = this.loadingSubject.asObservable();

  constructor(
    private http: HttpClient,
    private registryService: ModelRegistryService
  ) {
    super();
    this.apiUrl = `${this.backendUrl}/models/active-context`;

    // Auto-refresh when registry changes (model loads, switches, etc.)
    this.registryService.changes$.subscribe(() => {
      this.refresh();
    });
  }

  /**
   * Fetch the active model context from the backend.
   */
  refresh(): void {
    this.loadingSubject.next(true);
    this.http.get<ActiveModelContext>(this.apiUrl).pipe(
      catchError(err => {
        console.error('Failed to fetch active model context:', err);
        this.loadingSubject.next(false);
        return [];
      })
    ).subscribe(ctx => {
      if (ctx) {
        this.contextSubject.next(ctx);
      }
      this.loadingSubject.next(false);
    });
  }

  /**
   * Get the staging model card URL for a given model ID.
   * Returns null if staging is not connected.
   */
  getStagingModelCardUrl(modelId: string): string | null {
    const ctx = this.contextSubject.value;
    if (ctx?.staging?.connected && ctx.staging.uiUrl) {
      return `${ctx.staging.uiUrl}/#/model/${modelId}`;
    }
    return null;
  }
}
