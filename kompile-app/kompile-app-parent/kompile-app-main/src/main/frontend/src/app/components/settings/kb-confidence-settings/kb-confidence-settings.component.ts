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
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { Subject, takeUntil } from 'rxjs';

import { KbConfigService, KbConfig } from '../../../services/kb-config.service';

@Component({
  selector: 'app-kb-confidence-settings',
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
    MatCardModule,
    MatTooltipModule,
    MatExpansionModule,
    MatDividerModule,
    MatChipsModule,
    MatCheckboxModule
  ],
  templateUrl: './kb-confidence-settings.component.html',
  styleUrls: ['./kb-confidence-settings.component.scss']
})
export class KbConfidenceSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: KbConfig;

  loading = false;
  saving = false;
  resettingToDefaults = false;
  error: string | null = null;
  successMessage: string | null = null;

  // Personal email domain chip input
  newDomain = '';

  constructor(private kbConfigService: KbConfigService) {
    this.config = kbConfigService.createDefaultConfig();
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

    this.kbConfigService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (data) => {
          this.config = { ...this.kbConfigService.createDefaultConfig(), ...data };
          if (!Array.isArray(this.config.kbPersonalEmailDomains)) {
            this.config.kbPersonalEmailDomains = [];
          }
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load KB configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  saveConfiguration(): void {
    this.saving = true;
    this.error = null;
    this.successMessage = null;

    this.kbConfigService.saveConfig(this.config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (updated) => {
          this.config = { ...this.kbConfigService.createDefaultConfig(), ...updated };
          if (!Array.isArray(this.config.kbPersonalEmailDomains)) {
            this.config.kbPersonalEmailDomains = [];
          }
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

  resetToDefaults(): void {
    this.resettingToDefaults = true;
    this.error = null;
    this.successMessage = null;

    this.kbConfigService.getDefaults()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (defaults) => {
          this.config = { ...this.kbConfigService.createDefaultConfig(), ...defaults };
          if (!Array.isArray(this.config.kbPersonalEmailDomains)) {
            this.config.kbPersonalEmailDomains = [];
          }
          this.successMessage = 'Defaults loaded — click Save to persist';
          this.resettingToDefaults = false;
          setTimeout(() => this.successMessage = null, 5000);
        },
        error: (err) => {
          this.error = 'Failed to load defaults: ' + (err.error?.message || err.message);
          this.resettingToDefaults = false;
        }
      });
  }

  // ---- Domain chip management ----

  addDomain(): void {
    const domain = this.newDomain.trim().toLowerCase();
    if (domain && !this.config.kbPersonalEmailDomains.includes(domain)) {
      this.config.kbPersonalEmailDomains = [...this.config.kbPersonalEmailDomains, domain];
    }
    this.newDomain = '';
  }

  removeDomain(domain: string): void {
    this.config.kbPersonalEmailDomains = this.config.kbPersonalEmailDomains.filter(d => d !== domain);
  }

  onDomainKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      this.addDomain();
    }
  }
}
