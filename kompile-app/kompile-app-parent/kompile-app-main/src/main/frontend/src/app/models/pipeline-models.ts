export interface PipelineSummary {
  pipelineId: string;
  pipelineType: string;
  stepCount: number;
  stepTypes: string[];
  createdAt: string;
  updatedAt: string;
  serving: boolean;
}

export interface StepSchema {
  name: string;
  runnerClassName: string;
  description: string;
  parameters: ParameterSchema[];
  inputs: ParameterSchema[];
  outputs: ParameterSchema[];
}

export interface ParameterSchema {
  name: string;
  type: string;
  description: string;
  required: boolean;
  defaultValue: any;
}

export interface PipelineExecutionResult {
  executionId: string;
  pipelineId: string;
  status: string;
  outputData: { [key: string]: any };
  errorMessage: string;
  durationMs: number;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

export interface CreatePipelineRequest {
  pipelineId: string;
  pipelineType: string;
  steps: StepConfigRequest[];
}

export interface StepConfigRequest {
  runnerClassName: string;
  parameters: { [key: string]: any };
}

// SDX Serving Models

export interface SdxModelInfo {
  modelId: string;
  path: string;
  format: string;
  loaded: boolean;
  sizeBytes?: number;
}

export interface SdxInferenceResult {
  status: string;
  modelId: string;
  outputData?: { [key: string]: any };
  errorMessage?: string;
  durationMs: number;
}

export interface SdxModelSchema {
  modelId: string;
  runnerClass: string;
  path: string;
  description?: string;
  parameters?: SdxSchemaParam[];
  inputs?: SdxSchemaParam[];
  outputs?: SdxSchemaParam[];
}

export interface SdxSchemaParam {
  name: string;
  type: string;
  description?: string;
  required: boolean;
  defaultValue?: any;
}

export interface SdxServingStatus {
  loadedModelCount: number;
  models: Array<{
    modelId: string;
    path: string;
    runnerClass: string;
    loadedAt: number;
  }>;
}
