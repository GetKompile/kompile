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
import { AlignmentService } from '../../services/alignment.service';
import { DatasetService } from '../../services/dataset.service';
import { StagingService } from '../../services/staging.service';
import { ModelEntry, DatasetInfo, AlignmentAlgorithm } from '../../models/api-models';

@Component({
  selector: 'app-alignment-config',
  standalone: false,
  templateUrl: './alignment-config.component.html',
  styleUrls: ['./alignment-config.component.css']
})
export class AlignmentConfigComponent implements OnInit {
  models: ModelEntry[] = [];
  datasets: DatasetInfo[] = [];
  algorithms: any[] = [];
  submitting = false;

  selectedAlgorithm: AlignmentAlgorithm = 'DPO';
  baseModelId = '';
  rewardModelId = '';
  datasetId = '';
  beta = 0.1;
  labelSmoothness = 0.0;
  maxPromptLength = 512;
  maxCompletionLength = 256;
  enablePeft = true;

  algorithmCards: { id: AlignmentAlgorithm; name: string; description: string; needsReward: boolean }[] = [
    { id: 'DPO', name: 'Direct Preference Optimization', description: 'Directly optimizes the policy from preference pairs without a reward model', needsReward: false },
    { id: 'KTO', name: 'Kahneman-Tversky Optimization', description: 'Uses prospect theory-based loss for single good/bad examples', needsReward: false },
    { id: 'ORPO', name: 'Odds Ratio Preference Optimization', description: 'Combines SFT and preference optimization in a single stage', needsReward: false },
    { id: 'PPO', name: 'Proximal Policy Optimization', description: 'Classic RL approach using a reward model for preference learning', needsReward: true },
    { id: 'GRPO', name: 'Group Relative Policy Optimization', description: 'Group-based reward normalization for improved stability', needsReward: true }
  ];

  constructor(
    private alignmentService: AlignmentService,
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
    this.alignmentService.getAlgorithms().subscribe({
      next: (algs) => this.algorithms = algs,
      error: () => {}
    });
  }

  selectAlgorithm(alg: AlignmentAlgorithm): void {
    this.selectedAlgorithm = alg;
  }

  needsRewardModel(): boolean {
    const card = this.algorithmCards.find(c => c.id === this.selectedAlgorithm);
    return card?.needsReward || false;
  }

  startAlignment(): void {
    if (!this.baseModelId || !this.datasetId) {
      this.snackBar.open('Please select a model and dataset', 'Close', { duration: 3000 });
      return;
    }
    this.submitting = true;
    const request: any = {
      algorithm: this.selectedAlgorithm,
      baseModelId: this.baseModelId,
      rewardModelId: this.needsRewardModel() ? this.rewardModelId : undefined,
      datasetId: this.datasetId,
      beta: this.beta,
      labelSmoothness: this.labelSmoothness,
      maxPromptLength: this.maxPromptLength,
      maxCompletionLength: this.maxCompletionLength,
      trainingConfig: {
        modelId: this.baseModelId,
        datasetId: this.datasetId,
        epochs: 1,
        batchSize: 4,
        lrSchedule: 'COSINE',
        warmupRatio: 0.1,
        loggingSteps: 10,
        saveSteps: 500,
        evalSteps: 500,
        seed: 42,
        maxSteps: -1,
        maxGradNorm: 1.0,
        gradientAccumulationSteps: 4,
        fp16: false,
        bf16: false
      }
    };
    if (this.enablePeft) {
      request.peftConfig = {
        peftType: 'LORA',
        baseModelId: this.baseModelId,
        loraConfig: { rank: 16, alpha: 32.0, dropout: 0.05, bias: 'none', initMethod: 'kaiming_uniform' }
      };
    }
    this.alignmentService.startAlignment(request).subscribe({
      next: (job) => {
        this.snackBar.open('Alignment started: ' + job.jobId, 'Close', { duration: 3000 });
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
