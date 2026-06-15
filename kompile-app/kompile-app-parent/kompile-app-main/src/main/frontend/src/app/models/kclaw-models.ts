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

// Channel Configuration
export interface ChannelConfig {
  channelId: string;
  channelType: string;
  agentId: string;
  enabled: boolean;
  adapterConfig?: AdapterConfig;
  telegram?: TelegramChannelConfig;
  discord?: DiscordChannelConfig;
  slack?: SlackChannelConfig;
  whatsapp?: WhatsAppChannelConfig;
  email?: EmailChannelConfig;
}

export interface AdapterConfig {
  channelId: string;
  agentId: string;
  enabled: boolean;
  sessionKeyPrefix: string;
  maxMessageLength: number;
  allowFileUploads: boolean;
  allowVoiceMessages: boolean;
}

export interface TelegramChannelConfig {
  botToken?: string;
  allowedChatIds: number[];
}

export interface DiscordChannelConfig {
  botToken?: string;
  allowedChannelIds: string[];
  allowedGuildIds: string[];
}

export interface SlackChannelConfig {
  botToken?: string;
  appToken?: string;
  allowedChannelIds: string[];
  respondToAllMessages: boolean;
}

export interface WhatsAppChannelConfig {
  accessToken?: string;
  phoneNumberId?: string;
  verifyToken?: string;
  allowedPhoneNumbers: string[];
}

export interface EmailChannelConfig {
  imapHost: string;
  imapPort: number;
  username: string;
  password?: string;
  useSsl: boolean;
  smtpHost: string;
  smtpPort: number;
  fromAddress: string;
  fromName: string;
  pollIntervalSeconds: number;
  allowedSenders: string[];
}

// Channel Status
export interface ChannelStatus {
  channelName: string;
  running: boolean;
  config?: AdapterConfig;
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
