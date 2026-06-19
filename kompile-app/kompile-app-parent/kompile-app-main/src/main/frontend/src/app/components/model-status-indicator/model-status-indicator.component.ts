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

import { Component, OnInit, OnDestroy, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { interval, Subscription, forkJoin, of, catchError } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { Subject } from 'rxjs';
import { BaseService } from '../../services/base.service';
import { ModelRegistryService } from '../../services/model-registry.service';
import { WebSocketService } from '../../services/websocket.service';
import { ModelStatusUpdate } from '../../models/api-models';

// ==================== Interfaces ====================

export interface ModelSourceStatus {
  configured: boolean;
  sourceType: string | null;  // "staging" or "archive"
  description: string | null;
  available: boolean;
  error: string | null;
  encoderCount: number;
  crossEncoderCount: number;
  message?: string;
}

// Staging service config from database (UI-configured)
export interface StagingServiceConfig {
  id?: number;
  name: string;
  endpointUrl: string;
  apiKey?: string;
  active: boolean;
  verified: boolean;
  lastVerifiedAt?: string;
  lastError?: string;
}

// Connection test result
export interface ConnectionTestResult {
  success: boolean;
  message: string;
  modelCount: number;
  version: string;
}

export interface EmbeddingModelStatusDto {
  configuredModel: string | null;
  configuredSource: string | null;
  configuredArchiveId: string | null;
  available: boolean;
  initialized: boolean;
  activeModel: string | null;
  activeSource: string | null;
  dimensions: number | null;
  matchesConfig: boolean;
  // Loading progress tracking
  loading?: boolean;
  loadingPhase?: string;
  loadingMessage?: string;
  loadingElapsedMs?: number;
}

export interface CrossEncoderStatusDto {
  rerankingEnabled: boolean;
  rerankerType: string | null;
  configuredModel: string | null;
  configuredSource: string | null;
  configuredArchiveId: string | null;
  rerankTopK: number | null;
  available: boolean;
  modelFoundIn: string | null;  // "registry", "built-in", or null
  matchesConfig: boolean;
  loaded: boolean;              // true if the model has been loaded for inference
  loadStatus: string | null;    // "not_configured", "not_available", "available_on_demand", "loaded", "disabled", "not_applicable"
}

export interface FactSheetModelStatusDto {
  factSheetId: number;
  factSheetName: string;
  embedding: EmbeddingModelStatusDto;
  crossEncoder: CrossEncoderStatusDto;
}

// Embedding-subprocess restart governor (manual toggle + native-crash circuit breaker)
export interface EmbeddingRestartStatusDto {
  autoRestartEnabled: boolean;
  nativeCrashThreshold: number;
  restartsPaused: boolean;
  consecutiveNativeCrashes: number;
  pausedReason: string | null;
  lastCrashReason: string | null;
  subprocessRunning: boolean;
  modelAvailable: boolean;
}

export interface RegistryVersionInfo {
  registryVersion: string;
  updatedAt: string | null;
  totalModels: number;
  activeModels: number;
  modelsBySource: { [key: string]: number };
  installedArchives: ArchiveInfo[];
}

export interface ArchiveInfo {
  archive_id: string;
  archive_name: string;
  version: string;
  installed_at: string;
  model_ids: string[];
}

export interface ModelEntry {
  model_id: string;
  type: string;
  path: string;
  status: string;
  metadata?: {
    embedding_dim?: number;
    model_type?: string;
  };
}

export interface ModelRegistry {
  version: string;
  updated_at: string | null;
  models: { [key: string]: ModelEntry };
}

// Archive-related interfaces
export interface ArchiveFileInfo {
  name: string;
  path: string;
  archiveId: string | null;
  version: string | null;
  description: string | null;
  modelCount: number;
  sizeBytes: number;
  lastModified: string;
  loaded: boolean;
}

export interface ArchiveStatus {
  loaded: boolean;
  archivePath: string | null;
  archiveId: string | null;
  contentVersion: string | null;
  description: string | null;
  modelCount: number;
  encoderCount: number;
  crossEncoderCount: number;
  loadedAt: string | null;
}

export interface ArchiveModelInfo {
  modelId: string;
  type: string;
  path: string;
  embeddingDim: number | null;
  maxSequenceLength: number | null;
  description: string | null;
}

// Model source type - determines where models are fetched from
export type ModelSourceType = 'staging' | 'archive' | 'default' | 'registry';

@Component({
  selector: 'app-model-status-indicator',
  standalone: true,
  imports: [CommonModule, HttpClientModule],
  template: `
    <div class="model-status-bar" [class.expanded]="isExpanded">
      <!-- Main Status Bar (always visible) -->
      <div class="status-bar-content" (click)="toggleExpanded()">

        <!-- Model Source Status (Staging or Archive) -->
        <div class="status-segment source-segment"
             [class.ready]="isSourceConnected()"
             [class.warning]="isSourceConfigured() && !isSourceConnected()"
             [class.not-loaded]="!isSourceConfigured()">
          <div class="segment-icon">
            <!-- Archive icon -->
            <svg *ngIf="currentSourceType === 'archive'" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 8v13H3V8"></path>
              <path d="M1 3h22v5H1z"></path>
              <path d="M10 12h4"></path>
            </svg>
            <!-- Remote Staging cloud icon (when connected) -->
            <svg *ngIf="currentSourceType !== 'archive' && stagingConnected" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"></path>
            </svg>
            <!-- Default staging icon (when not connected) -->
            <svg *ngIf="currentSourceType !== 'archive' && !stagingConnected" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"></path>
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"></path>
            </svg>
          </div>
          <div class="segment-info">
            <span class="segment-label">{{ getSourceLabel() }}</span>
            <span class="segment-value">{{ getSourceDisplayText() }}</span>
          </div>
          <div class="status-dot"
               [class.green]="isSourceConnected()"
               [class.yellow]="isSourceConfigured() && !isSourceConnected()"
               [class.red]="!isSourceConfigured()"></div>
        </div>

        <div class="divider"></div>

        <!-- Encoders from Source -->
        <div class="status-segment" [class.ready]="denseEncoderCount > 0" [class.not-loaded]="denseEncoderCount === 0">
          <div class="segment-icon">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"></circle>
              <path d="M12 6v6l4 2"></path>
            </svg>
          </div>
          <div class="segment-info">
            <span class="segment-label">Encoders</span>
            <span class="segment-value">{{ denseEncoderCount }} available</span>
          </div>
          <div class="status-dot" [class.green]="denseEncoderCount > 0" [class.red]="denseEncoderCount === 0"></div>
        </div>

        <div class="divider"></div>

        <!-- Cross-Encoders from Source -->
        <div class="status-segment" [class.ready]="crossEncoderCount > 0" [class.not-loaded]="crossEncoderCount === 0">
          <div class="segment-icon">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="4" y1="9" x2="20" y2="9"></line>
              <line x1="4" y1="15" x2="20" y2="15"></line>
              <line x1="10" y1="3" x2="8" y2="21"></line>
              <line x1="16" y1="3" x2="14" y2="21"></line>
            </svg>
          </div>
          <div class="segment-info">
            <span class="segment-label">Cross-Encoders</span>
            <span class="segment-value">{{ crossEncoderCount }} available</span>
          </div>
          <div class="status-dot" [class.green]="crossEncoderCount > 0" [class.red]="crossEncoderCount === 0"></div>
        </div>

        <div class="divider"></div>

        <!-- Active Embedding Status -->
        <div class="status-segment embedding-segment"
             [class.ready]="modelStatus?.embedding?.initialized && !modelLoading"
             [class.loading]="modelLoading"
             [class.not-loaded]="!modelStatus?.embedding?.initialized && !modelLoading">
          <div class="segment-icon">
            <!-- Loading spinner when model is loading -->
            <svg *ngIf="modelLoading" class="spin" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
            </svg>
            <!-- Checkmark when loaded -->
            <svg *ngIf="!modelLoading" xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
              <polyline points="22 4 12 14.01 9 11.01"></polyline>
            </svg>
          </div>
          <div class="segment-info">
            <span class="segment-label">{{ modelLoading ? 'Loading' : 'Active' }}</span>
            <span class="segment-value">{{ getActiveModelDisplayText() }}</span>
          </div>
          <div class="status-dot"
               [class.green]="modelStatus?.embedding?.initialized && !modelLoading"
               [class.yellow]="modelLoading"
               [class.red]="!modelStatus?.embedding?.initialized && !modelLoading"></div>
        </div>

        <div class="divider"></div>

        <!-- Embedding Subprocess Restart Governor -->
        <div class="status-segment restart-segment"
             [class.ready]="restartStatus && restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
             [class.warning]="restartStatus && !restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
             [class.not-loaded]="restartStatus?.restartsPaused">
          <div class="segment-icon">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 2v6h-6"></path>
              <path d="M3 12a9 9 0 0 1 15-6.7L21 8"></path>
              <path d="M3 22v-6h6"></path>
              <path d="M21 12a9 9 0 0 1-15 6.7L3 16"></path>
            </svg>
          </div>
          <div class="segment-info">
            <span class="segment-label">Restarts</span>
            <span class="segment-value">{{ getRestartDisplayText() }}</span>
          </div>
          <div class="status-dot"
               [class.green]="restartStatus && restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
               [class.yellow]="restartStatus && !restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
               [class.red]="restartStatus?.restartsPaused"></div>
        </div>

        <!-- Expand/Collapse Icon -->
        <div class="expand-icon">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline [attr.points]="isExpanded ? '18 15 12 9 6 15' : '6 9 12 15 18 9'"></polyline>
          </svg>
        </div>
      </div>

      <!-- Expanded Panel -->
      <div class="expanded-panel" *ngIf="isExpanded">

        <!-- Model Loading Progress (shown when loading) -->
        <div class="loading-progress-section" *ngIf="modelLoading">
          <div class="loading-header">
            <svg class="spin" xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
            </svg>
            <h4>Model Loading in Progress</h4>
          </div>
          <div class="loading-details">
            <div class="loading-phase">{{ formatLoadingPhase(modelLoadingPhase || 'STARTING') }}</div>
            <div class="loading-message" *ngIf="modelLoadingMessage">{{ modelLoadingMessage }}</div>
            <div class="loading-time" *ngIf="modelLoadingElapsedMs > 0">
              Elapsed: {{ formatElapsedTime(modelLoadingElapsedMs) }}
            </div>
          </div>
          <div class="loading-bar">
            <div class="loading-bar-progress"></div>
          </div>
        </div>

        <!-- Model Source Status (Staging Service or Archive) -->
        <div class="panel-section source-section">
          <div class="section-header">
            <h4>{{ currentSourceType === 'archive' ? 'Model Archive' : (stagingConnected ? 'Remote Staging Service' : 'Staging Service') }}</h4>
            <span class="badge" [class.badge-success]="isSourceConnected()" [class.badge-warning]="isSourceConfigured() && !isSourceConnected()" [class.badge-error]="!isSourceConfigured()">
              {{ getSourceStatusBadge() }}
            </span>
          </div>

          <!-- Staging Service Details -->
          <div class="source-details" *ngIf="currentSourceType !== 'archive' && stagingConfig">
            <div class="detail-row">
              <span class="detail-label">Name:</span>
              <span class="detail-value">{{ stagingConfig.name }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">URL:</span>
              <span class="detail-value">{{ stagingConfig.endpointUrl }}</span>
            </div>
            <div class="detail-row" *ngIf="stagingConfig.lastError">
              <span class="detail-label">Error:</span>
              <span class="detail-value status-no">{{ stagingConfig.lastError }}</span>
            </div>
            <div class="detail-row" *ngIf="stagingConnected">
              <span class="detail-label">Models:</span>
              <span class="detail-value status-yes">{{ denseEncoderCount + crossEncoderCount }} available</span>
            </div>
          </div>

          <!-- Archive Details -->
          <div class="source-details" *ngIf="currentSourceType === 'archive'">
            <div class="detail-row">
              <span class="detail-label">Archive ID:</span>
              <span class="detail-value">{{ currentArchiveId || 'Not configured' }}</span>
            </div>
            <div class="detail-row" *ngIf="archiveStatus?.contentVersion">
              <span class="detail-label">Version:</span>
              <span class="detail-value">{{ archiveStatus?.contentVersion }}</span>
            </div>
            <div class="detail-row" *ngIf="archiveStatus?.description">
              <span class="detail-label">Description:</span>
              <span class="detail-value">{{ archiveStatus?.description }}</span>
            </div>
            <div class="detail-row" *ngIf="sourceStatus?.error">
              <span class="detail-label">Error:</span>
              <span class="detail-value status-no">{{ sourceStatus?.error }}</span>
            </div>
            <div class="detail-row" *ngIf="archiveStatus?.loaded">
              <span class="detail-label">Models:</span>
              <span class="detail-value status-yes">{{ denseEncoderCount + crossEncoderCount }} available</span>
            </div>
          </div>

          <!-- No Source Configured -->
          <div class="source-details" *ngIf="currentSourceType !== 'archive' && !stagingConfig">
            <div class="detail-row">
              <span class="detail-value status-pending">No staging service configured. Click "Open Model Staging" to add one.</span>
            </div>
          </div>
        </div>

        <!-- Available Dense Encoders -->
        <div class="panel-section">
          <div class="section-header">
            <h4>Dense Encoders (Embeddings)</h4>
            <span class="badge" [class.badge-success]="denseEncoderCount > 0">{{ denseEncoderCount }} from source</span>
          </div>
          <div class="model-list" *ngIf="denseEncoders.length > 0">
            <div class="model-item" *ngFor="let model of denseEncoders"
                 [class.active]="isModelActive(model)">
              <div class="model-info">
                <span class="model-name">{{ model.model_id }}</span>
                <span class="model-meta" *ngIf="model.metadata?.embedding_dim">{{ model.metadata?.embedding_dim }}d</span>
                <span class="model-meta loaded-badge" *ngIf="isDenseEncoderLoaded(model)">LOADED</span>
              </div>
              <div class="model-status">
                <span class="status-indicator"
                      [class.loaded]="isModelActive(model)"
                      [class.available]="!isModelActive(model)">
                  {{ getModelDisplayStatus(model) }}
                </span>
              </div>
            </div>
          </div>
          <div class="empty-state" *ngIf="denseEncoders.length === 0">
            <span *ngIf="!stagingConfig">No staging service configured. Add one in Model Staging.</span>
            <span *ngIf="stagingConfig && !stagingConnected">Staging service unavailable. Check connection.</span>
            <span *ngIf="stagingConfig && stagingConnected">No dense encoders in staging registry.</span>
          </div>
        </div>

        <!-- Available Cross-Encoders -->
        <div class="panel-section">
          <div class="section-header">
            <h4>Cross-Encoders (Rerankers)</h4>
            <span class="badge" [class.badge-success]="crossEncoderCount > 0">{{ crossEncoderCount }} from source</span>
          </div>
          <div class="model-list" *ngIf="crossEncoders.length > 0">
            <div class="model-item" *ngFor="let model of crossEncoders"
                 [class.active]="isModelActive(model)">
              <div class="model-info">
                <span class="model-name">{{ model.model_id }}</span>
              </div>
              <div class="model-status">
                <span class="status-indicator"
                      [class.loaded]="isModelActive(model)"
                      [class.available]="!isModelActive(model)">
                  {{ getModelDisplayStatus(model) }}
                </span>
              </div>
            </div>
          </div>
          <div class="empty-state" *ngIf="crossEncoders.length === 0">
            <span *ngIf="!stagingConfig">No staging service configured. Add one in Model Staging.</span>
            <span *ngIf="stagingConfig && !stagingConnected">Staging service unavailable. Check connection.</span>
            <span *ngIf="stagingConfig && stagingConnected">No cross-encoders in staging registry.</span>
          </div>
        </div>

        <!-- Open Staging Interface -->
        <div class="panel-section">
          <div class="action-buttons">
            <button class="action-btn primary" (click)="forceReloadModels()" [disabled]="forceReloading || isLoading">
              <svg *ngIf="forceReloading" class="spin" xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
              </svg>
              <span *ngIf="forceReloading">Reloading...</span>
              <span *ngIf="!forceReloading">Force Reload Models</span>
            </button>
            <button class="action-btn secondary" (click)="openModelStaging()">
              Open Model Staging
            </button>
            <button class="action-btn secondary" (click)="refreshAll()" [disabled]="isLoading">
              <span *ngIf="isLoading">Refreshing...</span>
              <span *ngIf="!isLoading">Refresh Status</span>
            </button>
          </div>
        </div>

        <!-- Embedding Subprocess Restarts -->
        <div class="panel-section" *ngIf="restartStatus">
          <div class="section-header">
            <h4>Embedding Subprocess Restarts</h4>
            <span class="badge"
                  [class.badge-success]="restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
                  [class.badge-warning]="!restartStatus.autoRestartEnabled && !restartStatus.restartsPaused"
                  [class.badge-error]="restartStatus.restartsPaused">
              {{ restartStatus.restartsPaused ? 'Paused' : (restartStatus.autoRestartEnabled ? 'Auto-restart on' : 'Auto-restart off') }}
            </span>
          </div>
          <div class="detail-grid">
            <div class="detail-row">
              <span class="detail-label">Subprocess</span>
              <span class="detail-value" [class.status-yes]="restartStatus.subprocessRunning" [class.status-no]="!restartStatus.subprocessRunning">
                {{ restartStatus.subprocessRunning ? 'Running' : 'Not running' }}
              </span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Native crashes</span>
              <span class="detail-value">{{ restartStatus.consecutiveNativeCrashes }} / {{ restartStatus.nativeCrashThreshold }}</span>
            </div>
            <div class="detail-row" *ngIf="restartStatus.pausedReason">
              <span class="detail-label">Paused reason</span>
              <span class="detail-value status-no">{{ restartStatus.pausedReason }}</span>
            </div>
            <div class="detail-row" *ngIf="restartStatus.lastCrashReason">
              <span class="detail-label">Last crash</span>
              <span class="detail-value">{{ restartStatus.lastCrashReason }}</span>
            </div>
          </div>
          <div class="action-buttons">
            <button class="action-btn primary" *ngIf="restartStatus.restartsPaused" (click)="resumeRestarts()" [disabled]="resumingRestarts">
              <span *ngIf="resumingRestarts">Resuming...</span>
              <span *ngIf="!resumingRestarts">Resume restarts</span>
            </button>
            <button class="action-btn"
                    [class.primary]="!restartStatus.autoRestartEnabled"
                    [class.secondary]="restartStatus.autoRestartEnabled"
                    (click)="setAutoRestart(!restartStatus.autoRestartEnabled)"
                    [disabled]="savingRestartConfig">
              <span *ngIf="savingRestartConfig">Saving...</span>
              <span *ngIf="!savingRestartConfig">{{ restartStatus.autoRestartEnabled ? 'Disable auto-restart' : 'Enable auto-restart' }}</span>
            </button>
          </div>
        </div>

        <!-- Current Fact Sheet Configuration -->
        <div class="panel-section">
          <div class="section-header">
            <h4>Fact Sheet: {{ modelStatus?.factSheetName || 'Loading...' }}</h4>
          </div>
          <div class="detail-grid" *ngIf="modelStatus">
            <div class="detail-row">
              <span class="detail-label">Embedding Model:</span>
              <span class="detail-value">{{ modelStatus?.embedding?.configuredModel || '(default)' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">Reranking:</span>
              <span class="detail-value">{{ modelStatus?.crossEncoder?.rerankingEnabled ? 'Enabled' : 'Disabled' }}</span>
            </div>
            <div class="detail-row" *ngIf="modelStatus?.crossEncoder?.rerankingEnabled">
              <span class="detail-label">Reranker Type:</span>
              <span class="detail-value">{{ formatRerankerType(modelStatus?.crossEncoder?.rerankerType) }}</span>
            </div>
            <div class="detail-row" *ngIf="modelStatus?.crossEncoder?.rerankingEnabled && modelStatus?.crossEncoder?.configuredModel">
              <span class="detail-label">Cross-Encoder:</span>
              <span class="detail-value">{{ modelStatus?.crossEncoder?.configuredModel }}</span>
            </div>
          </div>
        </div>

        <!-- Messages -->
        <div class="message success" *ngIf="successMessage">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
            <polyline points="22 4 12 14.01 9 11.01"></polyline>
          </svg>
          {{ successMessage }}
        </div>
        <div class="message error" *ngIf="errorMessage">
          <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="8" x2="12" y2="12"></line>
            <line x1="12" y1="16" x2="12.01" y2="16"></line>
          </svg>
          {{ errorMessage }}
        </div>
      </div>
    </div>
  `,
  styles: [`
    .model-status-bar {
      background: #1a1a1a;
      border-radius: 8px;
      border: 1px solid #333;
      overflow: hidden;
      font-size: 12px;
    }

    .status-bar-content {
      display: flex;
      align-items: center;
      padding: 8px 12px;
      cursor: pointer;
      transition: background 0.2s;
    }

    .status-bar-content:hover {
      background: #222;
    }

    .status-segment {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 8px;
      border-radius: 4px;
      transition: background 0.2s;
    }

    .segment-icon {
      color: #888;
      display: flex;
      align-items: center;
    }

    .segment-info {
      display: flex;
      flex-direction: column;
    }

    .segment-label {
      font-size: 10px;
      color: #888;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .segment-value {
      font-size: 12px;
      color: #fff;
      font-weight: 500;
    }

    .status-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      margin-left: 4px;
    }

    .status-dot.green { background: #4caf50; }
    .status-dot.yellow { background: #ff9800; }
    .status-dot.red { background: #f44336; }
    .status-dot.gray { background: #666; }

    .embedding-segment.ready .segment-icon { color: #4caf50; }
    .embedding-segment.warning .segment-icon { color: #ff9800; }
    .embedding-segment.not-loaded .segment-icon { color: #f44336; }

    .restart-segment.ready .segment-icon { color: #4caf50; }
    .restart-segment.warning .segment-icon { color: #ff9800; }
    .restart-segment.not-loaded .segment-icon { color: #f44336; }

    .reranker-segment.ready .segment-icon { color: #4caf50; }
    .reranker-segment.warning .segment-icon { color: #ff9800; }
    .reranker-segment.disabled .segment-icon { color: #666; }

    .divider {
      width: 1px;
      height: 24px;
      background: #333;
      margin: 0 12px;
    }

    .expand-icon {
      margin-left: auto;
      color: #666;
      transition: transform 0.2s;
    }

    .model-status-bar.expanded .expand-icon {
      transform: rotate(180deg);
    }

    .expanded-panel {
      border-top: 1px solid #333;
      padding: 16px;
      background: #111;
    }

    .panel-section {
      margin-bottom: 16px;
      padding-bottom: 16px;
      border-bottom: 1px solid #222;
    }

    .panel-section:last-of-type {
      margin-bottom: 0;
      padding-bottom: 0;
      border-bottom: none;
    }

    .section-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 12px;
    }

    .section-header h4 {
      margin: 0;
      font-size: 13px;
      font-weight: 600;
      color: #fff;
    }

    .status-badge {
      font-size: 10px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 500;
      text-transform: uppercase;
    }

    .status-badge.ready {
      background: rgba(76, 175, 80, 0.2);
      color: #4caf50;
    }

    .status-badge.warning {
      background: rgba(255, 152, 0, 0.2);
      color: #ff9800;
    }

    .status-badge.not-loaded {
      background: rgba(244, 67, 54, 0.2);
      color: #f44336;
    }

    .status-badge.disabled {
      background: rgba(102, 102, 102, 0.2);
      color: #888;
    }

    .badge {
      font-size: 10px;
      padding: 2px 8px;
      border-radius: 10px;
      background: #333;
      color: #aaa;
    }

    .detail-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
      margin-bottom: 12px;
    }

    .detail-row {
      display: flex;
      flex-direction: column;
    }

    .detail-label {
      font-size: 10px;
      color: #666;
      margin-bottom: 2px;
    }

    .detail-value {
      font-size: 12px;
      color: #ddd;
      word-break: break-all;
    }

    .detail-value.status-yes {
      color: #4caf50;
      font-weight: 600;
    }

    .detail-value.status-no {
      color: #f44336;
      font-weight: 600;
    }

    .detail-value.status-pending {
      color: #ff9800;
      font-weight: 500;
    }

    .detail-value.status-na {
      color: #888;
      font-style: italic;
    }

    .highlight-row {
      background: rgba(255, 255, 255, 0.03);
      padding: 4px 6px;
      border-radius: 4px;
      margin: -2px -6px;
    }

    .action-buttons {
      display: flex;
      gap: 8px;
    }

    .action-btn {
      padding: 6px 12px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 500;
      cursor: pointer;
      border: none;
      transition: all 0.2s;
    }

    .action-btn.primary {
      background: #2196f3;
      color: #fff;
    }

    .action-btn.primary:hover:not(:disabled) {
      background: #1976d2;
    }

    .action-btn.secondary {
      background: #333;
      color: #ddd;
      border: 1px solid #444;
    }

    .action-btn.secondary:hover:not(:disabled) {
      background: #444;
    }

    .action-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .message {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 10px 12px;
      border-radius: 4px;
      margin-top: 12px;
      font-size: 12px;
    }

    .message.success {
      background: rgba(76, 175, 80, 0.1);
      border: 1px solid rgba(76, 175, 80, 0.3);
      color: #4caf50;
    }

    .message.error {
      background: rgba(244, 67, 54, 0.1);
      border: 1px solid rgba(244, 67, 54, 0.3);
      color: #f44336;
    }

    /* Model list styles */
    .model-list {
      display: flex;
      flex-direction: column;
      gap: 6px;
      margin-bottom: 12px;
    }

    .model-item {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 10px;
      background: #1a1a1a;
      border-radius: 4px;
      border: 1px solid #333;
    }

    .model-item.active {
      border-color: #4caf50;
      background: rgba(76, 175, 80, 0.1);
    }

    .model-info {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .model-name {
      font-size: 12px;
      font-weight: 500;
      color: #fff;
    }

    .model-meta {
      font-size: 10px;
      color: #888;
      padding: 2px 6px;
      background: #333;
      border-radius: 3px;
    }

    .model-status {
      display: flex;
      align-items: center;
    }

    .status-indicator {
      font-size: 10px;
      font-weight: 600;
      padding: 3px 8px;
      border-radius: 3px;
      text-transform: uppercase;
    }

    .status-indicator.loaded {
      background: rgba(76, 175, 80, 0.2);
      color: #4caf50;
    }

    .status-indicator.available {
      background: rgba(33, 150, 243, 0.15);
      color: #64b5f6;
    }

    .loaded-badge {
      background: rgba(76, 175, 80, 0.3) !important;
      color: #4caf50 !important;
      font-weight: 600;
    }

    .empty-state {
      font-size: 12px;
      color: #888;
      padding: 16px;
      text-align: center;
      background: #1a1a1a;
      border-radius: 4px;
      border: 1px dashed #333;
      margin-bottom: 12px;
    }

    .badge-success {
      background: rgba(76, 175, 80, 0.2) !important;
      color: #4caf50 !important;
    }

    .badge-warning {
      background: rgba(255, 152, 0, 0.2) !important;
      color: #ff9800 !important;
    }

    .badge-error {
      background: rgba(244, 67, 54, 0.2) !important;
      color: #f44336 !important;
    }

    .source-segment.ready .segment-icon { color: #4caf50; }
    .source-segment.warning .segment-icon { color: #ff9800; }
    .source-segment.not-loaded .segment-icon { color: #f44336; }

    .source-section {
      background: rgba(33, 150, 243, 0.05);
      margin: -16px -16px 16px -16px;
      padding: 16px;
      border-bottom: 1px solid #333;
    }

    .source-details {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-top: 8px;
    }

    /* Loading spinner animation */
    .spin {
      animation: spin 1s linear infinite;
    }

    @keyframes spin {
      from { transform: rotate(0deg); }
      to { transform: rotate(360deg); }
    }

    .embedding-segment.loading .segment-icon { color: #ff9800; }

    /* Loading progress section */
    .loading-progress-section {
      background: rgba(255, 152, 0, 0.1);
      border: 1px solid rgba(255, 152, 0, 0.3);
      border-radius: 8px;
      padding: 16px;
      margin-bottom: 16px;
    }

    .loading-header {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 12px;
    }

    .loading-header .spin {
      color: #ff9800;
    }

    .loading-header h4 {
      margin: 0;
      color: #ff9800;
      font-size: 14px;
      font-weight: 600;
    }

    .loading-details {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }

    .loading-phase {
      font-size: 13px;
      color: #fff;
      font-weight: 500;
    }

    .loading-message {
      font-size: 12px;
      color: #aaa;
    }

    .loading-time {
      font-size: 11px;
      color: #888;
      margin-top: 4px;
    }

    .loading-bar {
      height: 4px;
      background: rgba(255, 152, 0, 0.2);
      border-radius: 2px;
      margin-top: 12px;
      overflow: hidden;
    }

    .loading-bar-progress {
      height: 100%;
      background: #ff9800;
      border-radius: 2px;
      animation: loading-pulse 1.5s ease-in-out infinite;
      width: 30%;
    }

    @keyframes loading-pulse {
      0% { transform: translateX(-100%); }
      100% { transform: translateX(400%); }
    }
  `]
})
export class ModelStatusIndicatorComponent implements OnInit, OnDestroy, OnChanges {

  @Input() factSheetId: number | null = null;
  @Output() openStaging = new EventEmitter<void>();

  // State
  isExpanded = false;
  isLoading = false;

  // Model status
  modelStatus: FactSheetModelStatusDto | null = null;
  versionInfo: RegistryVersionInfo | null = null;
  registry: ModelRegistry | null = null;
  sourceStatus: ModelSourceStatus | null = null;

  // Current model source (from fact sheet configuration)
  currentSourceType: ModelSourceType = 'staging';
  currentArchiveId: string | null = null;

  // Staging config (from UI/database)
  stagingConfig: StagingServiceConfig | null = null;
  stagingConnected: boolean = false;
  remoteRegistry: any = null;

  // Archive config
  archiveStatus: ArchiveStatus | null = null;
  archiveModels: ArchiveModelInfo[] = [];
  availableArchives: ArchiveFileInfo[] = [];

  // Counts and model lists
  denseEncoderCount = 0;
  crossEncoderCount = 0;
  denseEncoders: ModelEntry[] = [];
  crossEncoders: ModelEntry[] = [];

  // Active models from remote service (type -> modelId)
  remoteActiveModels: { [type: string]: string } = {};

  // Model loading state (for lazy initialization tracking)
  modelLoading = false;
  modelLoadingPhase: string | null = null;
  modelLoadingMessage: string | null = null;
  modelLoadingElapsedMs = 0;

  // Messages
  successMessage: string | null = null;
  errorMessage: string | null = null;

  // Force reload state
  forceReloading = false;

  // Embedding-subprocess restart governor state (hot-bar indicator)
  restartStatus: EmbeddingRestartStatusDto | null = null;
  resumingRestarts = false;
  savingRestartConfig = false;

  // Subscriptions
  private refreshSubscription?: Subscription;
  private registryChangeSubscription?: Subscription;
  private loadingPollSubscription?: Subscription;
  private modelStatusSubscription?: Subscription;
  private messageTimeout?: any;
  private destroy$ = new Subject<void>();

  constructor(
    private http: HttpClient,
    private baseService: BaseService,
    private modelRegistryService: ModelRegistryService,
    private webSocketService: WebSocketService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.refreshAll();

    // Subscribe to registry change events for immediate updates
    this.registryChangeSubscription = this.modelRegistryService.changes$.subscribe(event => {
      console.log('Registry changed:', event.type, event.modelId || event.archiveId || '');
      this.refreshAll();
    });

    // Subscribe to WebSocket model status updates for real-time updates
    this.subscribeToModelStatusUpdates();

    // Auto-refresh every 30 seconds as fallback
    this.refreshSubscription = interval(30000).subscribe(() => {
      this.refreshAll();
    });
  }

  /**
   * Subscribe to WebSocket model status updates for real-time UI updates.
   * This provides push-based updates when embedding model status changes.
   */
  private subscribeToModelStatusUpdates(): void {
    // Connect WebSocket and subscribe to model status
    this.webSocketService.connect();

    this.modelStatusSubscription = this.webSocketService.subscribeToModelStatus().pipe(
      takeUntil(this.destroy$)
    ).subscribe({
      next: (status: ModelStatusUpdate) => {
        this.handleModelStatusUpdate(status);
      },
      error: (err) => {
        console.error('[WS-MODEL] Error in model status WebSocket:', err);
      }
    });

    console.log('[WS-MODEL] Model status indicator subscribed to WebSocket updates');
  }

  /**
   * Handle incoming model status updates from WebSocket.
   * Updates the component state with real-time embedding status.
   */
  private handleModelStatusUpdate(status: ModelStatusUpdate): void {
    if (!status.embedding) return;

    const embedding = status.embedding;
    const wasLoading = this.modelLoading;
    const wasInitialized = this.modelStatus?.embedding?.initialized;

    // Update loading state
    this.modelLoading = embedding.loading || false;
    this.modelLoadingPhase = embedding.loadingPhase || null;
    this.modelLoadingMessage = embedding.loadingMessage || null;
    this.modelLoadingElapsedMs = embedding.loadingElapsedMs || 0;

    // Update model status if we have one
    if (this.modelStatus?.embedding) {
      this.modelStatus.embedding = {
        ...this.modelStatus.embedding,
        initialized: embedding.initialized,
        loading: embedding.loading,
        loadingPhase: embedding.loadingPhase,
        loadingMessage: embedding.loadingMessage,
        loadingElapsedMs: embedding.loadingElapsedMs,
        dimensions: embedding.dimensions || this.modelStatus.embedding.dimensions,
        activeModel: embedding.modelId || this.modelStatus.embedding.activeModel,
        available: embedding.initialized || this.modelStatus.embedding.available
      };
    }

    // Start/stop loading poll based on loading state
    if (this.modelLoading && !wasLoading) {
      this.startLoadingPoll();
    } else if (!this.modelLoading && wasLoading) {
      this.stopLoadingPoll();
      // Full refresh when loading completes
      this.refreshAll();
    }

    // If model became initialized, do a full refresh to get accurate data
    if (!wasInitialized && embedding.initialized && !embedding.loading) {
      console.log('[WS-MODEL] Model became initialized, refreshing status');
      this.refreshAll();
    }

    // Trigger change detection
    this.cdr.markForCheck();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      // Fact sheet changed, refresh
      this.refreshAll();
    }
  }

  ngOnDestroy(): void {
    this.refreshSubscription?.unsubscribe();
    this.registryChangeSubscription?.unsubscribe();
    this.loadingPollSubscription?.unsubscribe();
    this.modelStatusSubscription?.unsubscribe();
    this.webSocketService.unsubscribeFromModelStatus();
    this.destroy$.next();
    this.destroy$.complete();
    if (this.messageTimeout) {
      clearTimeout(this.messageTimeout);
    }
  }

  toggleExpanded(): void {
    this.isExpanded = !this.isExpanded;
  }

  refreshAll(): void {
    this.isLoading = true;
    this.clearMessages();
    this.fetchRestartStatus();

    // Build the model status request URL
    const modelStatusUrl = this.factSheetId
      ? `${this.baseService.backendUrl}/fact-sheets/${this.factSheetId}/model-status`
      : `${this.baseService.backendUrl}/fact-sheets/active/model-status`;

    // First fetch the model status to determine source type
    this.http.get<FactSheetModelStatusDto>(modelStatusUrl).pipe(
      catchError(err => of(null))
    ).subscribe({
      next: (modelStatus) => {
        this.modelStatus = modelStatus;

        // Extract loading state from embedding status
        const embedding = modelStatus?.embedding;
        if (embedding) {
          const wasLoading = this.modelLoading;
          this.modelLoading = embedding.loading || false;
          this.modelLoadingPhase = embedding.loadingPhase || null;
          this.modelLoadingMessage = embedding.loadingMessage || null;
          this.modelLoadingElapsedMs = embedding.loadingElapsedMs || 0;

          // Start/stop loading poll based on loading state
          if (this.modelLoading && !wasLoading) {
            this.startLoadingPoll();
          } else if (!this.modelLoading && wasLoading) {
            this.stopLoadingPoll();
          }
        }

        // Determine source type from fact sheet configuration
        const configuredSource = modelStatus?.embedding?.configuredSource;
        this.currentArchiveId = modelStatus?.embedding?.configuredArchiveId || null;

        // Map source type
        if (configuredSource === 'archive' && this.currentArchiveId) {
          this.currentSourceType = 'archive';
          this.fetchArchiveModels();
        } else if (configuredSource === 'staging' || configuredSource === 'registry' || !configuredSource) {
          this.currentSourceType = 'staging';
          this.fetchStagingModels();
        } else {
          this.currentSourceType = 'default';
          this.fetchStagingModels(); // Fall back to staging for default
        }
      },
      error: (err) => {
        console.error('Error fetching model status:', err);
        this.isLoading = false;
      }
    });
  }

  /**
   * Start polling more frequently when model is loading.
   */
  private startLoadingPoll(): void {
    this.stopLoadingPoll();
    // Poll every 500ms when loading to show progress
    this.loadingPollSubscription = interval(500).subscribe(() => {
      if (!this.modelLoading) {
        this.stopLoadingPoll();
        return;
      }
      // Fetch just the model status to update loading progress
      const modelStatusUrl = this.factSheetId
        ? `${this.baseService.backendUrl}/fact-sheets/${this.factSheetId}/model-status`
        : `${this.baseService.backendUrl}/fact-sheets/active/model-status`;

      this.http.get<FactSheetModelStatusDto>(modelStatusUrl).pipe(
        catchError(err => of(null))
      ).subscribe(status => {
        if (status?.embedding) {
          this.modelLoading = status.embedding.loading || false;
          this.modelLoadingPhase = status.embedding.loadingPhase || null;
          this.modelLoadingMessage = status.embedding.loadingMessage || null;
          this.modelLoadingElapsedMs = status.embedding.loadingElapsedMs || 0;

          // If loading completed, stop polling and do a full refresh
          if (!this.modelLoading) {
            this.stopLoadingPoll();
            this.refreshAll();
          }
        }
      });
    });
  }

  /**
   * Stop the loading poll.
   */
  private stopLoadingPoll(): void {
    this.loadingPollSubscription?.unsubscribe();
    this.loadingPollSubscription = undefined;
  }

  /**
   * Fetch models from the remote staging service.
   */
  private fetchStagingModels(): void {
    forkJoin({
      stagingConfig: this.http.get<StagingServiceConfig>(`${this.baseService.backendUrl}/staging-config/configs/active`).pipe(
        catchError(err => of(null))
      ),
      remoteRegistry: this.http.get<any>(`${this.baseService.backendUrl}/staging-config/remote/registry`).pipe(
        catchError(err => of(null))
      ),
      remoteActive: this.http.get<any>(`${this.baseService.backendUrl}/staging-config/remote/active`).pipe(
        catchError(err => of(null))
      )
    }).subscribe({
      next: (results) => {
        this.stagingConfig = results.stagingConfig;
        this.remoteRegistry = results.remoteRegistry;
        this.archiveStatus = null; // Clear archive state
        this.archiveModels = [];

        // Store remote active models (type -> modelId mapping)
        if (results.remoteActive && results.remoteActive.active) {
          this.remoteActiveModels = results.remoteActive.active;
        } else {
          this.remoteActiveModels = {};
        }

        // Build source status from staging config
        this.sourceStatus = {
          configured: !!results.stagingConfig,
          sourceType: 'staging',
          description: results.stagingConfig?.endpointUrl || null,
          available: results.stagingConfig?.verified || false,
          error: results.stagingConfig?.lastError || null,
          encoderCount: 0,
          crossEncoderCount: 0
        };

        // If we have a remote registry, it means we're connected
        if (results.remoteRegistry && results.remoteRegistry.models) {
          this.stagingConnected = true;
          this.sourceStatus.available = true;

          // Extract models from remote registry
          const models = results.remoteRegistry.models;
          const allModels = Object.values(models) as any[];

          // Categorize by type
          this.denseEncoders = allModels.filter((m: any) =>
            m.type === 'dense_encoder' || m.type === 'encoder' ||
            m.type === 'sparse_encoder' || m.metadata?.model_type === 'dense'
          );
          this.crossEncoders = allModels.filter((m: any) =>
            m.type === 'cross_encoder' || m.type === 'reranker' ||
            m.metadata?.model_type === 'cross_encoder'
          );

          this.denseEncoderCount = this.denseEncoders.length;
          this.crossEncoderCount = this.crossEncoders.length;
          this.sourceStatus.encoderCount = this.denseEncoderCount;
          this.sourceStatus.crossEncoderCount = this.crossEncoderCount;
        } else {
          this.stagingConnected = false;
          this.denseEncoders = [];
          this.crossEncoders = [];
          this.denseEncoderCount = 0;
          this.crossEncoderCount = 0;
        }

        this.isLoading = false;
      },
      error: (err) => {
        console.error('Error fetching staging models:', err);
        this.isLoading = false;
      }
    });
  }

  /**
   * Fetch models from an archive.
   * First checks available archives, loads the matching one if needed, then gets models.
   */
  private fetchArchiveModels(): void {
    // Clear staging state
    this.stagingConfig = null;
    this.stagingConnected = false;
    this.remoteRegistry = null;
    this.remoteActiveModels = {};

    // First get list of available archives
    this.http.get<ArchiveFileInfo[]>(`${this.baseService.backendUrl}/archives`).pipe(
      catchError(err => of([]))
    ).subscribe({
      next: (archives) => {
        this.availableArchives = archives;

        // Find the archive matching our configured archiveId
        const targetArchive = archives.find(a => a.archiveId === this.currentArchiveId);

        if (!targetArchive) {
          // Archive not found
          this.sourceStatus = {
            configured: true,
            sourceType: 'archive',
            description: `Archive not found: ${this.currentArchiveId}`,
            available: false,
            error: `Archive '${this.currentArchiveId}' not found in available archives`,
            encoderCount: 0,
            crossEncoderCount: 0
          };
          this.denseEncoders = [];
          this.crossEncoders = [];
          this.denseEncoderCount = 0;
          this.crossEncoderCount = 0;
          this.isLoading = false;
          return;
        }

        // Check if archive is already loaded
        if (targetArchive.loaded) {
          this.loadModelsFromArchive(targetArchive);
        } else {
          // Load the archive first
          this.http.post<ArchiveStatus>(`${this.baseService.backendUrl}/archives/load`, {
            archivePath: targetArchive.path
          }).pipe(
            catchError(err => of(null))
          ).subscribe({
            next: (status) => {
              if (status && status.loaded) {
                this.loadModelsFromArchive(targetArchive);
              } else {
                this.sourceStatus = {
                  configured: true,
                  sourceType: 'archive',
                  description: `Failed to load archive: ${this.currentArchiveId}`,
                  available: false,
                  error: 'Failed to load archive',
                  encoderCount: 0,
                  crossEncoderCount: 0
                };
                this.isLoading = false;
              }
            },
            error: (err) => {
              console.error('Error loading archive:', err);
              this.isLoading = false;
            }
          });
        }
      },
      error: (err) => {
        console.error('Error fetching archives:', err);
        this.isLoading = false;
      }
    });
  }

  /**
   * Load models from a loaded archive.
   */
  private loadModelsFromArchive(archive: ArchiveFileInfo): void {
    forkJoin({
      status: this.http.get<ArchiveStatus>(`${this.baseService.backendUrl}/archives/status`).pipe(
        catchError(err => of(null))
      ),
      models: this.http.get<ArchiveModelInfo[]>(`${this.baseService.backendUrl}/archives/models`).pipe(
        catchError(err => of([]))
      )
    }).subscribe({
      next: (results) => {
        this.archiveStatus = results.status;
        this.archiveModels = results.models;

        // Build source status from archive
        this.sourceStatus = {
          configured: true,
          sourceType: 'archive',
          description: archive.archiveId || archive.name,
          available: true,
          error: null,
          encoderCount: 0,
          crossEncoderCount: 0
        };

        // Convert archive models to ModelEntry format for display
        this.denseEncoders = results.models
          .filter(m => m.type === 'encoder' || m.type === 'dense_encoder' || m.type === 'sparse_encoder')
          .map(m => this.archiveModelToModelEntry(m));

        this.crossEncoders = results.models
          .filter(m => m.type === 'cross_encoder' || m.type === 'reranker')
          .map(m => this.archiveModelToModelEntry(m));

        this.denseEncoderCount = this.denseEncoders.length;
        this.crossEncoderCount = this.crossEncoders.length;
        this.sourceStatus.encoderCount = this.denseEncoderCount;
        this.sourceStatus.crossEncoderCount = this.crossEncoderCount;

        this.isLoading = false;
      },
      error: (err) => {
        console.error('Error loading models from archive:', err);
        this.isLoading = false;
      }
    });
  }

  /**
   * Convert an ArchiveModelInfo to ModelEntry format for unified display.
   */
  private archiveModelToModelEntry(archiveModel: ArchiveModelInfo): ModelEntry {
    return {
      model_id: archiveModel.modelId,
      type: archiveModel.type,
      path: archiveModel.path,
      status: 'available', // Archive models are always available once loaded
      metadata: {
        embedding_dim: archiveModel.embeddingDim || undefined,
        model_type: archiveModel.type
      }
    };
  }

  /**
   * Fetch the embedding-subprocess restart-governor status for the hot-bar indicator.
   */
  private fetchRestartStatus(): void {
    this.http.get<EmbeddingRestartStatusDto>(`${this.baseService.backendUrl}/embedding-restart/status`).pipe(
      catchError(() => of(null))
    ).subscribe(status => {
      this.restartStatus = status;
      this.cdr.markForCheck();
    });
  }

  /**
   * Short label for the Restarts segment in the status bar.
   */
  getRestartDisplayText(): string {
    if (!this.restartStatus) return '—';
    if (this.restartStatus.restartsPaused) return 'Paused';
    return this.restartStatus.autoRestartEnabled ? 'Auto: on' : 'Auto: off';
  }

  /**
   * Clear a paused/tripped embedding-restart state and bring the subprocess back.
   */
  resumeRestarts(): void {
    this.resumingRestarts = true;
    this.clearMessages();
    this.http.post<EmbeddingRestartStatusDto>(`${this.baseService.backendUrl}/embedding-restart/resume`, {}).pipe(
      catchError(() => {
        this.resumingRestarts = false;
        this.errorMessage = 'Failed to resume embedding restarts';
        this.autoHideMessage();
        this.cdr.markForCheck();
        return of(null);
      })
    ).subscribe(status => {
      this.resumingRestarts = false;
      if (status) {
        this.restartStatus = status;
        this.successMessage = 'Embedding restarts resumed';
        this.autoHideMessage();
      }
      this.cdr.markForCheck();
    });
  }

  /**
   * Enable or disable embedding auto-restart from the hot bar (persists via the config endpoint).
   * Enabling also clears a prior pause server-side.
   */
  setAutoRestart(enabled: boolean): void {
    if (!this.restartStatus) return;
    this.savingRestartConfig = true;
    this.clearMessages();
    this.http.put<EmbeddingRestartStatusDto>(`${this.baseService.backendUrl}/embedding-restart/config`, {
      autoRestartEnabled: enabled,
      nativeCrashThreshold: this.restartStatus.nativeCrashThreshold
    }).pipe(
      catchError(() => {
        this.savingRestartConfig = false;
        this.errorMessage = 'Failed to update auto-restart setting';
        this.autoHideMessage();
        this.cdr.markForCheck();
        return of(null);
      })
    ).subscribe(status => {
      this.savingRestartConfig = false;
      if (status) {
        this.restartStatus = status;
        this.successMessage = enabled ? 'Auto-restart enabled' : 'Auto-restart disabled';
        this.autoHideMessage();
      }
      this.cdr.markForCheck();
    });
  }

  openModelStaging(): void {
    this.openStaging.emit();
    this.isExpanded = false;
  }

  /**
   * Force reload models from staging/archive.
   * This will trigger a refresh of the registry and reload the embedding model.
   */
  forceReloadModels(): void {
    this.forceReloading = true;
    this.clearMessages();

    // Call the refresh-and-reload endpoint
    this.http.post<any>(`${this.baseService.backendUrl}/models/registry/refresh-and-reload`, {}).subscribe({
      next: (result) => {
        this.forceReloading = false;
        if (result.success) {
          this.successMessage = 'Models reloaded successfully!';
          this.refreshAll();
        } else {
          this.errorMessage = result.error || 'Failed to reload models';
        }
        this.autoHideMessage();
      },
      error: (err) => {
        this.forceReloading = false;
        this.errorMessage = err.error?.error || err.message || 'Failed to reload models';
        this.autoHideMessage();
      }
    });
  }

  // Source display helpers

  /**
   * Get the label for the current source type.
   * Shows "Remote Staging" when connected to a remote staging service.
   */
  getSourceLabel(): string {
    if (this.currentSourceType === 'archive') {
      return 'Archive';
    }
    // Show "Remote Staging" when connected to a remote staging service
    if (this.stagingConnected && this.stagingConfig) {
      return 'Remote Staging';
    }
    // Show "Staging" when configured but not connected, or not configured
    return 'Staging';
  }

  /**
   * Check if any model source is configured.
   */
  isSourceConfigured(): boolean {
    if (this.currentSourceType === 'archive') {
      return !!this.currentArchiveId;
    }
    return !!this.stagingConfig;
  }

  /**
   * Check if the model source is connected/available.
   */
  isSourceConnected(): boolean {
    if (this.currentSourceType === 'archive') {
      return this.archiveStatus?.loaded || false;
    }
    return this.stagingConnected;
  }

  /**
   * Get the display text for the current source.
   */
  getSourceDisplayText(): string {
    if (this.currentSourceType === 'archive') {
      if (!this.currentArchiveId) return 'Not Configured';
      if (this.archiveStatus?.loaded) {
        return this.currentArchiveId;
      }
      if (this.sourceStatus?.error) return 'Error';
      return 'Loading...';
    }

    // Staging source
    if (!this.stagingConfig && !this.stagingConnected) return 'Not Configured';
    if (!this.stagingConnected) return 'Disconnected';

    // Show the staging config name if available
    if (this.stagingConfig?.name) {
      return this.stagingConfig.name;
    }
    return 'Connected';
  }

  /**
   * Get the badge text for the source status.
   */
  getSourceStatusBadge(): string {
    if (this.currentSourceType === 'archive') {
      if (!this.currentArchiveId) return 'Not Configured';
      if (this.archiveStatus?.loaded) return 'Loaded';
      if (this.sourceStatus?.error) return 'Error';
      return 'Loading';
    }

    // Staging source
    if (this.stagingConnected) return 'Connected';
    if (this.stagingConfig) return 'Disconnected';
    return 'Not Configured';
  }

  formatSourceType(type: string | null): string {
    if (!type) return 'Not Configured';

    // If we have a staging config, show its details
    if (this.stagingConfig) {
      return `Remote Staging: ${this.stagingConfig.endpointUrl}`;
    }

    switch (type.toLowerCase()) {
      case 'staging':
        return 'Remote Staging Service';
      case 'archive':
        return 'Local Archive (.karch)';
      default:
        return type;
    }
  }

  // Helper methods

  /**
   * Get the display text for the active model status.
   * Shows loading phase/message when loading, model name when loaded.
   */
  getActiveModelDisplayText(): string {
    if (this.modelLoading) {
      // Show loading phase or message
      if (this.modelLoadingMessage) {
        return this.modelLoadingMessage;
      }
      if (this.modelLoadingPhase) {
        return this.formatLoadingPhase(this.modelLoadingPhase);
      }
      return 'Initializing...';
    }

    if (this.modelStatus?.embedding?.initialized) {
      return this.modelStatus.embedding.activeModel || 'Loaded';
    }
    return 'Not loaded';
  }

  /**
   * Format loading phase to user-friendly text.
   */
  formatLoadingPhase(phase: string): string {
    const phaseMap: { [key: string]: string } = {
      'IDLE': 'Waiting...',
      'STARTING': 'Starting...',
      'LOOKING_UP_REGISTRY': 'Looking up registry...',
      'LOADING_MODEL_FILES': 'Loading model files...',
      'CREATING_ENCODER': 'Creating encoder...',
      'TESTING_ENCODER': 'Testing encoder...',
      'COMPLETE': 'Complete',
      'FAILED': 'Failed'
    };
    return phaseMap[phase] || phase;
  }

  /**
   * Format elapsed time in a human-readable format.
   */
  formatElapsedTime(ms: number): string {
    if (ms < 1000) return `${ms}ms`;
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}m ${remainingSeconds}s`;
  }

  getEmbeddingStatusText(): string {
    if (!this.modelStatus?.embedding) return 'Loading...';

    const e = this.modelStatus.embedding;
    if (!e.available) return 'Not available';
    if (!e.initialized) return 'Not loaded';
    if (!e.matchesConfig) return 'Mismatch';
    return e.activeModel || 'Ready';
  }

  getRerankerStatusText(): string {
    if (!this.modelStatus?.crossEncoder) return 'Loading...';

    const r = this.modelStatus.crossEncoder;
    if (!r.rerankingEnabled) return 'Disabled';
    if (r.rerankerType !== 'cross_encoder') return r.rerankerType || 'N/A';
    if (!r.available) return 'Not found';
    if (!r.matchesConfig) return 'Not ready';
    return r.configuredModel || 'Ready';
  }

  getEmbeddingBadgeText(): string {
    if (!this.modelStatus?.embedding) return 'Loading';

    const e = this.modelStatus.embedding;
    if (!e.available) return 'Unavailable';
    if (!e.initialized) return 'Not Loaded';
    if (!e.matchesConfig) return 'Mismatch';
    return 'Ready';
  }

  getRerankerBadgeText(): string {
    if (!this.modelStatus?.crossEncoder) return 'Loading';

    const r = this.modelStatus.crossEncoder;
    if (!r.rerankingEnabled) return 'Disabled';
    if (!r.matchesConfig) return 'Not Ready';
    return 'Ready';
  }

  getLoadStatusText(crossEncoder: CrossEncoderStatusDto | null | undefined): string {
    if (!crossEncoder) return 'Unknown';

    switch (crossEncoder.loadStatus) {
      case 'loaded':
        return 'Yes';
      case 'available_on_demand':
        return 'Ready (loads on first use)';
      case 'not_available':
        return 'No - Model not found';
      case 'not_configured':
        return 'No - Not configured';
      case 'not_applicable':
        return 'N/A (uses ' + this.formatRerankerType(crossEncoder.rerankerType) + ')';
      case 'disabled':
        return 'N/A (disabled)';
      default:
        return crossEncoder.loaded ? 'Yes' : 'No';
    }
  }

  formatRerankerType(type: string | null | undefined): string {
    if (!type) return 'Unknown';
    switch (type.toLowerCase()) {
      case 'cross_encoder':
        return 'Cross-Encoder';
      case 'rrf':
        return 'RRF (Reciprocal Rank Fusion)';
      case 'mmr':
        return 'MMR (Max Marginal Relevance)';
      case 'none':
        return 'None';
      default:
        return type;
    }
  }

  formatDate(dateStr: string | null): string {
    if (!dateStr) return 'Unknown';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch {
      return dateStr;
    }
  }

  /**
   * Check if a model is active in the remote staging service.
   * Uses multiple sources: model's status field, remote active models endpoint,
   * and the model's type to determine the correct active status.
   */
  isModelActive(model: ModelEntry): boolean {
    if (!model) return false;

    // Check 1: Model's own status field from registry (case-insensitive)
    if (model.status) {
      const status = model.status.toLowerCase();
      if (status === 'active' || status === 'enabled' || status === 'loaded') {
        return true;
      }
    }

    // Check 2: Remote active models map (type -> modelId)
    // Map model types to the keys used in the active models response
    const typeMapping: { [key: string]: string[] } = {
      'dense_encoder': ['encoder', 'dense_encoder'],
      'encoder': ['encoder', 'dense_encoder'],
      'sparse_encoder': ['sparse_encoder'],
      'cross_encoder': ['cross_encoder', 'reranker'],
      'reranker': ['cross_encoder', 'reranker']
    };

    const modelType = model.type?.toLowerCase() || '';
    const possibleKeys = typeMapping[modelType] || [modelType];

    for (const key of possibleKeys) {
      if (this.remoteActiveModels[key] === model.model_id) {
        return true;
      }
    }

    return false;
  }

  /**
   * Get the display status for a model.
   * Returns 'ACTIVE' for active models, 'Available' for available models.
   */
  getModelDisplayStatus(model: ModelEntry): string {
    if (this.isModelActive(model)) {
      return 'ACTIVE';
    }
    return 'Available';
  }

  /**
   * Check if the dense encoder model is currently loaded in the local embedding service.
   * This is different from being "active" in the remote service.
   */
  isDenseEncoderLoaded(model: ModelEntry): boolean {
    return this.modelStatus?.embedding?.activeModel === model.model_id;
  }

  private clearMessages(): void {
    this.successMessage = null;
    this.errorMessage = null;
  }

  private autoHideMessage(): void {
    this.messageTimeout = setTimeout(() => {
      this.successMessage = null;
      this.errorMessage = null;
    }, 5000);
  }
}
