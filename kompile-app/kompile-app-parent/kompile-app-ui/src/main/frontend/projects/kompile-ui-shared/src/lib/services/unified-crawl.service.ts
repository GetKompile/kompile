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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import { SingleSourceGraphEntityPreview, SingleSourceGraphRelationPreview } from '../models/api-models';

// ── Types ────────────────────────────────────────────────────────────────────

export interface UnifiedCrawlSource {
  label: string;
  sourceType: string;
  pathOrUrl: string;
  maxDepth?: number;
  maxDocuments?: number;
  includePatterns?: string[];
  excludePatterns?: string[];
  allowedContentTypes?: string[];
  properties?: { [key: string]: any };
}

export interface GraphExtractionConfig {
  enabled: boolean;
  schemaPresetId?: string;
  entityTypes?: string[];
  relationshipTypes?: string[];
  llmProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  schemaMode?: string;
  entityResolution?: boolean;
  entityResolutionSimilarityThreshold?: number;
  entityResolutionUseEmbeddings?: boolean;
  entityResolutionEmbeddingThreshold?: number;
  minConfidence?: number;
  customPrompt?: string;
  /** Whitelist of provider prefixes allowed for the extraction model (e.g. ["opencode"]). Empty = all. */
  extractionModelProviderAllow?: string[];
  /** Substrings that disqualify a model id from extraction (case-insensitive). Replaces the default ["claude","codex"]. */
  extractionModelExcludeMarkers?: string[];
}

export interface VectorIndexConfig {
  enabled: boolean;
  collectionName?: string;
  chunkerName?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  embeddingBatchSize?: number;
  maxEmbeddingBatchSize?: number;
  adaptiveBatching?: boolean;
}

export interface ProcessingBackend {
  id: string;
  displayName?: string;
  type: 'LOCAL_MODEL' | 'CLI_AGENT' | 'API_AGENT';
  priority?: number;
  maxConcurrent?: number;
  requestsPerMinute?: number;
  maxMemoryBytes?: number;
  agentName?: string;
  endpointUrl?: string;
  apiKey?: string;
  modelName?: string;
  enabled?: boolean;
  capabilities?: string[];
}

export interface ProcessingRouteConfig {
  pdfRoutingMode?: 'AUTO' | 'FORCE_VLM' | 'FORCE_TEXT' | 'DISABLED';
  fallbackEnabled?: boolean;
  backends?: ProcessingBackend[];
  vlmModelId?: string;
  extractTablesFromTextPdfs?: boolean;
  textThresholdCharsPerPage?: number;
}

export interface CapacitySnapshot {
  backendId: string;
  type: string;
  activeRequests: number;
  maxConcurrent: number;
  requestsThisMinute: number;
  requestsPerMinute: number;
  gpuMemoryUsed: number;
  gpuMemoryTotal: number;
  available: boolean;
  statusMessage: string;
}

export interface TranslationConfig {
  enabled: boolean;
  targetLanguage?: string;
  sourceLanguage?: string;
  preserveOriginal?: boolean;
  dualIndex?: boolean;
  detectionConfidenceThreshold?: number;
  maxCharsPerRequest?: number;
  domainHint?: string;
  preserveTerms?: string[];
  customInstructions?: string;
}

export interface LanguageDetectionConfig {
  enabled: boolean;
  minTextLength?: number;
  llmFallback?: boolean;
  forceLanguage?: string;
}

export interface UnicodeNormalizationConfig {
  enabled: boolean;
  form?: string;
  fixMojibake?: boolean;
  standardizeTypography?: boolean;
}

export interface PiiRedactionConfig {
  enabled: boolean;
  entityTypes?: string[];
  replacementStrategy?: string;
  useLlm?: boolean;
  logCounts?: boolean;
}

export interface BoilerplateRemovalConfig {
  enabled: boolean;
  removeWebBoilerplate?: boolean;
  removeEmailSignatures?: boolean;
  removeLegalDisclaimers?: boolean;
  customPatterns?: string[];
  minRemainingChars?: number;
}

export interface DeduplicationConfig {
  enabled: boolean;
  similarityThreshold?: number;
  strategy?: string;
  algorithm?: string;
  trackDuplicateRelations?: boolean;
}

