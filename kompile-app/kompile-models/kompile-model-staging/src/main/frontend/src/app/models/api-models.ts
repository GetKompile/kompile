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
export type ModelType = 'dense_encoder' | 'sparse_encoder' | 'cross_encoder' | 'encoder'
  | 'ocr_detection' | 'ocr_recognition' | 'ocr_table' | 'layout_model' | 'ocr_pipeline'
  | 'document_classifier' | 'vlm_pipeline' | 'llm_ggml';

/**
 * Model status in the registry.
 */
export type ModelStatus = 'active' | 'available' | 'staged' | 'pending' | 'failed' | 'deprecated';

/**
 * Staging status for models being processed.
 */
export type StagingStatus = 'pending' | 'downloading' | 'converting' | 'validating' | 'optimizing' | 'ready' | 'promoting' | 'completed' | 'failed';

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
  // Pipeline identity fields
  encoderType?: string;
  ragRole?: string;
  version?: string;
  // OCR-specific fields
  inputHeight?: number;
  inputWidth?: number;
  supportedLanguages?: string[];
  supportsBatch?: boolean;
  maxBatchSize?: number;
  supportsHandwriting?: boolean;
  averageAccuracy?: number;
  ocrVocabSize?: number;
  usesCtc?: boolean;
  // VLM-specific fields
  visionFrames?: number;
  imageSize?: number;
  tileSize?: number;
  components?: string[];
  // Optimization tracking
  optimized?: boolean;
  optimized_at?: string;
  optimization_time_ms?: number;
  applied_optimizations?: string[];
  optimization_stats?: OptimizationStatsData;
  optimization_config?: SavedOptimizationConfig;
  // Benchmark
  benchmark_result?: BenchmarkResult;
  // Vision encoder IO config (auto-probed, user-overridable)
  vision_encoder_pixel_values_name?: string;
  vision_encoder_pixel_attention_mask_name?: string;
  vision_encoder_primary_output_name?: string;
  vision_encoder_output_names?: string[];
}

export interface BenchmarkResult {
  throughput_tok_per_sec?: number;
  latency_p99_ms?: number;
  token_diversity?: number;
  structure_valid?: boolean;
  baseline_model?: string;
  throughput_delta_percent?: number;
  regression?: boolean;
  benchmarked_at?: string;
}

export interface OptimizationStatsData {
  ops_before?: number;
  ops_after?: number;
  vars_before?: number;
  vars_after?: number;
  size_before_bytes?: number;
  size_after_bytes?: number;
  reduction_percent?: number;
}

export interface SavedOptimizationConfig {
  enabled_passes?: string[];
  preset?: string;
  quantization_type?: string;
  quantize_per_channel?: boolean;
  max_iterations?: number;
}

// ==================== Optimization Pass Registry ====================

export interface OptimizationSubPass {
  id: string;
  name: string;
  description: string;
  category: 'CLEANUP' | 'FUSION' | 'GPU' | 'QUANTIZATION';
  icon: string;
}

export interface OptimizationPassDetail {
  id: string;
  name: string;
  description: string;
  category: 'CLEANUP' | 'FUSION' | 'GPU' | 'QUANTIZATION';
  icon: string;
  subPasses?: OptimizationSubPass[];
}

/**
 * Hierarchical registry of all SameDiff GraphOptimizer passes.
 * Each group contains individual sub-passes that can be selected independently.
 */
