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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { environment } from '../../../../environments/environment';

export interface TrainingConfigRequest {
  modelId: string;
  datasetId: string;
  trainingType: string;
  peftConfig?: {
    peftType: string;
    rank: number;
    alpha: number;
    dropout: number;
    targetModules: string[];
  };
  updaterConfig: {
    type: string;
    learningRate: number;
  };
  lrSchedule: string;
  warmupRatio: number;
  epochs: number;
  batchSize: number;
  gradientAccumulationSteps: number;
  maxSteps: number;
  maxGradNorm: number;
  fp16: boolean;
  bf16: boolean;
  loggingSteps: number;
  saveSteps: number;
  evalSteps: number;
  seed: number;
  autoRegister: boolean;
  evaluateAfterTraining: boolean;
  enableMonitoring: boolean;
}

export interface TrainingJobStatus {
  taskId: string;
  status: string;
  modelId: string;
  datasetId: string;
  trainingType: string;
  message?: string;
}

@Component({
  selector: 'app-training-launch',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
    MatExpansionModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './training-launch.component.html',
  styleUrls: ['./training-launch.component.css']
})
export class TrainingLaunchComponent implements OnInit {

  // Dropdown data
  availableModels: any[] = [];
  datasets: any[] = [];
  peftTypes: string[] = [];
  updaterTypes: string[] = [];
  lrSchedules: string[] = [];

  // Loading state
  loading = false;
  launching = false;

  // Messages
  successMessage: string | null = null;
  errorMessage: string | null = null;
  launchedJobId: string | null = null;

  // Training type options
  trainingTypes = ['FINETUNE', 'LORA', 'DISTILLATION', 'ALIGNMENT'];

  // HuggingFace download state
  downloading = false;
  downloadStatus: string | null = null;
  downloadMessage: string | null = null;
  downloadConfig = {
    repository: '',
    modelId: '',
    format: 'onnx',
    type: 'llm_ggml',
    authToken: '',
    autoConvert: true
  };

  // Form model
  config: TrainingConfigRequest = {
    modelId: '',
    datasetId: '',
    trainingType: 'FINETUNE',
    updaterConfig: {
      type: '',
      learningRate: 1e-4
    },
    lrSchedule: 'COSINE',
    warmupRatio: 0.1,
    epochs: 3,
    batchSize: 8,
    gradientAccumulationSteps: 1,
    maxSteps: -1,
    maxGradNorm: 1.0,
    fp16: false,
    bf16: false,
    loggingSteps: 10,
    saveSteps: 500,
    evalSteps: 500,
    seed: 42,
    autoRegister: true,
    evaluateAfterTraining: false,
    enableMonitoring: true
  };

