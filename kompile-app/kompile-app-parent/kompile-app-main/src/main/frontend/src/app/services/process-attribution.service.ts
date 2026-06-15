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
  AttributionChain,
  PredictedEvent,
  AttributionResult,
  PredictionResult,
  MebnVariableMeta
} from '../models/attribution-models';

// ─── Process Attribution domain types ────────────────────────────────────

export type AlertSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';

export interface ProcessEventAlert {
  alertId: string;
  workflowRunId?: string;
  processDefinitionId?: string;
  stepId?: string;
  targetNodeId?: string;
  severity: AlertSeverity;
  alertType: string;
  title: string;
  explanation?: string;
  causalChains: AttributionChain[];
  predictions: PredictedEvent[];
  confidence: number;
  llmUsed: boolean;
  bayesianPosteriors?: Record<string, number>;
  bayesianPriors?: Record<string, number>;
  mebnMeta?: Record<string, MebnVariableMeta>;
  createdAt: string;
  acknowledged: boolean;
  acknowledgedBy?: string;
  acknowledgedAt?: string;
}

export interface StepAttributionSummary {
  stepId: string;
  stepName?: string;
  graphNodeIds: string[];
  causalChains: AttributionChain[];
  influenceScores: Record<string, number>;
  bayesianPosteriors: Record<string, number>;
  bayesianPriors: Record<string, number>;
  mebnMeta?: Record<string, MebnVariableMeta>;
  riskScore: number;
  confidenceBand?: string;
  narrative?: string;
  bayesianInferenceAvailable: boolean;
  computedAt?: string;
}

export interface ProcessRiskAssessment {
  assessmentId: string;
  workflowRunId?: string;
  processDefinitionId?: string;
  overallRiskScore: number;
  riskLevel: AlertSeverity;
  alerts: ProcessEventAlert[];
  stepRiskScores: Record<string, number>;
  highRiskStepIds: string[];
  stepAttributionResults?: Record<string, StepAttributionSummary>;
  summary?: string;
  llmUsed: boolean;
  computedAt: string;
  computationTimeMs: number;
}

export interface StepAttributionResult {
  stepId: string;
  stepName?: string;
  attribution?: AttributionResult;
  prediction?: PredictionResult;
  riskScore: number;
  hasGraphBindings: boolean;
  bayesianPosteriors?: Record<string, number>;
  bayesianPriors?: Record<string, number>;
  mebnMeta?: Record<string, MebnVariableMeta>;
}

// ─── Service ─────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class ProcessAttributionService extends BaseService {

  private readonly apiPath = '/process/attribution';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Full risk assessment for a running workflow.
   */
  assessRunRisk(runId: string, useLlm = true): Observable<ProcessRiskAssessment> {
    const params = new HttpParams().set('useLlm', useLlm.toString());
    return this.http.get<ProcessRiskAssessment>(
      `${this.backendUrl}${this.apiPath}/run/${encodeURIComponent(runId)}/risk`, { params }
    );
  }

  /**
   * Explain why a specific step failed or produced unexpected results.
   */
  explainStep(runId: string, stepId: string, useLlm = true): Observable<StepAttributionResult> {
    const params = new HttpParams().set('useLlm', useLlm.toString());
    return this.http.get<StepAttributionResult>(
      `${this.backendUrl}${this.apiPath}/run/${encodeURIComponent(runId)}/step/${encodeURIComponent(stepId)}/explain`,
      { params }
    );
  }

  /**
   * Explain why a control gate failed.
   */
  explainControlFailure(runId: string, stepId: string, controlId: string,
                        useLlm = true): Observable<ProcessEventAlert> {
    const params = new HttpParams().set('useLlm', useLlm.toString());
    return this.http.get<ProcessEventAlert>(
      `${this.backendUrl}${this.apiPath}/run/${encodeURIComponent(runId)}/step/${encodeURIComponent(stepId)}/control/${encodeURIComponent(controlId)}`,
      { params }
    );
  }

  /**
   * Pre-flight risk assessment for a process definition.
   */
  assessDefinitionRisk(defId: string, version = 1,
                       useLlm = true): Observable<ProcessRiskAssessment> {
    let params = new HttpParams()
      .set('version', version.toString())
      .set('useLlm', useLlm.toString());
    return this.http.get<ProcessRiskAssessment>(
      `${this.backendUrl}${this.apiPath}/definition/${encodeURIComponent(defId)}/risk`, { params }
    );
  }
}
