import { Component, OnInit } from '@angular/core';
import { PipelineService } from '../../services/pipeline.service';
import {
  PipelineSummary,
  StepSchema,
  PipelineExecutionResult,
  ValidationResult,
  CreatePipelineRequest,
  StepConfigRequest,
  SdxModelInfo,
  SdxModelSchema,
  SdxInferenceResult
} from '../../models/pipeline-models';

@Component({
  standalone: false,
  selector: 'app-pipeline-hub',
  templateUrl: './pipeline-hub.component.html',
  styleUrls: ['./pipeline-hub.component.css']
})
export class PipelineHubComponent implements OnInit {
  activeTab: 'list' | 'builder' | 'runner' | 'stepBrowser' | 'serving' | 'sdx' = 'list';

  // List tab
  pipelines: PipelineSummary[] = [];
  loading = false;

  // Builder tab
  newPipelineId = '';
  newPipelineType = 'sequence';
  builderSteps: StepConfigRequest[] = [];
  selectedStepType = '';
  stepParameters: { [key: string]: string } = {};
  validationResult: ValidationResult | null = null;
  builderMessage = '';

  // Runner tab
  selectedPipelineId = '';
  runnerInput = '{}';
  executionResult: PipelineExecutionResult | null = null;
  executing = false;

  // Step browser
  availableSteps: StepSchema[] = [];
  expandedStep: string | null = null;

  // Serving tab
  servingStatus: { [key: string]: boolean } = {};
  serveInput = '{}';
  serveResult: PipelineExecutionResult | null = null;
  selectedServingPipeline = '';

  // SDX tab
  sdxModels: SdxModelInfo[] = [];
  sdxLoadingModel = false;
  sdxSelectedModel = '';
  sdxModelSchema: SdxModelSchema | null = null;
  sdxInferenceInput = '{}';
  sdxInferenceResult: SdxInferenceResult | null = null;
  sdxInferring = false;

  constructor(private pipelineService: PipelineService) {}

  ngOnInit(): void {
    this.loadPipelines();
    this.loadAvailableSteps();
  }

  // ==================== List Tab ====================

  loadPipelines(): void {
    this.loading = true;
    this.pipelineService.listPipelines().subscribe({
      next: (data) => {
        this.pipelines = data;
        this.loading = false;
      },
      error: (err) => {
        console.error('Failed to load pipelines:', err);
        this.loading = false;
      }
    });
  }

  deletePipeline(id: string): void {
    if (confirm(`Delete pipeline "${id}"?`)) {
      this.pipelineService.deletePipeline(id).subscribe({
        next: () => this.loadPipelines(),
        error: (err) => console.error('Delete failed:', err)
      });
    }
  }

  editPipeline(pipeline: PipelineSummary): void {
    this.pipelineService.getPipeline(pipeline.pipelineId).subscribe({
      next: (data) => {
        this.newPipelineId = data.pipelineId;
        this.newPipelineType = data.pipelineType || 'sequence';
        this.builderSteps = data.steps || [];
        this.activeTab = 'builder';
      },
      error: (err) => console.error('Failed to load pipeline:', err)
    });
  }

  // ==================== Builder Tab ====================

  loadAvailableSteps(): void {
    this.pipelineService.getAvailableSteps().subscribe({
      next: (data) => this.availableSteps = data,
      error: (err) => console.error('Failed to load step types:', err)
    });
  }

  addStep(): void {
    if (!this.selectedStepType) return;
    const step: StepConfigRequest = {
      runnerClassName: this.selectedStepType,
      parameters: { ...this.stepParameters }
    };
    this.builderSteps.push(step);
    this.stepParameters = {};
    this.selectedStepType = '';
  }

  removeStep(index: number): void {
    this.builderSteps.splice(index, 1);
  }

