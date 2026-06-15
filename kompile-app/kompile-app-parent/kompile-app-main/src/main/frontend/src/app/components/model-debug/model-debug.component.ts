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

import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { interval, Subscription, Subject } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { WebSocketService, WebSocketConnectionState } from '../../services/websocket.service';
import { ArchiveService } from '../../services/archive.service';
import { LocalRegistryService, EmbeddingModelStatus } from '../../services/local-registry.service';
import { ModelRegistryService } from '../../services/model-registry.service';
import {
  CpuInfo,
  MemoryInfo,
  MemoryPool,
  ThreadInfo,
  ProcessInfo,
  DiskInfo,
  DiskPartition,
  GarbageCollector,
  Nd4jResourceInfo,
  Nd4jDeviceMemory,
  SystemPropertiesInfo,
  DeviceInfo,
  SystemResourcesResponse,
  DevicesResponse,
  ModelRegistry,
  ModelRegistryEntry,
  ModelType,
  StagingStatusResponse,
  StagingModelInfo,
  ArchiveInfo,
  ArchiveStatus,
  ArchiveModelInfo,
  getModelRegistryStatusColor,
  getModelRegistryStatusIcon,
  getStagingStatusIcon,
  getStagingStatusColor
} from '../../models/api-models';

export interface ModelSummary {
  totalSameDiffModels: number;
  totalComputationGraphModels: number;
  totalMultiLayerNetworkModels: number;
  totalEmbeddingModels: number;
  totalLanguageModels: number;
}

export interface EmbeddingModelInfo {
  index: number;
  category: string;
  className: string;
  fullClassName: string;
  dimensions: number;
  type?: string;
  sameDiffModel?: any;
}

export interface LanguageModelInfo {
  index: number;
  category: string;
  className: string;
  fullClassName: string;
}

export interface BeanModelInfo {
  beanName: string;
  beanClass: string;
  beanFullClass: string;
  sameDiff?: any[];
  computationGraph?: any[];
  multiLayerNetwork?: any[];
}

export interface ModelsListResponse {
  status: string;
  summary: ModelSummary;
  embeddingModels: EmbeddingModelInfo[];
  embeddingModelCount: number;
  languageModels: LanguageModelInfo[];
  languageModelCount: number;
  allBeanModels: BeanModelInfo[];
  allBeanModelCount: number;
}

export interface SameDiffSummary {
  status: string;
  modelIndex?: number;
  modelType?: string;
  className?: string;
  fullClassName?: string;
  dimensions?: number;
  totalVariables: number;
  totalOperations: number;
  variableTypeBreakdown: { [key: string]: number };
  variables?: any[];
  operations?: any[];
  variablesTruncated?: boolean;
  operationsTruncated?: boolean;
  trainingConfig?: any;
  lossVariables?: string[];
  graphEdges?: { source: string; target: string; type: 'input' | 'output' }[];
}

export interface Nd4jEnvironment {
  booleanToggles: { [key: string]: boolean };
  integerConfigs: { [key: string]: number };
  backendInfo: {
    backend: string;
    isCPU: boolean;
    blasVersion?: string;
  };
  // Grouped toggles for organized UI display
  groupedToggles?: {
    coreDebugging?: { [key: string]: boolean };
    leakDetection?: { [key: string]: boolean };
    componentTracking?: { [key: string]: boolean };
    logging?: { [key: string]: boolean };
    funcTrace?: { [key: string]: boolean };
    blasHelpers?: { [key: string]: boolean };
    memoryManagement?: { [key: string]: boolean };
    workspace?: { [key: string]: boolean };
    inputOutputCheck?: { [key: string]: boolean };
  };
  // Grouped integer configs
  groupedIntConfigs?: {
    tracking?: { [key: string]: number };
    performance?: { [key: string]: number };
    threads?: { [key: string]: number };
  };
  // Read-only info
  readOnlyInfo?: {
    isCPU?: boolean;
    debugAndVerbose?: boolean;
    blasMajorVersion?: number;
    blasMinorVersion?: number;
    blasPatchVersion?: number;
  };
  // CUDA settings (only for GPU backends)
  cudaSettings?: {
    cudaDeviceCount?: number;
    cudaCurrentDevice?: number;
    memory?: { [key: string]: any };
    execution?: { [key: string]: any };
    threads?: { [key: string]: any };
    advanced?: { [key: string]: any };
  };
}

export interface EmbeddingTestResult {
  status: string;
  modelClass: string;
  inputText: string;
  embeddingShape: string;
  embeddingLength: number;
  embeddingPreview: string;
  inferenceTimeMs: number;
  message?: string;
}

// Profiling Metrics Interfaces
export interface ProfilingMetrics {
  status: string;
  timestamp: number;
  collectionTimeMs: number;
  backend: BackendInfo;
  threads: ThreadConfig;
  memory: MemoryMetrics;
  profilingStatus: ProfilingStatus;
  thresholds: Thresholds;
  blas: BlasStatus;
  tracking: TrackingConfig;
  debug: DebugSettings;
  availablePresets: { [key: string]: string };
  inference: { note: string; heartbeatInterval: string };
}

export interface BackendInfo {
  backend: string;
  isCPU: boolean;
  blasMajorVersion: number;
  blasMinorVersion: number;
  blasPatchVersion: number;
  blasVersion: string;
}

export interface ThreadConfig {
  maxThreads: number;
  maxMasterThreads: number;
  availableProcessors: number;
  activeThreadCount: number;
}

export interface MemoryMetrics {
  jvmMaxMemoryMB: number;
  jvmTotalMemoryMB: number;
  jvmUsedMemoryMB: number;
  jvmFreeMemoryMB: number;
  jvmUsagePercent: string;
  device0AllocatedBytes?: number;
  device0LimitBytes?: number;
  group0LimitBytes?: number;
  deviceMemoryError?: string;
}

export interface ProfilingStatus {
  verbose: boolean;
  debug: boolean;
  profiling: boolean;
  debugAndVerbose: boolean;
  detectingLeaks: boolean;
  lifecycleTracking: boolean;
  trackOperations: boolean;
  trackViews: boolean;
  trackDeletions: boolean;
  logNDArrayEvents: boolean;
  logNativeNDArrayCreation: boolean;
  funcTraceAllocate: boolean;
  funcTraceDeallocate: boolean;
}

export interface Thresholds {
  tadThreshold: number;
  elementwiseThreshold: number;
}

export interface BlasStatus {
  blasEnabled: boolean;
  helpersAllowed: boolean;
}

export interface TrackingConfig {
  stackDepth: number;
  reportInterval: number;
  maxDeletionHistory: number;
  snapshotFiles: boolean;
  trackWorkspaceOpenClose: boolean;
  numWorkspaceEventsToKeep: number;
}

export interface DebugSettings {
  checkInputChange: boolean;
  checkOutputChange: boolean;
  deletePrimary: boolean;
  deleteSpecial: boolean;
  deleteShapeInfo: boolean;
  variableTracingEnabled: boolean;
}

// Reranker interfaces
export interface RerankerParam {
  id: string;
  label: string;
  type: string;
  default: any;
  min?: any;
  max?: any;
}

export interface RerankerTypeInfo {
  id: string;
  name: string;
  description: string;
  parameters: RerankerParam[];
  supported: boolean;
}

export interface RerankerInfo {
  available: boolean;
  types: RerankerTypeInfo[];
}

export interface CrossEncoderModelInfo {
  modelId: string;
  description: string;
  hiddenSize: number;
  numLayers: number;
  maxSequenceLength: number;
  framework: string;
  trainingData: string;
  cached: boolean;
  cachedPath?: string;
  isDefault?: boolean;
}

export interface CrossEncoderModelsResponse {
  totalModels: number;
  cachedCount: number;
  defaultModel: string;
  models: CrossEncoderModelInfo[];
}

export interface ThreadDumpResponse {
  status: string;
  totalThreads: number;
  filteredThreads: number;
  filter: string;
  threads: ThreadDumpEntry[];
  stateCounts: { [key: string]: number };
  threadsInNativeCode: number;
}

export interface ThreadDumpEntry {
  name: string;
  id: number;
  state: string;
  priority: number;
  isDaemon: boolean;
  isAlive: boolean;
  isInterrupted: boolean;
  stackTrace: string[];
  stackDepth: number;
  mightBeStuck: boolean;
  inNativeCode?: boolean;
  nativeMethod?: string;
}

// ═══════════════════════════════════════════════════════════════════════════
// SERVICE STATE INTERFACES (from /api/services/state)
// ═══════════════════════════════════════════════════════════════════════════

export interface ServiceStateResponse {
  timestamp: string;
  embeddingModel: EmbeddingModelStateInfo;
  vectorStore: VectorStoreStateInfo;
  reranker: RerankerStateInfo;
  documentRetriever: DocumentRetrieverStateInfo;
  summary: ServiceStateSummary;
}

