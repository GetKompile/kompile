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
import { BaseService } from './base.service';

// Types for MCP Server Configuration
export type TransportType = 'STDIO' | 'SSE' | 'STREAMABLE_HTTP' | 'STATELESS_STREAMABLE_HTTP';
export type ServerStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'ERROR';
export type ToolImplementationType = 'HTTP_ENDPOINT' | 'SCRIPT' | 'JAVA_CLASS' | 'BUILT_IN';
export type ResourceType = 'STATIC' | 'FILE' | 'HTTP' | 'DATABASE' | 'DYNAMIC';
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM';
export type ContentType = 'TEXT' | 'IMAGE' | 'RESOURCE';

export interface ParameterConfig {
  name: string;
  type: string;
  description?: string;
  required: boolean;
  defaultValue?: any;
  enumValues?: any[];
}

export interface HttpEndpointConfig {
  url: string;
  method: string;
  contentType: string;
  headers?: { [key: string]: string };
  timeoutMs: number;
}

export interface ScriptConfig {
  language: string;
  script?: string;
  scriptPath?: string;
  timeoutMs: number;
  environment?: { [key: string]: string };
}

export interface JavaClassConfig {
  className: string;
  methodName: string;
  beanName?: string;
}

export interface McpToolConfig {
  name: string;
  description: string;
  inputSchema?: any;
  implementationType: ToolImplementationType;
  httpConfig?: HttpEndpointConfig;
  scriptConfig?: ScriptConfig;
  javaClassConfig?: JavaClassConfig;
  parameters: ParameterConfig[];
  enabled: boolean;
}

export interface FileResourceConfig {
  basePath: string;
  pattern?: string;
  recursive: boolean;
  watchForChanges: boolean;
}

export interface HttpResourceConfig {
  url: string;
  method: string;
  headers?: { [key: string]: string };
  timeoutMs: number;
  cacheTtlSeconds: number;
}

export interface DatabaseResourceConfig {
  dataSourceName: string;
  query: string;
  parameters?: { [key: string]: any };
  outputFormat: string;
}

export interface McpResourceConfig {
  uri: string;
  name: string;
  description?: string;
  mimeType: string;
  resourceType: ResourceType;
  fileConfig?: FileResourceConfig;
  httpConfig?: HttpResourceConfig;
  databaseConfig?: DatabaseResourceConfig;
  staticContent?: string;
  supportsSubscription: boolean;
  enabled: boolean;
}

export interface PromptArgument {
  name: string;
  description?: string;
  required: boolean;
  defaultValue?: string;
}

export interface PromptMessage {
  role: MessageRole;
  contentType: ContentType;
  content: string;
}

export interface McpPromptConfig {
  name: string;
  description?: string;
  arguments: PromptArgument[];
  messages: PromptMessage[];
  enabled: boolean;
}

export interface McpServerConfig {
  id?: string;
  name: string;
  version: string;
  description?: string;
  transportType: TransportType;
  port: number;
  basePath: string;
  tools: McpToolConfig[];
  resources: McpResourceConfig[];
  prompts: McpPromptConfig[];
  loggingEnabled: boolean;
  completionsEnabled: boolean;
  status: ServerStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface ValidationResult {
  valid: boolean;
  errors?: string[];
}

export interface ServerStatusResponse {
  id: string;
  status: ServerStatus;
}

@Injectable({
  providedIn: 'root'
})
export class McpServerBuilderService extends BaseService {

  private readonly apiPath = '/mcp/servers';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * List all MCP server configurations
   */
  listServers(): Observable<McpServerConfig[]> {
    return this.http.get<McpServerConfig[]>(`${this.backendUrl}${this.apiPath}`);
  }

  /**
   * Get a specific MCP server configuration
   */
  getServer(id: string): Observable<McpServerConfig> {
    return this.http.get<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${id}`);
  }

  /**
   * Create a new MCP server configuration
   */
  createServer(config: McpServerConfig): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}`, config);
  }