export const OPTIMIZATION_PASS_GROUPS: OptimizationPassDetail[] = [
  {
    id: 'dead_code_elimination', name: 'Dead Code Elimination',
    description: 'Removes operations whose outputs are never used by any downstream computation',
    category: 'CLEANUP', icon: 'delete_sweep',
    subPasses: [
      { id: 'RemoveUnusedConstants', name: 'Remove Unused Constants', description: 'Removes constant variables not consumed by any operation', category: 'CLEANUP', icon: 'delete_sweep' }
    ]
  },
  {
    id: 'constant_folding', name: 'Constant Folding',
    description: 'Pre-computes operations where all inputs are constants',
    category: 'CLEANUP', icon: 'compress',
    subPasses: [
      { id: 'FoldConstantFunctions', name: 'Fold Constant Functions', description: 'Executes ops with all-constant inputs at optimization time and replaces with result', category: 'CLEANUP', icon: 'compress' }
    ]
  },
  {
    id: 'algebraic_simplification', name: 'Algebraic Simplification',
    description: 'Simplifies algebraic expressions such as x*1=x, x+0=x, x*0=0',
    category: 'CLEANUP', icon: 'functions',
    subPasses: [
      { id: 'AddZero', name: 'Add Zero', description: 'Simplifies x+0 to x', category: 'CLEANUP', icon: 'functions' },
      { id: 'SubtractZero', name: 'Subtract Zero', description: 'Simplifies x-0 to x', category: 'CLEANUP', icon: 'functions' },
      { id: 'MultiplyOne', name: 'Multiply One', description: 'Simplifies x*1 to x', category: 'CLEANUP', icon: 'functions' },
      { id: 'MultiplyZero', name: 'Multiply Zero', description: 'Simplifies x*0 to 0', category: 'CLEANUP', icon: 'functions' },
      { id: 'SubtractSelf', name: 'Subtract Self', description: 'Simplifies x-x to 0', category: 'CLEANUP', icon: 'functions' },
      { id: 'DivideOne', name: 'Divide One', description: 'Simplifies x/1 to x', category: 'CLEANUP', icon: 'functions' }
    ]
  },
  {
    id: 'identity_removal', name: 'Identity Removal',
    description: 'Removes identity (no-op) operations that pass through values unchanged',
    category: 'CLEANUP', icon: 'remove_circle_outline',
    subPasses: [
      { id: 'RemoveIdentityPermute', name: 'Remove Identity Permute', description: 'Removes permute(0,1,2,...) that don\'t change order', category: 'CLEANUP', icon: 'remove_circle_outline' },
      { id: 'RemoveIdentityOps', name: 'Remove Identity Ops', description: 'Removes identity(x) operations and rewires inputs', category: 'CLEANUP', icon: 'remove_circle_outline' }
    ]
  },
  {
    id: 'shape_fusion', name: 'Shape Fusion',
    description: 'Fuses consecutive shape manipulation operations into single operations',
    category: 'FUSION', icon: 'view_in_ar',
    subPasses: [
      { id: 'FuseChainedPermutes', name: 'Fuse Chained Permutes', description: 'Combines consecutive permute operations', category: 'FUSION', icon: 'view_in_ar' },
      { id: 'FuseChainedReshapes', name: 'Fuse Chained Reshapes', description: 'Combines consecutive reshape operations', category: 'FUSION', icon: 'view_in_ar' },
      { id: 'FuseChainedConcatOps', name: 'Fuse Chained Concats', description: 'Combines consecutive concat operations', category: 'FUSION', icon: 'view_in_ar' }
    ]
  },
  {
    id: 'activation_fusion', name: 'Activation Fusion',
    description: 'Fuses activation function patterns into single optimized operations',
    category: 'FUSION', icon: 'merge_type',
    subPasses: [
      { id: 'FuseSigmoidMulToSwish', name: 'Fuse Sigmoid*Mul → Swish', description: 'Detects sigmoid(x)*x and replaces with swish', category: 'FUSION', icon: 'merge_type' },
      { id: 'FuseSwiGLUPattern', name: 'Fuse SwiGLU Pattern', description: 'Detects SwiGLU gating pattern and replaces with fused op', category: 'FUSION', icon: 'merge_type' }
    ]
  },
  {
    id: 'normalization_fusion', name: 'Normalization Fusion',
    description: 'Fuses normalization sub-graphs into single fused operations',
    category: 'FUSION', icon: 'tune',
    subPasses: [
      { id: 'FuseRMSNormPattern', name: 'Fuse RMSNorm', description: 'Detects RMS normalization sub-graph and replaces with fused op', category: 'FUSION', icon: 'tune' },
      { id: 'FuseMeanSquarePattern', name: 'Fuse Mean Square', description: 'Detects mean-square pattern and replaces with fused op', category: 'FUSION', icon: 'tune' }
    ]
  },
  {
    id: 'linear_fusion', name: 'Linear Fusion',
    description: 'Fuses matmul + bias add into a single fused linear op',
    category: 'FUSION', icon: 'linear_scale',
    subPasses: [
      { id: 'FuseMatMulWithAdd', name: 'Fuse MatMul + Add', description: 'Fuses matmul followed by add into xw_plus_b', category: 'FUSION', icon: 'linear_scale' },
      { id: 'FuseTensorMmulWithAdd', name: 'Fuse TensorMmul + Add', description: 'Fuses tensor matmul followed by add', category: 'FUSION', icon: 'linear_scale' },
      { id: 'FuseConsecutiveReshapes', name: 'Fuse Consecutive Reshapes', description: 'Merges consecutive reshapes in linear layers', category: 'FUSION', icon: 'linear_scale' }
    ]
  },
  {
    id: 'attention_fusion', name: 'Attention Fusion',
    description: 'Detects and fuses multi-head attention patterns into optimized ops',
    category: 'FUSION', icon: 'hub',
    subPasses: [
      { id: 'FuseManualAttentionPattern', name: 'Fuse Manual Attention', description: 'Detects Q*K^T/sqrt(d)*V and replaces with DotProductAttention', category: 'FUSION', icon: 'hub' },
      { id: 'FuseAttentionWithProjection', name: 'Fuse Attention + Projection', description: 'Fuses attention output with linear projection', category: 'FUSION', icon: 'hub' },
      { id: 'FuseAttentionWithCausalMask', name: 'Fuse Attention + Causal Mask', description: 'Fuses attention with causal mask application', category: 'FUSION', icon: 'hub' },
      { id: 'FuseAttentionWithMask', name: 'Fuse Attention + Mask', description: 'Fuses attention with arbitrary mask', category: 'FUSION', icon: 'hub' },
      { id: 'CollectMultiHeadAttention', name: 'Collect Multi-Head Attention', description: 'Collects split Q/K/V heads into multi-head op', category: 'FUSION', icon: 'hub' },
      { id: 'FuseLLaMAAttentionBlock', name: 'Fuse LLaMA Attention', description: 'Fuses LLaMA-style attention block', category: 'FUSION', icon: 'hub' }
    ]
  },
  {
    id: 'cudnn_replacement', name: 'cuDNN Replacement',
    description: 'Replaces compatible operations with cuDNN-accelerated implementations',
    category: 'GPU', icon: 'memory',
    subPasses: [
      { id: 'CudnnConv2dNCHWtoNHWCConversion', name: 'cuDNN Conv2D NCHW→NHWC', description: 'Converts Conv2D layout for Tensor Core acceleration', category: 'GPU', icon: 'memory' }
    ]
  },
  {
    id: 'quantization', name: 'Quantization',
    description: 'Converts floating-point weights to lower-precision types (INT8/FP16)',
    category: 'QUANTIZATION', icon: 'speed',
    subPasses: [
      { id: 'QuantizeConstantsToFP16', name: 'Quantize to FP16', description: 'Converts constant weights to FP16 half-precision', category: 'QUANTIZATION', icon: 'speed' },
      { id: 'QuantizeConstantsToINT8', name: 'Quantize to INT8', description: 'Converts constant weights to INT8 with scale factors', category: 'QUANTIZATION', icon: 'speed' },
      { id: 'FuseDequantizeQuantizePair', name: 'Fuse Dequant-Quant Pair', description: 'Removes redundant dequantize→quantize pairs', category: 'QUANTIZATION', icon: 'speed' },
      { id: 'RemoveRedundantCasts', name: 'Remove Redundant Casts', description: 'Removes unnecessary dtype cast operations', category: 'QUANTIZATION', icon: 'speed' },
      { id: 'OptimizeConstantsForInference', name: 'Optimize Constants', description: 'Optimizes constant storage for inference', category: 'QUANTIZATION', icon: 'speed' },
      { id: 'QuantizePlaceholder', name: 'Quantize Placeholder', description: 'Adds quantization for placeholder inputs', category: 'QUANTIZATION', icon: 'speed' }
    ]
  }
];

