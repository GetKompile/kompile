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

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

// ==================== Interfaces ====================

export interface LlmGenerateRequest {
  prompt: string;
  maxTokens?: number;
  temperature?: number;
  topK?: number;
  topP?: number;
  repetitionPenalty?: number;
  doSample?: boolean;
  presetName?: string;
  stopSequences?: string[];
  seed?: number;
  minTokens?: number;
  frequencyPenalty?: number;
  presencePenalty?: number;
}

export interface LlmGenerateResponse {
  generatedText: string;
  tokensPerSecond: number;
  firstTokenLatencyMs: number;
  totalTokens: number;
  finishReason: string;
  totalTimeMs: number;
}

export interface LlmModelStatus {
  modelId: string | null;
  loaded: boolean;
  memoryUsageMb: number;
  kvCacheType: string | null;
  message: string;
  decoderPath: string | null;
  maxContextLength: number;
}

export interface LlmLoadModelRequest {
  modelId: string;
  kvCacheType?: string;
  modelPath?: string;
}

export interface SamplingPreset {
  name: string;
  displayName: string;
  description: string;
  temperature: number;
  topK: number;
  topP: number;
  repetitionPenalty: number;
  doSample: boolean;
}

export interface SpeculativeConfig {
  enabled: boolean;
  ngramSize: number;
  maxSpeculativeTokens: number;
  useDraftModel: boolean;
  draftModelId?: string;
}

export interface DecoderConfig {
  decoderPath?: string;
  eosTokenId: number;
  maxContextLength: number;
  minNewTokens: number;
  stopSequences?: string[];
  seed: number;
  frequencyPenalty: number;
  presencePenalty: number;
  numHeads: number;
  headDim: number;
  numKvLayers: number;
}

export interface PipelineInfo {
  loaded: boolean;
  modelId: string | null;
  decoderPath: string | null;
  kvCacheStrategy: string | null;
  memoryUsageMb: number;
  decoderConfig: DecoderConfig | null;
  inputNames: string[] | null;
  outputNames: string[] | null;
  logitsOutputName: string | null;
  kvCacheKeyNames: string[] | null;
  kvCacheValueNames: string[] | null;
  currentSamplingConfig: { [key: string]: any } | null;
  speculativeConfig: SpeculativeConfig | null;
  message: string;
}

// ==================== Chat Interfaces ====================

export interface ChatMessage {
  role: string; // system, user, assistant
  content: string;
}

export interface ChatRequest {
  messages: ChatMessage[];
  chatTemplate?: string;
  maxTokens?: number;
  temperature?: number;
  topK?: number;
  topP?: number;
  repetitionPenalty?: number;
  doSample?: boolean;
  presetName?: string;
  stopSequences?: string[];
}

export interface ChatResponse {
  assistantMessage: string;
  formattedPrompt: string;
  tokensPerSecond: number;
  totalTokens: number;
  finishReason: string;
  totalTimeMs: number;
}

// ==================== Template Interfaces ====================

export interface PromptTemplate {
  id: string;
  name: string;
  template: string;
  variables: string[];
  description: string;
  createdAt: number;
}

export interface BuiltinTemplate {
  name: string;
  description: string;
}

// ==================== Pipeline Interfaces ====================

export interface PipelineStep {
  stepId: string;
  name: string;
  promptTemplate: string;
  maxTokens: number;
  presetName?: string;
}

export interface PipelineDefinition {
  id: string;
  name: string;
  steps: PipelineStep[];
  variables?: { [key: string]: string };
}

export interface StepOutput {
  stepId: string;
  stepName: string;
  prompt: string;
  output: string;
  timeMs: number;
  finishReason: string;
}

export interface PipelineResult {
  pipelineId: string;
  stepOutputs: StepOutput[];
  finalOutput: string;
  totalTimeMs: number;
  finishReason: string;
}

// ==================== Batch Interfaces ====================

export interface BatchGenerateRequest {
  prompts: string[];
  maxTokens?: number;
  temperature?: number;
  topK?: number;
  topP?: number;
  repetitionPenalty?: number;
  doSample?: boolean;
  presetName?: string;
}

export interface BatchGenerateResponse {
  results: LlmGenerateResponse[];
  totalTimeMs: number;
  successCount: number;
  errorCount: number;
}

@Injectable({
  providedIn: 'root'
})
export class LlmService {

  private baseUrl: string;

