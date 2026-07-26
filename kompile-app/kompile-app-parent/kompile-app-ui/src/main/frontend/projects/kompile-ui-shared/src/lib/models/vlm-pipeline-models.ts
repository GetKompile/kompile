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
 * Dynamic VLM Pipeline Configuration TypeScript interfaces.
 *
 * These interfaces mirror the Java POJOs in ai.kompile.modelmanager.vlm.dynamic
 * and are used for the VLM pipeline configuration REST API.
 */

// =====================================================================
// PIPELINE DEFINITIONS
// =====================================================================

/**
 * Type of pipeline execution.
 */
export type PipelineType = 'SEQUENCE' | 'GRAPH';

/**
 * Complete pipeline configuration.
 */
export interface VlmPipelineDefinition {
  pipelineId: string;
  displayName: string;
  description?: string;
  pipelineType: PipelineType;
  stages: VlmPipelineStageConfig[];
  graphNodes: { [nodeId: string]: VlmGraphNodeConfig };
  modelSetId?: string;
  extractionTypes: string[];
  defaultParameters: { [key: string]: any };
  isBuiltin: boolean;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

/**
 * Stage instance within a pipeline.
 */
export interface VlmPipelineStageConfig {
  stageId: string;
  order: number;
  enabled: boolean;
  modelOverrideId?: string;
  parameters: { [key: string]: any };
}

/**
 * Graph node for DAG-based pipelines.
 */
export interface VlmGraphNodeConfig {
  nodeId: string;
  stageId: string;
  inputs: string[];
  enabled: boolean;
  modelOverrideId?: string;
  parameters: { [key: string]: any };
  condition?: string;
}

// =====================================================================
// STAGE DEFINITIONS
// =====================================================================

/**
 * Dynamic stage definition (extends enum concept).
 */
export interface VlmStageDefinition {
  stageId: string;
  displayName: string;
  inputDescription?: string;
  outputDescription?: string;
  modelComponentKey?: string;
  requiresModel: boolean;
  isBuiltin: boolean;
  description?: string;
}

// =====================================================================
// MODEL SET DEFINITIONS
// =====================================================================

/**
 * Source of model files.
 */
export type ModelSource = 'HUGGINGFACE' | 'LOCAL' | 'CUSTOM_URL';

/**
 * Configuration for a single model component.
 */
export interface VlmModelComponentConfig {
  componentKey: string;
  fileName: string;
  downloadUrl?: string;
  pipelineStage?: string;
  description?: string;
  inputShape?: string;
  outputShape?: string;
  estimatedSizeBytes?: number;
}

/**
 * Runtime-registrable model set.
 */
export interface VlmCustomModelSet {
  setId: string;
  displayName: string;
  description?: string;
  source: ModelSource;
  huggingFaceRepo?: string;
  localPath?: string;
  components: VlmModelComponentConfig[];
  pipelineConfig: { [key: string]: any };
  isBuiltin: boolean;
  createdAt: number;
  updatedAt: number;
}

// =====================================================================
// API RESPONSE TYPES
// =====================================================================

/**
 * Pipeline list item (summary view).
 */
export interface PipelineListItem {
  pipelineId: string;
  displayName: string;
  description?: string;
  pipelineType?: string;
  isBuiltin: boolean;
  enabled: boolean;
  stageCount: number;
  extractionTypes: string[];
  modelSetId?: string;
  createdAt: number;
  updatedAt: number;
}

/**
 * Stage list item (summary view).
 */
export interface StageListItem {
  stageId: string;
  displayName: string;
  inputDescription?: string;
  outputDescription?: string;
  modelComponentKey?: string;
  requiresModel: boolean;
  isBuiltin: boolean;
}

/**
 * Model set list item (summary view).
 */
export interface ModelSetListItem {
  setId: string;
  displayName: string;
  description?: string;
  source?: string;
  huggingFaceRepo?: string;
  componentCount: number;
  isBuiltin: boolean;
  createdAt: number;
  updatedAt: number;
}

/**
 * Registry statistics.
 */
export interface VlmRegistryStats {
  totalPipelines: number;
  builtinPipelines: number;
  customPipelines: number;
  totalStages: number;
  builtinStages: number;
  customStages: number;
  totalModelSets: number;
  builtinModelSets: number;
  customModelSets: number;
  configDirectory: string;
}

/**
 * Validation response.
 */
export interface ValidationResponse {
  valid: boolean;
  errors: string[];
}

/**
 * Generic API response for mutations.
 */
export interface ApiResponse {
  success: boolean;
  message?: string;
  errors?: string[];
  pipelineId?: string;
  stageId?: string;
  setId?: string;
}

/**
 * Pipeline output cache statistics.
 */
export interface PipelineCacheStats {
  available: boolean;
  totalEntries: number;
  finalOutputEntries: number;
  stageCheckpointEntries: number;
  totalSizeBytes: number;
  totalSizeMB: number;
  hitCount: number;
  missCount: number;
  hitRate: number;
  message?: string;
}

/**
 * Cache entry list item (browsable view).
 */
export interface CacheEntryListItem {
  cacheKey: string;
  entryType: string;
  pipelineId: string;
  pipelineDisplayName?: string;
  stageId?: string;
  contentHash: string;
  contentHashShort: string;
  outputClassName?: string;
  modelSetId?: string;
  hitCount: number;
  sizeBytes: number;
  sizeMB: number;
  createdAt?: string;
  lastAccessedAt?: string;
  metadata?: { [key: string]: any };
}

/**
 * Paginated cache entries response.
 */
export interface CacheEntriesResponse {
  available: boolean;
  entries: CacheEntryListItem[];
  total: number;
  offset: number;
  limit: number;
}

/**
 * Single cache entry detail response.
 */
export interface CacheEntryDetailResponse {
  found: boolean;
  entry?: CacheEntryListItem;
  outputPreview?: string;
}

// =====================================================================
// BUILDER HELPERS
// =====================================================================

/**
 * Create an empty pipeline definition for the builder.
 */
export function createEmptyPipeline(): Partial<VlmPipelineDefinition> {
  return {
    pipelineId: '',
    displayName: '',
    description: '',
    pipelineType: 'SEQUENCE',
    stages: [],
    graphNodes: {},
    extractionTypes: [],
    defaultParameters: {},
    isBuiltin: false,
    enabled: true
  };
}

/**
 * Create an empty stage config.
 */
export function createEmptyStageConfig(stageId: string, order: number): VlmPipelineStageConfig {
  return {
    stageId,
    order,
    enabled: true,
    parameters: {}
  };
}

/**
 * Create an empty graph node config.
 */
export function createEmptyGraphNode(nodeId: string, stageId: string): VlmGraphNodeConfig {
  return {
    nodeId,
    stageId,
    inputs: [],
    enabled: true,
    parameters: {}
  };
}

/**
 * Create an empty custom stage definition.
 */
export function createEmptyStageDefinition(): Partial<VlmStageDefinition> {
  return {
    stageId: '',
    displayName: '',
    inputDescription: '',
    outputDescription: '',
    requiresModel: false,
    isBuiltin: false
  };
}

/**
 * Create an empty custom model set.
 */
export function createEmptyModelSet(): Partial<VlmCustomModelSet> {
  return {
    setId: '',
    displayName: '',
    description: '',
    source: 'HUGGINGFACE',
    components: [],
    pipelineConfig: {},
    isBuiltin: false
  };
}

/**
 * Create an empty model component config.
 */
export function createEmptyComponentConfig(): Partial<VlmModelComponentConfig> {
  return {
    componentKey: '',
    fileName: ''
  };
}
