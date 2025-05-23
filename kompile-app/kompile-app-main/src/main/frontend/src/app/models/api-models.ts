/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
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

// For AddSourceDialogComponent communication
export interface AddSourceDialogResult {
  file?: File;
  url?: string;
  fileName?: string;
  selectedLoader?: string;
  rebuildIndex?: boolean; // Added flag for optional rebuild
}
