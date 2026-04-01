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
  loaderName?: string;
  chunkerName?: string;
  rebuildIndex?: boolean;
}

export interface AddPathRequest {
  path: string;
  loader?: string;
  chunkerName?: string;
}

// YouTube transcript request/response models
export interface AddYouTubeRequest {
  url: string;
  language?: string;
  chunkerName?: string;
  saveTranscriptFile?: boolean;
  rebuildIndex?: boolean;
}

export interface YouTubeTranscriptResponse {
  message: string;
  videoId: string;
  videoTitle: string;
  language: string;
  transcriptLength: number;
  segmentCount: number;
  metadata?: { [key: string]: any };
  savedToFile?: string;
  filePath?: string;
  taskId?: string;
  processingStarted?: boolean;
  processingNote?: string;
  error?: string;
  details?: string;
}

// Text/Clipboard content request/response models
export interface AddTextRequest {
  content: string;
  sourceName?: string;
  chunkerName?: string;
  processingMode?: ProcessingMode;
  rebuildIndex?: boolean;
}

export interface AddTextResponse {
  message: string;
  sourceName: string;
  contentLength: number;
  wordCount: number;
  taskId?: string;
  processingStarted?: boolean;
  filePath?: string;
  error?: string;
}

// Discord channel/server request/response models
export interface AddDiscordRequest {
  serverId: string;
  channelId?: string;
  botToken: string;
  messageLimit?: number;
  includeThreads?: boolean;
  chunkerName?: string;
  saveMessagesFile?: boolean;
}

export interface DiscordResponse {
  message: string;
  serverId: string;
  serverName?: string;
  channelId?: string;
  channelName?: string;
  messageCount: number;
  userCount?: number;
  metadata?: { [key: string]: any };
  savedToFile?: string;
  filePath?: string;
  taskId?: string;
  processingStarted?: boolean;
  processingNote?: string;
  error?: string;
  details?: string;
}

// Slack channel/workspace request/response models
export interface AddSlackRequest {
  channelId: string;         // Slack channel ID or name
  token?: string;            // Optional: Slack Bot OAuth token (if not in config)
  messageLimit?: number;     // Maximum messages to fetch
  includeThreads?: boolean;  // Whether to include thread replies
  chunkerName?: string;      // Chunker to use for processing
}

export interface AddSlackHistoryRequest {
  channelId: string;         // Slack channel ID, name, or comma-separated list
  token?: string;            // Optional: Slack Bot OAuth token (if not in config)
  startDate?: string;        // Start date (ISO format) or days back
  endDate?: string;          // End date (ISO format), defaults to now
  daysBack?: number;         // Alternative to startDate: number of days to look back
  maxMessages?: number;      // Maximum messages to fetch (0 = unlimited)
  includeThreads?: boolean;  // Whether to include thread replies
  loadAllChannels?: boolean; // Whether to load from all accessible channels
  chunkerName?: string;      // Chunker to use for processing
}

export interface SlackResponse {
  message: string;
  channelId: string;
  channelName?: string;
  messageCount: number;
  threadCount?: number;
  userCount?: number;
  startDate?: string;
  endDate?: string;
  metadata?: { [key: string]: any };
  taskId?: string;
  processingStarted?: boolean;
  processingNote?: string;
  error?: string;
  details?: string;
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
  similarityThreshold?: number;  // For vector search - minimum cosine similarity (0.0 to 1.0)
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
  // Total document count in the keyword index (for keyword search)
  totalDocumentCount?: number;
  // Total vector count in the vector store (for vector search)
  totalVectorCount?: number;
}

// New interface for Index Browser Status
export interface IndexBrowserStatus {
  // Keyword Index fields
  indexerImplementation: string;
  indexerFullClassName: string;
  retrieverImplementation: string;
  retrieverFullClassName: string;
  indexAvailable: boolean;
  approximateDocumentCount: number | string;
  isNoOpIndexer: boolean;
  isNoOpRetriever: boolean;
  indexPath?: string;
  warning?: string;

  // Vector Store fields
  vectorStoreImplementation?: string;
  vectorStoreFullClassName?: string;
  vectorStoreAvailable?: boolean;
  vectorStorePath?: string;
  approximateVectorCount?: number | string;
  approximateTotalTokens?: number;  // Total tokens across all indexed documents
  isNoOpVectorStore?: boolean;
  isUsingFallbackIndex?: boolean;

  // Active Model Status (from Staging Manager)
  activeEmbeddingModel?: string;       // Currently loaded embedding model identifier
  embeddingModelName?: string;         // Display name of the embedding model
  embeddingDimensions?: number;        // Embedding vector dimensions
  embeddingModelInitialized?: boolean; // Whether the embedding model is ready for use
  embeddingModelLoading?: boolean;     // Whether the model is currently loading
  embeddingModelLoadingPhase?: string; // Current loading phase (IDLE, STARTING, LOOKING_UP_REGISTRY, etc.)
  embeddingModelLoadingMessage?: string; // Human-readable loading progress message
  embeddingModelWarning?: string;      // Warning message if model not initialized
  embeddingModelError?: string;        // Error details if initialization failed

  // Staging Service Connection
  stagingServiceConfigured?: boolean;  // Whether a staging service is configured
  stagingServiceName?: string;         // Name of the configured staging service
  stagingServiceUrl?: string;          // URL of the staging service
  stagingServiceVerified?: boolean;    // Whether the staging service connection is verified
  stagingConnected?: boolean;          // Whether currently connected to staging service

  // Active Models from Staging (type -> modelId)
  activeModels?: { [type: string]: string };
  activeEncoder?: string;              // Active dense encoder model ID
  activeCrossEncoder?: string;         // Active cross-encoder model ID
  activeSparseEncoder?: string;        // Active sparse encoder model ID
}

// Vector Indexing Job Status
export enum JobState {
  IDLE = 'IDLE',
  RUNNING = 'RUNNING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED'
}

export interface JobStatus {
  state: JobState;
  message: string;
  percentComplete: number;
  documentsProcessed: number;
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
  | 'table-aware'
  | 'boundary-aware'
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

  // Boundary preservation options (for boundary-aware chunker)

  /** Preserve URLs - prevent splitting URLs across chunks */
  preserveUrls?: boolean;

  /** Preserve email addresses - prevent splitting emails across chunks */
  preserveEmails?: boolean;

  /** Preserve file paths - prevent splitting file paths across chunks */
  preserveFilePaths?: boolean;

  /** Preserve IP addresses - prevent splitting IP addresses across chunks */
  preserveIpAddresses?: boolean;

  /** Preserve phone numbers - prevent splitting phone numbers across chunks */
  preservePhoneNumbers?: boolean;

  /** Preserve quoted strings - prevent splitting quoted text across chunks */
  preserveQuotedStrings?: boolean;

  /** Preserve code identifiers (camelCase, snake_case) - prevent splitting identifiers */
  preserveCodeIdentifiers?: boolean;
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
  },
  {
    id: 'table-aware',
    name: 'Table-Aware',
    description: 'Preserves table structure (markdown, TSV, HTML) as atomic chunks',
    icon: 'table_chart',
    bestFor: 'Documents with tables'
  },
  {
    id: 'boundary-aware',
    name: 'Boundary-Aware',
    description: 'Prevents splitting URLs, emails, file paths, and other semantic units',
    icon: 'link',
    bestFor: 'Technical docs, code references'
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
  splitOnHeadings: true,
  // Boundary preservation defaults (enabled for common cases)
  preserveUrls: true,
  preserveEmails: true,
  preserveFilePaths: true,
  preserveIpAddresses: false,
  preservePhoneNumbers: false,
  preserveQuotedStrings: false,
  preserveCodeIdentifiers: false
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
  path?: string; // Server-side path
  sourceType?: 'file' | 'url' | 'path' | 'text' | 'youtube' | 'discord' | 'slack' | 'slack_history' | 'confluence';
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
  // Text/Clipboard source configuration
  textContent?: string; // Raw text content pasted by user
  textSourceName?: string; // Optional name for the text source
  // YouTube transcript configuration
  youtubeUrl?: string; // YouTube video URL or video ID
  youtubeLanguage?: string; // Preferred language code (e.g., 'en', 'es')
  saveTranscriptFile?: boolean; // Whether to save transcript to file for processing
  // Discord channel/server configuration
  discordServerId?: string; // Discord server (guild) ID
  discordChannelId?: string; // Optional specific channel ID (if empty, fetches all channels)
  discordBotToken?: string; // Discord bot token for authentication
  discordMessageLimit?: number; // Maximum messages to fetch per channel
  discordIncludeThreads?: boolean; // Whether to include thread messages
  saveDiscordMessages?: boolean; // Whether to save messages to file for processing
  // Slack channel/workspace configuration
  slackChannelId?: string; // Slack channel ID or name (supports comma-separated list)
  slackToken?: string; // Slack Bot OAuth token for authentication
  slackMessageLimit?: number; // Maximum messages to fetch per channel
  slackIncludeThreads?: boolean; // Whether to include thread replies
  slackStartDate?: string; // Start date for history (ISO format)
  slackEndDate?: string; // End date for history (ISO format)
  slackDaysBack?: number; // Alternative to startDate: days to look back
  slackLoadAllChannels?: boolean; // Whether to load from all accessible channels
  slackHistoryMode?: boolean; // Whether this is a history load (vs. current messages)
  // Confluence page/space configuration
  confluenceBaseUrl?: string; // Confluence instance base URL
  confluenceEmail?: string; // User email for authentication
  confluenceApiToken?: string; // API token for authentication
  confluenceSpaceKey?: string; // Confluence space key to ingest
  confluencePageIds?: string[]; // Specific page IDs to ingest
  confluenceIncludeChildren?: boolean; // Whether to include child pages
  confluenceIncludeAttachments?: boolean; // Whether to include page attachments
  confluenceMaxDepth?: number; // Maximum depth for child page traversal
  // Composite PDF loader option
  /**
   * When true, multiple PDF loaders will be tested and the one extracting
   * the most content will be used. Useful for handling scanned/image-based PDFs.
   */
  useCompositePdfLoader?: boolean;
  /**
   * PDF processing configuration for controlling text extraction, VLM, OCR, and table extraction.
   * When provided, these settings override defaults for PDF processing.
   */
  pdfProcessingConfig?: PdfProcessingConfig;
}

// ═══════════════════════════════════════════════════════════════════════════════
// PDF PROCESSING CONFIGURATION
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * PDF processing mode - determines how PDF content is extracted.
 */
