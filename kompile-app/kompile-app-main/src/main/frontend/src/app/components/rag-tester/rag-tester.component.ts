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
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { WebSocketService } from '../../services/websocket.service';
import { ModelStatusUpdate } from '../../models/api-models';

interface RagStatus {
  keywordRetriever: { class: string; available: boolean };
  vectorStore: { class: string; available: boolean };
  embeddingModel: { class: string; available: boolean };
  reranker: { class: string; available: boolean; supportedTypes: string[] };
  graphRag: { class: string; available: boolean; searchTypes: string[] };
}

interface GraphRagSearchType {
  id: string;
  name: string;
  description: string;
}

interface GraphRagInfo {
  available: boolean;
  class: string;
  fullClass?: string;
  type: string;
  description: string;
  searchTypes: GraphRagSearchType[];
}

interface GraphRagQueryResponse {
  query: string;
  searchType: string;
  k: number;
  conversationId: string;
  available: boolean;
  serviceClass?: string;
  durationMs?: number;
  answer?: string;
  context?: string;
  contextPreview?: string;
  contextLength?: number;
  error?: string;
  searchTypeWarning?: string;
}

interface RerankerParam {
  id: string;
  label: string;
  type: 'number' | 'boolean' | 'select' | 'string';
  default: number | boolean | string;
  min?: number;
  max?: number;
  options?: string[];  // For 'select' type parameters (e.g., cross-encoder models)
}

interface RerankerTypeInfo {
  id: string;
  name: string;
  description: string;
  parameters: RerankerParam[];
  supported: boolean;
}

interface RerankerInfo {
  available: boolean;
  types: RerankerTypeInfo[];
}

interface RerankingHit extends SearchHit {
  newRank?: number;
  originalRank?: number;
  rankChange?: number;
}

interface RerankingResponse {
  query: string;
  maxResults: number;
  threshold: number;
  initialDurationMs: number;
  initialCount: number;
  initialHits: SearchHit[];
  rerankerType: string;
  rerankerDescription: string;
  reranked: boolean;
  rerankDurationMs: number;
  rerankedCount?: number;
  rerankedHits: RerankingHit[];
  rerankerConfig?: any;
  totalHits: number;
  error?: string;
}

interface SearchHit {
  id: string;
  score: number;
  contentLength: number;
  preview: string;
  content: string;
  metadata?: any;
  sources?: string[];
}

interface SearchResult {
  type: string;
  retriever?: string;
  vectorStore?: string;
  durationMs: number;
  count: number;
  hits: SearchHit[];
  error?: string;
}

interface QueryResponse {
  query: string;
  maxResults: number;
  threshold: number;
  results: SearchResult[];
  totalHits: number;
}

interface HybridResponse {
  query: string;
  maxResults: number;
  hits: SearchHit[];
  totalHits: number;
  keywordError?: string;
  semanticError?: string;
}

interface EmbedTiming {
  totalWallClockMs: number;
  subprocessInferenceMs: number;
  subprocessOverheadMs: number;
  overheadPercent?: number;
}

interface EmbedResponse {
  text: string;
  embeddingModel: string;
  dimensions?: number;
  shape?: string;
  durationMs?: number;
  timing?: EmbedTiming;  // Detailed timing breakdown (subprocess overhead vs inference)
  preview?: number[];
  error?: string;
}

@Component({
  selector: 'app-rag-tester',
  standalone: false,
  templateUrl: './rag-tester.component.html',
  styleUrls: ['./rag-tester.component.css']
})
export class RagTesterComponent implements OnInit, OnDestroy {

  private backendUrl: string;
  private destroy$ = new Subject<void>();
  private modelStatusSubscribed = false;

  // Status
  status: RagStatus | null = null;
  statusLoading = false;
  embeddingModelError: string | null = null;
  embeddingModelInitialized = false;
  embeddingModelLoading = false;
  embeddingModelLoadingPhase: string | null = null;

  // Query form
  query = '';
  maxResults = 5;
  threshold = 0.0;
  includeKeyword = true;
  includeSemantic = true;
  useHybrid = false;

