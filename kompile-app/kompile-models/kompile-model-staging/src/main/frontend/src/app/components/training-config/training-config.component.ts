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
import { FormBuilder, FormGroup } from '@angular/forms';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TrainingService } from '../../services/training.service';
import { DatasetService } from '../../services/dataset.service';
import { StagingService } from '../../services/staging.service';
import { ModelEntry, DatasetInfo, PeftType, UpdaterType, LrSchedule } from '../../models/api-models';

@Component({
  selector: 'app-training-config',
  standalone: false,
  templateUrl: './training-config.component.html',
  styleUrls: ['./training-config.component.css']
})
export class TrainingConfigComponent implements OnInit {
  models: ModelEntry[] = [];
  datasets: DatasetInfo[] = [];
  peftTypes: any[] = [];
  updaterTypes: any[] = [];
  lrSchedules: any[] = [];
  submitting = false;
  enablePeft = false;

  trainingForm: FormGroup;

  constructor(
    private fb: FormBuilder,
    private trainingService: TrainingService,
    private datasetService: DatasetService,
    private stagingService: StagingService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {
    this.trainingForm = this.fb.group({
      modelId: [''],
      datasetId: [''],
      // PEFT
      peftType: ['LORA'],
      rank: [8],
      alpha: [16.0],
      dropout: [0.05],
      // Optimizer
      updaterType: ['ADAM'],
      learningRate: [0.0001],
      weightDecay: [0.0],
      // Schedule
      lrSchedule: ['COSINE'],
      warmupRatio: [0.1],
      // Training
      epochs: [3],
      batchSize: [8],
      gradientAccumulationSteps: [1],
      maxSteps: [-1],
      maxGradNorm: [1.0],
      fp16: [false],
      bf16: [false],
      // Logging
      loggingSteps: [10],
      saveSteps: [500],
      evalSteps: [500],
      seed: [42]
    });
  }

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.stagingService.getRegistry().subscribe({
      next: (reg) => this.models = Object.values(reg.models || {}),
      error: () => {}
    });
    this.datasetService.listDatasets().subscribe({
      next: (ds) => this.datasets = ds,
      error: () => {}
    });
    this.trainingService.getPeftTypes().subscribe({ next: (t) => this.peftTypes = t, error: () => {} });
    this.trainingService.getUpdaterTypes().subscribe({ next: (t) => this.updaterTypes = t, error: () => {} });
    this.trainingService.getLrSchedules().subscribe({ next: (s) => this.lrSchedules = s, error: () => {} });
  }

  startTraining(): void {
    const f = this.trainingForm.value;
    const request: any = {
      modelId: f.modelId,
      datasetId: f.datasetId,
      updaterConfig: { type: f.updaterType, learningRate: f.learningRate, weightDecay: f.weightDecay, beta1: 0.9, beta2: 0.999, epsilon: 1e-8, momentum: 0.9 },
      lrSchedule: f.lrSchedule,
      warmupRatio: f.warmupRatio,
      epochs: f.epochs,
      batchSize: f.batchSize,
      gradientAccumulationSteps: f.gradientAccumulationSteps,
      maxSteps: f.maxSteps,
      maxGradNorm: f.maxGradNorm,
      fp16: f.fp16,
      bf16: f.bf16,
      loggingSteps: f.loggingSteps,
      saveSteps: f.saveSteps,
      evalSteps: f.evalSteps,
      seed: f.seed
    };
    if (this.enablePeft) {
      request.peftConfig = {
        peftType: f.peftType,
        baseModelId: f.modelId,
        loraConfig: { rank: f.rank, alpha: f.alpha, dropout: f.dropout, bias: 'none', initMethod: 'kaiming_uniform' }
      };
    }
    this.submitting = true;
    this.trainingService.startTraining(request).subscribe({
      next: (job) => {
        this.snackBar.open('Training job started: ' + job.jobId, 'Close', { duration: 3000 });
        this.submitting = false;
        this.router.navigate(['/training']);
      },
      error: (err) => {
        this.snackBar.open('Failed: ' + err.message, 'Close', { duration: 5000 });
        this.submitting = false;
      }
    });
  }
}
