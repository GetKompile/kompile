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
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { Subject, takeUntil } from 'rxjs';

import { GuardrailsService } from '../../../services/guardrails.service';
import { GuardrailsConfig, AvailableGuardrails } from '../../../models/rag-management.models';

@Component({
  selector: 'app-guardrails-settings',
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
    MatChipsModule,
    MatDividerModule
  ],
  templateUrl: './guardrails-settings.component.html',
  styleUrls: ['./guardrails-settings.component.scss']
})
export class GuardrailsSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: GuardrailsConfig;
  availableGuardrails: AvailableGuardrails | null = null;

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  // Topic management
  newAllowedTopic = '';
  newBlockedTopic = '';

  constructor(private guardrailsService: GuardrailsService) {
    this.config = guardrailsService.createDefaultConfig();
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

    this.guardrailsService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.available) {
            this.config = response;
          }
          this.loading = false;
          this.loadAvailableGuardrails();
        },
        error: (err) => {
          this.error = 'Failed to load guardrails configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadAvailableGuardrails(): void {
    this.guardrailsService.getAvailableGuardrails()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.availableGuardrails = response;
        },
        error: (err) => {
          console.error('Failed to load available guardrails:', err);
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.guardrailsService.updateConfig(this.config)
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
    this.guardrailsService.toggle(this.config.enabled)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config.enabled = response.enabled;
          this.successMessage = `Guardrails ${response.enabled ? 'enabled' : 'disabled'}`;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.config.enabled = !this.config.enabled;
          this.error = 'Failed to toggle guardrails: ' + (err.error?.message || err.message);
        }
      });
  }

  addAllowedTopic(): void {
    const topic = this.newAllowedTopic.trim();
    if (topic && !this.config.input.topic.allowedTopics.includes(topic)) {
      this.config.input.topic.allowedTopics.push(topic);
      this.newAllowedTopic = '';
    }
  }

  removeAllowedTopic(topic: string): void {
    const index = this.config.input.topic.allowedTopics.indexOf(topic);
    if (index >= 0) {
      this.config.input.topic.allowedTopics.splice(index, 1);
    }
  }

  addBlockedTopic(): void {
    const topic = this.newBlockedTopic.trim();
    if (topic && !this.config.input.topic.blockedTopics.includes(topic)) {
      this.config.input.topic.blockedTopics.push(topic);
      this.newBlockedTopic = '';
    }
  }

  removeBlockedTopic(topic: string): void {
    const index = this.config.input.topic.blockedTopics.indexOf(topic);
    if (index >= 0) {
      this.config.input.topic.blockedTopics.splice(index, 1);
    }
  }

  formatThreshold(value: number): string {
    return (value * 100).toFixed(0) + '%';
  }
}