  /**
   * Update an existing MCP server configuration
   */
  updateServer(id: string, config: McpServerConfig): Observable<McpServerConfig> {
    return this.http.put<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${id}`, config);
  }

  /**
   * Delete an MCP server configuration
   */
  deleteServer(id: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/${id}`);
  }

  /**
   * Start an MCP server
   */
  startServer(id: string): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${id}/start`, {});
  }

  /**
   * Stop an MCP server
   */
  stopServer(id: string): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${id}/stop`, {});
  }

  /**
   * Restart an MCP server
   */
  restartServer(id: string): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${id}/restart`, {});
  }

  /**
   * Get server status
   */
  getServerStatus(id: string): Observable<ServerStatusResponse> {
    return this.http.get<ServerStatusResponse>(`${this.backendUrl}${this.apiPath}/${id}/status`);
  }

  /**
   * Validate a server configuration
   */
  validateConfig(config: McpServerConfig): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.backendUrl}${this.apiPath}/validate`, config);
  }

  /**
   * Export a server configuration
   */
  exportConfig(id: string): Observable<string> {
    return this.http.get(`${this.backendUrl}${this.apiPath}/${id}/export`, { responseType: 'text' });
  }

  /**
   * Import a server configuration
   */
  importConfig(json: string): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/import`, json, {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Get available transport types
   */
  getTransportTypes(): Observable<TransportType[]> {
    return this.http.get<TransportType[]>(`${this.backendUrl}${this.apiPath}/transport-types`);
  }

  /**
   * Get available tool implementation types
   */
  getToolTypes(): Observable<ToolImplementationType[]> {
    return this.http.get<ToolImplementationType[]>(`${this.backendUrl}${this.apiPath}/tool-types`);
  }

  /**
   * Get available resource types
   */
  getResourceTypes(): Observable<ResourceType[]> {
    return this.http.get<ResourceType[]>(`${this.backendUrl}${this.apiPath}/resource-types`);
  }

  /**
   * Add a tool to a server
   */
  addTool(serverId: string, tool: McpToolConfig): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/tools`, tool);
  }

  /**
   * Remove a tool from a server
   */
  removeTool(serverId: string, toolName: string): Observable<McpServerConfig> {
    return this.http.delete<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/tools/${toolName}`);
  }

  /**
   * Add a resource to a server
   */
  addResource(serverId: string, resource: McpResourceConfig): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/resources`, resource);
  }

  /**
   * Remove a resource from a server
   */
  removeResource(serverId: string, resourceUri: string): Observable<McpServerConfig> {
    return this.http.delete<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/resources/${encodeURIComponent(resourceUri)}`);
  }

  /**
   * Add a prompt to a server
   */
  addPrompt(serverId: string, prompt: McpPromptConfig): Observable<McpServerConfig> {
    return this.http.post<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/prompts`, prompt);
  }

  /**
   * Remove a prompt from a server
   */
  removePrompt(serverId: string, promptName: string): Observable<McpServerConfig> {
    return this.http.delete<McpServerConfig>(`${this.backendUrl}${this.apiPath}/${serverId}/prompts/${promptName}`);
  }

  /**
   * Create a default server configuration
   */
  createDefaultConfig(): McpServerConfig {
    return {
      name: 'New MCP Server',
      version: '1.0.0',
      description: '',
      transportType: 'SSE',
      port: 8081,
      basePath: '/mcp',
      tools: [],
      resources: [],
      prompts: [],
      loggingEnabled: true,
      completionsEnabled: false,
      status: 'STOPPED'
    };
  }

  /**
   * Create a default tool configuration
   */
  createDefaultTool(): McpToolConfig {
    return {
      name: 'new_tool',
      description: 'A new tool',
      implementationType: 'HTTP_ENDPOINT',
      parameters: [],
      enabled: true,
      httpConfig: {
        url: 'http://localhost:8080/api/endpoint',
        method: 'POST',
        contentType: 'application/json',
        timeoutMs: 30000
      }
    };
  }

  /**
   * Create a default resource configuration
   */
  createDefaultResource(): McpResourceConfig {
    return {
      uri: 'custom://resource',
      name: 'New Resource',
      mimeType: 'text/plain',
      resourceType: 'STATIC',
      staticContent: '',
      supportsSubscription: false,
      enabled: true
    };
  }

  /**
   * Create a default prompt configuration
   */
  createDefaultPrompt(): McpPromptConfig {
    return {
      name: 'new_prompt',
      description: 'A new prompt template',
      arguments: [],
      messages: [
        {
          role: 'USER',
          contentType: 'TEXT',
          content: 'Hello, {{name}}!'
        }
      ],
      enabled: true
    };
  }
}
