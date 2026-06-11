import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { GraphExtractionService, ModelProvider, ModelInfo } from '../../services/graph-extraction.service';

interface ContextualRagConfig {
  enabled: boolean;
  llmProvider: string;
  llmModel: string;
  temperature: number;
  maxContextTokens: number;
  includeDocumentSummary: boolean;
  documentSummaryMaxTokens: number;
  includeSurroundingChunks: boolean;
  surroundingChunksWindow: number;
  sourceAttributionEnabled: boolean;
  citationFormat: string;
  customCitationTemplate: string;
  includePageNumbers: boolean;
  batchSize: number;
  maxConcurrentRequests: number;
  requestTimeoutSeconds: number;
  maxRetries: number;
  cachingEnabled: boolean;
  cachePath: string;
  cacheTtlDays: number;
  fallbackOnError: boolean;
  webSearchFallbackThreshold: number;
  contextPromptTemplate: string;
}

interface PresetInfo {
  name: string;
  displayName: string;
  description: string;
}

interface ProviderModelInfo {
  id: string;
  name: string;
  displayName: string;
  description?: string;
  contextWindow?: number;
  supportsTools?: boolean;
}

interface ProviderInfo {
  id: string;
  displayName: string;
  available?: boolean;
  models?: ProviderModelInfo[];
}

interface TestResult {
  success: boolean;
  originalText?: string;
  contextualizedText?: string;
  contextPrefix?: string;
  wasContextualized?: boolean;
  processingTimeMs?: number;
  error?: string;
  metadata?: any;
}

interface CompareResult {
  success: boolean;
  original?: { text: string; length: number; wordCount: number };
  contextualized?: {
    text: string;
    length: number;
    wordCount: number;
    contextPrefix: string;
    addedLength: number;
    addedWords: number;
  };
  error?: string;
}

@Component({
  selector: 'app-contextual-rag-debug',
  standalone: false,
  templateUrl: './contextual-rag-debug.component.html',
  styleUrls: ['./contextual-rag-debug.component.css']
})
export class ContextualRagDebugComponent implements OnInit {
  private backendUrl: string;

  // Configuration
  config: ContextualRagConfig | null = null;
  presets: PresetInfo[] = [];
  providers: ProviderInfo[] = [];
  selectedPreset: string = '';

  // Status
  status: any = null;
  loading = false;
  enricherAvailable = false;

  // Test inputs
  testChunkText = 'Revenue increased by 23% reaching $4.2M in September.';
  testDocumentTitle = 'Q3 2024 Financial Report';
  testDocumentSummary = '';
  testChunkIndex = 0;
  testTotalChunks = 1;

  // Test results
  testResult: TestResult | null = null;
  compareResult: CompareResult | null = null;
  promptPreview: any = null;

  // Batch test
  batchChunks: string[] = [];
  batchChunksText = '';
  batchResults: any = null;

  // Cache stats
  cacheStats: any = null;

  // Current prompt template
  promptTemplate = '';
  customPromptTemplate = '';
  useCustomPrompt = false;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {
    // Dynamic backend URL
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
    this.loadAll();
  }

  loadAll(): void {
    this.loadConfig();
    this.loadStatus();
    this.loadPresets();
    this.loadProviders();
    this.loadCacheStats();
    this.loadPromptTemplate();
  }

  loadConfig(): void {
    this.http.get<ContextualRagConfig>(`${this.backendUrl}/contextual-rag/config`)
      .subscribe({
        next: (config) => {
          this.config = config;
        },
        error: (err) => {
          this.showSnackbar('Failed to load configuration', true);
          console.error('Config load error:', err);
        }
      });
  }

  loadStatus(): void {
    this.http.get<any>(`${this.backendUrl}/contextual-rag/status`)
      .subscribe({
        next: (status) => {
          this.status = status;
          this.enricherAvailable = status.enricherAvailable;
        },
        error: (err) => {
          console.error('Status load error:', err);
        }
      });
  }

