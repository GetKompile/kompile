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

// Types for REST-MCP Bridge Configuration
export type BridgeDirection = 'REST_TO_MCP' | 'MCP_TO_REST';
export type BridgeStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR' | 'SYNCING';
export type AuthType = 'NONE' | 'API_KEY' | 'BEARER' | 'BASIC' | 'OAUTH2';
export type TransformType = 'PASSTHROUGH' | 'JSON_PATH' | 'JMES_PATH' | 'JAVASCRIPT' | 'TEMPLATE' | 'FIELD_MAPPING';

export interface RestApiConfig {
  baseUrl: string;
  openApiUrl?: string;
  timeoutMs: number;
  defaultHeaders?: { [key: string]: string };
  verifySsl: boolean;
  rateLimitPerSecond: number;
}

export interface McpServerRef {
  serverId?: string;
  serverUrl?: string;
  port: number;
  basePath: string;
}

export interface ParameterDef {
  name: string;
  type?: string;
  description?: string;
  required: boolean;
  defaultValue?: any;
  enumValues?: any[];
}

export interface RestEndpoint {
  method: string;
  path: string;
  contentType: string;
  acceptType: string;
  queryParams?: ParameterDef[];
  pathParams?: ParameterDef[];
  requestBodySchema?: any;
  responseBodySchema?: any;
}

export interface McpToolMapping {
  name: string;
  description: string;
  inputSchema?: any;
  category?: string;
}

export interface ParameterMapping {
  sourceName: string;
  targetName: string;
  sourceLocation: string;
  targetLocation: string;
  transform?: string;
}

export interface TransformConfig {
  type: TransformType;
  jsonPath?: string;
  jmesPath?: string;
  script?: string;
  template?: string;
  fieldMappings?: { [key: string]: string };
}

export interface EndpointMapping {
  id?: string;
  enabled: boolean;
  restEndpoint: RestEndpoint;
  mcpTool: McpToolMapping;
  requestTransform?: TransformConfig;
  responseTransform?: TransformConfig;
  parameterMappings: ParameterMapping[];
}

export interface OAuth2Config {
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  scope?: string;
  grantType: string;
}

export interface AuthConfig {
  type: AuthType;
  apiKey?: string;
  apiKeyHeader: string;
  bearerToken?: string;
  username?: string;
  password?: string;
  oauth2?: OAuth2Config;
}

export interface RestMcpBridgeConfig {
  id?: string;
  name: string;
  description?: string;
  direction: BridgeDirection;
  enabled: boolean;
  status: BridgeStatus;
  restApiConfig: RestApiConfig;
  mcpServerRef: McpServerRef;
  mappings: EndpointMapping[];
  authConfig?: AuthConfig;
  requestTransform?: TransformConfig;
  responseTransform?: TransformConfig;
  createdAt?: string;
  updatedAt?: string;
}

export interface ValidationResult {
  valid: boolean;
  errors?: string[];
}

export interface BridgeStatusResponse {
  id: string;
  status: BridgeStatus;
}

export interface DiscoverResult {
  openApiUrl?: string;
  baseUrl?: string;
  mappings: EndpointMapping[];
  count: number;
}

export interface EndpointTestResult {
  success: boolean;
  statusCode: number;
  response?: any;
  error?: string;
  durationMs: number;
}

export interface DiscoveredTool {
  name: string;
  description: string;
  beanName: string;
  methodName: string;
  returnType: string;
  parameters: ToolParameter[];
}

export interface ToolParameter {
  name: string;
  type: string;
  description: string;
  required: boolean;
}

export interface BuiltInToolsDiscoveryResult {
  tools: DiscoveredTool[];
  mappings: EndpointMapping[];
  count: number;
}

export interface CreateBuiltInBridgeResult {
  message: string;
  bridge: RestMcpBridgeConfig;
}

export interface AddBuiltInToolsResult {
  message: string;
  addedCount: number;
  bridge: RestMcpBridgeConfig;
}

@Injectable({
  providedIn: 'root'
})
export class RestMcpBridgeService extends BaseService {

  private readonly apiPath = '/mcp/bridges';

  constructor(private http: HttpClient) {
    super();
  }

  /**
   * List all bridge configurations
   */
  listBridges(): Observable<RestMcpBridgeConfig[]> {
    return this.http.get<RestMcpBridgeConfig[]>(`${this.backendUrl}${this.apiPath}`);
  }

