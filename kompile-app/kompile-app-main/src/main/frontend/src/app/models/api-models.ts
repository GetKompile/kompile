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

// For RAG Service
export interface RagQuery {
  query: string;
  maxResults?: number;
}

export interface RagResponse {
  query: string;
  answer: string;
  retrieved_contexts: RetrievedContext[];
}

export interface RetrievedContext {
  document_id: string;
  content: string;
  score?: number;
}


// For Document Service
export interface AddUrlRequest {
  url: string;
  fileName?: string;
  loader?: string;
}

export interface FileUploadResponse {
  message: string;
  fileName?: string;
  filePath?: string;
  details?: string;
}

export interface SimpleMessageResponse {
  message: string;
  details?: string;
  error?: string;
}

export interface LoaderInfo {
  name: string;
  className: string;
}

export interface ChunkerInfo {
  name: string;
  className: string;
}

export interface UploadedFileInfo {
  fileName: string;
  filePath: string;
}

export enum DocumentSourceType {
  FILE = 'FILE',
  URL = 'URL'
}

export interface BatchLoadRequestItem {
  pathOrUrl: string;
  type: DocumentSourceType;
  loaderName?: string;
  originalFileName?: string;
  chunkerName?: string;
  chunkerOptions?: { [key: string]: any };
}

export interface BatchProcessRequest {
  items: BatchLoadRequestItem[];
  defaultLoaderName?: string;
  defaultChunkerName?: string;
  defaultChunkerOptions?: { [key: string]: any };
}

export interface BatchProcessResponseDetails {
  [key: string]: {
    count?: number;
    error?: string;
    summaries?: DocumentSummary[];
  };
}
export interface DocumentSummary {
  id: string;
  contentSnippet: string;
  metadata?: { [key: string]: any };
  [key: string]: any; // For other dynamic properties like in DocumentDebugInfo
}


export interface BatchProcessResponse {
  message: string;
  successful_items: number;
  failed_items: number;
  details: BatchProcessResponseDetails | null;
}

// Models for Document Debugger
export interface LoaderDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  supportsFile: boolean;
  supportReason: string;
}

export interface ChunkerDebugInfo {
  name: string;
  className: string;
  isNoOp: boolean;
  reason: string;
}

export interface DocumentDebugInfo {
  id: string;
  content: string;
  contentLength: number;
  hasContent: boolean;
  metadata: { [key: string]: any };
  contentLines: string[];
  contentStats: { [key: string]: any };
}

export interface ChunkDebugInfo {
  id: string;
  content: string;
  contentLength: number;
  chunkIndex: number;
  metadata: { [key: string]: any };
}

export interface DebugAnalysisResult {
  fileName: string;
  filePath: string | null;
  fileSize: number;
  availableLoaders: LoaderDebugInfo[] | null;
  selectedLoader: LoaderDebugInfo | null;
  loadedDocuments: DocumentDebugInfo[] | null;
  availableChunkers: ChunkerDebugInfo[] | null;
  selectedChunker: ChunkerDebugInfo | null;
  chunks: ChunkDebugInfo[] | null;
  processingStats: { [key: string]: any } | null;
  errorMessage: string | null;
}

export interface DebuggerStatus {
  uploadsPathConfigured: boolean;
  uploadsPath: string;
  totalLoaders: number;
  realLoaders: number;
  noOpLoaders: number;
  totalChunkers: number;
  realChunkers: number;
  noOpChunkers: number;
}

export interface TestUploadResponse {
  message: string;
  fileName: string;
  filePath: string;
  fileSize: number;
  error?: string; // Optional error field
}


// For MCP Tool Service
export interface McpToolInfo {
  name: string;
  description: string;
  inputSchema: any;
}

// ═══════════════════════════════════════════════════════════════════════════════
// ENHANCED TOOL DEFINITIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Tool source types.
 */
export type ToolSource = 'BUILT_IN' | 'MCP_SERVER' | 'REST_BRIDGE' | 'CUSTOM' | 'IMPORTED';

/**
 * Tool implementation types.
 */
export type ToolImplementationType = 'BUILT_IN' | 'JAVA_CLASS' | 'HTTP_ENDPOINT' | 'SCRIPT' | 'MCP_SERVER' | 'REST_BRIDGE';

/**
 * Enhanced tool definition with comprehensive metadata for agent discoverability.
 */
export interface EnhancedToolDefinition {
  /** Unique identifier */
  id?: string;

  /** Tool name (used for invocation) */
  name: string;

  /** Display name for UI */
  displayName?: string;

  /** Short description */
  description: string;

  /** Detailed description for agents */
  detailedDescription?: string;

  /** Tool category */
  category?: string;

  /** Tags for search and filtering */
  tags?: string[];

  /** JSON Schema for input parameters */
  inputSchema?: any;

  /** JSON Schema for output */
  outputSchema?: any;

  /** Detailed parameter definitions */
  parameters?: ToolParameterDefinition[];

  /** Usage examples */
  examples?: ToolUsageExample[];

  /** Agent hints for when to use this tool */
  usageHints?: string[];

  /** Related tools */
  relatedTools?: string[];

  /** Source of this tool */
  source?: ToolSource;

  /** Implementation details */
  implementation?: ToolImplementation;

  /** Whether tool is enabled */
  enabled?: boolean;

  /** Whether tool performs write operations */
  isWriteOperation?: boolean;

  /** Whether results can be undone */
  undoable?: boolean;

  /** Version */
  version?: string;

  /** Creation timestamp */
  createdAt?: string;

  /** Last update timestamp */
  updatedAt?: string;

  /** Creator */
  createdBy?: string;
}

/**
 * Tool parameter definition.
 */
export interface ToolParameterDefinition {
  name: string;
  displayName?: string;
  type: string;
  description?: string;
  required?: boolean;
  defaultValue?: any;
  exampleValues?: any[];
  enumValues?: any[];
  minimum?: number;
  maximum?: number;
  pattern?: string;
  properties?: ToolParameterDefinition[];
  items?: ToolParameterDefinition;
}

/**
 * Tool usage example.
 */
export interface ToolUsageExample {
  title?: string;
  description?: string;
  input?: { [key: string]: any };
  expectedOutput?: any;
  scenario?: string;
}

/**
 * Tool implementation configuration.
 */
export interface ToolImplementation {
  type: ToolImplementationType;
  beanName?: string;
  className?: string;
  methodName?: string;
  httpUrl?: string;
  httpMethod?: string;
  httpHeaders?: { [key: string]: string };
  script?: string;
  scriptLanguage?: string;
  mcpServerId?: string;
  restBridgeId?: string;
}

/**
 * Tool category information.
 */
export interface ToolCategoryInfo {
  displayName: string;
  description: string;
  keywords: string[];
}

/**
 * Tools summary for quick overview.
 */
export interface ToolsSummary {
  totalTools: number;
  enabledTools: number;
  builtInTools: number;
  customTools: number;
  categoryCount: number;
  categories: string[];
}

/**
 * Tool category display information.
 */
export const TOOL_CATEGORIES: { [key: string]: ToolCategoryInfo } = {
  'rag': {
    displayName: 'RAG & Document Search',
    description: 'Tools for retrieving and querying documents using semantic search and RAG.',
    keywords: ['query', 'search', 'retrieve', 'document']
  },
  'filesystem': {
    displayName: 'File System Operations',
    description: 'Tools for reading, writing, and managing files and directories.',
    keywords: ['file', 'directory', 'path', 'read', 'write']
  },
  'indexing': {
    displayName: 'Index Management',
    description: 'Tools for managing document indexes.',
    keywords: ['index', 'rebuild', 'stats']
  },
  'model': {
    displayName: 'Model Management',
    description: 'Tools for inspecting and managing ML models and embeddings.',
    keywords: ['model', 'embedding', 'samediff']
  },
  'system': {
    displayName: 'System Diagnostics',
    description: 'Tools for monitoring system resources and JVM configuration.',
    keywords: ['memory', 'cpu', 'thread', 'jvm']
  },
  'config': {
    displayName: 'Application Configuration',
    description: 'Tools for viewing and modifying application settings.',
    keywords: ['config', 'setting', 'property']
  },
  'action_log': {
    displayName: 'Action History',
    description: 'Tools for viewing and managing action history with undo support.',
    keywords: ['action', 'undo', 'history']
  },
  'chat': {
    displayName: 'Chat & Sessions',
    description: 'Tools for managing chat sessions and conversation history.',
    keywords: ['chat', 'session', 'conversation']
  },
  'custom': {
    displayName: 'Custom Tools',
    description: 'User-defined custom tools.',
    keywords: []
  }
};

// For Indexer (Anserini) Service
export interface IndexStatusResponse {
  index_status: 'AVAILABLE' | 'NOT_AVAILABLE_OR_INVALID';
  message: string;
  error?: string;
}

// New interfaces for Index Browser
export interface IndexedDocInfo {
  id: string;
  preview?: string;
  content?: string;
  metadata?: { [key: string]: any };
  lucene_internal_id?: number;
}

export interface UpdateDocRequest {
  content: string;
}

// New interfaces for Index Browser Search
export interface SearchRequest {
  query: string;
  maxResults?: number;
}

export interface SearchResult {
  id: string;
  content: string;
  preview: string;
  score: number;
  metadata: { [key: string]: any };
  originalDocument: string;
}

export interface SearchResponse {
  query: string;
  maxResults: number;
  totalResults: number;
  results: SearchResult[];
}

// New interface for Index Browser Status
export interface IndexBrowserStatus {
  indexerImplementation: string;
  indexerFullClassName: string;
  retrieverImplementation: string;
  retrieverFullClassName: string;
  indexAvailable: boolean;
  approximateDocumentCount: number | string;
  isNoOpIndexer: boolean;
  isNoOpRetriever: boolean;
  warning?: string;
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHUNKING CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Available chunker strategy types.
 */
export type ChunkerStrategy =
  | 'spring_recursive_character'
  | 'custom_recursive_character'
  | 'recursive-character'
  | 'opennlp_sentence'
  | 'sentence'
  | 'spring_token'
  | 'custom_markdown'
  | 'spring_markdown'
  | 'auto';

/**
 * Chunking configuration options.
 */
export interface ChunkingOptions {
  /** Maximum size per chunk (characters or tokens depending on chunker) */
  chunkSize: number;

  /** Overlap between chunks */
  overlap: number;

  /** Preserve paragraph boundaries */
  preserveParagraphs: boolean;

  /** Custom separators for recursive chunker */
  separators?: string[];

  /** Minimum chunk size to avoid tiny chunks */
  minChunkSize?: number;

