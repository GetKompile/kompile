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

import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  DeviceRoutingService,
  DeviceRoutingConfig,
  ServiceDeviceConfig
} from '../../services/device-routing.service';

interface ServiceEntry {
  key: string;
  label: string;
  description: string;
  icon: string;
  config: ServiceDeviceConfig;
  memoryMb: number | null;
}

interface GpuDevice {
  id: number;
  name: string;
  totalMemory: number;
}

@Component({
  selector: 'app-device-routing',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './device-routing.component.html',
  styleUrls: ['./device-routing.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DeviceRoutingComponent implements OnInit {

  loading = true;
  saving = false;

  config: DeviceRoutingConfig = {
    serviceRoutes: {},
    enabled: false
  };

  services: ServiceEntry[] = [];
  gpuDevices: GpuDevice[] = [];

  private static readonly SERVICE_DEFS = [
    { key: 'embedding', label: 'Embedding', description: 'Dense vector encoding', icon: 'hub' },
    { key: 'vectorPopulation', label: 'Vector Population', description: 'Index building & vector indexing', icon: 'storage' },
    { key: 'ingest', label: 'Ingest', description: 'Document ingestion & chunking', icon: 'upload_file' },
    { key: 'modelInit', label: 'Model Init', description: 'Model initialization & validation', icon: 'model_training' }
  ];

  constructor(
    private deviceRoutingService: DeviceRoutingService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  private loadData(): void {
    this.loading = true;
    this.cdr.markForCheck();

    // Load config and devices in parallel
    this.deviceRoutingService.getConfiguration().subscribe({
      next: (config) => {
        this.config = config || { serviceRoutes: {}, enabled: false };
        this.buildServiceEntries();
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load device routing config', err);
        this.config = { serviceRoutes: {}, enabled: false };
        this.buildServiceEntries();
        this.loading = false;
        this.cdr.markForCheck();
      }
    });

    this.deviceRoutingService.getSystemDevices().subscribe({
      next: (devices) => {
        if (Array.isArray(devices)) {
          this.gpuDevices = devices
            .filter((d: any) => d.isCuda || d.deviceType === 'cuda')
            .map((d: any) => ({
              id: d.id || d.deviceId || 0,
              name: d.name || d.deviceName || 'GPU',
              totalMemory: d.totalMemory || d.totalMemoryBytes || 0
            }));
        }
        this.cdr.markForCheck();
      },
      error: () => {
        // Devices endpoint may not be available - that's fine, just show CPU option
        this.gpuDevices = [];
        this.cdr.markForCheck();
      }
    });
  }

  private buildServiceEntries(): void {
    this.services = DeviceRoutingComponent.SERVICE_DEFS.map(def => {
      const existing = this.config.serviceRoutes?.[def.key];
      const config: ServiceDeviceConfig = existing || {
        deviceType: null,
        cudaDeviceId: null,
        maxThreads: null,
        maxDeviceMemory: null
      };
      return {
        ...def,
        config: { ...config },
        memoryMb: config.maxDeviceMemory ? Math.round(config.maxDeviceMemory / (1024 * 1024)) : null
      };
    });
  }

  onEnabledChange(): void {
    // Just update local state, will be saved on Save button
  }

  onDeviceTypeChange(svc: ServiceEntry): void {
    if (svc.config.deviceType === 'cuda' && svc.config.cudaDeviceId == null) {
      svc.config.cudaDeviceId = this.gpuDevices.length > 0 ? this.gpuDevices[0].id : 0;
    }
    if (svc.config.deviceType !== 'cuda') {
      svc.config.cudaDeviceId = null;
    }
  }

  onMemoryChange(svc: ServiceEntry): void {
    if (svc.memoryMb != null && svc.memoryMb > 0) {
      svc.config.maxDeviceMemory = svc.memoryMb * 1024 * 1024;
    } else {
      svc.config.maxDeviceMemory = null;
    }
  }

  saveConfig(): void {
    this.saving = true;
    this.cdr.markForCheck();

    // Build the config from service entries
    const routes: { [key: string]: ServiceDeviceConfig } = {};
    for (const svc of this.services) {
      // Only include services that have an explicit device type set
      if (svc.config.deviceType != null) {
        routes[svc.key] = {
          deviceType: svc.config.deviceType,
          cudaDeviceId: svc.config.deviceType === 'cuda' ? svc.config.cudaDeviceId : null,
          maxThreads: svc.config.maxThreads || null,
          maxDeviceMemory: svc.config.maxDeviceMemory || null
        };
      }
    }

    const configToSave: DeviceRoutingConfig = {
      serviceRoutes: routes,
      enabled: this.config.enabled
    };

    this.deviceRoutingService.saveConfiguration(configToSave).subscribe({
      next: (saved) => {
        this.config = saved;
        this.buildServiceEntries();
        this.saving = false;
        this.snackBar.open('Device routing configuration saved', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open('Failed to save configuration: ' + (err.message || 'Unknown error'), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  resetToDefaults(): void {
    this.deviceRoutingService.resetToDefaults().subscribe({
      next: (config) => {
        this.config = config;
        this.buildServiceEntries();
        this.snackBar.open('Device routing reset to defaults', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.snackBar.open('Failed to reset: ' + (err.message || 'Unknown error'), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  formatMemory(bytes: number): string {
    if (!bytes || bytes <= 0) return 'N/A';
    const gb = bytes / (1024 * 1024 * 1024);
    if (gb >= 1) return gb.toFixed(1) + ' GB';
    const mb = bytes / (1024 * 1024);
    return mb.toFixed(0) + ' MB';
  }
}
