import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  RagPipelineDefinition,
  PipelineModelStatus,
  RagPipelineResult
} from '../models/rag-pipeline-models';

@Injectable({
  providedIn: 'root'
})
export class RagPipelineService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listPipelines(): Observable<RagPipelineDefinition[]> {
    return this.http.get<RagPipelineDefinition[]>(`${this.backendUrl}/rag-pipelines`);
  }

  getTemplates(): Observable<RagPipelineDefinition[]> {
    return this.http.get<RagPipelineDefinition[]>(`${this.backendUrl}/rag-pipelines/templates`);
  }

  getPipeline(id: string): Observable<RagPipelineDefinition> {
    return this.http.get<RagPipelineDefinition>(`${this.backendUrl}/rag-pipelines/${id}`);
  }

  createPipeline(def: Partial<RagPipelineDefinition>): Observable<RagPipelineDefinition> {
    return this.http.post<RagPipelineDefinition>(`${this.backendUrl}/rag-pipelines`, def);
  }

  updatePipeline(id: string, def: Partial<RagPipelineDefinition>): Observable<RagPipelineDefinition> {
    return this.http.put<RagPipelineDefinition>(`${this.backendUrl}/rag-pipelines/${id}`, def);
  }

  deletePipeline(id: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/rag-pipelines/${id}`);
  }

  getModelStatus(id: string): Observable<PipelineModelStatus> {
    return this.http.get<PipelineModelStatus>(`${this.backendUrl}/rag-pipelines/${id}/model-status`);
  }

  executePipeline(id: string, query: string): Observable<RagPipelineResult> {
    return this.http.post<RagPipelineResult>(`${this.backendUrl}/rag-pipelines/${id}/execute`, { query });
  }
}
