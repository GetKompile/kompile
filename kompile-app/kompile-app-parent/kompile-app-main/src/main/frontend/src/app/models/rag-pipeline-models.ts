export interface EmbeddingStageConfig {
  modelId: string;
  modelSource?: string;
  archiveId?: string;
  dimensions?: number;
}

export interface RetrievalStageConfig {
  strategy: 'SEMANTIC' | 'KEYWORD' | 'HYBRID';
  topK: number;
  similarityThreshold: number;
}

export interface RerankingStageConfig {
  enabled: boolean;
  rerankerType: string;
  crossEncoderModel?: string;
  crossEncoderModelSource?: string;
  crossEncoderArchiveId?: string;
  topK: number;
  mmrLambda: number;
}

export interface LlmStageConfig {
  provider: string;
  model?: string;
  systemPrompt?: string;
  temperature: number;
  maxTokens: number;
}

export interface RagPipelineDefinition {
  id: string;
  name: string;
  description?: string;
  builtin: boolean;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  embedding?: EmbeddingStageConfig;
  retrieval?: RetrievalStageConfig;
  reranking?: RerankingStageConfig;
  llm?: LlmStageConfig;
}

export interface ModelRequirement {
  stage: string;
  modelId: string;
  status: 'ready' | 'available' | 'staging' | 'missing';
  source: string;
}

export interface PipelineModelStatus {
  pipelineId: string;
  requirements: ModelRequirement[];
  allModelsReady: boolean;
}

export interface RagPipelineResult {
  pipelineId: string;
  pipelineName: string;
  status: 'completed' | 'error';
  response?: string;
  context?: string;
  documentCount: number;
  durationMs: number;
  errorMessage?: string;
}
