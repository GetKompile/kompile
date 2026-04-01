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
import { Observable, forkJoin, of } from 'rxjs';
import { map, catchError } from 'rxjs/operators';
import { BaseService } from './base.service';

/**
 * Status level for a diagnostic check.
 */
export type DiagnosticStatus = 'pass' | 'warning' | 'fail' | 'unknown';

/**
 * Editable setting configuration for a diagnostic check.
 */
export interface EditableSetting {
  /** The key used to identify this setting when saving (e.g., 'vectorStorePath') */
  settingKey: string;
  /** Type of input control to use */
  inputType: 'text' | 'path' | 'number' | 'select';
  /** Label to show for the input */
  label: string;
  /** Placeholder text */
  placeholder?: string;
  /** Options for select inputs */
  options?: { value: string; label: string }[];
}

/**
 * Individual diagnostic check result.
 */
export interface DiagnosticCheck {
  id: string;
  name: string;
  category: string;
  status: DiagnosticStatus;
  message: string;
  details?: string;
  recommendation?: string;
  value?: any;
  /** If present, this check shows an editable setting */
  editable?: EditableSetting;
}

/**
 * Category summary for diagnostics.
 */
export interface DiagnosticCategory {
  name: string;
  icon: string;
  checks: DiagnosticCheck[];
  overallStatus: DiagnosticStatus;
  passCount: number;
  warningCount: number;
  failCount: number;
}

/**
 * Full diagnostic report.
 */
export interface DiagnosticReport {
  timestamp: string;
  overallStatus: DiagnosticStatus;
  categories: DiagnosticCategory[];
  totalChecks: number;
  passedChecks: number;
  warningChecks: number;
  failedChecks: number;
  prerequisites: PrerequisiteStatus;
}

/**
 * Prerequisites status for key features.
 */
export interface PrerequisiteStatus {
  vectorSearch: {
    ready: boolean;
    missing: string[];
  };
  ragQuery: {
    ready: boolean;
    missing: string[];
  };
  documentIngestion: {
    ready: boolean;
    missing: string[];
  };
  reranking: {
    ready: boolean;
    missing: string[];
  };
}

/**
 * Raw system status from backend.
 */
interface RawSystemStatus {
  embeddingModel?: {
    class?: string;
    dimensions?: number;
    available?: boolean;
    modelId?: string;
  };
  reranker?: {
    available?: boolean;
    class?: string;
    supportedTypes?: string[];
  };
  vectorStore?: {
    class?: string;
    available?: boolean;
    path?: string;
    documentCount?: number;
  };
  keywordIndex?: {
    available?: boolean;
    path?: string;
    documentCount?: number;
  };
}

/**
 * Service for running comprehensive system diagnostics.
 */
