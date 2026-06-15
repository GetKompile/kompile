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

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export type MetaToolMode = 'DIRECT' | 'DYNAMIC' | 'HYBRID';

export interface ToolOverride {
  compressionEnabled?: boolean | null;
  maxResponseChars?: number | null;
  exposeInDynamicMode?: boolean | null;
}

export interface McpOptimizationConfig {
  enabled?: boolean | null;
  ragMaxContentChars?: number | null;
  ragMaxDocs?: number | null;
  filesystemStorePreviousContentInCache?: boolean | null;
  filesystemUndoTtlSeconds?: number | null;
  knowledgeGraphTruncateChars?: number | null;
  compressionThresholdChars?: number | null;
  resultCacheMaxEntries?: number | null;
  resultCacheTtlSeconds?: number | null;
  metaToolMode?: MetaToolMode | null;
  alwaysExposedTools?: string[] | null;
  toolOverrides?: { [name: string]: ToolOverride } | null;
}

export interface McpOptimizationResponse extends McpOptimizationConfig {
  configFilePath?: string;
  message?: string;
}

/**
 * Service for managing MCP token-saving optimization configuration.
 */
@Injectable({ providedIn: 'root' })
export class McpOptimizationService {
  private readonly baseUrl = `${backendUrl}/config/mcp-optimization`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<McpOptimizationResponse> {
    return this.http.get<McpOptimizationResponse>(this.baseUrl);
  }

  updateConfig(config: McpOptimizationConfig): Observable<McpOptimizationResponse> {
    return this.http.put<McpOptimizationResponse>(this.baseUrl, config);
  }

  resetConfig(): Observable<McpOptimizationResponse> {
    return this.http.post<McpOptimizationResponse>(`${this.baseUrl}/reset`, {});
  }

  defaults(): McpOptimizationConfig {
    return {
      enabled: true,
      ragMaxContentChars: 2000,
      ragMaxDocs: 3,
      filesystemStorePreviousContentInCache: true,
      filesystemUndoTtlSeconds: 3600,
      knowledgeGraphTruncateChars: 200,
      compressionThresholdChars: 4000,
      resultCacheMaxEntries: 1000,
      resultCacheTtlSeconds: 900,
      metaToolMode: 'HYBRID',
      alwaysExposedTools: [],
      toolOverrides: {}
    };
  }
}