  // LoRA / PEFT config (only used when trainingType === 'LORA')
  peftConfig = {
    peftType: '',
    rank: 8,
    alpha: 16,
    dropout: 0.05,
    targetModulesStr: ''  // comma-separated string for the text input
  };

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.loadAll();
  }

  private loadAll(): void {
    this.loading = true;
    this.errorMessage = null;

    // Load models from staging registry — only show ACTIVE/STAGED models (ready for training)
    this.http.get<any>(`${environment.apiUrl}/staging/registry`).subscribe({
      next: data => {
        let models: any[];
        if (Array.isArray(data)) {
          models = data;
        } else if (data && data.models) {
          models = data.models;
        } else if (data && data.entries) {
          models = data.entries;
        } else {
          models = [];
        }
        // Filter to only models that are ready for training
        this.availableModels = models.filter(m => {
          const status = (m.status || '').toUpperCase();
          return status === 'ACTIVE' || status === 'STAGED' || !status;
        });
      },
      error: () => { this.availableModels = []; }
    });

    this.http.get<any[]>(`${environment.apiUrl}/datasets`).subscribe({
      next: data => { this.datasets = data || []; },
      error: () => { this.datasets = []; }
    });

    this.http.get<string[]>(`${environment.apiUrl}/training/peft-types`).subscribe({
      next: data => {
        this.peftTypes = data || [];
        if (this.peftTypes.length > 0) {
          this.peftConfig.peftType = this.peftTypes[0];
        }
      },
      error: () => { this.peftTypes = ['LORA', 'QLORA', 'PREFIX_TUNING', 'PROMPT_TUNING']; }
    });

    this.http.get<string[]>(`${environment.apiUrl}/training/updater-types`).subscribe({
      next: data => {
        this.updaterTypes = data || [];
        if (this.updaterTypes.length > 0 && !this.config.updaterConfig.type) {
          this.config.updaterConfig.type = this.updaterTypes[0];
        }
      },
      error: () => {
        this.updaterTypes = ['ADAM', 'ADAMW', 'SGD', 'ADAGRAD'];
        this.config.updaterConfig.type = 'ADAMW';
      }
    });

    this.http.get<string[]>(`${environment.apiUrl}/training/lr-schedules`).subscribe({
      next: data => {
        this.lrSchedules = data || [];
        this.loading = false;
      },
      error: () => {
        this.lrSchedules = ['COSINE', 'LINEAR', 'CONSTANT', 'COSINE_WITH_RESTARTS', 'POLYNOMIAL'];
        this.loading = false;
      }
    });
  }

  get isLoraMode(): boolean {
    return this.config.trainingType === 'LORA';
  }

  get canLaunch(): boolean {
    return !!this.config.modelId && !!this.config.datasetId && !this.launching;
  }

  onTrainingTypeChange(): void {
    // Clear PEFT config if switching away from LORA
    if (!this.isLoraMode) {
      delete this.config.peftConfig;
    }
  }

  startTraining(): void {
    if (!this.canLaunch) return;

    this.launching = true;
    this.successMessage = null;
    this.errorMessage = null;
    this.launchedJobId = null;

    // Build the request
    const request: TrainingConfigRequest = { ...this.config };

    if (this.isLoraMode) {
      const modules = this.peftConfig.targetModulesStr
        .split(',')
        .map(s => s.trim())
        .filter(s => s.length > 0);

      request.peftConfig = {
        peftType: this.peftConfig.peftType,
        rank: this.peftConfig.rank,
        alpha: this.peftConfig.alpha,
        dropout: this.peftConfig.dropout,
        targetModules: modules
      };
    }

    this.http.post<TrainingJobStatus>(`${environment.apiUrl}/training/start`, request).subscribe({
      next: status => {
        this.launching = false;
        this.launchedJobId = status.taskId;
        this.successMessage = `Training job launched successfully! Job ID: ${status.taskId}`;
      },
      error: err => {
        this.launching = false;
        this.errorMessage = err.error?.error || err.error?.message || err.message || 'Failed to launch training job.';
      }
    });
  }

  downloadModel(): void {
    if (!this.downloadConfig.repository || !this.downloadConfig.modelId) return;

    this.downloading = true;
    this.downloadStatus = 'in_progress';
    this.downloadMessage = 'Starting download from HuggingFace...';

    const request = {
      source: 'huggingface',
      repository: this.downloadConfig.repository,
      modelId: this.downloadConfig.modelId,
      format: this.downloadConfig.format,
      type: this.downloadConfig.type,
      authToken: this.downloadConfig.authToken || undefined
    };

    this.http.post<any>(`${environment.apiUrl}/staging/stage`, request).subscribe({
      next: (status) => {
        this.downloadMessage = `Model staging started: ${status.modelId || this.downloadConfig.modelId}. Check Model & Staging tab for progress.`;
        this.downloadStatus = 'completed';
        this.downloading = false;
        // Refresh the models list after a delay to pick up the new model
        setTimeout(() => {
          this.http.get<any>(`${environment.apiUrl}/staging/registry`).subscribe({
            next: data => {
              if (Array.isArray(data)) {
                this.availableModels = data;
              } else if (data && data.models) {
                this.availableModels = data.models;
              } else if (data && data.entries) {
                this.availableModels = data.entries;
              }
            }
          });
        }, 3000);
      },
      error: err => {
        this.downloading = false;
        this.downloadStatus = 'failed';
        this.downloadMessage = err.error?.message || err.message || 'Download failed';
      }
    });
  }

  resetForm(): void {
    this.successMessage = null;
    this.errorMessage = null;
    this.launchedJobId = null;
    this.config = {
      modelId: '',
      datasetId: '',
      trainingType: 'FINETUNE',
      updaterConfig: {
        type: this.updaterTypes.length > 0 ? this.updaterTypes[0] : 'ADAMW',
        learningRate: 1e-4
      },
      lrSchedule: 'COSINE',
      warmupRatio: 0.1,
      epochs: 3,
      batchSize: 8,
      gradientAccumulationSteps: 1,
      maxSteps: -1,
      maxGradNorm: 1.0,
      fp16: false,
      bf16: false,
      loggingSteps: 10,
      saveSteps: 500,
      evalSteps: 500,
      seed: 42,
      autoRegister: true,
      evaluateAfterTraining: false,
      enableMonitoring: true
    };
    this.peftConfig = {
      peftType: this.peftTypes.length > 0 ? this.peftTypes[0] : '',
      rank: 8,
      alpha: 16,
      dropout: 0.05,
      targetModulesStr: ''
    };
  }
}
