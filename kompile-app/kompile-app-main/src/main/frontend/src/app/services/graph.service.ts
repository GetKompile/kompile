/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  GraphNode,
  GraphEdge,
  NodeLevel,
  EdgeType,
  D3VisualizationData,
  GraphStatistics,
  CreateNodeRequest,
  UpdateNodeRequest,
  CreateEdgeRequest,
  UpdateEdgeRequest,
  CreateCompositeEntityRequest,
  HierarchyTreeNode,
  NamedGraph,
  CreateNamedGraphRequest,
  GraphMetadataPatchRequest,
  GraphMetadataPatchResult,
  MoveGraphResult
} from '../models/graph-models';

// ─── Fact-sheet graph operation result types ───────────────────────────────

export interface GraphBuildStatus {
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  nodesCreated?: number;
  edgesCreated?: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  processedDocuments?: number;
  totalDocuments?: number;
  conceptsExtracted?: number;
}

export interface GraphBuildCancelResult {
  cancelled: boolean;
  jobId?: string;
}

export interface FactSheetGraphStatistics {
  nodesByType?: Record<string, number>;
  edgesByType?: Record<string, number>;
  distinctConcepts?: number;
  totalNodes?: number;
  totalEdges?: number;
}

export interface GraphClearResult {
  entitiesDeleted?: number;
  edgesDeleted?: number;
}

export interface GraphConceptEntry {
  concept: string;
  count: number;
  nodeIds?: string[];
}

export interface GraphEdgeRebuildResult {
  edgesCreated?: number;
  edgesDeleted?: number;
}

export interface SourceLink {
  id?: string;
  sourceNodeId1: string;
  sourceNodeId2: string;
  description?: string;
  strength?: number;
  createdAt?: string;
}

export interface SourceLinkResult {
  linksCreated?: number;
  linksDeleted?: number;
}

export interface SourceConnectivity {
  totalSources?: number;
  connectedSources?: number;
  isolatedSources?: number;
  avgConnectionsPerSource?: number;
}

// ─── Raw backend visualization response (before D3 normalization) ──────────

interface RawVisualizationResponse {
  nodes?: D3VisualizationData['nodes'];
  links?: D3VisualizationData['links'];
  edges?: D3VisualizationData['links'];
  statistics?: GraphStatistics;
  metadata?: GraphStatistics;
}

// ───────────────────────────────────────────────────────────────────────────

@Injectable({
  providedIn: 'root'
})
export class GraphService extends BaseService {

