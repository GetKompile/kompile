import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface ExperimentDto {
  id: string;
  name: string;
  description: string;
  suiteId: string;
  datasetId: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface ExperimentRunDto {
  id: string;
  experimentId: string;
  modelId: string;
  modelVariant: string;
  modelType: string;
  suiteResultId: string;
  status: string;
  passRate: number | null;
  averageScore: number | null;
  passedCount: number | null;
  failedCount: number | null;
  totalCount: number | null;
  startedAt: string;
  completedAt: string;
  executionTimeMs: number | null;
  errorMessage: string;
}

export interface ExperimentWithRunsDto extends ExperimentDto {
  runs: ExperimentRunDto[];
}

export interface EvalDatasetDto {
  id: string;
  name: string;
  description: string;
  suiteId: string;
  format: string;
  sampleCount: number;
  version: string;
  createdAt: string;
  updatedAt: string;
}

export interface DatasetRowDto {
  id: string;
  name: string;
  query: string;
  expectedAnswer: string;
}

export interface CreateExperimentRequest {
  name: string;
  description?: string;
  suiteId?: string;
  datasetId?: string;
  tags?: string[];
}

export interface AddRunRequest {
  modelId: string;
  modelVariant?: string;
  modelType?: string;
}

@Injectable({ providedIn: 'root' })
export class ExperimentService {
  private baseUrl = `${environment.apiUrl}/experiments`;

  constructor(private http: HttpClient) {}

  create(request: CreateExperimentRequest): Observable<ExperimentDto> {
    return this.http.post<ExperimentDto>(this.baseUrl, request);
  }

  list(): Observable<ExperimentDto[]> {
    return this.http.get<ExperimentDto[]>(this.baseUrl);
  }

  get(id: string): Observable<ExperimentWithRunsDto> {
    return this.http.get<ExperimentWithRunsDto>(`${this.baseUrl}/${id}`);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  compare(id: string): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/${id}/compare`);
  }

  addRun(experimentId: string, request: AddRunRequest): Observable<ExperimentRunDto> {
    return this.http.post<ExperimentRunDto>(`${this.baseUrl}/${experimentId}/runs`, request);
  }

  executeRun(experimentId: string, runId: string): Observable<ExperimentRunDto> {
    return this.http.post<ExperimentRunDto>(`${this.baseUrl}/${experimentId}/runs/${runId}/execute`, {});
  }

  listRuns(experimentId: string): Observable<ExperimentRunDto[]> {
    return this.http.get<ExperimentRunDto[]>(`${this.baseUrl}/${experimentId}/runs`);
  }

  getRun(experimentId: string, runId: string): Observable<ExperimentRunDto> {
    return this.http.get<ExperimentRunDto>(`${this.baseUrl}/${experimentId}/runs/${runId}`);
  }

  getRunsByModel(modelId: string): Observable<ExperimentRunDto[]> {
    return this.http.get<ExperimentRunDto[]>(`${this.baseUrl}/models/${modelId}/runs`);
  }

  createEvalDataset(file: File, name: string, description?: string): Observable<EvalDatasetDto> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('name', name);
    if (description) formData.append('description', description);
    return this.http.post<EvalDatasetDto>(`${this.baseUrl}/eval-datasets`, formData);
  }

  listEvalDatasets(): Observable<EvalDatasetDto[]> {
    return this.http.get<EvalDatasetDto[]>(`${this.baseUrl}/eval-datasets`);
  }

  getEvalDataset(id: string): Observable<EvalDatasetDto> {
    return this.http.get<EvalDatasetDto>(`${this.baseUrl}/eval-datasets/${id}`);
  }

  deleteEvalDataset(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/eval-datasets/${id}`);
  }

  previewDataset(id: string, maxRows = 20): Observable<DatasetRowDto[]> {
    return this.http.get<DatasetRowDto[]>(`${this.baseUrl}/eval-datasets/${id}/preview`, {
      params: { maxRows }
    });
  }
}
