/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

export interface RagQuery {
  query: string;
  useToolCalling?: boolean;
}

export interface RagResponse {
  query: string;
  answer?: string;
  error?: string;
}

// --- Document Management Models ---
export interface AddUrlRequest {
  url: string;
  fileName?: string;
  loader?: string; // Optional loader name
}

export interface FileUploadResponse {
  message: string;
  path?: string;
  next_step?: string;
  error?: string;
  fileName?: string;
  selectedLoader?: string;
}

export interface SimpleMessageResponse {
  message?: string;
  error?: string;
}

export interface LoaderInfo {
  name: string;
  className: string;
}

export enum DocumentSourceType {
  URL = 'URL',
  FILE = 'FILE',
  DIRECTORY = 'DIRECTORY'
  // Add other types if they exist in backend and are needed by frontend
}

export interface BatchLoadRequestItem {
  pathOrUrl: string;
  type: DocumentSourceType;
  loaderName?: string;
  originalFileName?: string;
}

export interface BatchProcessRequest {
  items: BatchLoadRequestItem[];
  defaultLoaderName?: string;
}

export interface DocumentSummary {
  id: string;
  metadata: {[key: string]: any};
  contentSnippet: string; // Or use 'textSnippet' if it aligns with doc.getText()
}

export interface BatchProcessResultItem {
  count?: number;
  summaries?: DocumentSummary[];
  error?: string;
}

export interface BatchProcessResponseDetails {
  [pathOrUrl: string]: BatchProcessResultItem;
}

export interface BatchProcessResponse {
  message: string;
  successful_items: number;
  failed_items: number;
  details?: BatchProcessResponseDetails;
  error?: string;
}

// --- Anserini Models (Re-adding) ---
export interface AnseriniHit {
  content: string; // Assuming string content for hits
  // Add other properties like 'docid', 'score' if available and needed
}

export interface AnseriniSearchResponse {
  query: string;
  maxResults: number;
  hits?: AnseriniHit[];
  error?: string;
}

// --- MCP Tool Models (Re-adding) ---
export interface McpToolInfo {
  name: string;
  description: string;
  note?: string; // e.g., "Full input schema available via MCP tools/list protocol."
  inputSchemaError?: string; // If fetching schema fails
  // Potentially add inputSchema?: any; if you plan to fetch and display it
}

// Other existing models if any (e.g., DocumentSource, UploadedFile)
// can remain if they are still in use and correctly defined.
// For example:
export interface UploadedFile { // If this is still used for listing uploaded files.
  name: string;
  // Add other properties if your backend provides more for the /uploaded-files endpoint.
}