@Injectable({
  providedIn: 'root'
})
export class DiagnosticService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * Run all diagnostic checks and return a comprehensive report.
   */
  runDiagnostics(): Observable<DiagnosticReport> {
    return forkJoin({
      systemStatus: this.getSystemStatus(),
      embeddingStatus: this.getEmbeddingStatus(),
      factSheetStatus: this.getFactSheetStatus(),
      indexerStatus: this.getIndexerStatus(),
      llmStatus: this.getLlmStatus(),
      mcpStatus: this.getMcpStatus(),
      memoryStatus: this.getMemoryStatus(),
      environmentStatus: this.getEnvironmentStatus()
    }).pipe(
      map(results => this.buildReport(results)),
      catchError(err => {
        console.error('Diagnostic error:', err);
        return of(this.buildErrorReport(err));
      })
    );
  }

  private getSystemStatus(): Observable<RawSystemStatus> {
    return this.http.get<any>(`${this.backendUrl}/rag/test/status`).pipe(
      catchError(() => of({}))
    );
  }

  private getEmbeddingStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/models/embedding/status`).pipe(
      catchError(() => of({ available: false }))
    );
  }

  private getFactSheetStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/fact-sheets/active`).pipe(
      catchError(() => of(null))
    );
  }

  private getIndexerStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/indexer/status`).pipe(
      catchError(() => of({}))
    );
  }

  private getLlmStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/llm/status`).pipe(
      catchError(() => of({ available: false }))
    );
  }

  private getMcpStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/mcp/servers`).pipe(
      catchError(() => of([]))
    );
  }

  private getMemoryStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/system/memory`).pipe(
      catchError(() => of({}))
    );
  }

  private getEnvironmentStatus(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}/environment/status`).pipe(
      catchError(() => of({}))
    );
  }

  private buildReport(results: any): DiagnosticReport {
    const checks: DiagnosticCheck[] = [];

    // === EMBEDDING MODEL CHECKS ===
    checks.push(this.checkEmbeddingModel(results.embeddingStatus, results.systemStatus));
    checks.push(this.checkEmbeddingDimensions(results.embeddingStatus));
    checks.push(this.checkEmbeddingSource(results.embeddingStatus));

    // === VECTOR STORE CHECKS ===
    checks.push(this.checkVectorStore(results.systemStatus, results.indexerStatus));
    checks.push(this.checkVectorStorePath(results.indexerStatus, results.factSheetStatus));
    checks.push(this.checkVectorStoreDocuments(results.indexerStatus));

    // === KEYWORD INDEX CHECKS ===
    checks.push(this.checkKeywordIndex(results.indexerStatus));
    checks.push(this.checkKeywordIndexPath(results.indexerStatus, results.factSheetStatus));

    // === FACT SHEET CHECKS ===
    checks.push(this.checkActiveFactSheet(results.factSheetStatus));
    checks.push(this.checkFactSheetConfiguration(results.factSheetStatus));

    // === RERANKER CHECKS ===
    checks.push(this.checkReranker(results.systemStatus));

    // === LLM CHECKS ===
    checks.push(this.checkLlmAvailability(results.llmStatus));

    // === MCP CHECKS ===
    checks.push(this.checkMcpServers(results.mcpStatus));

    // === MEMORY CHECKS ===
    checks.push(this.checkMemoryUsage(results.memoryStatus));
    checks.push(this.checkHeapMemory(results.memoryStatus));

    // === CLI/ENVIRONMENT CHECKS ===
    checks.push(this.checkKompileDataDir(results.environmentStatus));
    checks.push(this.checkModelCacheDir(results.environmentStatus));
    checks.push(this.checkSubprocessConfig(results.environmentStatus));
    checks.push(this.checkDiskSpace(results.environmentStatus));
    checks.push(this.checkLlmApiKeys(results.environmentStatus));

    // Build categories
    const categories = this.buildCategories(checks);

    // Calculate overall status
    const passedChecks = checks.filter(c => c.status === 'pass').length;
    const warningChecks = checks.filter(c => c.status === 'warning').length;
    const failedChecks = checks.filter(c => c.status === 'fail').length;

    let overallStatus: DiagnosticStatus = 'pass';
    if (failedChecks > 0) {
      overallStatus = 'fail';
    } else if (warningChecks > 0) {
      overallStatus = 'warning';
    }

    // Build prerequisites
    const prerequisites = this.buildPrerequisites(results);

    return {
      timestamp: new Date().toISOString(),
      overallStatus,
      categories,
      totalChecks: checks.length,
      passedChecks,
      warningChecks,
      failedChecks,
      prerequisites
    };
  }

  private buildErrorReport(error: any): DiagnosticReport {
    return {
      timestamp: new Date().toISOString(),
      overallStatus: 'fail',
      categories: [{
        name: 'System',
        icon: 'error',
        checks: [{
          id: 'system-error',
          name: 'System Status',
          category: 'System',
          status: 'fail',
          message: 'Failed to run diagnostics',
          details: error?.message || 'Unknown error occurred'
        }],
        overallStatus: 'fail',
        passCount: 0,
        warningCount: 0,
        failCount: 1
      }],
      totalChecks: 1,
      passedChecks: 0,
      warningChecks: 0,
      failedChecks: 1,
      prerequisites: {
        vectorSearch: { ready: false, missing: ['System error'] },
        ragQuery: { ready: false, missing: ['System error'] },
        documentIngestion: { ready: false, missing: ['System error'] },
        reranking: { ready: false, missing: ['System error'] }
      }
    };
  }

  // === CHECK IMPLEMENTATIONS ===

  private checkEmbeddingModel(embeddingStatus: any, systemStatus: any): DiagnosticCheck {
    const isLoaded = embeddingStatus?.initialized && embeddingStatus?.available;
    const modelId = embeddingStatus?.modelId || systemStatus?.embeddingModel?.modelId;
    const isNoOp = modelId?.toLowerCase().includes('noop') ||
                   systemStatus?.embeddingModel?.class?.includes('NoOp');

    if (isNoOp) {
      return {
        id: 'embedding-model',
        name: 'Embedding Model',
        category: 'Embedding',
        status: 'fail',
        message: 'NoOp embedding model detected',
        details: 'A placeholder embedding model is configured. This cannot generate real embeddings.',
        recommendation: 'Load a real embedding model from the Staging Manager.',
        value: modelId
      };
    }

    if (!isLoaded) {
      return {
        id: 'embedding-model',
        name: 'Embedding Model',
        category: 'Embedding',
        status: 'fail',
        message: 'No embedding model loaded',
        details: 'Vector search requires an embedding model to generate document embeddings.',
        recommendation: 'Go to Staging Manager and load an embedding model.',
        value: null
      };
    }

    return {
      id: 'embedding-model',
      name: 'Embedding Model',
      category: 'Embedding',
      status: 'pass',
      message: `Model loaded: ${modelId}`,
      value: modelId
    };
  }

  private checkEmbeddingDimensions(embeddingStatus: any): DiagnosticCheck {
    const dimensions = embeddingStatus?.dimensions;

    if (!dimensions || dimensions <= 0) {
      return {
        id: 'embedding-dimensions',
        name: 'Embedding Dimensions',
        category: 'Embedding',
        status: 'fail',
        message: 'Invalid embedding dimensions',
        details: 'Embedding dimensions should be a positive number (e.g., 384, 768, 1024).',
        recommendation: 'Reload the embedding model or load a different model.',
        value: dimensions
      };
    }

    return {
      id: 'embedding-dimensions',
      name: 'Embedding Dimensions',
      category: 'Embedding',
      status: 'pass',
      message: `${dimensions} dimensions`,
      value: dimensions
    };
  }

  private checkEmbeddingSource(embeddingStatus: any): DiagnosticCheck {
    const source = embeddingStatus?.source;

    if (!source) {
      return {
        id: 'embedding-source',
        name: 'Embedding Source',
        category: 'Embedding',
        status: 'warning',
        message: 'Unknown embedding source',
        details: 'Could not determine where the embedding model was loaded from.'
      };
    }

    return {
      id: 'embedding-source',
      name: 'Embedding Source',
      category: 'Embedding',
      status: 'pass',
      message: `Source: ${source}`,
      value: source
    };
  }

  private checkVectorStore(systemStatus: any, indexerStatus: any): DiagnosticCheck {
    const vectorStore = systemStatus?.vectorStore;
    const isAvailable = vectorStore?.available;
    const isNoOp = vectorStore?.class?.includes('NoOp');

    if (isNoOp) {
      return {
        id: 'vector-store',
        name: 'Vector Store',
        category: 'Vector Store',
        status: 'fail',
        message: 'NoOp vector store detected',
        details: 'A placeholder vector store is configured. Documents cannot be stored or searched.',
        recommendation: 'Configure a real vector store path in the Fact Sheet settings.',
        value: vectorStore?.class
      };
    }

    if (!isAvailable) {
      return {
        id: 'vector-store',
        name: 'Vector Store',
        category: 'Vector Store',
        status: 'fail',
        message: 'Vector store not available',
        details: 'No vector store is configured or the configured store is not accessible.',
        recommendation: 'Set a vector store path in the active Fact Sheet configuration.',
        value: null
      };
    }

    const className = vectorStore?.class?.split('.').pop() || 'Unknown';
    return {
      id: 'vector-store',
      name: 'Vector Store',
      category: 'Vector Store',
      status: 'pass',
      message: `${className} available`,
      value: className
    };
  }

  private checkVectorStorePath(indexerStatus: any, factSheetStatus: any): DiagnosticCheck {
    const vectorPath = indexerStatus?.vectorStorePath || factSheetStatus?.vectorStorePath;

    const editableConfig: EditableSetting = {
      settingKey: 'vectorStorePath',
      inputType: 'path',
      label: 'Vector Store Path',
      placeholder: '/path/to/vector-store'
    };

    if (!vectorPath) {
      return {
        id: 'vector-store-path',
        name: 'Vector Store Path',
        category: 'Vector Store',
        status: 'fail',
        message: 'No vector store path configured',
        details: 'A vector store path is required to store document embeddings.',
        recommendation: 'Set the vector store path below or in the Fact Sheet configuration.',
        editable: editableConfig
      };
    }

    return {
      id: 'vector-store-path',
      name: 'Vector Store Path',
      category: 'Vector Store',
      status: 'pass',
      message: vectorPath,
      value: vectorPath,
      editable: editableConfig
    };
  }

  private checkVectorStoreDocuments(indexerStatus: any): DiagnosticCheck {
    const docCount = indexerStatus?.approximateVectorCount || indexerStatus?.vectorDocumentCount || 0;

    if (docCount === 0) {
      return {
        id: 'vector-documents',
        name: 'Indexed Documents',
        category: 'Vector Store',
        status: 'warning',
        message: 'No documents indexed',
        details: 'The vector store is empty. Vector search will return no results.',
        recommendation: 'Index documents from the Fact Sheets tab.',
        value: 0
      };
    }

    return {
      id: 'vector-documents',
      name: 'Indexed Documents',
      category: 'Vector Store',
      status: 'pass',
      message: `${docCount.toLocaleString()} documents indexed`,
      value: docCount
    };
  }

  private checkKeywordIndex(indexerStatus: any): DiagnosticCheck {
    const isAvailable = indexerStatus?.keywordIndexAvailable !== false;
    const isNoOp = indexerStatus?.isNoOpKeywordIndex;

    if (isNoOp) {
      return {
        id: 'keyword-index',
        name: 'Keyword Index',
        category: 'Keyword Index',
        status: 'warning',
        message: 'NoOp keyword index',
        details: 'Keyword/BM25 search is using a placeholder implementation.',
        recommendation: 'Configure a keyword index path if BM25 search is needed.'
      };
    }

    if (!isAvailable) {
      return {
        id: 'keyword-index',
        name: 'Keyword Index',
        category: 'Keyword Index',
        status: 'warning',
        message: 'Keyword index not available',
        details: 'BM25/keyword search will not work without a keyword index.',
        recommendation: 'Set a keyword index path in the Fact Sheet configuration.'
      };
    }

    return {
      id: 'keyword-index',
      name: 'Keyword Index',
      category: 'Keyword Index',
      status: 'pass',
      message: 'Keyword index available'
    };
  }

  private checkKeywordIndexPath(indexerStatus: any, factSheetStatus: any): DiagnosticCheck {
    const keywordPath = indexerStatus?.keywordIndexPath || factSheetStatus?.keywordIndexPath;

    const editableConfig: EditableSetting = {
      settingKey: 'keywordIndexPath',
      inputType: 'path',
      label: 'Keyword Index Path',
      placeholder: '/path/to/keyword-index'
    };

    if (!keywordPath) {
      return {
        id: 'keyword-index-path',
        name: 'Keyword Index Path',
        category: 'Keyword Index',
        status: 'warning',
        message: 'No keyword index path configured',
        details: 'Optional: Set a keyword index path for BM25 search capabilities.',
        value: null,
        editable: editableConfig
      };
    }

    return {
      id: 'keyword-index-path',
      name: 'Keyword Index Path',
      category: 'Keyword Index',
      status: 'pass',
      message: keywordPath,
      value: keywordPath,
      editable: editableConfig
    };
  }

  private checkActiveFactSheet(factSheetStatus: any): DiagnosticCheck {
    if (!factSheetStatus || !factSheetStatus.id) {
      return {
        id: 'fact-sheet',
        name: 'Active Fact Sheet',
        category: 'Fact Sheet',
        status: 'fail',
        message: 'No active fact sheet',
        details: 'An active fact sheet is required to organize documents and configure retrieval.',
        recommendation: 'Create or activate a fact sheet from the Fact Sheets tab.'
      };
    }

    return {
      id: 'fact-sheet',
      name: 'Active Fact Sheet',
      category: 'Fact Sheet',
      status: 'pass',
      message: factSheetStatus.name,
      value: factSheetStatus
    };
  }

  private checkFactSheetConfiguration(factSheetStatus: any): DiagnosticCheck {
    if (!factSheetStatus) {
      return {
        id: 'fact-sheet-config',
        name: 'Fact Sheet Config',
        category: 'Fact Sheet',
        status: 'unknown',
        message: 'No fact sheet to check'
      };
    }

    const issues: string[] = [];

    if (!factSheetStatus.vectorStorePath) {
      issues.push('Missing vector store path');
    }
    if (!factSheetStatus.embeddingModel && !factSheetStatus.embeddingModelSource) {
      issues.push('No embedding model specified');
    }

    if (issues.length > 0) {
      return {
        id: 'fact-sheet-config',
        name: 'Fact Sheet Config',
        category: 'Fact Sheet',
        status: 'warning',
        message: `${issues.length} configuration issue(s)`,
        details: issues.join(', '),
        recommendation: 'Edit the fact sheet to complete the configuration.'
      };
    }

    return {
      id: 'fact-sheet-config',
      name: 'Fact Sheet Config',
      category: 'Fact Sheet',
      status: 'pass',
      message: 'Configuration complete'
    };
  }

  private checkReranker(systemStatus: any): DiagnosticCheck {
    const reranker = systemStatus?.reranker;
    const isAvailable = reranker?.available;

    if (!isAvailable) {
      return {
        id: 'reranker',
        name: 'Reranker',
        category: 'Reranking',
        status: 'warning',
        message: 'Reranking not available',
        details: 'Reranking improves search quality by re-scoring results. This is optional.',
        recommendation: 'Load a cross-encoder model to enable reranking.'
      };
    }

    const types = reranker?.supportedTypes || [];
    return {
      id: 'reranker',
      name: 'Reranker',
      category: 'Reranking',
      status: 'pass',
      message: `Available (${types.length} method(s))`,
      value: types
    };
  }

  private checkLlmAvailability(llmStatus: any): DiagnosticCheck {
    const isAvailable = llmStatus?.available;
    const provider = llmStatus?.provider || llmStatus?.type;

    if (!isAvailable) {
      return {
        id: 'llm',
        name: 'LLM Connection',
        category: 'LLM',
        status: 'warning',
        message: 'No LLM configured',
        details: 'An LLM is required for chat-based RAG queries.',
        recommendation: 'Configure an LLM provider (OpenAI, Anthropic, etc.) in application settings.'
      };
    }

    return {
      id: 'llm',
      name: 'LLM Connection',
      category: 'LLM',
      status: 'pass',
      message: provider ? `Provider: ${provider}` : 'LLM available',
      value: provider
    };
  }

  private checkMcpServers(mcpStatus: any): DiagnosticCheck {
    const servers = Array.isArray(mcpStatus) ? mcpStatus : [];
    const activeServers = servers.filter((s: any) => s.connected || s.active);

    if (servers.length === 0) {
      return {
        id: 'mcp-servers',
        name: 'MCP Servers',
        category: 'MCP',
        status: 'pass',
        message: 'No MCP servers configured',
        details: 'MCP servers are optional and provide additional tool capabilities.'
      };
    }

    if (activeServers.length === 0) {
      return {
        id: 'mcp-servers',
        name: 'MCP Servers',
        category: 'MCP',
        status: 'warning',
        message: `${servers.length} server(s) configured, none active`,
        details: 'MCP servers are configured but not currently connected.',
        recommendation: 'Start the configured MCP servers or check their status.'
      };
    }

    return {
      id: 'mcp-servers',
      name: 'MCP Servers',
      category: 'MCP',
      status: 'pass',
      message: `${activeServers.length}/${servers.length} server(s) active`,
      value: servers.length
    };
  }

  private checkMemoryUsage(memoryStatus: any): DiagnosticCheck {
    const usedPercent = memoryStatus?.usedPercent || memoryStatus?.heapUsedPercent;

    if (typeof usedPercent !== 'number') {
      return {
        id: 'memory-usage',
        name: 'Memory Usage',
        category: 'System',
        status: 'unknown',
        message: 'Could not determine memory usage'
      };
    }

    if (usedPercent > 90) {
      return {
        id: 'memory-usage',
        name: 'Memory Usage',
        category: 'System',
        status: 'fail',
        message: `Critical: ${usedPercent.toFixed(1)}% used`,
        details: 'Memory usage is critically high. Operations may fail.',
        recommendation: 'Restart the application or increase heap size.'
      };
    }

    if (usedPercent > 75) {
      return {
        id: 'memory-usage',
        name: 'Memory Usage',
        category: 'System',
        status: 'warning',
        message: `High: ${usedPercent.toFixed(1)}% used`,
        details: 'Memory usage is elevated. Consider monitoring for issues.'
      };
    }

    return {
      id: 'memory-usage',
      name: 'Memory Usage',
      category: 'System',
      status: 'pass',
      message: `${usedPercent.toFixed(1)}% used`,
      value: usedPercent
    };
  }

  private checkHeapMemory(memoryStatus: any): DiagnosticCheck {
    const maxHeap = memoryStatus?.maxHeapMB || memoryStatus?.maxHeap;

    if (typeof maxHeap !== 'number') {
      return {
        id: 'heap-size',
        name: 'Heap Size',
        category: 'System',
        status: 'unknown',
        message: 'Could not determine heap size'
      };
    }

    const heapGB = maxHeap / 1024;

    if (heapGB < 2) {
      return {
        id: 'heap-size',
        name: 'Heap Size',
        category: 'System',
        status: 'warning',
        message: `${heapGB.toFixed(1)} GB max heap`,
        details: 'Low heap size may limit document processing capacity.',
        recommendation: 'Consider increasing JVM heap size (-Xmx4g or higher).'
      };
    }

    return {
      id: 'heap-size',
      name: 'Heap Size',
      category: 'System',
      status: 'pass',
      message: `${heapGB.toFixed(1)} GB max heap`,
      value: maxHeap
    };
  }

  // === CLI/ENVIRONMENT CHECKS ===

  private checkKompileDataDir(envStatus: any): DiagnosticCheck {
    const directories = envStatus?.directories;
    const kompileDir = directories?.kompileDataDir;

    if (!kompileDir) {
      return {
        id: 'kompile-data-dir',
        name: 'Kompile Data Directory',
        category: 'Environment',
        status: 'unknown',
        message: 'Could not check kompile directory',
        details: 'Environment status endpoint not available.'
      };
    }

    if (!kompileDir.exists) {
      return {
        id: 'kompile-data-dir',
        name: 'Kompile Data Directory',
        category: 'Environment',
        status: 'fail',
        message: 'Directory does not exist',
        details: `Expected at: ${kompileDir.path}`,
        recommendation: 'Run "kompile bootstrap" to initialize the kompile directory.'
      };
    }

    if (!kompileDir.writable) {
      return {
        id: 'kompile-data-dir',
        name: 'Kompile Data Directory',
        category: 'Environment',
        status: 'warning',
        message: 'Directory is not writable',
        details: `Path: ${kompileDir.path}`,
        recommendation: 'Check directory permissions.'
      };
    }

    return {
      id: 'kompile-data-dir',
      name: 'Kompile Data Directory',
      category: 'Environment',
      status: 'pass',
      message: kompileDir.path,
      value: kompileDir.path
    };
  }

  private checkModelCacheDir(envStatus: any): DiagnosticCheck {
    const directories = envStatus?.directories;
    const modelsDir = directories?.modelsDir;

    if (!modelsDir) {
      return {
        id: 'model-cache-dir',
        name: 'Model Cache Directory',
        category: 'Environment',
        status: 'unknown',
        message: 'Could not check model cache directory'
      };
    }

    if (!modelsDir.exists) {
      return {
        id: 'model-cache-dir',
        name: 'Model Cache Directory',
        category: 'Environment',
        status: 'warning',
        message: 'Directory does not exist yet',
        details: `Will be created at: ${modelsDir.path}`,
        recommendation: 'Directory will be created when first model is downloaded.'
      };
    }

    const fileCount = modelsDir.fileCount || 0;
    return {
      id: 'model-cache-dir',
      name: 'Model Cache Directory',
      category: 'Environment',
      status: 'pass',
      message: `${fileCount} item(s) in cache`,
      details: modelsDir.path,
      value: modelsDir.path
    };
  }

  private checkSubprocessConfig(envStatus: any): DiagnosticCheck {
    const subprocess = envStatus?.subprocess;

    if (!subprocess) {
      return {
        id: 'subprocess-config',
        name: 'Subprocess Configuration',
        category: 'Environment',
        status: 'unknown',
        message: 'Could not check subprocess configuration'
      };
    }

    const ingest = subprocess.ingest;
    if (ingest?.enabled && !ingest?.javaPathValid) {
      return {
        id: 'subprocess-config',
        name: 'Subprocess Configuration',
        category: 'Environment',
        status: 'fail',
        message: 'Java path is invalid',
        details: `Configured path: ${ingest.javaPath}`,
        recommendation: 'Set a valid Java executable path or ensure Java is on the system PATH.'
      };
    }

    if (ingest?.enabled) {
      const heapMB = subprocess.ingestHeapMB || 0;
      const currentJvmMB = subprocess.currentJvmMaxHeapMB || 0;

      if (heapMB > 0 && heapMB > currentJvmMB * 2) {
        return {
          id: 'subprocess-config',
          name: 'Subprocess Configuration',
          category: 'Environment',
          status: 'warning',
          message: `Subprocess heap (${heapMB}MB) is large`,
          details: `Current JVM heap: ${currentJvmMB}MB`,
          recommendation: 'Ensure system has sufficient memory for subprocess heap allocation.'
        };
      }

      return {
        id: 'subprocess-config',
        name: 'Subprocess Configuration',
        category: 'Environment',
        status: 'pass',
        message: `Enabled with ${ingest.heapSize} heap`,
        details: `${subprocess.availableProcessors} CPU cores available`
      };
    }

    return {
      id: 'subprocess-config',
      name: 'Subprocess Configuration',
      category: 'Environment',
      status: 'pass',
      message: 'Subprocess disabled (in-process mode)'
    };
  }

  private checkDiskSpace(envStatus: any): DiagnosticCheck {
    const diskSpace = envStatus?.diskSpace;

    if (!diskSpace) {
      return {
        id: 'disk-space',
        name: 'Disk Space',
        category: 'Environment',
        status: 'unknown',
        message: 'Could not check disk space'
      };
    }

    const freeGB = diskSpace.kompileDataDirFreeGB || 0;

    if (diskSpace.status === 'fail') {
      return {
        id: 'disk-space',
        name: 'Disk Space',
        category: 'Environment',
        status: 'fail',
        message: `Critical: Only ${freeGB}GB free`,
        details: diskSpace.message,
        recommendation: 'Free up disk space to ensure proper operation.'
      };
    }

    if (diskSpace.status === 'warning') {
      return {
        id: 'disk-space',
        name: 'Disk Space',
        category: 'Environment',
        status: 'warning',
        message: `Low: ${freeGB}GB free`,
        details: diskSpace.message,
        recommendation: 'Consider freeing up disk space.'
      };
    }

    return {
      id: 'disk-space',
      name: 'Disk Space',
      category: 'Environment',
      status: 'pass',
      message: `${freeGB}GB free`,
      value: freeGB
    };
  }

  private checkLlmApiKeys(envStatus: any): DiagnosticCheck {
    const envVars = envStatus?.environmentVariables;

    if (!envVars) {
      return {
        id: 'llm-api-keys',
        name: 'LLM API Keys',
        category: 'Environment',
        status: 'unknown',
        message: 'Could not check API key configuration'
      };
    }

    const openaiSet = envVars.OPENAI_API_KEY?.isSet;
    const anthropicSet = envVars.ANTHROPIC_API_KEY?.isSet;
    const googleSet = envVars.GOOGLE_API_KEY?.isSet;

    const configuredProviders: string[] = [];
    if (openaiSet) configuredProviders.push('OpenAI');
    if (anthropicSet) configuredProviders.push('Anthropic');
    if (googleSet) configuredProviders.push('Google');

    if (configuredProviders.length === 0) {
      return {
        id: 'llm-api-keys',
        name: 'LLM API Keys',
        category: 'Environment',
        status: 'warning',
        message: 'No LLM API keys configured',
        details: 'Chat-based RAG features require an LLM provider.',
        recommendation: 'Set OPENAI_API_KEY, ANTHROPIC_API_KEY, or GOOGLE_API_KEY environment variable.'
      };
    }

    return {
      id: 'llm-api-keys',
      name: 'LLM API Keys',
      category: 'Environment',
      status: 'pass',
      message: `Configured: ${configuredProviders.join(', ')}`,
      value: configuredProviders
    };
  }

  // === CATEGORY BUILDING ===

  private buildCategories(checks: DiagnosticCheck[]): DiagnosticCategory[] {
    const categoryMap = new Map<string, DiagnosticCheck[]>();

    for (const check of checks) {
      if (!categoryMap.has(check.category)) {
        categoryMap.set(check.category, []);
      }
      categoryMap.get(check.category)!.push(check);
    }

    const categoryIcons: { [key: string]: string } = {
      'Embedding': 'psychology',
      'Vector Store': 'storage',
      'Keyword Index': 'search',
      'Fact Sheet': 'folder',
      'Reranking': 'sort',
      'LLM': 'chat',
      'MCP': 'extension',
      'System': 'memory',
      'Environment': 'settings_applications'
    };

    return Array.from(categoryMap.entries()).map(([name, catChecks]) => {
      const passCount = catChecks.filter(c => c.status === 'pass').length;
      const warningCount = catChecks.filter(c => c.status === 'warning').length;
      const failCount = catChecks.filter(c => c.status === 'fail').length;

      let overallStatus: DiagnosticStatus = 'pass';
      if (failCount > 0) overallStatus = 'fail';
      else if (warningCount > 0) overallStatus = 'warning';

      return {
        name,
        icon: categoryIcons[name] || 'check_circle',
        checks: catChecks,
        overallStatus,
        passCount,
        warningCount,
        failCount
      };
    });
  }

  // === PREREQUISITES ===

  private buildPrerequisites(results: any): PrerequisiteStatus {
    const embeddingReady = results.embeddingStatus?.initialized &&
                          results.embeddingStatus?.dimensions > 0 &&
                          !results.embeddingStatus?.modelId?.includes('NoOp');

    const vectorStoreReady = results.systemStatus?.vectorStore?.available &&
                            !results.systemStatus?.vectorStore?.class?.includes('NoOp');

    const vectorPathSet = !!(results.indexerStatus?.vectorStorePath ||
                           results.factSheetStatus?.vectorStorePath);

    const factSheetActive = !!results.factSheetStatus?.id;

    const llmReady = results.llmStatus?.available;

    const rerankerReady = results.systemStatus?.reranker?.available;

    // Vector Search prerequisites
    const vectorSearchMissing: string[] = [];
    if (!embeddingReady) vectorSearchMissing.push('Embedding model');
    if (!vectorStoreReady) vectorSearchMissing.push('Vector store');
    if (!vectorPathSet) vectorSearchMissing.push('Vector store path');

    // RAG Query prerequisites
    const ragQueryMissing: string[] = [...vectorSearchMissing];
    if (!llmReady) ragQueryMissing.push('LLM connection');

    // Document Ingestion prerequisites
    const ingestionMissing: string[] = [];
    if (!embeddingReady) ingestionMissing.push('Embedding model');
    if (!vectorPathSet) ingestionMissing.push('Vector store path');
    if (!factSheetActive) ingestionMissing.push('Active fact sheet');

    // Reranking prerequisites
    const rerankingMissing: string[] = [];
    if (!rerankerReady) rerankingMissing.push('Cross-encoder model');

    return {
      vectorSearch: {
        ready: vectorSearchMissing.length === 0,
        missing: vectorSearchMissing
      },
      ragQuery: {
        ready: ragQueryMissing.length === 0,
        missing: ragQueryMissing
      },
      documentIngestion: {
        ready: ingestionMissing.length === 0,
        missing: ingestionMissing
      },
      reranking: {
        ready: rerankingMissing.length === 0,
        missing: rerankingMissing
      }
    };
  }
}