export type PdfProcessingMode =
  | 'TEXT_EXTRACTION'  // Direct text extraction using PDFBox (fast, for text-based PDFs)
  | 'VLM'              // Vision-Language Model processing (for scanned/image PDFs)
  | 'TRADITIONAL_OCR'  // Traditional OCR (detection + recognition pipeline)
  | 'AUTO'             // Try text extraction first, use VLM if insufficient content
  | 'COMPARE';         // Compare multiple loaders and select best

/**
 * VLM output format options.
 */
export type VlmOutputFormat = 'DOCTAGS' | 'MARKDOWN' | 'FLORENCE2' | 'DONUT' | 'PLAIN_TEXT' | 'JSON' | 'TEXT';

/**
 * Table storage mode for extracted tables.
 */
export type TableStorageMode = 'INLINE' | 'SEPARATE' | 'BOTH' | 'NONE';

/**
 * Table extraction method options.
 */
export type TableExtractionMethod =
  | 'TABULA'  // Use Tabula (rule-based, fast, works on text PDFs)
  | 'VLM'     // Use VLM (AI-based, better for scanned/image PDFs)
  | 'AUTO'    // Try Tabula first, fall back to VLM if no tables found
  | 'NONE';   // Disable table extraction

/**
 * Configuration for PDF processing - replaces Spring @Value properties with runtime UI config.
 */
export interface PdfProcessingConfig {
  // Processing mode
  processingMode: PdfProcessingMode;

  // VLM configuration
  useVlm: boolean;
  vlmModelId?: string;
  vlmOutputFormat?: VlmOutputFormat;
  maxNewTokens?: number;
  temperature?: number;
  topP?: number;
  beamSize?: number;
  doSample?: boolean;

  // VLM component path overrides (optional - null means auto-detect from model directory)
  vlmDecoderPath?: string;
  vlmEncoderPath?: string;
  vlmEmbedTokensPath?: string;
  vlmTokenizerPath?: string;
  vlmPreprocessorConfigPath?: string;

  // Traditional OCR configuration
  detectionModelId?: string;
  recognitionModelId?: string;
  tableModelId?: string;
  layoutModelId?: string;

  // PDF rendering
  pdfRenderDpi?: number;

  // Table extraction
  extractTables: boolean;
  tableStorageMode?: TableStorageMode;
  tableExtractionMethod?: TableExtractionMethod;
  tableFormat?: string;
  minTableRows?: number;
  minTableCols?: number;

  // Text extraction settings
  extractByPage?: boolean;
  extractMetadata?: boolean;
  extractAnnotations?: boolean;
  extractFormFields?: boolean;
  extractBookmarks?: boolean;
  extractLinks?: boolean;

  // Post-processing
  enablePostProcessing?: boolean;
  enableLayoutAnalysis?: boolean;

  // Auto mode threshold
  autoModeMinCharacters?: number;

  // Composite loader setting
  useCompositeLoader?: boolean;
}

/**
 * Default PDF processing configuration.
 */
export const DEFAULT_PDF_PROCESSING_CONFIG: PdfProcessingConfig = {
  processingMode: 'AUTO',
  useVlm: false,
  vlmModelId: '',
  vlmOutputFormat: 'MARKDOWN',
  maxNewTokens: 4096,
  temperature: 0.0,
  topP: 1.0,
  beamSize: 1,
  doSample: false,
  pdfRenderDpi: 300,
  extractTables: true,
  tableStorageMode: 'BOTH',
  tableExtractionMethod: 'AUTO',
  tableFormat: 'markdown',
  minTableRows: 2,
  minTableCols: 2,
  extractByPage: false,
  extractMetadata: true,
  enablePostProcessing: false,
  enableLayoutAnalysis: false,
  autoModeMinCharacters: 100,
  useCompositeLoader: false
};

/**
 * PDF processing mode information for UI display.
 */
export interface PdfProcessingModeInfo {
  id: PdfProcessingMode;
  name: string;
  description: string;
  icon: string;
  bestFor: string;
}

/**
 * Available PDF processing modes for UI selection.
 */
export const PDF_PROCESSING_MODES: PdfProcessingModeInfo[] = [
  {
    id: 'AUTO',
    name: 'Auto Detect',
    description: 'Automatically detect best extraction method based on PDF content',
    icon: 'auto_awesome',
    bestFor: 'Most documents (recommended)'
  },
  {
    id: 'TEXT_EXTRACTION',
    name: 'Text Extraction',
    description: 'Direct text extraction using PDFBox - fast and accurate for text-based PDFs',
    icon: 'text_fields',
    bestFor: 'Native/digital PDFs with embedded text'
  },
  {
    id: 'VLM',
    name: 'Vision Language Model',
    description: 'AI-powered document understanding using SmolDocling or similar VLMs',
    icon: 'psychology',
    bestFor: 'Scanned documents, images, complex layouts'
  },
  {
    id: 'TRADITIONAL_OCR',
    name: 'Traditional OCR',
    description: 'Detection + recognition pipeline using DBNET + CRNN',
    icon: 'document_scanner',
    bestFor: 'Scanned text documents without complex layout'
  },
  {
    id: 'COMPARE',
    name: 'Compare Methods',
    description: 'Try multiple extraction methods and use the one with best results',
    icon: 'compare',
    bestFor: 'Uncertain document types, quality assurance'
  }
];

/**
 * Table extraction method information for UI display.
 */
export interface TableExtractionMethodInfo {
  id: TableExtractionMethod;
  name: string;
  description: string;
  icon: string;
}

/**
 * Available table extraction methods for UI selection.
 */
export const TABLE_EXTRACTION_METHODS: TableExtractionMethodInfo[] = [
  {
    id: 'AUTO',
    name: 'Auto',
    description: 'Try Tabula first, fall back to VLM if needed',
    icon: 'auto_fix_high'
  },
  {
    id: 'TABULA',
    name: 'Tabula',
    description: 'Rule-based extraction - fast, works on text PDFs',
    icon: 'grid_on'
  },
  {
    id: 'VLM',
    name: 'VLM',
    description: 'AI-based extraction - better for scanned/image tables',
    icon: 'psychology'
  },
  {
    id: 'NONE',
    name: 'Disabled',
    description: 'Skip table extraction',
    icon: 'block'
  }
];

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
  enabled: false,  // Default to in-process mode; configure in Developer Hub > Processing Settings
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
  OCR_PROCESSING = 'OCR_PROCESSING',
  CONVERTING = 'CONVERTING',
  CHUNKING = 'CHUNKING',
  INDEXING_AND_EMBEDDING = 'INDEXING_AND_EMBEDDING',
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
  tokensProcessed?: number;  // Total tokens processed during tokenization
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
  // Batch history - last N completed batches for UI visibility
  batchHistory?: BatchHistoryEntry[];
  // ========== SUBPROCESS RUNTIME INFO ==========
  // Runtime details when running in subprocess mode
  subprocessRuntimeInfo?: SubprocessRuntimeInfo;
  // ========== RESTART TRACKING ==========
  // Current restart attempt (0 = first run, 1+ = restart)
  restartAttempt?: number;
  // Maximum allowed restart attempts
  maxRestartAttempts?: number;
  // Current heap size being used
  heapSize?: string;
  // Whether heap was increased from previous attempt
  heapIncreased?: boolean;
  // ========== RESUME/CHECKPOINT INFO ==========
  // Resume info - shows where this run was resumed from
  resumedFromChunkCount?: number;     // Chunks already processed from previous run
  resumedFromEmbeddedCount?: number;  // Embeddings already done from previous run
  resumedFromIndexedCount?: number;   // Documents already indexed from previous run
  isResumedRun?: boolean;             // True if this is a resumed run from checkpoint
  // ========== OCR PROCESSING METRICS ==========
  currentOcrMetrics?: OcrProcessingMetrics;
  ocrProcessingTimeMs?: number;
}

export interface OcrProcessingMetrics {
  currentPage?: number;
  totalPages?: number;
  currentStep?: string;
  vlmModelId?: string;
  // Per-page token metrics (updated after each page completes)
  generatedTokens?: number;
  promptTokens?: number;
  tokensPerSecond?: number;
  generateTimeMs?: number;
  // Cumulative metrics
  totalTokensGenerated?: number;
  pagesCompleted?: number;
  totalOcrTimeMs?: number;
  averageTokensPerSecond?: number;
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

  // Source document tracking
  sourceDocuments?: string;       // Comma-separated list of source document names
  sourceDocumentCount?: number;   // Total number of unique source documents in this batch

  // Tensor shape information
  inputTensorShape?: string;      // e.g., "[32, 512]" for [batch_size, max_seq_length]
  outputTensorShape?: string;     // e.g., "[32, 768]" for [batch_size, embedding_dim]
  actualInputShape?: string;      // Actual input tensor shape from encoder
  actualOutputShape?: string;     // Actual output tensor shape from encoder

  // Processing status
  statusLevel?: string;           // RUNNING, PROCESSING, SLOW, VERY_SLOW, EXTREMELY_SLOW
  etaMessage?: string;            // Estimated time remaining message
}

/**
 * Simplified batch history entry for UI display.
 * Contains key metrics from completed batches.
 */
export interface BatchHistoryEntry {
  batchNumber: number;
  inputTexts: number;
  maxSequenceLength: number;
  embeddingDimension: number;
  actualInputShape?: string;
  actualOutputShape?: string;
  totalBatchTimeMs: number;
  currentStep?: string;
  tokensPerSecond: number;
  passageTokenCounts?: number[];  // Token count for each passage in the batch
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

/**
 * Type of job being tracked in the active jobs panel.
 */
export type JobType = 'DOCUMENT_INGEST' | 'VECTOR_POPULATION';

export interface IngestProgressUpdate {
  taskId: string;
  fileName: string;
  phase: IngestPhase;
  status: IngestStatus;
  progressPercent: number;
  currentStep: string;
  message: string;
  stats: IngestStats | null;
  errorMessage: string | null;
  timestamp: string;
  /** The ID of the fact sheet this task is associated with */
  factSheetId: number | null;
  /** Type of job - defaults to 'DOCUMENT_INGEST' for backwards compatibility */
  jobType?: JobType;
  /** Keyword index path (for VECTOR_POPULATION jobs) */
  keywordIndexPath?: string;
  /** Vector store path (for VECTOR_POPULATION jobs) */
  vectorIndexPath?: string;
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
 * Pipeline configuration settings.
 * These settings control the ingestion pipeline behavior including
 * batch sizes, thread counts, queue capacities, and timeouts.
 */
export interface PipelineConfig {
  // Batch size settings
  /** Minimum batch size for processing */
  minBatchSize?: number;
  /** Default batch size for processing */
  defaultBatchSize?: number;
  /** Maximum batch size for processing */
  maxBatchSize?: number;

