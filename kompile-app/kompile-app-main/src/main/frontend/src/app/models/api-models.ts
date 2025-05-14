/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

export interface DocumentSource {
  // Assuming the backend returns a list of strings for sources
  source: string;
}

export interface UploadedFile {
  name: string;
  // Add other properties if your backend provides more
}

export interface AnseriniHit {
  // Define based on what anseriniRetriever.search actually returns (List<String>)
  // For now, let's assume it's just a string content
  content: string;
}

export interface AnseriniSearchResponse {
  query: string;
  maxResults: number;
  hits?: AnseriniHit[]; // Or string[]
  error?: string;
}

export interface McpToolInfo {
  name: string;
  description: string;
  note?: string; // "Full input schema available via MCP tools/list protocol."
  inputSchemaError?: string;
}

export interface AddUrlRequest {
  url: string;
  fileName?: string;
}

export interface FileUploadResponse {
  message: string;
  path?: string;
  next_step?: string;
  error?: string;
}

export interface SimpleMessageResponse {
  message?: string;
  error?: string;
}