  constructor(private http: HttpClient) {
    // Dynamic API URL based on current location
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      this.baseUrl = `${protocol}//${hostname}${port ? ':' + port : ''}/api/llm`;
    } else {
      this.baseUrl = '/api/llm';
    }
  }

  // ==================== Model Management ====================

  loadModel(request: LlmLoadModelRequest): Observable<LlmModelStatus> {
    return this.http.post<LlmModelStatus>(`${this.baseUrl}/load`, request)
      .pipe(catchError(this.handleError));
  }

  unloadModel(): Observable<LlmModelStatus> {
    return this.http.post<LlmModelStatus>(`${this.baseUrl}/unload`, {})
      .pipe(catchError(this.handleError));
  }

  getStatus(): Observable<LlmModelStatus> {
    return this.http.get<LlmModelStatus>(`${this.baseUrl}/status`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Text Generation ====================

  generate(request: LlmGenerateRequest): Observable<LlmGenerateResponse> {
    return this.http.post<LlmGenerateResponse>(`${this.baseUrl}/generate`, request)
      .pipe(catchError(this.handleError));
  }

  cancelGeneration(): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/cancel`, {})
      .pipe(catchError(this.handleError));
  }

  isGenerating(): Observable<{ generating: boolean }> {
    return this.http.get<{ generating: boolean }>(`${this.baseUrl}/generating`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Streaming ====================

  connectToGenerateStream(request: LlmGenerateRequest): EventSource {
    const params = new URLSearchParams();
    params.set('prompt', request.prompt);
    if (request.maxTokens) params.set('maxTokens', request.maxTokens.toString());
    if (request.presetName) params.set('presetName', request.presetName);
    return new EventSource(`${this.baseUrl}/generate/stream?${params.toString()}`);
  }

  // ==================== Chat ====================

  chat(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.baseUrl}/chat`, request)
      .pipe(catchError(this.handleError));
  }

  connectToChatStream(request: ChatRequest): EventSource {
    // POST-based SSE - can't use EventSource directly, use fetch
    // For simplicity, we'll use the non-streaming version and handle streaming in the component
    // EventSource only supports GET, so for POST-based SSE we need a different approach
    // This is handled in the component using fetch API
    throw new Error('Use fetchChatStream() instead');
  }

  // ==================== Presets ====================

  getPresets(): Observable<SamplingPreset[]> {
    return this.http.get<SamplingPreset[]>(`${this.baseUrl}/presets`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Speculative Decoding ====================

  getSpeculativeConfig(): Observable<SpeculativeConfig> {
    return this.http.get<SpeculativeConfig>(`${this.baseUrl}/speculative`)
      .pipe(catchError(this.handleError));
  }

  updateSpeculativeConfig(config: SpeculativeConfig): Observable<SpeculativeConfig> {
    return this.http.put<SpeculativeConfig>(`${this.baseUrl}/speculative`, config)
      .pipe(catchError(this.handleError));
  }

  // ==================== Decoder Configuration ====================

  getDecoderConfig(): Observable<DecoderConfig> {
    return this.http.get<DecoderConfig>(`${this.baseUrl}/decoder-config`)
      .pipe(catchError(this.handleError));
  }

  updateDecoderConfig(config: DecoderConfig): Observable<DecoderConfig> {
    return this.http.put<DecoderConfig>(`${this.baseUrl}/decoder-config`, config)
      .pipe(catchError(this.handleError));
  }

  // ==================== Pipeline Info ====================

  getPipelineInfo(): Observable<PipelineInfo> {
    return this.http.get<PipelineInfo>(`${this.baseUrl}/pipeline-info`)
      .pipe(catchError(this.handleError));
  }

  // ==================== Prompt Templates ====================

  listTemplates(): Observable<PromptTemplate[]> {
    return this.http.get<PromptTemplate[]>(`${this.baseUrl}/templates`)
      .pipe(catchError(this.handleError));
  }

  getBuiltinTemplates(): Observable<BuiltinTemplate[]> {
    return this.http.get<BuiltinTemplate[]>(`${this.baseUrl}/templates/builtin`)
      .pipe(catchError(this.handleError));
  }

  createTemplate(template: Partial<PromptTemplate>): Observable<PromptTemplate> {
    return this.http.post<PromptTemplate>(`${this.baseUrl}/templates`, template)
      .pipe(catchError(this.handleError));
  }

  updateTemplate(id: string, template: Partial<PromptTemplate>): Observable<PromptTemplate> {
    return this.http.put<PromptTemplate>(`${this.baseUrl}/templates/${id}`, template)
      .pipe(catchError(this.handleError));
  }

  deleteTemplate(id: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/templates/${id}`)
      .pipe(catchError(this.handleError));
  }

  applyTemplate(id: string, variables: { [key: string]: string }): Observable<any> {
    return this.http.post<any>(`${this.baseUrl}/templates/${id}/apply`, variables)
      .pipe(catchError(this.handleError));
  }

  // ==================== Text Pipelines ====================

  listPipelines(): Observable<PipelineDefinition[]> {
    return this.http.get<PipelineDefinition[]>(`${this.baseUrl}/pipelines`)
      .pipe(catchError(this.handleError));
  }

  createPipeline(definition: Partial<PipelineDefinition>): Observable<PipelineDefinition> {
    return this.http.post<PipelineDefinition>(`${this.baseUrl}/pipelines`, definition)
      .pipe(catchError(this.handleError));
  }

  deletePipeline(id: string): Observable<any> {
    return this.http.delete<any>(`${this.baseUrl}/pipelines/${id}`)
      .pipe(catchError(this.handleError));
  }

  executePipeline(id: string, variables: { [key: string]: string }): Observable<PipelineResult> {
    return this.http.post<PipelineResult>(`${this.baseUrl}/pipelines/${id}/execute`, variables)
      .pipe(catchError(this.handleError));
  }

  // ==================== Batch Generation ====================

  generateBatch(request: BatchGenerateRequest): Observable<BatchGenerateResponse> {
    return this.http.post<BatchGenerateResponse>(`${this.baseUrl}/generate/batch`, request)
      .pipe(catchError(this.handleError));
  }

  // ==================== Helper ====================

  getBaseUrl(): string {
    return this.baseUrl;
  }

  // ==================== Error Handling ====================

  private handleError(error: HttpErrorResponse): Observable<never> {
    let errorMessage = 'An error occurred';

    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
    } else {
      // Server-side error
      if (error.error?.message) {
        errorMessage = error.error.message;
      } else if (error.error?.error) {
        errorMessage = error.error.error;
      } else {
        errorMessage = `Error ${error.status}: ${error.message}`;
      }
    }

    console.error('LlmService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
