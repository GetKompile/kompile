/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';
import { ToolGatewayConfig, ToolGatewayRule, GatewayJudgeScore } from '../models/tool-gateway.models';
import { ToggleResponse } from '../models/rag-management.models';

@Injectable({
  providedIn: 'root'
})
export class ToolGatewayService {
  private readonly baseUrl = `${backendUrl}/tool-gateway`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<ToolGatewayConfig> {
    return this.http.get<ToolGatewayConfig>(`${this.baseUrl}/config`);
  }

  updateConfig(config: Partial<ToolGatewayConfig>): Observable<ToolGatewayConfig> {
    return this.http.put<ToolGatewayConfig>(`${this.baseUrl}/config`, config);
  }

  toggle(enabled: boolean): Observable<ToggleResponse> {
    return this.http.post<ToggleResponse>(`${this.baseUrl}/toggle`, { enabled });
  }

  getRules(): Observable<ToolGatewayRule[]> {
    return this.http.get<ToolGatewayRule[]>(`${this.baseUrl}/rules`);
  }

  addRule(rule: ToolGatewayRule): Observable<any> {
    return this.http.post(`${this.baseUrl}/rules`, rule);
  }

  deleteRule(ruleId: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/rules/${ruleId}`);
  }

  reloadRules(): Observable<any> {
    return this.http.post(`${this.baseUrl}/rules/reload`, {});
  }

  getScores(): Observable<GatewayJudgeScore[]> {
    return this.http.get<GatewayJudgeScore[]>(`${this.baseUrl}/scores`);
  }

  createDefaultConfig(): ToolGatewayConfig {
    return {
      available: true,
      enabled: false,
      failOpen: true,
      evaluationTimeoutMs: 10000,
      verboseLogging: false,
      hotReload: false,
      dryRun: false,
      rulesFilePath: '',
      defaultAction: 'ALLOW',
      systemPrompt: null,
      rulesCount: 0,
      enabledRulesCount: 0,
      model: {
        configured: false,
        baseUrl: null,
        apiKeySet: false,
        modelName: null,
        temperature: 0.0
      }
    };
  }
}
