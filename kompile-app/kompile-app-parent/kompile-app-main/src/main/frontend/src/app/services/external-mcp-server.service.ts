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

// Types for External MCP Server Configuration
export type ServerStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'ERROR';
export type TransportType = 'STDIO' | 'REST' | 'SSE';

export interface ExternalMcpServerConfig {
  id: string;
  transportType: TransportType;

  // STDIO configuration (for STDIO transport)
  command?: string;
  args?: string[];
  env?: { [key: string]: string };

  // REST/SSE configuration (for REST/SSE transport)
  url?: string;
  headers?: { [key: string]: string };
  connectionTimeout?: number;
  requestTimeout?: number;
  verifySsl?: boolean;
  sseEndpoint?: string;
  messagesEndpoint?: string;

  // Common fields
  enabled: boolean;
  description?: string;
  status?: ServerStatus;
  lastStarted?: string;
  lastStopped?: string;
  errorMessage?: string;
  pid?: number;
}

export interface UnifiedMcpConfig {
  mcpServers: { [key: string]: ExternalMcpServerConfig };
  lastModified?: string;
}

export interface ServerStatusResponse {
  id: string;
  status: ServerStatus;
  pid?: number;
  errorMessage?: string;
}

export interface ValidationResult {
  valid: boolean;
  errors?: string[];
}

export interface ConfigOperationResult {
  message: string;
  serverCount: number;
}

@Injectable({
  providedIn: 'root'
})
export class ExternalMcpServerService extends BaseService {

  private readonly serverApiPath = '/mcp/external-servers';
  private readonly configApiPath = '/mcp/config';

  constructor(private http: HttpClient) {
    super();
  }

  // ==================== Server CRUD Operations ====================

  /**
   * List all external MCP server configurations
   */
  listServers(): Observable<ExternalMcpServerConfig[]> {
    return this.http.get<ExternalMcpServerConfig[]>(`${this.backendUrl}${this.serverApiPath}`);
  }