  // Results
  queryResponse: QueryResponse | null = null;
  hybridResponse: HybridResponse | null = null;
  isSearching = false;

  // Embedding test
  embedText = '';
  embedResponse: EmbedResponse | null = null;
  isEmbedding = false;

  // UI state
  expandedHitId: string | null = null;

  // Reranking
  rerankerInfo: RerankerInfo | null = null;
  selectedRerankerType = 'none';
  useReranking = false;
  rerankingResponse: RerankingResponse | null = null;
  isRerankingSearch = false;

  // Reranker parameters (with defaults) - supports number, boolean, and string types
  rerankerParams: { [key: string]: number | boolean | string } = {
    // Common parameters
    fbDocs: 10,
    fbTerms: 10,
    topK: -1,
    // RM3 parameters
    originalQueryWeight: 0.5,
    filterTerms: true,
    outputQuery: false,
    // BM25-PRF parameters
    k1: 0.9,
    b: 0.4,
    newTermWeight: 0.2,
    // Rocchio parameters
    alpha: 1.0,
    beta: 0.75,
    gamma: 0.15,
    useNegative: false,
    // Axiom parameters
    r: 20,
    n: 30,
    axiomBeta: 0.4,
    deterministic: true,
    seed: 42,
    // Cross-encoder parameters
    crossEncoderModel: 'ms-marco-MiniLM-L-6-v2'
  };

  // Graph RAG
  graphRagInfo: GraphRagInfo | null = null;
  graphRagQuery = '';
  graphRagSearchType = 'LOCAL';
  graphRagMaxResults = 5;
  graphRagConversationId = 'test';
  graphRagResponse: GraphRagQueryResponse | null = null;
  isGraphRagSearching = false;
  showGraphRagContext = false;

  constructor(
    private http: HttpClient,
    private router: Router,
    private snackBar: MatSnackBar,
    private websocketService: WebSocketService,
    private cdr: ChangeDetectorRef
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
    this.loadStatus();
    this.loadRerankers();
    this.loadGraphRagInfo();
    // Subscribe to WebSocket model status updates for real-time UI updates
    this.subscribeToModelStatusUpdates();
  }

  ngOnDestroy(): void {
    // Unsubscribe from WebSocket model status
    this.websocketService.unsubscribeFromModelStatus();
    this.destroy$.next();
    this.destroy$.complete();
  }

