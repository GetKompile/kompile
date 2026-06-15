import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  ComputeGraphConfig,
  ComputeGraphStatus,
  ComputeGraph,
  GraphExecutionResult,
  ComputeArtifact,
  ValidationResult
} from '../models/compute-graph-models';

@Injectable({ providedIn: 'root' })
export class ComputeGraphService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  getConfig(): Observable<ComputeGraphConfig> {
    return this.http.get<ComputeGraphConfig>(`${this.backendUrl}/compute-graph/config`);
  }

  updateConfig(config: ComputeGraphConfig): Observable<ComputeGraphConfig> {
    return this.http.put<ComputeGraphConfig>(`${this.backendUrl}/compute-graph/config`, config);
  }

  getStatus(): Observable<ComputeGraphStatus> {
    return this.http.get<ComputeGraphStatus>(`${this.backendUrl}/compute-graph/status`);
  }

  executeGraph(graph: ComputeGraph, inputs: { [key: string]: any }): Observable<GraphExecutionResult> {
    return this.http.post<GraphExecutionResult>(`${this.backendUrl}/compute-graph/execute`, { graph, inputs });
  }

  executeNode(graph: ComputeGraph, nodeId: string, inputs: { [key: string]: any }): Observable<GraphExecutionResult> {
    return this.http.post<GraphExecutionResult>(`${this.backendUrl}/compute-graph/execute-node`, { graph, nodeId, inputs });
  }

  validateGraph(graph: ComputeGraph): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(`${this.backendUrl}/compute-graph/validate`, graph);
  }

  getArtifacts(executionId: string): Observable<ComputeArtifact[]> {
    return this.http.get<ComputeArtifact[]>(`${this.backendUrl}/compute-graph/artifacts/${executionId}`);
  }

  getNodeArtifacts(executionId: string, nodeId: string): Observable<ComputeArtifact[]> {
    return this.http.get<ComputeArtifact[]>(`${this.backendUrl}/compute-graph/artifacts/${executionId}/${nodeId}`);
  }

  deleteArtifacts(executionId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/compute-graph/artifacts/${executionId}`);
  }
}
