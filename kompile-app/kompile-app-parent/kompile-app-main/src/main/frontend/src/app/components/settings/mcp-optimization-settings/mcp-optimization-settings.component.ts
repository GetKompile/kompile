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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, takeUntil } from 'rxjs';

import {
  McpOptimizationService,
  McpOptimizationConfig,
  MetaToolMode
} from '../../../services/mcp-optimization.service';

@Component({
  selector: 'app-mcp-optimization-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatCardModule,
    MatTooltipModule,
    MatChipsModule,
    MatDividerModule
  ],
  templateUrl: './mcp-optimization-settings.component.html',
  styleUrls: ['./mcp-optimization-settings.component.scss']
})
export class McpOptimizationSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: McpOptimizationConfig;
  configFilePath: string | null = null;

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  newAlwaysExposedTool = '';

  readonly metaToolModes: { value: MetaToolMode; label: string; description: string }[] = [
    {
      value: 'DIRECT',
      label: 'Direct',
      description: 'Expose every @Tool bean (legacy behavior, highest token cost)'
    },
    {
      value: 'DYNAMIC',
      label: 'Dynamic',
      description: 'Only search_tools / describe_tools / execute_tool + always-exposed list'
    },
    {
      value: 'HYBRID',
      label: 'Hybrid (recommended)',
      description: 'Meta-tools + a small built-in whitelist (rag_query, read_file, list_files)'
    }
  ];

  constructor(private service: McpOptimizationService) {
    this.config = service.defaults();
  }

  ngOnInit(): void {
    this.loadConfiguration();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.service.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.applyResponse(response);
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load MCP optimization configuration: ' +
            (err.error?.error || err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.service.updateConfig(this.config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.applyResponse(response);
          this.successMessage = response.message || 'Configuration saved';
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to save configuration: ' +
            (err.error?.error || err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  resetConfiguration(): void {
    if (!confirm('Reset MCP optimization configuration to defaults?')) {
      return;
    }

    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.service.resetConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.applyResponse(response);
          this.successMessage = response.message || 'Configuration reset to defaults';
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to reset configuration: ' +
            (err.error?.error || err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  addAlwaysExposedTool(): void {
    const tool = this.newAlwaysExposedTool.trim();
    if (!tool) {
      return;
    }
    const list = this.config.alwaysExposedTools ?? [];
    if (!list.includes(tool)) {
      this.config.alwaysExposedTools = [...list, tool];
    }
    this.newAlwaysExposedTool = '';
  }

  removeAlwaysExposedTool(tool: string): void {
    const list = this.config.alwaysExposedTools ?? [];
    this.config.alwaysExposedTools = list.filter(t => t !== tool);
  }

  private applyResponse(response: McpOptimizationConfig & { configFilePath?: string }): void {
    const defaults = this.service.defaults();
    this.config = {
      ...defaults,
      ...response,
      alwaysExposedTools: response.alwaysExposedTools ?? defaults.alwaysExposedTools,
      toolOverrides: response.toolOverrides ?? defaults.toolOverrides
    };
    this.configFilePath = response.configFilePath ?? null;
  }
}
