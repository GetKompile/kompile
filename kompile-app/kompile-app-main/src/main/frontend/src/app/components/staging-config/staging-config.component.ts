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
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatMenuModule } from '@angular/material/menu';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import {
  StagingConfigService,
  StagingServiceConfig,
  StagingServiceConfigDto,
  ConnectionTestResult
} from '../../services/staging-config.service';

@Component({
  selector: 'app-staging-config',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    ReactiveFormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatSlideToggleModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatChipsModule,
    MatDividerModule,
    MatDialogModule,
    MatExpansionModule,
    MatMenuModule
  ],
  templateUrl: './staging-config.component.html',
  styleUrls: ['./staging-config.component.css']
})
export class StagingConfigComponent implements OnInit, OnDestroy {

  configs: StagingServiceConfig[] = [];
  activeConfig: StagingServiceConfig | null = null;
  loading = false;
  testingId: number | null = null;

  // Form state
  showForm = false;
  editingConfig: StagingServiceConfig | null = null;
  configForm!: FormGroup;

  private destroy$ = new Subject<void>();

  constructor(
    private stagingConfigService: StagingConfigService,
    private snackBar: MatSnackBar,
    private fb: FormBuilder
  ) {
    this.initForm();
  }

  ngOnInit(): void {
    // Subscribe to configs
    this.stagingConfigService.configs$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(configs => {
      this.configs = configs;
    });

    // Subscribe to active config
    this.stagingConfigService.activeConfig$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(config => {
      this.activeConfig = config;
    });

    // Subscribe to loading state
    this.stagingConfigService.loading$.pipe(
      takeUntil(this.destroy$)
    ).subscribe(loading => {
      this.loading = loading;
    });

    // Load initial data
    this.stagingConfigService.loadConfigs().subscribe();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  private initForm(): void {
    this.configForm = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(255)]],
      endpointUrl: ['', [Validators.required, Validators.pattern(/^https?:\/\/.+/)]],
      apiKey: [''],
      active: [false],
      connectionTimeoutMs: [5000, [Validators.required, Validators.min(1000), Validators.max(60000)]],
      readTimeoutMs: [30000, [Validators.required, Validators.min(1000), Validators.max(300000)]],
      autoSync: [false],
      syncIntervalMinutes: [60, [Validators.required, Validators.min(1), Validators.max(1440)]],
      description: ['']
    });
  }

  showAddForm(): void {
    this.editingConfig = null;
    this.configForm.reset({
      name: '',
      endpointUrl: '',
      apiKey: '',
      active: this.configs.length === 0, // Auto-activate if first config
      connectionTimeoutMs: 5000,
      readTimeoutMs: 30000,
      autoSync: false,
      syncIntervalMinutes: 60,
      description: ''
    });
    this.showForm = true;
  }

  showEditForm(config: StagingServiceConfig): void {
    this.editingConfig = config;
    this.configForm.patchValue({
      name: config.name,
      endpointUrl: config.endpointUrl,
      apiKey: config.apiKey || '',
      active: config.active,
      connectionTimeoutMs: config.connectionTimeoutMs,
      readTimeoutMs: config.readTimeoutMs,
      autoSync: config.autoSync,
      syncIntervalMinutes: config.syncIntervalMinutes,
      description: config.description || ''
    });
    this.showForm = true;
  }

  cancelForm(): void {
    this.showForm = false;
    this.editingConfig = null;
    this.configForm.reset();
  }

  saveConfig(): void {
    if (this.configForm.invalid) {
      this.showSnackbar('Please fix form errors', true);
      return;
    }

    const dto: StagingServiceConfigDto = {
      name: this.configForm.value.name,
      endpointUrl: this.configForm.value.endpointUrl,
      apiKey: this.configForm.value.apiKey || undefined,
      active: this.configForm.value.active,
      connectionTimeoutMs: this.configForm.value.connectionTimeoutMs,
      readTimeoutMs: this.configForm.value.readTimeoutMs,
      autoSync: this.configForm.value.autoSync,
      syncIntervalMinutes: this.configForm.value.syncIntervalMinutes,
      description: this.configForm.value.description || undefined
    };

    if (this.editingConfig) {
      // Update existing
      this.stagingConfigService.updateConfig(this.editingConfig.id!, dto).subscribe({
        next: () => {
          this.showSnackbar('Configuration updated successfully');
          this.cancelForm();
        },
        error: (err) => {
          this.showSnackbar(err.error?.error || 'Failed to update configuration', true);
        }
      });
    } else {
      // Create new
      this.stagingConfigService.createConfig(dto).subscribe({
        next: () => {
          this.showSnackbar('Configuration created successfully');
          this.cancelForm();
        },
        error: (err) => {
          this.showSnackbar(err.error?.error || 'Failed to create configuration', true);
        }
      });
    }
  }

  deleteConfig(config: StagingServiceConfig): void {
    if (!confirm(`Are you sure you want to delete "${config.name}"?`)) {
      return;
    }

    this.stagingConfigService.deleteConfig(config.id!).subscribe({
      next: () => {
        this.showSnackbar('Configuration deleted');
      },
      error: (err) => {
        this.showSnackbar(err.error?.error || 'Failed to delete configuration', true);
      }
    });
  }

  activateConfig(config: StagingServiceConfig): void {
    this.stagingConfigService.activateConfig(config.id!).subscribe({
      next: () => {
        this.showSnackbar(`"${config.name}" is now the active staging service`);
      },
      error: (err) => {
        this.showSnackbar(err.error?.error || 'Failed to activate configuration', true);
      }
    });
  }

  testConnection(config: StagingServiceConfig): void {
    this.testingId = config.id!;

    this.stagingConfigService.testConnection(config.id!).subscribe({
      next: (result: ConnectionTestResult) => {
        this.testingId = null;
        if (result.success) {
          this.showSnackbar(
            `Connected! Registry has ${result.modelCount} models (version: ${result.version})`
          );
        } else {
          this.showSnackbar(`Connection failed: ${result.message}`, true);
        }
      },
      error: (err) => {
        this.testingId = null;
        this.showSnackbar('Connection test failed', true);
      }
    });
  }

  refresh(): void {
    this.stagingConfigService.refresh();
  }

  formatDate(dateString?: string): string {
    if (!dateString) return 'Never';
    const date = new Date(dateString);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString();
  }

  getStatusIcon(config: StagingServiceConfig): string {
    if (!config.verified) return 'help_outline';
    return config.lastError ? 'error' : 'check_circle';
  }

  getStatusColor(config: StagingServiceConfig): string {
    if (!config.verified && !config.lastVerifiedAt) return '';
    return config.verified ? 'status-success' : 'status-error';
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: isError ? 6000 : 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
