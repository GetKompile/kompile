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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, takeUntil } from 'rxjs';

import { QueryTransformerService } from '../../../services/query-transformer.service';
import { QueryTransformerConfig, TransformerType, TransformerPreset } from '../../../models/rag-management.models';

@Component({
  selector: 'app-query-transformer-settings',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSliderModule,
    MatCardModule,
    MatTooltipModule,
    MatSelectModule,
    MatRadioModule,
    MatDividerModule
  ],
  templateUrl: './query-transformer-settings.component.html',
  styleUrls: ['./query-transformer-settings.component.scss']
})
export class QueryTransformerSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: QueryTransformerConfig;
  transformerTypes: TransformerType[] = [];
  presets: TransformerPreset[] = [];

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  constructor(private queryTransformerService: QueryTransformerService) {
    this.config = queryTransformerService.createDefaultConfig();
  }

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadTransformerTypes();
    this.loadPresets();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.queryTransformerService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.available) {
            this.config = response;
          }
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load query transformer configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadTransformerTypes(): void {
    this.queryTransformerService.getTransformerTypes()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (types) => {
          this.transformerTypes = types;
        },
        error: (err) => {
          console.error('Failed to load transformer types:', err);
        }
      });
  }

  loadPresets(): void {
    this.queryTransformerService.getPresets()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (presets) => {
          this.presets = presets;
        },
        error: (err) => {
          console.error('Failed to load presets:', err);
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.queryTransformerService.updateConfig(this.config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config = response;
          this.successMessage = 'Configuration saved successfully';
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to save configuration: ' + (err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  toggleEnabled(): void {
    this.queryTransformerService.toggle(this.config.enabled)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config.enabled = response.enabled;
          this.successMessage = `Query transformer ${response.enabled ? 'enabled' : 'disabled'}`;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.config.enabled = !this.config.enabled;
          this.error = 'Failed to toggle query transformer: ' + (err.error?.message || err.message);
        }
      });
  }

  applyPreset(preset: string): void {
    this.saving = true;
    this.error = null;

    this.queryTransformerService.applyPreset(preset)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config = response;
          this.successMessage = `Applied "${preset}" preset`;
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to apply preset: ' + (err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  onTypeChange(): void {
    // Optionally auto-save when type changes
    // For now, just let the user save manually
  }

  getSelectedTypeInfo(): TransformerType | undefined {
    return this.transformerTypes.find(t => t.type === this.config.type);
  }

  requiresLlm(): boolean {
    const typeInfo = this.getSelectedTypeInfo();
    return typeInfo?.requiresLlm ?? false;
  }

  showAdvancedOptions(): boolean {
    return this.config.type !== 'passthrough';
  }
}
