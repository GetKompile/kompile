import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  WorkflowDetail,
  WorkflowSaveResponse,
  WorkflowInspection,
  WorkflowEngineType
} from '../models/workflow-models';
import { ComputeArtifact } from '../models/compute-graph-models';

@Injectable({ providedIn: 'root' })
export class WorkflowService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listWorkflows(engineType?: WorkflowEngineType): Observable<ComputeArtifact[]> {
    if (engineType) {
      return this.http.get<ComputeArtifact[]>(`${this.backendUrl}/workflows`, { params: { engineType } });
    }
    return this.http.get<ComputeArtifact[]>(`${this.backendUrl}/workflows`);
  }

  getWorkflow(engineType: WorkflowEngineType, name: string): Observable<WorkflowDetail> {
    return this.http.get<WorkflowDetail>(`${this.backendUrl}/workflows/${engineType}/${name}`);
  }

  saveWorkflow(engineType: WorkflowEngineType, name: string, content: string,
               metadata?: { [key: string]: any }): Observable<WorkflowSaveResponse> {
    return this.http.post<WorkflowSaveResponse>(
      `${this.backendUrl}/workflows/${engineType}/${name}`,
      { content, metadata }
    );
  }

  updateWorkflow(engineType: WorkflowEngineType, name: string, content: string,
                 metadata?: { [key: string]: any }): Observable<WorkflowSaveResponse> {
    return this.http.put<WorkflowSaveResponse>(
      `${this.backendUrl}/workflows/${engineType}/${name}`,
      { content, metadata }
    );
  }

  deleteWorkflow(engineType: WorkflowEngineType, name: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/workflows/${engineType}/${name}`);
  }

  inspectXircuits(workflowJson: string): Observable<WorkflowInspection> {
    return this.http.post<WorkflowInspection>(
      `${this.backendUrl}/workflows/inspect/xircuits`,
      workflowJson,
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  inspectN8n(workflowJson: string): Observable<WorkflowInspection> {
    return this.http.post<WorkflowInspection>(
      `${this.backendUrl}/workflows/inspect/n8n`,
      workflowJson,
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  inspect(engineType: WorkflowEngineType, workflowJson: string): Observable<WorkflowInspection> {
    return engineType === 'xircuits'
      ? this.inspectXircuits(workflowJson)
      : this.inspectN8n(workflowJson);
  }
}