export interface ServiceStateSummary {
  allServicesLoaded: boolean;
  embeddingModelLoaded: boolean;
  vectorStoreLoaded: boolean;
  rerankerLoaded: boolean;
  documentRetrieverLoaded: boolean;
}

export interface EmbeddingModelStateInfo {
  loaded: boolean;
  reason?: string;
  totalModels?: number;
  primary?: {
    class: string;
    fullClass: string;
    dimensions: number;
    optimalBatchSize: number;
    maxBatchSize: number;
    modelIdentifier?: string;
    activeModelId?: string;
    encoderType?: string;
    modelType?: string;
    usesAutoModelManagement?: boolean;
    isShuttingDown?: boolean;
    modelInfo?: {
      modelId?: string;
      initialized?: boolean;
      source?: string;
      encoderType?: string;
      dimensions?: number;
      optimalBatchSize?: number;
      maxBatchSize?: number;
      modelType?: string;
      subprocessMode?: boolean;
      subprocessRunning?: boolean;
      subprocessModelLoaded?: boolean;
      subprocessDimensions?: number;
      subprocessEncoderType?: string;
    };
    batchConfig?: {
      optimal512Tokens: number;
      max512Tokens: number;
      optimalFor256Tokens: number;
      optimalFor128Tokens: number;
    };
    currentBatch?: any;
  };
  allModels?: Array<{
    index: number;
    class: string;
    isNoOp: boolean;
    dimensions: number;
    modelIdentifier?: string;
    encoderType?: string;
  }>;
}

export interface VectorStoreStateInfo {
  loaded: boolean;
  reason?: string;
  totalStores?: number;
  primary?: {
    class: string;
    fullClass: string;
    isAvailable: boolean;
    path: string;
    indexPath?: string;
    usingFallbackIndex?: boolean;
    isDestroyed?: boolean;
    rerankingAvailable?: boolean;
    documentCount?: number;
    indexPopulated?: boolean;
    documentCountError?: string;
    modelTracking?: {
      encoderModelId: string;
      rerankerModelId: string;
      modelConfiguration: { [key: string]: string };
    };
    warnings?: string[];
  };
  allStores?: Array<{
    index: number;
    class: string;
    isNoOp: boolean;
    path: string;
    encoderModelId?: string;
    usingFallback?: boolean;
  }>;
}

export interface RerankerStateInfo {
  loaded: boolean;
  reason?: string;
  class?: string;
  fullClass?: string;
  supportedTypes?: Array<{
    id: string;
    name: string;
    description: string;
    supported: boolean;
  }>;
  supportedTypeCount?: number;
  defaultConfig?: {
    type: string;
    enabled: boolean;
    fbDocs: number;
    fbTerms: number;
    topK: number;
  };
}

export interface DocumentRetrieverStateInfo {
  loaded: boolean;
  reason?: string;
  totalRetrievers?: number;
  primary?: {
    class: string;
    fullClass: string;
  };
  allRetrievers?: Array<{
    index: number;
    class: string;
    isNoOp: boolean;
  }>;
}

@Component({
  selector: 'app-model-debug',
  standalone: false,
  templateUrl: './model-debug.component.html',
  styleUrls: ['./model-debug.component.css']
})
export class ModelDebugComponent implements OnInit, OnDestroy {

  protected backendUrl: string;

  // State
  isLoading = false;
  modelsResponse: ModelsListResponse | null = null;
  nd4jEnvironment: Nd4jEnvironment | null = null;
  selectedModelSummary: SameDiffSummary | null = null;
  selectedModelIndex: number | null = null;
  rawSummaryText: string | null = null;
  showGraphView: boolean = false;

  // Embedding test
  testText = 'Hello world, this is a test sentence for embedding.';
  testModelIndex = 0;
  testResult: EmbeddingTestResult | null = null;
  isTesting = false;

  // ND4J toggle state
  nd4jUpdating = false;

  // Expanded sections
  expandedEmbeddingIndex: number | null = null;
  expandedBeanIndex: number | null = null;
  showRawSummary = false;

  // SDZ Upload state
  showSdzUpload = false;
  sdzUploadFile: File | null = null;
  vocabUploadFile: File | null = null;
  sdzUploadModelId = '';
  sdzUploadModelType = 'dense_encoder';
  sdzUploadVersion = '';
  sdzUploadEmbeddingDim: number | null = null;
  sdzUploadMaxSeqLen: number | null = null;
  sdzUploadDescription = '';
  sdzUploadOverwrite = false;
  sdzUploading = false;
  sdzUploadError: string | null = null;

  // Error state
  errorMessage: string | null = null;

  // System Resources State
  systemResources: SystemResourcesResponse | null = null;
  devices: DeviceInfo[] = [];
  resourcesLoading = false;
  autoRefreshEnabled = false;
  refreshIntervalMs = 2000;
  private refreshSubscription: Subscription | null = null;

  // WebSocket state for real-time updates
  useWebSocket = true; // Prefer WebSocket over polling
  webSocketConnected = false;
  private wsSubscription: Subscription | null = null;
  private wsConnectionSubscription: Subscription | null = null;
  private wsResourceSubscription: Subscription | null = null;

  // CPU/Memory history for charts (last 60 data points)
  cpuHistory: number[] = [];
  memoryHistory: number[] = [];
  historyMaxLength = 60;

  // Profiling metrics state
  profilingMetrics: ProfilingMetrics | null = null;
  profilingLoading = false;
  threadDump: ThreadDumpResponse | null = null;
  threadDumpLoading = false;
  threadFilter = '';
  selectedPreset: string | null = null;
  presetApplying = false;
  expandedThreadId: number | null = null;

  // Reranker state
  rerankerInfo: RerankerInfo | null = null;
  rerankerLoading = false;

  // Cross-encoder models state
  crossEncoderModelsResponse: CrossEncoderModelsResponse | null = null;
  crossEncoderModels: string[] = [];
  crossEncoderLoading = false;
  downloadingModelId: string | null = null;
  deletingModelId: string | null = null;

  // Model Registry state (from kompile-model-staging)
  modelRegistry: ModelRegistry | null = null;
  stagingStatus: StagingStatusResponse | null = null;
  registryLoading = false;
  stagingServiceAvailable = false;

  // Main RAG Service Status
  ragServiceStatus: {
    available: boolean;
    embeddingModel: { className: string; available: boolean; dimensions?: number } | null;
    vectorStore: { className: string; available: boolean } | null;
    reranker: { className: string; available: boolean; supportedTypes: string[] } | null;
    keywordRetriever: { className: string; available: boolean } | null;
  } = {
    available: false,
    embeddingModel: null,
    vectorStore: null,
    reranker: null,
    keywordRetriever: null
  };
  ragServiceLoading = false;

  // Service State (comprehensive transparency)
  serviceState: ServiceStateResponse | null = null;
  serviceStateLoading = false;

  // Archive state
  availableArchives: ArchiveInfo[] = [];
  currentArchiveStatus: ArchiveStatus | null = null;
  archiveModels: ArchiveModelInfo[] = [];
  isLoadingArchives = false;
  private destroy$ = new Subject<void>();

  // Active embedding model status
  activeEmbeddingStatus: EmbeddingModelStatus | null = null;

