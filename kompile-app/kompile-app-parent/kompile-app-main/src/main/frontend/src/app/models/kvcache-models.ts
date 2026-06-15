export interface KVCacheConfig {
  type?: string;
  blockSize?: number;
  maxBatchSize?: number;
  maxSeqLen?: number;
  numKvHeads?: number;
  headDim?: number;
  dataType?: string;
  poolSizeFactor?: number;
  evictionPolicy?: string;
  tokenBudget?: number;
  quantFormat?: string;
  turboQuantBits?: number;
  tieredEnabled?: boolean;
  gpuPressureThreshold?: number;
  hostPoolMaxBlocks?: number;
  diskOffloadPath?: string;
}

export interface KVCacheSummary {
  name: string;
  type: string;
  createdAt: number;
  memoryUsageBytes: number;
  activeSequences: number;
  freeBlocks: number;
  totalBlocks: number;
}

export interface KVCacheStats {
  cacheName: string;
  cacheType: string;
  totalAppends: number;
  totalEvictions: number;
  totalFrees: number;
  hitCount: number;
  missCount: number;
  hitRate: number;
  memoryUsedBytes: number;
  memoryCapacityBytes: number;
  memoryUtilization: number;
  activeSequences: number;
  freeBlocks: number;
  totalBlocks: number;
  perSequenceTokenCounts?: { [seqIdx: number]: number };
  recentSamples?: StatsSample[];
  collectedAt: number;
}

export interface StatsSample {
  timestamp: number;
  memoryUsedBytes: number;
  activeSequences: number;
  appendsPerSecond: number;
  evictionsPerSecond: number;
}

export interface CheckpointInfo {
  id: string;
  label: string;
  createdAt: number;
  tokenCount: number;
  sizeBytes: number;
  onDisk: boolean;
  diskPath?: string;
}

export interface PrefixEntry {
  prefixHash: string;
  tokenCount: number;
  blockCount: number;
  accessCount: number;
  lastAccessed: number;
}

export interface PrefixCacheStats {
  totalEntries: number;
  maxEntries: number;
  totalLookups: number;
  totalHits: number;
  hitRate: number;
}

export interface KVCacheStatus {
  enabled: boolean;
  cacheCount: number;
  checkpointsEnabled: boolean;
  prefixCacheEnabled: boolean;
}

export interface KVCacheProperties {
  enabled: boolean;
  defaultType: string;
  blockSize: number;
  maxBatchSize: number;
  maxSeqLen: number;
  numKvHeads: number;
  headDim: number;
  dataType: string;
  poolSizeFactor: number;
  evictionPolicy: string;
  tokenBudget: number;
  quantFormat: string;
  turboQuantBits: number;
  tieredEnabled: boolean;
  gpuPressureThreshold: number;
  hostPoolMaxBlocks: number;
  diskOffloadPath: string;
  prefixCacheEnabled: boolean;
  prefixCacheMaxEntries: number;
  checkpointEnabled: boolean;
  maxCheckpoints: number;
  checkpointDir: string;
  statsWindowSeconds: number;
}
