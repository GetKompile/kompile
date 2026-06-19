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
import { BayesianInferenceResult, BayesianQueryRequest, MebnQueryRequest, MebnTypeQueryRequest, MpeResult, SensitivityRequest, SensitivityResult, WhatIfRequest } from '../models/attribution-models';

@Injectable({
  providedIn: 'root'
})
export class BayesianService extends BaseService {

  private readonly apiPath = '/attribution/bayesian';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Build a Bayesian network from seed nodes and query posteriors.
   */
  queryPosteriors(request: BayesianQueryRequest): Observable<BayesianInferenceResult> {
    return this.http.post<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/query`, request
    );
  }

  /**
   * Quick query: build network from a single node and get all posteriors.
   */
  queryFromNode(nodeId: string, maxDepth: number = 3, maxNodes: number = 100): Observable<BayesianInferenceResult> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    // nodeId is a query param, not a path segment: KG node IDs can contain '/'
    // (document paths), which Tomcat rejects as %2F in the URL path.
    return this.http.get<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/query`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * Most probable explanation: find most likely state of all variables given evidence.
   */
  mostProbableExplanation(request: BayesianQueryRequest): Observable<MpeResult> {
    return this.http.post<MpeResult>(
      `${this.backendUrl}${this.apiPath}/mpe`, request
    );
  }

  /**
   * Get network statistics without running inference.
   */
  networkStats(nodeId: string, maxDepth: number = 3, maxNodes: number = 100): Observable<Record<string, any>> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    return this.http.get<Record<string, any>>(
      `${this.backendUrl}${this.apiPath}/network/stats`, { params: params.set('nodeId', nodeId) }
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // MEBN ENDPOINTS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Run MEBN inference from seed nodes (auto-constructs MTheory from KG).
   */
  queryMebn(request: MebnQueryRequest): Observable<BayesianInferenceResult> {
    return this.http.post<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/mebn/query`, request
    );
  }

  /**
   * Quick MEBN query from a single node.
   */
  queryMebnFromNode(nodeId: string, maxDepth: number = 3, maxNodes: number = 100): Observable<BayesianInferenceResult> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    return this.http.get<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/mebn/query`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * Get MEBN structure with per-variable metadata and inference for visualization.
   */
  mebnStructure(nodeId: string, maxDepth: number = 3, maxNodes: number = 100): Observable<BayesianInferenceResult> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    return this.http.get<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/mebn/structure`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * Get MEBN theory statistics without inference.
   */
  mebnStats(nodeId: string, maxDepth: number = 3, maxNodes: number = 100): Observable<Record<string, any>> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    return this.http.get<Record<string, any>>(
      `${this.backendUrl}${this.apiPath}/mebn/stats`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * MEBN query filtered by entity type.
   */
  queryMebnByType(request: MebnTypeQueryRequest): Observable<BayesianInferenceResult> {
    return this.http.post<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/mebn/query/byType`, request
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SENSITIVITY & WHAT-IF
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Sensitivity analysis: how much does each variable influence the query posterior?
   */
  sensitivityAnalysis(request: SensitivityRequest): Observable<SensitivityResult> {
    return this.http.post<SensitivityResult>(
      `${this.backendUrl}${this.apiPath}/sensitivity`, request
    );
  }

  /**
   * Quick sensitivity for a single node.
   */
  quickSensitivity(nodeId: string, maxDepth: number = 3, maxNodes: number = 50): Observable<SensitivityResult> {
    let params = new HttpParams()
      .set('maxDepth', maxDepth.toString())
      .set('maxNodes', maxNodes.toString());

    return this.http.get<SensitivityResult>(
      `${this.backendUrl}${this.apiPath}/sensitivity`, { params: params.set('nodeId', nodeId) }
    );
  }

  /**
   * What-if query: posteriors under hypothetical evidence.
   */
  whatIfQuery(request: WhatIfRequest): Observable<BayesianInferenceResult> {
    return this.http.post<BayesianInferenceResult>(
      `${this.backendUrl}${this.apiPath}/whatif`, request
    );
  }
}
