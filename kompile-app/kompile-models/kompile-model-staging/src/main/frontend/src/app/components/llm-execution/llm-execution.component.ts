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

import { Component, OnInit, OnDestroy, ChangeDetectorRef, ViewChild, ElementRef } from '@angular/core';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
  LlmService,
  LlmModelStatus,
  LlmGenerateRequest,
  LlmGenerateResponse,
  SamplingPreset,
  SpeculativeConfig,
  DecoderConfig,
  PipelineInfo,
  ChatMessage,
  ChatRequest,
  ChatResponse,
  PromptTemplate,
  BuiltinTemplate,
  PipelineDefinition,
  PipelineStep,
  PipelineResult,
  StepOutput,
  BatchGenerateRequest,
  BatchGenerateResponse
} from '../../services/llm.service';

export interface GenerationHistoryEntry {
  prompt: string;
  generatedText: string;
  tokensPerSecond: number;
  totalTokens: number;
  totalTimeMs: number;
  finishReason: string;
  timestamp: Date;
}

@Component({
  selector: 'app-llm-execution',
  standalone: false,
  templateUrl: './llm-execution.component.html',
  styleUrls: ['./llm-execution.component.css']
})
export class LlmExecutionComponent implements OnInit, OnDestroy {

  // Tab index
  activeTab = 0;

  // Model state
  modelStatus: LlmModelStatus | null = null;
  modelId: string = '';
  modelPath: string = '';
  kvCacheType: string = 'STATIC';
  isLoadingModel: boolean = false;

  // Sampling config
  prompt: string = '';
  maxTokens: number = 256;
  temperature: number = 1.0;
  topK: number = 0;
  topP: number = 1.0;
  repetitionPenalty: number = 1.0;
  doSample: boolean = false;
  selectedPreset: string = '';

  // Advanced generation settings
  showAdvanced: boolean = false;
  stopSequencesText: string = '';
  seed: number = -1;
  minTokens: number = 0;
  frequencyPenalty: number = 0.0;
  presencePenalty: number = 0.0;

  // Presets
  presets: SamplingPreset[] = [];

  // Speculative decoding
  speculativeEnabled: boolean = false;
  speculativeNgramSize: number = 3;
  speculativeMaxTokens: number = 5;
  speculativeUseDraftModel: boolean = false;
  speculativeDraftModelId: string = '';

  // Decoder config
  decoderConfigPath: string = '';
  eosTokenId: number = -1;
  maxContextLength: number = 0;
  decoderMinNewTokens: number = 0;
  decoderStopSequencesText: string = '';
  decoderSeed: number = -1;
  decoderFrequencyPenalty: number = 0.0;
  decoderPresencePenalty: number = 0.0;
  numHeads: number = 0;
  headDim: number = 0;
  numKvLayers: number = 0;

  // Pipeline info
  pipelineInfo: PipelineInfo | null = null;

  // Generation state
  isGenerating: boolean = false;
  generationOutput: string = '';
  generationResponse: LlmGenerateResponse | null = null;
  streamingEnabled: boolean = false;

  // History
  generationHistory: GenerationHistoryEntry[] = [];

  // KV cache type options
  kvCacheTypes: string[] = ['STATIC', 'PAGED', 'QUANTIZED'];

  // ==================== Chat State ====================
  chatMessages: ChatMessage[] = [];
  chatInput: string = '';
  chatSystemPrompt: string = '';
  selectedChatTemplate: string = 'chatml';
  builtinChatTemplates: BuiltinTemplate[] = [];
  isChatting: boolean = false;
  chatStreamingEnabled: boolean = false;
  chatMaxTokens: number = 256;

  // ==================== Templates State ====================
  promptTemplates: PromptTemplate[] = [];
  newTemplateName: string = '';
  newTemplateText: string = '';
  newTemplateDescription: string = '';
  templateTestVariables: { [key: string]: string } = {};
  selectedTemplateForTest: PromptTemplate | null = null;
  templateTestResult: string = '';

  // ==================== Pipelines State ====================
  textPipelines: PipelineDefinition[] = [];
  newPipelineName: string = '';
  newPipelineSteps: PipelineStep[] = [];
  pipelineInputVariables: string = '';
  selectedPipelineForRun: PipelineDefinition | null = null;
  pipelineResult: PipelineResult | null = null;
  isRunningPipeline: boolean = false;