  /** Maximum chunk size safety limit */
  maxChunkSize?: number;

  /** Language for sentence detection (OpenNLP) */
  language?: string;

  /** Split on headings (Markdown) */
  splitOnHeadings?: boolean;
}

/**
 * Full chunking configuration for document processing.
 */
export interface ChunkingConfig {
  /** Whether to use custom chunking settings */
  useCustomSettings: boolean;

  /** Selected chunker strategy */
  strategy: ChunkerStrategy;

  /** Chunking options */
  options: ChunkingOptions;
}

/**
 * Chunker strategy information for UI display.
 */
export interface ChunkerStrategyInfo {
  id: ChunkerStrategy;
  name: string;
  description: string;
  icon: string;
  bestFor: string;
}

/**
 * Available chunker strategies with descriptions.
 */
export const CHUNKER_STRATEGIES: ChunkerStrategyInfo[] = [
  {
    id: 'auto',
    name: 'Auto-detect',
    description: 'Automatically select the best chunker for your content',
    icon: 'auto_awesome',
    bestFor: 'General use'
  },
  {
    id: 'spring_recursive_character',
    name: 'Recursive Character (Spring)',
    description: 'Spring AI hierarchical splitting using separators (paragraphs, lines, sentences)',
    icon: 'account_tree',
    bestFor: 'General documents, prose'
  },
  {
    id: 'custom_recursive_character',
    name: 'Recursive Character (Custom)',
    description: 'Custom implementation with configurable separators and chunk merging',
    icon: 'account_tree',
    bestFor: 'Fine-tuned chunking control'
  },
  {
    id: 'recursive-character',
    name: 'Recursive Character (Core)',
    description: 'Core recursive character splitter with overlap support',
    icon: 'account_tree',
    bestFor: 'Basic recursive splitting'
  },
  {
    id: 'opennlp_sentence',
    name: 'Sentence (OpenNLP)',
    description: 'ML-based sentence boundary detection with multi-language support',
    icon: 'psychology',
    bestFor: 'Multi-language, academic text'
  },
  {
    id: 'sentence',
    name: 'Sentence (Regex)',
    description: 'Fast regex-based sentence detection with abbreviation handling',
    icon: 'short_text',
    bestFor: 'English text, fast processing'
  },
  {
    id: 'spring_token',
    name: 'Token-based',
    description: 'Token counting using CL100K encoding (GPT-4 compatible)',
    icon: 'tag',
    bestFor: 'LLM context optimization'
  },
  {
    id: 'custom_markdown',
    name: 'Markdown (Custom)',
    description: 'Structure-aware chunking preserving markdown headings and code blocks',
    icon: 'code',
    bestFor: 'Markdown, documentation'
  },
  {
    id: 'spring_markdown',
    name: 'Markdown (Spring)',
    description: 'Spring AI markdown document splitter',
    icon: 'code',
    bestFor: 'Markdown with Spring AI'
  }
];

/**
 * Default chunking options.
 */
export const DEFAULT_CHUNKING_OPTIONS: ChunkingOptions = {
  chunkSize: 1000,
  overlap: 200,
  preserveParagraphs: true,
  minChunkSize: 100,
  maxChunkSize: 2000,
  language: 'en',
  splitOnHeadings: true
};

/**
 * Default chunking configuration.
 */
export const DEFAULT_CHUNKING_CONFIG: ChunkingConfig = {
  useCustomSettings: false,
  strategy: 'auto',
  options: { ...DEFAULT_CHUNKING_OPTIONS }
};

/**
 * Document size thresholds for recommendations.
 */
export const DOCUMENT_SIZE_THRESHOLDS = {
  SMALL: 100 * 1024,           // 100 KB
  MEDIUM: 1 * 1024 * 1024,     // 1 MB
  LARGE: 10 * 1024 * 1024,     // 10 MB
  VERY_LARGE: 50 * 1024 * 1024 // 50 MB
};

/**
 * Large document handling mode.
 */
export type LargeDocumentMode = 'standard' | 'streaming' | 'batch' | 'hierarchical';

/**
 * Large document handling configuration.
 */
export interface LargeDocumentConfig {
  /** Processing mode for large documents */
  mode: LargeDocumentMode;

  /** Enable memory-efficient streaming processing */
  enableStreaming: boolean;

  /** Process in smaller batches to manage memory */
  batchSize: number;

  /** Enable hierarchical chunking (summary + detail chunks) */
  enableHierarchical: boolean;

  /** Create parent document summaries */
  createSummaries: boolean;

  /** Maximum document size before warning (bytes) */
  warningSizeThreshold: number;

  /** Maximum document size before requiring confirmation (bytes) */
  confirmationSizeThreshold: number;

  /** Enable automatic text extraction optimization */
  optimizeExtraction: boolean;

