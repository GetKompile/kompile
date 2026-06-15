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

/**
 * SameDiff LLM Pipeline TypeScript interfaces.
 */

/**
 * Model role/type in the pipeline.
 */
export type SameDiffLLMModelRole = 'tokenizer' | 'embed_tokens' | 'decoder' | 'token_decoder';

/**
 * Model architecture type.
 */
export type SameDiffLLMArchitecture = 'smollm' | 'phi' | 'gemma' | 'llama' | 'mistral' | 'qwen' | 'custom';

/**
 * Individual model component within a model set.
 */
export interface SameDiffLLMModelComponent {
  componentKey: string;
  fileName: string;
  role: SameDiffLLMModelRole;
  architecture: SameDiffLLMArchitecture;
  inputShape?: string;
  outputShape?: string;
  metadata?: { [key: string]: any };
}

/**
 * Information about a SameDiff LLM model set.
 */
export interface SameDiffLLMModelSet {
  setId: string;
  displayName: string;
  description: string;
  architecture: SameDiffLLMArchitecture;
  vocabSize: number;
  hiddenSize: number;
  numLayers: number;
  numHeads: number;
  contextLength: number;
  cached: boolean;
  components: SameDiffLLMModelComponent[];
  metadata?: { [key: string]: any };
}

/**
 * Pipeline stage information.
 */
export interface SameDiffLLMPipelineStage {
  id: string;
  displayName: string;
  inputDescription: string;
  outputDescription: string;
  modelComponentKey: string | null;
  requiresModel: boolean;
}

/**
 * Configuration preset information.
 */
export interface SameDiffLLMPreset {
  id: string;
  name: string;
  description: string;
  modelSetId: string;
  maxNewTokens: number;
  temperature: number;
  topK: number;
  enableToolCalling: boolean;
}

/**
 * Download progress status.
 */
export interface SameDiffLLMDownloadStatus {
  setId: string;
  downloading: boolean;
  complete: boolean;
  success: boolean;
  cached?: boolean;
  currentComponent?: string;
  componentProgress?: number;
  componentsCompleted?: number;
  totalComponents?: number;
  message?: string;
}

/**
 * Model sets status response.
 */
export interface SameDiffLLMModelSetsStatus {
  cacheDirectory: string;
  modelSets: { [key: string]: boolean };
  readyModels: string[];
}

/**
 * Service status.
 */
export interface SameDiffLLMServiceStatus {
  cacheStats: {
    cachedModels: number;
    readyModels: number;
    readyModelIds: string[];
    cacheDirectory: string;
  };
  activeDownloads: string[];
  availableModelSets: string[];
}

/**
 * Pipeline configuration request.
 */
export interface SameDiffLLMPipelineConfig {
  pipelineId: string;
  modelSetId: string;
  maxNewTokens: number;
  temperature: number;
  topK: number;
  enableToolCalling: boolean;
  toolCallFormat?: string;
  toolChoice?: string;
  specificToolName?: string;
  customComponents?: {
    embedTokensUri?: string;
    decoderUri?: string;
    tokenizerUri?: string;
  };
}

/**
 * Pipeline execution request.
 */
export interface SameDiffLLMExecutionRequest {
  pipelineId: string;
  prompt: string;
  conversationHistory?: string[];
  maxNewTokens?: number;
  temperature?: number;
}

/**
 * Pipeline execution result.
 */
export interface SameDiffLLMExecutionResult {
  executionId: string;
  pipelineId: string;
  status: 'SUCCESS' | 'ERROR' | 'TOOL_CALL';
  responseText?: string;
  toolCallRequest?: {
    id: string;
    name: string;
    arguments: string;
  };
  errorMessage?: string;
  durationMs: number;
  generatedTokens?: number;
  promptTokens?: number;
  tokensPerSecond?: number;
}

/**
 * Pipeline step schema.
 */
export interface SameDiffLLMStepSchema {
  runnerClassName: string;
  name: string;
  description: string;
  inputSchema: {
    fields: Array<{
      name: string;
      type: string;
      description: string;
      required: boolean;
    }>;
  };
  outputSchema: {
    fields: Array<{
      name: string;
      type: string;
      description: string;
      required: boolean;
    }>;
  };
  parameters: Array<{
    name: string;
    type: string;
    description: string;
    required: boolean;
    defaultValue?: any;
  }>;
}