/** Flat lookup map for pass IDs (groups + sub-passes). */
export const OPTIMIZATION_PASSES: { [key: string]: OptimizationPassDetail | OptimizationSubPass } = (() => {
  const map: { [key: string]: OptimizationPassDetail | OptimizationSubPass } = {};
  for (const group of OPTIMIZATION_PASS_GROUPS) {
    map[group.id] = group;
    if (group.subPasses) {
      for (const sub of group.subPasses) {
        map[sub.id] = sub;
      }
    }
  }
  return map;
})();

export function resolvePassDetail(passId: string): OptimizationPassDetail {
  return OPTIMIZATION_PASSES[passId] || {
    id: passId,
    name: passId.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').trim(),
    description: 'Optimization pass',
    category: 'CLEANUP' as const,
    icon: 'settings'
  };
}

export function getCategoryColor(category: string): string {
  switch (category) {
    case 'CLEANUP': return '#2196f3';
    case 'FUSION': return '#4caf50';
    case 'GPU': return '#ff9800';
    case 'QUANTIZATION': return '#9c27b0';
    default: return '#757575';
  }
}

export interface GraphAnalysis {
  graphDepth: number;
  parameterCount: number;
  parametersByDataType?: { [key: string]: number };
  constantCount: number;
  layerGroups?: LayerGroup[];
  attentionHeads: number;
  hasAttentionFusion: boolean;
  hasLinearFusion: boolean;
  fusedOpCount: number;
  memoryEstimateBytes: number;
}

