/**
 * TypeScript interfaces for LLM dynamic pipeline configuration.
 * Mirrors the VLM pipeline models (vlm-pipeline-models.ts) for full feature parity.
 */

// Pipeline definition types
export type LlmPipelineType = 'SEQUENCE' | 'GRAPH';

export interface LlmPipelineDefinition {
  pipelineId: string;
  displayName: string;
  description: string;
  pipelineType: LlmPipelineType;
  modelSetId?: string;
  stages: LlmPipelineStageConfig[];
  graphNodes: LlmGraphNodeConfig[];
  defaultParameters: { [key: string]: any };
  isBuiltin: boolean;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface LlmPipelineStageConfig {
  stageId: string;
  order: number;
  enabled: boolean;
  modelOverrideId?: string;
  parameters: { [key: string]: any };
}

export interface LlmGraphNodeConfig {
  nodeId: string;
  stageId: string;
  inputs: string[];
  enabled: boolean;
  modelOverrideId?: string;
  parameters: { [key: string]: any };
  condition?: string;
}

export interface LlmStageDefinition {
  stageId: string;
  displayName: string;
  inputDescription: string;
  outputDescription: string;
  modelComponentKey?: string;
  requiresModel: boolean;
  isBuiltin: boolean;
}

export interface LlmCustomModelSet {
  setId: string;
  displayName: string;
  description: string;
  source: 'HUGGINGFACE' | 'LOCAL' | 'CUSTOM_URL';
  sourceUri?: string;
  components: any[];
  pipelineConfig: { [key: string]: any };
  createdAt?: string;
  updatedAt?: string;
}

// List/summary types for API responses
export interface PipelineListItem {
  pipelineId: string;
  displayName: string;
  description: string;
  pipelineType: LlmPipelineType;
  modelSetId?: string;
  stageCount: number;
  isBuiltin: boolean;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface StageListItem {
  stageId: string;
  displayName: string;
  inputDescription: string;
  outputDescription: string;
  modelComponentKey?: string;
  requiresModel: boolean;
  isBuiltin: boolean;
}

export interface ModelSetListItem {
  setId: string;
  displayName: string;
  description: string;
  architecture?: string;
  components: number;
  isBuiltin: boolean;
  source?: string;
}

export interface LlmRegistryStats {
  totalPipelines: number;
  builtinPipelines: number;
  customPipelines: number;
  totalStages: number;
  builtinStages: number;
  customModelSets: number;
}

export interface ValidationResponse {
  valid: boolean;
  errors: string[];
}

export interface ApiResponse<T> {
  data?: T;
  error?: string;
  details?: string[];
  message?: string;
}

export interface LlmGenerationPreset {
  id: string;
  name: string;
  description: string;
  maxNewTokens: number;
  temperature: number;
  topK: number;
  topP: number;
  repetitionPenalty: number;
  samplingStrategy: string;
  enableToolCalling: boolean;
}

// Helper functions
export function createEmptyPipeline(): LlmPipelineDefinition {
  return {
    pipelineId: '',
    displayName: '',
    description: '',
    pipelineType: 'SEQUENCE',
    stages: [],
    graphNodes: [],
    defaultParameters: {},
    isBuiltin: false,
    enabled: true
  };
}

export function createEmptyStageConfig(): LlmPipelineStageConfig {
  return {
    stageId: '',
    order: 0,
    enabled: true,
    parameters: {}
  };
}

export function createEmptyGraphNode(): LlmGraphNodeConfig {
  return {
    nodeId: '',
    stageId: '',
    inputs: [],
    enabled: true,
    parameters: {}
  };
}