  // Embedding init-status (subprocess initialization details)
  embeddingInitStatus: any = null;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog,
    private webSocketService: WebSocketService,
    private archiveService: ArchiveService,
    private localRegistryService: LocalRegistryService,
    private modelRegistryService: ModelRegistryService
  ) {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.backendUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
    } else {
      this.backendUrl = '/api';
    }
  }

  ngOnInit(): void {
    this.loadAllData();
    this.loadSystemResources();
    this.loadDevices();
    this.loadRerankerInfo();
    this.loadCrossEncoderModels();
    this.loadModelRegistry();
    this.checkRagServiceStatus(); // Check main service status first
    this.checkStagingService();
    this.loadArchiveData();
    this.loadServiceState(); // Load comprehensive service state
    this.loadActiveEmbeddingStatus(); // Load active embedding model status

    // Subscribe to model registry changes for real-time updates
    this.modelRegistryService.changes$
      .pipe(takeUntil(this.destroy$))
      .subscribe(event => {
        console.log('[ModelDebug] Model registry change:', event.type, event.modelId);
        this.loadActiveEmbeddingStatus();
        this.loadAllData();
      });

    // Initialize WebSocket connection and monitor state
    this.initializeWebSocket();
  }

  /**
   * Load the active embedding model status from the local registry service.
   */
  loadActiveEmbeddingStatus(): void {
    this.localRegistryService.getEmbeddingStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.activeEmbeddingStatus = status;
          this.cdr.markForCheck();
        },
        error: (err) => {
          console.warn('Could not load active embedding status:', err);
        }
      });
  }

  ngOnDestroy(): void {
    this.stopAutoRefresh();
    this.cleanupWebSocket();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Initialize WebSocket connection for real-time updates.
   */
  private initializeWebSocket(): void {
    // Monitor WebSocket connection state
    this.wsConnectionSubscription = this.webSocketService.connectionState$.subscribe(state => {
      this.webSocketConnected = (state === WebSocketConnectionState.CONNECTED);
      this.cdr.detectChanges();

      // If we're using WebSocket and auto-refresh is enabled, ensure subscription
      if (this.webSocketConnected && this.autoRefreshEnabled && this.useWebSocket) {
        this.subscribeToSystemResourcesWebSocket();
      }
    });

    // Connect WebSocket if not already connected
    this.webSocketService.connect();
  }

  /**
   * Clean up WebSocket subscriptions.
   */
  private cleanupWebSocket(): void {
    if (this.wsSubscription) {
      this.wsSubscription.unsubscribe();
      this.wsSubscription = null;
    }
    if (this.wsConnectionSubscription) {
      this.wsConnectionSubscription.unsubscribe();
      this.wsConnectionSubscription = null;
    }
    if (this.wsResourceSubscription) {
      this.webSocketService.unsubscribeFromSystemResources();
      this.wsResourceSubscription.unsubscribe();
      this.wsResourceSubscription = null;
    }
  }

  loadAllData(): void {
    this.isLoading = true;
    this.errorMessage = null;

    // Load models list, ND4J environment, and init status in parallel
    Promise.all([
      this.loadModels(),
      this.loadNd4jEnvironment(),
      this.loadInitStatus()
    ]).finally(() => {
      this.isLoading = false;
      this.cdr.detectChanges();
    });
  }

  private loadModels(): Promise<void> {
    return new Promise((resolve) => {
      this.http.get<ModelsListResponse>(`${this.backendUrl}/models/list`).subscribe({
        next: (response) => {
          this.modelsResponse = response;
          resolve();
        },
        error: (err) => {
          this.showSnackbar('Failed to load models: ' + this.getErrorMessage(err), true);
          resolve();
        }
      });
    });
  }

  public loadNd4jEnvironment(): Promise<void> {
    return new Promise((resolve) => {
      this.http.get<Nd4jEnvironment>(`${this.backendUrl}/models/nd4j/environment`).subscribe({
        next: (response) => {
          this.nd4jEnvironment = response;
          resolve();
        },
        error: (err) => {
          this.showSnackbar('Failed to load ND4J environment: ' + this.getErrorMessage(err), true);
          resolve();
        }
      });
    });
  }

  private loadInitStatus(): Promise<void> {
    return new Promise((resolve) => {
      this.http.get<any>(`${this.backendUrl}/models/init-status`).subscribe({
        next: (response) => {
          this.embeddingInitStatus = response;
          resolve();
        },
        error: () => resolve()
      });
    });
  }

  refreshData(): void {
    this.loadAllData();
    this.showSnackbar('Data refreshed');
  }

  // Load SameDiff model summary
  loadModelSummary(modelIndex: number): void {
    if (this.selectedModelIndex === modelIndex) {
      // Toggle off
      this.selectedModelIndex = null;
      this.selectedModelSummary = null;
      this.rawSummaryText = null;
      this.showGraphView = false;
      return;
    }

    this.selectedModelIndex = modelIndex;
    this.selectedModelSummary = null;
    this.rawSummaryText = null;
    this.showGraphView = false;

    this.http.get<SameDiffSummary>(`${this.backendUrl}/models/samediff-embeddings/${modelIndex}/summary?maxOperations=0&maxVariables=0`).subscribe({
      next: (response) => {
        this.selectedModelSummary = response;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.showSnackbar('Failed to load model summary: ' + this.getErrorMessage(err), true);
        this.selectedModelIndex = null;
      }
    });
  }

  toggleGraphView(): void {
    this.showGraphView = !this.showGraphView;
  }

  // Load raw summary text
  loadRawSummary(modelIndex: number): void {
    this.http.get(`${this.backendUrl}/models/samediff-embeddings/${modelIndex}/summary/text`, { responseType: 'text' }).subscribe({
      next: (text) => {
        this.rawSummaryText = text;
        this.showRawSummary = true;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.showSnackbar('Failed to load raw summary: ' + this.getErrorMessage(err), true);
      }
    });
  }

  // Test embedding
  testEmbedding(): void {
    if (!this.testText.trim()) {
      this.showSnackbar('Please enter test text', true);
      return;
    }

    this.isTesting = true;
    this.testResult = null;

    const params = { text: this.testText, modelIndex: this.testModelIndex.toString() };

    this.http.post<EmbeddingTestResult>(`${this.backendUrl}/models/embeddings/test`, null, { params }).subscribe({
      next: (result) => {
        this.testResult = result;
        this.isTesting = false;
        this.cdr.detectChanges();
        this.showSnackbar(`Embedding generated in ${result.inferenceTimeMs}ms`);
      },
      error: (err) => {
        this.isTesting = false;
        this.showSnackbar('Embedding test failed: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  // ND4J Toggle controls
  toggleNd4jSetting(key: string, currentValue: boolean): void {
    this.nd4jUpdating = true;

    const params = { enabled: (!currentValue).toString() };
    this.http.post(`${this.backendUrl}/models/nd4j/environment/toggle/${key}`, null, { params }).subscribe({
      next: () => {
        if (this.nd4jEnvironment?.booleanToggles) {
          this.nd4jEnvironment.booleanToggles[key] = !currentValue;
        }
        this.nd4jUpdating = false;
        this.cdr.detectChanges();
        this.showSnackbar(`${key} set to ${!currentValue}`);
      },
      error: (err) => {
        this.nd4jUpdating = false;
        this.showSnackbar('Failed to toggle setting: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  enableAllNd4jToggles(): void {
    this.nd4jUpdating = true;
    this.http.post(`${this.backendUrl}/models/nd4j/environment/enable-all`, null).subscribe({
      next: () => {
        this.loadNd4jEnvironment().then(() => {
          this.nd4jUpdating = false;
          this.cdr.detectChanges();
          this.showSnackbar('All ND4J toggles enabled');
        });
      },
      error: (err) => {
        this.nd4jUpdating = false;
        this.showSnackbar('Failed to enable all toggles: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  disableAllNd4jToggles(): void {
    this.nd4jUpdating = true;
    this.http.post(`${this.backendUrl}/models/nd4j/environment/disable-all`, null).subscribe({
      next: () => {
        this.loadNd4jEnvironment().then(() => {
          this.nd4jUpdating = false;
          this.cdr.detectChanges();
          this.showSnackbar('All ND4J toggles disabled');
        });
      },
      error: (err) => {
        this.nd4jUpdating = false;
        this.showSnackbar('Failed to disable all toggles: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  updateNd4jConfig(configName: string, value: number): void {
    this.nd4jUpdating = true;

    const params = { value: value.toString() };
    this.http.post(`${this.backendUrl}/models/nd4j/environment/config/${configName}`, null, { params }).subscribe({
      next: () => {
        if (this.nd4jEnvironment?.integerConfigs) {
          this.nd4jEnvironment.integerConfigs[configName] = value;
        }
        this.nd4jUpdating = false;
        this.cdr.detectChanges();
        this.showSnackbar(`${configName} set to ${value}`);
      },
      error: (err) => {
        this.nd4jUpdating = false;
        this.showSnackbar('Failed to update config: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  // Toggle expansion
  toggleEmbeddingExpansion(index: number): void {
    this.expandedEmbeddingIndex = this.expandedEmbeddingIndex === index ? null : index;
  }

  toggleBeanExpansion(index: number): void {
    this.expandedBeanIndex = this.expandedBeanIndex === index ? null : index;
  }

  // Copy content
  copyToClipboard(text: string): void {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text)
        .then(() => this.showSnackbar('Copied to clipboard'))
        .catch(() => this.fallbackCopyTextToClipboard(text));
    } else {
      this.fallbackCopyTextToClipboard(text);
    }
  }

  private fallbackCopyTextToClipboard(text: string): void {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    try {
      if (document.execCommand('copy')) {
        this.showSnackbar('Copied (fallback)');
      }
    } catch (err) {
      this.showSnackbar('Copy failed', true);
    }
    document.body.removeChild(ta);
  }

  // Helper methods
  private getErrorMessage(error: HttpErrorResponse): string {
    return error.error?.message || error.error?.error || error.message || 'Unknown error';
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  // Template helpers
  getToggleKeys(): string[] {
    return this.nd4jEnvironment?.booleanToggles ? Object.keys(this.nd4jEnvironment.booleanToggles) : [];
  }

  getConfigKeys(): string[] {
    return this.nd4jEnvironment?.integerConfigs ? Object.keys(this.nd4jEnvironment.integerConfigs) : [];
  }

  getVariableTypeKeys(): string[] {
    return this.selectedModelSummary?.variableTypeBreakdown
      ? Object.keys(this.selectedModelSummary.variableTypeBreakdown)
      : [];
  }

  formatToggleName(name: string): string {
    // Convert camelCase to Title Case with spaces
    return name.replace(/([A-Z])/g, ' $1').replace(/^./, str => str.toUpperCase());
  }

  hasSameDiffModel(model: EmbeddingModelInfo): boolean {
    return model.sameDiffModel != null;
  }

  // ==================== Grouped Toggle Helpers ====================

  /**
   * Get all toggle group names for iteration in templates.
   */
  getToggleGroupNames(): string[] {
    if (!this.nd4jEnvironment?.groupedToggles) {
      return [];
    }
    return Object.keys(this.nd4jEnvironment.groupedToggles);
  }

  /**
   * Get toggle keys for a specific group.
   */
  getToggleKeysForGroup(groupName: string): string[] {
    const groupedToggles = this.nd4jEnvironment?.groupedToggles;
    if (!groupedToggles) return [];
    const group = (groupedToggles as any)[groupName];
    return group ? Object.keys(group) : [];
  }

  /**
   * Get toggle value for a specific key in a group.
   */
  getToggleValueInGroup(groupName: string, key: string): boolean {
    const groupedToggles = this.nd4jEnvironment?.groupedToggles;
    if (!groupedToggles) return false;
    const group = (groupedToggles as any)[groupName];
    return group ? group[key] : false;
  }

  /**
   * Format group name for display.
   */
  formatGroupName(groupName: string): string {
    const displayNames: { [key: string]: string } = {
      coreDebugging: 'Core Debugging',
      leakDetection: 'Leak Detection',
      componentTracking: 'Component Tracking',
      logging: 'Logging',
      funcTrace: 'Function Trace',
      blasHelpers: 'BLAS & Helpers',
      memoryManagement: 'Memory Management',
      workspace: 'Workspace',
      inputOutputCheck: 'Input/Output Checking'
    };
    return displayNames[groupName] || this.formatToggleName(groupName);
  }

  /**
   * Get icon for a toggle group.
   */
  getGroupIcon(groupName: string): string {
    const icons: { [key: string]: string } = {
      coreDebugging: 'bug_report',
      leakDetection: 'water_drop',
      componentTracking: 'track_changes',
      logging: 'article',
      funcTrace: 'timeline',
      blasHelpers: 'speed',
      memoryManagement: 'memory',
      workspace: 'workspaces',
      inputOutputCheck: 'compare_arrows'
    };
    return icons[groupName] || 'settings';
  }

  /**
   * Get tooltip/description for a toggle.
   */
  getToggleDescription(key: string): string {
    const descriptions: { [key: string]: string } = {
      // Core Debugging
      verbose: 'Enable verbose logging for ND4J operations',
      debug: 'Enable debug mode with additional validation',
      profiling: 'Enable operation profiling for performance analysis',

      // Leak Detection
      detectingLeaks: 'Enable memory leak detection',
      lifecycleTracking: 'Track NDArray lifecycle (creation/deletion)',
      trackViews: 'Track array view creations',
      trackDeletions: 'Track array deletions (HIGH OVERHEAD)',
      trackOperations: 'Track all operations',

      // Component Tracking
      ndArrayTracking: 'Track individual NDArray instances',
      dataBufferTracking: 'Track DataBuffer instances',
      tadCacheTracking: 'Track TAD cache entries',
      shapeCacheTracking: 'Track shape cache entries',
      opContextTracking: 'Track operation context instances',

      // Logging
      logNDArrayEvents: 'Log NDArray lifecycle events',
      logNativeNDArrayCreation: 'Log native array creation',
      truncateLogStrings: 'Truncate long log strings',

      // Function Trace
      funcTraceAllocate: 'Print stack trace on allocation (VERY HIGH OVERHEAD)',
      funcTraceDeallocate: 'Print stack trace on deallocation (VERY HIGH OVERHEAD)',
      funcTracePrintJavaOnly: 'Only print Java frames in stack traces',

      // BLAS & Helpers
      enableBlas: 'Enable BLAS operations (disabling significantly impacts performance)',
      helpersAllowed: 'Allow oneDNN/cuDNN helpers',

      // Memory Management
      deletePrimary: 'Delete primary memory on deallocation',
      deleteSpecial: 'Delete special memory on deallocation',
      deleteShapeInfo: 'Delete shape info on deallocation',

      // Workspace
      trackWorkspaceOpenClose: 'Track workspace open/close events',
      snapshotFiles: 'Enable snapshot file creation',

      // Input/Output Checking
      variableTracingEnabled: 'Enable variable tracing',
      checkInputChange: 'Validate inputs haven\'t changed after op (HIGH OVERHEAD)',
      checkOutputChange: 'Validate outputs haven\'t changed after op (HIGH OVERHEAD)'
    };
    return descriptions[key] || '';
  }

  /**
   * Check if a toggle has high overhead warning.
   */
  isHighOverheadToggle(key: string): boolean {
    const highOverheadToggles = [
      'trackDeletions',
      'funcTraceAllocate',
      'funcTraceDeallocate',
      'checkInputChange',
      'checkOutputChange'
    ];
    return highOverheadToggles.includes(key);
  }

  /**
   * Check if a toggle is dangerous (can cause memory issues).
   */
  isDangerousToggle(key: string): boolean {
    const dangerousToggles = [
      'deletePrimary',
      'deleteSpecial',
      'enableBlas'
    ];
    return dangerousToggles.includes(key);
  }

  /**
   * Get all integer config group names.
   */
  getIntConfigGroupNames(): string[] {
    if (!this.nd4jEnvironment?.groupedIntConfigs) {
      return [];
    }
    return Object.keys(this.nd4jEnvironment.groupedIntConfigs);
  }

  /**
   * Get config keys for a specific integer config group.
   */
  getIntConfigKeysForGroup(groupName: string): string[] {
    const groupedConfigs = this.nd4jEnvironment?.groupedIntConfigs;
    if (!groupedConfigs) return [];
    const group = (groupedConfigs as any)[groupName];
    return group ? Object.keys(group) : [];
  }

  /**
   * Get config value for a specific key in a group.
   */
  getIntConfigValueInGroup(groupName: string, key: string): number {
    const groupedConfigs = this.nd4jEnvironment?.groupedIntConfigs;
    if (!groupedConfigs) return 0;
    const group = (groupedConfigs as any)[groupName];
    return group ? group[key] : 0;
  }

  /**
   * Format integer config group name.
   */
  formatIntConfigGroupName(groupName: string): string {
    const displayNames: { [key: string]: string } = {
      tracking: 'Tracking Settings',
      performance: 'Performance Thresholds',
      threads: 'Thread Configuration'
    };
    return displayNames[groupName] || this.formatToggleName(groupName);
  }

  /**
   * Check if we have CUDA settings (GPU backend).
   */
  hasCudaSettings(): boolean {
    return this.nd4jEnvironment?.cudaSettings != null;
  }

  /**
   * Get CUDA memory setting keys.
   */
  getCudaMemoryKeys(): string[] {
    return this.nd4jEnvironment?.cudaSettings?.memory
      ? Object.keys(this.nd4jEnvironment.cudaSettings.memory)
      : [];
  }

  /**
   * Get CUDA execution setting keys.
   */
  getCudaExecutionKeys(): string[] {
    return this.nd4jEnvironment?.cudaSettings?.execution
      ? Object.keys(this.nd4jEnvironment.cudaSettings.execution)
      : [];
  }

  /**
   * Get CUDA thread setting keys.
   */
  getCudaThreadKeys(): string[] {
    return this.nd4jEnvironment?.cudaSettings?.threads
      ? Object.keys(this.nd4jEnvironment.cudaSettings.threads)
      : [];
  }

  /**
   * Get CUDA advanced setting keys.
   */
  getCudaAdvancedKeys(): string[] {
    return this.nd4jEnvironment?.cudaSettings?.advanced
      ? Object.keys(this.nd4jEnvironment.cudaSettings.advanced)
      : [];
  }

  // ==================== System Resources Methods ====================

  loadSystemResources(): void {
    this.resourcesLoading = true;
    this.http.get<SystemResourcesResponse>(`${this.backendUrl}/system/resources`).subscribe({
      next: (response) => {
        this.systemResources = response;
        this.updateHistory();
        this.resourcesLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.resourcesLoading = false;
        console.error('Failed to load system resources:', err);
        this.cdr.detectChanges();
      }
    });
  }

  loadDevices(): void {
    this.http.get<DevicesResponse>(`${this.backendUrl}/system/devices`).subscribe({
      next: (response) => {
        this.devices = response.devices || [];
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load devices:', err);
      }
    });
  }

  /**
   * Load reranker information including available types and cross-encoder models.
   */
  loadRerankerInfo(): void {
    this.rerankerLoading = true;
    this.http.get<RerankerInfo>(`${this.backendUrl}/rag/test/rerankers`).subscribe({
      next: (response) => {
        this.rerankerInfo = response;
        // Extract cross-encoder models from the types if available
        const crossEncoderType = response.types?.find(t => t.id === 'cross_encoder');
        if (crossEncoderType) {
          const modelParam = crossEncoderType.parameters?.find(p => p.id === 'crossEncoderModel');
          if (modelParam && typeof modelParam.default === 'string') {
            this.crossEncoderModels = [modelParam.default];
          }
        }
        this.rerankerLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load reranker info:', err);
        this.rerankerLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Get supported reranker types (those that are marked as supported).
   */
  getSupportedRerankerTypes(): RerankerTypeInfo[] {
    if (!this.rerankerInfo?.types) return [];
    return this.rerankerInfo.types.filter(t => t.supported);
  }

  /**
   * Get all reranker types.
   */
  getAllRerankerTypes(): RerankerTypeInfo[] {
    return this.rerankerInfo?.types || [];
  }

  /**
   * Algorithmic reranker type IDs (non-neural, query expansion based).
   */
  private readonly ALGORITHMIC_RERANKER_IDS = ['rm3', 'bm25prf', 'rocchio', 'axiom', 'score_ties', 'rrf', 'normalize', 'mmr'];

  /**
   * Neural/model-based reranker type IDs.
   */
  private readonly NEURAL_RERANKER_IDS = ['cross_encoder'];

  /**
   * Get algorithmic reranker types (query expansion, PRF-based).
   */
  getAlgorithmicRerankerTypes(): RerankerTypeInfo[] {
    if (!this.rerankerInfo?.types) return [];
    return this.rerankerInfo.types.filter(t =>
      this.ALGORITHMIC_RERANKER_IDS.includes(t.id) && t.id !== 'none'
    );
  }

  /**
   * Get neural/model-based reranker types.
   */
  getNeuralRerankerTypes(): RerankerTypeInfo[] {
    if (!this.rerankerInfo?.types) return [];
    return this.rerankerInfo.types.filter(t =>
      this.NEURAL_RERANKER_IDS.includes(t.id)
    );
  }

  /**
   * Get icon for a reranker type.
   */
  getRerankerIcon(typeId: string): string {
    const icons: { [key: string]: string } = {
      'none': 'block',
      'rm3': 'auto_fix_high',
      'bm25prf': 'trending_up',
      'rocchio': 'scatter_plot',
      'axiom': 'hub',
      'score_ties': 'swap_vert',
      'cross_encoder': 'psychology',
      'rrf': 'merge_type',
      'normalize': 'straighten',
      'mmr': 'diversity_3'
    };
    return icons[typeId] || 'sort';
  }

  /**
   * Get category label for a reranker type.
   */
  getRerankerCategory(typeId: string): string {
    if (this.NEURAL_RERANKER_IDS.includes(typeId)) {
      return 'Neural';
    }
    return 'Algorithmic';
  }

  // ==================== Cross-Encoder Model Methods ====================

  /**
   * Load cross-encoder models from the API.
   */
  loadCrossEncoderModels(): void {
    this.crossEncoderLoading = true;
    this.http.get<CrossEncoderModelsResponse>(`${this.backendUrl}/rag/test/cross-encoder-models`).subscribe({
      next: (response) => {
        this.crossEncoderModelsResponse = response;
        // Mark default model
        if (response.models) {
          response.models.forEach(m => {
            m.isDefault = m.modelId === response.defaultModel;
          });
        }
        this.crossEncoderLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load cross-encoder models:', err);
        this.crossEncoderLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Download a cross-encoder model.
   */
  downloadCrossEncoderModel(modelId: string): void {
    this.downloadingModelId = modelId;
    this.http.post<any>(`${this.backendUrl}/rag/test/cross-encoder-models/${modelId}/download`, null).subscribe({
      next: (response) => {
        this.downloadingModelId = null;
        if (response.success) {
          this.showSnackbar(`Model ${modelId} downloaded successfully`);
          // Refresh the list
          this.loadCrossEncoderModels();
        } else {
          this.showSnackbar(`Failed to download model: ${response.error}`, true);
        }
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.downloadingModelId = null;
        this.showSnackbar('Failed to download model: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Delete a cached cross-encoder model.
   */
  deleteCrossEncoderModel(modelId: string): void {
    const dialogData: ConfirmDialogData = {
      title: 'Delete Cached Model',
      message: `Are you sure you want to delete the cached model "${modelId}"?`,
      confirmText: 'Delete',
      confirmColor: 'warn',
      icon: 'delete'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.deletingModelId = modelId;
        this.http.delete<any>(`${this.backendUrl}/rag/test/cross-encoder-models/${modelId}`).subscribe({
          next: (response) => {
            this.deletingModelId = null;
            if (response.success) {
              this.showSnackbar(`Model ${modelId} cache deleted`);
              // Refresh the list
              this.loadCrossEncoderModels();
            } else {
              this.showSnackbar(`Failed to delete model: ${response.error}`, true);
            }
            this.cdr.detectChanges();
          },
          error: (err) => {
            this.deletingModelId = null;
            this.showSnackbar('Failed to delete model: ' + this.getErrorMessage(err), true);
            this.cdr.detectChanges();
          }
        });
      });
  }

  /**
   * Get cross-encoder models list.
   */
  getCrossEncoderModels(): CrossEncoderModelInfo[] {
    return this.crossEncoderModelsResponse?.models || [];
  }

  /**
   * Get only cached cross-encoder models (locally available in ~/.kompile/models).
   */
  getCachedCrossEncoderModels(): CrossEncoderModelInfo[] {
    return (this.crossEncoderModelsResponse?.models || []).filter(m => m.cached);
  }

  /**
   * Get cached cross-encoder models count.
   */
  getCachedCrossEncoderCount(): number {
    return this.crossEncoderModelsResponse?.cachedCount || 0;
  }

  /**
   * Get total cross-encoder models count.
   */
  getTotalCrossEncoderCount(): number {
    return this.crossEncoderModelsResponse?.totalModels || 0;
  }

  private updateHistory(): void {
    if (this.systemResources?.cpu) {
      const cpuValue = typeof this.systemResources.cpu.systemCpuLoad === 'number'
        ? this.systemResources.cpu.systemCpuLoad
        : (typeof this.systemResources.cpu.processCpuLoad === 'number'
          ? this.systemResources.cpu.processCpuLoad
          : 0);

      this.cpuHistory.push(cpuValue);
      if (this.cpuHistory.length > this.historyMaxLength) {
        this.cpuHistory.shift();
      }
    }

    if (this.systemResources?.memory?.heap) {
      this.memoryHistory.push(this.systemResources.memory.heap.usagePercent);
      if (this.memoryHistory.length > this.historyMaxLength) {
        this.memoryHistory.shift();
      }
    }
  }

  toggleAutoRefresh(): void {
    this.autoRefreshEnabled = !this.autoRefreshEnabled;
    if (this.autoRefreshEnabled) {
      this.startAutoRefresh();
      const mode = this.useWebSocket && this.webSocketConnected ? 'WebSocket' : 'polling';
      this.showSnackbar(`Auto-refresh enabled via ${mode}`);
    } else {
      this.stopAutoRefresh();
      this.showSnackbar('Auto-refresh disabled');
    }
  }

  /**
   * Toggle between WebSocket and HTTP polling modes.
   */
  toggleWebSocketMode(): void {
    this.useWebSocket = !this.useWebSocket;
    if (this.autoRefreshEnabled) {
      // Restart auto-refresh with new mode
      this.stopAutoRefresh();
      this.startAutoRefresh();
    }
    const mode = this.useWebSocket ? 'WebSocket' : 'HTTP polling';
    this.showSnackbar(`Switched to ${mode} mode`);
  }

  startAutoRefresh(): void {
    this.stopAutoRefresh();

    // Prefer WebSocket if enabled and connected
    if (this.useWebSocket && this.webSocketConnected) {
      this.subscribeToSystemResourcesWebSocket();
    } else {
      // Fallback to HTTP polling
      this.refreshSubscription = interval(this.refreshIntervalMs).subscribe(() => {
        this.loadSystemResources();
      });
    }
  }

  /**
   * Subscribe to system resources via WebSocket for real-time updates.
   */
  private subscribeToSystemResourcesWebSocket(): void {
    // Avoid duplicate subscriptions
    if (this.wsResourceSubscription) {
      return;
    }

    console.log('Subscribing to system resources via WebSocket...');
    this.wsResourceSubscription = this.webSocketService.subscribeToSystemResources().subscribe({
      next: (resources) => {
        this.systemResources = resources;
        this.updateHistory();
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('WebSocket resource subscription error:', err);
        // Fallback to polling on error
        if (this.autoRefreshEnabled && !this.refreshSubscription) {
          this.showSnackbar('WebSocket error, falling back to HTTP polling', true);
          this.refreshSubscription = interval(this.refreshIntervalMs).subscribe(() => {
            this.loadSystemResources();
          });
        }
      }
    });
  }

  stopAutoRefresh(): void {
    // Stop HTTP polling
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
      this.refreshSubscription = null;
    }

    // Stop WebSocket subscription
    if (this.wsResourceSubscription) {
      this.webSocketService.unsubscribeFromSystemResources();
      this.wsResourceSubscription.unsubscribe();
      this.wsResourceSubscription = null;
    }
  }

  setRefreshInterval(ms: number): void {
    this.refreshIntervalMs = ms;
    if (this.autoRefreshEnabled && !this.useWebSocket) {
      // Only restart if using HTTP polling (WebSocket uses server-side interval)
      this.stopAutoRefresh();
      this.startAutoRefresh();
    }
    this.showSnackbar(`Refresh interval set to ${ms / 1000}s`);
  }

  triggerGc(): void {
    this.http.post<any>(`${this.backendUrl}/system/gc`, null).subscribe({
      next: (response) => {
        this.showSnackbar(`GC triggered. Freed ${response.freedMemoryMB || 0}MB`);
        this.loadSystemResources();
      },
      error: (err) => {
        this.showSnackbar('Failed to trigger GC: ' + this.getErrorMessage(err), true);
      }
    });
  }

  refreshResources(): void {
    this.loadSystemResources();
    this.loadDevices();
    this.showSnackbar('Resources refreshed');
  }

  // Format uptime from milliseconds
  formatUptime(ms: number): string {
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);

    if (days > 0) {
      return `${days}d ${hours % 24}h ${minutes % 60}m`;
    } else if (hours > 0) {
      return `${hours}h ${minutes % 60}m ${seconds % 60}s`;
    } else if (minutes > 0) {
      return `${minutes}m ${seconds % 60}s`;
    } else {
      return `${seconds}s`;
    }
  }

  // Get thread state color
  getThreadStateColor(state: string): string {
    switch (state) {
      case 'RUNNABLE': return '#4caf50';
      case 'WAITING': return '#ff9800';
      case 'TIMED_WAITING': return '#ff9800';
      case 'BLOCKED': return '#f44336';
      case 'NEW': return '#2196f3';
      case 'TERMINATED': return '#9e9e9e';
      default: return '#757575';
    }
  }

  // Get memory pool names
  getMemoryPoolKeys(): string[] {
    return this.systemResources?.memory?.pools
      ? this.systemResources.memory.pools.map(p => p.name)
      : [];
  }

  // Get thread state keys
  getThreadStateKeys(): string[] {
    return this.systemResources?.threads?.states
      ? Object.keys(this.systemResources.threads.states)
      : [];
  }

  // Get disk partition keys
  getDiskPartitions(): DiskPartition[] {
    return this.systemResources?.disk?.partitions || [];
  }

  // Get GC collectors
  getGarbageCollectors(): GarbageCollector[] {
    return this.systemResources?.process?.garbageCollectors || [];
  }

  // Format bytes to human readable
  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  // Get CPU usage as number
  getCpuUsage(): number {
    if (!this.systemResources?.cpu) return 0;
    const cpu = this.systemResources.cpu;
    if (typeof cpu.systemCpuLoad === 'number') return cpu.systemCpuLoad;
    if (typeof cpu.processCpuLoad === 'number') return cpu.processCpuLoad;
    return 0;
  }

  // Get memory usage percentage
  getMemoryUsage(): number {
    return this.systemResources?.memory?.heap?.usagePercent || 0;
  }

  // Get system memory usage percentage
  getSystemMemoryUsage(): number {
    return this.systemResources?.memory?.system?.usagePercent || 0;
  }

  // ==================== Profiling Metrics Methods ====================

  loadProfilingMetrics(): void {
    this.profilingLoading = true;
    this.http.get<ProfilingMetrics>(`${this.backendUrl}/models/nd4j/profiling-metrics`).subscribe({
      next: (response) => {
        this.profilingMetrics = response;
        this.profilingLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.profilingLoading = false;
        this.showSnackbar('Failed to load profiling metrics: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  applyProfilingPreset(preset: string): void {
    this.presetApplying = true;
    this.selectedPreset = preset;

    this.http.post<any>(`${this.backendUrl}/models/nd4j/profiling-preset/${preset}`, null).subscribe({
      next: (response) => {
        this.presetApplying = false;
        this.showSnackbar(response.message || `Preset '${preset}' applied`);
        // Reload metrics to show updated state
        this.loadProfilingMetrics();
        this.loadNd4jEnvironment();
      },
      error: (err) => {
        this.presetApplying = false;
        this.selectedPreset = null;
        this.showSnackbar('Failed to apply preset: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  loadThreadDump(filter?: string): void {
    this.threadDumpLoading = true;

    let url = `${this.backendUrl}/models/nd4j/thread-dump`;
    if (filter) {
      url += `?filter=${encodeURIComponent(filter)}`;
    }

    this.http.get<ThreadDumpResponse>(url).subscribe({
      next: (response) => {
        this.threadDump = response;
        this.threadDumpLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.threadDumpLoading = false;
        this.showSnackbar('Failed to load thread dump: ' + this.getErrorMessage(err), true);
        this.cdr.detectChanges();
      }
    });
  }

  refreshThreadDump(): void {
    this.loadThreadDump(this.threadFilter || undefined);
  }

  filterThreads(): void {
    this.loadThreadDump(this.threadFilter || undefined);
  }

  toggleThreadExpansion(threadId: number): void {
    this.expandedThreadId = this.expandedThreadId === threadId ? null : threadId;
  }

  getProfilingStatusKeys(): string[] {
    return this.profilingMetrics?.profilingStatus
      ? Object.keys(this.profilingMetrics.profilingStatus)
      : [];
  }

  getPresetKeys(): string[] {
    return this.profilingMetrics?.availablePresets
      ? Object.keys(this.profilingMetrics.availablePresets)
      : [];
  }

  getPresetDescription(preset: string): string {
    return this.profilingMetrics?.availablePresets?.[preset] || '';
  }

  getThreadStateCountKeys(): string[] {
    return this.threadDump?.stateCounts
      ? Object.keys(this.threadDump.stateCounts)
      : [];
  }

  getProfilingStatusValue(key: string): boolean {
    return (this.profilingMetrics?.profilingStatus as any)?.[key] ?? false;
  }

  isProfilingEnabled(): boolean {
    if (!this.profilingMetrics?.profilingStatus) return false;
    const status = this.profilingMetrics.profilingStatus;
    return status.profiling || status.verbose || status.debug || status.lifecycleTracking;
  }

  getActiveProfilingCount(): number {
    if (!this.profilingMetrics?.profilingStatus) return 0;
    const status = this.profilingMetrics.profilingStatus;
    return Object.values(status).filter(v => v === true).length;
  }

  getStuckThreadsCount(): number {
    return this.threadDump?.threadsInNativeCode || 0;
  }

  formatPresetName(preset: string): string {
    return preset.charAt(0).toUpperCase() + preset.slice(1).replace(/([A-Z])/g, ' $1');
  }

  getPresetIcon(preset: string): string {
    switch (preset.toLowerCase()) {
      case 'production': return 'speed';
      case 'monitoring': return 'visibility';
      case 'debugging': return 'bug_report';
      case 'memoryanalysis': return 'memory';
      default: return 'settings';
    }
  }

  getPresetColor(preset: string): string {
    switch (preset.toLowerCase()) {
      case 'production': return 'primary';
      case 'monitoring': return 'accent';
      case 'debugging': return 'warn';
      case 'memoryanalysis': return 'primary';
      default: return 'primary';
    }
  }

  updateThreadConfig(configName: string, value: number): void {
    const updates: any = {};
    updates[configName] = value;

    this.http.post<any>(`${this.backendUrl}/models/nd4j/environment/bulk-update`, updates).subscribe({
      next: (response) => {
        this.showSnackbar(`${configName} updated to ${value}`);
        this.loadProfilingMetrics();
      },
      error: (err) => {
        this.showSnackbar('Failed to update config: ' + this.getErrorMessage(err), true);
      }
    });
  }

  // ==================== Model Registry Methods ====================

  /**
   * Load the model registry from the backend API.
   * This reads from ~/.kompile/models/registry.json via ModelRegistryController.
   */
  loadModelRegistry(): void {
    this.registryLoading = true;
    this.http.get<ModelRegistry>(`${this.backendUrl}/models/registry`).subscribe({
      next: (response) => {
        this.modelRegistry = response;
        this.registryLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load model registry:', err);
        this.registryLoading = false;
        // Registry might not exist yet - this is not an error condition
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Check the main RAG service status by calling /api/rag/test/status.
   * This indicates whether the Spring Boot app is running with models loaded.
   */
  checkRagServiceStatus(): void {
    this.ragServiceLoading = true;
    this.http.get<any>(`${this.backendUrl}/rag/test/status`).subscribe({
      next: (response) => {
        this.ragServiceLoading = false;

        // Parse the response from RagTestController.getStatus()
        const embeddingModel = response?.embeddingModel;
        const vectorStore = response?.vectorStore;
        const reranker = response?.reranker;
        const keywordRetriever = response?.keywordRetriever;

        this.ragServiceStatus = {
          available: true,
          embeddingModel: embeddingModel ? {
            className: embeddingModel.class || embeddingModel.className || 'Unknown',
            available: embeddingModel.available !== false,
            dimensions: embeddingModel.dimensions
          } : null,
          vectorStore: vectorStore ? {
            className: vectorStore.class || vectorStore.className || 'Unknown',
            available: vectorStore.available !== false
          } : null,
          reranker: reranker ? {
            className: reranker.class || reranker.className || 'Unknown',
            available: reranker.available !== false,
            supportedTypes: reranker.supportedTypes || []
          } : null,
          keywordRetriever: keywordRetriever ? {
            className: keywordRetriever.class || keywordRetriever.className || 'Unknown',
            available: keywordRetriever.available !== false
          } : null
        };

        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('RAG service status check failed:', err);
        this.ragServiceLoading = false;
        this.ragServiceStatus = {
          available: false,
          embeddingModel: null,
          vectorStore: null,
          reranker: null,
          keywordRetriever: null
        };
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Refresh the RAG service status.
   */
  refreshRagServiceStatus(): void {
    this.checkRagServiceStatus();
    this.showSnackbar('RAG service status refreshed');
  }

  // ==================== Service State Methods (Comprehensive Transparency) ====================

  /**
   * Load comprehensive service state from /api/services/state.
   * This provides detailed state information for all services including
   * embedding model, vector store, reranker, and document retriever.
   */
  loadServiceState(): void {
    this.serviceStateLoading = true;
    this.http.get<ServiceStateResponse>(`${this.backendUrl}/services/state`).subscribe({
      next: (response) => {
        this.serviceState = response;
        this.serviceStateLoading = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load service state:', err);
        this.serviceStateLoading = false;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Refresh the comprehensive service state.
   */
  refreshServiceState(): void {
    this.loadServiceState();
    this.showSnackbar('Service state refreshed');
  }

  /**
   * Check if all services are loaded.
   */
  areAllServicesLoaded(): boolean {
    return this.serviceState?.summary?.allServicesLoaded || false;
  }

  /**
   * Get the count of loaded services.
   */
  getLoadedServiceCount(): number {
    if (!this.serviceState?.summary) return 0;
    const summary = this.serviceState.summary;
    let count = 0;
    if (summary.embeddingModelLoaded) count++;
    if (summary.vectorStoreLoaded) count++;
    if (summary.rerankerLoaded) count++;
    if (summary.documentRetrieverLoaded) count++;
    return count;
  }

  /**
   * Get the total service count.
   */
  getTotalServiceCount(): number {
    return 4; // embedding, vector store, reranker, document retriever
  }

  /**
   * Get service state status icon.
   */
  getServiceStateIcon(loaded: boolean): string {
    return loaded ? 'check_circle' : 'cancel';
  }

  /**
   * Get service state status color class.
   */
  getServiceStateColorClass(loaded: boolean): string {
    return loaded ? 'status-loaded' : 'status-not-loaded';
  }

  /**
   * Check if the main RAG service is ready (has real models loaded, not NoOp).
   */
  isRagServiceReady(): boolean {
    if (!this.ragServiceStatus.available) return false;
    // Check if at least embedding model is available and not NoOp
    const embedding = this.ragServiceStatus.embeddingModel;
    return embedding !== null && embedding.available &&
           !embedding.className?.includes('NoOp');
  }

  /**
   * Get a display name for the embedding model.
   */
  getRagEmbeddingDisplayName(): string {
    const model = this.ragServiceStatus.embeddingModel;
    if (!model) return 'Not Available';
    if (model.className?.includes('NoOp')) return 'Not Configured';
    return model.className || 'Unknown';
  }

  /**
   * Get a display name for the vector store.
   */
  getRagVectorStoreDisplayName(): string {
    const store = this.ragServiceStatus.vectorStore;
    if (!store) return 'Not Available';
    if (store.className?.includes('NoOp')) return 'Not Configured';
    return store.className || 'Unknown';
  }

  /**
   * Check if the staging service is available and get its status.
   */
  checkStagingService(): void {
    this.http.get<StagingStatusResponse>(`${this.backendUrl}/models/staging/status`).subscribe({
      next: (response) => {
        this.stagingStatus = response;
        this.stagingServiceAvailable = response.connected;
        this.cdr.detectChanges();
      },
      error: (err) => {
        console.debug('Staging service not available:', err);
        this.stagingServiceAvailable = false;
        this.stagingStatus = null;
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Refresh the model registry and staging status.
   */
  refreshModelRegistry(): void {
    this.loadModelRegistry();
    this.checkStagingService();
    this.showSnackbar('Model registry refreshed');
  }

  /**
   * Get all models from the registry.
   * Uses Object.entries to preserve the modelId from the key.
   */
  getRegistryModels(): ModelRegistryEntry[] {
    if (!this.modelRegistry?.models) return [];
    return Object.entries(this.modelRegistry.models).map(([id, model]) => ({
      ...model,
      modelId: id
    }));
  }

  /**
   * Get encoder models from the registry.
   */
  getEncoderModelsFromRegistry(): ModelRegistryEntry[] {
    return this.getRegistryModels().filter(m => m.type === 'encoder');
  }

  /**
   * Get cross-encoder models from the registry.
   */
  getCrossEncoderModelsFromRegistry(): ModelRegistryEntry[] {
    return this.getRegistryModels().filter(m => m.type === 'cross_encoder');
  }

  /**
   * Get reranker models from the registry.
   */
  getRerankerModelsFromRegistry(): ModelRegistryEntry[] {
    return this.getRegistryModels().filter(m => m.type === 'reranker');
  }

  /**
   * Get active models from the registry.
   */
  getActiveModels(): ModelRegistryEntry[] {
    return this.getRegistryModels().filter(m => m.status === 'active');
  }

  /**
   * Get models in staging (being processed).
   */
  getModelsInStaging(): StagingModelInfo[] {
    return this.stagingStatus?.modelsInStaging || [];
  }

  /**
   * Get count of models in registry by type.
   */
  getRegistryModelCount(type?: string): number {
    if (!type) return this.getRegistryModels().length;
    return this.getRegistryModels().filter(m => m.type === type).length;
  }

  /**
   * Get CSS class for a model status.
   */
  getRegistryStatusColor(status: string): string {
    return getModelRegistryStatusColor(status as any);
  }

  /**
   * Get icon for a model status.
   */
  getRegistryStatusIcon(status: string): string {
    return getModelRegistryStatusIcon(status as any);
  }

  /**
   * Get icon for staging status.
   */
  getStagingIcon(status: string): string {
    return getStagingStatusIcon(status as any);
  }

  /**
   * Get CSS class for staging status.
   */
  getStagingColor(status: string): string {
    return getStagingStatusColor(status as any);
  }

  /**
   * Get human-readable display name for a model type.
   * Handles both legacy and new naming conventions.
   */
  getModelTypeDisplayName(type: string): string {
    switch (type) {
      case 'encoder':
      case 'dense_encoder': return 'Dense Encoder';
      case 'sparse_encoder': return 'Sparse Encoder';
      case 'cross_encoder': return 'Cross-Encoder';
      case 'reranker': return 'Reranker';
      default: return type || 'Unknown';
    }
  }

  /**
   * Get icon for a model type.
   * Handles both legacy and new naming conventions.
   */
  getModelTypeIcon(type: string): string {
    switch (type) {
      case 'encoder':
      case 'dense_encoder': return 'text_fields';
      case 'sparse_encoder': return 'scatter_plot';
      case 'cross_encoder': return 'compare_arrows';
      case 'reranker': return 'sort';
      default: return 'memory';
    }
  }

  /**
   * Check if the model registry has any models.
   */
  hasRegistryModels(): boolean {
    return this.getRegistryModels().length > 0;
  }

  /**
   * Check if there are models in staging.
   */
  hasModelsInStaging(): boolean {
    return this.getModelsInStaging().length > 0;
  }

  /**
   * Format model metadata for display.
   */
  formatModelMetadata(model: ModelRegistryEntry): string {
    const parts: string[] = [];
    if (model.metadata?.embeddingDim) {
      parts.push(`${model.metadata.embeddingDim}d`);
    }
    if (model.metadata?.maxSequenceLength) {
      parts.push(`max ${model.metadata.maxSequenceLength} tokens`);
    }
    if (model.metadata?.framework) {
      parts.push(model.metadata.framework);
    }
    return parts.join(' • ');
  }

  /**
   * Format promoted date for display.
   */
  formatPromotedDate(dateStr: string | undefined): string {
    if (!dateStr) return 'Unknown';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
    } catch {
      return dateStr;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // VERSION & PROVENANCE DISPLAY
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get count of active models in the registry.
   */
  getActiveModelCount(): number {
    if (!this.modelRegistry?.models) return 0;
    return Object.values(this.modelRegistry.models)
      .filter(m => m.status === 'active').length;
  }

  /**
   * Get installed archives from the registry.
   */
  getInstalledArchives(): any[] {
    if (!this.modelRegistry?.installedArchives) return [];
    return Object.values(this.modelRegistry.installedArchives);
  }

  /**
   * Format model source/provenance for display.
   */
  formatModelSource(model: ModelRegistryEntry): string {
    const source = model.metadata?.installedFrom;
    if (source === 'archive') {
      const archiveId = model.metadata?.sourceArchiveId;
      const version = model.metadata?.sourceArchiveVersion;
      if (archiveId) {
        return version ? `${archiveId} v${version}` : archiveId;
      }
      return version ? `Archive v${version}` : 'Archive';
    }
    if (source === 'staging') {
      const version = model.metadata?.stagingRegistryVersion;
      return version ? `Staging v${version}` : 'Staging';
    }
    if (source === 'builtin') return 'Built-in';
    if (source === 'manual') return 'Manual Upload';
    if (source === 'download') return 'Downloaded';
    if (source === 'local') return 'Local';
    // If no source but has path, try to infer
    if (!source && model.path) {
      if (model.path.includes('archive')) return 'Archive';
      if (model.path.includes('builtin')) return 'Built-in';
    }
    return 'Registry';
  }

  /**
   * Get icon for the source type.
   */
  getSourceIcon(model: ModelRegistryEntry): string {
    const source = model.metadata?.installedFrom;
    switch (source) {
      case 'archive': return 'archive';
      case 'staging': return 'cloud_download';
      case 'builtin': return 'inventory_2';
      case 'manual': return 'upload_file';
      case 'download': return 'download';
      case 'local': return 'folder';
      default: return 'storage';
    }
  }

  /**
   * Get CSS class for source badge styling.
   */
  getSourceClass(model: ModelRegistryEntry): string {
    const source = model.metadata?.installedFrom;
    return `source-${source || 'unknown'}`;
  }

  /**
   * Get the effective version of a model.
   */
  getModelVersion(model: ModelRegistryEntry): string | null {
    if (model.version) return model.version;
    if (model.metadata?.version) return model.metadata.version;
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ARCHIVE MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load archive data (available archives, status, models).
   */
  loadArchiveData(): void {
    // Subscribe to archive status
    this.archiveService.status$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.currentArchiveStatus = status;
        this.cdr.markForCheck();
      });

    // Subscribe to archive models
    this.archiveService.models$
      .pipe(takeUntil(this.destroy$))
      .subscribe(models => {
        this.archiveModels = models;
        this.cdr.markForCheck();
      });

    // Subscribe to available archives
    this.archiveService.archives$
      .pipe(takeUntil(this.destroy$))
      .subscribe(archives => {
        this.availableArchives = archives;
        this.cdr.markForCheck();
      });

    // Initial load
    this.refreshArchiveData();
  }

  /**
   * Refresh archive data from the server.
   */
  refreshArchiveData(): void {
    this.isLoadingArchives = true;

    // Load archive status
    this.archiveService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          if (status?.loaded) {
            this.archiveService.loadModels()
              .pipe(takeUntil(this.destroy$))
              .subscribe();
          }
        },
        error: () => {
          console.warn('Could not load archive status');
        }
      });

    // Load available archives
    this.archiveService.listArchives()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isLoadingArchives = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.isLoadingArchives = false;
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Load a specific archive.
   */
  loadArchive(archivePath: string): void {
    this.isLoadingArchives = true;
    this.archiveService.loadArchive(archivePath)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => {
          this.isLoadingArchives = false;
          if (status.loaded) {
            this.snackBar.open(`Archive loaded: ${status.archiveId || archivePath}`, 'OK', { duration: 3000 });
          }
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.isLoadingArchives = false;
          this.snackBar.open('Failed to load archive', 'OK', { duration: 3000 });
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Unload the current archive.
   */
  unloadArchive(): void {
    this.isLoadingArchives = true;
    this.archiveService.unloadArchive()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isLoadingArchives = false;
          this.snackBar.open('Archive unloaded', 'OK', { duration: 3000 });
          this.cdr.markForCheck();
        },
        error: () => {
          this.isLoadingArchives = false;
          this.snackBar.open('Failed to unload archive', 'OK', { duration: 3000 });
          this.cdr.markForCheck();
        }
      });
  }

  /**
   * Get encoder models from the loaded archive.
   */
  getArchiveEncoders(): ArchiveModelInfo[] {
    return this.archiveModels.filter(m => m.type === 'encoder');
  }

  /**
   * Get cross-encoder models from the loaded archive.
   */
  getArchiveCrossEncoders(): ArchiveModelInfo[] {
    return this.archiveModels.filter(m => m.type === 'cross_encoder');
  }

  /**
   * Check if an archive is loaded.
   */
  isArchiveLoaded(): boolean {
    return this.currentArchiveStatus?.loaded || false;
  }

  // ==================== SDZ Upload Methods ====================

  /**
   * Show the SDZ upload dialog.
   */
  showSdzUploadDialog(): void {
    this.resetSdzUploadForm();
    this.showSdzUpload = true;
  }

  /**
   * Close the SDZ upload dialog.
   */
  closeSdzUpload(): void {
    this.showSdzUpload = false;
    this.resetSdzUploadForm();
  }

  /**
   * Reset the SDZ upload form.
   */
  private resetSdzUploadForm(): void {
    this.sdzUploadFile = null;
    this.vocabUploadFile = null;
    this.sdzUploadModelId = '';
    this.sdzUploadModelType = 'dense_encoder';
    this.sdzUploadVersion = '';
    this.sdzUploadEmbeddingDim = null;
    this.sdzUploadMaxSeqLen = null;
    this.sdzUploadDescription = '';
    this.sdzUploadOverwrite = false;
    this.sdzUploading = false;
    this.sdzUploadError = null;
  }

  /**
   * Handle SDZ file selection.
   */
  onSdzFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];
      if (!file.name.toLowerCase().endsWith('.sdz')) {
        this.sdzUploadError = 'Please select an .sdz file';
        return;
      }
      this.sdzUploadFile = file;
      this.sdzUploadError = null;

      // Auto-populate model ID from filename if empty
      if (!this.sdzUploadModelId) {
        const nameWithoutExt = file.name.replace(/\.sdz$/i, '');
        this.sdzUploadModelId = nameWithoutExt.replace(/[^a-zA-Z0-9-_]/g, '-');
      }
    }
  }

  /**
   * Handle vocab file selection.
   */
  onVocabFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.vocabUploadFile = input.files[0];
    }
  }

  /**
   * Format file size for display.
   */
  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  /**
   * Submit the SDZ upload.
   */
  submitSdzUpload(): void {
    if (!this.sdzUploadFile || !this.sdzUploadModelId) {
      this.sdzUploadError = 'Model file and Model ID are required';
      return;
    }

    this.sdzUploading = true;
    this.sdzUploadError = null;

    const formData = new FormData();
    formData.append('modelFile', this.sdzUploadFile);
    if (this.vocabUploadFile) {
      formData.append('vocabFile', this.vocabUploadFile);
    }
    formData.append('modelId', this.sdzUploadModelId);
    formData.append('modelType', this.sdzUploadModelType);
    if (this.sdzUploadVersion) {
      formData.append('version', this.sdzUploadVersion);
    }
    if (this.sdzUploadEmbeddingDim) {
      formData.append('embeddingDim', this.sdzUploadEmbeddingDim.toString());
    }
    if (this.sdzUploadMaxSeqLen) {
      formData.append('maxSequenceLength', this.sdzUploadMaxSeqLen.toString());
    }
    if (this.sdzUploadDescription) {
      formData.append('description', this.sdzUploadDescription);
    }
    formData.append('overwrite', this.sdzUploadOverwrite.toString());

    this.http.post<any>(`${this.backendUrl}/staging-config/remote/upload-sdz`, formData)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.sdzUploading = false;
          if (response.success) {
            this.snackBar.open(`Model "${this.sdzUploadModelId}" registered successfully`, 'OK', { duration: 5000 });
            this.closeSdzUpload();
            this.refreshModelRegistry();
          } else {
            this.sdzUploadError = response.error || 'Upload failed';
          }
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.sdzUploading = false;
          this.sdzUploadError = err.error?.error || err.message || 'Upload failed';
          this.cdr.markForCheck();
        }
      });
  }
}