export interface LayerGroup {
  name: string;
  count: number;
  opsPerGroup: number;
  opTypes?: string[];
}

export interface GraphInfoResponse {
  modelId: string;
  totalOps: number;
  totalVariables: number;
  opTypes?: { [key: string]: number };
  inputNames?: string[];
  outputNames?: string[];
  ops?: GraphOpInfo[];
  modelSizeBytes?: number;
  analysis?: GraphAnalysis;
}

export interface GraphOpInfo {
  name: string;
  opType: string;
  inputs?: string[];
  outputs?: string[];
}

export interface StageWithOptimizationRequest {
  autoPromote?: boolean;
  optimizationConfig?: AutoOptimizationConfigDto;
}

export interface AutoOptimizationConfigDto {
  enabledPasses?: string[];
  preset?: string;
  quantizationType?: string;
  quantizePerChannel?: boolean;
  maxIterations?: number;
}

/**
 * Image preprocessor configuration for VLM models.
 * Controls how input images are transformed before the vision encoder.
 */
export interface ImagePreprocessorConfig {
  image_processor_type?: string;
  // Resize
  do_resize: boolean;
  size_height?: number;
  size_width?: number;
  size_shortest_edge?: number;
  size_longest_edge?: number;
  resample?: number;
  // Rescale
  do_rescale: boolean;
  rescale_factor: number;
  // Normalize
  do_normalize: boolean;
  image_mean?: number[];
  image_std?: number[];
  // Color
  do_convert_rgb: boolean;
  // Center crop
  do_center_crop: boolean;
  crop_size_height?: number;
  crop_size_width?: number;
  // Padding
  do_pad: boolean;
  pad_size_height?: number;
  pad_size_width?: number;
  // Patch (ViT)
  patch_size?: number;
  num_channels: number;
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
  preprocessor?: ImagePreprocessorConfig;
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
    maxSequenceLength?: number;
    description: string;
    trainingData?: string;
    framework?: string;
    encoderType?: string;
    ragRole?: string;
    version?: string;
    // OCR fields
    inputHeight?: number;
    inputWidth?: number;
    supportedLanguages?: string[];
    supportsBatch?: boolean;
    maxBatchSize?: number;
    supportsHandwriting?: boolean;
    averageAccuracy?: number;
    ocrVocabSize?: number;
    usesCtc?: boolean;
    // VLM fields
    visionFrames?: number;
    imageSize?: number;
    tileSize?: number;
    components?: string[];
    visionEncoderOutputNames?: string[];
    visionEncoderPrimaryOutputName?: string;
    // Optimization
    optimized?: boolean;
    optimized_at?: string;
    optimization_time_ms?: number;
    applied_optimizations?: string[];
    optimization_stats?: OptimizationStatsData;
    optimization_config?: SavedOptimizationConfig;
  };
  modelType?: string;
  installed?: boolean;
  /** True when a SameDiff .fb/.sdz file exists — model can be GraphOptimizer'd */
  optimizable?: boolean;
  status?: string;
  path?: string;
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
  vlm: CatalogModel[];
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
  bytes_downloaded?: number;
  total_bytes?: number;
  bytes_per_second?: number;
  current_file?: string;
}

/**
 * Format bytes to human-readable string.
 */
