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
 * Information about an OAuth provider.
 */
export interface OAuthProviderInfo {
  providerId: string;
  displayName: string;
  description: string;
  icon: string;
  color: string;
  configured: boolean;
  notConfiguredMessage?: string;
  requiredScopes: string[];
  relatedSources: string[];
}

/**
 * OAuth connection status and metadata.
 */
export interface OAuthConnection {
  providerId: string;
  providerDisplayName: string;
  providerIcon: string;
  status: 'connected' | 'expired' | 'error' | 'disconnected';
  userEmail?: string;
  userName?: string;
  userPicture?: string;
  connectedAt?: string;
  expiresAt?: string;
  lastUsedAt?: string;
  scopes?: string[];
  errorMessage?: string;
  needsRefresh: boolean;
}

/**
 * OAuth connection status check response.
 */
export interface OAuthConnectionStatus {
  providerId: string;
  connected: boolean;
  tokenValid: boolean;
  needsRefresh: boolean;
  expiresAt?: string;
  userEmail?: string;
  userName?: string;
  errorMessage?: string;
}

/**
 * Authorization URL response.
 */
export interface AuthorizationUrlResponse {
  authorizationUrl: string;
  state: string;
  providerId: string;
}

/**
 * Connection health check response.
 */
export interface ConnectionHealthResponse {
  healthy: boolean;
  providerId: string;
  message: string;
}

/**
 * Provider metadata for UI display.
 */
export const PROVIDER_METADATA: Record<string, { icon: string; color: string; brandIcon?: string }> = {
  google: {
    icon: 'google',
    color: '#4285F4',
    brandIcon: 'assets/icons/google.svg'
  },
  microsoft: {
    icon: 'microsoft',
    color: '#00A4EF',
    brandIcon: 'assets/icons/microsoft.svg'
  },
  atlassian: {
    icon: 'link',
    color: '#0052CC',
    brandIcon: 'assets/icons/atlassian.svg'
  },
  notion: {
    icon: 'auto_stories',
    color: '#000000',
    brandIcon: 'assets/icons/notion.svg'
  },
  slack: {
    icon: 'tag',
    color: '#4A154B',
    brandIcon: 'assets/icons/slack.svg'
  }
};
