/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * TypeScript models for Knowledge Graph Builder
 */

// ==================== Enums and Types ====================

export type GraphBuilderType = 'MANUAL' | 'LLM' | 'PATTERN' | 'HYBRID';

export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type ProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

// ==================== Builder Configuration ====================

/**
 * Configuration for a knowledge graph builder
 */
export interface BuilderConfig {
  modelProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  entityTypes?: string[];
  minConfidence?: number;
  autoAccept?: boolean;
  autoAcceptThreshold?: number;
  customPrompt?: string;
  batchSize?: number;
}

/**
 * Information about an available builder
 */
export interface GraphBuilderInfo {
  id: string;
  displayName: string;
  type: GraphBuilderType;
  description?: string;
  configSchema?: Record<string, any>;
  supportsExtractionLog: boolean;
  supportsConcurrentIndexing: boolean;
}

// ==================== Extraction Jobs ====================

/**
 * Extraction job response from the API
 */
export interface ExtractionJob {
  jobId: string;
  factSheetId: number;
  builderType: string;
  status: JobStatus;
  totalChunks?: number;
  processedChunks?: number;
  proposalsCreated?: number;
  proposalsAccepted?: number;
  proposalsRejected?: number;
  progressPercent?: number;
  createdAt?: string;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
}

/**
 * Request to start an extraction job
 */
export interface StartJobRequest {
  factSheetId: number;
  builderType: string;
  config?: BuilderConfig;
  chunkIds?: string[];
}

/**
 * Job statistics
 */
export interface JobStatistics {
  totalChunks: number;
  processedChunks: number;
  successfulChunks: number;
  failedChunks: number;
  proposalsCreated: number;
  proposalsAccepted: number;
  proposalsRejected: number;
  proposalsPending: number;
  averageLatencyMs: number;
  totalTokens: number;
}

// ==================== Extraction Logs ====================

/**
 * Extraction log record showing full LLM interaction
 */
export interface ExtractionLog {
  id: number;
  chunkId: string;
  documentId?: string;
  promptText: string;
  responseText?: string;
  inputText?: string;
  parsedEntitiesJson?: string;
  parsedRelationshipsJson?: string;
  entitiesCount?: number;
  relationshipsCount?: number;
  modelProvider?: string;
  modelName?: string;
  latencyMs?: number;
  promptTokens?: number;
  responseTokens?: number;
  success: boolean;
  errorMessage?: string;
  createdAt?: string;
}

/**
 * Parsed entity from extraction log
 */
export interface ParsedEntity {
  id: string;
  title: string;
  label: string;
  description?: string;
  metadata?: Record<string, any>;
}

/**
 * Parsed relationship from extraction log
 */
export interface ParsedRelationship {
  source: string;
  target: string;
  type: string;
  description?: string;
  confidence?: number;
  metadata?: Record<string, any>;
}

// ==================== Triple Proposals ====================

/**
 * A proposed triple (subject-predicate-object) for review
 */
export interface TripleProposal {
  proposalId: string;
  jobId?: string;
  factSheetId: number;
  subjectName: string;
  subjectType?: string;
  predicateName: string;
  objectName: string;
  objectType?: string;
  confidence: number;
  status: ProposalStatus;
  sourceChunkId?: string;
  sourceDocumentId?: string;
  sourceContext?: string;
  createdAt?: string;
  reviewedAt?: string;
  reviewedBy?: string;
  rejectionReason?: string;
  subjectNodeId?: string;
  objectNodeId?: string;
  edgeId?: string;
}

/**
 * Request to create a manual proposal
 */
export interface ManualProposalRequest {
  factSheetId: number;
  subjectName: string;
  subjectType?: string;
  predicateName: string;
  objectName: string;
  objectType?: string;
  description?: string;
  autoAccept?: boolean;
}

/**
 * Request to reject a proposal
 */
export interface RejectRequest {
  reason?: string;
  reviewedBy?: string;
}

/**
 * Request for bulk accept/reject
 */
export interface BulkActionRequest {
  proposalIds: string[];
  reviewedBy?: string;
  storageType?: string;
}

/**
 * Request for bulk reject with reason
 */
export interface BulkRejectRequest {
  proposalIds: string[];
  reviewedBy?: string;
  reason?: string;
}

// ==================== Paginated Responses ====================

/**
 * Spring Data Page wrapper
 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

// ==================== UI Helpers ====================

/**
 * Filter options for proposals list
 */
export interface ProposalFilter {
  jobId?: string;
  factSheetId?: number;
  status?: ProposalStatus;
  query?: string;
  minConfidence?: number;
}

/**
 * Status color mapping
 */
export const PROPOSAL_STATUS_COLORS: Record<ProposalStatus, string> = {
  PENDING: '#FF9800',   // Orange
  ACCEPTED: '#4CAF50',  // Green
  REJECTED: '#F44336'   // Red
};

/**
 * Job status color mapping
 */
export const JOB_STATUS_COLORS: Record<JobStatus, string> = {
  PENDING: '#9E9E9E',    // Grey
  RUNNING: '#2196F3',    // Blue
  COMPLETED: '#4CAF50',  // Green
  FAILED: '#F44336',     // Red
  CANCELLED: '#FF9800'   // Orange
};

/**
 * Builder type icons
 */
export const BUILDER_TYPE_ICONS: Record<GraphBuilderType, string> = {
  MANUAL: 'edit',
  LLM: 'psychology',
  PATTERN: 'pattern',
  HYBRID: 'merge_type'
};

/**
 * Default entity types for LLM extraction
 */
export const DEFAULT_ENTITY_TYPES = [
  'PERSON',
  'ORGANIZATION',
  'LOCATION',
  'CONCEPT',
  'EVENT',
  'PRODUCT',
  'TECHNOLOGY'
];
