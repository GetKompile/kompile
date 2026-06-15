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
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DistillationService } from '../../services/distillation.service';
import { DatasetService } from '../../services/dataset.service';
import { StagingService } from '../../services/staging.service';
import { ModelEntry, DatasetInfo, DistillationType } from '../../models/api-models';

@Component({
  selector: 'app-distillation-config',
  standalone: false,
  templateUrl: './distillation-config.component.html',
  styleUrls: ['./distillation-config.component.css']
})
export class DistillationConfigComponent implements OnInit {
  models: ModelEntry[] = [];
  datasets: DatasetInfo[] = [];
  distillationTypes: any[] = [];
  submitting = false;

  teacherModelId = '';
  studentModelId = '';
  distillationType: DistillationType = 'LOGIT_KD';
  temperature = 4.0;
  alpha = 0.5;
  datasetId = '';
  enableStudentPeft = false;

  distillationTypeCards: { id: DistillationType; name: string; description: string; icon: string }[] = [
    { id: 'LOGIT_KD', name: 'Logit Distillation', description: 'Transfer knowledge through output probability distributions (soft labels)', icon: 'functions' },
    { id: 'FEATURE_KD', name: 'Feature Distillation', description: 'Match intermediate layer representations between teacher and student', icon: 'layers' },
    { id: 'ATTENTION_KD', name: 'Attention Distillation', description: 'Transfer attention patterns from teacher to student model', icon: 'center_focus_strong' },
    { id: 'COMBINED', name: 'Combined', description: 'Combine logit, feature, and attention distillation for best results', icon: 'merge_type' }
  ];

  constructor(
    private distillationService: DistillationService,
    private datasetService: DatasetService,
    private stagingService: StagingService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.stagingService.getRegistry().subscribe({
      next: (reg) => this.models = Object.values(reg.models || {}),
      error: () => {}
    });
    this.datasetService.listDatasets().subscribe({
      next: (ds) => this.datasets = ds,
      error: () => {}
    });
    this.distillationService.getDistillationTypes().subscribe({
      next: (types) => this.distillationTypes = types,
      error: () => {}
    });
  }

  selectDistillationType(type: DistillationType): void {
    this.distillationType = type;
  }

  startDistillation(): void {
    if (!this.teacherModelId || !this.studentModelId || !this.datasetId) {
      this.snackBar.open('Please fill in all required fields', 'Close', { duration: 3000 });
      return;
    }
    this.submitting = true;
    const request: any = {
      teacherModelId: this.teacherModelId,
      studentModelId: this.studentModelId,
      distillationType: this.distillationType,
      temperature: this.temperature,
      alpha: this.alpha,
      datasetId: this.datasetId,
      trainingConfig: {
        modelId: this.studentModelId,
        datasetId: this.datasetId,
        epochs: 3,
        batchSize: 8,
        lrSchedule: 'COSINE',
        warmupRatio: 0.1,
        loggingSteps: 10,
        saveSteps: 500,
        evalSteps: 500,
        seed: 42,
        maxSteps: -1,
        maxGradNorm: 1.0,
        gradientAccumulationSteps: 1,
        fp16: false,
        bf16: false
      }
    };
    if (this.enableStudentPeft) {
      request.studentPeftConfig = {
        peftType: 'LORA',
        baseModelId: this.studentModelId,
        loraConfig: { rank: 8, alpha: 16.0, dropout: 0.05, bias: 'none', initMethod: 'kaiming_uniform' }
      };
    }
    this.distillationService.startDistillation(request).subscribe({
      next: (job) => {
        this.snackBar.open('Distillation started: ' + job.jobId, 'Close', { duration: 3000 });
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