  /** Skip embedded images/media for faster processing */
  skipEmbeddedMedia: boolean;
}

/**
 * Default large document configuration.
 */
export const DEFAULT_LARGE_DOCUMENT_CONFIG: LargeDocumentConfig = {
  mode: 'standard',
  enableStreaming: false,
  batchSize: 50,
  enableHierarchical: false,
  createSummaries: false,
  warningSizeThreshold: DOCUMENT_SIZE_THRESHOLDS.LARGE,
  confirmationSizeThreshold: DOCUMENT_SIZE_THRESHOLDS.VERY_LARGE,
  optimizeExtraction: true,
  skipEmbeddedMedia: false
};

/**
 * Recommendation severity level.
 */
export type RecommendationSeverity = 'info' | 'suggestion' | 'warning' | 'critical';

/**
 * Chunking recommendation.
 */
export interface ChunkingRecommendation {
  id: string;
  severity: RecommendationSeverity;
  title: string;
  description: string;
  action?: {
    label: string;
    preset?: string;
    strategy?: ChunkerStrategy;
    options?: Partial<ChunkingOptions>;
    largeDocConfig?: Partial<LargeDocumentConfig>;
  };
  icon: string;
}

/**
 * Document analysis result for recommendations.
 */
export interface DocumentAnalysis {
  totalSize: number;
  fileCount: number;
  averageSize: number;
  largestFile: { name: string; size: number } | null;
  fileTypes: { [ext: string]: number };
  dominantType: string | null;
  hasLargeFiles: boolean;
  hasVeryLargeFiles: boolean;
  estimatedChunks: number;
  estimatedProcessingTime: string;
  memoryWarning: boolean;
}

/**
 * File type categories for recommendations.
 */
export const FILE_TYPE_CATEGORIES: { [key: string]: { category: string; recommendedStrategy: ChunkerStrategy; description: string } } = {
  // Text documents
  'txt': { category: 'text', recommendedStrategy: 'spring_recursive_character', description: 'Plain text' },
  'md': { category: 'markdown', recommendedStrategy: 'custom_markdown', description: 'Markdown' },
  'markdown': { category: 'markdown', recommendedStrategy: 'custom_markdown', description: 'Markdown' },

  // Office documents
  'pdf': { category: 'document', recommendedStrategy: 'spring_recursive_character', description: 'PDF document' },
  'doc': { category: 'document', recommendedStrategy: 'opennlp_sentence', description: 'Word document' },
  'docx': { category: 'document', recommendedStrategy: 'opennlp_sentence', description: 'Word document' },
  'rtf': { category: 'document', recommendedStrategy: 'spring_recursive_character', description: 'Rich text' },
  'odt': { category: 'document', recommendedStrategy: 'opennlp_sentence', description: 'OpenDocument text' },

  // Spreadsheets
  'csv': { category: 'data', recommendedStrategy: 'spring_recursive_character', description: 'CSV data' },
  'xls': { category: 'spreadsheet', recommendedStrategy: 'spring_recursive_character', description: 'Excel spreadsheet' },
  'xlsx': { category: 'spreadsheet', recommendedStrategy: 'spring_recursive_character', description: 'Excel spreadsheet' },

  // Code/structured
  'html': { category: 'code', recommendedStrategy: 'custom_markdown', description: 'HTML' },
  'htm': { category: 'code', recommendedStrategy: 'custom_markdown', description: 'HTML' },
  'xml': { category: 'code', recommendedStrategy: 'spring_recursive_character', description: 'XML' },
  'json': { category: 'code', recommendedStrategy: 'spring_recursive_character', description: 'JSON' },

  // Presentations
  'ppt': { category: 'presentation', recommendedStrategy: 'spring_recursive_character', description: 'PowerPoint' },
  'pptx': { category: 'presentation', recommendedStrategy: 'spring_recursive_character', description: 'PowerPoint' },

  // Email
  'eml': { category: 'email', recommendedStrategy: 'opennlp_sentence', description: 'Email' },
  'msg': { category: 'email', recommendedStrategy: 'opennlp_sentence', description: 'Outlook email' },

  // eBooks
  'epub': { category: 'ebook', recommendedStrategy: 'opennlp_sentence', description: 'eBook' }
};

/**
 * Large document mode information for UI display.
 */
export interface LargeDocumentModeInfo {
  id: LargeDocumentMode;
  name: string;
  description: string;
  icon: string;
  bestFor: string;
  memoryUsage: 'low' | 'medium' | 'high';
  processingSpeed: 'fast' | 'medium' | 'slow';
}

/**
 * Available large document processing modes.
 */
export const LARGE_DOCUMENT_MODES: LargeDocumentModeInfo[] = [
  {
    id: 'standard',
    name: 'Standard',
    description: 'Process entire document at once',
    icon: 'description',
    bestFor: 'Small to medium documents',
    memoryUsage: 'high',
    processingSpeed: 'fast'
  },
  {
    id: 'streaming',
    name: 'Streaming',
    description: 'Process document in chunks to reduce memory',
    icon: 'stream',
    bestFor: 'Large documents, limited memory',
    memoryUsage: 'low',
    processingSpeed: 'medium'
  },
  {
    id: 'batch',
    name: 'Batch Processing',
    description: 'Split into batches with controlled indexing',
    icon: 'layers',
    bestFor: 'Very large documents, stability',
    memoryUsage: 'medium',
    processingSpeed: 'slow'
  },
  {
    id: 'hierarchical',
    name: 'Hierarchical',
    description: 'Create summary and detail chunks for better retrieval',
    icon: 'account_tree',
    bestFor: 'Long documents needing context',
    memoryUsage: 'medium',
    processingSpeed: 'slow'
  }
];

/**
 * Preset chunking configurations for common use cases.
 */
export interface ChunkingPreset {
  id: string;
  name: string;
  description: string;
  config: Partial<ChunkingConfig>;
}

/**
 * Available chunking presets.
 */
export const CHUNKING_PRESETS: ChunkingPreset[] = [
  {
    id: 'default',
    name: 'Default',
    description: 'Balanced settings for general use',
    config: {
      strategy: 'auto',
      options: { chunkSize: 1000, overlap: 200, preserveParagraphs: true }
    }
  },
  {
    id: 'small-chunks',
    name: 'Small Chunks',
    description: 'Smaller chunks for precise retrieval',
    config: {
      strategy: 'spring_recursive_character',
      options: { chunkSize: 500, overlap: 100, preserveParagraphs: true }
    }
  },
  {
    id: 'large-chunks',
    name: 'Large Chunks',
    description: 'Larger chunks for more context',
    config: {
      strategy: 'spring_recursive_character',
      options: { chunkSize: 2000, overlap: 400, preserveParagraphs: true }
    }
  },
  {
    id: 'sentence-based',
    name: 'Sentence-based',
    description: 'Preserve sentence boundaries',
    config: {
      strategy: 'opennlp_sentence',
      options: { chunkSize: 800, overlap: 100, preserveParagraphs: true, language: 'en' }
    }
  },
  {
    id: 'token-optimized',
    name: 'Token Optimized',
    description: 'Optimized for LLM token limits',
    config: {
      strategy: 'spring_token',
      options: { chunkSize: 512, overlap: 50, preserveParagraphs: false }
    }
  },
  {
    id: 'markdown',
    name: 'Markdown Docs',
    description: 'Preserve markdown structure and headings',
    config: {
      strategy: 'custom_markdown',
      options: { chunkSize: 1500, overlap: 200, preserveParagraphs: true, splitOnHeadings: true }
    }
  }
];

// For AddSourceDialogComponent communication
export interface AddSourceDialogResult {
  file?: File;
  files?: File[]; // Support for multiple file upload
  url?: string;
  fileName?: string;
  selectedLoader?: string;
  rebuildIndex?: boolean; // Added flag for optional rebuild
  chunkerName?: string; // Selected chunker strategy
  chunkerOptions?: { [key: string]: any }; // Chunking options
  largeDocumentConfig?: Partial<LargeDocumentConfig>; // Large document handling
  // Tokenizer configuration
  tokenizerModel?: string; // Selected tokenizer model (e.g., 'bert-base-uncased')
  maxTokenLength?: number; // Maximum token length for pre-tokenization
  enablePreTokenization?: boolean; // Whether to enable pre-tokenization stage
  // Adaptive performance mode configuration
  adaptivePerformanceConfig?: AdaptivePerformanceConfig;
  // Subprocess ingest configuration (global settings)
  subprocessConfig?: SubprocessIngestConfig;
  /**
   * Per-request processing mode override.
   * - 'auto': Use global subprocess configuration (default)
   * - 'subprocess': Force subprocess mode (isolated JVM)
   * - 'inprocess': Force in-process mode (same JVM)
   */
  processingMode?: ProcessingMode;
}

/**
 * Configuration for adaptive performance mode.
 * When enabled, batch sizes are automatically adjusted based on system metrics.
 */
export interface AdaptivePerformanceConfig {
  /** Enable adaptive performance mode */
  enabled: boolean;
  /** Target memory usage percentage (will reduce batch size if exceeded) */
  targetMemoryPercent: number;
  /** Critical memory threshold (aggressive reduction) */
  criticalMemoryPercent: number;
  /** Minimum embedding batch size */
  minEmbeddingBatch: number;
  /** Maximum embedding batch size */
  maxEmbeddingBatch: number;
  /** Minimum index batch size */
  minIndexBatch: number;
  /** Maximum index batch size */
  maxIndexBatch: number;
  /** How often to check metrics (ms) */
  checkIntervalMs: number;
  /** Cooldown between adjustments (ms) */
  adjustmentCooldownMs: number;
}

// ═══════════════════════════════════════════════════════════════════════════════
// SUBPROCESS INGEST CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Configuration for subprocess-based document ingestion.
 * When enabled, document processing runs in isolated JVM processes for crash isolation.
 */
export interface SubprocessIngestConfig {
  /** Enable subprocess mode for document ingestion */
  enabled: boolean;
  /** JVM heap size for subprocess (e.g., "4g", "8g") */
  heapSize: string;
  /** JavaCPP native/off-heap limit (e.g., "8g", "8192m", or bytes); blank means auto (2x heap) */
  offHeapMaxBytes: string;
  /** Maximum job timeout in minutes */
  timeoutMinutes: number;
  /** Heartbeat interval in seconds for liveness checking */
  heartbeatIntervalSeconds: number;
  /** Seconds without heartbeat before process is considered stale */
  staleThresholdSeconds: number;
}

/**
 * Default subprocess ingest configuration.
 */
export const DEFAULT_SUBPROCESS_CONFIG: SubprocessIngestConfig = {
  enabled: false,  // Default to in-process mode for simplicity
  heapSize: '4g',
  offHeapMaxBytes: '',
  timeoutMinutes: 60,
  heartbeatIntervalSeconds: 10,
  staleThresholdSeconds: 120
};

/**
 * Heap size options for subprocess configuration.
 */
export const HEAP_SIZE_OPTIONS = ['1g', '2g', '4g', '6g', '8g', '12g', '16g'];

/**
 * Get recommended heap size based on available system memory in MB.
 */
export function getRecommendedHeapSize(availableMemoryMB: number): string {
  if (availableMemoryMB <= 4096) return '1g';
  if (availableMemoryMB <= 8192) return '2g';
  if (availableMemoryMB <= 16384) return '4g';
  if (availableMemoryMB <= 32768) return '8g';
  return '12g';
}

// Async Upload & WebSocket Progress Tracking
export interface AsyncUploadResponse {
  taskId: string | null;
  fileName: string;
  message: string;
  websocketTopic: string | null;
  accepted: boolean;
  error: string | null;
}

export enum IngestPhase {
  QUEUED = 'QUEUED',
  UPLOADING = 'UPLOADING',
  LOADING = 'LOADING',
  CONVERTING = 'CONVERTING',
  CHUNKING = 'CHUNKING',
  EMBEDDING = 'EMBEDDING',
  INDEXING = 'INDEXING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED'
}

export enum IngestStatus {
  PENDING = 'PENDING',
  IN_PROGRESS = 'IN_PROGRESS',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED'
}

export interface IngestStats {
  documentsLoaded: number;
  chunksCreated: number;
  chunksEmbedded: number;
  chunksIndexed: number;  // Number of chunks written to vector store
  documentsIndexed: number;
  totalProcessingTimeMs: number;
  loaderUsed: string | null;
  chunkerUsed: string | null;
  processedDocumentIds: string[];
  // Enhanced timing breakdown
  loadingTimeMs?: number;
  conversionTimeMs?: number;
  chunkingTimeMs?: number;
  embeddingTimeMs?: number;
  indexingTimeMs?: number;
  // Batch processing details
  currentBatch?: number;
  totalBatches?: number;
  batchSize?: number;
  parallelProcessing?: boolean;
  workerThreads?: number;
  // Throughput metrics
  chunksPerSecond?: number;
  docsPerSecond?: number;
  // Memory info
  memoryUsagePercent?: number;
  memoryStatus?: 'OK' | 'WARNING' | 'CRITICAL';
  // ========== PIPELINE ARCHITECTURE DETAILS ==========
  // Chunking pipeline info
  chunkingThreads?: number;
  chunkingQueueSize?: number;
  // Embedding pipeline info (pipelined tokenization + inference)
  pipelinedEmbedding?: boolean;
  tokenizationThreads?: number;
  tokenizationQueueSize?: number;
  embeddingQueueDepth?: number;
  tokenizationRate?: number;      // tokens/sec
  inferenceRate?: number;         // embeddings/sec
  // Indexing pipeline info (Lucene batch mode)
  luceneBatchMode?: boolean;
  ramBufferSizeMb?: number;
  mergeThreads?: number;
  // Active stage indicators
  activeStage?: 'CHUNKING' | 'TOKENIZING' | 'EMBEDDING' | 'INDEXING';
  pipelineStatus?: 'IDLE' | 'WARMING_UP' | 'PROCESSING' | 'DRAINING' | 'COMPLETE';
  // Per-worker status
  workerStatuses?: WorkerStatusDto[];
  queueStatus?: QueueStatusDto;
  // ========== EMBEDDING INFERENCE METRICS ==========
  // Current embedding batch details
  currentEmbeddingBatch?: EmbeddingBatchMetrics;
  // ========== SUBPROCESS RUNTIME INFO ==========
  // Runtime details when running in subprocess mode
  subprocessRuntimeInfo?: SubprocessRuntimeInfo;
}

export interface WorkerStatusDto {
  workerId: number;
  workerType: 'chunking' | 'embedding' | 'indexing';
  status: 'idle' | 'processing' | 'waiting' | 'complete';
  itemsProcessed: number;
  currentBatchSize: number;
  throughput: number;
  currentItem: string | null;
}

export interface QueueStatusDto {
  chunkQueueSize: number;
  chunkQueueCapacity: number;
  embeddedQueueSize: number;
  embeddedQueueCapacity: number;
  chunkQueueUtilization: number;
  embeddedQueueUtilization: number;
}

/**
 * Metrics for the current embedding batch being processed.
 * Provides detailed insight into the embedding inference process.
 */
export interface EmbeddingBatchMetrics {
  // Batch identification
  batchNumber?: number;           // Current batch number (1-indexed)
  totalBatches?: number;          // Total expected batches

  // Input metrics
  inputTexts?: number;            // Number of text chunks in this batch
  inputTokens?: number;           // Total tokens in this batch (if available)
  maxSequenceLength?: number;     // Max sequence length after padding
  avgSequenceLength?: number;     // Average sequence length before padding

  // Output metrics
  outputVectors?: number;         // Number of embedding vectors produced
  embeddingDimension?: number;    // Dimension of each embedding vector (e.g., 768, 1024)
  outputSizeBytes?: number;       // Total size of output embeddings in bytes

  // Timing metrics - coarse grained
  tokenizationTimeMs?: number;    // Time spent tokenizing this batch
  inferenceTimeMs?: number;       // Time spent in model inference
  totalBatchTimeMs?: number;      // Total time for this batch (tokenize + inference + overhead)

  // Detailed timing breakdown - fine grained (from encoder)
  paddingTimeMs?: number;         // Time spent padding sequences
  tensorCreationTimeMs?: number;  // Time spent creating input tensors (INDArray)
  forwardPassTimeMs?: number;     // Time spent in actual neural network forward pass
  extractionTimeMs?: number;      // Time spent extracting embeddings from output