export interface ScriptTransliterationConfig {
  enabled: boolean;
  targetScript?: string;
  sourceScript?: string;
  preserveOriginal?: boolean;
}

export interface DateNumberNormalizationConfig {
  enabled: boolean;
  dateFormat?: string;
  numberLocale?: string;
  normalizeCurrency?: boolean;
  normalizeUnits?: boolean;
}

export interface TerminologyStandardizationConfig {
  enabled: boolean;
  glossary?: { [variant: string]: string };
  glossaryFile?: string;
  caseSensitive?: boolean;
  expandAbbreviations?: boolean;
  contextAwareDisambiguation?: boolean;
}

export interface PreprocessingConfig {
  enabled: boolean;
  llmProvider?: string;
  llmModelName?: string;
  parallelism?: number;
  translation?: TranslationConfig;
  languageDetection?: LanguageDetectionConfig;
  unicodeNormalization?: UnicodeNormalizationConfig;
  scriptTransliteration?: ScriptTransliterationConfig;
  piiRedaction?: PiiRedactionConfig;
  boilerplateRemoval?: BoilerplateRemovalConfig;
  deduplication?: DeduplicationConfig;
  dateNumberNormalization?: DateNumberNormalizationConfig;
  terminologyStandardization?: TerminologyStandardizationConfig;
}

/** Per-request runtime overrides — any set field overrides the corresponding global config value. */
export interface RuntimeConfig {
  graphExtractionParallelism?: number;
  graphExtractionBatchSize?: number;
  graphExtractionTargetCharsPerBatch?: number;
  sourceLoadParallelism?: number;
  chunkingParallelism?: number;
  vectorBatchSize?: number;
  costSortChunks?: boolean;
  entityResolutionBatchSize?: number;
  edgeComputationParallelism?: number;
  vectorIndexingParallelism?: number;
  parallelVectorAndGraph?: boolean;
  llmCallTimeoutSeconds?: number;
  graphExtractionBatchTimeoutSeconds?: number;
  graphExtractionRemoteParallelism?: number;
  graphExtractionMaxItemsPerBatch?: number;
  /** null = global setting, true/false = per-crawl override of incremental content-hash skipping */
  incrementalByContentHash?: boolean;
  /** true = force a full re-crawl for this request regardless of the global flag */
  forceFullRecrawl?: boolean;
  /** true = clear the fact sheet's graph at the very start of this crawl (destructive opt-in) */
  clearGraphBeforeRun?: boolean;
}

/** ENRICHMENT-step graph hydration config (DERIVATION → PRUNE_COMPACT → HEALTH). */
export interface HydrationConfig {
  /** Stage IDs to execute. Empty = run all. Valid: DERIVATION, PRUNE_COMPACT, HEALTH. */
  enabledStageIds?: string[];
  /** Confidence threshold for pruning low-confidence inferred edges (0.0–1.0, default 0.4). */
  confidencePruneThreshold?: number;
  /** When true, stages execute without writing (dry-run). */
  dryRun?: boolean;
}

export interface UnifiedCrawlRequest {
  name: string;
  factSheetId?: number | null;
  factSheetName?: string;
  sources: UnifiedCrawlSource[];
  graphExtraction?: GraphExtractionConfig;
  vectorIndex?: VectorIndexConfig;
  processingRoute?: ProcessingRouteConfig;
  preprocessing?: PreprocessingConfig;
  hydration?: HydrationConfig;
  runtimeConfig?: RuntimeConfig;
  enabledSteps?: string[];
  archivedSteps?: string[];
  /** Max validation retries per document before marking it permanently failed (default 2). */
  maxValidationRetries?: number;
  /** Default pipeline ID when no content route rule matches (null = system default). */
  defaultPipelineId?: string;
  /** Named ingest pipeline definitions (advanced; raw IngestPipelineDefinition[]). Empty = defaults. */
  pipelines?: any[];
  /** Content routing rules directing sources to pipelines (advanced; raw ContentRouteRule[]). Empty = defaults. */
  routeRules?: any[];
  /** Present only for distributed crawls (POST /distributed-crawl/start): the coordinator partitions sources. */
  distribution?: DistributionConfig;
}

/** Distribution config for a distributed crawl — mirrors the backend UnifiedCrawlRequest.DistributionConfig. */
export interface DistributionConfig {
  partitionStrategy?: string;   // PER_SOURCE | ROUND_ROBIN (default PER_SOURCE)
  timeoutMinutes?: number;
  mergeResults?: boolean;
}

