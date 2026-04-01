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
import { MatSelectModule } from '@angular/material/select';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { Subject, takeUntil } from 'rxjs';

import { FilterChainService } from '../../../services/filter-chain.service';
import {
  FilterChainConfig,
  FilterConfig,
  FilterInfo,
  FilterPhase,
  FilterType
} from '../../../models/filter-chain.models';

@Component({
  selector: 'app-filter-chain-settings',
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
    MatSelectModule,
    MatCardModule,
    MatTooltipModule,
    MatExpansionModule,
    MatChipsModule,
    MatDividerModule,
    MatDialogModule,
    MatMenuModule,
    DragDropModule
  ],
  templateUrl: './filter-chain-settings.component.html',
  styleUrls: ['./filter-chain-settings.component.scss']
})
export class FilterChainSettingsComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  config: FilterChainConfig | null = null;
  availableFilters: FilterInfo[] = [];
  configuredFilters: FilterConfig[] = [];

  loading = false;
  saving = false;
  error: string | null = null;
  successMessage: string | null = null;

  // For editing a filter
  editingFilter: FilterConfig | null = null;
  isNewFilter = false;

  // All available phases
  allPhases: FilterPhase[] = ['PRE_RETRIEVAL', 'POST_RETRIEVAL', 'PRE_LLM', 'POST_LLM'];

  // Filter types
  filterTypes: { value: FilterType; label: string }[] = [
    { value: 'LOCAL', label: 'Built-in' },
    { value: 'HTTP', label: 'HTTP Remote' },
    { value: 'MCP', label: 'MCP Remote' }
  ];

  constructor(
    private filterChainService: FilterChainService,
    private dialog: MatDialog
  ) {}

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

    this.filterChainService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.config = response;
          if (response.available) {
            this.configuredFilters = [...(response.filters || [])];
          }
          this.loading = false;
          this.loadAvailableFilters();
        },
        error: (err) => {
          this.error = 'Failed to load filter chain configuration: ' + (err.error?.message || err.message);
          this.loading = false;
        }
      });
  }

  loadAvailableFilters(): void {
    this.filterChainService.getFilters()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          this.availableFilters = response.availableFilters || [];
        },
        error: (err) => {
          console.error('Failed to load available filters:', err);
        }
      });
  }

  saveConfiguration(): void {
    if (!this.config) return;

    this.saving = true;
    this.error = null;
    this.successMessage = null;

    const configToSave = {
      ...this.config,
      filters: this.configuredFilters
    };

    this.filterChainService.updateConfig(configToSave)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.config = response.config;
            this.configuredFilters = [...(response.config.filters || [])];
            this.successMessage = 'Configuration saved successfully';
          } else {
            this.error = response.error || 'Failed to save configuration';
          }
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
    if (!this.config) return;

    const newState = !this.config.enabled;

    this.filterChainService.toggleEnabled(newState)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success && this.config) {
            this.config.enabled = response.enabled ?? newState;
            this.successMessage = `Filter chain ${this.config.enabled ? 'enabled' : 'disabled'}`;
            setTimeout(() => this.successMessage = null, 3000);
          }
        },
        error: (err) => {
          this.error = 'Failed to toggle filter chain: ' + (err.error?.message || err.message);
        }
      });
  }

  toggleFilter(filter: FilterConfig): void {
    this.filterChainService.toggleFilter(filter.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            filter.enabled = response.enabled ?? !filter.enabled;
            this.successMessage = `Filter ${response.enabled ? 'enabled' : 'disabled'}`;
            setTimeout(() => this.successMessage = null, 3000);
          }
        },
        error: (err) => {
          this.error = 'Failed to toggle filter: ' + (err.error?.message || err.message);
        }
      });
  }

  addNewFilter(type: FilterType): void {
    switch (type) {
      case 'LOCAL':
        this.editingFilter = this.filterChainService.createDefaultFilter();
        break;
      case 'HTTP':
        this.editingFilter = this.filterChainService.createHttpFilter();
        break;
      case 'MCP':
        this.editingFilter = this.filterChainService.createMcpFilter();
        break;
    }
    this.isNewFilter = true;
  }

  editFilter(filter: FilterConfig): void {
    this.editingFilter = { ...filter };
    if (filter.remoteConfig) {
      this.editingFilter.remoteConfig = { ...filter.remoteConfig };
    }
    this.isNewFilter = false;
  }

  saveFilter(): void {
    if (!this.editingFilter) return;

    this.saving = true;
    this.error = null;

    const saveObservable = this.isNewFilter
      ? this.filterChainService.addFilter(this.editingFilter)
      : this.filterChainService.updateFilter(this.editingFilter.id, this.editingFilter);

    saveObservable.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.config = response.config;
            this.configuredFilters = [...(response.config.filters || [])];
            this.successMessage = this.isNewFilter ? 'Filter added' : 'Filter updated';
            this.editingFilter = null;
            this.isNewFilter = false;
          } else {
            this.error = response.error || 'Failed to save filter';
          }
          this.saving = false;
          setTimeout(() => this.successMessage = null, 3000);
        },
        error: (err) => {
          this.error = 'Failed to save filter: ' + (err.error?.message || err.message);
          this.saving = false;
        }
      });
  }

  cancelEdit(): void {
    this.editingFilter = null;
    this.isNewFilter = false;
  }

  deleteFilter(filter: FilterConfig): void {
    if (!confirm(`Are you sure you want to delete filter "${filter.name}"?`)) {
      return;
    }

    this.filterChainService.deleteFilter(filter.id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.config = response.config;
            this.configuredFilters = [...(response.config.filters || [])];
            this.successMessage = 'Filter deleted';
            setTimeout(() => this.successMessage = null, 3000);
          }
        },
        error: (err) => {
          this.error = 'Failed to delete filter: ' + (err.error?.message || err.message);
        }
      });
  }

  onDrop(event: CdkDragDrop<FilterConfig[]>): void {
    if (event.previousIndex !== event.currentIndex) {
      moveItemInArray(this.configuredFilters, event.previousIndex, event.currentIndex);
      // Update priorities based on new order
      this.configuredFilters.forEach((filter, index) => {
        filter.priority = (index + 1) * 10;
      });
    }
  }

  resetToDefaults(): void {
    if (!confirm('Are you sure you want to reset to default configuration? All custom filters will be removed.')) {
      return;
    }

    this.filterChainService.resetConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.config = response.config;
            this.configuredFilters = [...(response.config.filters || [])];
            this.successMessage = 'Configuration reset to defaults';
            setTimeout(() => this.successMessage = null, 3000);
          }
        },
        error: (err) => {
          this.error = 'Failed to reset configuration: ' + (err.error?.message || err.message);
        }
      });
  }

  getPhaseDisplayName(phase: string): string {
    return this.filterChainService.getPhaseDisplayName(phase);
  }

  getTypeDisplayName(type: string): string {
    return this.filterChainService.getTypeDisplayName(type);
  }

  togglePhase(phase: FilterPhase): void {
    if (!this.editingFilter) return;

    const index = this.editingFilter.phases.indexOf(phase);
    if (index >= 0) {
      this.editingFilter.phases.splice(index, 1);
    } else {
      this.editingFilter.phases.push(phase);
    }
  }

  isPhaseSelected(phase: FilterPhase): boolean {
    return this.editingFilter?.phases.includes(phase) ?? false;
  }

  addLocalFilter(filterInfo: FilterInfo): void {
    const filter = this.filterChainService.createDefaultFilter();
    filter.id = filterInfo.id;
    filter.name = filterInfo.name;
    filter.localFilterId = filterInfo.id;
    filter.description = filterInfo.description;

    this.filterChainService.addFilter(filter)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          if (response.success) {
            this.config = response.config;
            this.configuredFilters = [...(response.config.filters || [])];
            this.successMessage = 'Filter added';
            setTimeout(() => this.successMessage = null, 3000);
          }
        },
        error: (err) => {
          this.error = 'Failed to add filter: ' + (err.error?.message || err.message);
        }
      });
  }
}