export function formatBytes(bytes: number): string {
  if (bytes <= 0) return '0 B';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
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
    case 'available':
    case 'completed':
    case 'ready':
      return 'status-success';
    case 'failed':
      return 'status-error';
    case 'pending':
    case 'downloading':
    case 'converting':
    case 'validating':
    case 'optimizing':
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
    case 'available':
      return 'check';
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
    case 'optimizing':
      return 'bolt';
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
    case 'ocr_detection':
      return 'crop_free';
    case 'ocr_recognition':
      return 'text_fields';
    case 'ocr_table':
      return 'table_chart';
    case 'layout_model':
      return 'dashboard';
    case 'ocr_pipeline':
      return 'document_scanner';
    case 'document_classifier':
      return 'category';
    case 'vlm_pipeline':
      return 'visibility';
    case 'llm_ggml':
      return 'smart_toy';
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
    case 'ocr_detection':
      return 'OCR Detection';
    case 'ocr_recognition':
      return 'OCR Recognition';
    case 'ocr_table':
      return 'OCR Table';
    case 'layout_model':
      return 'Layout Model';
    case 'ocr_pipeline':
      return 'OCR Pipeline';
    case 'document_classifier':
      return 'Document Classifier';
    case 'vlm_pipeline':
      return 'Vision-Language Model';
    case 'llm_ggml':
      return 'LLM (GGML)';
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
    case 'ocr_detection':
      return 'Detects text regions in images';
    case 'ocr_recognition':
      return 'Recognizes text from detected regions';
    case 'ocr_table':
      return 'Extracts table structure from documents';
    case 'layout_model':
      return 'Analyzes document layout structure';
    case 'ocr_pipeline':
      return 'End-to-end OCR processing pipeline';
    case 'document_classifier':
      return 'Classifies document types';
    case 'vlm_pipeline':
      return 'Vision-language understanding and generation';
    case 'llm_ggml':
      return 'Text generation language model';
    default:
      return '';
  }
}

/**
 * Get the RAG pipeline role description for a model type.
 */