export interface PipelineStepCatalogEntry {
  id: string;
  displayName: string;
  stepType: string;
  dependsOn: string[];
  chunkConsumerOnly: boolean;
  chunkProducer: boolean;
  foundational: boolean;
  archivable: boolean;
}

export interface ResumableJobEntry {
  jobId: string;
  name: string;
  factSheetId: number | null;
  archivedSteps: string[];
  archivedAt: string;
}

export interface StartJobResponse {
  jobId: string;
  status: string;
  factSheetId?: number | null;
  sourceCount: number;
  graphExtractionEnabled: boolean;
  vectorIndexEnabled: boolean;
  message: string;
}

export interface SourceProgress {
  label: string;
  sourceType: string;
  pathOrUrl: string;
  status: string;
  documentsDiscovered: number;
  documentsLoaded: number;
  chunksCreated?: number;
  entitiesExtracted: number;
  relationshipsExtracted: number;
  currentPhase?: string;
  currentItem?: string;
  errorMessage?: string;
}

export interface PipelineStepProgress {
  stepId: string;
  displayName: string;
  stepType: string;
  status: string;
  progressPercent: number;
  totalItems: number;
  completedItems: number;
  failedItems: number;
  activeTasks: number;
  totalBatches: number;
  completedBatches: number;
  currentBatchSize: number;
  currentItem?: string;
  message?: string;
  startedAt?: string;
  completedAt?: string;
  lastUpdatedAt?: string;
  elapsedMs: number;
}

export interface DocumentGraphProgress {
  documentKey: string;
  fileName?: string;
  sourcePath?: string;
  sourceType?: string;
  contentType?: string;
  loaderName?: string;
  phase?: string;
  status?: string;
  message?: string;
  errorMessage?: string;
  chunksCreated: number;
  chunksEmbedded?: number;
  chunksIndexed?: number;
  entitiesExtracted: number;
  relationshipsExtracted: number;
  graphNodesCreated: number;
  graphEdgesCreated: number;
  extractors?: string[];
  startedAt?: string;
  updatedAt?: string;
  completedAt?: string;
}

export interface BackendRoutingStats {
  backendId: string;
  backendType: string;
  requestsDispatched: number;
  requestsCompleted: number;
  requestsFailed: number;
  requestsRerouted: number;
  inputTokens: number;
  outputTokens: number;
  estimatedCostCentsX100: number;
  emaLatencyMsX100: number;
  activeRequests: number;
  maxConcurrent: number;
  healthy: boolean;
  unhealthyReason?: string;
}

export interface RerouteEvent {
  timestamp: string;
  fromBackend: string;
  toBackend: string;
  taskType: string;
  reason: string;
  itemCount: number;
}

export interface RetryEvent {
  timestamp: string;
  stage: string;
  attempt: number;
  maxAttempts: number;
  itemCount: number;
  originalBatchSize: number;
  reducedBatchSize: number;
  failureReason: string;
  backendId?: string;
  fallbackBackendId?: string;
  backoffMs: number;
  succeeded: boolean;
  sentToDeadLetter: boolean;
}

export interface LlmCallRecord {
  timestamp: string;
  backendId: string;
  taskType: string;
  latencyMs: number;
  inputTokens: number;
  outputTokens: number;
  success: boolean;
  timedOut: boolean;
  rateLimited: boolean;
  circuitBroken: boolean;
  errorCategory?: string;
  errorMessage?: string;
  promptChars: number;
  responseChars: number;
}

export interface TuningDecision {
  timestamp: string;
  /** Which controller made the decision: GRAPH_EXTRACTION | GRAPH_EXTRACTION_CHARS | GRAPH_PARALLELISM */
  stage: string;
  oldValue: number;
  newValue: number;
  /** UP | DOWN | HOLD */
  direction: string;
  /** Canonical reason token: stable_throughput | batch_failure | memory_critical | memory_pressure |
   *  zero_yield | emergency | heap_critical | heap_recovered | at_max */
  reason: string;
  /** Free-text diagnostic, e.g. "heap 84% >= critical 82%" or "yield 0 ent / 28000 chars" */
  detail?: string;
  /** Heap usage percent (0..100) at decision time */
  memoryPercent?: number;
}