  // Heartbeat/liveness tracking
  currentStep?: string;           // Current inference step: TOKENIZING, PADDING, TENSOR_CREATION, FORWARD_PASS, EXTRACTION
  heartbeatSeconds?: number;      // Seconds elapsed since batch started (for liveness)
  stepStartTimeMs?: number;       // When current step started (epoch ms)
  isStuck?: boolean;              // True if step is taking unusually long (>30s)

  // Throughput metrics
  tokensPerSecond?: number;       // Tokenization throughput
  embeddingsPerSecond?: number;   // Inference throughput
  batchThroughput?: number;       // Overall batch throughput (texts/sec)

  // Model info
  modelName?: string;             // Name of the embedding model
  deviceType?: string;            // CPU, CUDA, etc.
  isBatched?: boolean;            // Whether batched inference is being used
}

/**
 * Runtime information about the subprocess executing the ingest pipeline.
 * Provides visibility into the isolated JVM process.
 */
export interface SubprocessRuntimeInfo {
  // Process identification
  pid?: number;
  uptimeMs?: number;
  processMode?: 'SUBPROCESS' | 'IN_PROCESS' | 'UNKNOWN';

  // JVM info
  javaVersion?: string;
  javaVendor?: string;
  javaHome?: string;
  vmName?: string;
  vmVersion?: string;

  // Memory configuration
  heapMaxBytes?: number;
  heapUsedBytes?: number;
  heapFreeBytes?: number;
  heapUsagePercent?: number;
  nonHeapUsedBytes?: number;

  // GC info
  gcCount?: number;
  gcTimeMs?: number;

  // Process resources
  availableProcessors?: number;
  workingDirectory?: string;
  tempDirectory?: string;

  // Command line
  commandLine?: string;
  jvmArguments?: string[];
  inputFiles?: string[];

  // Environment variables
  ndj4Backend?: string;
  cudaVisibleDevices?: string;
  ompNumThreads?: string;
  mklNumThreads?: string;

  // ND4J environment configuration snapshots
  nd4jEnvironmentInvoked?: Nd4jEnvironmentConfig;
  nd4jEnvironmentUsed?: Nd4jEnvironmentConfig;

  // Native library info
  nd4jBackend?: string;
  blasVendor?: string;
  cudaAvailable?: boolean;
  cudaVersion?: string;

  // Model info
  embeddingModelId?: string;
  embeddingModelPath?: string;
  embeddingDimension?: number;
}

export interface IngestProgressUpdate {
  taskId: string;
  fileName: string;
  phase: IngestPhase;
  status: IngestStatus;
  progressPercent: number;
  currentStep: string;
  message: string;
  stats: IngestStats;
  errorMessage: string | null;
  timestamp: string;
}

export interface BatchAsyncUploadResponse {
  files: AsyncUploadResponse[];
  acceptedCount: number;
  rejectedCount: number;
  message: string;
  websocketTopic: string | null;
}

export interface CancelTaskResponse {
  taskId: string;
  cancelled: boolean;
  message: string;
}

// ═══════════════════════════════════════════════════════════════════════════════
// PROCESSING SETTINGS & MEMORY MONITORING
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Memory status information for monitoring system resources.
 */
export interface MemoryStatus {
  maxMemoryMB: number;
  usedMemoryMB: number;
  freeMemoryMB: number;
  usagePercent: number;
  thresholdExceeded: boolean;
  criticalExceeded: boolean;
  status: 'OK' | 'WARNING' | 'CRITICAL';
}

/**
 * Processing settings for document ingestion pipeline.
 *
 * NOTE: This controls the INDEX BATCH SIZE - how many documents/chunks are batched
 * together when writing to the Lucene vector index. This is DIFFERENT from the
 * embedding batch size (which controls neural network inference batch size).
 *
 * Index Batch Size recommendations based on memory:
 * - < 4GB RAM:  50 chunks per batch
 * - 4-8GB RAM:  100 chunks per batch
 * - 8-16GB RAM: 150 chunks per batch
 * - > 16GB RAM: 200 chunks per batch
 */
export interface ProcessingSettings {
  // Concurrency settings
  /** Maximum number of concurrent document processing jobs */
  maxConcurrentJobs: number;
  /** Currently running jobs */
  activeJobs: number;
  /** Jobs waiting in queue */
  queuedJobs: number;
  /** Whether the system can accept new jobs */
  canAcceptNewJob: boolean;

  // Index/Storage Batch settings (different from embedding batch!)
  /**
   * INDEX BATCH SIZE: Number of chunks batched together for Lucene index writes.
   * This is NOT the embedding batch size. Use batch-size-config for embedding settings.
   */
  indexBatchSize: number;
  /** Minimum allowed index batch size */
  minBatchSize: number;
  /** Maximum allowed index batch size */
  maxBatchSize: number;
  /** Whether to dynamically adjust batch size based on memory pressure */
  adaptiveBatchSize: boolean;

  // Memory settings
  /** Memory usage percentage threshold for warnings (e.g., 80) */
  memoryThresholdPercent: number;
  /** Memory usage percentage threshold for critical actions (e.g., 90) */
  memoryCriticalPercent: number;
  /** Current memory status */
  memoryStatus: MemoryStatus;