  // Queue settings
  /** Queue capacity for chunks waiting to be processed */
  queueCapacity?: number;
  /** Queue poll timeout in milliseconds */
  queuePollTimeoutMs?: number;
  /** Maximum wait time for batch accumulation (ms) */
  maxBatchWaitMs?: number;
  /** Minimum wait time for batch accumulation (ms) */
  minBatchWaitMs?: number;

  // Thread settings
  /** Number of threads for chunking documents */
  chunkingThreads?: number;
  /** Number of threads for computing embeddings */
  embeddingThreads?: number;
  /** Number of threads for Lucene indexing */
  indexingThreads?: number;
  /** Number of chunks to accumulate before Lucene commit */
  indexingBatchAccumulationSize?: number;

  // Mode settings
  /** Skip embedding computation (keyword-only mode) */
  skipEmbedding?: boolean;

  // Timeout settings
  /** Timeout for each embedding batch in seconds (0 = no timeout) */
  embeddingTimeoutSeconds?: number;

  // Read-only status
  /** Current preset name if using a preset */
  currentPreset?: string;
  /** Whether the config has been modified from defaults */
  isModified?: boolean;
  /** Available preset names */
  availablePresets?: string[];
}

/**
 * Pipeline preset details for display in the UI.
 * Note: This is different from the PipelinePreset string type used for preset selection.
 */
export interface PipelinePresetDetails {
  name: string;
  description: string;
  embeddingTimeoutSeconds: number;
  queueCapacity: number;
  embeddingThreads: number;
  chunkingThreads: number;
  indexingThreads: number;
  skipEmbedding?: boolean;
}

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
// GPU/CUDA DEVICE MEMORY RECOMMENDATIONS
// ═══════════════════════════════════════════════════════════════════════════════

// Note: GpuDeviceInfo is an alias for DeviceInfo (defined below in SYSTEM RESOURCE MONITORING section)
// and DevicesResponse is also defined below. These types are used for GPU memory recommendations.

/**
 * Recommended batch sizes for GPU/CUDA backends based on device memory.
 * GPU memory is typically more constrained than system RAM.
 */
export const GPU_RECOMMENDED_BATCH_SIZES = {
  /** For GPUs with < 4GB VRAM */
  LOW_VRAM: {
    embeddingBatch: 2,
    maxEmbeddingBatch: 4,
    indexBatch: 25,
    description: 'Conservative settings for low VRAM GPUs (< 4GB)'
  },
  /** For GPUs with 4-8GB VRAM */
  MEDIUM_VRAM: {
    embeddingBatch: 4,
    maxEmbeddingBatch: 8,
    indexBatch: 50,
    description: 'Balanced settings for mid-range GPUs (4-8GB)'
  },
  /** For GPUs with 8-16GB VRAM */
  HIGH_VRAM: {
    embeddingBatch: 8,
    maxEmbeddingBatch: 16,
    indexBatch: 75,
    description: 'Performance settings for high-end GPUs (8-16GB)'
  },
  /** For GPUs with > 16GB VRAM */
  VERY_HIGH_VRAM: {
    embeddingBatch: 16,
    maxEmbeddingBatch: 32,
    indexBatch: 100,
    description: 'High-throughput settings for professional GPUs (> 16GB)'
  }
} as const;

/**
 * Get recommended batch sizes based on GPU VRAM in MB.
 * Note: GPU batch sizes are more conservative than CPU due to VRAM constraints
 * and the need to leave room for model weights and intermediate computations.
 */
export function getGpuRecommendedBatchSizes(vramMB: number): typeof GPU_RECOMMENDED_BATCH_SIZES[keyof typeof GPU_RECOMMENDED_BATCH_SIZES] {
  if (vramMB < 4096) {
    return GPU_RECOMMENDED_BATCH_SIZES.LOW_VRAM;
  } else if (vramMB < 8192) {
    return GPU_RECOMMENDED_BATCH_SIZES.MEDIUM_VRAM;
  } else if (vramMB < 16384) {
    return GPU_RECOMMENDED_BATCH_SIZES.HIGH_VRAM;
  } else {
    return GPU_RECOMMENDED_BATCH_SIZES.VERY_HIGH_VRAM;
  }
}

/**
 * Common type for batch size recommendations (CPU or GPU).
 */
export interface BatchSizeRecommendation {
  embeddingBatch: number;
  maxEmbeddingBatch: number;
  indexBatch: number;
  description: string;
}

/**
 * Get recommended batch sizes considering both system RAM and GPU VRAM.
 * When using CUDA backend, GPU memory is often the limiting factor.
 *
 * @param systemMemoryMB Available system RAM in MB
 * @param gpuInfo Optional GPU device information (if using CUDA)
 * @returns Recommended batch sizes, accounting for both RAM and VRAM constraints
 */
export function getRecommendedBatchSizesWithGpu(
  systemMemoryMB: number,
  gpuInfo?: { isGpuBackend: boolean; freeMemoryMB?: number; totalMemoryMB?: number }
): {
  settings: BatchSizeRecommendation;
  isGpuConstrained: boolean;
  constraintReason: string;
} {
  const cpuSettings = getRecommendedBatchSizes(systemMemoryMB);

  // If not using GPU backend, use CPU-based recommendations
  if (!gpuInfo?.isGpuBackend) {
    return {
      settings: cpuSettings,
      isGpuConstrained: false,
      constraintReason: 'Using CPU backend - recommendations based on system RAM'
    };
  }

  // Get GPU VRAM - prefer free memory, fall back to total
  const gpuMemoryMB = gpuInfo.freeMemoryMB ?? gpuInfo.totalMemoryMB ?? 0;

  if (gpuMemoryMB === 0) {
    // No GPU memory info available - use conservative CPU settings
    return {
      settings: cpuSettings,
      isGpuConstrained: false,
      constraintReason: 'GPU memory info unavailable - using system RAM recommendations'
    };
  }

  const gpuSettings = getGpuRecommendedBatchSizes(gpuMemoryMB);

  // Compare GPU vs CPU recommendations and use the more conservative one
  // GPU is typically the limiting factor for embedding operations
  const isGpuMoreConstrained = gpuSettings.embeddingBatch < cpuSettings.embeddingBatch;

  if (isGpuMoreConstrained) {
    return {
      settings: gpuSettings,
      isGpuConstrained: true,
      constraintReason: `GPU VRAM (${Math.round(gpuMemoryMB / 1024 * 10) / 10}GB) is the limiting factor - using GPU-optimized batch sizes`
    };
  } else {
    return {
      settings: cpuSettings,
      isGpuConstrained: false,
      constraintReason: `System RAM (${Math.round(systemMemoryMB / 1024 * 10) / 10}GB) is the limiting factor - GPU has sufficient VRAM`
    };
  }
}

/**
 * GPU memory tier labels for display.
 */
export function getGpuMemoryTierLabel(vramMB: number): string {
  if (vramMB < 4096) return 'Low VRAM (< 4GB)';
  if (vramMB < 8192) return 'Medium VRAM (4-8GB)';
  if (vramMB < 16384) return 'High VRAM (8-16GB)';
  return 'Very High VRAM (> 16GB)';
}

/**
 * GPU memory tier CSS class.
 */
export function getGpuMemoryTierClass(vramMB: number): string {
  if (vramMB < 4096) return 'vram-low';
  if (vramMB < 8192) return 'vram-medium';
  if (vramMB < 16384) return 'vram-high';
  return 'vram-very-high';
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
export type RerankerType = 'none' | 'rm3' | 'bm25prf' | 'rocchio' | 'axiom' | 'score_ties' | 'cross_encoder' | 'rrf' | 'normalize' | 'mmr';

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

  /** Top-K results to rerank (-1 for all) */
  topK?: number;

  // RM3-specific parameters
  /** Weight for original query in RM3 interpolation (0.0-1.0) */
  originalQueryWeight: number;

  /** Whether to filter non-alphanumeric expansion terms */
  filterTerms: boolean;

  /** Whether to output expanded query for debugging */
  outputQuery?: boolean;

  // BM25-PRF parameters
  /** BM25 k1 parameter */
  k1: number;

  /** BM25 b parameter */
  b: number;

  /** Weight boost for newly added expansion terms */
  newTermWeight: number;

  // Rocchio parameters
  /** Rocchio alpha parameter (original query weight) */
  alpha: number;

  /** Rocchio beta parameter (positive feedback weight) */
  beta: number;

  /** Rocchio gamma parameter (negative feedback weight) */
  gamma: number;

  /** Whether to use negative feedback in Rocchio */
  useNegative: boolean;

  // Axiom parameters
  /** Axiom R parameter - number of terms from top passages */
  r?: number;

  /** Axiom N parameter - number of documents to consider */
  n?: number;

  /** Axiom beta parameter - interpolation weight */
  axiomBeta?: number;

  /** Use deterministic random for Axiom */
  deterministic?: boolean;

  /** Random seed for deterministic mode */
  seed?: number;

  // Cross-encoder parameters
  /** Model ID for cross-encoder reranking */
  crossEncoderModel?: string;

  // RRF parameters
  /** RRF k constant (typically 60) */
  rrfK?: number;

  // MMR parameters
  /** MMR lambda: 1.0 = pure relevance, 0.0 = pure diversity */
  lambda?: number;
}

/**
 * Default reranker configuration values.
 */
export const DEFAULT_RERANKER_CONFIG: RerankerConfig = {
  enabled: false,
  type: 'rm3',
  fbDocs: 10,
  fbTerms: 10,
  topK: -1,
  // RM3 defaults
  originalQueryWeight: 0.5,
  filterTerms: true,
  outputQuery: false,
  // BM25-PRF defaults
  k1: 0.9,
  b: 0.4,
  newTermWeight: 0.2,
  // Rocchio defaults
  alpha: 1.0,
  beta: 0.75,
  gamma: 0.15,
  useNegative: false,
  // Axiom defaults
  r: 20,
  n: 30,
  axiomBeta: 0.4,
  deterministic: true,
  seed: 42,
  // Cross-encoder defaults
  crossEncoderModel: '',
  // RRF defaults
  rrfK: 60,
  // MMR defaults
  lambda: 0.5
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
    id: 'axiom',
    name: 'Axiom',
    description: 'Semantic term matching using external corpus'
  },
  {
    id: 'score_ties',
    name: 'Score Ties',
    description: 'Deterministic tie-breaking for reproducibility'
  },
  {
    id: 'cross_encoder',
    name: 'Cross-Encoder',
    description: 'Neural reranking using cross-encoder models (BERT-based)'
  },
  {
    id: 'rrf',
    name: 'RRF',
    description: 'Reciprocal Rank Fusion - Combines multiple ranked lists for hybrid search'
  },
  {
    id: 'normalize',
    name: 'Normalize',
    description: 'Min-max score normalization to [0, 1] range'
  },
  {
    id: 'mmr',
    name: 'MMR',
    description: 'Maximal Marginal Relevance - Reduces redundancy for diverse results'
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

  /** Agent type: CLI (subprocess) or API (OpenAI-compatible endpoint) */
  agentType?: 'CLI' | 'API';

  /** API endpoint URL (API agents only) */
  endpointUrl?: string;

  /** API key (masked when returned from server) */
  apiKey?: string;

  /** Model name for the API endpoint */
  modelName?: string;

  /** Temperature for generation */
  temperature?: number;

  /** Max tokens for generation */
  maxTokens?: number;
}

/** Request body for creating/updating API agent configurations */
export interface ApiAgentConfigRequest {
  name?: string;
  displayName?: string;
  endpointUrl?: string;
  apiKey?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  description?: string;
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