export interface JobSummary {
  jobId: string;
  /** Internal UUID used as the basis for the persisted-log taskId (crawl-<internalJobId>).
   *  Present on active/history responses where a schedulerJobId differs from the UUID. */
  internalJobId?: string;
  name: string;
  factSheetId?: number | null;
  status: string;
  sourceCount: number;
  documentsDiscovered: number;
  documentsLoaded: number;
  chunksProcessed: number;
  chunksCreated?: number;
  graphChunksProcessed?: number;
  graphChunksTotal?: number;
  chunksQueuedForEmbedding?: number;
  chunksEmbedded?: number;
  documentsIndexed: number;
  entitiesExtracted: number;
  relationshipsExtracted: number;
  filesSkippedUnchanged?: number;
  filesReprocessed?: number;
  errorCount: number;
  errors?: string[];
  errorMessage?: string;
  elapsedMs: number;
  currentPhase?: string;
  progressPercent?: number;
  queuePosition?: number;
  activeJobs?: number;
  queuedJobs?: number;
  maxConcurrentJobs?: number;
  queueCapacity?: number;
  queuedAt?: string;
  memoryUsagePercent?: number;
  peakMemoryUsagePercent?: number;
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  nativeMemoryUsagePercent?: number;
  peakNativeMemoryUsagePercent?: number;
  nativePhysicalBytes?: number;
  peakNativePhysicalBytes?: number;
  nativeTotalBytes?: number;
  nativeMaxPhysicalBytes?: number;
  directBufferBytes?: number;
  processRssBytes?: number;
  childProcessRssBytes?: number;
  embeddingSubprocessRssBytes?: number;
  otherChildProcessRssBytes?: number;
  processTreeRssBytes?: number;
  vectorBatchesTotal?: number;
  vectorBatchesCompleted?: number;
  currentBatchSize?: number;
  embeddingBatchSize?: number;
  embeddingModelOptimalBatchSize?: number;
  embeddingModelMaxBatchSize?: number;
  embeddingSingleDspPlan?: boolean;
  embeddingDspPlanBatchSize?: number;
  currentBatchStep?: string;
  currentFile?: string;
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  graphNodeCount?: number;
  graphEdgeCount?: number;
  entityTypeCounts?: { [type: string]: number };
  relationshipTypeCounts?: { [type: string]: number };
  graphExtractionEnabled?: boolean;
  vectorIndexEnabled?: boolean;
  llmProvider?: string;
  llmModel?: string;
  pipelineSteps?: PipelineStepProgress[];
  recentEvents?: CrawlStageEvent[];
  sources?: SourceProgress[];
  recentDocuments?: RecentDocument[];
  recentlyDiscoveredItems?: DiscoveredItem[];
  // Work-stealing stats
  workStealCount?: number;
  workStealFailures?: number;
  localDispatchCount?: number;
  workImbalanceRatioX100?: number;
  // Dynamic batch sizing
  adaptiveBatchSize?: number;
  batchSizeAdjustments?: number;
  lastBatchAdjustDirection?: string;
  lastBatchAdjustReason?: string;
  batchEmaLatencyMsX100?: number;
  peakThroughputX100?: number;
  // Token budget
  totalInputTokens?: number;
  totalOutputTokens?: number;
  estimatedCostCentsX100?: number;
  backendStats?: { [backendId: string]: BackendRoutingStats };
  // Workload rerouting
  reroutedItems?: number;
  droppedItems?: number;
  recentRerouteEvents?: RerouteEvent[];
  // Retry / fallback
  retriedBatches?: number;
  retriedItems?: number;
  deadLetterCount?: number;
  backendsCoolingDown?: number;
  recentRetryEvents?: RetryEvent[];
  // LLM call observability
  llmCallsTotal?: number;
  llmCallsSucceeded?: number;
  llmCallsFailed?: number;
  llmCallsTimedOut?: number;
  llmCallsRateLimited?: number;
  llmCallsCircuitBroken?: number;
  llmCallEmaLatencyMsX100?: number;
  llmCallPeakLatencyMs?: number;
  recentLlmCalls?: LlmCallRecord[];
  // Adaptive tuning decisions
  recentTuningDecisions?: TuningDecision[];
  // Entity-partition coverage: subjects that ended with a durable coverage claim, and the ones
  // that did not. An uncovered subject is one an answer drawn from this graph is unbounded about,
  // so it is named rather than counted.
  partitionsCovered?: number;
  partitionsUncovered?: number;
  uncoveredPartitionSubjects?: string[];
  partitionCoverageDetail?: string;
  fromHistory?: boolean;
}

