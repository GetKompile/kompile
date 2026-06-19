/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  AttributionResult,
  AttributionQueryRequest,
  PredictionResult,
  PredictionQueryRequest
} from '../models/attribution-models';

@Injectable({
  providedIn: 'root'
})
export class AttributionService extends BaseService {

  private readonly apiPath = '/attribution';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Full attribution query — "Why did this happen?"
   */
  explain(request: AttributionQueryRequest): Observable<AttributionResult> {
    return this.http.post<AttributionResult>(
      `${this.backendUrl}${this.apiPath}/explain`, request
    );
  }

  /**
   * Quick attribution with defaults.
   */
  explainQuick(nodeId: string, options?: {
    question?: string;
    factSheetId?: number;
    maxDepth?: number;
    maxChains?: number;
    useLlm?: boolean;
    includeCounterfactuals?: boolean;
  }): Observable<AttributionResult> {
    let params = new HttpParams();
    if (options?.question) params = params.set('question', options.question);
    if (options?.factSheetId) params = params.set('factSheetId', options.factSheetId.toString());
    if (options?.maxDepth) params = params.set('maxDepth', options.maxDepth.toString());
    if (options?.maxChains) params = params.set('maxChains', options.maxChains.toString());
    if (options?.useLlm !== undefined) params = params.set('useLlm', options.useLlm.toString());
    if (options?.includeCounterfactuals !== undefined) params = params.set('includeCounterfactuals', options.includeCounterfactuals.toString());

    // nodeId is sent as a query param, not a path segment: document node IDs embed
    // filesystem paths ('/') that Tomcat rejects as %2F in the URL path.
    return this.http.get<AttributionResult>(
      `${this.backendUrl}${this.apiPath}/explain`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * Full prediction query — "What will happen next?"
   */
  predict(request: PredictionQueryRequest): Observable<PredictionResult> {
    return this.http.post<PredictionResult>(
      `${this.backendUrl}${this.apiPath}/predict`, request
    );
  }

  /**
   * Quick prediction with defaults.
   */
  predictQuick(nodeId: string, options?: {
    context?: string;
    factSheetId?: number;
    maxDepth?: number;
    maxPredictions?: number;
    useLlm?: boolean;
  }): Observable<PredictionResult> {
    let params = new HttpParams();
    if (options?.context) params = params.set('context', options.context);
    if (options?.factSheetId) params = params.set('factSheetId', options.factSheetId.toString());
    if (options?.maxDepth) params = params.set('maxDepth', options.maxDepth.toString());
    if (options?.maxPredictions) params = params.set('maxPredictions', options.maxPredictions.toString());
    if (options?.useLlm !== undefined) params = params.set('useLlm', options.useLlm.toString());

    // nodeId is sent as a query param, not a path segment (see explainQuick).
    return this.http.get<PredictionResult>(
      `${this.backendUrl}${this.apiPath}/predict`, { params: params.set('nodeId', nodeId) }
    );
  }
}