  /** Token throughput metrics from LLM streaming */
  tokenMetrics?: {
    outputTokens: number;
    inputTokens: number;
    totalGenerationMs: number;
    tokensPerSecond: number;
    model?: string;
  };

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

  // Graph RAG Configuration
  /** Enable Graph RAG (knowledge graph-based retrieval) */
  enableGraphRag?: boolean;

  /** Maximum results for graph RAG */
  graphRagMaxResults?: number;

  /** Search type for graph RAG: LOCAL or GLOBAL */
  graphRagSearchType?: string;

  /** Conversation ID for graph RAG entity tracking */
  graphRagConversationId?: string;

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
  | 'CANCELLED'
  // Restart-related event types
  | 'RESTART_SCHEDULED'
  | 'RESTART_ATTEMPTED'
  | 'RESTART_SUCCEEDED'
  | 'RESTART_FAILED'
  | 'MEMORY_ANALYSIS'
  | 'HEAP_ADJUSTED'
  | 'THREADS_REDUCED'
  | 'MANUAL_RESTART';

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

  // Restart-related fields
  /** Current restart attempt number (0 = first run) */
  restartAttempt?: number;

  /** Maximum restart attempts allowed */
  maxRestartAttempts?: number;

  /** Heap size string (e.g., "4g", "2g") */
  heapSize?: string;

  /** Whether heap was increased from previous attempt */
  heapIncreased?: boolean;

  /** OMP_NUM_THREADS setting */
  ompThreads?: number;

  /** OPENBLAS_NUM_THREADS setting */
  blasThreads?: number;

  /** Batch size setting */
  batchSize?: number;

  /** Memory analysis reason/explanation */
  memoryAnalysisReason?: string;

  /** System RAM total bytes */
  systemRamTotal?: number;

  /** System RAM free bytes */
  systemRamFree?: number;

  /** Next restart scheduled time (ISO string) */
  nextRestartTime?: string;

  /** Backoff delay in milliseconds */
  backoffMs?: number;
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
  // BLAS configuration
  blasSerializationEnabled?: boolean;
  openBlasThreads?: number;
}

/**
 * Response for task environment snapshot endpoint (ingest tasks).
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
 * Response for vector population task environment snapshot endpoint.
 */
export interface VectorPopulationTaskEnvironment {
  available: boolean;
  found: boolean;
  taskId: string;
  keywordIndexPath?: string;
  vectorIndexPath?: string;
  timestamp?: string;
  environmentCaptured: boolean;
  nd4jEnvironment?: Nd4jEnvironmentConfig;
  nd4jEnvironmentRaw?: string;
  message?: string;
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
    case 'RESTART_SUCCEEDED':
      return 'success';
    case 'WARNING':
    case 'RESTART_SCHEDULED':
    case 'RESTART_ATTEMPTED':
    case 'MEMORY_ANALYSIS':
    case 'HEAP_ADJUSTED':
    case 'THREADS_REDUCED':
    case 'MANUAL_RESTART':
      return 'warning';
    case 'ERROR':
    case 'FAILED':
    case 'CANCELLED':
    case 'RESTART_FAILED':
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
    'CANCELLED': 'Cancelled',
    // Restart-related events
    'RESTART_SCHEDULED': 'Restart Scheduled',
    'RESTART_ATTEMPTED': 'Restart Attempted',
    'RESTART_SUCCEEDED': 'Restart Succeeded',
    'RESTART_FAILED': 'Restart Failed',
    'MEMORY_ANALYSIS': 'Memory Analysis',
    'HEAP_ADJUSTED': 'Heap Adjusted',
    'THREADS_REDUCED': 'Threads Reduced',
    'MANUAL_RESTART': 'Manual Restart'
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
    'CANCELLED': 'block',
    // Restart-related events
    'RESTART_SCHEDULED': 'schedule_send',
    'RESTART_ATTEMPTED': 'autorenew',
    'RESTART_SUCCEEDED': 'check_circle',
    'RESTART_FAILED': 'error',
    'MEMORY_ANALYSIS': 'memory',
    'HEAP_ADJUSTED': 'tune',
    'THREADS_REDUCED': 'speed',
    'MANUAL_RESTART': 'touch_app'
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
    'OCR_PROCESSING': 'OCR Processing',
    'CONVERTING': 'Converting',
    'CHUNKING': 'Chunking',
    'INDEXING_AND_EMBEDDING': 'Indexing + Embedding',
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
    'OCR_PROCESSING': 'document_scanner',
    'CONVERTING': 'transform',
    'CHUNKING': 'content_cut',
    'INDEXING_AND_EMBEDDING': 'sync',
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
 * Model status update from WebSocket.
 * Provides real-time embedding and staging connection status.
 */
export interface ModelStatusUpdate {
  timestamp: number;
  ready: boolean;
  embedding: EmbeddingStatusInfo;
  staging: StagingStatusInfo;
}

export interface EmbeddingStatusInfo {
  available: boolean;
  modelId?: string;
  loading: boolean;
  loadingPhase?: string;
  loadingMessage?: string;
  loadingElapsedMs?: number;
  source?: string;
  initialized: boolean;
  dimensions?: number;
  optimalBatchSize?: number;
  error?: string;
  canRetry?: boolean;
}

export interface StagingStatusInfo {
  available: boolean;
  connected: boolean;
  attempted: boolean;
  endpointUrl?: string;
  canRetry: boolean;
  consecutiveFailures: number;
  lastError?: string;
  lastAttemptTimeMs?: number;
  timeSinceLastAttemptMs?: number;
  activeConfigId?: number;
  activeConfigName?: string;
  verified?: boolean;
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
 * Graph building settings for entity/relationship extraction.
 */
export interface GraphBuildingSettings {
  /** Enable entity extraction during indexing */
  enabled: boolean;
  /** Batch size for entity extraction (chunks per LLM call) */
  batchSize: number;
  /** Schema enforcement mode: NONE, LENIENT, STRICT */
  schemaEnforcement?: string;
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
  graphBuilding: GraphBuildingSettings;
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
  graphBuilding?: StageMetrics;
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
  graphBuilding?: Partial<GraphBuildingSettings>;
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