export interface GraphSummary {
  entityCount: number;
  relationshipCount: number;
  totalNodeCount?: number;
  documentCount?: number;
  snippetCount?: number;
  tableCount?: number;
  extractedEntityCount?: number;
  extractedRelationshipCount?: number;
  entityTypeCounts?: { [type: string]: number };
  edgeTypeCounts?: { [type: string]: number };
  relationshipTypeCounts?: { [type: string]: number };
  topEntities?: { name: string; type: string; nodeId: string; connectionCount: number }[];
  live?: boolean;
}

export interface JobDetail {
  jobId: string;
  name: string;
  factSheetId?: number | null;
  status: string;
  createdAt: string;
  startedAt: string;
  completedAt: string;
  documentsDiscovered: number;
  documentsLoaded: number;
  chunksProcessed: number;
  chunksCreated?: number;
  graphChunksProcessed?: number;
  graphChunksTotal?: number;
  chunksQueuedForEmbedding?: number;
  chunksEmbedded?: number;
  documentsIndexed: number;
  entitiesExtracted: number;
  relationshipsExtracted: number;
  filesSkippedUnchanged?: number;
  filesReprocessed?: number;
  errorCount: number;
  elapsedMs: number;
  currentPhase?: string;
  progressPercent?: number;
  queuePosition?: number;
  activeJobs?: number;
  queuedJobs?: number;
  maxConcurrentJobs?: number;
  queueCapacity?: number;
  queuedAt?: string;
  memoryUsagePercent?: number;
  peakMemoryUsagePercent?: number;
  heapUsedBytes?: number;
  heapMaxBytes?: number;
  nativeMemoryUsagePercent?: number;
  peakNativeMemoryUsagePercent?: number;
  nativePhysicalBytes?: number;
  peakNativePhysicalBytes?: number;
  nativeTotalBytes?: number;
  nativeMaxPhysicalBytes?: number;
  directBufferBytes?: number;
  processRssBytes?: number;
  childProcessRssBytes?: number;
  embeddingSubprocessRssBytes?: number;
  otherChildProcessRssBytes?: number;
  processTreeRssBytes?: number;
  vectorBatchesTotal?: number;
  vectorBatchesCompleted?: number;
  currentBatchSize?: number;
  embeddingBatchSize?: number;
  embeddingModelOptimalBatchSize?: number;
  embeddingModelMaxBatchSize?: number;
  embeddingSingleDspPlan?: boolean;
  embeddingDspPlanBatchSize?: number;
  currentBatchStep?: string;
  currentFile?: string;
  errors?: string[];
  recentEvents?: CrawlStageEvent[];
  pipelineSteps?: PipelineStepProgress[];
  documentProgress?: DocumentGraphProgress[];
  // Entity-partition coverage, carried into the persisted detail so a finished crawl can still
  // say which subjects it left without a coverage claim.
  partitionsCovered?: number;
  partitionsUncovered?: number;
  uncoveredPartitionSubjects?: string[];
  partitionCoverageDetail?: string;
  errorMessage?: string;
  sources: SourceProgress[];
  recentlyDiscoveredItems?: DiscoveredItem[];
  graph?: GraphSummary;
  requestConfig?: RequestConfig;
  graphExtractionEnabled?: boolean;
  vectorIndexEnabled?: boolean;
  llmProvider?: string;
  llmModel?: string;
  // Work-stealing stats
  workStealCount?: number;
  workStealFailures?: number;
  localDispatchCount?: number;
  workImbalanceRatioX100?: number;
  // Dynamic batch sizing
  adaptiveBatchSize?: number;
  batchSizeAdjustments?: number;
  lastBatchAdjustDirection?: string;
  lastBatchAdjustReason?: string;
  batchEmaLatencyMsX100?: number;
  peakThroughputX100?: number;
  // Token budget
  totalInputTokens?: number;
  totalOutputTokens?: number;
  estimatedCostCentsX100?: number;
  backendStats?: { [backendId: string]: BackendRoutingStats };
  // Workload rerouting
  reroutedItems?: number;
  droppedItems?: number;
  recentRerouteEvents?: RerouteEvent[];
  // Retry / fallback
  retriedBatches?: number;
  retriedItems?: number;
  deadLetterCount?: number;
  backendsCoolingDown?: number;
  recentRetryEvents?: RetryEvent[];
  // LLM call observability
  llmCallsTotal?: number;
  llmCallsSucceeded?: number;
  llmCallsFailed?: number;
  llmCallsTimedOut?: number;
  llmCallsRateLimited?: number;
  llmCallsCircuitBroken?: number;
  llmCallEmaLatencyMsX100?: number;
  llmCallPeakLatencyMs?: number;
  recentLlmCalls?: LlmCallRecord[];
  // Adaptive tuning decisions
  recentTuningDecisions?: TuningDecision[];
  fromHistory?: boolean;
  /** Task ID of the job this run was resumed from (e.g. "crawl-<uuid>"). Present only when the job was resumed. */
  resumedFromTaskId?: string;
}

