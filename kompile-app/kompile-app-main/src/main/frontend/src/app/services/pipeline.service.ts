import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  PipelineSummary,
  StepSchema,
  PipelineExecutionResult,
  ValidationResult,
  CreatePipelineRequest,
  SdxModelInfo,
  SdxInferenceResult,
  SdxModelSchema,
  SdxServingStatus
} from '../models/pipeline-models';

@Injectable({
  providedIn: 'root'
})
export class PipelineService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listPipelines(): Observable<PipelineSummary[]> {
    return this.http.get<PipelineSummary[]>(`${this.backendUrl}/pipelines`)
      .pipe(catchError(this.handleError));
  }

  getPipeline(id: string): Observable<CreatePipelineRequest> {
    return this.http.get<CreatePipelineRequest>(`${this.backendUrl}/pipelines/${id}`)
      .pipe(catchError(this.handleError));
  }

  createPipeline(request: CreatePipelineRequest): Observable<PipelineSummary> {
    return this.http.post<PipelineSummary>(`${this.backendUrl}/pipelines`, request)
      .pipe(catchError(this.handleError));
  }

  updatePipeline(id: string, request: CreatePipelineRequest): Observable<PipelineSummary> {
    return this.http.put<PipelineSummary>(`${this.backendUrl}/pipelines/${id}`, request)
      .pipe(catchError(this.handleError));
  }

  deletePipeline(id: string): Observable<{ deleted: boolean }> {
    return this.http.delete<{ deleted: boolean }>(`${this.backendUrl}/pipelines/${id}`)
      .pipe(catchError(this.handleError));
  }

  validatePipeline(request: CreatePipelineRequest): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.backendUrl}/pipelines/validate`, request)
      .pipe(catchError(this.handleError));
  }

  executeSync(id: string, input: any): Observable<PipelineExecutionResult> {
    return this.http.post<PipelineExecutionResult>(`${this.backendUrl}/pipelines/${id}/execute`, input || {})
      .pipe(catchError(this.handleError));
  }

  executeAsync(id: string, input: any): Observable<{ executionId: string }> {
    return this.http.post<{ executionId: string }>(`${this.backendUrl}/pipelines/${id}/execute-async`, input || {})
      .pipe(catchError(this.handleError));
  }

  getAsyncResult(executionId: string): Observable<PipelineExecutionResult> {
    return this.http.get<PipelineExecutionResult>(`${this.backendUrl}/pipelines/executions/${executionId}`)
      .pipe(catchError(this.handleError));
  }

  servePipeline(id: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/pipelines/${id}/serve`, {})
      .pipe(catchError(this.handleError));
  }

  unservePipeline(id: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/pipelines/${id}/unserve`, {})
      .pipe(catchError(this.handleError));
  }

  getServingStatus(): Observable<{ [key: string]: boolean }> {
    return this.http.get<{ [key: string]: boolean }>(`${this.backendUrl}/pipelines/serving/status`)
      .pipe(catchError(this.handleError));
  }

  invokeServed(id: string, input: any): Observable<PipelineExecutionResult> {
    return this.http.post<PipelineExecutionResult>(`${this.backendUrl}/pipelines/serving/${id}/invoke`, input || {})
      .pipe(catchError(this.handleError));
  }

  getAvailableSteps(): Observable<StepSchema[]> {
    return this.http.get<StepSchema[]>(`${this.backendUrl}/pipelines/steps/available`)
      .pipe(catchError(this.handleError));
  }

  // ==================== SDX Serving ====================

  listSdxModels(): Observable<SdxModelInfo[]> {
    return this.http.get<SdxModelInfo[]>(`${this.backendUrl}/sdx/models`)
      .pipe(catchError(this.handleError));
  }

  loadSdxModel(modelId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/sdx/models/${modelId}/load`, {})
      .pipe(catchError(this.handleError));
  }

  unloadSdxModel(modelId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/sdx/models/${modelId}/unload`, {})
      .pipe(catchError(this.handleError));
  }

  getSdxModelSchema(modelId: string): Observable<SdxModelSchema> {
    return this.http.get<SdxModelSchema>(`${this.backendUrl}/sdx/models/${modelId}/schema`)
      .pipe(catchError(this.handleError));
  }

  runSdxInference(modelId: string, input: any): Observable<SdxInferenceResult> {
    return this.http.post<SdxInferenceResult>(`${this.backendUrl}/sdx/models/${modelId}/infer`, input || {})
      .pipe(catchError(this.handleError));
  }

  getSdxInputTemplate(modelId: string): Observable<any> {
    return this.http.get(`${this.backendUrl}/sdx/models/${modelId}/input-template`)
      .pipe(catchError(this.handleError));
  }

  getSdxStatus(): Observable<SdxServingStatus> {
    return this.http.get<SdxServingStatus>(`${this.backendUrl}/sdx/status`)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: any) {
    console.error('Pipeline service error:', error);
    return throwError(() => error);
  }
}
