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

export type SyncProvider = 'NOTION' | 'OBSIDIAN' | 'LOCAL_FOLDER' | 'GIT_REPOSITORY';
export type SyncDirection = 'KOMPILE_TO_EXTERNAL' | 'EXTERNAL_TO_KOMPILE' | 'BIDIRECTIONAL';
export type SyncStatus = 'NEVER' | 'OK' | 'ERROR' | 'CONFLICT';
export type SyncAuthMode = 'NONE' | 'OBSIDIAN_REST_TOKEN' | 'SYSTEM_GIT' | 'HTTPS_TOKEN';
export type SyncAuthStatus = 'UNKNOWN' | 'NOT_REQUIRED' | 'CONFIGURED' | 'VALID' | 'INVALID' | 'MISSING';

export interface NoteSyncConfig {
  notionEnabled: boolean;
  notionWebhookSecret: string;
  notionCallbackBaseUrl: string;
  obsidianEnabled: boolean;
  obsidianFileWatchEnabled: boolean;
  schedulerEnabled: boolean;
  schedulerCheckIntervalMs: number;
}

export interface SyncConnectionRequest {
  factSheetId: number;
  provider: SyncProvider;
  externalScope: string;
  direction: SyncDirection;
  pollCron?: string;
  obsidianApiUrl?: string;
  obsidianToken?: string;
  authMode?: SyncAuthMode;
  gitUsername?: string;
  gitToken?: string;
  repositoryUrl?: string;
  gitBranch?: string;
  autoCommit?: boolean;
  remoteSyncEnabled?: boolean;
}

export interface SyncConnectionResponse {
  id: number;
  factSheetId: number;
  provider: SyncProvider;
  externalScope: string;
  direction: SyncDirection;
  pollCron?: string;
  webhookId?: string;
  obsidianApiUrl?: string;
  repositoryUrl?: string;
  gitBranch?: string;
  gitUsername?: string;
  authMode?: SyncAuthMode;
  authSecretConfigured?: boolean;
  authStatus?: SyncAuthStatus;
  authStatusMessage?: string;
  authLastCheckedAt?: string;
  autoCommit?: boolean;
  remoteSyncEnabled?: boolean;
  enabled: boolean;
  lastSyncAt?: string;
  lastSyncStatus: SyncStatus;
  lastSyncError?: string;
  createdAt: string;
  updatedAt: string;
}

export interface SyncConnectionTestResponse {
  connectionId: number;
  success: boolean;
  authMode: SyncAuthMode;
  authStatus: SyncAuthStatus;
  message: string;
  checkedAt: string;
}

export interface SyncStatusUpdate {
  sessionId: string;
  connectionId: number;
  status: 'RUNNING' | 'COMPLETED' | 'ERROR';
  message?: string;
  pushed: number;
  pulled: number;
  conflicts: number;
  skipped: number;
  errors: number;
  timestamp: string;
}

export interface SyncRecord {
  id: number;
  noteId: number;
  connectionId: number;
  externalId: string;
  status: string;
  kompileUpdatedAt?: string;
  externalUpdatedAt?: string;
  contentChecksum?: string;
  lastSyncAt?: string;
  errorMessage?: string;
}

export interface NoteModel {
  id: number;
  factSheetId: number;
  title?: string;
  content: string;
  noteType: 'HUMAN' | 'AI';
  tags?: string;
  externalId?: string;
  syncProvider?: SyncProvider;
  externalUpdatedAt?: string;
  embedded: boolean;
  createdAt: string;
  updatedAt: string;
}
