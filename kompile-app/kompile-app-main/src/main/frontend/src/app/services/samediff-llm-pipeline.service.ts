import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import {
  LlmPipelineDefinition,
  LlmStageDefinition,
  LlmCustomModelSet,
  PipelineListItem,
  StageListItem,
  ModelSetListItem,
  LlmRegistryStats,
  ValidationResponse,
  LlmGenerationPreset
} from '../models/samediff-llm-pipeline-models';

/**
 * Angular service for LLM pipeline configuration management.
 * Mirrors vlm-pipeline.service.ts for full VLM parity.
 */
@Injectable({
  providedIn: 'root'
})
export class SameDiffLlmPipelineService {
  private apiUrl: string;

  constructor(private http: HttpClient) {
    this.apiUrl = this.getApiUrl();
  }

  private getApiUrl(): string {
    if (typeof window !== 'undefined' && window.location) {
      const protocol = window.location.protocol;
      const hostname = window.location.hostname;
      const port = window.location.port;
      return `${protocol}//${hostname}${port ? ':' + port : ''}`;
    }
    return '';
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'An error occurred';
    if (error.error instanceof ErrorEvent) {
      errorMessage = error.error.message;
    } else {
      errorMessage = `Server error: ${error.status} - ${error.error?.error || error.message}`;
    }
    return throwError(() => new Error(errorMessage));
  }

  // ==================== Pipeline Config CRUD ====================

  listPipelines(type?: string): Observable<{ pipelines: PipelineListItem[], total: number }> {
    const params = type ? `?type=${type}` : '';
    return this.http.get<{ pipelines: PipelineListItem[], total: number }>(
      `${this.apiUrl}/api/llm/config/pipelines${params}`
    ).pipe(catchError(this.handleError));
  }

  getPipeline(pipelineId: string): Observable<LlmPipelineDefinition> {
    return this.http.get<LlmPipelineDefinition>(
      `${this.apiUrl}/api/llm/config/pipelines/${pipelineId}`
    ).pipe(catchError(this.handleError));
  }

  createPipeline(pipeline: LlmPipelineDefinition): Observable<any> {
    return this.http.post(
      `${this.apiUrl}/api/llm/config/pipelines`, pipeline
    ).pipe(catchError(this.handleError));
  }

  updatePipeline(pipelineId: string, pipeline: LlmPipelineDefinition): Observable<any> {
    return this.http.put(
      `${this.apiUrl}/api/llm/config/pipelines/${pipelineId}`, pipeline
    ).pipe(catchError(this.handleError));
  }

  deletePipeline(pipelineId: string): Observable<any> {
    return this.http.delete(
      `${this.apiUrl}/api/llm/config/pipelines/${pipelineId}`
    ).pipe(catchError(this.handleError));
  }

  validatePipeline(pipeline: LlmPipelineDefinition): Observable<ValidationResponse> {
    return this.http.post<ValidationResponse>(
      `${this.apiUrl}/api/llm/config/pipelines/validate`, pipeline
    ).pipe(catchError(this.handleError));
  }

  // ==================== Stage Management ====================

  listStages(): Observable<{ stages: StageListItem[], total: number }> {
    return this.http.get<{ stages: StageListItem[], total: number }>(
      `${this.apiUrl}/api/llm/config/stages`
    ).pipe(catchError(this.handleError));
  }

  getStage(stageId: string): Observable<LlmStageDefinition> {
    return this.http.get<LlmStageDefinition>(
      `${this.apiUrl}/api/llm/config/stages/${stageId}`
    ).pipe(catchError(this.handleError));
  }

  createStage(stage: LlmStageDefinition): Observable<any> {
    return this.http.post(
      `${this.apiUrl}/api/llm/config/stages`, stage
    ).pipe(catchError(this.handleError));
  }

  deleteStage(stageId: string): Observable<any> {
    return this.http.delete(
      `${this.apiUrl}/api/llm/config/stages/${stageId}`
    ).pipe(catchError(this.handleError));
  }

  // ==================== Model Set Management ====================

  listModelSets(): Observable<{ modelSets: ModelSetListItem[], total: number }> {
    return this.http.get<{ modelSets: ModelSetListItem[], total: number }>(
      `${this.apiUrl}/api/llm/config/model-sets`
    ).pipe(catchError(this.handleError));
  }

  createCustomModelSet(modelSet: LlmCustomModelSet): Observable<any> {
    return this.http.post(
      `${this.apiUrl}/api/llm/config/model-sets`, modelSet
    ).pipe(catchError(this.handleError));
  }

  deleteCustomModelSet(setId: string): Observable<any> {
    return this.http.delete(
      `${this.apiUrl}/api/llm/config/model-sets/${setId}`
    ).pipe(catchError(this.handleError));
  }

  // ==================== Registry Stats ====================

  getRegistryStats(): Observable<LlmRegistryStats> {
    return this.http.get<LlmRegistryStats>(
      `${this.apiUrl}/api/llm/config/stats`
    ).pipe(catchError(this.handleError));
  }

  // ==================== Generation Presets ====================

  getPresets(): Observable<LlmGenerationPreset[]> {
    return this.http.get<LlmGenerationPreset[]>(
      `${this.apiUrl}/api/samediff-llm/presets`
    ).pipe(catchError(this.handleError));
  }

  // ==================== Pipeline Definitions (from service) ====================

  getPipelineDefinitions(): Observable<any[]> {
    return this.http.get<any[]>(
      `${this.apiUrl}/api/samediff-llm/pipeline-definitions`
    ).pipe(catchError(this.handleError));
  }
}
