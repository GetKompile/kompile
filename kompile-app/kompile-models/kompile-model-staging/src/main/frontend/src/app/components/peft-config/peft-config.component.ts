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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { PeftService } from '../../services/peft.service';
import { TrainingService } from '../../services/training.service';
import { StagingService } from '../../services/staging.service';
import { PeftType, ModelEntry } from '../../models/api-models';

@Component({
  selector: 'app-peft-config',
  standalone: false,
  templateUrl: './peft-config.component.html',
  styleUrls: ['./peft-config.component.css']
})
export class PeftConfigComponent implements OnInit {
  peftTypes: any[] = [];
  models: ModelEntry[] = [];
  selectedPeftType: PeftType = 'LORA';
  configForm: FormGroup;
  creating = false;

  peftTypeDescriptions: { [key: string]: string } = {
    'LORA': 'Low-Rank Adaptation - adds trainable low-rank matrices to existing weights',
    'QLORA': 'Quantized LoRA - combines 4-bit quantization with LoRA for memory efficiency',
    'ADALORA': 'Adaptive LoRA - dynamically allocates rank budget across layers',
    'DYLORA': 'Dynamic LoRA - trains across multiple ranks simultaneously',
    'DORA': 'Weight-Decomposed LoRA - decomposes into magnitude and direction components',
    'IA3': 'Infused Adapter by Inhibiting and Amplifying Inner Activations',
    'PROMPT_TUNING': 'Prepends learnable virtual tokens to the input',
    'PREFIX_TUNING': 'Prepends learnable prefix vectors to each layer'
  };

  constructor(
    private fb: FormBuilder,
    private peftService: PeftService,
    private trainingService: TrainingService,
    private stagingService: StagingService,
    private snackBar: MatSnackBar
  ) {
    this.configForm = this.fb.group({
      baseModelId: ['', Validators.required],
      // LoRA fields
      rank: [8],
      alpha: [16.0],
      dropout: [0.05],
      targetModules: [''],
      bias: ['none'],
      initMethod: ['kaiming_uniform'],
      // QLoRA fields
      quantType: ['nf4'],
      bits: [4],
      doubleQuant: [true],
      computeDtype: ['bfloat16'],
      // AdaLoRA fields
      initialRank: [12],
      targetRank: [8],
      warmupSteps: [100],
      pruningThreshold: [0.1],
      // DyLoRA fields
      minRank: [1],
      // DoRA fields
      decomposeWeight: [true],
      // Prompt Tuning
      numVirtualTokens: [20],
      initText: [''],
      // Prefix Tuning
      numPrefixTokens: [20],
      projectionDim: [512]
    });
  }

  ngOnInit(): void {
    this.loadPeftTypes();
    this.loadModels();
  }

  loadPeftTypes(): void {
    this.peftService.getTypes().subscribe({
      next: (types) => this.peftTypes = types,
      error: () => {}
    });
  }

  loadModels(): void {
    this.stagingService.getRegistry().subscribe({
      next: (registry) => {
        this.models = Object.values(registry.models || {});
      },
      error: () => {}
    });
  }

  selectPeftType(type: PeftType): void {
    this.selectedPeftType = type;
  }

  createPeftModel(): void {
    if (!this.configForm.get('baseModelId')?.value) {
      this.snackBar.open('Please select a base model', 'Close', { duration: 3000 });
      return;
    }
    this.creating = true;
    const formVal = this.configForm.value;
    const targetModules = formVal.targetModules ? formVal.targetModules.split(',').map((s: string) => s.trim()).filter((s: string) => s) : undefined;

    const request: any = {
      peftType: this.selectedPeftType,
      baseModelId: formVal.baseModelId
    };

    switch (this.selectedPeftType) {
      case 'LORA':
        request.loraConfig = { rank: formVal.rank, alpha: formVal.alpha, dropout: formVal.dropout, targetModules, bias: formVal.bias, initMethod: formVal.initMethod };
        break;
      case 'QLORA':
        request.qloraConfig = { rank: formVal.rank, alpha: formVal.alpha, dropout: formVal.dropout, targetModules, bias: formVal.bias, quantType: formVal.quantType, bits: formVal.bits, doubleQuant: formVal.doubleQuant, computeDtype: formVal.computeDtype };
        break;
      case 'ADALORA':
        request.adaLoraConfig = { initialRank: formVal.initialRank, targetRank: formVal.targetRank, warmupSteps: formVal.warmupSteps, pruningThreshold: formVal.pruningThreshold, targetModules };
        break;
      case 'DYLORA':
        request.dyLoraConfig = { rank: formVal.rank, alpha: formVal.alpha, dropout: formVal.dropout, minRank: formVal.minRank, targetModules };
        break;
      case 'DORA':
        request.doraConfig = { rank: formVal.rank, alpha: formVal.alpha, decomposeWeight: formVal.decomposeWeight, targetModules };
        break;
      case 'IA3':
        request.ia3Config = { targetModules, feedforwardModules: [] };
        break;
      case 'PROMPT_TUNING':
        request.promptTuningConfig = { numVirtualTokens: formVal.numVirtualTokens, initText: formVal.initText };
        break;
      case 'PREFIX_TUNING':
        request.prefixTuningConfig = { numPrefixTokens: formVal.numPrefixTokens, projectionDim: formVal.projectionDim };
        break;
    }

    this.peftService.createPeftModel(request).subscribe({
      next: (result) => {
        this.snackBar.open('PEFT model created successfully', 'Close', { duration: 3000 });
        this.creating = false;
      },
      error: (err) => {
        this.snackBar.open('Failed: ' + err.message, 'Close', { duration: 5000 });
        this.creating = false;
      }
    });
  }
}