  /**
   * Subscribe to WebSocket model status updates for real-time UI updates.
   * This provides push-based updates when model status changes.
   */
  private subscribeToModelStatusUpdates(): void {
    if (this.modelStatusSubscribed) {
      return;
    }

    // Connect WebSocket and subscribe to model status
    this.websocketService.connect();
    this.modelStatusSubscribed = true;

    this.websocketService.subscribeToModelStatus().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (status: ModelStatusUpdate) => {
        this.handleModelStatusUpdate(status);
      },
      error: (err) => {
        console.error('[WS-MODEL] Error in model status WebSocket:', err);
      }
    });

    console.log('[WS-MODEL] RAG Tester subscribed to model status updates');
  }

  /**
   * Handle incoming model status updates from WebSocket.
   */
  private handleModelStatusUpdate(status: ModelStatusUpdate): void {
    if (!status.embedding) return;

    const isNowInitialized = status.embedding.initialized && (status.embedding.dimensions || 0) > 0;

    // Track detailed embedding status
    this.embeddingModelInitialized = isNowInitialized;
    this.embeddingModelLoading = status.embedding.loading || false;
    this.embeddingModelLoadingPhase = status.embedding.loadingPhase || null;
    this.embeddingModelError = status.embedding.error || null;

    // Update the local status if we have one
    if (this.status) {
      const wasAvailable = this.status.embeddingModel?.available;
      this.status.embeddingModel = {
        ...this.status.embeddingModel,
        available: isNowInitialized
      };

      // Show notification when model becomes ready
      if (!wasAvailable && isNowInitialized) {
        console.log('[WS-MODEL] Embedding model is now ready in RAG Tester!');
        this.showSnackbar('Embedding model is now ready!');
        this.embeddingModelError = null; // Clear error on success
        // Refresh full status to get accurate info
        this.loadStatus();
      }
    }

    // Trigger change detection to update UI
    this.cdr.detectChanges();
  }

  /**
   * Check if embedding model is ready to use.
   * Returns true only if initialized and not loading.
   */
  isEmbeddingReady(): boolean {
    return this.embeddingModelInitialized && !this.embeddingModelLoading;
  }

  /**
   * Get the embedding status message for display.
   */
  getEmbeddingStatusMessage(): string {
    if (this.embeddingModelLoading) {
      return this.embeddingModelLoadingPhase
        ? `Loading model (${this.embeddingModelLoadingPhase})...`
        : 'Loading model...';
    }
    if (this.embeddingModelError) {
      return this.embeddingModelError;
    }
    if (!this.embeddingModelInitialized) {
      return 'Embedding model not loaded';
    }
    return 'Ready';
  }

  loadStatus(): void {
    this.statusLoading = true;
    this.http.get<RagStatus>(`${this.backendUrl}/rag/test/status`).subscribe({
      next: (status) => {
        this.status = status;
        this.statusLoading = false;
        // Also load detailed embedding model status
        this.loadEmbeddingModelStatus();
      },
      error: (err) => {
        this.showSnackbar('Failed to load RAG status: ' + (err.error?.message || err.message), true);
        this.statusLoading = false;
      }
    });
  }

  /**
   * Load detailed embedding model status from the model status endpoint.
   */
  private loadEmbeddingModelStatus(): void {
    this.http.get<any>(`${this.backendUrl}/models/embedding/status`).subscribe({
      next: (modelStatus) => {
        // Update embedding status fields
        this.embeddingModelInitialized = modelStatus.initialized && (modelStatus.dimensions || 0) > 0;
        this.embeddingModelLoading = modelStatus.loading || false;
        this.embeddingModelLoadingPhase = modelStatus.loadingPhase || null;
        this.embeddingModelError = modelStatus.initializationError || modelStatus.error || null;

        // Update the status.embeddingModel.available to reflect true initialized state
        if (this.status) {
          this.status.embeddingModel = {
            ...this.status.embeddingModel,
            available: this.embeddingModelInitialized
          };
        }

        this.cdr.detectChanges();
      },
      error: (err) => {
        console.error('Failed to load embedding model status:', err);
      }
    });
  }

  runQuery(): void {
    if (!this.query.trim()) {
      this.showSnackbar('Please enter a query', true);
      return;
    }

    this.isSearching = true;
    this.queryResponse = null;
    this.hybridResponse = null;

    if (this.useHybrid) {
      this.runHybridQuery();
    } else {
      this.runStandardQuery();
    }
  }

  private runStandardQuery(): void {
    const params: any = {
      q: this.query,
      k: this.maxResults,
      threshold: this.threshold,
      keyword: this.includeKeyword,
      semantic: this.includeSemantic
    };

    this.http.get<QueryResponse>(`${this.backendUrl}/rag/test/query`, { params }).subscribe({
      next: (response) => {
        this.queryResponse = response;
        this.isSearching = false;
        this.showSnackbar(`Found ${response.totalHits} results`);
      },
      error: (err) => {
        this.showSnackbar('Query failed: ' + (err.error?.message || err.message), true);
        this.isSearching = false;
      }
    });
  }

  private runHybridQuery(): void {
    const params: any = {
      q: this.query,
      k: this.maxResults,
      threshold: this.threshold
    };

    this.http.get<HybridResponse>(`${this.backendUrl}/rag/test/hybrid`, { params }).subscribe({
      next: (response) => {
        this.hybridResponse = response;
        this.isSearching = false;
        this.showSnackbar(`Found ${response.totalHits} results (hybrid)`);
      },
      error: (err) => {
        this.showSnackbar('Hybrid query failed: ' + (err.error?.message || err.message), true);
        this.isSearching = false;
      }
    });
  }

  testEmbedding(): void {
    if (!this.embedText.trim()) {
      this.showSnackbar('Please enter text to embed', true);
      return;
    }

    this.isEmbedding = true;
    this.embedResponse = null;

    const params = { text: this.embedText };

    this.http.get<EmbedResponse>(`${this.backendUrl}/rag/test/embed`, { params }).subscribe({
      next: (response) => {
        this.embedResponse = response;
        this.isEmbedding = false;
        if (response.error) {
          this.showSnackbar('Embedding error: ' + response.error, true);
        } else {
          // Show timing breakdown in snackbar if available
          if (response.timing) {
            this.showSnackbar(
              `Generated ${response.dimensions}-dim embedding: ${response.timing.subprocessInferenceMs}ms inference + ${response.timing.subprocessOverheadMs}ms overhead = ${response.durationMs}ms total`
            );
          } else {
            this.showSnackbar(`Generated ${response.dimensions}-dim embedding in ${response.durationMs}ms`);
          }
        }
      },
      error: (err) => {
        this.showSnackbar('Embedding failed: ' + (err.error?.message || err.message), true);
        this.isEmbedding = false;
      }
    });
  }

  toggleHitExpansion(hitId: string): void {
    this.expandedHitId = this.expandedHitId === hitId ? null : hitId;
  }

  isHitExpanded(hitId: string): boolean {
    return this.expandedHitId === hitId;
  }

  copyContent(content: string): void {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(content)
        .then(() => this.showSnackbar('Content copied to clipboard'))
        .catch(() => this.showSnackbar('Failed to copy', true));
    }
  }

  getSourceBadgeClass(source: string): string {
    return source === 'keyword' ? 'badge-keyword' : 'badge-semantic';
  }

  formatScore(score: number | undefined): string {
    if (score === undefined || score === null) return 'N/A';
    return score.toFixed(4);
  }

  getKeywordResults(): SearchResult | null {
    if (!this.queryResponse?.results) return null;
    return this.queryResponse.results.find(r => r.type === 'keyword') || null;
  }

  getSemanticResults(): SearchResult | null {
    if (!this.queryResponse?.results) return null;
    return this.queryResponse.results.find(r => r.type === 'semantic') || null;
  }

  // Reranking methods
  loadRerankers(): void {
    this.http.get<RerankerInfo>(`${this.backendUrl}/rag/test/rerankers`).subscribe({
      next: (info) => {
        this.rerankerInfo = info;
      },
      error: (err) => {
        console.error('Failed to load rerankers:', err);
      }
    });
  }

  getSelectedRerankerType(): RerankerTypeInfo | null {
    if (!this.rerankerInfo || this.selectedRerankerType === 'none') return null;
    return this.rerankerInfo.types.find(t => t.id === this.selectedRerankerType) || null;
  }

  onRerankerTypeChange(): void {
    const typeInfo = this.getSelectedRerankerType();
    if (typeInfo && typeInfo.parameters) {
      // Reset parameters to defaults for the selected reranker
      typeInfo.parameters.forEach(param => {
        this.rerankerParams[param.id] = param.default;
      });
    }
  }

  runRerankingQuery(): void {
    if (!this.query.trim()) {
      this.showSnackbar('Please enter a query', true);
      return;
    }

    this.isRerankingSearch = true;
    this.rerankingResponse = null;

    // Build params object with all reranker parameters
    const params: any = {
      q: this.query,
      k: this.maxResults,
      threshold: this.threshold,
      rerankerType: this.selectedRerankerType,
      // Common parameters
      fbDocs: this.rerankerParams['fbDocs'],
      fbTerms: this.rerankerParams['fbTerms'],
      topK: this.rerankerParams['topK'],
      // RM3 parameters
      originalQueryWeight: this.rerankerParams['originalQueryWeight'],
      filterTerms: this.rerankerParams['filterTerms'],
      outputQuery: this.rerankerParams['outputQuery'],
      // BM25-PRF parameters
      k1: this.rerankerParams['k1'],
      b: this.rerankerParams['b'],
      newTermWeight: this.rerankerParams['newTermWeight'],
      // Rocchio parameters
      alpha: this.rerankerParams['alpha'],
      beta: this.rerankerParams['beta'],
      gamma: this.rerankerParams['gamma'],
      useNegative: this.rerankerParams['useNegative'],
      // Axiom parameters
      r: this.rerankerParams['r'],
      n: this.rerankerParams['n'],
      axiomBeta: this.rerankerParams['axiomBeta'],
      deterministic: this.rerankerParams['deterministic'],
      seed: this.rerankerParams['seed'],
      // Cross-encoder parameters
      crossEncoderModel: this.rerankerParams['crossEncoderModel']
    };

    this.http.get<RerankingResponse>(`${this.backendUrl}/rag/test/query-with-reranking`, { params }).subscribe({
      next: (response) => {
        this.rerankingResponse = response;
        this.isRerankingSearch = false;
        if (response.error) {
          this.showSnackbar('Reranking error: ' + response.error, true);
        } else {
          const rerankInfo = response.reranked
            ? ` (reranked in ${response.rerankDurationMs}ms)`
            : '';
          this.showSnackbar(`Found ${response.totalHits} results in ${response.initialDurationMs}ms${rerankInfo}`);
        }
      },
      error: (err) => {
        this.showSnackbar('Reranking query failed: ' + (err.error?.message || err.message), true);
        this.isRerankingSearch = false;
      }
    });
  }

  getRankChangeClass(change: number | undefined): string {
    if (change === undefined || change === 0) return 'rank-unchanged';
    return change > 0 ? 'rank-up' : 'rank-down';
  }

  getRankChangeIcon(change: number | undefined): string {
    if (change === undefined || change === 0) return 'remove';
    return change > 0 ? 'arrow_upward' : 'arrow_downward';
  }

  formatRankChange(change: number | undefined): string {
    if (change === undefined || change === 0) return '-';
    return change > 0 ? `+${change}` : `${change}`;
  }

  // Helper methods for parameter type detection and handling
  isNumberParam(param: RerankerParam): boolean {
    return param.type === 'number';
  }

  isBooleanParam(param: RerankerParam): boolean {
    return param.type === 'boolean';
  }

  isSelectParam(param: RerankerParam): boolean {
    return param.type === 'select';
  }

  isStringParam(param: RerankerParam): boolean {
    return param.type === 'string';
  }

  // Get the step value for number inputs
  getParamStep(param: RerankerParam): number {
    if (param.type !== 'number') return 1;
    // Use smaller steps for parameters with max <= 1 (like weights)
    if (param.max !== undefined && param.max <= 1) return 0.05;
    // Use smaller steps for decimal-looking defaults
    const defaultVal = param.default;
    if (typeof defaultVal === 'number' && !Number.isInteger(defaultVal)) return 0.1;
    return 1;
  }

  // Get boolean value for a parameter
  getParamBoolValue(paramId: string): boolean {
    const val = this.rerankerParams[paramId];
    return typeof val === 'boolean' ? val : false;
  }

  // Set boolean value for a parameter
  setParamBoolValue(paramId: string, value: boolean): void {
    this.rerankerParams[paramId] = value;
  }

  // Get number value for a parameter
  getParamNumberValue(paramId: string): number {
    const val = this.rerankerParams[paramId];
    return typeof val === 'number' ? val : 0;
  }

  // Set number value for a parameter
  setParamNumberValue(paramId: string, value: number): void {
    this.rerankerParams[paramId] = value;
  }

  // Get string value for a parameter
  getParamStringValue(paramId: string): string {
    const val = this.rerankerParams[paramId];
    return typeof val === 'string' ? val : '';
  }

  // Set string value for a parameter
  setParamStringValue(paramId: string, value: string): void {
    this.rerankerParams[paramId] = value;
  }

  // Filter parameters by type for organized display
  getNumberParams(): RerankerParam[] {
    const typeInfo = this.getSelectedRerankerType();
    if (!typeInfo?.parameters) return [];
    return typeInfo.parameters.filter(p => p.type === 'number');
  }

  getBooleanParams(): RerankerParam[] {
    const typeInfo = this.getSelectedRerankerType();
    if (!typeInfo?.parameters) return [];
    return typeInfo.parameters.filter(p => p.type === 'boolean');
  }

  getSelectParams(): RerankerParam[] {
    const typeInfo = this.getSelectedRerankerType();
    if (!typeInfo?.parameters) return [];
    return typeInfo.parameters.filter(p => p.type === 'select');
  }

  // Get reranker type icon based on type
  getRerankerIcon(typeId: string): string {
    switch (typeId) {
      case 'rm3': return 'autorenew';
      case 'bm25prf': return 'tune';
      case 'rocchio': return 'compare_arrows';
      case 'axiom': return 'analytics';
      case 'cross_encoder': return 'psychology';
      case 'score_ties': return 'swap_vert';
      case 'rrf': return 'merge_type';
      case 'normalize': return 'straighten';
      case 'mmr': return 'diversity_3';
      default: return 'sort';
    }
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  // Graph RAG Methods
  loadGraphRagInfo(): void {
    this.http.get<GraphRagInfo>(`${this.backendUrl}/rag/test/graph/info`).subscribe({
      next: (info) => {
        this.graphRagInfo = info;
      },
      error: (err) => {
        console.error('Failed to load Graph RAG info:', err);
      }
    });
  }

  runGraphRagQuery(): void {
    if (!this.graphRagQuery.trim()) {
      this.showSnackbar('Please enter a query', true);
      return;
    }

    this.isGraphRagSearching = true;
    this.graphRagResponse = null;

    const params: any = {
      q: this.graphRagQuery,
      searchType: this.graphRagSearchType,
      k: this.graphRagMaxResults,
      conversationId: this.graphRagConversationId
    };

    this.http.get<GraphRagQueryResponse>(`${this.backendUrl}/rag/test/graph/query`, { params }).subscribe({
      next: (response) => {
        this.graphRagResponse = response;
        this.isGraphRagSearching = false;
        if (response.error) {
          this.showSnackbar('Graph RAG error: ' + response.error, true);
        } else {
          this.showSnackbar(`Graph RAG query completed in ${response.durationMs}ms`);
        }
      },
      error: (err) => {
        this.showSnackbar('Graph RAG query failed: ' + (err.error?.message || err.message), true);
        this.isGraphRagSearching = false;
      }
    });
  }

  toggleGraphRagContext(): void {
    this.showGraphRagContext = !this.showGraphRagContext;
  }

  getGraphRagSearchTypeDescription(): string {
    if (!this.graphRagInfo?.searchTypes) return '';
    const type = this.graphRagInfo.searchTypes.find(t => t.id === this.graphRagSearchType);
    return type?.description || '';
  }

  getGraphRagTypeIcon(): string {
    if (!this.graphRagInfo) return 'account_tree';
    switch (this.graphRagInfo.type) {
      case 'neo4j': return 'hub';
      case 'matrix': return 'grid_on';
      default: return 'account_tree';
    }
  }

  copyGraphRagContent(content: string): void {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(content)
        .then(() => this.showSnackbar('Content copied to clipboard'))
        .catch(() => this.showSnackbar('Failed to copy', true));
    }
  }

  clearGraphRagConversation(): void {
    this.graphRagConversationId = 'test-' + Date.now();
    this.showSnackbar('Conversation reset with new ID');
  }

  /**
   * Navigate to the Developer Hub > System > Embedding Subprocess logs tab.
   * This helps users diagnose embedding model failures.
   */
  navigateToEmbeddingLogs(): void {
    // Navigate to developer-hub with query params to select the System tab (index 4)
    // and the Embedding Subprocess sub-tab (index 3)
    this.router.navigate(['/developer-hub'], {
      queryParams: {
        tab: 4,       // System tab
        subtab: 3     // Embedding Subprocess sub-tab
      }
    });
    this.showSnackbar('Navigate to Developer Hub > System > Embedding Subprocess to view logs');
  }
}