export interface RequestConfigSource {
  label?: string;
  sourceType?: string;
  pathOrUrl?: string;
  maxDepth?: number;
  maxDocuments?: number;
  includePatterns?: string[];
  excludePatterns?: string[];
  allowedContentTypes?: string[];
}

export interface RequestConfigGraphExtraction {
  enabled: boolean;
  llmProvider?: string;
  modelName?: string;
  entityTypes?: string[];
  relationshipTypes?: string[];
  schemaMode?: string;
  temperature?: number;
  maxTokens?: number;
  entityResolution?: boolean;
  minConfidence?: number;
  schemaPresetId?: string;
}

export interface RequestConfigVectorIndex {
  enabled: boolean;
  collectionName?: string;
  chunkerName?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  embeddingBatchSize?: number;
  maxEmbeddingBatchSize?: number;
  adaptiveBatching?: boolean;
}

export interface RequestConfig {
  factSheetId?: number | null;
  sources?: RequestConfigSource[];
  graphExtraction?: RequestConfigGraphExtraction;
  vectorIndex?: RequestConfigVectorIndex;
}

export interface DiscoveredItem {
  name: string;
  sourceType: string;
  sourceLabel: string;
  discoveredAt: string;
}

export interface RecentDocument {
  documentKey: string;
  fileName?: string;
  status?: string;
  phase?: string;
  contentType?: string;
  chunksCreated: number;
  chunksEmbedded?: number;
  entitiesExtracted: number;
  errorMessage?: string;
  updatedAt?: string;
}

export interface CrawlStageEvent {
  timestamp: string;
  phase: string;
  level: string;
  message: string;
  details?: string;
  progressPercent?: number;
}

export interface AvailableSourceType {
  type: string;
  displayName: string;
  description: string;
  available: boolean;
  requiredProperties: string[];
  optionalProperties: string[];
}

export interface SubprocessEvent {
  id: number;
  eventType: string;
  modelId?: string;
  timestamp: string;
  restartAttemptNumber?: number;
  maxRestartAttempts?: number;
  failureReason?: string;
  errorMessage?: string;
  exitCode?: number;
  backoffMs?: number;
  heapBytes?: number;
  batchSize?: number;
  threadCount?: number;
  embeddingDimensions?: number;
  encoderType?: string;
  restartSuccessful?: boolean;
  taskId?: string;
}

export interface SubprocessStatistics {
  available: boolean;
  totalCrashes: number;
  totalRestartAttempts: number;
  successfulRestarts: number;
  exhaustedRestarts: number;
  modelsLoaded: number;
  modelsFailed: number;
  restartSuccessRate: number;
}

export interface StartJobWithFilesConfig {
  name?: string;
  factSheetId?: number | null;
  graphExtraction?: GraphExtractionConfig;
  vectorIndex?: VectorIndexConfig;
  processingRoute?: ProcessingRouteConfig;
}

export interface StartJobWithFilesResponse {
  jobId: string;
  status: string;
  factSheetId?: number | null;
  sourceCount: number;
  fileNames: string[];
  graphExtractionEnabled: boolean;
  vectorIndexEnabled: boolean;
  message: string;
}

