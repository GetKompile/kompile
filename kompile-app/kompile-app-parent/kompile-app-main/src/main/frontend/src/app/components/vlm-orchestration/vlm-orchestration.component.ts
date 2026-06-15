import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { VlmOrchestrationService, VlmOrchestrationConfig } from '../../services/vlm-orchestration.service';
import { GpuLifecycleService } from '../../services/gpu-lifecycle.service';

interface GpuOption {
  id: number;
  label: string;
}

@Component({
  selector: 'app-vlm-orchestration',
  standalone: true,
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
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule
  ],
  templateUrl: './vlm-orchestration.component.html',
  styleUrls: ['./vlm-orchestration.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class VlmOrchestrationComponent implements OnInit {
  loading = true;
  saving = false;

  config: VlmOrchestrationConfig = {
    releaseEncoderAfterEncoding: true,
    encoderDeviceId: -1,
    decoderDeviceId: -1,
    tritonCacheEnabled: true,
    tritonCacheDir: '',
    tritonAutoImport: true,
    tritonAutoExport: true
  };

  gpuOptions: GpuOption[] = [{ id: -1, label: 'Auto (default)' }];

  constructor(
    private vlmService: VlmOrchestrationService,
    private gpuService: GpuLifecycleService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadConfig();
    this.loadDevices();
  }

  private loadConfig(): void {
    this.loading = true;
    this.cdr.markForCheck();

    this.vlmService.getConfig().subscribe({
      next: (config) => {
        this.config = config;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        console.error('Failed to load VLM orchestration config', err);
        this.loading = false;
        this.cdr.markForCheck();
      }
    });
  }

  private loadDevices(): void {
    this.gpuService.getDevices().subscribe({
      next: (data) => {
        this.gpuOptions = [{ id: -1, label: 'Auto (default)' }];
        if (data?.devices && Array.isArray(data.devices)) {
          for (const d of data.devices) {
            const idx = d.cudaRuntimeIndex ?? d.index ?? 0;
            const name = d.name || 'GPU';
            const memMb = Math.round((d.totalMemory || 0) / (1024 * 1024));
            this.gpuOptions.push({
              id: idx,
              label: `GPU ${idx}: ${name} (${memMb} MB)`
            });
          }
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.gpuOptions = [{ id: -1, label: 'Auto (default)' }];
        this.cdr.markForCheck();
      }
    });
  }

  saveConfig(): void {
    this.saving = true;
    this.cdr.markForCheck();

    this.vlmService.updateConfig(this.config).subscribe({
      next: (saved) => {
        this.config = saved;
        this.saving = false;
        this.snackBar.open('VLM orchestration config saved', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open('Failed to save: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  resetConfig(): void {
    this.vlmService.resetConfig().subscribe({
      next: (config) => {
        this.config = config;
        this.snackBar.open('VLM orchestration config reset to defaults', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.snackBar.open('Failed to reset: ' + (err.error?.message || err.message), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }
}