  moveStep(index: number, direction: number): void {
    const newIndex = index + direction;
    if (newIndex < 0 || newIndex >= this.builderSteps.length) return;
    const temp = this.builderSteps[index];
    this.builderSteps[index] = this.builderSteps[newIndex];
    this.builderSteps[newIndex] = temp;
  }

  validatePipeline(): void {
    const request = this.buildRequest();
    this.pipelineService.validatePipeline(request).subscribe({
      next: (result) => this.validationResult = result,
      error: (err) => console.error('Validation failed:', err)
    });
  }

  savePipeline(): void {
    if (!this.newPipelineId) {
      this.builderMessage = 'Pipeline ID is required';
      return;
    }
    const request = this.buildRequest();
    this.pipelineService.createPipeline(request).subscribe({
      next: () => {
        this.builderMessage = 'Pipeline saved successfully';
        this.loadPipelines();
      },
      error: (err) => {
        this.builderMessage = 'Failed to save: ' + (err.error?.message || err.message);
      }
    });
  }

  private buildRequest(): CreatePipelineRequest {
    return {
      pipelineId: this.newPipelineId,
      pipelineType: this.newPipelineType,
      steps: this.builderSteps
    };
  }

  clearBuilder(): void {
    this.newPipelineId = '';
    this.newPipelineType = 'sequence';
    this.builderSteps = [];
    this.validationResult = null;
    this.builderMessage = '';
  }

  getStepName(runnerClassName: string): string {
    const step = this.availableSteps.find(s => s.runnerClassName === runnerClassName);
    return step ? step.name : runnerClassName.split('.').pop() || runnerClassName;
  }

  getSelectedStepSchema(): StepSchema | undefined {
    return this.availableSteps.find(s => s.runnerClassName === this.selectedStepType);
  }

  // ==================== Runner Tab ====================

  executePipeline(): void {
    if (!this.selectedPipelineId) return;
    this.executing = true;
    this.executionResult = null;
    let input: any;
    try {
      input = JSON.parse(this.runnerInput);
    } catch {
      this.executionResult = {
        executionId: '',
        pipelineId: this.selectedPipelineId,
        status: 'ERROR',
        outputData: {},
        errorMessage: 'Invalid JSON input',
        durationMs: 0
      };
      this.executing = false;
      return;
    }
    this.pipelineService.executeSync(this.selectedPipelineId, input).subscribe({
      next: (result) => {
        this.executionResult = result;
        this.executing = false;
      },
      error: (err) => {
        this.executionResult = {
          executionId: '',
          pipelineId: this.selectedPipelineId,
          status: 'ERROR',
          outputData: {},
          errorMessage: err.error?.message || err.message || 'Execution failed',
          durationMs: 0
        };
        this.executing = false;
      }
    });
  }

  // ==================== Step Browser ====================

  toggleStep(runnerClassName: string): void {
    this.expandedStep = this.expandedStep === runnerClassName ? null : runnerClassName;
  }

  // ==================== Serving Tab ====================

  loadServingStatus(): void {
    this.pipelineService.getServingStatus().subscribe({
      next: (data) => this.servingStatus = data,
      error: (err) => console.error('Failed to load serving status:', err)
    });
  }

  toggleServe(id: string, currentlyServing: boolean): void {
    const action = currentlyServing
      ? this.pipelineService.unservePipeline(id)
      : this.pipelineService.servePipeline(id);

    action.subscribe({
      next: () => {
        this.loadServingStatus();
        this.loadPipelines();
      },
      error: (err) => console.error('Serve/unserve failed:', err)
    });
  }

  invokeServed(): void {
    if (!this.selectedServingPipeline) return;
    let input: any;
    try {
      input = JSON.parse(this.serveInput);
    } catch {
      return;
    }
    this.pipelineService.invokeServed(this.selectedServingPipeline, input).subscribe({
      next: (result) => this.serveResult = result,
      error: (err) => console.error('Invoke failed:', err)
    });
  }