/** Snapshot of a single pipeline step returned in a SingleSourceRunResponse. */
export interface PipelineStepSnapshot {
  stepId: string;
  displayName: string;
  status: string;
  elapsedMs: number;
}

/** Request body for POST /api/unified-crawl/single-source. */
export interface SingleSourceRunRequest {
  sourceType?: string;
  label?: string;
  /** Mutually exclusive with content — path or URL to load. */
  pathOrUrl?: string;
  /** Mutually exclusive with pathOrUrl — inline text content. */
  content?: string;
  dryRun: boolean;
  /** Step IDs to run; omit to run all. */
  steps?: string[];
  factSheetId?: number;
  modelName?: string;
  /** Seconds to block waiting for the job to finish (omit for dry-run). */
  waitTimeoutSeconds?: number;
}

/** Response body from POST /api/unified-crawl/single-source. */
export interface SingleSourceRunResponse {
  dryRun: boolean;
  completed: boolean;
  jobId: string | null;
  factSheetId: number | null;
  persisted: boolean;
  status: string;
  stepsPlanned: { [stepId: string]: 'RUN' | 'SKIP' | 'ARCHIVE' } | null;
  steps: PipelineStepSnapshot[] | null;
  entityCount: number;
  relationCount: number;
  entityTypeCounts: { [k: string]: number };
  relationshipTypeCounts: { [k: string]: number };
  chunksCreated: number;
  documentsLoaded: number;
  errorCount: number;
  errors: string[];
  warnings: string[];
  sampleEntities: SingleSourceGraphEntityPreview[];
  sampleRelations: SingleSourceGraphRelationPreview[];
  elapsedMs: number;
}