  /** Source of the log: STDOUT, STDERR, SYSTEM, EMBEDDING */
  source: 'STDOUT' | 'STDERR' | 'SYSTEM' | 'EMBEDDING';

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

// ═══════════════════════════════════════════════════════════════════════════════
// VECTOR POPULATION MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Vector population phases (matches backend VectorPopulationPhase enum).
 */
export enum VectorPopulationPhase {
  LOADING = 'LOADING',
  EMBEDDING = 'EMBEDDING',
  INDEXING = 'INDEXING',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED'
}

/**
 * Vector population status (matches backend VectorPopulationStatus enum).
 */
export enum VectorPopulationStatus {
  PENDING = 'PENDING',
  IN_PROGRESS = 'IN_PROGRESS',
  COMPLETED = 'COMPLETED',
  FAILED = 'FAILED',
  CANCELLED = 'CANCELLED'
}

/**
 * Worker status for vector population subprocess.
 */
export interface VectorPopulationWorkerStatus {
  workerId: number;
  workerType: string;
  status: string;
  itemsProcessed: number;
  currentBatchSize: number;
  throughput: number;
  currentItem?: string;
}

/**
 * Queue status for vector population subprocess.
 */
export interface VectorPopulationQueueStatus {
  chunkQueueSize: number;
  chunkQueueCapacity: number;
  embeddedQueueSize: number;
  embeddedQueueCapacity: number;
}

/**
 * Embedding batch metrics for vector population.
 */
export interface VectorPopulationEmbeddingBatchMetrics {
  batchNumber: number;
  totalBatches: number;
  inputTexts: number;
  currentStep: string;
  heartbeatSeconds: number;
  forwardPassTimeMs: number;
  totalBatchTimeMs: number;
  isStuck: boolean;
  sourceDocuments?: string;
  sourceDocumentCount?: number;
  inputTensorShape?: string;
  outputTensorShape?: string;
  embeddingDimension?: number;
  inferenceTimeMs?: number;
  batchThroughput?: number;
  modelName?: string;
  deviceType?: string;
  // Additional timing metrics
  tokensProcessed?: number;
  maxTokenLength?: number;
  avgTokenLength?: number;
  inputTokens?: number;
  tokenizationTimeMs?: number;
  paddingTimeMs?: number;
  tensorCreationTimeMs?: number;
  extractionTimeMs?: number;
  // Per-passage token counts
  passageTokenCounts?: number[];
}

/**
 * Statistics for vector population progress.
 */
export interface VectorPopulationStats {
  documentsLoaded: number;
  chunksCreated: number;
  chunksEmbedded: number;
  chunksIndexed: number;
  totalDocuments: number;
  // Token counts for tracking total tokens processed
  tokensProcessed: number;
  totalTokensInIndex: number;
  elapsedTimeMs: number;
  throughputDocsPerSec: number;
  memoryUsagePercent: number;
  // Actual index store counts (verified after commit to persistence)
  actualKeywordIndexCount?: number;
  actualVectorStoreCount?: number;
  workerStatuses?: VectorPopulationWorkerStatus[];
  queueStatus?: VectorPopulationQueueStatus;
  currentEmbeddingBatch?: VectorPopulationEmbeddingBatchMetrics;
  // Batch history - last N completed batches for UI visibility
  batchHistory?: BatchHistoryEntry[];
  // Resume/restart info - shows where this run was resumed from
  resumedFromChunkCount?: number;     // Chunks already processed from previous run
  resumedFromEmbeddedCount?: number;  // Embeddings already done from previous run
  resumedFromIndexedCount?: number;   // Documents already indexed from previous run
  isResumedRun?: boolean;             // True if this is a resumed run from checkpoint
}

/**
 * Vector population progress update (WebSocket message).
 */
export interface VectorPopulationUpdate {
  taskId: string;
  phase: VectorPopulationPhase;
  status: VectorPopulationStatus;
  progressPercent: number;
  currentStep: string;
  message: string;
  keywordIndexPath?: string;
  vectorIndexPath?: string;
  stats?: VectorPopulationStats;
  errorMessage?: string;
  timestamp: string;
}

/**
 * Vector population subprocess status.
 */
export interface VectorPopulationSubprocessStatus {
  taskId: string;
  keywordIndexPath: string;
  vectorIndexPath: string;
  pid: number;
  alive: boolean;
  cancelled: boolean;
  oomDetected: boolean;
  currentPhase: string;
  progressPercent: number;
  lastMessage: string;
  startTime: string;
  lastHeartbeat: string;
}

/**
 * Vector population launch request.
 */
export interface VectorPopulationLaunchRequest {
  keywordIndexPath: string;
  vectorIndexPath: string;
  taskId?: string;
  embeddingBatchSize?: number;
  parallelIndexing?: boolean;
  indexingWorkers?: number;
}

/**
 * Vector population service status response.
 */
export interface VectorPopulationServiceStatus {
  populationServiceAvailable: boolean;
  trackerAvailable: boolean;
  subprocessLauncherAvailable: boolean;
  activeTaskCount?: number;
  totalTrackedTasks?: number;
  activeSubprocessCount?: number;
}

/**
 * Vector population summary response.
 */
export interface VectorPopulationSummary {
  available: boolean;
  message?: string;
  legacyActiveTaskCount?: number;
  trackedTaskCount?: number;
  activeCount?: number;
  completedCount?: number;
  failedCount?: number;
  cancelledCount?: number;
  subprocessCount?: number;
  aliveSubprocessCount?: number;
}

/**
 * Get display name for vector population phase.
 */
export function getVectorPopulationPhaseDisplayName(phase: VectorPopulationPhase | string): string {
  const displayNames: { [key: string]: string } = {
    'LOADING': 'Loading Documents',
    'EMBEDDING': 'Generating Embeddings',
    'INDEXING': 'Writing to Vector Store',
    'COMPLETED': 'Completed',
    'FAILED': 'Failed',
    'CANCELLED': 'Cancelled'
  };
  return displayNames[phase] || phase;
}

/**
 * Get icon for vector population phase.
 */
export function getVectorPopulationPhaseIcon(phase: VectorPopulationPhase | string): string {
  const icons: { [key: string]: string } = {
    'LOADING': 'folder_open',
    'EMBEDDING': 'memory',
    'INDEXING': 'storage',
    'COMPLETED': 'check_circle',
    'FAILED': 'error',
    'CANCELLED': 'cancel'
  };
  return icons[phase] || 'info';
}

/**
 * Get color for vector population status.
 */
export function getVectorPopulationStatusColor(status: VectorPopulationStatus | string): string {
  const colors: { [key: string]: string } = {
    'PENDING': 'gray',
    'IN_PROGRESS': 'blue',
    'COMPLETED': 'green',
    'FAILED': 'red',
    'CANCELLED': 'orange'
  };
  return colors[status] || 'gray';
}

// ═══════════════════════════════════════════════════════════════════════════════
// SOURCE VIEWER INTERFACES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * View mode for source files.
 */
export type SourceViewMode = 'TEXT' | 'IMAGE' | 'EMBEDDED' | 'DOWNLOAD_ONLY';

/**
 * Source type indicating where the document came from.
 */
export type SourceType = 'UPLOAD' | 'STORED' | 'URL';

/**
 * Information about a source file.
 */
export interface SourceInfo {
  /** File name */
  fileName: string;
  /** Full path to file */
  path: string;
  /** SHA-256 checksum (for stored documents) */
  checksum: string | null;
  /** Source type (UPLOAD, STORED, or URL) */
  sourceType: SourceType;
  /** File extension (lowercase, without dot) */
  extension: string;
  /** MIME type */
  mimeType: string;
  /** File size in bytes */
  sizeBytes: number;
  /** Last modified timestamp (ISO format) */
  lastModified: string;
  /** How this file can be viewed */
  viewMode: SourceViewMode;
  /** Whether this file can be previewed inline */
  canPreview: boolean;
  /** Original URL if downloaded from web */
  sourceUrl: string | null;
}

/**
 * Response for listing sources.
 */
export interface SourceListResponse {
  /** List of sources */
  sources: SourceInfo[];
  /** Total count (before pagination) */
  totalCount: number;
  /** Current offset */
  offset: number;
  /** Page limit */
  limit: number;
}

/**
 * Response for text content retrieval.
 */
export interface TextContentResponse {
  /** Text content of the file */
  content: string | null;
  /** File name */
  fileName: string;
  /** File extension */
  extension: string | null;
  /** Original file size in bytes */
  fileSize: number;
  /** Number of lines returned */
  lineCount: number;
  /** Error message if any */
  error: string | null;
  /** Whether content was truncated */
  truncated: boolean;
}

/**
 * Supported file types response.
 */
export interface SupportedTypesResponse {
  /** Extensions supported for text viewing */
  textExtensions: string[];
  /** Extensions supported for image viewing */
  imageExtensions: string[];
  /** Extensions supported for embedded viewing (PDF) */
  viewableExtensions: string[];
  /** All supported extensions */
  allSupported: string[];
}

/**
 * Get icon for source view mode.
 */
export function getSourceViewModeIcon(viewMode: SourceViewMode): string {
  switch (viewMode) {
    case 'TEXT': return 'description';
    case 'IMAGE': return 'image';
    case 'EMBEDDED': return 'picture_as_pdf';
    case 'DOWNLOAD_ONLY': return 'download';
    default: return 'insert_drive_file';
  }
}

/**
 * Get display name for source view mode.
 */
export function getSourceViewModeDisplayName(viewMode: SourceViewMode): string {
  switch (viewMode) {
    case 'TEXT': return 'Text File';
    case 'IMAGE': return 'Image';
    case 'EMBEDDED': return 'Document';
    case 'DOWNLOAD_ONLY': return 'Download Only';
    default: return 'Unknown';
  }
}

/**
 * Format file size for display.
 */
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// ═══════════════════════════════════════════════════════════════════════════════
// FACT SHEET MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * A fact sheet - a named collection of facts that users can switch between.
 */
export interface FactSheet {
  /** Unique ID */
  id: number;
  /** Name of the fact sheet */
  name: string;
  /** Optional description */
  description: string | null;
  /** Whether this is the currently active sheet */
  isActive: boolean;
  /** ID of the sheet this was derived from (if any) */
  derivedFromId: number | null;
  /** Color for UI display (hex) */
  color: string;
  /** Icon name for UI display */
  icon: string;
  /** Path to vector (dense) store for this sheet */
  vectorStorePath: string | null;
  /** Path to keyword (sparse) index for this sheet */
  keywordIndexPath: string | null;

  // ==================== Retrieval Configuration ====================
  /** Embedding model ID for retrieval (e.g., "bge-base-en-v1.5") */
  embeddingModel: string | null;
  /** Source of the embedding model: 'archive', 'registry', or 'default' */
  embeddingModelSource: string | null;
  /** Archive ID if embeddingModelSource is 'archive' */
  embeddingArchiveId: string | null;

  // ==================== Model Tracking ====================
  /** The embedding model that was actually used when indexing documents */
  indexedWithModel: string | null;
  /** Timestamp when the vector store was last populated/indexed */
  indexedAt: string | null;
  /** Whether this fact sheet needs reindexing due to model mismatch */
  needsReindex: boolean;

  // ==================== Reranking Configuration ====================
  /** Whether reranking is enabled for this fact sheet */
  rerankingEnabled: boolean;
  /** Type of reranker: 'none', 'cross_encoder', 'rrf', 'mmr', 'rm3', 'bm25prf' */
  rerankerType: string | null;
  /** Cross-encoder model ID for reranking */
  crossEncoderModel: string | null;
  /** Source of the cross-encoder model: 'archive', 'registry', or 'default' */
  crossEncoderModelSource: string | null;
  /** Archive ID if crossEncoderModelSource is 'archive' */
  crossEncoderArchiveId: string | null;
  /** Number of top results to rerank */
  rerankTopK: number | null;
  /** MMR lambda parameter (diversity vs relevance trade-off) */
  mmrLambda: number | null;

  // ==================== Knowledge Graph Configuration ====================
  /** Whether knowledge graph building is enabled for this fact sheet */
  enableGraphBuilding: boolean;
  /** Type of graph builder: 'manual', 'llm', 'pattern', 'hybrid' */
  graphBuilderType: string | null;
  /** JSON configuration for the graph builder */
  graphBuilderConfigJson: string | null;
  /** Storage type for accepted graph data: 'jpa' or 'neo4j' */
  graphStorageType: string | null;

