/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * TypeScript models for Knowledge Graph Embeddings
 */

// ==================== Enums and Types ====================

export type KGEmbeddingAlgorithm = 'TRANSE' | 'ROTATE';

export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

// ==================== Training Job ====================

/**
 * KG Embedding training job
 */
export interface KGEmbeddingJob {
  jobId: string;
  factSheetId: number;
  algorithm: KGEmbeddingAlgorithm;
  status: JobStatus;

  // Configuration
  embeddingDim?: number;
  epochs?: number;
  learningRate?: number;
  batchSize?: number;
  margin?: number;
  negativeSamples?: number;

  // Progress
  currentEpoch?: number;
  currentLoss?: number;
  totalTriples?: number;

  // Results
  embeddingVersion?: number;
  entitiesEmbedded?: number;
  relationsEmbedded?: number;

  // Timing
  createdAt: string;
  startedAt?: string;
  completedAt?: string;

  // Error
  errorMessage?: string;
}

/**
 * Request to start training
 */
export interface TrainRequest {
  factSheetId: number;
  algorithm: string;
  embeddingDim?: number;
  epochs?: number;
  learningRate?: number;
  batchSize?: number;
  margin?: number;
  negativeSamples?: number;
}

// ==================== Embeddings ====================

/**
 * Entity with embedding
 */
export interface EntityEmbedding {
  entityId: string;
  entityName: string;
  entityType?: string;
  embedding: number[];
  algorithm?: string;
  version?: number;
  updatedAt?: string;
}

/**
 * Embedding statistics
 */
export interface EmbeddingStats {
  totalNodes: number;
  nodesWithEmbeddings: number;
  totalEdges: number;
  edgesWithEmbeddings: number;
  latestVersion?: number;
}

// ==================== Link Prediction ====================

/**
 * Triple to score
 */
export interface TripleRequest {
  factSheetId: number;
  head: string;
  relation: string;
  tail: string;
}

/**
 * Prediction request
 */
export interface PredictRequest {
  factSheetId: number;
  head?: string;
  relation?: string;
  tail?: string;
  topK?: number;
}

/**
 * Score result
 */
export interface ScoreResult {
  head: string;
  relation: string;
  tail: string;
  score: number;
}

/**
 * Embedding score for predictions
 */
export interface EmbeddingScore {
  entity: string;
  entityType?: string;
  score: number;
  rank: number;
}

// ==================== Algorithm Info ====================

/**
 * Algorithm information
 */
export interface AlgorithmInfo {
  id: string;
  displayName: string;
  description: string;
}

// ==================== Training Progress (WebSocket) ====================

/**
 * Training progress update from WebSocket
 */
export interface TrainingProgressUpdate {
  type: 'progress' | 'completed';
  jobId: string;
  epoch?: number;
  totalEpochs?: number;
  loss?: number;
  progressPercent?: number;
  status?: JobStatus;
  entitiesEmbedded?: number;
  relationsEmbedded?: number;
  errorMessage?: string;
}

// ==================== UI Helpers ====================

/**
 * Status color mapping
 */
export const JOB_STATUS_COLORS: Record<JobStatus, string> = {
  PENDING: '#9E9E9E',    // Grey
  RUNNING: '#2196F3',    // Blue
  COMPLETED: '#4CAF50',  // Green
  FAILED: '#F44336',     // Red
  CANCELLED: '#FF9800'   // Orange
};

/**
 * Algorithm icons
 */
export const ALGORITHM_ICONS: Record<KGEmbeddingAlgorithm, string> = {
  TRANSE: 'trending_flat',   // Translation arrow
  ROTATE: 'rotate_right'     // Rotation icon
};

/**
 * Default training configuration
 */
export const DEFAULT_TRAIN_CONFIG = {
  TRANSE: {
    embeddingDim: 100,
    epochs: 100,
    learningRate: 0.01,
    batchSize: 1024,
    margin: 1.0,
    negativeSamples: 10
  },
  ROTATE: {
    embeddingDim: 100,
    epochs: 100,
    learningRate: 0.001,
    batchSize: 512,
    margin: 6.0,
    negativeSamples: 256
  }
};

// ==================== Paginated Response ====================

/**
 * Spring Data Page wrapper
 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

// ==================== Configuration ====================

/**
 * Training configuration for a KG embedding algorithm
 */
export interface TrainConfig {
  embeddingDim: number;
  epochs: number;
  learningRate: number;
  batchSize: number;
  margin: number;
  negativeSamples: number;
}

/**
 * GraphRAG retrieval configuration
 */
export interface GraphRAGConfig {
  enabled: boolean;
  kgWeight: number;
  textWeight: number;
  expansionHops: number;
  topKEntities: number;
  defaultFactSheetId: number;
}

/**
 * Neo4j connection configuration
 */
export interface Neo4jConfig {
  enabled: boolean;
  uri: string;
  username: string;
  password: string;
}

/**
 * Connection test result
 */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
}

/**
 * Graph Building configuration (stored per FactSheet)
 */
export interface GraphBuildingConfig {
  enabled: boolean;
  builderType: GraphBuilderType;
  storageType: GraphStorageType;
  autoAccept: boolean;
  autoAcceptThreshold: number;
  entityTypes: string[];
  modelProvider: string;
  modelName: string;
  temperature: number;
  maxTokens: number;
  batchSize: number;
  customPrompt: string;
}

export type GraphBuilderType = 'manual' | 'llm' | 'pattern';
export type GraphStorageType = 'jpa' | 'neo4j';

/**
 * Default graph building configuration
 */
export const DEFAULT_GRAPH_BUILDING_CONFIG: GraphBuildingConfig = {
  enabled: false,
  builderType: 'llm',
  storageType: 'jpa',
  autoAccept: false,
  autoAcceptThreshold: 0.9,
  entityTypes: ['PERSON', 'ORGANIZATION', 'LOCATION', 'CONCEPT', 'EVENT', 'PRODUCT', 'TECHNOLOGY'],
  modelProvider: 'default',
  modelName: '',
  temperature: 0.0,
  maxTokens: 2048,
  batchSize: 10,
  customPrompt: ''
};

/**
 * Available entity types for knowledge graph extraction
 */
export const AVAILABLE_ENTITY_TYPES = [
  'PERSON',
  'ORGANIZATION',
  'LOCATION',
  'CONCEPT',
  'EVENT',
  'PRODUCT',
  'TECHNOLOGY',
  'DOCUMENT',
  'DATE',
  'QUANTITY',
  'PROCESS',
  'ATTRIBUTE'
];

/**
 * Complete KG embedding configuration
 */
export interface KGEmbeddingSettings {
  transe: TrainConfig;
  rotate: TrainConfig;
  graphrag: GraphRAGConfig;
  neo4j: Neo4jConfig;
  graphBuilding?: GraphBuildingConfig;
}