  // Thread pool info
  corePoolSize: number;
  maxPoolSize: number;
  activeThreads: number;
  queueSize: number;
}

/**
 * Request to update processing settings.
 */
export interface ProcessingSettingsUpdate {
  /** Maximum concurrent document processing jobs */
  maxConcurrentJobs?: number;
  /**
   * INDEX BATCH SIZE: Chunks per Lucene batch write.
   * For EMBEDDING batch size, use the Batch Size Config component instead.
   */
  indexBatchSize?: number;
  /** Memory threshold percentage for warnings */
  memoryThresholdPercent?: number;
  /** Enable adaptive batch sizing based on memory pressure */
  adaptiveBatchSize?: boolean;
}

/**
 * Recommended batch sizes based on available system memory.
 * Use these as guidelines when configuring batch sizes.
 */
export const RECOMMENDED_BATCH_SIZES = {
  /** For systems with < 4GB RAM */
  LOW_MEMORY: {
    embeddingBatch: 4,
    maxEmbeddingBatch: 8,
    indexBatch: 50,
    description: 'Conservative settings for low-memory systems'
  },
  /** For systems with 4-8GB RAM */
  MEDIUM_MEMORY: {
    embeddingBatch: 8,
    maxEmbeddingBatch: 16,
    indexBatch: 100,
    description: 'Balanced settings for typical systems'
  },
  /** For systems with 8-16GB RAM */
  HIGH_MEMORY: {
    embeddingBatch: 16,
    maxEmbeddingBatch: 32,
    indexBatch: 150,
    description: 'Performance settings for well-provisioned systems'
  },
  /** For systems with > 16GB RAM */
  VERY_HIGH_MEMORY: {
    embeddingBatch: 32,
    maxEmbeddingBatch: 64,
    indexBatch: 200,
    description: 'High-throughput settings for powerful systems'
  }
} as const;

/**
 * Get recommended batch sizes based on available memory in MB.
 */
export function getRecommendedBatchSizes(availableMemoryMB: number): typeof RECOMMENDED_BATCH_SIZES[keyof typeof RECOMMENDED_BATCH_SIZES] {
  if (availableMemoryMB < 4096) {
    return RECOMMENDED_BATCH_SIZES.LOW_MEMORY;
  } else if (availableMemoryMB < 8192) {
    return RECOMMENDED_BATCH_SIZES.MEDIUM_MEMORY;
  } else if (availableMemoryMB < 16384) {
    return RECOMMENDED_BATCH_SIZES.HIGH_MEMORY;
  } else {
    return RECOMMENDED_BATCH_SIZES.VERY_HIGH_MEMORY;
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONVERSATIONAL RAG MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Chat request for conversational RAG.
 */
export interface ConversationalChatRequest {
  conversationId?: string;
  message: string;
  options?: ConversationalRagOptions;
}

/**
 * RAG configuration options.
 */
export interface ConversationalRagOptions {
  semanticK?: number;
  keywordK?: number;
  similarityThreshold?: number;
  maxHistoryMessages?: number;
  maxContextTokens?: number;
  useToolCalling?: boolean;
  systemPrompt?: string;
  enableQueryProcessing?: boolean;
  rerankerConfig?: RerankerConfig;
}

/**
 * Chat response from conversational RAG.
 */
export interface ConversationalChatResponse {
  conversationId: string;
  answer: string;
  documents?: RetrievedDocument[];
  queryMetadata?: QueryMetadata;
  metrics?: PerformanceMetrics;
  error?: string;
}

/**
 * Retrieved document with score.
 */
export interface RetrievedDocument {
  id: string;
  content: string;
  score: number;
  metadata?: { [key: string]: any };
}

/**
 * Query processing metadata.
 */
export interface QueryMetadata {
  originalQuery: string;
  rewrittenQuery: string;
  wasRewritten: boolean;
  intent?: string;
}

/**
 * Performance metrics for RAG operations.
 */
export interface PerformanceMetrics {
  totalMs: number;
  generationMs: number;
  retrievalMs: number;
  documentsRetrieved: number;
}

/**
 * Conversation history message.
 */
export interface ConversationMessage {
  role: string;
  content: string;
}

/**
 * Conversation history response.
 */
export interface ConversationHistoryResponse {
  conversationId: string;
  messages: ConversationMessage[];
  error?: string;
}

/**
 * RAG service status.
 */
export interface RagServiceStatus {
  available: boolean;
  service: string;
}

/**
 * Search type for RAG configuration.
 */
export type SearchType = 'hybrid' | 'semantic' | 'keyword';

/**
 * Default RAG configuration values.
 */
export const DEFAULT_RAG_OPTIONS: ConversationalRagOptions = {
  semanticK: 5,
  keywordK: 5,
  similarityThreshold: 0.5,
  maxHistoryMessages: 10,
  maxContextTokens: 4000,
  useToolCalling: false,
  enableQueryProcessing: true
};

// ═══════════════════════════════════════════════════════════════════════════════
// RERANKING CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Available reranker types.
 */
export type RerankerType = 'none' | 'rm3' | 'bm25prf' | 'rocchio' | 'score_ties';

/**
 * Reranker configuration options.
 */
export interface RerankerConfig {
  /** Whether reranking is enabled */
  enabled: boolean;

  /** Type of reranker to use */
  type: RerankerType;

  /** Number of feedback documents for query expansion */
  fbDocs: number;

  /** Number of feedback terms to use */
  fbTerms: number;

  /** Weight for original query in RM3 interpolation (0.0-1.0) */
  originalQueryWeight: number;

  /** Whether to filter non-alphanumeric expansion terms */
  filterTerms: boolean;

  /** BM25 k1 parameter */
  k1: number;

  /** BM25 b parameter */
  b: number;

  /** Weight boost for newly added expansion terms */
  newTermWeight: number;

  /** Rocchio alpha parameter (original query weight) */
  alpha: number;

  /** Rocchio beta parameter (positive feedback weight) */
  beta: number;

  /** Rocchio gamma parameter (negative feedback weight) */
  gamma: number;

  /** Whether to use negative feedback in Rocchio */
  useNegative: boolean;
}

/**
 * Default reranker configuration values.
 */
export const DEFAULT_RERANKER_CONFIG: RerankerConfig = {
  enabled: false,
  type: 'rm3',
  fbDocs: 10,
  fbTerms: 10,
  originalQueryWeight: 0.5,
  filterTerms: true,
  k1: 0.9,
  b: 0.4,
  newTermWeight: 0.2,
  alpha: 1.0,
  beta: 0.75,
  gamma: 0.15,
  useNegative: false
};

/**
 * Reranker type information for UI display.
 */
export interface RerankerTypeInfo {
  id: RerankerType;
  name: string;
  description: string;
}

/**
 * Available reranker types with descriptions.
 */
export const RERANKER_TYPES: RerankerTypeInfo[] = [
  {
    id: 'none',
    name: 'None',
    description: 'No reranking applied'
  },
  {
    id: 'rm3',
    name: 'RM3',
    description: 'Relevance Model 3 - Query expansion using pseudo-relevance feedback'
  },
  {
    id: 'bm25prf',
    name: 'BM25-PRF',
    description: 'BM25-weighted pseudo-relevance feedback'
  },
  {
    id: 'rocchio',
    name: 'Rocchio',
    description: 'Vector space model query expansion'
  },
  {
    id: 'score_ties',
    name: 'Score Ties',
    description: 'Deterministic tie-breaking for reproducibility'
  }
];

// ═══════════════════════════════════════════════════════════════════════════════
// LOCAL AGENT CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Process state for agent execution.
 */
export type ProcessState = 'STARTING' | 'RUNNING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'TIMEOUT' | 'CANCELLED';

/**
 * Agent provider configuration.
 */
export interface AgentProvider {
  /** Unique identifier for the agent */
  name: string;

  /** Human-readable display name */
  displayName: string;

  /** CLI command to execute */
  command: string;

  /** Flag to skip permission prompts */
  skipPermissionsFlag: string;

  /** Whether to skip permissions by default */
  skipPermissions: boolean;

  /** Additional command arguments */
  args: string[];

  /** Environment variables */
  environment: { [key: string]: string };

  /** Whether the CLI is installed and accessible */
  available: boolean;

  /** Whether this is the default agent */
  isDefault: boolean;

  /** Description of the agent */
  description: string;
}

/**
 * Process status tracking.
 */
export interface ProcessStatus {
  /** Unique process ID */
  id: string;

  /** Name of the agent running */
  agentName: string;

  /** Process start time */
  startTime: string;

  /** Process end time (if completed) */
  endTime?: string;

  /** Current process state */
  state: ProcessState;

  /** OS process ID */
  pid?: number;

  /** Command being executed */
  command: string;

  /** Command arguments */
  commandArgs: string[];

  /** Exit code (if completed) */
  exitCode?: number;

  /** Error message (if failed) */
  errorMessage?: string;

  /** Number of output lines received */
  linesReceived: number;

  /** Number of chunks streamed to client */
  chunksStreamed: number;

  /** Total bytes received */
  bytesReceived: number;

  /** List of modified files */
  modifiedFiles: string[];

  /** Recent output lines */
  recentOutput: string[];

  /** Additional metadata */
  metadata: { [key: string]: any };
}

/**
 * Diagnostic summary for quick status check.
 */
export interface AgentDiagnosticSummary {
  /** Whether there's an active process */
  hasActiveProcess: boolean;

  /** ID of active process */
  activeProcessId?: string;

  /** Name of active agent */
  activeAgentName?: string;

  /** State of active process */
  activeProcessState?: ProcessState;

  /** Duration of active process in ms */
  activeProcessDurationMs?: number;

  /** Lines received by active process */
  activeProcessLinesReceived?: number;

  /** Count of recent processes */
  recentProcessCount: number;

  /** Count of failed processes */
  failedProcessCount: number;

  /** Time of last process */
  lastProcessTime?: string;

  /** Last error message */
  lastError?: string;
}

/**
 * Full diagnostic report.
 */
export interface AgentFullDiagnosticReport {
  /** Summary information */
  summary: AgentDiagnosticSummary;

  /** Current active process (if any) */
  currentProcess?: ProcessStatus;

  /** List of recent processes */
  recentProcesses: ProcessStatus[];
}

/**
 * Agent availability check response.
 */
export interface AgentAvailabilityResponse {
  agentName: string;
  available: boolean;
  timestamp: number;
}

/**
 * Agent count summary.
 */
export interface AgentCountSummary {
  total: number;
  available: number;
}

/**
 * Active process status response.
 */
export interface ActiveProcessResponse {
  hasActiveProcess: boolean;
  processId?: string;
  agentName?: string;
  state?: ProcessState;
}

/**
 * Agent selection info for UI display.
 */
export interface AgentSelectionInfo {
  id: string;
  name: string;
  displayName: string;
  description: string;
  available: boolean;
  isDefault: boolean;
  icon?: string;
}

/**
 * Convert AgentProvider to AgentSelectionInfo for UI.
 */
export function toAgentSelectionInfo(agent: AgentProvider): AgentSelectionInfo {
  const iconMap: { [key: string]: string } = {
    'claude': '🤖',
    'codex': '💻',
    'gemini': '✨'
  };

  return {
    id: agent.name,
    name: agent.name,
    displayName: agent.displayName,
    description: agent.description,
    available: agent.available,
    isDefault: agent.isDefault,
    icon: iconMap[agent.name] || '🔧'
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// LOCAL AGENT CHAT SESSION & MESSAGES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Message role for chat messages.
 */
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';

/**
 * Chat message for local agent conversations.
 */
export interface LocalAgentMessage {
  /** Unique message ID */
  id: string;

  /** Session this message belongs to */
  sessionId: string;

  /** Message role */
  role: MessageRole;

  /** Message content */
  content: string;

  /** Raw response from agent (before any parsing) */
  rawResponse?: string;

  /** Message timestamp */
  timestamp: string;

  /** Agent that generated this message (for assistant messages) */
  agent?: AgentProvider;

  /** Token count (if available) */
  tokenCount?: number;

  /** Response latency in ms */
  latencyMs?: number;

  /** Whether this message is currently streaming */
  streaming?: boolean;

  /** Error flag */
  error?: boolean;

  /** Error message */
  errorMessage?: string;

  /** Files modified by this message (for agent responses) */
  modifiedFiles?: string[];

  /** Tool uses in this message */
  toolUses?: ToolUseEvent[];

  /** Retrieved sources from RAG (for footnotes) */
  sources?: RetrievedSource[];

  // ═══════════════════════════════════════════════════════════════════════════════
  // BRANCHING/FORK FIELDS
  // ═══════════════════════════════════════════════════════════════════════════════

  /** Parent message ID (for tracking conversation tree) */
  parentId?: string;

  /** Sibling message IDs (alternative responses at this point) */
  siblingIds?: string[];

  /** Current sibling index (which branch we're viewing) */
  siblingIndex?: number;

  /** Total number of siblings including this message */
  siblingCount?: number;

  /** Whether sources section is expanded */
  _sourcesExpanded?: boolean;

  // Performance cache fields
  /** Cached truncated content for display */
  _truncatedContent?: string;

  /** Whether content is large (> 2000 chars) */
  _isLarge?: boolean;

  /** Whether content is expanded in UI */
  _isExpanded?: boolean;

  /** Full content length */
  _fullLength?: number;

  /** Cached lowercase role for CSS */
  _roleLowerCase?: string;
}

/**
 * Tool use event from agent.
 */
export interface ToolUseEvent {
  tool: string;
  input: string;
}

/**
 * Retrieved source/chunk from RAG for display in footnotes.
 */
export interface RetrievedSource {
  /** Source index (1-based) */
  index: number;

  /** Document ID */
  id: string;

  /** Relevance score */
  score: number;

  /** Source name (filename, title, etc.) */
  sourceName: string;

  /** Content preview (short) */
  preview: string;

  /** Full content (may be truncated) */
  content: string;

  /** Document metadata */
  metadata?: { [key: string]: any };

  /** Whether this source is expanded in the UI */
  _expanded?: boolean;
}

/**
 * Result event from agent session.
 */
export interface ResultEvent {
  durationMs: number;
  costUsd?: number;
  numTurns?: number;
  isError: boolean;
}

/**
 * Chat session for local agent conversations.
 */
export interface LocalAgentSession {
  /** Unique session ID */
  id: string;

  /** Session name/title */
  name: string;

  /** Creation timestamp */
  createdAt: string;

  /** Last updated timestamp */
  updatedAt: string;

  /** Active agent for this session */
  activeAgent?: AgentProvider;

  /** Messages in this session (current branch view) */
  messages: LocalAgentMessage[];

  /** All messages including all branches (flat storage) */
  allMessages?: LocalAgentMessage[];

  /** Current branch path (message IDs from root to current) */
  currentBranchPath?: string[];

  /** Whether session is archived */
  archived: boolean;

  /** Total tokens used */
  totalTokens: number;

  /** Message count */
  messageCount: number;

  /** Working directory for this session */
  workingDirectory?: string;

  /** Session metadata */
  metadata?: { [key: string]: any };
}

/**
 * Chat tab state for multi-tab support.
 */
export interface ChatTabState {
  /** Unique tab ID */
  tabId: string;

  /** Associated session */
  session: LocalAgentSession | null;

  /** Working messages in UI */
  messages: LocalAgentMessage[];

  /** Current streaming content */
  streamingContent: string;

  /** Whether tab is streaming */
  isStreaming: boolean;

  /** User input for this tab */
  userInput: string;

  /** Scroll position for restoration */
  scrollPosition: number;

  /** Loading state */
  isLoading: boolean;

  /** Display name for tab */
  displayName: string;

  /** Whether tab has unsaved changes */
  isDirty: boolean;

  /** Selected agent for this tab */
  selectedAgent: AgentProvider | null;

  /** Skip permissions setting */
  skipPermissions: boolean;
}

/**
 * Create an empty tab state.
 */
export function createEmptyTabState(tabId?: string): ChatTabState {
  return {
    tabId: tabId || generateTabId(),
    session: null,
    messages: [],
    streamingContent: '',
    isStreaming: false,
    userInput: '',
    scrollPosition: 0,
    isLoading: false,
    displayName: 'New Chat',
    isDirty: false,
    selectedAgent: null,
    skipPermissions: true
  };
}

/**
 * Generate a unique tab ID.
 */
export function generateTabId(): string {
  return `tab-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Generate a unique message ID.
 */
export function generateMessageId(): string {
  return `msg-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Generate a unique session ID.
 */
export function generateSessionId(): string {
  return `session-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
}

/**
 * Create a new user message.
 */
export function createUserMessage(sessionId: string, content: string): LocalAgentMessage {
  return {
    id: generateMessageId(),
    sessionId,
    role: 'USER',
    content,
    timestamp: new Date().toISOString(),
    streaming: false
  };
}

/**
 * Create a new assistant message (streaming placeholder).
 */
export function createAssistantMessage(sessionId: string, agent: AgentProvider): LocalAgentMessage {
  return {
    id: generateMessageId(),
    sessionId,
    role: 'ASSISTANT',
    content: '',
    timestamp: new Date().toISOString(),
    agent,
    streaming: true
  };
}

/**
 * Create a new session.
 */
export function createNewSession(name?: string, agent?: AgentProvider): LocalAgentSession {
  const id = generateSessionId();
  return {
    id,
    name: name || `Chat ${new Date().toLocaleDateString()}`,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    activeAgent: agent,
    messages: [],
    archived: false,
    totalTokens: 0,
    messageCount: 0
  };
}

/**
 * SSE Event types for streaming.
 */
export interface SseChunkEvent {
  type: 'chunk';
  data: string;
}

export interface SseToolUseEvent {
  type: 'tool_use';
  data: ToolUseEvent;
}

export interface SseResultEvent {
  type: 'result';
  data: ResultEvent;
}

export interface SseFilesModifiedEvent {
  type: 'files_modified';
  data: string[];
}

export interface SseCompleteEvent {
  type: 'complete';
  data: LocalAgentMessage;
}

export interface SseErrorEvent {
  type: 'error';
  data: string;
}

export type SseEvent =
  | SseChunkEvent
  | SseToolUseEvent
  | SseResultEvent
  | SseFilesModifiedEvent
  | SseCompleteEvent
  | SseErrorEvent;

/**
 * Chat request for local agent.
 */
export interface LocalAgentChatRequest {
  /** Message to send */
  message: string;

  /** Agent to use */
  agentName?: string;

  /** Skip permissions */
  skipPermissions?: boolean;

  /** Working directory */
  workingDirectory?: string;

  /** Include chat history */
  includeHistory?: boolean;

  /** Chat history to include */
  chatHistory?: ChatHistoryEntry[];

  /** Session metadata */
  metadata?: { [key: string]: any };

  // RAG Configuration
  /** Enable RAG document retrieval */
  enableRag?: boolean;

  /** Maximum documents to retrieve for RAG */
  ragMaxResults?: number;

  /** Similarity threshold for RAG (0.0 - 1.0) */
  ragSimilarityThreshold?: number;

  /** Include keyword/BM25 search */
  includeKeywordSearch?: boolean;

  /** Include semantic/vector search */
  includeSemanticSearch?: boolean;

  /** Folder ID for context injection (file paths injected into prompt) */
  folderId?: string;

  /** Timeout in seconds (0 = no timeout, default 300 = 5 minutes) */
  timeoutSeconds?: number;
}

/**
 * Simplified history entry for context.
 */
export interface ChatHistoryEntry {
  role: MessageRole;
  content: string;
}

/**
 * Storage keys for local persistence.
 */
export const STORAGE_KEYS = {
  SESSIONS: 'kompile_agent_sessions',
  ACTIVE_SESSION: 'kompile_agent_active_session',
  TABS: 'kompile_agent_tabs',
  ACTIVE_TAB: 'kompile_agent_active_tab',
  SETTINGS: 'kompile_agent_settings'
} as const;

/**
 * Agent chat settings for persistence.
 */
export interface AgentChatSettings {
  /** Default agent name */
  defaultAgentName?: string;

  /** Default skip permissions */
  defaultSkipPermissions: boolean;

  /** Default working directory */
  defaultWorkingDirectory?: string;

  /** Max history messages to include */
  maxHistoryMessages: number;

  /** Auto-scroll on new messages */
  autoScroll: boolean;

  /** Show timestamps */
  showTimestamps: boolean;

  /** Show token counts */
  showTokenCounts: boolean;

  /** Theme preference */
  theme: 'light' | 'dark' | 'system';

  // RAG Settings
  /** Enable RAG by default */
  enableRag: boolean;

  /** Default max RAG results */
  ragMaxResults: number;

  /** Default RAG similarity threshold */
  ragSimilarityThreshold: number;

  /** Include keyword search in RAG */
  includeKeywordSearch: boolean;

  /** Include semantic search in RAG */
  includeSemanticSearch: boolean;
}

/**
 * Default agent chat settings.
 */
export const DEFAULT_AGENT_CHAT_SETTINGS: AgentChatSettings = {
  defaultSkipPermissions: true,
  maxHistoryMessages: 20,
  autoScroll: true,
  showTimestamps: true,
  showTokenCounts: false,
  theme: 'system',
  // RAG defaults
  enableRag: false,
  ragMaxResults: 5,
  ragSimilarityThreshold: 0.0,
  includeKeywordSearch: true,
  includeSemanticSearch: true
};

// ═══════════════════════════════════════════════════════════════════════════════
// PROMPT TEMPLATE MANAGEMENT
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Template variable definition.
 */
export interface TemplateVariable {
  /** Variable name (used in {{name}} syntax) */
  name: string;

  /** Human-readable display name */
  displayName?: string;

  /** Description of what this variable is for */
  description?: string;

  /** Type of the variable */
  type?: string;

  /** Whether this variable is required */
  required?: boolean;

  /** Default value if not provided */
  defaultValue?: string;

  /** Example value for documentation */
  exampleValue?: string;

  /** Allowed values for enum-like variables */
  allowedValues?: string[];

  /** Validation pattern (regex) */
  validationPattern?: string;

  /** Minimum length for string variables */
  minLength?: number;

  /** Maximum length for string variables */
  maxLength?: number;
}

/**
 * Template usage example.
 */
export interface TemplateExample {
  /** Title of the example */
  title?: string;

  /** Description of what this example demonstrates */
  description?: string;

  /** Input values for the variables */
  inputs?: { [key: string]: any };

  /** The rendered output for this example */
  renderedOutput?: string;

  /** Expected response (for documentation) */
  expectedResponse?: string;
}

/**
 * Prompt template definition.
 */
export interface PromptTemplate {
  /** Unique identifier */
  id?: string;

  /** Unique name for the template */
  name: string;

  /** Human-readable display name */
  displayName?: string;

  /** Short description of what the template does */
  description?: string;

  /** Category for organization */
  category?: string;

  /** Template content with variable placeholders {{variableName}} */
  content: string;

  /** System prompt (optional) */
  systemPrompt?: string;

  /** Defined variables */
  variables?: TemplateVariable[];

  /** Usage examples */
  examples?: TemplateExample[];

  /** Tags for searchability */
  tags?: string[];

  /** Whether this template is enabled */
  enabled?: boolean;

  /** Whether this is a built-in system template */
  builtIn?: boolean;

  /** Template version */
  version?: string;

  /** Creation timestamp */
  createdAt?: string;

  /** Last update timestamp */
  updatedAt?: string;

  /** Creator */
  createdBy?: string;

  /** Output format hint (e.g., "json", "markdown", "text") */
  outputFormat?: string;

  /** Recommended model for this template */
  recommendedModel?: string;

  /** Maximum tokens recommendation */
  maxTokens?: number;

  /** Temperature recommendation */
  temperature?: number;
}

/**
 * Template category information.
 */
export interface TemplateCategoryInfo {
  displayName: string;
  description: string;
  keywords: string[];
}

/**
 * Templates summary for quick overview.
 */
export interface TemplatesSummary {
  totalTemplates: number;
  enabledTemplates: number;
  builtInTemplates: number;
  customTemplates: number;
  categoryCount: number;
  categories: string[];
}

/**
 * Rendered template result.
 */
export interface RenderedTemplate {
  templateName: string;
  content: string;
  systemPrompt?: string;
  outputFormat?: string;
  recommendedModel?: string;
  maxTokens?: number;
  temperature?: number;
}

/**
 * Template category display information.
 */
export const TEMPLATE_CATEGORIES: { [key: string]: TemplateCategoryInfo } = {
  'rag': {
    displayName: 'RAG & Retrieval',
    description: 'Templates for retrieval-augmented generation and document Q&A',
    keywords: ['retrieval', 'search', 'document', 'qa']
  },
  'summarization': {
    displayName: 'Summarization',
    description: 'Templates for summarizing text, documents, and conversations',
    keywords: ['summary', 'condense', 'brief']
  },
  'code': {
    displayName: 'Code Generation',
    description: 'Templates for generating, reviewing, and explaining code',
    keywords: ['coding', 'programming', 'development']
  },
  'analysis': {
    displayName: 'Analysis',
    description: 'Templates for analyzing data, text, and patterns',
    keywords: ['analyze', 'examine', 'review']
  },
  'creative': {
    displayName: 'Creative Writing',
    description: 'Templates for creative content generation',
    keywords: ['writing', 'story', 'creative']
  },
  'extraction': {
    displayName: 'Data Extraction',
    description: 'Templates for extracting structured data from text',
    keywords: ['extract', 'parse', 'structure']
  },
  'classification': {
    displayName: 'Classification',
    description: 'Templates for categorizing and classifying content',
    keywords: ['classify', 'categorize', 'label']
  },
  'translation': {
    displayName: 'Translation',
    description: 'Templates for language translation and localization',
    keywords: ['translate', 'language', 'localize']
  },
  'conversation': {
    displayName: 'Conversation',
    description: 'Templates for chat and conversational interactions',
    keywords: ['chat', 'dialog', 'conversation']
  },
  'system': {
    displayName: 'System Prompts',
    description: 'System prompts for configuring agent behavior',
    keywords: ['system', 'persona', 'behavior']
  },
  'custom': {
    displayName: 'Custom',
    description: 'User-defined custom templates',
    keywords: []
  }
};

// ═══════════════════════════════════════════════════════════════════════════════
// INGEST EVENT LOG MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Event types for ingest event log.
 */
export type IngestEventType =
  | 'QUEUED'
  | 'PHASE_STARTED'
  | 'PROGRESS'
  | 'PHASE_COMPLETED'
  | 'STATE_TRANSITION'
  | 'WARNING'
  | 'ERROR'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

/**
 * Ingest event log entry.
 */
export interface IngestEvent {
  /** Unique event ID */
  id?: number;

  /** Task ID this event belongs to */
  taskId: string;

  /** File name being processed */
  fileName: string;

  /** Event type */
  eventType: IngestEventType;

  /** Current phase (if applicable) */
  phase?: IngestPhase;

  /** Previous phase (for state transitions) */
  previousPhase?: IngestPhase;

  /** Event message/description */
  message: string;

  /** Additional details (JSON) */
  details?: string;

  /** Error message (for errors/failures) */
  errorMessage?: string;

  /** Stack trace (for errors) */
  stackTrace?: string;

  /** Progress percentage (0-100) */
  progressPercent?: number;

  /** Duration in milliseconds (for phase completions) */
  durationMs?: number;

  /** Items processed count */
  itemsProcessed?: number;

  /** Total items to process */
  totalItems?: number;

  /** Memory usage percentage at event time */
  memoryUsagePercent?: number;

  /** Event timestamp */
  timestamp: string;

  /** ND4J environment configuration snapshot captured at job start (JSON) */
  nd4jEnvironmentSnapshot?: string;
}

/**
 * ND4J environment configuration captured at job start.
 * Used to reproduce environment-specific issues.
 */
export interface Nd4jEnvironmentConfig {
  maxThreads?: number;
  maxMasterThreads?: number;
  debug?: boolean;
  verbose?: boolean;
  profiling?: boolean;
  enableBlas?: boolean;
  helpersAllowed?: boolean;
  leaksDetector?: boolean;
  tadThreshold?: number;
  elementwiseThreshold?: number;
  maxPrimaryMemory?: number;
  maxSpecialMemory?: number;
  maxDeviceMemory?: number;
  lifecycleTracking?: boolean;
  trackViews?: boolean;
  trackDeletions?: boolean;
  snapshotFiles?: boolean;
  trackOperations?: boolean;
  stackDepth?: number;
  reportInterval?: number;
  maxDeletionHistory?: number;
  ndArrayTracking?: boolean;
  dataBufferTracking?: boolean;
  tadCacheTracking?: boolean;
  shapeCacheTracking?: boolean;
  opContextTracking?: boolean;
  funcTracePrintAllocate?: boolean;
  funcTracePrintDeallocate?: boolean;
  funcTracePrintJavaOnly?: boolean;
  logNativeNDArrayCreation?: boolean;
  logNDArrayEvents?: boolean;
  checkInputChange?: boolean;
  checkOutputChange?: boolean;
  trackWorkspaceOpenClose?: boolean;
  deleteShapeInfo?: boolean;
  deletePrimary?: boolean;
  deleteSpecial?: boolean;
  variableTracingEnabled?: boolean;
  javacppLoggerDebug?: boolean;
  javacppPathsFirst?: boolean;
}

/**
 * Response for task environment snapshot endpoint.
 */
export interface TaskEnvironmentResponse {
  taskId: string;
  fileName: string;
  timestamp: string;
  environmentCaptured: boolean;
  nd4jEnvironment?: Nd4jEnvironmentConfig;
  nd4jEnvironmentRaw?: string;
  message?: string;
  parseError?: string;
}

/**
 * Response wrapper for events by task.
 */
export interface TaskEventsResponse {
  taskId: string;
  eventCount: number;
  events: IngestEvent[];
}

/**
 * Response wrapper for recent events.
 */
export interface RecentEventsResponse {
  lookbackHours: number;
  eventCount: number;
  events: IngestEvent[];
}

/**
 * Response wrapper for error events.
 */
export interface ErrorEventsResponse {
  lookbackHours: number;
  errorCount: number;
  errors: IngestEvent[];
}

/**
 * Response wrapper for task IDs list.
 */
export interface TaskIdsResponse {
  lookbackHours: number;
  taskCount: number;
  tasks: string[];
}

/**
 * Event log status response.
 */
export interface EventLogStatusResponse {
  enabled: boolean;
  totalEvents?: number;
  message?: string;
}

/**
 * Event log summary statistics.
 */
export interface EventLogSummary {
  lookbackHours: number;
  totalTasks: number;
  completed: number;
  failed: number;
  cancelled: number;
  errorCount: number;
  averageDurationMs: number;
  totalEventsInDb: number;
}

/**
 * Cleanup response.
 */
export interface CleanupResponse {
  olderThanDays: number;
  deletedCount: number;
}

/**
 * Delete task events response.
 */
export interface DeleteTaskEventsResponse {
  taskId: string;
  deleted: boolean;
}

/**
 * Event severity for UI styling.
 */
export type EventSeverity = 'info' | 'success' | 'warning' | 'error';

/**
 * Get severity level for an event type.
 */
export function getEventSeverity(eventType: IngestEventType): EventSeverity {
  switch (eventType) {
    case 'COMPLETED':
    case 'PHASE_COMPLETED':
      return 'success';
    case 'WARNING':
      return 'warning';
    case 'ERROR':
    case 'FAILED':
    case 'CANCELLED':
      return 'error';
    default:
      return 'info';
  }
}

/**
 * Get display name for event type.
 */
export function getEventTypeDisplayName(eventType: IngestEventType): string {
  const displayNames: { [key in IngestEventType]: string } = {
    'QUEUED': 'Queued',
    'PHASE_STARTED': 'Phase Started',
    'PROGRESS': 'Progress',
    'PHASE_COMPLETED': 'Phase Completed',
    'STATE_TRANSITION': 'State Transition',
    'WARNING': 'Warning',
    'ERROR': 'Error',
    'COMPLETED': 'Completed',
    'FAILED': 'Failed',
    'CANCELLED': 'Cancelled'
  };
  return displayNames[eventType] || eventType;
}

/**
 * Get icon for event type.
 */
export function getEventTypeIcon(eventType: IngestEventType): string {
  const icons: { [key in IngestEventType]: string } = {
    'QUEUED': 'schedule',
    'PHASE_STARTED': 'play_arrow',
    'PROGRESS': 'trending_up',
    'PHASE_COMPLETED': 'check_circle',
    'STATE_TRANSITION': 'swap_horiz',
    'WARNING': 'warning',
    'ERROR': 'error',
    'COMPLETED': 'done_all',
    'FAILED': 'cancel',
    'CANCELLED': 'block'
  };
  return icons[eventType] || 'info';
}

/**
 * Get display name for ingest phase.
 */
export function getPhaseDisplayName(phase: IngestPhase): string {
  const displayNames: { [key in IngestPhase]: string } = {
    'QUEUED': 'Queued',
    'UPLOADING': 'Uploading',
    'LOADING': 'Loading',
    'CONVERTING': 'Converting',
    'CHUNKING': 'Chunking',
    'EMBEDDING': 'Embedding',
    'INDEXING': 'Indexing',
    'COMPLETED': 'Completed',
    'FAILED': 'Failed'
  };
  return displayNames[phase] || phase;
}

/**
 * Get icon for ingest phase.
 */
export function getPhaseIcon(phase: IngestPhase): string {
  const icons: { [key in IngestPhase]: string } = {
    'QUEUED': 'schedule',
    'UPLOADING': 'cloud_upload',
    'LOADING': 'folder_open',
    'CONVERTING': 'transform',
    'CHUNKING': 'content_cut',
    'EMBEDDING': 'memory',
    'INDEXING': 'storage',
    'COMPLETED': 'check_circle',
    'FAILED': 'error'
  };
  return icons[phase] || 'info';
}

// ═══════════════════════════════════════════════════════════════════════════════
// STAGED PIPELINE CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Pipeline preset identifiers.
 */
export type PipelinePreset = 'adaptive' | 'memoryOptimized' | 'highThroughput' | 'custom';

/**
 * Extraction stage settings.
 */
export interface ExtractionSettings {
  preferredLoader?: string;
  autoDetectLoader: boolean;
  threads: number;
}

/**
 * Tokenization stage settings.
 */
export interface TokenizationSettings {
  enabled: boolean;
  model?: string;
  maxTokenLength: number;
  threads: number;
}

/**
 * Chunking stage settings.
 */
export interface ChunkingSettings {
  type?: string;
  chunkSize: number;
  chunkOverlap: number;
  preserveParagraphs: boolean;
  threads: number;
}

/**
 * Embedding stage settings.
 */
export interface EmbeddingSettings {
  batchSize: number;
  threads: number;
}

/**
 * Indexing stage settings.
 */
export interface IndexingSettings {
  batchSize: number;
  threads: number;
}

/**
 * Queue configuration.
 */
export interface QueueSettings {
  capacity: number;
  backpressureEnabled: boolean;
}

/**
 * System resource information.
 */
export interface SystemResourceInfo {
  availableCores: number;
  maxMemoryMB: number;
  usedMemoryMB: number;
}

// ═══════════════════════════════════════════════════════════════════════════════
// SYSTEM RESOURCES WEBSOCKET RESPONSE
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * CPU information from system resource monitoring.
 */
export interface CpuInfo {
  availableProcessors: number;
  arch: string;
  name?: string;
  version?: string;
  systemLoadAverage: number | string;
  systemCpuLoad: number | string;
  processCpuLoad: number | string;
  processCpuTime?: number;
  recentCpuUsage?: number;
}

/**
 * Memory pool information.
 */
export interface MemoryPool {
  name: string;
  type: string;
  usedMB: number;
  committedMB: number;
  maxMB?: number;
}

/**
 * Memory information from system resource monitoring.
 */
export interface MemoryInfo {
  heap: {
    usedMB: number;
    committedMB: number;
    maxMB: number;
    usagePercent: number;
  };
  nonHeap: {
    usedMB: number;
    committedMB: number;
    maxMB?: number;
  };
  jvm: {
    totalMB: number;
    freeMB: number;
    usedMB: number;
    maxMB: number;
  };
  system?: {
    totalMB: number;
    freeMB: number;
    usedMB: number;
    usagePercent: number;
    swapTotalMB?: number;
    swapFreeMB?: number;
    swapUsedMB?: number;
  };
  pools: MemoryPool[];
}

/**
 * Thread information from system resource monitoring.
 */
export interface ThreadInfo {
  threadCount: number;
  peakThreadCount: number;
  daemonThreadCount: number;
  totalStartedThreadCount: number;
  states: { [key: string]: number };
  deadlockedThreads: number;
  topCpuThreads?: Array<{
    id: number;
    name: string;
    state: string;
    cpuTimeMs: number;
  }>;
}

/**
 * Garbage collector information.
 */
export interface GarbageCollector {
  name: string;
  collectionCount: number;
  collectionTimeMs: number;
  memoryPoolNames?: string[];
}

/**
 * Process information from system resource monitoring.
 */
export interface ProcessInfo {
  pid: number;
  command?: string;
  user?: string;
  startTime?: string;
  uptimeSeconds?: number;
  totalCpuSeconds?: number;
  jvmUptimeMs: number;
  jvmStartTime?: string;
  vmName: string;
  vmVendor?: string;
  vmVersion: string;
  inputArguments?: string[];
  classLoading?: {
    loadedClassCount: number;
    totalLoadedClassCount: number;
    unloadedClassCount?: number;
  };
  compilation?: {
    name: string;
    totalCompilationTimeMs?: number;
  };
  garbageCollectors: GarbageCollector[];
}

/**
 * Disk partition information.
 */
export interface DiskPartition {
  path: string;
  totalGB: string;
  freeGB: string;
  usableGB: string;
  usagePercent: number;
}

/**
 * Disk information from system resource monitoring.
 */
export interface DiskInfo {
  partitions: DiskPartition[];
  currentDirectory: string;
  currentDirectoryFreeGB: string;
}

/**
 * ND4J device memory information.
 */
export interface Nd4jDeviceMemory {
  deviceId: number;
  current: boolean;
  name?: string;
  totalMemoryMB?: number;
  freeMemoryMB?: number;
  usedMemoryMB?: number;
  usagePercent?: number;
  computeCapability?: string;
  error?: string;
}

/**
 * ND4J resource information from system resource monitoring.
 */
export interface Nd4jResourceInfo {
  backend: string;
  dataType: string;
  isGpuBackend?: boolean;
  numberOfDevices: number;
  currentDevice: number;
  currentDeviceFreeMemoryMB?: number;
  devices?: Nd4jDeviceMemory[];
  environment: { [key: string]: any };
  cache: {
    shape: { entries: number; bytesMB: string; peakEntries?: number; peakBytesMB?: string };
    tad: { entries: number; bytesMB: string; peakEntries?: number; peakBytesMB?: string };
  };
  currentWorkspace?: {
    id: string;
    currentSizeMB: number;
    spilledSizeMB?: number;
    pinnedSizeMB?: number;
    generation?: number;
  };
  gpu?: { [key: string]: any };
}

/**
 * System properties information.
 */
export interface SystemPropertiesInfo {
  osName: string;
  osArch: string;
  osVersion: string;
  javaVersion: string;
  javaVendor: string;
  javaHome?: string;
  userDir?: string;
  userHome?: string;
  userName?: string;
  tempDir?: string;
  fileEncoding?: string;
  environmentVariables?: { [key: string]: string };
}

/**
 * Device information from system resource monitoring.
 */
export interface DeviceInfo {
  id: number;
  type: string;
  name?: string;
  cores?: number;
  backend?: string;
  available: boolean;
  current?: boolean;
  totalMemoryMB?: number;
  freeMemoryMB?: number;
  usedMemoryMB?: number;
  memoryUsagePercent?: number;
  computeCapability?: string;
  workspaceMemory?: number;
}

/**
 * Complete system resources response from WebSocket or REST API.
 */
export interface SystemResourcesResponse {
  status: string;
  timestamp: number;
  timestampIso: string;
  cpu: CpuInfo;
  memory: MemoryInfo;
  nd4j: Nd4jResourceInfo;
  threads: ThreadInfo;
  process: ProcessInfo;
  disk: DiskInfo;
  system: SystemPropertiesInfo;
}

/**
 * Devices response from system resource monitoring.
 */
export interface DevicesResponse {
  status: string;
  devices: DeviceInfo[];
  deviceCount: number;
  nd4jDeviceCount?: number;
  currentDevice?: number;
  backend?: string;
  isGpuBackend?: boolean;
}

/**
 * Complete pipeline configuration.
 */
export interface PipelineConfig {
  extraction: ExtractionSettings;
  tokenization: TokenizationSettings;
  chunking: ChunkingSettings;
  embedding: EmbeddingSettings;
  indexing: IndexingSettings;
  queues: QueueSettings;
  system: SystemResourceInfo;
}

/**
 * Pipeline preset definition.
 */
export interface PipelinePresetInfo {
  name: string;
  description: string;
  extractionThreads: number;
  tokenizationThreads: number;
  chunkingThreads: number;
  embeddingThreads: number;
  embeddingBatchSize: number;
  indexBatchSize: number;
}

/**
 * Available pipeline presets.
 */
export interface PipelinePresets {
  adaptive: PipelinePresetInfo;
  memoryOptimized: PipelinePresetInfo;
  highThroughput: PipelinePresetInfo;
}

/**
 * Stage metrics for monitoring.
 */
export interface StageMetrics {
  itemsProcessed: number;
  itemsFailed: number;
  throughput: number;
  avgProcessingTimeMs: number;
  queueSize: number;
}

/**
 * Complete stage metrics response.
 */
export interface StageMetricsResponse {
  status: 'idle' | 'running' | 'complete' | 'error';
  message?: string;
  extraction: StageMetrics;
  tokenization: StageMetrics;
  chunking: StageMetrics;
  embedding: StageMetrics;
  indexing: StageMetrics;
}

/**
 * Pipeline settings request for custom configuration.
 */
export interface PipelineSettingsRequest {
  preset?: PipelinePreset;
  extraction?: Partial<ExtractionSettings>;
  tokenization?: Partial<TokenizationSettings>;
  chunking?: Partial<ChunkingSettings>;
  embedding?: Partial<EmbeddingSettings>;
  indexing?: Partial<IndexingSettings>;
  queues?: Partial<QueueSettings>;
}

/**
 * Default pipeline settings (adaptive).
 */
export const DEFAULT_PIPELINE_SETTINGS: PipelineSettingsRequest = {
  preset: 'adaptive'
};

/**
 * Loader options for UI display.
 */
export interface LoaderOption {
  name: string;
  displayName: string;
  description?: string;
  supportedExtensions: string[];
  icon?: string;
}

/**
 * Tokenizer model options.
 */
export interface TokenizerOption {
  id: string;
  name: string;
  description?: string;
  maxLength: number;
}

/**
 * Available tokenizer models.
 */
export const AVAILABLE_TOKENIZERS: TokenizerOption[] = [
  { id: 'default', name: 'Default', description: 'Simple whitespace tokenizer', maxLength: 512 },
  { id: 'bert-base-uncased', name: 'BERT Base', description: 'BERT tokenizer (uncased)', maxLength: 512 },
  { id: 'bert-large-uncased', name: 'BERT Large', description: 'BERT Large tokenizer (uncased)', maxLength: 512 }
];

// ═══════════════════════════════════════════════════════════════════════════════
// FOLDER MANAGEMENT FOR CHAT SESSIONS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * File stored in a folder.
 */
export interface FolderFile {
  /** Unique file identifier */
  fileId: string;

  /** Original file name */
  fileName: string;

  /** Path where file is stored on disk */
  storedPath: string;

  /** File size in bytes */
  fileSize: number;

  /** MIME type */
  mimeType?: string;

  /** Upload timestamp */
  uploadedAt: string;
}

/**
 * Folder for organizing chat sessions and reference files.
 */
export interface ChatFolder {
  /** Unique folder identifier */
  folderId: string;

  /** Folder name */
  name: string;

  /** Folder description */
  description?: string;

  /** User who owns the folder */
  userId?: string;

  /** Creation timestamp */
  createdAt: string;

  /** Last update timestamp */
  updatedAt: string;

  /** Number of files in folder */
  fileCount: number;

  /** Number of chat sessions associated with this folder */
  sessionCount?: number;

  /** Files in the folder (populated when fetching single folder) */
  files?: FolderFile[];
}

/**
 * Request to create a new folder.
 */
export interface CreateFolderRequest {
  /** Folder name */
  name: string;

  /** Optional description */
  description?: string;

  /** Optional user ID */
  userId?: string;
}

/**
 * Request to update an existing folder.
 */
export interface UpdateFolderRequest {
  /** New folder name */
  name?: string;

  /** New description */
  description?: string;
}

/**
 * Folder context for prompt injection.
 */
export interface FolderContext {
  /** Folder ID */
  folderId: string;

  /** Folder name */
  name: string;

  /** List of file paths available in the folder */
  filePaths: string[];

  /** Pre-formatted context prompt for injection */
  contextPrompt?: string;
}

/**
 * Response for batch file upload.
 */
export interface BatchFileUploadResponse {
  /** Number of successfully uploaded files */
  successCount: number;

  /** Number of failed uploads */
  failedCount: number;

  /** List of uploaded files */
  files: FolderFile[];

  /** List of error messages for failed uploads */
  errors: string[];
}

/**
 * Chat session DTO for folder associations.
 */
export interface ChatSessionDto {
  /** Session ID */
  sessionId: string;

  /** Session title */
  title?: string;

  /** Session description */
  description?: string;

  /** Number of messages in the session */
  messageCount?: number;

  /** Creation timestamp */
  createdAt: string;

  /** Last update timestamp */
  updatedAt: string;

  /** Associated folder ID */
  folderId?: string;
}

/**
 * Log entry from subprocess execution.
 * Streamed via WebSocket to /topic/ingest/{taskId}/logs
 */
export interface IngestLogEntry {
  /** Task ID this log belongs to */
  taskId: string;

  /** Log level: DEBUG, INFO, WARN, ERROR */
  level: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

  /** Source of the log: STDOUT, STDERR, SYSTEM */
  source: 'STDOUT' | 'STDERR' | 'SYSTEM';

  /** Log message content */
  message: string;

  /** Logger name (e.g., class name) */
  logger?: string;

  /** Timestamp of the log entry */
  timestamp: string;

  /** Sequence number for ordering */
  sequenceNumber: number;
}

/**
 * Processing mode information for document ingestion.
 */
export interface ProcessingModeInfo {
  /** The mode value to send to the backend */
  value: string;

  /** Human-readable label for display */
  label: string;

  /** Description of what this mode does */
  description: string;
}

/**
 * Processing mode type for easier type checking
 */
export type ProcessingMode = 'auto' | 'subprocess' | 'inprocess';
