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
 * Model types supported by the staging service.
 *
 * - dense_encoder: Dense bi-encoders for semantic retrieval (BGE, Arctic, CosDPR)
 * - sparse_encoder: Sparse encoders for learned sparse retrieval (SPLADE, UniCOIL)
 * - cross_encoder: Neural rerankers that score query-document pairs (MiniLM, TinyBERT)
 * - encoder: Legacy type, treated as dense_encoder
 */
export type ModelType = 'dense_encoder' | 'sparse_encoder' | 'cross_encoder' | 'encoder';

/**
 * Model status in the registry.
 */
export type ModelStatus = 'active' | 'staged' | 'pending' | 'failed' | 'deprecated';

/**
 * Staging status for models being processed.
 */
export type StagingStatus = 'pending' | 'downloading' | 'converting' | 'validating' | 'ready' | 'promoting' | 'completed' | 'failed';

/**
 * Tokenizer configuration.
 */
export interface TokenizerConfig {
  do_lower_case: boolean;
  add_special_tokens: boolean;
  strip_accents: boolean;
  max_length: number;
  padding?: string;
  truncation?: boolean;
}

/**
 * Model metadata.
 */
export interface ModelMetadata {
  embeddingDim?: number;
  hiddenSize?: number;
  numLayers?: number;
  maxSequenceLength: number;
  modelType?: string;
  framework: string;
  trainingData?: string;
  sourceOrigin?: string;
  sourceRepository?: string;
  originalFormat?: string;
  conversionDate?: string;
  description?: string;
  vocabSize?: number;
}

/**
 * A single model entry in the registry.
 * Note: Properties use snake_case to match backend JSON serialization.
 */
export interface ModelEntry {
  model_id: string;
  type: ModelType;
  path: string;
  model_file: string;
  vocab_file: string;
  checksum: string;
  status: ModelStatus;
  promoted_at?: string;
  metadata: ModelMetadata;
  tokenizer: TokenizerConfig;
  // Computed fields from backend
  modelFilePath?: string;
  vocabFilePath?: string;
  active?: boolean;
}

/**
 * The model registry structure.
 * Note: Properties use snake_case to match backend JSON serialization.
 */
export interface ModelRegistry {
  version: string;
  updated_at: string;
  models: { [model_id: string]: ModelEntry };
  installed_archives?: { [archive_id: string]: any };
}

/**
 * Model info from the catalog (available for download).
 */
export interface CatalogModel {
  id: string;
  source: string;
  repo: string;
  format: string;
  files: {
    model: string;
    vocab: string;
  };
  metadata: {
    embeddingDim?: number;
    hiddenSize?: number;
    numLayers?: number;
    maxSequenceLength: number;
    description: string;
    trainingData?: string;
  };
}

/**
 * Source configuration.
 */
export interface SourceConfig {
  baseUrl: string;
  enabled: boolean;
}

/**
 * Model catalog response.
 */
export interface ModelCatalog {
  sources: { [key: string]: SourceConfig };
  encoders: CatalogModel[];
  crossEncoders: CatalogModel[];
}

/**
 * Staging model info for models being processed.
 * Note: Properties use snake_case to match backend JSON serialization.
 */
export interface StagingModelInfo {
  model_id: string;
  status: StagingStatus;
  progress: number;
  error?: string;
  source?: string;
  type?: string;
  started_at?: string;
  completed_at?: string;
  message?: string;
}

/**
 * Staging status response.
 */
export interface StagingStatusResponse {
  connected: boolean;
  modelsInStaging: StagingModelInfo[];
  registryModelCount?: number;
  stagingDir?: string;
  modelDir?: string;
}

/**
 * Request to stage a model.
 */
export interface StageModelRequest {
  modelId: string;
  source?: string;
  repository?: string;
  format?: string;
  autoPromote?: boolean;
}

/**
 * Request to promote a model.
 */
export interface PromoteModelRequest {
  modelId: string;
}

/**
 * Export request.
 */
export interface ExportRequest {
  modelIds: string[];
  outputPath: string;
  includeVocab?: boolean;
}

/**
 * Import request.
 */
export interface ImportRequest {
  bundlePath: string;
  verifyChecksums?: boolean;
}

/**
 * Export result.
 */
export interface ExportResult {
  success: boolean;
  bundlePath?: string;
  modelCount?: number;
  checksum?: string;
  error?: string;
}

/**
 * Import result.
 */
export interface ImportResult {
  success: boolean;
  modelsImported?: string[];
  modelCount?: number;
  error?: string;
}

/**
 * Generic API response.
 */
export interface ApiResponse<T> {
  status: string;
  data?: T;
  error?: string;
  message?: string;
}

// ==================== Archive Types ====================

/**
 * Version diff types for archive updates.
 */
export type VersionDiff = 'MAJOR_UPGRADE' | 'MINOR_UPGRADE' | 'PATCH_UPGRADE' | 'SAME' | 'DOWNGRADE';

/**
 * Information about an installed archive.
 */
export interface ArchiveInstallInfo {
  archiveId: string;
  version: string;
  installedAt: string;
  sourceUrl?: string;
  modelIds: string[];
}

/**
 * Archive export request.
 */
export interface ArchiveExportRequest {
  modelIds?: string[];
  outputPath?: string;
  archiveId?: string;
  version?: string;
  description?: string;
  exportAll?: boolean;
  publisherName?: string;
  publisherUrl?: string;
  minKompileVersion?: string;
  includeReadme?: boolean;
  includeChangelog?: boolean;
}

/**
 * Archive export result.
 */
export interface ArchiveExportResult {
  success: boolean;
  archivePath?: string;
  archiveId?: string;
  version?: string;
  modelCount?: number;
  archiveSize?: number;
  checksum?: string;
  error?: string;
}

