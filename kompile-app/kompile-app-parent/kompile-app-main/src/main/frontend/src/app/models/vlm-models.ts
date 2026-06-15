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
 * VLM (Vision-Language Model) TypeScript interfaces.
 */

/**
 * Information about a VLM model set (e.g., SmolDocling, Donut, SigLIP).
 */
export interface VlmModelSet {
  setId: string;
  displayName: string;
  description: string;
  architecture: string;
  inputSize: number;
  hiddenSize: number;
  cached: boolean;
  components: VlmModelComponent[];
}

/**
 * Individual model component within a model set.
 */
export interface VlmModelComponent {
  componentKey: string;
  fileName: string;
  pipelineStage: string | null;
  inputShape: string | null;
  outputShape: string | null;
}

/**
 * Pipeline stage information.
 */
export interface VlmPipelineStage {
  id: string;
  displayName: string;
  inputDescription: string;
  outputDescription: string;
  modelComponentKey: string | null;
  requiresModel: boolean;
}

/**
 * Extraction type information.
 */
export interface VlmExtractionType {
  id: string;
  description: string;
  defaultModelSetId: string | null;
  defaultModelSetName: string | null;
}

/**
 * Configuration preset information.
 */
export interface VlmPreset {
  id: string;
  name: string;
  description: string;
  enabledExtractions: string[];
}

/**
 * Download progress status.
 */
export interface VlmDownloadStatus {
  setId: string;
  downloading: boolean;
  complete: boolean;
  success: boolean;
  cached?: boolean;
  currentComponent?: string;
  componentProgress?: number;
  componentsCompleted?: number;
  message?: string;
}

/**
 * Model sets status response.
 */
export interface VlmModelSetsStatus {
  cacheDirectory: string;
  modelSets: { [key: string]: boolean };
  readyModels: string[];
}

/**
 * VLM service status.
 */
export interface VlmServiceStatus {
  cacheStats: {
    cachedExtractors: number;
    readyModels: number;
    readyModelIds: string[];
    cacheDirectory: string;
  };
  activeDownloads: string[];
  availableModelSets: string[];
}

/**
 * Preset models response.
 */
export interface VlmPresetModels {
  presetId: string;
  requiredModelSets: VlmModelSet[];
  totalComponents: number;
}

/**
 * Ensure models response.
 */
export interface VlmEnsureModelsResponse {
  presetId: string;
  success: boolean;
  failures: string[];
  readyModels: string[];
}

/**
 * VLM benchmark iteration result.
 */
export interface VlmBenchmarkIteration {
  iteration: number;
  generateTimeMs: number;
  generatedTokens: number;
  promptTokens: number;
  tokensPerSecond: number;
}

/**
 * VLM benchmark summary.
 */
export interface VlmBenchmarkSummary {
  avgTokensPerSecond: number;
  avgGenerateTimeMs: number;
  avgGeneratedTokens: number;
  minTokensPerSecond: number;
  maxTokensPerSecond: number;
}

/**
 * VLM benchmark result.
 */
export interface VlmBenchmarkResult {
  modelId: string;
  iterations: number;
  results: VlmBenchmarkIteration[];
  summary: VlmBenchmarkSummary;
  error?: string;
}
