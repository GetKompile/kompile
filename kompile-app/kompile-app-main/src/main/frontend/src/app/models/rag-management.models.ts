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

// ==================== Guardrails Models ====================

export interface GuardrailsConfig {
  available: boolean;
  enabled: boolean;
  maxRetries: number;
  input: InputGuardrailsConfig;
  output: OutputGuardrailsConfig;
  message?: string;
}

export interface InputGuardrailsConfig {
  promptInjection: PromptInjectionConfig;
  toxicity: ToxicityConfig;
  pii: PiiConfig;
  topic: TopicConfig;
}

export interface OutputGuardrailsConfig {
  hallucination: HallucinationConfig;
  format: FormatConfig;
  relevancy: RelevancyConfig;
}

export interface PromptInjectionConfig {
  enabled: boolean;
  threshold: number;
}

export interface ToxicityConfig {
  enabled: boolean;
  threshold: number;
  categories: string[];
}

export interface PiiConfig {
  enabled: boolean;
  detectEmail: boolean;
  detectPhone: boolean;
  detectSsn: boolean;
  detectCreditCard: boolean;
  blockOnDetection: boolean;
}

export interface TopicConfig {
  enabled: boolean;
  allowedTopics: string[];
  blockedTopics: string[];
}

export interface HallucinationConfig {
  enabled: boolean;
  threshold: number;
  supportsRetry: boolean;
}

export interface FormatConfig {
  enabled: boolean;
  expectedFormat: string | null;
  maxLength: number;
  minLength: number;
}

export interface RelevancyConfig {
  enabled: boolean;
  threshold: number;
  supportsRetry: boolean;
}

export interface GuardrailInfo {
  name: string;
  categories: string[];
  priority: number;
  requiresLlm: boolean;
  supportsRetry?: boolean;
}

export interface AvailableGuardrails {
  available: boolean;
  inputGuardrails: GuardrailInfo[];
  outputGuardrails: GuardrailInfo[];
  message?: string;
}

// ==================== Evaluation Models ====================

export interface EvaluationConfig {
  available: boolean;
  enabled: boolean;
  async: boolean;
  defaultThreshold: number;
  evaluators: EvaluatorsConfig;
  message?: string;
}

export interface EvaluatorsConfig {
  relevancy: EvaluatorConfig;
  faithfulness: EvaluatorConfig;
  answerCorrectness: AnswerCorrectnessConfig;
  contextRelevancy: EvaluatorConfig;
  hallucination: EvaluatorConfig;
}

export interface EvaluatorConfig {
  enabled: boolean;
  threshold: number;
}

export interface AnswerCorrectnessConfig extends EvaluatorConfig {
  semanticWeight: number;
  factualWeight: number;
}

export interface EvaluatorInfo {
  name: string;
  type: string;
}

export interface AvailableEvaluators {
  available: boolean;
  serviceEnabled: boolean;
  evaluators: EvaluatorInfo[];
  message?: string;
}

export interface EvaluationType {
  type: string;
  description: string;
}

// ==================== Query Transformer Models ====================

export interface QueryTransformerConfig {
  available: boolean;
  enabled: boolean;
  type: string;
  maxQueries: number;
  includeOriginal: boolean;
  systemPrompt: string | null;
  temperature: number;
  maxTokens: number;
  message?: string;
}

export interface TransformerType {
  type: string;
  name: string;
  description: string;
  requiresLlm: boolean;
}

export interface TransformerPreset {
  preset: string;
  name: string;
  description: string;
  type: string;
}

// ==================== Common Response Types ====================

export interface ToggleResponse {
  success: boolean;
  enabled: boolean;
  message?: string;
}

export interface ConfigUpdateResponse {
  success: boolean;
  message?: string;
}
