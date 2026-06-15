/**
 * TypeScript interfaces for the Chunk Manager feature.
 * These models correspond to the backend DTOs in ChunkManagerDtos.java.
 */

/**
 * Detailed information about a single chunk.
 * Now includes location info showing which indexes contain the chunk.
 */
export interface ChunkDetail {
  id: string;
  content: string;
  contentLength: number;
  metadata: Record<string, any>;
  sourceId?: string;
  sourceFilename?: string;
  chunkIndex?: number;
  totalChunks?: number;
  pageNumber?: number;
  indexedAt?: string;
  /** Whether this chunk exists in the keyword index */
  inKeywordIndex: boolean;
  /** Whether this chunk exists in the vector store */
  inVectorStore: boolean;
}

/**
 * Summary information for chunk list views.
 * Now includes location info showing which indexes contain the chunk.
 */
export interface ChunkSummary {
  id: string;
  preview: string;
  contentLength: number;
  sourceId?: string;
  sourceFilename?: string;
  chunkIndex?: number;
  /** Whether this chunk exists in the keyword index */
  inKeywordIndex: boolean;
  /** Whether this chunk exists in the vector store */
  inVectorStore: boolean;
}

/**
 * Statistics about index counts.
 */
export interface IndexStats {
  keywordIndexCount: number;
  vectorStoreCount: number;
}

/**
 * Response for paginated chunk listings.
 * Now includes index statistics.
 */
export interface ChunkListResponse {
  chunks: ChunkSummary[];
  offset: number;
  limit: number;
  totalCount: number;
  pageCount: number;
  indexStats?: IndexStats;
}

/**
 * A group of duplicate chunks.
 */
export interface DuplicateGroup {
  groupKey: string;
  strategy: string;
  duplicates: ChunkSummary[];
  keepId: string;
}

/**
 * Response containing duplicate analysis results.
 */
export interface DuplicateAnalysisResponse {
  strategy: string;
  totalDuplicateGroups: number;
  totalDuplicateChunks: number;
  chunksToRemove: number;
  groups: DuplicateGroup[];
}

/**
 * Result of a deduplication operation.
 */
export interface DeduplicationResult {
  strategy: string;
  duplicateGroupsFound: number;
  chunksRemoved: number;
  chunksKept: number;
  success: boolean;
  message: string;
}

/**
 * Request for deduplication operations.
 */
export interface DeduplicationRequest {
  strategy: 'content_hash' | 'source_and_index';
  keepPolicy: 'first' | 'latest';
  dryRun: boolean;
}

/**
 * Request for exporting chunks.
 */
export interface ExportRequest {
  chunkIds?: string[];
  sourceId?: string;
  includeMetadata: boolean;
  format: 'markdown';
}

/**
 * Response for chunk export operations.
 */
export interface ExportResponse {
  format: string;
  content: string;
  chunkCount: number;
  filename: string;
}

/**
 * Request for deleting chunks by source.
 */
export interface DeleteBySourceRequest {
  sourceId: string;
}

/**
 * Request for clearing all chunks.
 */
export interface ClearAllRequest {
  confirmationToken: string;
}

/**
 * Generic operation response.
 */
export interface OperationResponse {
  success: boolean;
  message: string;
  affectedCount: number;
}

/**
 * Information about a source document.
 * Now includes counts from both indexes.
 */
export interface SourceInfo {
  sourceId: string;
  filename: string;
  chunkCount: number;
  keywordChunkCount: number;
  vectorChunkCount: number;
}

/**
 * Response listing all source documents.
 */
export interface SourceListResponse {
  sources: SourceInfo[];
  totalSources: number;
}

/**
 * Token response for clear all confirmation.
 */
export interface ClearTokenResponse {
  token: string;
  expiresIn: number;
  message: string;
}

/**
 * Deduplication strategy options.
 */
export type DeduplicationStrategy = 'content_hash' | 'source_and_index';

/**
 * Keep policy options for deduplication.
 */
export type KeepPolicy = 'first' | 'latest';

// ═══════════════════════════════════════════════════════════════════════════
// CHUNK EDITING INTERFACES
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Entity extracted from a chunk.
 */
export interface ChunkEntityDto {
  name: string;
  type: string;
  confidence?: number;
  startOffset?: number;
  endOffset?: number;
}

/**
 * Request for updating a chunk's content and metadata.
 */
export interface ChunkUpdateRequest {
  content?: string;
  semanticType?: string;
  sourceTitle?: string;
  sourceAuthor?: string;
  sourceDate?: string;
  sourceUrl?: string;
  entities?: ChunkEntityDto[];
  metadata?: Record<string, any>;
}

/**
 * Detailed chunk information for editing.
 * Extends ChunkDetail with additional editable fields.
 */
export interface ChunkEditDetail extends ChunkDetail {
  semanticType?: string;
  sourceTitle?: string;
  sourceAuthor?: string;
  sourceDate?: string;
  sourceUrl?: string;
  sourcePath?: string;
  entities?: ChunkEntityDto[];
  entityCount?: number;
  relationCount?: number;
}

/**
 * Response for chunk update operations.
 */
export interface ChunkUpdateResponse {
  success: boolean;
  message: string;
  updatedChunk?: ChunkEditDetail;
}

/**
 * Available semantic types for chunks.
 */
export const SEMANTIC_TYPES: string[] = [
  'TEXT', 'DEFINITION', 'EXPLANATION', 'PROCEDURE', 'EXAMPLE',
  'SUMMARY', 'FACT', 'OPINION', 'QUESTION', 'ANSWER',
  'CODE', 'TABLE', 'LIST', 'HEADER', 'QUOTE',
  'REFERENCE', 'METADATA', 'WARNING', 'NOTE', 'CONCLUSION', 'UNKNOWN'
];

/**
 * Available entity types for chunks.
 */
export const ENTITY_TYPES: string[] = [
  'PERSON', 'ORGANIZATION', 'LOCATION', 'DATE', 'TIME',
  'MONEY', 'PERCENT', 'PRODUCT', 'EVENT', 'WORK_OF_ART',
  'LAW', 'LANGUAGE', 'TECHNOLOGY', 'CONCEPT', 'QUANTITY',
  'URL', 'EMAIL', 'CODE', 'API', 'FUNCTION', 'CLASS', 'CUSTOM', 'UNKNOWN'
];