export function getModelTypeRole(type: ModelType): string {
  switch (type) {
    case 'dense_encoder':
    case 'encoder':
      return 'Embedding & Retrieval';
    case 'sparse_encoder':
      return 'Sparse Retrieval';
    case 'cross_encoder':
      return 'Reranking';
    case 'ocr_detection':
      return 'OCR Detection';
    case 'ocr_recognition':
      return 'OCR Recognition';
    case 'ocr_table':
      return 'Table Extraction';
    case 'layout_model':
      return 'Layout Analysis';
    case 'ocr_pipeline':
      return 'OCR Pipeline';
    case 'document_classifier':
      return 'Document Classification';
    case 'vlm_pipeline':
      return 'Vision-Language';
    case 'llm_ggml':
      return 'Text Generation';
    default:
      return type;
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

// ==================== Dataset Types ====================

export type DatasetFormat = 'JSONL' | 'CSV' | 'PARQUET' | 'TXT' | 'TEXT' | 'ARROW';

export type DatasetTask = 'CAUSAL_LM' | 'SEQ2SEQ' | 'CLASSIFICATION' | 'TEXT_CLASSIFICATION' | 'QUESTION_ANSWERING' | 'SUMMARIZATION' | 'TRANSLATION' | 'PREFERENCE' | 'INSTRUCTION' | 'OTHER';

export interface DatasetStats {
  trainSamples: number;
  valSamples: number;
  avgTokenLength: number;
  maxTokenLength: number;
}

export interface DatasetInfo {
  id: string;
  name: string;
  description?: string;
  format: DatasetFormat;
  task: DatasetTask;
  stats?: DatasetStats;
  sampleCount?: number;
  totalSamples?: number;
  sizeBytes?: number;
  createdAt?: string;
  path?: string;
}

export interface DatasetUploadConfig {
  name: string;
  description?: string;
  format: DatasetFormat;
  task: DatasetTask;
  splitRatio?: number;
  inputColumn?: string;
  outputColumn?: string;
  chosenColumn?: string;
  rejectedColumn?: string;
  trainSplit?: number;
}

// ==================== Training Types ====================

export interface TrainingJobStatus {
  jobId: string;
  modelId?: string;
  datasetId?: string;
  status: string;
  currentEpoch?: number;
  totalEpochs?: number;
  currentStep?: number;
  totalSteps?: number;
  loss?: number;
  learningRate?: number;
  overallProgress?: number;
  elapsedMs?: number;
  error?: string;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
}

export type LrSchedule = 'COSINE' | 'LINEAR' | 'CONSTANT' | 'POLYNOMIAL';

export type UpdaterType = 'ADAM' | 'SGD' | 'ADAMW' | 'RMSPROP';

export interface TrainingLogEntry {
  timestamp: string;
  level: string;
  message: string;
  step?: number;
  loss?: number;
}

export interface TrainingMetricsSnapshot {
  step: number;
  epoch: number;
  loss?: number;
  trainLoss: number;
  learningRate: number;
  gradNorm?: number;
  evalLoss?: number;
  evalAccuracy?: number;
  tokensPerSecond?: number;
  samplesPerSecond: number;
  timestamp?: string;
}

export interface TrainingConfigRequest {
  modelId: string;
  datasetId: string;
  epochs: number;
  batchSize: number;
  learningRate?: number;
  lrSchedule?: LrSchedule;
  warmupRatio?: number;
  loggingSteps?: number;
  saveSteps?: number;
  evalSteps?: number;
  seed?: number;
  maxSteps?: number;
  maxGradNorm?: number;
  gradientAccumulationSteps?: number;
  fp16?: boolean;
  bf16?: boolean;
}

// ==================== PEFT Types ====================

export type PeftType = 'LORA' | 'QLORA' | 'ADALORA' | 'DYLORA' | 'DORA' | 'IA3' | 'PREFIX_TUNING' | 'PROMPT_TUNING';

export interface PeftConfigRequest {
  peftType: PeftType;
  baseModelId: string;
  loraConfig?: {
    rank: number;
    alpha: number;
    dropout: number;
    bias: string;
    initMethod: string;
  };
}

// ==================== Alignment Types ====================

export type AlignmentAlgorithm = 'DPO' | 'KTO' | 'ORPO' | 'PPO' | 'GRPO';

export interface AlignmentConfigRequest {
  algorithm: AlignmentAlgorithm;
  baseModelId: string;
  rewardModelId?: string;
  datasetId: string;
  beta: number;
  labelSmoothness: number;
  maxPromptLength: number;
  maxCompletionLength: number;
  trainingConfig?: any;
  peftConfig?: any;
}

// ==================== Distillation Types ====================

export type DistillationType = 'LOGIT_KD' | 'FEATURE_KD' | 'ATTENTION_KD' | 'COMBINED' | 'KNOWLEDGE_DISTILLATION' | 'LOGIT_DISTILLATION' | 'FEATURE_DISTILLATION';

export interface DistillationConfigRequest {
  distillationType: DistillationType;
  teacherModelId: string;
  studentModelId: string;
  datasetId: string;
  temperature: number;
  alpha: number;
  trainingConfig?: any;
  peftConfig?: any;
}

// ==================== Evaluation Types ====================

export interface EvaluationRequest {
  modelId: string;
  datasetId?: string;
  evaluationType?: string;
  metrics?: string[];
  batchSize?: number;
  maxSamples?: number;
}

export interface EvaluationResult {
  modelId: string;
  datasetId?: string;
  metrics: { [key: string]: number };
  evaluationType: string;
  evaluationTimeMs?: number;
  samplesEvaluated?: number;
  completedAt?: string;
  duration?: number;
  error?: string;
}

// ==================== Optimization Types ====================

export type OptimizationTypeId = string;

export type QuantizationTypeId = string;

export interface OptimizationType {
  id: string;
  name: string;
  displayName: string;
  description: string;
  category: string;
  enabled: boolean;
  isDefault?: boolean;
}

export interface QuantizationType {
  id: string;
  name: string;
  displayName: string;
  description: string;
  bitsPerWeight: number;
}

export interface OptimizationPreset {
  id: string;
  name: string;
  description: string;
  optimizations: string[];
  quantizationType?: string;
}

export type OptimizationCategory = string;

export interface OptimizationDetails {
  id?: string;
  name?: string;
  description?: string;
  category?: string;
  parameters?: any;
  optimized?: boolean;
  optimizedAt?: string;
  optimizationTimeMs?: number;
  appliedOptimizations?: string[];
  stats?: {
    ops_before?: number;
    ops_after?: number;
    vars_before?: number;
    vars_after?: number;
    size_before_bytes?: number;
    size_after_bytes?: number;
    reduction_percent?: number;
  };
  hasBackup?: boolean;
  backupFile?: string;
}

export interface ConfigurableOptimizeRequest {
  modelId?: string;
  optimizations?: string[];
  enabledOptimizations?: string[];
  dryRun?: boolean;
  preset?: string;
  quantizationType?: string;
  createBackup?: boolean;
  force?: boolean;
}

export interface ConfigurableOptimizeResult {
  success: boolean;
  modelId?: string;
  optimizationsApplied?: string[];
  appliedOptimizations?: string[];
  message?: string;
  error?: string;
  beforeSize?: number;
  afterSize?: number;
  optimizationTimeMs?: number;
  stats?: {
    ops_before?: number;
    ops_after?: number;
    vars_before?: number;
    vars_after?: number;
    size_before_bytes?: number;
    size_after_bytes?: number;
    reduction_percent?: number;
  };
  backupFile?: string;
}

export interface ComparisonResult {
  success: boolean;
  modelId?: string;
  match?: boolean;
  outputsMatch?: boolean;
  maxAbsoluteDifference?: number;
  meanAbsoluteDifference?: number;
  speedupFactor?: number;
  originalResult?: {
    inferenceTimeMs?: number;
    numOps?: number;
    numVars?: number;
    modelSizeBytes?: number;
  };
  optimizedResult?: {
    inferenceTimeMs?: number;
    numOps?: number;
    numVars?: number;
    modelSizeBytes?: number;
  };
  differences?: any[];
  error?: string;
}

export interface RestoreResult {
  success: boolean;
  modelId?: string;
  message?: string;
  restoredPath?: string;
  error?: string;
}

// ==================== OCR Types ====================

export interface OcrConfigRequest {
  imageUrl?: string;
  imagePath?: string;
  engineType?: string;
  language?: string;
  confidenceThreshold?: number;
  format?: string;
}

export interface OcrTextRegion {
  text: string;
  confidence: number;
  boundingBox: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
}

export interface OcrRecognizeResponse {
  success: boolean;
  text: string;
  regions?: OcrTextRegion[];
  processingTimeMs?: number;
  error?: string;
}

// ==================== VLM Types ====================

export interface VlmExtractionType {
  id: string;
  name?: string;
  description?: string;
  defaultModelSetName?: string;
}

export interface VlmPipelineStage {
  id: string;
  name?: string;
  displayName?: string;
  description?: string;
  requiresModel?: boolean;
  modelComponentKey?: string;
  inputDescription?: string;
  outputDescription?: string;
}

export interface VlmModelSetComponent {
  fileName: string;
  componentKey: string;
  pipelineStage?: string;
  inputShape?: string;
  outputShape?: string;
}

export interface VlmModelSet {
  id?: string;
  setId: string;
  name?: string;
  displayName?: string;
  description?: string;
  architecture?: string;
  inputSize?: number;
  hiddenSize?: number;
  models?: { [role: string]: string };
  components?: VlmModelSetComponent[];
  status?: string;
  cached?: boolean;
}

export interface VlmModelSetsStatus {
  sets?: VlmModelSet[];
  modelSets: { [setId: string]: boolean };
  activeSetId?: string;
}

export interface VlmServiceStatus {
  loaded: boolean;
  modelId?: string;
  status: string;
  memoryUsage?: number;
  cacheStats?: {
    cacheDirectory?: string;
    totalSizeBytes?: number;
    modelCount?: number;
  };
}

export interface VlmPreset {
  id: string;
  name: string;
  description?: string;
  models?: { [role: string]: string };
  enabledExtractions?: string[];
}

export interface VlmPresetModels {
  presets: VlmPreset[];
}

export interface VlmDownloadStatus {
  modelId?: string;
  status: string;
  progress?: number;
  complete?: boolean;
  success?: boolean;
  message?: string;
  currentComponent?: string;
  componentProgress?: number;
  error?: string;
}

export interface VlmEnsureModelsResponse {
  success: boolean;
  message?: string;
  failures?: string[];
  downloadStatuses?: VlmDownloadStatus[];
}

export interface VlmGenerateRequest {
  prompt: string;
  imagePath?: string;
  imageUrl?: string;
  maxTokens?: number;
  temperature?: number;
  tilingEnabled?: boolean;
  maxTiles?: number;
  modelSetId?: string;
}

export interface VlmGenerateResponse {
  success?: boolean;
  text?: string;
  generatedText?: string;
  tokens?: number;
  processingTimeMs?: number;
  finishReason?: string;
  error?: string;
  metrics?: {
    processingTimeMs?: number;
    tokensGenerated?: number;
    deviceUsed?: string;
    tilesUsed?: number;
  };
}

export interface DocTagsParseRequest {
  text?: string;
  rawDocTags?: string;
  format?: string;
}

export interface DocTagsParseResponse {
  success: boolean;
  structure?: any;
  elements?: any[];
  structuredElements?: any[];
  markdown?: string;
  html?: string;
  error?: string;
}

export interface TileInfo {
  index: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface TilingPreviewResponse {
  success?: boolean;
  tiles?: TileInfo[];
  totalTiles?: number;
  tileCount?: number;
  gridRows?: number;
  gridCols?: number;
  tileWidth?: number;
  tileHeight?: number;
  originalWidth?: number;
  originalHeight?: number;
  imageWidth?: number;
  imageHeight?: number;
  error?: string;
}

// ==================== Training Helper Functions ====================

export function getTrainingStatusColor(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'status-success';
    case 'FAILED':
      return 'status-error';
    case 'RUNNING':
    case 'TRAINING':
      return 'status-pending';
    case 'PENDING':
    case 'QUEUED':
      return 'status-info';
    case 'CANCELLED':
      return 'status-warning';
    default:
      return '';
  }
}

export function getTrainingStatusIcon(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'check_circle';
    case 'FAILED':
      return 'error';
    case 'RUNNING':
    case 'TRAINING':
      return 'sync';
    case 'PENDING':
    case 'QUEUED':
      return 'hourglass_empty';
    case 'CANCELLED':
      return 'cancel';
    default:
      return 'help';
  }
}

// ==================== Optimization Helper Functions ====================

export function getOptimizationCategoryName(category: string): string {
  switch (category) {
    case 'graph':
      return 'Graph';
    case 'memory':
      return 'Memory';
    case 'compute':
      return 'Compute';
    default:
      return category;
  }
}

export function getOptimizationCategoryIcon(category: string): string {
  switch (category) {
    case 'graph':
      return 'account_tree';
    case 'memory':
      return 'memory';
    case 'compute':
      return 'speed';
    default:
      return 'tune';
  }
}

// ==================== Experiment Tracking Types ====================

export type ExperimentStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface Experiment {
  id: string;
  name: string;
  description?: string;
  suiteId: string;
  datasetId?: string;
  status: ExperimentStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface ExperimentWithRuns extends Experiment {
  runs: ExperimentRun[];
}

export interface ExperimentRun {
  id: string;
  experimentId: string;
  modelId: string;
  modelVariant?: string;
  modelType?: string;
  suiteResultId?: string;
  status: ExperimentStatus;
  passRate?: number;
  averageScore?: number;
  passedCount?: number;
  failedCount?: number;
  totalCount?: number;
  startedAt?: string;
  completedAt?: string;
  executionTimeMs?: number;
  errorMessage?: string;
}

export interface EvalDataset {
  id: string;
  name: string;
  description?: string;
  suiteId: string;
  format: string;
  sampleCount?: number;
  version?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface DatasetRow {
  id: string;
  name: string;
  query: string;
  expectedAnswer?: string;
}

export interface ExperimentComparison {
  experimentId: string;
  runCount: number;
  runs: RunComparisonEntry[];
  bestModelId?: string;
  bestAverageScore?: number;
}

export interface RunComparisonEntry {
  runId: string;
  modelId: string;
  modelVariant?: string;
  modelType?: string;
  passRate?: number;
  averageScore?: number;
  passedCount?: number;
  failedCount?: number;
  totalCount?: number;
  executionTimeMs?: number;
  completedAt?: string;
  scoresByType?: { [key: string]: number };
}

export function getExperimentStatusColor(status: ExperimentStatus): string {
  switch (status) {
    case 'COMPLETED':
      return 'status-success';
    case 'FAILED':
      return 'status-error';
    case 'RUNNING':
      return 'status-pending';
    case 'PENDING':
      return 'status-info';
    default:
      return '';
  }
}

export function getExperimentStatusIcon(status: ExperimentStatus): string {
  switch (status) {
    case 'COMPLETED':
      return 'check_circle';
    case 'FAILED':
      return 'error';
    case 'RUNNING':
      return 'sync';
    case 'PENDING':
      return 'hourglass_empty';
    default:
      return 'help';
  }
}
