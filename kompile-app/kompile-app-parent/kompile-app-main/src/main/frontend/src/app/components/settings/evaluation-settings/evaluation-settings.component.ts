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
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, takeUntil } from 'rxjs';

import { EvaluationService } from '../../../services/evaluation.service';
import { EvaluationConfig, AvailableEvaluators, EvaluationType } from '../../../models/rag-management.models';

@Component({
  selector: 'app-evaluation-settings',
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
    MatExpansionModule,
    MatDividerModule
  ],
  templateUrl: './evaluation-settings.component.html',
  styleUrls: ['./evaluation-settings.component.scss']
})
export class EvaluationSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: EvaluationConfig;
  availableEvaluators: AvailableEvaluators | null = null;
  evaluationTypes: EvaluationType[] = [];

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  constructor(private evaluationService: EvaluationService) {
    this.config = evaluationService.createDefaultConfig();
  }

  ngOnInit(): void {
    this.loadConfiguration();
    this.loadEvaluationTypes();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfiguration(): void {
    this.loading = true;
    this.error = null;

    this.evaluationService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.available) {
            this.config = response;
          }
          this.loading = false;
          this.loadAvailableEvaluators();
        },
        error: (err) => {
          this.error = 'Failed to load evaluation configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadAvailableEvaluators(): void {
    this.evaluationService.getAvailableEvaluators()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.availableEvaluators = response;
        },
        error: (err) => {
          console.error('Failed to load available evaluators:', err);
        }
      });
  }

  loadEvaluationTypes(): void {
    this.evaluationService.getEvaluationTypes()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (types) => {
          this.evaluationTypes = types;
        },
        error: (err) => {
          console.error('Failed to load evaluation types:', err);
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.evaluationService.updateConfig(this.config)
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
    this.evaluationService.toggle(this.config.enabled)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config.enabled = response.enabled;
          this.successMessage = `Evaluation ${response.enabled ? 'enabled' : 'disabled'}`;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.config.enabled = !this.config.enabled;
          this.error = 'Failed to toggle evaluation: ' + (err.error?.message || err.message);
        }
      });
  }

  formatThreshold(value: number): string {
    return (value * 100).toFixed(0) + '%';
  }

  getEvaluatorTypeDescription(type: string): string {
    const evalType = this.evaluationTypes.find(t => t.type === type);
    return evalType?.description || '';
  }
}