  /**
   * Get a specific external MCP server configuration
   */
  getServer(id: string): Observable<ExternalMcpServerConfig> {
    return this.http.get<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}`);
  }

  /**
   * Add a new external MCP server configuration
   */
  addServer(config: ExternalMcpServerConfig): Observable<ExternalMcpServerConfig> {
    return this.http.post<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}`, config);
  }

  /**
   * Update an existing external MCP server configuration
   */
  updateServer(id: string, config: ExternalMcpServerConfig): Observable<ExternalMcpServerConfig> {
    return this.http.put<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}`, config);
  }

  /**
   * Delete an external MCP server configuration
   */
  deleteServer(id: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}`);
  }

  // ==================== Server Lifecycle Operations ====================

  /**
   * Start an external MCP server
   */
  startServer(id: string): Observable<ExternalMcpServerConfig> {
    return this.http.post<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}/start`, {});
  }

  /**
   * Stop an external MCP server
   */
  stopServer(id: string): Observable<ExternalMcpServerConfig> {
    return this.http.post<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}/stop`, {});
  }

  /**
   * Restart an external MCP server
   */
  restartServer(id: string): Observable<ExternalMcpServerConfig> {
    return this.http.post<ExternalMcpServerConfig>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}/restart`, {});
  }

  /**
   * Get the status of an external MCP server
   */
  getServerStatus(id: string): Observable<ServerStatusResponse> {
    return this.http.get<ServerStatusResponse>(`${this.backendUrl}${this.serverApiPath}/${encodeURIComponent(id)}/status`);
  }

  // ==================== Configuration Operations ====================

  /**
   * Get the full unified configuration JSON
   */
  getConfig(): Observable<string> {
    return this.http.get(`${this.backendUrl}${this.configApiPath}`, { responseType: 'text' });
  }

  /**
   * Replace the entire configuration with new JSON
   */
  replaceConfig(json: string): Observable<ConfigOperationResult> {
    return this.http.put<ConfigOperationResult>(`${this.backendUrl}${this.configApiPath}`, json, {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Import servers from Claude Desktop format JSON (merges with existing)
   */
  importConfig(json: string): Observable<ConfigOperationResult> {
    return this.http.post<ConfigOperationResult>(`${this.backendUrl}${this.configApiPath}/import`, json, {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Export the current configuration as Claude Desktop format JSON
   */
  exportConfig(): Observable<string> {
    return this.http.get(`${this.backendUrl}${this.configApiPath}/export`, { responseType: 'text' });
  }

  /**
   * Validate configuration JSON without saving
   */
  validateConfig(json: string): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.backendUrl}${this.configApiPath}/validate`, json, {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Reload configuration from disk
   */
  reloadConfig(): Observable<ConfigOperationResult> {
    return this.http.post<ConfigOperationResult>(`${this.backendUrl}${this.configApiPath}/reload`, {});
  }

  // ==================== Helper Methods ====================

  /**
   * Create a default STDIO server configuration
   */
  createDefaultConfig(): ExternalMcpServerConfig {
    return {
      id: 'new-server',
      transportType: 'STDIO',
      command: 'npx',
      args: ['-y', '@modelcontextprotocol/server-filesystem', '.'],
      env: {},
      enabled: true,
      description: ''
    };
  }

  /**
   * Create a default REST server configuration
   */
  createDefaultRestConfig(): ExternalMcpServerConfig {
    return {
      id: 'new-rest-server',
      transportType: 'REST',
      url: 'http://localhost:3000/mcp',
      headers: {},
      connectionTimeout: 30000,
      requestTimeout: 60000,
      verifySsl: true,
      enabled: true,
      description: ''
    };
  }

  /**
   * Create a default SSE server configuration
   */
  createDefaultSseConfig(): ExternalMcpServerConfig {
    return {
      id: 'new-sse-server',
      transportType: 'SSE',
      url: 'http://localhost:3000/mcp',
      headers: {},
      connectionTimeout: 30000,
      requestTimeout: 60000,
      verifySsl: true,
      sseEndpoint: '/sse',
      messagesEndpoint: '/message',
      enabled: true,
      description: ''
    };
  }

  /**
   * Parse configuration JSON into server list
   */
  parseConfigJson(json: string): ExternalMcpServerConfig[] {
    try {
      const config = JSON.parse(json);
      const servers: ExternalMcpServerConfig[] = [];

      if (config.mcpServers) {
        for (const [id, serverConfig] of Object.entries(config.mcpServers)) {
          const server = serverConfig as any;
          const transportType = server.transportType || 'STDIO';

          const baseConfig: ExternalMcpServerConfig = {
            id,
            transportType,
            enabled: server.enabled !== false,
            description: server.description || ''
          };

          if (transportType === 'STDIO') {
            // STDIO configuration
            baseConfig.command = server.command || '';
            baseConfig.args = server.args || [];
            baseConfig.env = server.env || {};
          } else {
            // REST/SSE configuration
            baseConfig.url = server.url || '';
            baseConfig.headers = server.headers || {};
            baseConfig.connectionTimeout = server.connectionTimeout || 30000;
            baseConfig.requestTimeout = server.requestTimeout || 60000;
            baseConfig.verifySsl = server.verifySsl !== false;
            baseConfig.sseEndpoint = server.sseEndpoint;
            baseConfig.messagesEndpoint = server.messagesEndpoint;
          }

          servers.push(baseConfig);
        }
      }

      return servers;
    } catch (e) {
      console.error('Failed to parse config JSON:', e);
      return [];
    }
  }

  /**
   * Format server list into JSON configuration
   */
  formatConfigJson(servers: ExternalMcpServerConfig[]): string {
    const config: any = { mcpServers: {} };

    for (const server of servers) {
      const transportType = server.transportType || 'STDIO';
      const serverConfig: any = {
        transportType
      };

      if (transportType === 'STDIO') {
        // STDIO configuration
        serverConfig.command = server.command;
        serverConfig.args = server.args || [];
        serverConfig.env = server.env || {};
      } else {
        // REST/SSE configuration
        serverConfig.url = server.url;
        serverConfig.headers = server.headers || {};
        serverConfig.connectionTimeout = server.connectionTimeout;
        serverConfig.requestTimeout = server.requestTimeout;
        serverConfig.verifySsl = server.verifySsl;
        if (server.sseEndpoint) {
          serverConfig.sseEndpoint = server.sseEndpoint;
        }
        if (server.messagesEndpoint) {
          serverConfig.messagesEndpoint = server.messagesEndpoint;
        }
      }

      if (server.description) {
        serverConfig.description = server.description;
      }
      if (!server.enabled) {
        serverConfig.enabled = false;
      }

      config.mcpServers[server.id] = serverConfig;
    }

    return JSON.stringify(config, null, 2);
  }

  /**
   * Check if a server is STDIO transport
   */
  isStdio(server: ExternalMcpServerConfig): boolean {
    return !server.transportType || server.transportType === 'STDIO';
  }

  /**
   * Check if a server is REST transport
   */
  isRest(server: ExternalMcpServerConfig): boolean {
    return server.transportType === 'REST';
  }

  /**
   * Check if a server is SSE transport
   */
  isSse(server: ExternalMcpServerConfig): boolean {
    return server.transportType === 'SSE';
  }
}