  onTabChange(tab: 'list' | 'builder' | 'runner' | 'stepBrowser' | 'serving' | 'sdx'): void {
    this.activeTab = tab;
    if (tab === 'serving') {
      this.loadServingStatus();
    }
    if (tab === 'sdx') {
      this.loadSdxModels();
    }
  }

  // ==================== SDX Tab ====================

  loadSdxModels(): void {
    this.pipelineService.listSdxModels().subscribe({
      next: (data) => this.sdxModels = data,
      error: (err) => console.error('Failed to load SDX models:', err)
    });
  }

  loadSdxModel(modelId: string): void {
    this.sdxLoadingModel = true;
    this.pipelineService.loadSdxModel(modelId).subscribe({
      next: () => {
        this.sdxLoadingModel = false;
        this.loadSdxModels();
        this.sdxSelectedModel = modelId;
        this.loadSdxSchema(modelId);
      },
      error: (err) => {
        this.sdxLoadingModel = false;
        console.error('Failed to load model:', err);
      }
    });
  }

  unloadSdxModel(modelId: string): void {
    this.pipelineService.unloadSdxModel(modelId).subscribe({
      next: () => {
        this.loadSdxModels();
        if (this.sdxSelectedModel === modelId) {
          this.sdxSelectedModel = '';
          this.sdxModelSchema = null;
          this.sdxInferenceResult = null;
        }
      },
      error: (err) => console.error('Failed to unload model:', err)
    });
  }

  loadSdxSchema(modelId: string): void {
    this.pipelineService.getSdxModelSchema(modelId).subscribe({
      next: (schema) => this.sdxModelSchema = schema,
      error: (err) => console.error('Failed to load schema:', err)
    });
  }

  selectSdxModel(modelId: string): void {
    this.sdxSelectedModel = modelId;
    this.sdxInferenceResult = null;
    this.loadSdxSchema(modelId);
  }

  loadSdxInputTemplate(): void {
    if (!this.sdxSelectedModel) return;
    this.pipelineService.getSdxInputTemplate(this.sdxSelectedModel).subscribe({
      next: (template) => {
        this.sdxInferenceInput = JSON.stringify(template.inputs || {}, null, 2);
      },
      error: (err) => console.error('Failed to load template:', err)
    });
  }

  runSdxInference(): void {
    if (!this.sdxSelectedModel) return;
    this.sdxInferring = true;
    this.sdxInferenceResult = null;
    let input: any;
    try {
      input = JSON.parse(this.sdxInferenceInput);
    } catch {
      this.sdxInferenceResult = {
        status: 'error',
        modelId: this.sdxSelectedModel,
        errorMessage: 'Invalid JSON input',
        durationMs: 0
      };
      this.sdxInferring = false;
      return;
    }
    this.pipelineService.runSdxInference(this.sdxSelectedModel, input).subscribe({
      next: (result) => {
        this.sdxInferenceResult = result;
        this.sdxInferring = false;
      },
      error: (err) => {
        this.sdxInferenceResult = {
          status: 'error',
          modelId: this.sdxSelectedModel,
          errorMessage: err.error?.error || err.message || 'Inference failed',
          durationMs: 0
        };
        this.sdxInferring = false;
      }
    });
  }

  addSdxModelAsPipelineStep(modelId: string): void {
    const model = this.sdxModels.find(m => m.modelId === modelId);
    if (!model) return;
    const runnerClass = model.format === 'zip'
      ? 'ai.kompile.pipelines.steps.deeplearning4j.DL4JRunner'
      : 'ai.kompile.pipelines.steps.samediff.SameDiffRunner';
    this.builderSteps.push({
      runnerClassName: runnerClass,
      parameters: { modelUri: model.path }
    });
    this.activeTab = 'builder';
  }

  formatModelSize(bytes: number | undefined): string {
    if (!bytes) return 'Unknown';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  }

  formatJson(obj: any): string {
    try {
      return JSON.stringify(obj, null, 2);
    } catch {
      return String(obj);
    }
  }
}
