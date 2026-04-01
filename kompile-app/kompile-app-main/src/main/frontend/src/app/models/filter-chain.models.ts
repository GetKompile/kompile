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

/**
 * Filter type - local built-in, HTTP remote, or MCP remote.
 */
export type FilterType = 'LOCAL' | 'HTTP' | 'MCP';

/**
 * Filter execution phases in the RAG pipeline.
 */
export type FilterPhase = 'PRE_RETRIEVAL' | 'POST_RETRIEVAL' | 'PRE_LLM' | 'POST_LLM';

/**
 * Authentication type for remote filters.
 */
export type AuthType = 'NONE' | 'API_KEY' | 'BEARER' | 'BASIC' | 'OAUTH2';

/**
 * Authentication configuration for remote filters.
 */
export interface AuthConfig {
  type: AuthType;
  apiKey?: string;
  apiKeyHeader?: string;
  bearerToken?: string;
  username?: string;
  password?: string;
  oauth2TokenUrl?: string;
  oauth2ClientId?: string;
  oauth2ClientSecret?: string;
  oauth2Scope?: string;
}

/**
 * Remote filter configuration for HTTP and MCP filters.
 */
export interface RemoteFilterConfig {
  endpoint: string;
  httpMethod?: string;
  timeoutMs?: number;
  retries?: number;
  retryDelayMs?: number;
  headers?: Record<string, string>;
  mcpToolName?: string;
  verifySsl?: boolean;
  authConfig?: AuthConfig;
}

/**
 * Individual filter configuration.
 */
export interface FilterConfig {
  id: string;
  name: string;
  type: FilterType;
  enabled: boolean;
  priority: number;
  phases: FilterPhase[];
  localFilterId?: string;
  remoteConfig?: RemoteFilterConfig;
  settings?: Record<string, unknown>;
  description?: string;
}

/**
 * Complete filter chain configuration.
 */
export interface FilterChainConfig {
  available: boolean;
  enabled: boolean;
  globalTimeoutMs: number;
  continueOnError: boolean;
  tracingEnabled: boolean;
  filters: FilterConfig[];
  configPath?: string;
  message?: string;
}

/**
 * Information about an available filter.
 */
export interface FilterInfo {
  id: string;
  name: string;
  description: string;
  type: string;
  categories?: string[];
  enabled: boolean;
  priority: number;
}

/**
 * Response from filters endpoint.
 */
export interface FiltersResponse {
  configuredFilters: FilterConfig[];
  availableFilters: FilterInfo[];
}

/**
 * Response from toggle endpoint.
 */
export interface ToggleFilterResponse {
  success: boolean;
  filterId?: string;
  enabled?: boolean;
}

/**
 * Response from config update endpoint.
 */
export interface ConfigUpdateResponse {
  success: boolean;
  config: FilterChainConfig;
  error?: string;
}

/**
 * Filter chain status.
 */
export interface FilterChainStatus {
  moduleAvailable: boolean;
  enabled?: boolean;
  filterCount?: number;
  configPath?: string;
  serviceActive?: boolean;
  activeFilters?: number;
}