  private readonly apiPath = '/knowledge-graph';

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // NODE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all nodes with optional filtering
   */
  getNodes(type?: NodeLevel, query?: string, limit: number = 50): Observable<GraphNode[]> {
    let params = new HttpParams().set('limit', limit.toString());
    if (type) {
      params = params.set('type', type);
    }
    if (query) {
      params = params.set('query', query);
    }
    return this.http.get<GraphNode[]>(`${this.backendUrl}${this.apiPath}/nodes`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific node by ID
   */
  getNode(nodeId: string): Observable<GraphNode> {
    return this.http.get<GraphNode>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get children of a node
   */
  getNodeChildren(nodeId: string): Observable<GraphNode[]> {
    return this.http.get<GraphNode[]>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}/children`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get connected nodes within a certain depth
   */
  getConnectedNodes(nodeId: string, depth: number = 2): Observable<GraphNode[]> {
    const params = new HttpParams().set('depth', depth.toString());
    return this.http.get<GraphNode[]>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}/connected`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get related nodes based on similarity
   */
  getRelatedNodes(nodeId: string, maxResults: number = 10): Observable<GraphNode[]> {
    const params = new HttpParams().set('maxResults', maxResults.toString());
    return this.http.get<GraphNode[]>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}/related`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a new node
   */
  createNode(request: CreateNodeRequest): Observable<GraphNode> {
    return this.http.post<GraphNode>(`${this.backendUrl}${this.apiPath}/nodes`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update an existing node
   */
  updateNode(nodeId: string, request: UpdateNodeRequest): Observable<GraphNode> {
    return this.http.patch<GraphNode>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a node
   */
  deleteNode(nodeId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EDGE OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get edges for a node with optional type filtering
   */
  getEdges(nodeId?: string, type?: EdgeType): Observable<GraphEdge[]> {
    let params = new HttpParams();
    if (nodeId) {
      params = params.set('nodeId', nodeId);
    }
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<GraphEdge[]>(`${this.backendUrl}${this.apiPath}/edges`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific edge by ID
   */
  getEdge(edgeId: string): Observable<GraphEdge> {
    return this.http.get<GraphEdge>(`${this.backendUrl}${this.apiPath}/edges/${edgeId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a new edge between nodes
   */
  createEdge(request: CreateEdgeRequest): Observable<GraphEdge> {
    return this.http.post<GraphEdge>(`${this.backendUrl}${this.apiPath}/edges`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update an existing edge
   */
  updateEdge(edgeId: string, request: UpdateEdgeRequest): Observable<GraphEdge> {
    return this.http.patch<GraphEdge>(`${this.backendUrl}${this.apiPath}/edges/${edgeId}`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete an edge
   */
  deleteEdge(edgeId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/edges/${edgeId}`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // VISUALIZATION & STATISTICS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get D3-formatted visualization data
   */
  getVisualizationData(rootNodeId?: string, depth: number = 2, maxNodes: number = 100): Observable<D3VisualizationData> {
    let params = new HttpParams()
      .set('depth', depth.toString())
      .set('maxNodes', maxNodes.toString());
    if (rootNodeId) {
      params = params.set('rootNodeId', rootNodeId);
    }
    return this.http.get<RawVisualizationResponse>(`${this.backendUrl}${this.apiPath}/visualization`, { params })
      .pipe(
        map(response => this.transformVisualizationData(response)),
        catchError(this.handleError)
      );
  }

  /**
   * Get graph statistics
   */
  getStatistics(): Observable<GraphStatistics> {
    return this.http.get<GraphStatistics>(`${this.backendUrl}${this.apiPath}/statistics`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SEARCH
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Search nodes by query
   */
  searchNodes(query: string, type?: NodeLevel, limit: number = 20): Observable<GraphNode[]> {
    return this.getNodes(type, query, limit);
  }

  /**
   * Search edges by query, description, or connected node titles
   */
  searchEdges(query: string, type?: EdgeType, limit: number = 50): Observable<GraphEdge[]> {
    let params = new HttpParams()
      .set('query', query)
      .set('limit', limit.toString());
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<GraphEdge[]>(`${this.backendUrl}${this.apiPath}/edges/search`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all edges with optional filtering by query and type
   */
  getAllEdges(query?: string, type?: EdgeType, limit: number = 100): Observable<GraphEdge[]> {
    let params = new HttpParams().set('limit', limit.toString());
    if (query) {
      params = params.set('query', query);
    }
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<GraphEdge[]>(`${this.backendUrl}${this.apiPath}/edges`, { params })
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPER METHODS
  // ═══════════════════════════════════════════════════════════════════════════

  // ═══════════════════════════════════════════════════════════════════════════
  // FACT SHEET SCOPED OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get D3-formatted visualization data for a specific fact sheet
   */
  getFactSheetVisualizationData(factSheetId: number, maxNodes: number = 500, maxEdges: number = 1000): Observable<D3VisualizationData> {
    const params = new HttpParams()
      .set('maxNodes', maxNodes.toString())
      .set('maxEdges', maxEdges.toString());
    return this.http.get<RawVisualizationResponse>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/visualization`, { params })
      .pipe(
        map(response => this.transformVisualizationData(response)),
        catchError(this.handleError)
      );
  }

  /**
   * Build graph from indexed documents for a fact sheet
   */
  buildFactSheetGraph(factSheetId: number, config?: Record<string, unknown>): Observable<GraphBuildStatus> {
    return this.http.post<GraphBuildStatus>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/build`, { config })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get build status for a fact sheet graph job
   */
  getFactSheetBuildStatus(factSheetId: number, jobId: string): Observable<GraphBuildStatus> {
    return this.http.get<GraphBuildStatus>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/build/status/${jobId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Cancel a graph building job
   */
  cancelFactSheetBuild(factSheetId: number, jobId: string): Observable<GraphBuildCancelResult> {
    return this.http.post<GraphBuildCancelResult>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/build/cancel/${jobId}`, {})
      .pipe(catchError(this.handleError));
  }

  /**
   * Get graph statistics for a fact sheet
   */
  getFactSheetStatistics(factSheetId: number): Observable<FactSheetGraphStatistics> {
    return this.http.get<FactSheetGraphStatistics>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/statistics`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Clear graph for a fact sheet
   */
  clearFactSheetGraph(factSheetId: number): Observable<GraphClearResult> {
    return this.http.delete<GraphClearResult>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get top concepts for a fact sheet
   */
  getTopConcepts(factSheetId: number, limit: number = 20): Observable<GraphConceptEntry[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<GraphConceptEntry[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/concepts/top`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Rebuild concept edges for a fact sheet
   */
  rebuildConceptEdges(factSheetId: number, minSharedConcepts: number = 2): Observable<GraphEdgeRebuildResult> {
    const params = new HttpParams().set('minSharedConcepts', minSharedConcepts.toString());
    return this.http.post<GraphEdgeRebuildResult>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/concepts/rebuild-edges`, {}, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Search nodes in a fact sheet's graph
   */
  searchFactSheetNodes(factSheetId: number, query: string, limit: number = 20): Observable<GraphNode[]> {
    const params = new HttpParams()
      .set('query', query)
      .set('limit', limit.toString());
    return this.http.get<GraphNode[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/search`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get related documents for a fact sheet
   */
  getRelatedDocuments(factSheetId: number, documentNodeId: string, minShared: number = 2, limit: number = 10): Observable<GraphNode[]> {
    const params = new HttpParams()
      .set('minSharedConcepts', minShared.toString())
      .set('limit', limit.toString());
    return this.http.get<GraphNode[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/documents/${documentNodeId}/related`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Link sources within a fact sheet
   */
  linkSources(factSheetId: number, config?: Record<string, unknown>): Observable<SourceLinkResult> {
    return this.http.post<SourceLinkResult>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/link`, { config })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get source links for a fact sheet
   */
  getSourceLinks(factSheetId: number): Observable<SourceLink[]> {
    return this.http.get<SourceLink[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/links`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a manual link between sources
   */
  createSourceLink(factSheetId: number, sourceNodeId1: string, sourceNodeId2: string, description?: string, strength?: number): Observable<SourceLink> {
    return this.http.post<SourceLink>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/links`, {
      sourceNodeId1,
      sourceNodeId2,
      description,
      strength
    }).pipe(catchError(this.handleError));
  }

  /**
   * Remove a source link
   */
  removeSourceLink(factSheetId: number, sourceNodeId1: string, sourceNodeId2: string): Observable<void> {
    const params = new HttpParams()
      .set('sourceNodeId1', sourceNodeId1)
      .set('sourceNodeId2', sourceNodeId2);
    return this.http.delete<void>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/links`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get source connectivity summary
   */
  getSourceConnectivity(factSheetId: number): Observable<SourceConnectivity> {
    return this.http.get<SourceConnectivity>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/connectivity`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Find isolated sources
   */
  findIsolatedSources(factSheetId: number): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/isolated`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Find most connected sources
   */
  findMostConnectedSources(factSheetId: number, limit: number = 10): Observable<GraphNode[]> {
    const params = new HttpParams().set('limit', limit.toString());
    return this.http.get<GraphNode[]>(`${this.backendUrl}/fact-sheets/${factSheetId}/graph/sources/most-connected`, { params })
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HIERARCHY OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get the hierarchy tree rooted at a given node
   */
  getHierarchy(nodeId: string, depth: number = 2): Observable<HierarchyTreeNode> {
    const params = new HttpParams().set('depth', depth.toString());
    return this.http.get<HierarchyTreeNode>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}/hierarchy`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get ancestors of a node
   */
  getAncestors(nodeId: string): Observable<GraphNode[]> {
    return this.http.get<GraphNode[]>(`${this.backendUrl}${this.apiPath}/nodes/${nodeId}/ancestors`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a composite entity node
   */
  createCompositeEntity(request: CreateCompositeEntityRequest): Observable<GraphNode> {
    return this.http.post<GraphNode>(`${this.backendUrl}${this.apiPath}/nodes/composite`, request)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // NAMED GRAPH OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all named graphs, optionally filtered by a search query
   */
  getNamedGraphs(query?: string): Observable<NamedGraph[]> {
    let params = new HttpParams();
    if (query) params = params.set('query', query);
    return this.http.get<NamedGraph[]>(`${this.backendUrl}${this.apiPath}/named-graphs`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a single named graph by ID
   */
  getNamedGraph(graphId: string): Observable<NamedGraph> {
    return this.http.get<NamedGraph>(`${this.backendUrl}${this.apiPath}/named-graphs/${graphId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a named graph
   */
  createNamedGraph(request: CreateNamedGraphRequest): Observable<NamedGraph> {
    return this.http.post<NamedGraph>(`${this.backendUrl}${this.apiPath}/named-graphs`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update a named graph
   */
  updateNamedGraph(graphId: string, updates: Partial<NamedGraph>): Observable<NamedGraph> {
    return this.http.put<NamedGraph>(`${this.backendUrl}${this.apiPath}/named-graphs/${graphId}`, updates)
      .pipe(catchError(this.handleError));
  }

  /**
   * Delete a named graph
   */
  deleteNamedGraph(graphId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/named-graphs/${graphId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get child graphs of a named graph
   */
  getChildGraphs(graphId: string): Observable<NamedGraph[]> {
    return this.http.get<NamedGraph[]>(`${this.backendUrl}${this.apiPath}/named-graphs/${graphId}/children`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Move a graph to a new parent
   */
  moveGraph(graphId: string, newParentGraphId: string | null): Observable<MoveGraphResult> {
    return this.http.post<MoveGraphResult>(`${this.backendUrl}${this.apiPath}/named-graphs/${graphId}/move`,
      { newParentGraphId })
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // METADATA PATCH OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Patch node metadata in bulk using rules
   */
  patchNodeMetadata(request: GraphMetadataPatchRequest): Observable<GraphMetadataPatchResult> {
    return this.http.post<GraphMetadataPatchResult>(`${this.backendUrl}${this.apiPath}/nodes/patch-metadata`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Transform backend response to D3VisualizationData format
   */
  private transformVisualizationData(response: RawVisualizationResponse): D3VisualizationData {
    return {
      nodes: response.nodes || [],
      links: response.links || response.edges || [],
      statistics: response.statistics || response.metadata
    };
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      const serverError = error.error;
      if (serverError && (serverError.error || serverError.message)) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${serverError.error || serverError.message}`;
      } else if (error.message) {
        errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      } else {
        errorMessage = `Error Code: ${error.status}\nMessage: Server error`;
      }
    }
    console.error('GraphService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
