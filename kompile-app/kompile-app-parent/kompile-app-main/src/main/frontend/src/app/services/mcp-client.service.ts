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

export interface McpConnection {
  name: string;
  sseUrl: string;
  messageUrl: string;
}

export interface McpTool {
  name: string;
  description: string;
  inputSchema: any;
}

export interface McpResource {
  uri: string;
  name: string;
  description: string;
  mimeType: string;
}

export interface McpPrompt {
  name: string;
  description: string;
  arguments: any;
}

export interface McpToolCallResult {
  serverName: string;
  toolName: string;
  isError: boolean;
  content: McpContentItem[];
}

export interface McpContentItem {
  type: 'text' | 'image' | 'resource' | 'unknown';
  text?: string;
  data?: string;
  mimeType?: string;
  resource?: any;
  value?: string;
}

@Injectable({
  providedIn: 'root'
})
export class McpClientService {
  private readonly apiUrl = `${backendUrl}/mcp/client`;

  constructor(private http: HttpClient) {}

  connect(name: string, sseUrl: string, messageUrl?: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/connect`, { name, sseUrl, messageUrl });
  }

  disconnect(name: string): Observable<any> {
    return this.http.post(`${this.apiUrl}/disconnect/${name}`, {});
  }

  listConnections(): Observable<{ connections: McpConnection[], count: number }> {
    return this.http.get<{ connections: McpConnection[], count: number }>(`${this.apiUrl}/connections`);
  }

  listTools(serverName: string): Observable<{ serverName: string, tools: McpTool[], count: number }> {
    return this.http.get<{ serverName: string, tools: McpTool[], count: number }>(`${this.apiUrl}/${serverName}/tools`);
  }

  callTool(serverName: string, toolName: string, args?: Record<string, any>): Observable<McpToolCallResult> {
    return this.http.post<McpToolCallResult>(`${this.apiUrl}/${serverName}/tools/call`, { toolName, arguments: args || {} });
  }

  listResources(serverName: string): Observable<{ serverName: string, resources: McpResource[], count: number }> {
    return this.http.get<{ serverName: string, resources: McpResource[], count: number }>(`${this.apiUrl}/${serverName}/resources`);
  }

  listPrompts(serverName: string): Observable<{ serverName: string, prompts: McpPrompt[], count: number }> {
    return this.http.get<{ serverName: string, prompts: McpPrompt[], count: number }>(`${this.apiUrl}/${serverName}/prompts`);
  }
}