  /**
   * Get a specific bridge configuration
   */
  getBridge(id: string): Observable<RestMcpBridgeConfig> {
    return this.http.get<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${id}`);
  }

  /**
   * Create a new bridge configuration
   */
  createBridge(config: RestMcpBridgeConfig): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}`, config);
  }

  /**
   * Update an existing bridge configuration
   */
  updateBridge(id: string, config: RestMcpBridgeConfig): Observable<RestMcpBridgeConfig> {
    return this.http.put<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${id}`, config);
  }

  /**
   * Delete a bridge configuration
   */
  deleteBridge(id: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/${id}`);
  }

  /**
   * Start a bridge
   */
  startBridge(id: string): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${id}/start`, {});
  }

  /**
   * Stop a bridge
   */
  stopBridge(id: string): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${id}/stop`, {});
  }

  /**
   * Get bridge status
   */
  getBridgeStatus(id: string): Observable<BridgeStatusResponse> {
    return this.http.get<BridgeStatusResponse>(`${this.backendUrl}${this.apiPath}/${id}/status`);
  }

  /**
   * Discover endpoints from OpenAPI specification
   */
  discoverFromOpenApi(openApiUrl: string): Observable<DiscoverResult> {
    return this.http.post<DiscoverResult>(`${this.backendUrl}${this.apiPath}/discover/openapi`, { openApiUrl });
  }

  /**
   * Discover endpoints by probing a base URL
   */
  probeEndpoints(baseUrl: string): Observable<DiscoverResult> {
    return this.http.post<DiscoverResult>(`${this.backendUrl}${this.apiPath}/discover/probe`, { baseUrl });
  }

  /**
   * Test a specific endpoint mapping
   */
  testMapping(bridgeId: string, mappingId: string, testInput?: any): Observable<EndpointTestResult> {
    return this.http.post<EndpointTestResult>(
      `${this.backendUrl}${this.apiPath}/${bridgeId}/mappings/${mappingId}/test`,
      testInput || {}
    );
  }

  /**
   * Sync bridge mappings with the target
   */
  syncBridge(id: string): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${id}/sync`, {});
  }

  /**
   * Validate a bridge configuration
   */
  validateConfig(config: RestMcpBridgeConfig): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.backendUrl}${this.apiPath}/validate`, config);
  }

  /**
   * Export a bridge configuration
   */
  exportConfig(id: string): Observable<string> {
    return this.http.get(`${this.backendUrl}${this.apiPath}/${id}/export`, { responseType: 'text' });
  }

  /**
   * Import a bridge configuration
   */
  importConfig(json: string): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/import`, json, {
      headers: { 'Content-Type': 'application/json' }
    });
  }

  /**
   * Get available bridge directions
   */
  getBridgeDirections(): Observable<BridgeDirection[]> {
    return this.http.get<BridgeDirection[]>(`${this.backendUrl}${this.apiPath}/directions`);
  }

  /**
   * Get available auth types
   */
  getAuthTypes(): Observable<AuthType[]> {
    return this.http.get<AuthType[]>(`${this.backendUrl}${this.apiPath}/auth-types`);
  }

  /**
   * Get available transform types
   */
  getTransformTypes(): Observable<TransformType[]> {
    return this.http.get<TransformType[]>(`${this.backendUrl}${this.apiPath}/transform-types`);
  }

  /**
   * Add a mapping to a bridge
   */
  addMapping(bridgeId: string, mapping: EndpointMapping): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${bridgeId}/mappings`, mapping);
  }

  /**
   * Update a mapping in a bridge
   */
  updateMapping(bridgeId: string, mappingId: string, mapping: EndpointMapping): Observable<RestMcpBridgeConfig> {
    return this.http.put<RestMcpBridgeConfig>(
      `${this.backendUrl}${this.apiPath}/${bridgeId}/mappings/${mappingId}`,
      mapping
    );
  }

  /**
   * Remove a mapping from a bridge
   */
  removeMapping(bridgeId: string, mappingId: string): Observable<RestMcpBridgeConfig> {
    return this.http.delete<RestMcpBridgeConfig>(`${this.backendUrl}${this.apiPath}/${bridgeId}/mappings/${mappingId}`);
  }

  /**
   * Toggle a mapping's enabled status
   */
  toggleMapping(bridgeId: string, mappingId: string): Observable<RestMcpBridgeConfig> {
    return this.http.post<RestMcpBridgeConfig>(
      `${this.backendUrl}${this.apiPath}/${bridgeId}/mappings/${mappingId}/toggle`,
      {}
    );
  }

  // ==================== Built-in Tool Integration Methods ====================

  /**
   * Discover built-in MCP tools from the application.
   * Returns all @Tool annotated methods from registered beans.
   */
  discoverBuiltInTools(): Observable<BuiltInToolsDiscoveryResult> {
    return this.http.get<BuiltInToolsDiscoveryResult>(`${this.backendUrl}${this.apiPath}/discover/builtin`);
  }

  /**
   * Get an OpenAPI specification for the application's MCP tools.
   */
  getBuiltInToolsOpenApiSpec(): Observable<any> {
    return this.http.get<any>(`${this.backendUrl}${this.apiPath}/discover/builtin/openapi`);
  }

  /**
   * Create a pre-configured bridge for the application's built-in MCP tools.
   */
  createBuiltInToolsBridge(): Observable<CreateBuiltInBridgeResult> {
    return this.http.post<CreateBuiltInBridgeResult>(`${this.backendUrl}${this.apiPath}/create-builtin-bridge`, {});
  }

  /**
   * Add built-in tool mappings to an existing bridge.
   */
  addBuiltInToolsToBridge(bridgeId: string): Observable<AddBuiltInToolsResult> {
    return this.http.post<AddBuiltInToolsResult>(`${this.backendUrl}${this.apiPath}/${bridgeId}/add-builtin-tools`, {});
  }

  /**
   * Refresh the built-in tool discovery cache.
   */
  refreshBuiltInToolDiscovery(): Observable<BuiltInToolsDiscoveryResult> {
    return this.http.post<BuiltInToolsDiscoveryResult>(`${this.backendUrl}${this.apiPath}/discover/builtin/refresh`, {});
  }

  /**
   * Create a default bridge configuration
   */
  createDefaultConfig(): RestMcpBridgeConfig {
    return {
      name: 'New REST-MCP Bridge',
      description: '',
      direction: 'REST_TO_MCP',
      enabled: true,
      status: 'STOPPED',
      restApiConfig: {
        baseUrl: 'https://api.example.com',
        timeoutMs: 30000,
        verifySsl: true,
        rateLimitPerSecond: 0
      },
      mcpServerRef: {
        port: 8082,
        basePath: '/mcp-bridge'
      },
      mappings: [],
      authConfig: {
        type: 'NONE',
        apiKeyHeader: 'X-API-Key'
      }
    };
  }

  /**
   * Create a default endpoint mapping
   */
  createDefaultMapping(): EndpointMapping {
    return {
      enabled: true,
      restEndpoint: {
        method: 'GET',
        path: '/api/endpoint',
        contentType: 'application/json',
        acceptType: 'application/json',
        queryParams: [],
        pathParams: []
      },
      mcpTool: {
        name: 'new_tool',
        description: 'A new tool mapped from REST endpoint'
      },
      parameterMappings: []
    };
  }

  /**
   * Create a default REST endpoint configuration
   */
  createDefaultRestEndpoint(): RestEndpoint {
    return {
      method: 'GET',
      path: '/api/endpoint',
      contentType: 'application/json',
      acceptType: 'application/json',
      queryParams: [],
      pathParams: []
    };
  }

  /**
   * Create a default parameter definition
   */
  createDefaultParameter(): ParameterDef {
    return {
      name: 'param',
      type: 'string',
      required: false
    };
  }

  /**
   * Create a default auth configuration
   */
  createDefaultAuthConfig(type: AuthType): AuthConfig {
    const config: AuthConfig = {
      type,
      apiKeyHeader: 'X-API-Key'
    };

    switch (type) {
      case 'API_KEY':
        config.apiKey = '';
        break;
      case 'BEARER':
        config.bearerToken = '';
        break;
      case 'BASIC':
        config.username = '';
        config.password = '';
        break;
      case 'OAUTH2':
        config.oauth2 = {
          tokenUrl: '',
          clientId: '',
          clientSecret: '',
          grantType: 'client_credentials'
        };
        break;
    }

    return config;
  }

  /**
   * Create a default transform configuration
   */
  createDefaultTransformConfig(type: TransformType): TransformConfig {
    const config: TransformConfig = { type };

    switch (type) {
      case 'JSON_PATH':
        config.jsonPath = '$.data';
        break;
      case 'JMES_PATH':
        config.jmesPath = 'data';
        break;
      case 'JAVASCRIPT':
        config.script = 'return input;';
        break;
      case 'TEMPLATE':
        config.template = '{{data}}';
        break;
      case 'FIELD_MAPPING':
        config.fieldMappings = {};
        break;
    }

    return config;
  }

  /**
   * Get a user-friendly name for a bridge direction
   */
  getDirectionDisplayName(direction: BridgeDirection): string {
    switch (direction) {
      case 'REST_TO_MCP':
        return 'REST to MCP';
      case 'MCP_TO_REST':
        return 'MCP to REST';
      default:
        return direction;
    }
  }

  /**
   * Get a description for a bridge direction
   */
  getDirectionDescription(direction: BridgeDirection): string {
    switch (direction) {
      case 'REST_TO_MCP':
        return 'Expose REST API endpoints as MCP tools';
      case 'MCP_TO_REST':
        return 'Expose MCP server tools as REST endpoints';
      default:
        return '';
    }
  }

  /**
   * Get a user-friendly name for an auth type
   */
  getAuthTypeDisplayName(type: AuthType): string {
    switch (type) {
      case 'NONE':
        return 'No Authentication';
      case 'API_KEY':
        return 'API Key';
      case 'BEARER':
        return 'Bearer Token';
      case 'BASIC':
        return 'Basic Auth';
      case 'OAUTH2':
        return 'OAuth 2.0';
      default:
        return type;
    }
  }

  /**
   * Get status color for display
   */
  getStatusColor(status: BridgeStatus): string {
    switch (status) {
      case 'RUNNING':
        return 'green';
      case 'STOPPED':
        return 'gray';
      case 'STARTING':
      case 'SYNCING':
        return 'orange';
      case 'ERROR':
        return 'red';
      default:
        return 'gray';
    }
  }
}