// ── Service ──────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class UnifiedCrawlService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  startJob(request: UnifiedCrawlRequest): Observable<StartJobResponse> {
    return this.http.post<StartJobResponse>(`${this.backendUrl}/unified-crawl/start`, request)
      .pipe(catchError(this.handleError));
  }

  startJobWithFiles(files: File[], config?: StartJobWithFilesConfig): Observable<StartJobWithFilesResponse> {
    const formData = new FormData();
    for (const file of files) {
      formData.append('files', file, file.name);
    }
    if (config) {
      formData.append('config', JSON.stringify(config));
    }
    return this.http.post<StartJobWithFilesResponse>(`${this.backendUrl}/unified-crawl/start-with-files`, formData)
      .pipe(catchError(this.handleError));
  }

  listJobs(includeHistory = true): Observable<JobSummary[]> {
    return this.http.get<JobSummary[]>(`${this.backendUrl}/unified-crawl/jobs`,
      { params: { includeHistory: includeHistory.toString() } })
      .pipe(catchError(this.handleError));
  }

  listActiveJobs(): Observable<JobSummary[]> {
    return this.http.get<JobSummary[]>(`${this.backendUrl}/unified-crawl/jobs/active`)
      .pipe(catchError(this.handleError));
  }

  getJob(jobId: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${this.backendUrl}/unified-crawl/jobs/${jobId}`)
      .pipe(catchError(this.handleError));
  }

  getJobFromHistory(jobId: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${this.backendUrl}/unified-crawl/jobs/${jobId}/history`)
      .pipe(catchError(this.handleError));
  }

  /** SSE endpoint for live progress of a single crawl job (per-step state + rolling LLM transcript). */
  jobEventStreamUrl(jobId: string): string {
    return `${this.backendUrl}/crawl-events/stream/${jobId}`;
  }

  /** SSE endpoint for live progress of ALL crawl jobs (used by the Tools-side list monitor). */
  crawlEventsStreamUrl(): string {
    return `${this.backendUrl}/crawl-events/stream`;
  }

  cancelJob(jobId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/unified-crawl/jobs/${jobId}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  retryJob(jobId: string, retryPhase?: string, documentKeys?: string[]): Observable<any> {
    const body: any = {};
    if (retryPhase) body.retryPhase = retryPhase;
    if (documentKeys && documentKeys.length > 0) body.documentKeys = documentKeys;
    return this.http.post(`${this.backendUrl}/unified-crawl/jobs/${jobId}/retry`, body)
      .pipe(catchError(this.handleError));
  }

  cleanupJobs(): Observable<any> {
    return this.http.post(`${this.backendUrl}/unified-crawl/jobs/cleanup`, {})
      .pipe(catchError(this.handleError));
  }

  getSourceTypes(): Observable<AvailableSourceType[]> {
    return this.http.get<AvailableSourceType[]>(`${this.backendUrl}/unified-crawl/source-types`)
      .pipe(catchError(this.handleError));
  }

  getLiveGraphStats(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/unified-crawl/graph-stats`)
      .pipe(catchError(this.handleError));
  }

  getSubprocessEventsForTask(taskId: string): Observable<SubprocessEvent[]> {
    return this.http.get<SubprocessEvent[]>(`${this.backendUrl}/subprocess-events/task/${taskId}`)
      .pipe(catchError(() => {
        // Gracefully return empty if endpoint not available
        return new Observable<SubprocessEvent[]>(subscriber => {
          subscriber.next([]);
          subscriber.complete();
        });
      }));
  }

  getProcessingRouteConfig(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/unified-crawl/processing-route`)
      .pipe(catchError(() => {
        return new Observable<any>(subscriber => {
          subscriber.next({ available: false });
          subscriber.complete();
        });
      }));
  }

  updateProcessingRouteConfig(config: ProcessingRouteConfig): Observable<any> {
    return this.http.put<any>(`${this.backendUrl}/unified-crawl/processing-route`, config)
      .pipe(catchError(this.handleError));
  }

  getProcessingCapacity(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/unified-crawl/processing-capacity`)
      .pipe(catchError(() => {
        return new Observable<any>(subscriber => {
          subscriber.next({ available: false, backends: [] });
          subscriber.complete();
        });
      }));
  }

  getPdfRoutingModes(): Observable<any[]> {
    return this.http.get<any[]>(`${this.backendUrl}/unified-crawl/pdf-routing-modes`)
      .pipe(catchError(() => {
        return new Observable<any[]>(subscriber => {
          subscriber.next([]);
          subscriber.complete();
        });
      }));
  }

  getProcessingBackendTypes(): Observable<any[]> {
    return this.http.get<any[]>(`${this.backendUrl}/unified-crawl/processing-backend-types`)
      .pipe(catchError(() => {
        return new Observable<any[]>(subscriber => {
          subscriber.next([]);
          subscriber.complete();
        });
      }));
  }

  getStepCatalog(): Observable<PipelineStepCatalogEntry[]> {
    return this.http.get<PipelineStepCatalogEntry[]>(`${this.backendUrl}/unified-crawl/steps`)
      .pipe(catchError(() => {
        return new Observable<PipelineStepCatalogEntry[]>(subscriber => {
          subscriber.next([]);
          subscriber.complete();
        });
      }));
  }

  listResumableJobs(): Observable<ResumableJobEntry[]> {
    return this.http.get<ResumableJobEntry[]>(`${this.backendUrl}/unified-crawl/jobs/resumable`)
      .pipe(catchError(() => {
        return new Observable<ResumableJobEntry[]>(subscriber => {
          subscriber.next([]);
          subscriber.complete();
        });
      }));
  }

  runStep(jobId: string, stepId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/unified-crawl/jobs/${jobId}/steps/${stepId}/run`, {})
      .pipe(catchError(this.handleError));
  }

  archiveStep(jobId: string, stepId: string): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/unified-crawl/jobs/${jobId}/steps/${stepId}/archive`, {})
      .pipe(catchError(this.handleError));
  }

  getSubprocessStatistics(): Observable<SubprocessStatistics> {
    return this.http.get<SubprocessStatistics>(`${this.backendUrl}/subprocess-events/statistics`)
      .pipe(catchError(() => {
        return new Observable<SubprocessStatistics>(subscriber => {
          subscriber.next({ available: false, totalCrashes: 0, totalRestartAttempts: 0, successfulRestarts: 0, exhaustedRestarts: 0, modelsLoaded: 0, modelsFailed: 0, restartSuccessRate: 0 });
          subscriber.complete();
        });
      }));
  }

  runSingleSource(request: SingleSourceRunRequest): Observable<SingleSourceRunResponse> {
    return this.http.post<SingleSourceRunResponse>(`${this.backendUrl}/unified-crawl/single-source`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: any): Observable<never> {
    console.error('UnifiedCrawlService error:', error);
    return throwError(() => error);
  }
}
