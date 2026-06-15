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

export type VlmOutputFormat = 'DOCTAGS' | 'MARKDOWN' | 'FLORENCE2' | 'DONUT' | 'PLAIN_TEXT' | 'JSON' | 'TEXT';

export interface TraceStep {
  name: string;
  durationMs: number;
  detail: string;
}

export interface PipelineTrace {
  success: boolean;
  fileName: string;
  fileSize: number;
  modelId: string;
  steps: TraceStep[];
  rawOutput?: string;
  parsedOutput?: string;
  outputFormat: string;
  tokensGenerated: number;
  tokensPerSecond: number;
  totalTimeMs: number;
  totalPages?: number;
  pageProcessed?: number;
  error?: string;
}

export interface OcrDebugStatus {
  registryModelCount: number;
  stagingReachable: boolean;
  vlmLoaded: boolean;
  vlmModelId?: string;
  vlmModelStatus: string;
  stagingUrl: string;
}

export interface RegistryModelInfo {
  modelId: string;
  type: string;
  status: string;
  path?: string;
  description?: string;
  framework?: string;
}

export interface VlmStatusResponse {
  reachable?: boolean;
  modelReady?: boolean;
  activeModelId?: string;
  modelStatus?: string;
  error?: string;
}

export interface PageRenderResult {
  success: boolean;
  page: number;
  totalPages: number;
  imageData?: string;
  width?: number;
  height?: number;
  format?: string;
  dpi?: number;
  error?: string;
}

// Backward-compatible types used by chunking-loader-test and document-debugger components
export interface OcrStatus {
  ocrEnabled: boolean;
  pipelineReady: boolean;
  postProcessorAvailable: boolean;
  // new fields from OcrDebugStatus
  stagingReachable?: boolean;
  vlmLoaded?: boolean;
  vlmModelId?: string;
  registryModelCount?: number;
}

export interface OcrConfig {
  ocrEnabled?: boolean;
  useVlm?: boolean;
  vlmModelId?: string;
  vlmOutputFormat?: VlmOutputFormat;
  vlmAvailable?: boolean;
  maxNewTokens?: number;
  temperature?: number;
  topP?: number;
  beamSize?: number;
  doSample?: boolean;
  detectionModel?: string;
  recognitionModel?: string;
  tableModel?: string;
  layoutModel?: string;
  enableTableExtraction?: boolean;
  enableLayoutAnalysis?: boolean;
  enablePostProcessing?: boolean;
  pdfRenderDpi?: number;
}

export interface OcrModelInfo {
  modelId: string;
  name?: string;
  type: string;
  description?: string;
  isLoaded?: boolean;
  status?: string;
}