/**
 * Archive import request.
 */
export interface ArchiveImportRequest {
  archivePath: string;
  verifyChecksums?: boolean;
  forceOverwrite?: boolean;
  skipCompatibilityCheck?: boolean;
}

/**
 * Archive import result.
 */
export interface ArchiveImportResult {
  success: boolean;
  archiveId?: string;
  version?: string;
  importedCount?: number;
  skippedCount?: number;
  importedModels?: string[];
  skippedModels?: string[];
  error?: string;
}

/**
 * Archive download request.
 */
export interface ArchiveDownloadRequest {
  url: string;
  destinationDir?: string;
  resumeEnabled?: boolean;
  verifyChecksum?: boolean;
  expectedChecksum?: string;
  autoImport?: boolean;
  forceOverwrite?: boolean;
}

/**
 * Archive download result.
 */
export interface ArchiveDownloadResult {
  success: boolean;
  archivePath?: string;
  totalBytes?: number;
  checksum?: string;
  durationMs?: number;
  wasResumed?: boolean;
  partialPath?: string;
  resumable?: boolean;
  import?: {
    archiveId: string;
    version: string;
    importedCount: number;
  };
  error?: string;
}

/**
 * Information about an available update.
 */
export interface UpdateInfo {
  archiveId: string;
  archiveName?: string;
  currentVersion: string;
  latestVersion?: string;
  versionDiff?: VersionDiff;
  updateAvailable: boolean;
  downloadUrl?: string;
  sizeBytes?: number;
  changelog?: string;
  mayHaveBreakingChanges?: boolean;
}

/**
 * Update check response.
 */
export interface ArchiveUpdateResponse {
  updatesAvailable: boolean;
  totalInstalled: number;
  updatesCount: number;
  majorUpdates: number;
  updates: UpdateInfo[];
}

/**
 * Update apply result.
 */
export interface UpdateApplyResult {
  success: boolean;
  archiveId: string;
  previousVersion?: string;
  newVersion?: string;
  modelsUpdated?: number;
  error?: string;
}

/**
 * Batch update result.
 */
export interface BatchUpdateResult {
  success: boolean;
  successCount: number;
  failCount: number;
  results: UpdateApplyResult[];
}

/**
 * Remote catalog entry.
 */
export interface RemoteCatalogEntry {
  archiveId: string;
  name: string;
  description?: string;
  latestVersion: string;
  modelCount: number;
  tags?: string[];
}

/**
 * Remote catalog.
 */
export interface RemoteCatalog {
  catalogVersion: string;
  lastUpdated?: string;
  baseUrl?: string;
  sourceUrl?: string;
  name?: string;
  description?: string;
  archives: RemoteCatalogEntry[];
}

/**
 * Cache status.
 */
export interface CacheStatus {
  url: string;
  fetchedAt: string;
  expired: boolean;
  archiveCount: number;
}

// ==================== Helper Functions ====================

/**
 * Get CSS class for model status.
 */
export function getStatusColor(status: ModelStatus | StagingStatus): string {
  switch (status) {
    case 'active':
    case 'completed':
    case 'ready':
      return 'status-success';
    case 'failed':
      return 'status-error';
    case 'pending':
    case 'downloading':
    case 'converting':
    case 'validating':
    case 'promoting':
    case 'staged':
      return 'status-pending';
    case 'deprecated':
      return 'status-info';
    default:
      return '';
  }
}

/**
 * Get icon for model status.
 */
export function getStatusIcon(status: ModelStatus | StagingStatus): string {
  switch (status) {
    case 'active':
    case 'completed':
      return 'check_circle';
    case 'ready':
      return 'verified';
    case 'failed':
      return 'error';
    case 'pending':
      return 'hourglass_empty';
    case 'downloading':
      return 'cloud_download';
    case 'converting':
      return 'transform';
    case 'validating':
      return 'fact_check';
    case 'promoting':
      return 'publish';
    case 'staged':
      return 'inventory';
    case 'deprecated':
      return 'archive';
    default:
      return 'help';
  }
}

/**
 * Get icon for model type.
 */
export function getModelTypeIcon(type: ModelType): string {
  switch (type) {
    case 'dense_encoder':
    case 'encoder':
      return 'hub'; // Dense vectors / embeddings
    case 'sparse_encoder':
      return 'scatter_plot'; // Sparse token weights
    case 'cross_encoder':
      return 'compare_arrows'; // Query-document comparison
    default:
      return 'memory';
  }
}

/**
 * Get display name for model type.
 */
export function getModelTypeDisplayName(type: ModelType): string {
  switch (type) {
    case 'dense_encoder':
      return 'Dense Encoder';
    case 'sparse_encoder':
      return 'Sparse Encoder';
    case 'encoder':
      return 'Encoder'; // Legacy
    case 'cross_encoder':
      return 'Cross-Encoder (Reranker)';
    default:
      return type;
  }
}

/**
 * Get a short description for model type.
 */
export function getModelTypeDescription(type: ModelType): string {
  switch (type) {
    case 'dense_encoder':
      return 'Semantic retrieval via dense vectors';
    case 'sparse_encoder':
      return 'Learned sparse retrieval (SPLADE, etc.)';
    case 'encoder':
      return 'Legacy encoder type';
    case 'cross_encoder':
      return 'Neural reranking of query-document pairs';
    default:
      return '';
  }
}

/**
 * Check if model type is for retrieval (vs reranking).
 */
export function isRetrievalType(type: ModelType): boolean {
  return type === 'dense_encoder' || type === 'sparse_encoder' || type === 'encoder';
}

/**
 * Check if model type is for reranking.
 */
export function isRerankingType(type: ModelType): boolean {
  return type === 'cross_encoder';
}
