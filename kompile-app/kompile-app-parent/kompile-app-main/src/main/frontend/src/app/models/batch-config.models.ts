/**
 * Batch Size Configuration Models
 * ================================
 *
 * This file defines models for two distinct types of batch sizes in the document processing pipeline:
 *
 * 1. **EMBEDDING BATCH SIZE (NDArray/Inference Batch)**
 *    - Purpose: Controls how many text chunks are batched together for a single forward pass
 *      through the neural network embedding model.
 *    - Impact: Larger batches = higher GPU/CPU throughput but more memory usage
 *    - Typical values: 8-64 depending on available memory
 *    - Configured per embedding model via BatchSizeConfigRequest/Response
 *    - Use the Batch Size Benchmark tool to find optimal settings for your hardware
 *
 * 2. **INDEX BATCH SIZE (Document/Chunk Processing Batch)**
 *    - Purpose: Controls how many documents/chunks are batched together when writing to
 *      the Lucene vector index.
 *    - Impact: Larger batches = faster indexing but more memory for buffering
 *    - Typical values: 50-200 depending on chunk size and memory
 *    - Configured globally via ProcessingSettings (see api-models.ts)
 *
 * Key Difference:
 * - Embedding batch size affects MODEL INFERENCE (neural network forward pass)
 * - Index batch size affects STORAGE OPERATIONS (Lucene writes)
 *
 * Both should be tuned based on your hardware (memory, GPU availability) and workload.
 */

/**
 * Request for batch size benchmark testing.
 * Used to test different embedding (inference) batch sizes to find optimal settings.
 */
export interface BatchSizeTestRequest {
  /** Model identifier (e.g., "bge-base-en-v1.5"). If null, uses the default loaded model. */
  modelId?: string;
  /** Custom sample texts for testing. If null or empty, uses default test texts. */
  sampleTexts?: string[];
  /** Batch sizes to test (e.g., [1, 2, 4, 8, 16, 32]). */
  batchSizesToTest: number[];
  /** Number of iterations per batch size (default: 3). */
  iterations: number;
  /** Number of warmup iterations to exclude from results (default: 1). */
  warmupIterations: number;
  /** Maximum time in seconds for each batch size test (default: 30). */
  timeoutSeconds: number;
}

/**
 * Result for a single batch size test.
 */
export interface BatchSizeTestResult {
  /** The batch size that was tested. */
  batchSize: number;
  /** Average time in milliseconds across all iterations. */
  avgTimeMs: number;
  /** Minimum time in milliseconds. */
  minTimeMs: number;
  /** Maximum time in milliseconds. */
  maxTimeMs: number;
  /** Standard deviation of time in milliseconds. */
  stdDevMs: number;
  /** Tokens processed per second. */
  tokensPerSecond: number;
  /** Documents processed per second. */
  documentsPerSecond: number;
  /** Memory used in bytes during test. */
  memoryUsedBytes: number;
  /** Peak memory in bytes during test. */
  peakMemoryBytes: number;
  /** Total tokens processed across all iterations. */
  totalTokensProcessed: number;
  /** Total documents processed across all iterations. */
  totalDocumentsProcessed: number;
  /** Whether the test completed successfully. */
  success: boolean;
  /** Error message if test failed. */
  errorMessage?: string;
}

/**
 * Response for complete batch size benchmark test.
 */
export interface BatchSizeTestResponse {
  /** Model identifier that was tested. */
  modelId: string;
  /** Display name of the model. */
  modelName: string;
  /** Embedding dimensions of the model. */
  embeddingDimensions: number;
  /** Results for each batch size tested. */
  results: BatchSizeTestResult[];
  /** Recommended batch size based on throughput/memory balance. */
  recommendedBatchSize: number;
  /** Largest batch size that didn't fail. */
  maxSafeBatchSize: number;
  /** Total test duration in milliseconds. */
  testDurationMs: number;
  /** System information (CPU, memory, etc.). */
  systemInfo: string;
  /** Number of sample texts used in the test. */
  sampleTextCount: number;
  /** Average sequence length of sample texts. */
  avgSequenceLength: number;
}

/**
 * Request for updating batch size configuration.
 * All fields are optional - only non-null fields will be updated.
 */
export interface BatchSizeConfigRequest {
  /** Model identifier. If null, applies configuration globally. */
  modelId?: string;
  /** Optimal batch size for the model. */
  optimalBatchSize?: number;
  /** Maximum batch size for the model. */
  maxBatchSize?: number;
  /** Memory scale factor (-1 for auto-detect). */
  memoryScaleFactor?: number;
  /** Whether to persist changes to application.properties. */
  persistToConfig?: boolean;
}

/**
 * Response for embedding batch size configuration.
 *
 * This configures the INFERENCE batch size - how many text chunks are processed
 * together in a single forward pass through the embedding model.
 *
 * Recommended defaults based on available memory:
 * - < 4GB RAM:  optimal=4,  max=8
 * - 4-8GB RAM:  optimal=8,  max=16
 * - 8-16GB RAM: optimal=16, max=32
 * - > 16GB RAM: optimal=32, max=64
 */
export interface BatchSizeConfigResponse {
  /** Model identifier (e.g., "bge-base-en-v1.5"). */
  modelId: string;
  /** Current optimal batch size for inference. This is used during normal operations. */
  currentOptimalBatchSize: number;
  /** Maximum batch size for inference. Never exceeded even under high memory conditions. */
  currentMaxBatchSize: number;
  /** Absolute maximum batch size allowed by the system (typically 128). */
  absoluteMaxBatchSize: number;
  /** Memory scale factor (-1 means auto-detect based on available heap). */
  memoryScaleFactor: number;
  /** Whether memory scaling is automatic (true when memoryScaleFactor is -1). */
  isAutoScaled: boolean;
  /** Available JVM heap memory in MB. */
  availableMemoryMb: number;
  /** Effective batch size after applying memory scaling to optimal batch size. */
  calculatedEffectiveBatchSize: number;
  /** Whether this model has a runtime override (vs using global defaults). */
  hasRuntimeOverride: boolean;
}

/**
 * Information about an embedding model.
 */
export interface EmbeddingModelInfo {
  /** Model identifier (e.g., "bge-base-en-v1.5"). */
  modelId: string;
  /** Human-readable display name. */
  displayName: string;
  /** Embedding vector dimensions. */
  dimensions: number;
  /** Model type (DENSE, SPARSE). */
  modelType: string;
  /** Whether the model is currently loaded. */
  isLoaded: boolean;
  /** Implementation class name. */
  implementationClass?: string;
  /** Current batch size configuration for this model. */
  batchConfig: BatchSizeConfigResponse;
}

/**
 * Status response from the batch config service.
 */
export interface BatchConfigStatus {
  status: 'ok' | 'error';
  totalModels: number;
  loadedModels: number;
  availableModels: number;
  message?: string;
}