  loadPresets(): void {
    this.http.get<PresetInfo[]>(`${this.backendUrl}/contextual-rag/presets`)
      .subscribe({
        next: (presets) => {
          this.presets = presets;
        },
        error: (err) => {
          console.error('Presets load error:', err);
        }
      });
  }

  loadProviders(): void {
    this.http.get<ProviderInfo[]>(`${this.backendUrl}/contextual-rag/providers`)
      .subscribe({
        next: (providers) => {
          this.providers = providers;
        },
        error: (err) => {
          console.error('Providers load error:', err);
        }
      });
  }

  loadCacheStats(): void {
    this.http.get<any>(`${this.backendUrl}/contextual-rag/cache/stats`)
      .subscribe({
        next: (stats) => {
          this.cacheStats = stats;
        },
        error: (err) => {
          console.error('Cache stats load error:', err);
        }
      });
  }

  loadPromptTemplate(): void {
    this.http.get<any>(`${this.backendUrl}/contextual-rag/prompt-template`)
      .subscribe({
        next: (result) => {
          this.promptTemplate = result.template;
          this.useCustomPrompt = result.isCustom;
        },
        error: (err) => {
          console.error('Prompt template load error:', err);
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CONFIGURATION ACTIONS
  // ═══════════════════════════════════════════════════════════════════════════

  toggleEnabled(): void {
    if (!this.config) return;

    const newEnabled = !this.config.enabled;
    this.http.post<any>(`${this.backendUrl}/contextual-rag/toggle`, { enabled: newEnabled })
      .subscribe({
        next: (result) => {
          this.config!.enabled = result.enabled;
          this.showSnackbar(result.message);
        },
        error: (err) => {
          this.showSnackbar('Failed to toggle enrichment', true);
        }
      });
  }

  applyPreset(presetName: string): void {
    this.loading = true;
    this.http.post<ContextualRagConfig>(`${this.backendUrl}/contextual-rag/presets/${presetName}`, {})
      .subscribe({
        next: (config) => {
          this.config = config;
          this.showSnackbar(`Applied preset: ${presetName}`);
          this.loading = false;
        },
        error: (err) => {
          this.showSnackbar('Failed to apply preset', true);
          this.loading = false;
        }
      });
  }

  saveConfig(): void {
    if (!this.config) return;

    this.loading = true;
    this.http.post<ContextualRagConfig>(`${this.backendUrl}/contextual-rag/config`, this.config)
      .subscribe({
        next: (config) => {
          this.config = config;
          this.showSnackbar('Configuration saved');
          this.loading = false;
        },
        error: (err) => {
          this.showSnackbar('Failed to save configuration', true);
          this.loading = false;
        }
      });
  }

  resetConfig(): void {
    this.loading = true;
    this.http.post<ContextualRagConfig>(`${this.backendUrl}/contextual-rag/config/reset`, {})
      .subscribe({
        next: (config) => {
          this.config = config;
          this.showSnackbar('Configuration reset to defaults');
          this.loading = false;
        },
        error: (err) => {
          this.showSnackbar('Failed to reset configuration', true);
          this.loading = false;
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // DEBUG / TESTING
  // ═══════════════════════════════════════════════════════════════════════════

  testContextualization(): void {
    this.loading = true;
    this.testResult = null;

    const request = {
      chunkText: this.testChunkText,
      documentTitle: this.testDocumentTitle,
      documentSummary: this.testDocumentSummary || null,
      chunkIndex: this.testChunkIndex,
      totalChunks: this.testTotalChunks
    };

    this.http.post<TestResult>(`${this.backendUrl}/contextual-rag/debug/test-contextualization`, request)
      .subscribe({
        next: (result) => {
          this.testResult = result;
          this.loading = false;
          if (result.success) {
            this.showSnackbar(`Contextualization completed in ${result.processingTimeMs}ms`);
          } else {
            this.showSnackbar(result.error || 'Contextualization failed', true);
          }
        },
        error: (err) => {
          this.showSnackbar('Test failed', true);
          this.loading = false;
        }
      });
  }

  compareTexts(): void {
    this.loading = true;
    this.compareResult = null;

    const request = {
      chunkText: this.testChunkText,
      documentTitle: this.testDocumentTitle,
      documentSummary: this.testDocumentSummary || null,
      chunkIndex: this.testChunkIndex,
      totalChunks: this.testTotalChunks
    };

    this.http.post<CompareResult>(`${this.backendUrl}/contextual-rag/debug/compare`, request)
      .subscribe({
        next: (result) => {
          this.compareResult = result;
          this.loading = false;
        },
        error: (err) => {
          this.showSnackbar('Comparison failed', true);
          this.loading = false;
        }
      });
  }

  previewPrompt(): void {
    const request = {
      chunkText: this.testChunkText,
      documentTitle: this.testDocumentTitle,
      documentSummary: this.testDocumentSummary || 'Sample document summary for preview.',
      chunkIndex: this.testChunkIndex,
      totalChunks: this.testTotalChunks
    };

    this.http.post<any>(`${this.backendUrl}/contextual-rag/debug/preview-prompt`, request)
      .subscribe({
        next: (result) => {
          this.promptPreview = result;
        },
        error: (err) => {
          this.showSnackbar('Failed to preview prompt', true);
        }
      });
  }

  testBatch(): void {
    // Parse batch chunks from textarea
    this.batchChunks = this.batchChunksText
      .split('\n---\n')
      .map(c => c.trim())
      .filter(c => c.length > 0);

    if (this.batchChunks.length === 0) {
      this.showSnackbar('Enter chunks separated by ---', true);
      return;
    }

    this.loading = true;
    this.batchResults = null;

    const request = {
      chunks: this.batchChunks,
      documentTitle: this.testDocumentTitle,
      documentText: this.testDocumentSummary || null
    };

    this.http.post<any>(`${this.backendUrl}/contextual-rag/debug/test-batch`, request)
      .subscribe({
        next: (result) => {
          this.batchResults = result;
          this.loading = false;
          if (result.success) {
            this.showSnackbar(`Batch test completed: ${result.processedChunks} chunks in ${result.processingTimeMs}ms`);
          }
        },
        error: (err) => {
          this.showSnackbar('Batch test failed', true);
          this.loading = false;
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // CACHE MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  clearCaches(): void {
    this.http.post<any>(`${this.backendUrl}/contextual-rag/cache/clear`, {})
      .subscribe({
        next: (result) => {
          if (result.success) {
            this.showSnackbar(`Cleared ${result.clearedDocumentSummaries} summaries and ${result.clearedChunkContexts} chunk contexts`);
            this.loadCacheStats();
          }
        },
        error: (err) => {
          this.showSnackbar('Failed to clear caches', true);
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // UTILITIES
  // ═══════════════════════════════════════════════════════════════════════════

  getModelsForProvider(providerId: string): ProviderModelInfo[] {
    const provider = this.providers.find(p => p.id === providerId);
    return provider?.models ?? [];
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.showSnackbar('Copied to clipboard');
    });
  }

  loadSampleChunk(): void {
    this.testChunkText = 'The company reported strong Q3 results with revenue of $4.2 million, representing a 23% increase year-over-year. Operating margins improved to 18% driven by cost optimization initiatives.';
    this.testDocumentTitle = 'Q3 2024 Financial Report';
    this.testDocumentSummary = 'This is the quarterly financial report for Q3 2024 covering financial performance, key metrics, and business outlook.';
  }

  loadSampleBatch(): void {
    this.batchChunksText = `Revenue increased by 23% reaching $4.2M in September.
---
Operating expenses decreased by 5% due to automation initiatives.
---
Customer acquisition cost improved to $45 from $52 last quarter.
---
Net profit margin expanded to 18%, up from 15% in Q2.`;
    this.testDocumentTitle = 'Q3 2024 Financial Report';
  }

  private showSnackbar(message: string, isError = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