  // ==================== Stats ====================
  /** Number of facts in this sheet */
  factCount: number;
  /** Number of indexed facts in this sheet */
  indexedCount: number;
  /** Number of unindexed facts in this sheet */
  unindexedCount: number;
  /** Total size of all facts in bytes */
  totalSizeBytes: number;
  /** When created */
  createdAt: string;
  /** When last modified */
  updatedAt: string;
}

/**
 * A fact - a document or file that belongs to a fact sheet.
 */
export interface Fact {
  /** Unique ID */
  id: number;
  /** ID of the parent fact sheet */
  factSheetId: number;
  /** Original file name */
  fileName: string;
  /** Full path to the file */
  filePath: string;
  /** SHA-256 checksum */
  checksum: string | null;
  /** Source type (UPLOAD, STORED, URL, TEXT, IMPORT) */
  sourceType: FactSourceType;
  /** File extension (lowercase, without dot) */
  extension: string | null;
  /** MIME type */
  mimeType: string | null;
  /** File size in bytes */
  sizeBytes: number | null;
  /** How this fact can be viewed */
  viewMode: SourceViewMode;
  /** Whether this fact can be previewed inline */
  canPreview: boolean;
  /** User-provided title */
  title: string | null;
  /** User-provided notes */
  notes: string | null;
  /** Comma-separated tags */
  tags: string | null;
  /** Original URL if downloaded from web */
  sourceUrl: string | null;
  /** Whether this fact has been indexed in the vector store */
  indexed: boolean;
  /** When this fact was indexed (null if not indexed) */
  indexedAt: string | null;
  /** When added to the sheet */
  createdAt: string;
  /** When last modified */
  updatedAt: string;
  /** When last accessed/viewed */
  lastAccessedAt: string | null;
}

/**
 * Fact source types.
 */
export type FactSourceType = 'UPLOAD' | 'STORED' | 'URL' | 'TEXT' | 'IMPORT';

/**
 * Request to create a new fact sheet.
 */
export interface CreateFactSheetRequest {
  name: string;
  description?: string;
  color?: string;
  icon?: string;
  vectorStorePath?: string;
  keywordIndexPath?: string;
  // Retrieval configuration
  embeddingModel?: string;
  embeddingModelSource?: string;
  embeddingArchiveId?: string;
  // Reranking configuration
  rerankingEnabled?: boolean;
  rerankerType?: string;
  crossEncoderModel?: string;
  crossEncoderModelSource?: string;
  crossEncoderArchiveId?: string;
  rerankTopK?: number;
  mmrLambda?: number;
}

/**
 * Request to derive a new fact sheet from an existing one.
 */
export interface DeriveFactSheetRequest {
  name: string;
  description?: string;
}

/**
 * Request to update a fact sheet.
 */
export interface UpdateFactSheetRequest {
  name?: string;
  description?: string;
  color?: string;
  icon?: string;
  vectorStorePath?: string;
  keywordIndexPath?: string;
  // Retrieval configuration
  embeddingModel?: string;
  embeddingModelSource?: string;
  embeddingArchiveId?: string;
  // Reranking configuration
  rerankingEnabled?: boolean;
  rerankerType?: string;
  crossEncoderModel?: string;
  crossEncoderModelSource?: string;
  crossEncoderArchiveId?: string;
  rerankTopK?: number;
  mmrLambda?: number;
  // Knowledge graph configuration
  enableGraphBuilding?: boolean;
  graphBuilderType?: string;
  graphBuilderConfigJson?: string;
  graphStorageType?: string;
}

/**
 * Request to update a fact.
 */
export interface UpdateFactRequest {
  title?: string;
  notes?: string;
  tags?: string;
}

/**
 * Request to copy or move facts between sheets.
 */
export interface CopyFactsRequest {
  factIds: number[];
}

/**
 * Response from copy/move operations.
 */
export interface CopyFactsResponse {
  copiedCount?: number;
  movedCount?: number;
}

/**
 * Request to mark facts as indexed.
 */
export interface MarkIndexedRequest {
  factIds: number[];
}

/**
 * Response from mark indexed operations.
 */
export interface MarkIndexedResponse {
  markedCount: number;
}

/**
 * Indexing statistics for a fact sheet.
 */
export interface IndexingStats {
  /** Total number of facts */
  totalFacts: number;
  /** Number of facts that have been indexed */
  indexedFacts: number;
  /** Number of facts that have not been indexed */
  unindexedFacts: number;
  /** Percentage of facts that have been indexed */
  indexedPercentage: number;
  /** Whether all facts have been indexed */
  allIndexed: boolean;
  /** Whether there are unindexed facts */
  hasUnindexed: boolean;
}

/**
 * A fact ready for indexing with its file path information.
 */
export interface FactForIndexing {
  id: number;
  fileName: string;
  filePath: string;
  sourceType: string;
  extension: string | null;
  mimeType: string | null;
  sizeBytes: number | null;
}

// ═══════════════════════════════════════════════════════════════════════════
// EMBEDDING MODEL MANAGEMENT
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Detailed information about a fact sheet's embedding model configuration and status.
 */
export interface EmbeddingModelInfo {
  /** The configured embedding model ID */
  configuredModel: string | null;
  /** Source of the model: 'default', 'registry', 'archive' */
  modelSource: string | null;
  /** Archive ID if source is 'archive' */
  archiveId: string | null;
  /** The model that was actually used for indexing */
  indexedWithModel: string | null;
  /** When the vector store was last indexed */
  indexedAt: string | null;
  /** Number of indexed facts */
  indexedFactCount: number;
  /** Total number of facts */
  totalFactCount: number;
  /** Whether reindexing is needed due to model mismatch */
  needsReindex: boolean;
  /** Whether there's a mismatch between configured and indexed model */
  hasModelMismatch: boolean;
  /** Percentage of facts that are indexed */
  indexedPercentage: number;
}

/**
 * Request to update a fact sheet's embedding model.
 */
export interface UpdateEmbeddingModelRequest {
  modelId: string;
  modelSource?: string;
  archiveId?: string;
  forceReindex?: boolean;
}

/**
 * Response from updating a fact sheet's embedding model.
 */
export interface EmbeddingModelUpdateResponse {
  success: boolean;
  message: string | null;
  previousModel: string | null;
  newModel: string | null;
  modelChanged: boolean;
  reindexRequired: boolean;
  affectedFactCount: number;
  error: string | null;
}

/**
 * Request to check what would happen if embedding model is changed.
 */
export interface CheckEmbeddingModelChangeRequest {
  newModelId: string;
}

/**
 * Response from checking an embedding model change.
 */
export interface EmbeddingModelChangeCheck {
  currentModel: string | null;
  proposedModel: string | null;
  indexedWithModel: string | null;
  indexedFactCount: number;
  totalFactCount: number;
  modelDiffers: boolean;
  wouldRequireReindex: boolean;
  warningMessage: string | null;
}

/**
 * Request to record which model was used for indexing.
 */
export interface SetIndexedModelRequest {
  modelId: string;
}

/**
 * Get icon for fact source type.
 */
export function getFactSourceTypeIcon(sourceType: FactSourceType): string {
  switch (sourceType) {
    case 'UPLOAD': return 'upload_file';
    case 'STORED': return 'inventory_2';
    case 'URL': return 'link';
    case 'TEXT': return 'text_snippet';
    case 'IMPORT': return 'import_export';
    default: return 'description';
  }
}

/**
 * Get display name for fact source type.
 */
export function getFactSourceTypeDisplayName(sourceType: FactSourceType): string {
  switch (sourceType) {
    case 'UPLOAD': return 'Uploaded';
    case 'STORED': return 'Stored';
    case 'URL': return 'From URL';
    case 'TEXT': return 'Text Input';
    case 'IMPORT': return 'Imported';
    default: return 'Unknown';
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// CROSS-INDEX STATUS MODELS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Overall index status for a document across all indexes.
 */
export type OverallIndexStatus = 'NOT_INDEXED' | 'PARTIAL' | 'FULLY_INDEXED' | 'OUT_OF_SYNC' | 'FAILED';

/**
 * Status for a document in a single index.
 */
export type IndexStatus = 'NOT_INDEXED' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'STALE';

/**
 * Sync job status.
 */
export type SyncStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

/**
 * Cross-index summary response.
 */
export interface CrossIndexSummary {
  factSheetId: number | null;
  factSheetName: string | null;
  totalDocuments: number;
  totalPassages: number;
  fullyIndexedDocuments: number;
  partiallyIndexedDocuments: number;
  notIndexedDocuments: number;
  outOfSyncDocuments: number;
  documentsNeedingSync: number;
  passagesMissingFromVector: number;
  passagesMissingFromGraph: number;
  autoSyncEnabled: boolean;
  lastSyncAt: string | null;
}

/**
 * Cross-index statistics response.
 */
export interface CrossIndexStatistics {
  factSheetId: number;
  documentStats: DocumentStats;
  passageStats: PassageStats;
  statusDistribution: { [key in OverallIndexStatus]?: number };
}

/**
 * Document statistics.
 */
export interface DocumentStats {
  total: number;
  fullyIndexed: number;
  partial: number;
  notIndexed: number;
  failed: number;
}

/**
 * Passage statistics.
 */
export interface PassageStats {
  total: number;
  inKeywordIndex: number;
  inVectorStore: number;
  inGraph: number;
}

/**
 * Single indexed document.
 */
export interface IndexedDocumentItem {
  id: number;
  sourceId: string;
  fileName: string | null;
  factId: number | null;
  overallStatus: OverallIndexStatus;
  keywordIndexStatus: IndexStatus;
  vectorStoreStatus: IndexStatus;
  graphStatus: IndexStatus;
  keywordPassageCount: number;
  vectorPassageCount: number;
  graphNodeCount: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Paginated indexed document list response.
 */
export interface IndexedDocumentResponse {
  documents: IndexedDocumentItem[];
  total: number;
  offset: number;
  limit: number;
}

/**
 * Indexed document detail response with passages.
 */
export interface IndexedDocumentDetail {
  document: IndexedDocumentItem;
  passages: IndexedPassageItem[];
  statusCounts: {
    fullyIndexed: number;
    inKeywordOnly: number;
    inVectorOnly: number;
    notIndexed: number;
  };
}

/**
 * Content type for passages.
 */
export type PassageContentType = 'text' | 'table' | 'code' | 'image';

/**
 * Single indexed passage item.
 */
export interface IndexedPassageItem {
  id: number;
  chunkId: string;
  chunkIndex: number | null;
  contentPreview: string | null;
  keywordIndexStatus: IndexStatus;
  vectorStoreStatus: IndexStatus;
  graphStatus: IndexStatus;
  vectorId: string | null;
  graphNodeId: string | null;
  // Content type and table metadata
  contentType?: PassageContentType;
  fullContent?: string | null;
  tableRowCount?: number | null;
  tableColumnCount?: number | null;
  tableHeaders?: string | null;
  tableType?: string | null;
  tablePageNumber?: number | null;
}

/**
 * Parsed table data for rendering.
 */
export interface ParsedTable {
  headers: string[];
  rows: string[][];
  rowCount: number;
  columnCount: number;
  tableType: string;
  rawMarkdown: string;
}

/**
 * Table passage with parsed data.
 */
export interface TablePassage extends IndexedPassageItem {
  contentType: 'table';
  parsedTable?: ParsedTable;
}

/**
 * Paginated indexed passage list response.
 */
export interface IndexedPassageResponse {
  passages: IndexedPassageItem[];
  total: number;
  offset: number;
  limit: number;
}

/**
 * Passage status response for bulk check.
 */
export interface PassageStatusResponse {
  chunkId: string;
  inKeywordIndex: boolean;
  inVectorStore: boolean;
  inKnowledgeGraph: boolean;
  fullyIndexed: boolean;
}

/**
 * Sync request.
 */
export interface SyncRequest {
  factSheetId?: number;
  documentIds?: number[];
  chunkIds?: string[];
  async: boolean;
}

/**
 * Sync job response.
 */
export interface SyncJobResponse {
  jobId: string | null;
  status: string;
  startedAt: string;
  message: string;
}

/**
 * Sync job status response.
 */
export interface SyncJobStatusResponse {
  jobId: string;
  status: SyncStatus;
  progress: number;
  message: string;
  documentsProcessed: number;
  passagesProcessed: number;
  totalDocuments: number;
  totalPassages: number;
  progressPercent: number;
  errors: string[];
}

/**
 * Auto-sync configuration request.
 */
export interface AutoSyncConfigRequest {
  factSheetId: number;
  enabled: boolean;
  maxPassagesPerSync?: number;
  syncTimeoutSeconds?: number;
  syncOnSearch?: boolean;
  syncOnIngest?: boolean;
}

/**
 * Auto-sync configuration response.
 */
export interface AutoSyncConfigResponse {
  factSheetId: number;
  enabled: boolean;
  maxPassagesPerSync: number;
  syncTimeoutSeconds: number;
  syncOnSearch: boolean;
  syncOnIngest: boolean;
}

/**
 * Get CSS class for overall index status.
 */
export function getCrossIndexStatusColor(status: OverallIndexStatus): string {
  switch (status) {
    case 'FULLY_INDEXED': return 'status-green';
    case 'PARTIAL': return 'status-yellow';
    case 'OUT_OF_SYNC': return 'status-blue';
    case 'NOT_INDEXED': return 'status-red';
    case 'FAILED': return 'status-red';
    default: return 'status-gray';
  }
}

/**
 * Get icon for overall index status.
 */
export function getCrossIndexStatusIcon(status: OverallIndexStatus): string {
  switch (status) {
    case 'FULLY_INDEXED': return 'check_circle';
    case 'PARTIAL': return 'warning';
    case 'OUT_OF_SYNC': return 'sync_problem';
    case 'NOT_INDEXED': return 'cancel';
    case 'FAILED': return 'error';
    default: return 'help';
  }
}

/**
 * Get label for overall index status.
 */
export function getCrossIndexStatusLabel(status: OverallIndexStatus): string {
  switch (status) {
    case 'FULLY_INDEXED': return 'Fully Synced';
    case 'PARTIAL': return 'Partially Synced';
    case 'OUT_OF_SYNC': return 'Out of Sync';
    case 'NOT_INDEXED': return 'Not Indexed';
    case 'FAILED': return 'Failed';
    default: return 'Unknown';
  }
}

/**
 * Get CSS class for individual index status.
 */
export function getIndexStatusColor(status: IndexStatus): string {
  switch (status) {
    case 'INDEXED': return 'status-green';
    case 'INDEXING': return 'status-blue';
    case 'STALE': return 'status-yellow';
    case 'NOT_INDEXED': return 'status-gray';
    case 'FAILED': return 'status-red';
    default: return 'status-gray';
  }
}

/**
 * Get icon for individual index status.
 */
export function getIndexStatusIcon(status: IndexStatus): string {
  switch (status) {
    case 'INDEXED': return 'check_circle';
    case 'INDEXING': return 'sync';
    case 'STALE': return 'update';
    case 'NOT_INDEXED': return 'remove_circle_outline';
    case 'FAILED': return 'error';
    default: return 'help';
  }
}

// ==============================================================================
// Model Registry Types (for kompile-model-staging integration)
// ==============================================================================

/**
 * Model type in the registry.
 */
export type ModelType = 'encoder' | 'cross_encoder' | 'reranker';

/**
 * Model status in the registry.
 */
export type ModelRegistryStatus = 'active' | 'staged' | 'pending' | 'failed' | 'deprecated';

/**
 * Model metadata including dimensions, architecture, and provenance.
 */
export interface ModelMetadata {
  embeddingDim?: number;
  hiddenSize?: number;
  numLayers?: number;
  maxSequenceLength: number;
  modelType?: string;  // 'dense' | 'sparse'
  framework: string;   // Always 'samediff' for production models
  trainingData?: string;
  sourceOrigin?: string;  // 'huggingface' | 'github' | 'custom'
  sourceRepository?: string;
  originalFormat?: string;  // 'onnx' | 'tensorflow' | 'keras'
  conversionDate?: string;
  description?: string;
  vocabSize?: number;
  version?: string;

  // === Provenance fields for version tracking ===

  /** Version of the staging registry when this model was pulled */
  stagingRegistryVersion?: string;
  /** Archive ID if this model was installed from an archive */
  sourceArchiveId?: string;
  /** Archive version if this model was installed from an archive */
  sourceArchiveVersion?: string;
  /** How the model was installed: 'staging' | 'archive' | 'builtin' | 'manual' */
  installedFrom?: string;
  /** ISO 8601 timestamp when the model was installed */
  installedAt?: string;
}

/**
 * Tokenizer configuration for a model.
 */
export interface TokenizerConfig {
  doLowerCase: boolean;
  addSpecialTokens: boolean;
  stripAccents: boolean;
  maxLength: number;
  padding?: string;
  truncation?: boolean;
}

/**
 * A single model entry in the registry.
 */
export interface ModelRegistryEntry {
  modelId: string;
  type: ModelType;
  path: string;
  modelFile: string;
  vocabFile: string;
  checksum: string;
  status: ModelRegistryStatus;
  promotedAt?: string;
  version?: string;
  metadata: ModelMetadata;
  tokenizer: TokenizerConfig;
}

/**
 * Information about an installed archive.
 */
export interface ArchiveInstallInfo {
  archiveId: string;
  archiveName: string;
  version: string;
  installedAt: string;
  sourceUrl?: string;
  checksum?: string;
  modelIds: string[];
}

/**
 * The root model registry structure.
 */
export interface ModelRegistry {
  version: string;
  updatedAt: string;
  models: { [modelId: string]: ModelRegistryEntry };
  installedArchives?: { [archiveId: string]: ArchiveInstallInfo };
}

/**
 * Version and provenance summary for the registry.
 */
export interface VersionInfoResponse {
  registryVersion: string;
  updatedAt: string;
  totalModels: number;
  activeModels: number;
  modelsBySource: { [source: string]: number };
  installedArchives: ArchiveInstallInfo[];
}

/**
 * Staging status for a model being processed.
 */
export type StagingStatus = 'pending' | 'downloading' | 'converting' | 'validating' | 'ready' | 'promoting' | 'completed' | 'failed';

/**
 * Information about a model in the staging pipeline.
 */
export interface StagingModelInfo {
  modelId: string;
  status: StagingStatus;
  progress: number;
  error?: string;
  source: string;
  type?: ModelType;
  startedAt: string;
  completedAt?: string;
  message?: string;
}

/**
 * Response from the staging service status endpoint.
 */
export interface StagingStatusResponse {
  connected: boolean;
  stagingServiceUrl?: string;
  modelsInStaging: StagingModelInfo[];
  lastSync?: string;
}

/**
 * Get CSS class for model registry status.
 */
export function getModelRegistryStatusColor(status: ModelRegistryStatus): string {
  switch (status) {
    case 'active': return 'status-green';
    case 'staged': return 'status-blue';
    case 'pending': return 'status-yellow';
    case 'failed': return 'status-red';
    case 'deprecated': return 'status-gray';
    default: return 'status-gray';
  }
}

/**
 * Get icon for model registry status.
 */
export function getModelRegistryStatusIcon(status: ModelRegistryStatus): string {
  switch (status) {
    case 'active': return 'check_circle';
    case 'staged': return 'schedule';
    case 'pending': return 'hourglass_empty';
    case 'failed': return 'error';
    case 'deprecated': return 'archive';
    default: return 'help';
  }
}

/**
 * Get icon for staging status.
 */
export function getStagingStatusIcon(status: StagingStatus): string {
  switch (status) {
    case 'pending': return 'hourglass_empty';
    case 'downloading': return 'cloud_download';
    case 'converting': return 'transform';
    case 'validating': return 'verified';
    case 'ready': return 'check_circle';
    case 'promoting': return 'publish';
    case 'completed': return 'done_all';
    case 'failed': return 'error';
    default: return 'help';
  }
}

/**
 * Get CSS class for staging status.
 */
export function getStagingStatusColor(status: StagingStatus): string {
  switch (status) {
    case 'pending': return 'status-gray';
    case 'downloading':
    case 'converting':
    case 'validating':
    case 'promoting': return 'status-blue';
    case 'ready':
    case 'completed': return 'status-green';
    case 'failed': return 'status-red';
    default: return 'status-gray';
  }
}

// ==================== Archive Types ====================

/**
 * Information about a Kompile Archive (.karch).
 */
export interface ArchiveInfo {
  /** Archive file name */
  name: string;
  /** Full path to the archive */
  path: string;
  /** Unique archive ID from manifest */
  archiveId: string | null;
  /** Version of the archive content */
  version: string | null;
  /** Description of the archive */
  description: string | null;
  /** Number of models in the archive */
  modelCount: number;
  /** Size of the archive in bytes */
  sizeBytes: number;
  /** Last modification timestamp */
  lastModified: string;
  /** Whether this archive is currently loaded */
  loaded: boolean;
}

/**
 * Status of the currently loaded archive.
 */
export interface ArchiveStatus {
  /** Whether an archive is loaded */
  loaded: boolean;
  /** Path to the loaded archive */
  archivePath: string | null;
  /** Archive ID from manifest */
  archiveId: string | null;
  /** Content version */
  contentVersion: string | null;
  /** Description */
  description: string | null;
  /** Total number of models */
  modelCount: number;
  /** Number of encoder models */
  encoderCount: number;
  /** Number of cross-encoder models */
  crossEncoderCount: number;
  /** When the archive was loaded */
  loadedAt: string | null;
}

/**
 * Model information from an archive.
 */
export interface ArchiveModelInfo {
  /** Model ID */
  modelId: string;
  /** Model type (encoder, cross_encoder) */
  type: string;
  /** Path within archive */
  path: string;
  /** Embedding dimension */
  embeddingDim: number | null;
  /** Max sequence length */
  maxSequenceLength: number | null;
  /** Description */
  description: string | null;
}

/**
 * Request to load an archive.
 */
export interface LoadArchiveRequest {
  archivePath: string;
}

/**
 * Request to extract a model from an archive.
 */
export interface ExtractModelRequest {
  modelId: string;
  destinationPath?: string;
}

/**
 * Result of extracting a model from an archive.
 */
export interface ExtractResult {
  success: boolean;
  modelId: string;
  destinationPath: string;
  filesExtracted: number;
  error?: string;
}

/**
 * Model source type for fact sheet configuration.
 */
export type ModelSourceType = 'default' | 'archive' | 'registry';

// ═══════════════════════════════════════════════════════════════════════════════
// MODEL CATALOG TYPES (for Archive Assembly)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Model type classification.
 */
export type CatalogModelType = 'dense_encoder' | 'sparse_encoder' | 'cross_encoder';

/**
 * RAG pipeline category.
 */
export type RagPipelineCategory = 'retrieval' | 'reranking';

/**
 * Comprehensive model information from the built-in catalog.
 */
export interface BuiltInModelInfo {
  /** Model ID (e.g., "bge-base-en-v1.5") */
  modelId: string;
  /** Model type: "dense_encoder", "sparse_encoder", "cross_encoder" */
  modelType: CatalogModelType;
  /** RAG pipeline category: "retrieval" or "reranking" */
  category: RagPipelineCategory;
  /** Human-readable description */
  description: string | null;
  /** Framework (e.g., "samediff", "onnx") */
  framework: string | null;
  /** Model version */
  version: string | null;
  /** Embedding dimension (for encoders) */
  embeddingDim: number | null;
  /** Maximum sequence length */
  maxSequenceLength: number | null;
  /** Hidden layer size (for cross-encoders) */
  hiddenSize: number | null;
  /** Number of transformer layers */
  numLayers: number | null;
  /** Training dataset (e.g., "ms-marco-passage") */
  trainingData: string | null;
  /** Input format (e.g., "query [SEP] document") */
  inputFormat: string | null;
  /** Output type (e.g., "relevance_score", "similarity_score") */
  outputType: string | null;
  /** Tokenizer type (e.g., "bert", "roberta") */
  tokenizerType: string | null;
  /** Whether tokenizer lowercases text */
  doLowerCase: boolean;
  /** Whether tokenizer strips accents */
  stripAccents: boolean;
  /** URL to download model file */
  downloadUrl: string | null;
  /** URL to download vocabulary file */
  vocabUrl: string | null;
  /** HuggingFace source URL */
  huggingfaceSource: string | null;
  /** Supported languages (e.g., "multilingual") */
  languages: string | null;
}

/**
 * Complete model catalog grouped by RAG pipeline phase.
 */
export interface BuiltInModelCatalog {
  /** Dense encoder models (for semantic/vector retrieval) */
  denseEncoders: BuiltInModelInfo[];
  /** Sparse encoder models (for learned sparse/lexical retrieval) */
  sparseEncoders: BuiltInModelInfo[];
  /** Cross-encoder models (for reranking) */
  crossEncoders: BuiltInModelInfo[];
  /** Count summary */
  counts: {
    denseEncoders: number;
    sparseEncoders: number;
    crossEncoders: number;
    total: number;
  };
}

/**
 * Request to assemble a custom archive with selected models.
 */
export interface AssembleArchiveRequest {
  /** Custom archive ID (optional, auto-generated if not provided) */
  archiveId?: string;
  /** Archive display name */
  archiveName?: string;
  /** Archive description */
  description?: string;
  /** Archive version */
  version?: string;
  /** Dense encoder model IDs to include */
  denseEncoderIds?: string[];
  /** Sparse encoder model IDs to include */
  sparseEncoderIds?: string[];
  /** Cross-encoder model IDs to include */
  crossEncoderIds?: string[];
}

/**
 * Response from archive assembly request.
 */
export interface AssembleArchiveResponse {
  /** Whether assembly was successful */
  success: boolean;
  /** Generated archive ID */
  archiveId: string | null;
  /** Path where archive will be created */
  archivePath: string | null;
  /** Total size of included models in bytes */
  totalSizeBytes: number;
  /** Number of models included */
  modelCount: number;
  /** List of included model IDs */
  includedModelIds: string[];
  /** Detailed info about included models */
  includedModels: BuiltInModelInfo[];
  /** Error message if failed */
  error: string | null;
}

/**
 * Helper function to get icon for model type.
 */
export function getModelTypeIcon(modelType: CatalogModelType): string {
  switch (modelType) {
    case 'dense_encoder': return 'memory';
    case 'sparse_encoder': return 'scatter_plot';
    case 'cross_encoder': return 'swap_horiz';
    default: return 'extension';
  }
}

/**
 * Helper function to get display name for model type.
 */
export function getModelTypeDisplayName(modelType: CatalogModelType): string {
  switch (modelType) {
    case 'dense_encoder': return 'Dense Encoder';
    case 'sparse_encoder': return 'Sparse Encoder';
    case 'cross_encoder': return 'Cross-Encoder';
    default: return 'Unknown';
  }
}

/**
 * Helper function to get description for model type.
 */
export function getModelTypeDescription(modelType: CatalogModelType): string {
  switch (modelType) {
    case 'dense_encoder':
      return 'Transforms text into dense vector embeddings for semantic similarity search.';
    case 'sparse_encoder':
      return 'Creates sparse term-weighted representations for lexical matching with learned weights.';
    case 'cross_encoder':
      return 'Jointly encodes query-document pairs to compute relevance scores for reranking.';
    default:
      return '';
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STAGING ARCHIVE EXPORT TYPES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Request to export models to a staging archive.
 * Sent to /api/staging/archives/export
 */
export interface StagingArchiveExportRequest {
  /** Model IDs to include in the archive */
  modelIds: string[];
  /** Output file path (optional) */
  outputPath?: string;
  /** Archive identifier */
  archiveId?: string;
  /** Archive version */
  version?: string;
  /** Archive description */
  description?: string;
  /** Publisher name */
  publisherName?: string;
  /** Publisher URL */
  publisherUrl?: string;
  /** Minimum Kompile version compatibility */
  minKompileVersion?: string;
  /** Export all models in registry */
  exportAll?: boolean;
}

/**
 * Response from staging archive export.
 */
export interface StagingArchiveExportResponse {
  success: boolean;
  archivePath?: string;
  archiveId?: string;
  version?: string;
  modelCount?: number;
  archiveSize?: number;
  checksum?: string;
  error?: string;
}

// ═══════════════════════════════════════════════════════════════════════════════
// BACKUP MANAGEMENT TYPES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Status of the backup service.
 */
export interface BackupStatus {
  enabled: boolean;
  inProgress: boolean;
  lastBackupTime?: string;
  backupPath: string;
  retentionDays: number;
  format: 'COMPRESSED' | 'DIRECTORY';
  intervalHours: number;
  lastResult?: BackupResult;
  status: string;
}

/**
 * Result of a backup operation.
 */
export interface BackupResult {
  success: boolean;
  message: string;
  backupPath?: string;
  fileCount: number;
  totalMB: number;
  durationMs: number;
  errors?: string[];
  status?: string;
}

/**
 * Information about a single backup.
 */
export interface BackupInfo {
  name: string;
  path: string;
  createdAt: string;
  sizeMB: number;
  format: string;
}

/**
 * Response from listing backups.
 */
export interface BackupListResponse {
  backups: BackupInfo[];
  count: number;
  totalSizeMB: number;
  status: string;
}

/**
 * Result of a restore operation.
 */
export interface RestoreResult {
  success: boolean;
  message: string;
  durationMs: number;
  errors?: string[];
  status: string;
}

/**
 * Response from backup cleanup operation.
 */
export interface BackupCleanupResponse {
  deletedCount: number;
  message: string;
  status: string;
}

/**
 * Response from delete backup operation.
 */
export interface DeleteBackupResponse {
  deleted: string;
  message: string;
  status: string;
}

// ==================== VLM Test Workflow ====================

export interface VlmTestStartResponse {
  taskId: string;
  fileName: string;
  status: string;
  modelId: string;
  outputFormat: string;
}

export interface VlmTestStatusResponse {
  taskId: string;
  status: string;
  progressPercent: number;
  currentPhase: string;
  pagesCompleted?: number;
  message?: string;
}

export interface VlmTestPageResult {
  pageNumber: number;
  text: string;
  rawDocTags?: string;
  success: boolean;
  processingTimeMs: number;
  generatedTokens?: number;
  tokensPerSecond?: number;
  error?: string;
}

export interface VlmTestPerformance {
  totalProcessingTimeMs: number;
  modelLoadTimeMs: number;
  totalPages: number;
  totalGeneratedTokens: number;
  avgTokensPerSecond: number;
  startHeapUsedBytes: number;
  endHeapUsedBytes: number;
  peakMemoryBytes: number;
  phaseDurations: { [key: string]: number };
}

export interface VlmTestResultResponse {
  taskId: string;
  filePath: string;
  status: string;
  totalTimeMs: number;
  pages?: VlmTestPageResult[];
  performance?: VlmTestPerformance;
  phaseDurations?: { [key: string]: number };
  errorMessage?: string;
}

export interface VlmTestProgressMessage {
  taskId: string;
  status: string;
  progressPercent: number;
  currentPhase: string;
  message?: string;
  result?: VlmTestResultResponse;
}

export interface VlmTestSubprocessConfig {
  heapSize: string;
  offHeapMultiplier: number;
  timeoutMinutes: number;
  javaPath: string;
  cudaPinnedHostLimitMb?: number;
}

export interface VlmTestSubprocessConfigUpdate {
  heapSize?: string;
  offHeapMultiplier?: number;
  timeoutMinutes?: number;
  javaPath?: string;
  vlmCudaPinnedHostLimitMb?: number;
}

export interface KompileLocalModelStatus {
  connected: boolean;
  stagingUrl: string;
  modelId?: string;
  modelLoaded: boolean;
  agentRegistered: boolean;
  message: string;
}

// ═══════════════════════════════════════════════════════════════════════════════
// ACTIVE MODEL CONTEXT
// ═══════════════════════════════════════════════════════════════════════════════

export interface ActiveModelContext {
  embedding: EmbeddingContext | null;
  reranker: RerankerContext | null;
  staging: StagingContext | null;
  availableEmbeddingModels: string[];
  availableRerankerModels: string[];
}

export interface EmbeddingContext {
  modelId: string;
  encoderType: string;
  dimensions: number;
  status: string;
  initialized: boolean;
}

export interface RerankerContext {
  modelId: string;
  available: boolean;
}

export interface StagingContext {
  connected: boolean;
  endpointUrl: string | null;
  uiUrl: string | null;
}

// ═══════════════════════════════════════════════════════════════════════════════
// TOOL PERMISSIONS
// ═══════════════════════════════════════════════════════════════════════════════

export type PermissionLevel = 'ALLOW' | 'DENY';

export interface ToolPermissionConfig {
  defaultPermission: PermissionLevel;
  categoryRules: { [category: string]: PermissionLevel };
  toolRules: { [toolName: string]: PermissionLevel };
}

export interface ToolPermissionStatus {
  defaultPermission: PermissionLevel;
  categories: { [key: string]: CategoryPermissionInfo };
  tools: ToolPermissionInfo[];
}

export interface CategoryPermissionInfo {
  displayName: string;
  permission: PermissionLevel | null;
  toolCount: number;
}

export interface ToolPermissionInfo {
  name: string;
  category: string;
  description: string;
  resolvedPermission: PermissionLevel;
  hasOverride: boolean;
}
