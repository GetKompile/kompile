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

import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

export interface FallbackChainEntry {
  agentName: string;
  modelId: string;
}

export interface ModelFallbackConfig {
  enabled: boolean;
  perCallTimeoutSeconds: number;
  maxAttempts: number;
  throttleBackoffSeconds: number;
  consecutiveTimeoutsBeforeSwitch: number;
  fallbackChain: FallbackChainEntry[];
  throttleSignals: string[];
}

export interface AvailableAgentModel {
  agentName: string;
  displayName: string;
  available: boolean;
  currentModel: string;
  availableModels: string[];
  modelSource: string;
}

@Component({
  selector: 'app-model-fallback-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatDividerModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './model-fallback-panel.component.html',
  styleUrls: ['./model-fallback-panel.component.css']
})
export class ModelFallbackPanelComponent implements OnInit {

  config: ModelFallbackConfig = {
    enabled: false,
    perCallTimeoutSeconds: 60,
    maxAttempts: 3,
    throttleBackoffSeconds: 30,
    consecutiveTimeoutsBeforeSwitch: 2,
    fallbackChain: [],
    throttleSignals: []
  };

  availableAgents: AvailableAgentModel[] = [];
  loading = false;
  saving = false;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadConfig();
    this.loadAvailableModels();
  }

  loadConfig(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.http.get<ModelFallbackConfig>('/api/model-fallback/config').subscribe({
      next: (cfg) => {
        this.config = cfg;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load model fallback config:', err.message);
        this.loading = false;
        this.cdr.markForCheck();
      }
    });
  }

  loadAvailableModels(): void {
    this.http.get<AvailableAgentModel[]>('/api/model-fallback/available-models').subscribe({
      next: (agents) => {
        this.availableAgents = agents || [];
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load available models:', err.message);
        this.cdr.markForCheck();
      }
    });
  }

  saveConfig(): void {
    this.saving = true;
    this.cdr.markForCheck();
    this.http.put<ModelFallbackConfig>('/api/model-fallback/config', this.config).subscribe({
      next: (updated) => {
        this.config = updated;
        this.saving = false;
        this.snackBar.open('Model fallback configuration saved', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open('Failed to save configuration: ' + (err.error?.message || err.message), 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  addChainEntry(): void {
    this.config.fallbackChain = [
      ...this.config.fallbackChain,
      { agentName: '', modelId: '' }
    ];
    this.cdr.markForCheck();
  }

  removeChainEntry(index: number): void {
    this.config.fallbackChain = this.config.fallbackChain.filter((_, i) => i !== index);
    this.cdr.markForCheck();
  }

  moveEntryUp(index: number): void {
    if (index <= 0) return;
    const chain = [...this.config.fallbackChain];
    [chain[index - 1], chain[index]] = [chain[index], chain[index - 1]];
    this.config.fallbackChain = chain;
    this.cdr.markForCheck();
  }

  moveEntryDown(index: number): void {
    if (index >= this.config.fallbackChain.length - 1) return;
    const chain = [...this.config.fallbackChain];
    [chain[index], chain[index + 1]] = [chain[index + 1], chain[index]];
    this.config.fallbackChain = chain;
    this.cdr.markForCheck();
  }

  getAvailableModelsForAgent(agentName: string): string[] {
    if (!agentName) return [];
    const agent = this.availableAgents.find(a => a.agentName === agentName);
    return agent ? (agent.availableModels || []) : [];
  }

  onAgentChanged(entry: FallbackChainEntry): void {
    entry.modelId = '';
    this.cdr.markForCheck();
  }

  trackByIndex(index: number): number {
    return index;
  }
}
