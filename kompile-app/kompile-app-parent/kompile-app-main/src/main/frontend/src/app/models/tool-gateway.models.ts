/*
 *   Copyright 2025 Kompile Inc.
 *  Licensed under the Apache License, Version 2.0
 */

export interface ToolGatewayModelConfig {
  configured: boolean;
  baseUrl: string | null;
  apiKeySet: boolean;
  modelName: string | null;
  temperature: number;
}

export interface ToolGatewayConfig {
  available: boolean;
  enabled: boolean;
  failOpen: boolean;
  evaluationTimeoutMs: number;
  verboseLogging: boolean;
  hotReload: boolean;
  dryRun: boolean;
  rulesFilePath: string;
  defaultAction: string;
  systemPrompt: string | null;
  rulesCount: number;
  enabledRulesCount: number;
  model: ToolGatewayModelConfig;
  message?: string;
}

export interface GatewayJudgeScore {
  toolName: string;
  correctness: number;
  completeness: number;
  reasoning: string;
  timestamp?: number;
}

export interface ToolGatewayRule {
  id: string;
  description: string;
  toolPatterns: string[];
  condition: string;
  action: 'ALLOW' | 'REWRITE' | 'BLOCK';
  blockMessage: string | null;
  rewriteInstructions: string | null;
  priority: number;
  enabled: boolean;
}
