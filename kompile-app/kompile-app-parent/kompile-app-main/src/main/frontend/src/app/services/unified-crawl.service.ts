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

export interface PreprocessingConfig {
  enabled: boolean;
  llmProvider?: string;
  llmModelName?: string;
  parallelism?: number;
  translation?: TranslationConfig;
  languageDetection?: LanguageDetectionConfig;
  unicodeNormalization?: UnicodeNormalizationConfig;
  piiRedaction?: PiiRedactionConfig;
  boilerplateRemoval?: BoilerplateRemovalConfig;
  deduplication?: DeduplicationConfig;
}

export interface UnifiedCrawlRequest {
  name: string;
  factSheetId?: number | null;
  sources: UnifiedCrawlSource[];
  graphExtraction?: GraphExtractionConfig;
  vectorIndex?: VectorIndexConfig;
  processingRoute?: ProcessingRouteConfig;
  preprocessing?: PreprocessingConfig;
  enabledSteps?: string[];
  archivedSteps?: string[];
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

  private handleError(error: any): Observable<never> {
    console.error('UnifiedCrawlService error:', error);
    return throwError(() => error);
  }
}