  // ==================== Batch State ====================
  batchPromptsText: string = '';
  batchResult: BatchGenerateResponse | null = null;
  isRunningBatch: boolean = false;
  showBatchSection: boolean = false;

  private destroy$ = new Subject<void>();
  private currentEventSource: EventSource | null = null;

  constructor(
    private llmService: LlmService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadStatus();
    this.loadPresets();
    this.loadSpeculativeConfig();
    this.loadDecoderConfig();
    this.loadBuiltinChatTemplates();
    this.loadTemplates();
    this.loadPipelines();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    if (this.currentEventSource) {
      this.currentEventSource.close();
    }
  }

  // ==================== Model Management ====================

  loadStatus(): void {
    this.llmService.getStatus()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.modelStatus = status;
          if (status.modelId) {
            this.modelId = status.modelId;
          }
          if (status.decoderPath) {
            this.modelPath = status.decoderPath;
          }
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load status:', err);
        }
      });
  }

  loadModel(): void {
    if (!this.modelId || this.modelId.trim() === '') {
      this.showSnackbar('Please enter a model ID', true);
      return;
    }

    this.isLoadingModel = true;
    const request: any = {
      modelId: this.modelId,
      kvCacheType: this.kvCacheType
    };
    if (this.modelPath && this.modelPath.trim() !== '') {
      request.modelPath = this.modelPath;
    }

    this.llmService.loadModel(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.modelStatus = status;
          this.isLoadingModel = false;
          if (status.loaded) {
            this.showSnackbar('Model loaded successfully');
            this.loadPipelineInfo();
          } else {
            this.showSnackbar(status.message || 'Failed to load model', true);
          }
          this.cdr.detectChanges();
        },
        error: err => {
          this.isLoadingModel = false;
          this.showSnackbar('Failed to load model: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  unloadModel(): void {
    this.llmService.unloadModel()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: status => {
          this.modelStatus = status;
          this.generationOutput = '';
          this.generationResponse = null;
          this.pipelineInfo = null;
          this.showSnackbar('Model unloaded');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to unload model: ' + err.message, true);
        }
      });
  }

  // ==================== Presets ====================

  loadPresets(): void {
    this.llmService.getPresets()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: presets => {
          this.presets = presets;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load presets:', err);
        }
      });
  }

  applyPreset(): void {
    if (!this.selectedPreset) return;

    const preset = this.presets.find(p => p.name === this.selectedPreset);
    if (preset) {
      this.temperature = preset.temperature;
      this.topK = preset.topK;
      this.topP = preset.topP;
      this.repetitionPenalty = preset.repetitionPenalty;
      this.doSample = preset.doSample;
      this.showSnackbar('Applied preset: ' + preset.displayName);
    }
  }

  // ==================== Speculative Decoding ====================

  loadSpeculativeConfig(): void {
    this.llmService.getSpeculativeConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: config => {
          this.speculativeEnabled = config.enabled;
          this.speculativeNgramSize = config.ngramSize;
          this.speculativeMaxTokens = config.maxSpeculativeTokens;
          this.speculativeUseDraftModel = config.useDraftModel;
          this.speculativeDraftModelId = config.draftModelId || '';
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load speculative config:', err);
        }
      });
  }

  saveSpeculativeConfig(): void {
    const config: SpeculativeConfig = {
      enabled: this.speculativeEnabled,
      ngramSize: this.speculativeNgramSize,
      maxSpeculativeTokens: this.speculativeMaxTokens,
      useDraftModel: this.speculativeUseDraftModel,
      draftModelId: this.speculativeDraftModelId || undefined
    };

    this.llmService.updateSpeculativeConfig(config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar('Speculative decoding config updated');
        },
        error: err => {
          this.showSnackbar('Failed to update config: ' + err.message, true);
        }
      });
  }

  // ==================== Decoder Configuration ====================

  loadDecoderConfig(): void {
    this.llmService.getDecoderConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: config => {
          this.decoderConfigPath = config.decoderPath || '';
          this.eosTokenId = config.eosTokenId;
          this.maxContextLength = config.maxContextLength;
          this.decoderMinNewTokens = config.minNewTokens;
          this.decoderStopSequencesText = (config.stopSequences || []).join('\n');
          this.decoderSeed = config.seed;
          this.decoderFrequencyPenalty = config.frequencyPenalty;
          this.decoderPresencePenalty = config.presencePenalty;
          this.numHeads = config.numHeads;
          this.headDim = config.headDim;
          this.numKvLayers = config.numKvLayers;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load decoder config:', err);
        }
      });
  }

  saveDecoderConfig(): void {
    const stopSequences = this.decoderStopSequencesText
      .split('\n')
      .map(s => s.trim())
      .filter(s => s.length > 0);

    const config: DecoderConfig = {
      decoderPath: this.decoderConfigPath || undefined,
      eosTokenId: this.eosTokenId,
      maxContextLength: this.maxContextLength,
      minNewTokens: this.decoderMinNewTokens,
      stopSequences: stopSequences.length > 0 ? stopSequences : undefined,
      seed: this.decoderSeed,
      frequencyPenalty: this.decoderFrequencyPenalty,
      presencePenalty: this.decoderPresencePenalty,
      numHeads: this.numHeads,
      headDim: this.headDim,
      numKvLayers: this.numKvLayers
    };

    this.llmService.updateDecoderConfig(config)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar('Decoder configuration updated');
        },
        error: err => {
          this.showSnackbar('Failed to update decoder config: ' + err.message, true);
        }
      });
  }

  resetDecoderConfig(): void {
    this.decoderConfigPath = '';
    this.eosTokenId = -1;
    this.maxContextLength = 0;
    this.decoderMinNewTokens = 0;
    this.decoderStopSequencesText = '';
    this.decoderSeed = -1;
    this.decoderFrequencyPenalty = 0.0;
    this.decoderPresencePenalty = 0.0;
    this.numHeads = 0;
    this.headDim = 0;
    this.numKvLayers = 0;
    this.showSnackbar('Decoder config reset to defaults');
  }

  // ==================== Pipeline Info ====================

  loadPipelineInfo(): void {
    this.llmService.getPipelineInfo()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: info => {
          this.pipelineInfo = info;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load pipeline info:', err);
        }
      });
  }

  // ==================== Generation ====================

  generate(): void {
    if (!this.prompt || this.prompt.trim() === '') {
      this.showSnackbar('Please enter a prompt', true);
      return;
    }

    if (!this.modelStatus?.loaded) {
      this.showSnackbar('Please load a model first', true);
      return;
    }

    this.isGenerating = true;
    this.generationOutput = '';
    this.generationResponse = null;

    // Parse stop sequences from text field
    const stopSequences = this.stopSequencesText
      .split('\n')
      .map(s => s.trim())
      .filter(s => s.length > 0);

    const request: LlmGenerateRequest = {
      prompt: this.prompt,
      maxTokens: this.maxTokens,
      temperature: this.temperature,
      topK: this.topK,
      topP: this.topP,
      repetitionPenalty: this.repetitionPenalty,
      doSample: this.doSample,
      presetName: this.selectedPreset || undefined,
      stopSequences: stopSequences.length > 0 ? stopSequences : undefined,
      seed: this.seed !== -1 ? this.seed : undefined,
      minTokens: this.minTokens > 0 ? this.minTokens : undefined,
      frequencyPenalty: this.frequencyPenalty > 0 ? this.frequencyPenalty : undefined,
      presencePenalty: this.presencePenalty > 0 ? this.presencePenalty : undefined
    };

    if (this.streamingEnabled) {
      this.generateWithStreaming(request);
    } else {
      this.llmService.generate(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: response => {
            this.generationResponse = response;
            this.generationOutput = response.generatedText;
            this.isGenerating = false;
            this.addToHistory(response);
            this.cdr.detectChanges();
          },
          error: err => {
            this.isGenerating = false;
            this.showSnackbar('Generation failed: ' + err.message, true);
            this.cdr.detectChanges();
          }
        });
    }
  }

  private generateWithStreaming(request: LlmGenerateRequest): void {
    const baseUrl = this.llmService.getBaseUrl();
    const params = new URLSearchParams();
    params.set('prompt', request.prompt);
    if (request.maxTokens) params.set('maxTokens', request.maxTokens.toString());
    if (request.presetName) params.set('presetName', request.presetName);

    const eventSource = new EventSource(`${baseUrl}/generate/stream?${params.toString()}`);
    this.currentEventSource = eventSource;

    eventSource.addEventListener('token', (event: any) => {
      this.generationOutput += event.data;
      this.cdr.detectChanges();
    });

    eventSource.addEventListener('done', (event: any) => {
      try {
        const response = JSON.parse(event.data);
        this.generationResponse = response;
        this.addToHistory(response);
      } catch (e) {
        // ignore parse errors
      }
      this.isGenerating = false;
      eventSource.close();
      this.currentEventSource = null;
      this.cdr.detectChanges();
    });

    eventSource.addEventListener('error', (event: any) => {
      this.isGenerating = false;
      this.showSnackbar('Streaming error', true);
      eventSource.close();
      this.currentEventSource = null;
      this.cdr.detectChanges();
    });
  }

  cancelGeneration(): void {
    if (this.currentEventSource) {
      this.currentEventSource.close();
      this.currentEventSource = null;
    }
    this.llmService.cancelGeneration()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.isGenerating = false;
          this.showSnackbar('Generation cancelled');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to cancel: ' + err.message, true);
        }
      });
  }

  toggleAdvanced(): void {
    this.showAdvanced = !this.showAdvanced;
  }

  // ==================== Chat ====================

  loadBuiltinChatTemplates(): void {
    this.llmService.getBuiltinTemplates()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: templates => {
          this.builtinChatTemplates = templates;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load builtin chat templates:', err);
        }
      });
  }

  sendChatMessage(): void {
    if (!this.chatInput || this.chatInput.trim() === '') return;
    if (!this.modelStatus?.loaded) {
      this.showSnackbar('Please load a model first', true);
      return;
    }

    // Add system prompt if present and not already added
    const messages: ChatMessage[] = [];
    if (this.chatSystemPrompt && this.chatSystemPrompt.trim() !== '') {
      messages.push({ role: 'system', content: this.chatSystemPrompt });
    }
    // Add existing conversation history
    messages.push(...this.chatMessages);
    // Add new user message
    const userMsg: ChatMessage = { role: 'user', content: this.chatInput };
    messages.push(userMsg);
    this.chatMessages.push(userMsg);
    this.chatInput = '';
    this.isChatting = true;

    const request: ChatRequest = {
      messages: messages,
      chatTemplate: this.selectedChatTemplate,
      maxTokens: this.chatMaxTokens,
      temperature: this.temperature,
      topK: this.topK,
      topP: this.topP,
      repetitionPenalty: this.repetitionPenalty,
      doSample: this.doSample,
      presetName: this.selectedPreset || undefined
    };

    if (this.chatStreamingEnabled) {
      this.sendChatWithStreaming(request);
    } else {
      this.llmService.chat(request)
        .pipe(takeUntil(this.destroy$))
        .subscribe({
          next: response => {
            this.chatMessages.push({ role: 'assistant', content: response.assistantMessage });
            this.isChatting = false;
            this.cdr.detectChanges();
          },
          error: err => {
            this.isChatting = false;
            this.showSnackbar('Chat failed: ' + err.message, true);
            this.cdr.detectChanges();
          }
        });
    }
  }

  private sendChatWithStreaming(request: ChatRequest): void {
    const baseUrl = this.llmService.getBaseUrl();
    // For POST-based SSE, use fetch API
    const assistantMsg: ChatMessage = { role: 'assistant', content: '' };
    this.chatMessages.push(assistantMsg);

    fetch(`${baseUrl}/chat/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request)
    }).then(response => {
      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      if (!reader) {
        this.isChatting = false;
        return;
      }

      const readStream = (): void => {
        reader.read().then(({ done, value }) => {
          if (done) {
            this.isChatting = false;
            this.cdr.detectChanges();
            return;
          }

          const text = decoder.decode(value, { stream: true });
          // Parse SSE events
          const lines = text.split('\n');
          for (const line of lines) {
            if (line.startsWith('data:')) {
              const data = line.substring(5).trim();
              if (data) {
                // Check if it's a token event (simple string) or done event (JSON)
                try {
                  const parsed = JSON.parse(data);
                  if (parsed.assistantMessage !== undefined) {
                    // done event - don't append
                  }
                } catch {
                  // Simple token
                  assistantMsg.content += data;
                  this.cdr.detectChanges();
                }
              }
            }
          }
          readStream();
        });
      };
      readStream();
    }).catch(err => {
      this.isChatting = false;
      this.showSnackbar('Chat streaming failed: ' + err.message, true);
      this.cdr.detectChanges();
    });
  }

  clearChat(): void {
    this.chatMessages = [];
    this.showSnackbar('Chat cleared');
  }

  // ==================== Prompt Templates ====================

  loadTemplates(): void {
    this.llmService.listTemplates()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: templates => {
          this.promptTemplates = templates;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load templates:', err);
        }
      });
  }

  createTemplate(): void {
    if (!this.newTemplateName || !this.newTemplateText) {
      this.showSnackbar('Template name and text are required', true);
      return;
    }

    this.llmService.createTemplate({
      name: this.newTemplateName,
      template: this.newTemplateText,
      description: this.newTemplateDescription
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: template => {
          this.promptTemplates.push(template);
          this.newTemplateName = '';
          this.newTemplateText = '';
          this.newTemplateDescription = '';
          this.showSnackbar('Template created');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to create template: ' + err.message, true);
        }
      });
  }

  deleteTemplate(id: string): void {
    this.llmService.deleteTemplate(id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.promptTemplates = this.promptTemplates.filter(t => t.id !== id);
          if (this.selectedTemplateForTest?.id === id) {
            this.selectedTemplateForTest = null;
            this.templateTestResult = '';
          }
          this.showSnackbar('Template deleted');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to delete template: ' + err.message, true);
        }
      });
  }

  selectTemplateForTest(template: PromptTemplate): void {
    this.selectedTemplateForTest = template;
    this.templateTestVariables = {};
    if (template.variables) {
      for (const v of template.variables) {
        this.templateTestVariables[v] = '';
      }
    }
    this.templateTestResult = '';
  }

  applyTemplateTest(): void {
    if (!this.selectedTemplateForTest) return;

    this.llmService.applyTemplate(this.selectedTemplateForTest.id, this.templateTestVariables)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.templateTestResult = response.result;
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to apply template: ' + err.message, true);
        }
      });
  }

  useTemplateAsPrompt(): void {
    if (this.templateTestResult) {
      this.prompt = this.templateTestResult;
      this.activeTab = 0;
      this.showSnackbar('Template result loaded as prompt');
    }
  }

  // ==================== Text Pipelines ====================

  loadPipelines(): void {
    this.llmService.listPipelines()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: pipelines => {
          this.textPipelines = pipelines;
          this.cdr.detectChanges();
        },
        error: err => {
          console.error('Failed to load pipelines:', err);
        }
      });
  }

  addPipelineStep(): void {
    this.newPipelineSteps.push({
      stepId: 'step' + (this.newPipelineSteps.length + 1),
      name: 'Step ' + (this.newPipelineSteps.length + 1),
      promptTemplate: '',
      maxTokens: 256,
      presetName: undefined
    });
  }

  removePipelineStep(index: number): void {
    this.newPipelineSteps.splice(index, 1);
  }

  createPipeline(): void {
    if (!this.newPipelineName || this.newPipelineSteps.length === 0) {
      this.showSnackbar('Pipeline name and at least one step are required', true);
      return;
    }

    this.llmService.createPipeline({
      name: this.newPipelineName,
      steps: this.newPipelineSteps
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: pipeline => {
          this.textPipelines.push(pipeline);
          this.newPipelineName = '';
          this.newPipelineSteps = [];
          this.showSnackbar('Pipeline created');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to create pipeline: ' + err.message, true);
        }
      });
  }

  deletePipeline(id: string): void {
    this.llmService.deletePipeline(id)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.textPipelines = this.textPipelines.filter(p => p.id !== id);
          if (this.selectedPipelineForRun?.id === id) {
            this.selectedPipelineForRun = null;
            this.pipelineResult = null;
          }
          this.showSnackbar('Pipeline deleted');
          this.cdr.detectChanges();
        },
        error: err => {
          this.showSnackbar('Failed to delete pipeline: ' + err.message, true);
        }
      });
  }

  selectPipelineForRun(pipeline: PipelineDefinition): void {
    this.selectedPipelineForRun = pipeline;
    this.pipelineResult = null;
    this.pipelineInputVariables = '';
  }

  executePipeline(): void {
    if (!this.selectedPipelineForRun) return;
    if (!this.modelStatus?.loaded) {
      this.showSnackbar('Please load a model first', true);
      return;
    }

    this.isRunningPipeline = true;
    this.pipelineResult = null;

    // Parse input variables from text (key=value per line)
    const variables: { [key: string]: string } = {};
    if (this.pipelineInputVariables) {
      for (const line of this.pipelineInputVariables.split('\n')) {
        const idx = line.indexOf('=');
        if (idx > 0) {
          variables[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
        }
      }
    }

    this.llmService.executePipeline(this.selectedPipelineForRun.id, variables)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: result => {
          this.pipelineResult = result;
          this.isRunningPipeline = false;
          this.cdr.detectChanges();
        },
        error: err => {
          this.isRunningPipeline = false;
          this.showSnackbar('Pipeline execution failed: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  // ==================== Batch Generation ====================

  generateBatch(): void {
    if (!this.batchPromptsText || this.batchPromptsText.trim() === '') {
      this.showSnackbar('Please enter prompts separated by ---', true);
      return;
    }

    if (!this.modelStatus?.loaded) {
      this.showSnackbar('Please load a model first', true);
      return;
    }

    const prompts = this.batchPromptsText.split('---').map(p => p.trim()).filter(p => p.length > 0);
    if (prompts.length === 0) {
      this.showSnackbar('No valid prompts found', true);
      return;
    }

    this.isRunningBatch = true;
    this.batchResult = null;

    const request: BatchGenerateRequest = {
      prompts: prompts,
      maxTokens: this.maxTokens,
      temperature: this.temperature,
      topK: this.topK,
      topP: this.topP,
      repetitionPenalty: this.repetitionPenalty,
      doSample: this.doSample,
      presetName: this.selectedPreset || undefined
    };

    this.llmService.generateBatch(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: response => {
          this.batchResult = response;
          this.isRunningBatch = false;
          this.showSnackbar(`Batch complete: ${response.successCount} success, ${response.errorCount} errors`);
          this.cdr.detectChanges();
        },
        error: err => {
          this.isRunningBatch = false;
          this.showSnackbar('Batch generation failed: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  // ==================== History ====================

  private addToHistory(response: LlmGenerateResponse): void {
    const entry: GenerationHistoryEntry = {
      prompt: this.prompt,
      generatedText: response.generatedText,
      tokensPerSecond: response.tokensPerSecond,
      totalTokens: response.totalTokens,
      totalTimeMs: response.totalTimeMs,
      finishReason: response.finishReason,
      timestamp: new Date()
    };
    this.generationHistory.unshift(entry);
    // Keep last 50 entries
    if (this.generationHistory.length > 50) {
      this.generationHistory = this.generationHistory.slice(0, 50);
    }
  }

  clearHistory(): void {
    this.generationHistory = [];
    this.showSnackbar('History cleared');
  }

  loadFromHistory(entry: GenerationHistoryEntry): void {
    this.prompt = entry.prompt;
    this.activeTab = 0;
    this.showSnackbar('Prompt loaded from history');
  }

  truncateText(text: string, maxLength: number): string {
    if (!text) return '';
    if (text.length <= maxLength) return text;
    return text.substring(0, maxLength) + '...';
  }

  // ==================== UI Helpers ====================

  get isModelLoaded(): boolean {
    return this.modelStatus?.loaded === true;
  }

  formatTokensPerSecond(tps: number): string {
    return tps > 0 ? tps.toFixed(1) : '-';
  }

  formatTime(ms: number): string {
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(2) + 's';
  }

  formatTimestamp(date: Date): string {
    return date.toLocaleTimeString() + ' ' + date.toLocaleDateString();
  }

  objectKeys(obj: any): string[] {
    return obj ? Object.keys(obj) : [];
  }

  private showSnackbar(message: string, isError: boolean = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }
}
