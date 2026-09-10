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

// Agent Definition
export interface AgentDefinition {
  name: string;
  description?: string;
  systemPrompt: string;
  tools: string[];
  modelPreferences?: {
    provider?: string;
    model?: string;
    temperature?: number;
    maxTokens?: number;
  };
  maxSteps: number;
  isDefault: boolean;
  type?: 'SINGLE' | 'SUPERVISOR' | 'WORKER' | 'SPECIALIST';
  capabilities?: string[];
  tags?: string[];
}

// Chat Request/Response
export interface KClawChatRequest {
  agentId?: string;
  sessionKey?: string;
  message: string;
  stream?: boolean;
  metadata?: Record<string, any>;
}

export interface KClawChatResponse {
  response: string;
  sessionKey: string;
  agentId?: string;
  tokenUsage?: {
    inputTokens: number;
    outputTokens: number;
  };
  success: boolean;
  error?: string;
  timestamp?: string;
  toolCalls?: string[];
  metadata?: Record<string, any>;
}

// Session
export interface KClawSession {
  sessionKey: string;
  messages: ReActMessage[];
  tokenCount: number;
}

export interface ReActMessage {
  id: string;
  role: 'SYSTEM' | 'USER' | 'ASSISTANT' | 'TOOL';
  content: string;
  thought?: string;
  toolCalls?: ToolCall[];
  timestamp: string;
}

export interface ToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
}

export type ChannelChatEngine = 'REACT' | 'KOMPILE_CLI' | 'WEB_CHAT';
export type ChannelRuntimeState = 'DISABLED' | 'STARTING' | 'RUNNING' | 'ERROR';
export type ChannelFieldType = 'STRING' | 'INTEGER' | 'BOOLEAN' | 'STRING_LIST' | 'LONG_LIST';

export interface ChannelFieldDescriptor {
  name: string;
  label: string;
  type: ChannelFieldType;
  required: boolean;
  defaultValue?: unknown;
  description: string;
  environmentHint?: string;
}

export interface ChannelProviderDescriptor {
  id: string;
  displayName: string;
  description: string;
  capabilities: string[];
  settings: ChannelFieldDescriptor[];
  secrets: ChannelFieldDescriptor[];
}

export interface ChannelEngineDescriptor {
  engine: ChannelChatEngine;
  displayName: string;
  description: string;
  supportsAgent: boolean;
  supportsModel: boolean;
  available: boolean;
  status: string;
}

export interface ChannelConnectionView {
  id: string;
  name: string;
  providerId: string;
  engine: ChannelChatEngine;
  agentId: string;
  model?: string;
  enabled: boolean;
  runtimeState: ChannelRuntimeState;
  settings: Record<string, unknown>;
  configuredSecrets: string[];
  createdAt: string;
  updatedAt: string;
  lastError?: string;
}

export interface ChannelConnectionWrite {
  name?: string;
  providerId?: string;
  engine?: ChannelChatEngine;
  agentId?: string;
  model?: string;
  settings: Record<string, unknown>;
  secrets: Record<string, string>;
  enabled?: boolean;
}

export interface ChannelBrowserSession {
  csrfToken: string;
  expiresAt: string;
}

export interface TelegramPairingStart {
  pairingId: string;
  code: string;
  command: string;
  expiresAt: string;
}

export interface TelegramPairing {
  pairingId: string;
  status: 'WAITING' | 'CANDIDATE' | 'APPROVED' | 'EXPIRED' | 'CANCELLED';
  expiresAt: string;
  candidate?: {
    chatId: number;
    chatType: string;
    chatTitle?: string;
    userId: number;
    username?: string;
    displayName: string;
    observedAt: string;
    authorizesEntireChat: boolean;
  };
}

export interface TelegramDiagnostics {
  connectionName: string;
  botId?: number;
  botUsername?: string;
  pollerAlive: boolean;
  ready: boolean;
  nextOffset: number;
  checkpointAt?: string;
  lastSuccessfulPoll?: string;
  lastUpdateAt?: string;
  consecutiveFailures: number;
  lastErrorCode?: number;
  lastError?: string;
  webhookConfigured: boolean;
  webhookHost?: string;
  pendingUpdateCount: number;
  activePairings: number;
}

export interface TelegramWebhookInfo {
  configured: boolean;
  host?: string;
  pendingUpdateCount: number;
  lastError?: string;
}

// Heartbeat
export interface HeartbeatInfo {
  id: string;
  cronExpression: string;
  agentId: string;
  sessionKey: string;
  message: string;
  status: 'SCHEDULED' | 'PAUSED' | 'RUNNING' | 'ERROR';
  nextFireTime?: string;
  lastFireTime?: string;
}

export interface HeartbeatRequest {
  id: string;
  cron: string;
  agentId: string;
  sessionKey?: string;
  message: string;
}

// Permissions
export interface PermissionStatus {
  allowed: string[];
  denied: string[];
  pending: string[];
}

// KClaw Config
export interface KClawConfig {
  workspace: string;
  defaultAgentId: string;
  gateway: {
    port: number;
    websocketEnabled: boolean;
    restEnabled: boolean;
    websocketPath: string;
  };
}
