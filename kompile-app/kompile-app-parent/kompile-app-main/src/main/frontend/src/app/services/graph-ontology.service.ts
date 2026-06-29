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
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

/** A non-conforming ENTITY node. Mirrors GraphConformanceReport.NodeViolation. */
export interface NodeViolation {
  nodeId: string;
  title: string;
  entityType: string;
  unknownType: boolean;
  messages: string[];
}

/** A non-conforming relationship/edge. Mirrors GraphConformanceReport.EdgeViolation. */
export interface EdgeViolation {
  edgeId: string;
  relationshipType: string;
  sourceType: string;
  targetType: string;
  reason: string;
}

/** OWL reasoning status for a fact sheet. Mirrors GET /api/graph-ontology/owl response. */
export interface OwlReasoningStatus {
  factSheetId: number;
  ontologyBound: boolean;
  ontologyName: string | null;
  classCount: number;
  objectPropertyCount: number;
  dataPropertyCount: number;
  axiomCount: number;
  entailmentsMaterialized: number;
  /** Instance-level inferred type assertions (is-a) from the OWL RL pass. */
  inferredTypeCount?: number;
  /** Inferred transitive-closure relations (has-a) from the OWL RL pass. */
  inferredRelationCount?: number;
  consistent: boolean;
  inconsistencies?: { description: string }[];
  sampleEntailments?: string[];
  reasonerActive: boolean;
}

/** Result of POST /api/graph-ontology/classify — the on-demand OWL classification run. */
export interface OwlClassificationResult {
  factSheetId: number;
  ontologyBound: boolean;
  ontologyName: string | null;
  inferredTypeCount: number;
  inferredRelationCount: number;
  entitiesClassified: number;
  edgesMaterialized: number;
  consistent: boolean;
  reasonerActive: boolean;
}

/** Mirrors ai.kompile.app.web.dto.ontology.GraphConformanceReport. */
export interface GraphConformanceReport {
  factSheetId: number;
  ontologyBound: boolean;
  ontologyId?: string;
  ontologyVersion?: number;
  ontologyName?: string;
  entitiesChecked: number;
  unknownTypeCount: number;
  nonConformantCount: number;
  conformanceScore: number | null;
  violations: NodeViolation[];
  edgesChecked: number;
  nonConformantEdgeCount: number;
  edgeViolations: EdgeViolation[];
  message: string;
}

/**
 * Ontology binding + conformance for a fact sheet's graph (graph-as-asset Phase 6/8). Binding lives
 * on the fact sheet's NamedGraph; conformance validates the graph's ENTITY nodes (+ edges) against
 * the bound ontology. Ontology listing for the bind picker is reused from ProcessEngineService.
 */
@Injectable({ providedIn: 'root' })
export class GraphOntologyService extends BaseService {
  private readonly apiPath = '/process/ontology';

  constructor(private http: HttpClient) {
    super();
  }

  /** Validate the fact sheet's graph against its bound ontology. */
  conformance(factSheetId: number): Observable<GraphConformanceReport> {
    return this.http.get<GraphConformanceReport>(
      `${this.backendUrl}${this.apiPath}/conformance`, { params: { factSheetId } });
  }

  /** Bind an ontology to the fact sheet's graph (omit version for the latest). */
  bind(factSheetId: number, ontologySchemaId: string, ontologyVersion?: number): Observable<any> {
    const params: { [k: string]: string | number } = { factSheetId, ontologySchemaId };
    if (ontologyVersion != null) {
      params['ontologyVersion'] = ontologyVersion;
    }
    return this.http.put<any>(`${this.backendUrl}${this.apiPath}/binding`, {}, { params });
  }

  /** Clear the fact sheet's explicit ontology binding. */
  unbind(factSheetId: number): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/binding`, { params: { factSheetId } });
  }

  /** Load OWL reasoning status for the fact sheet (GET /api/graph-ontology/owl). */
  owl(factSheetId: number): Observable<OwlReasoningStatus> {
    return this.http.get<OwlReasoningStatus>(
      `${this.backendUrl}/graph-ontology/owl`, { params: { factSheetId } });
  }

  /** Run OWL classification on demand + persist the results (POST /api/graph-ontology/classify). */
  classify(factSheetId: number): Observable<OwlClassificationResult> {
    return this.http.post<OwlClassificationResult>(
      `${this.backendUrl}/graph-ontology/classify`, {}, { params: { factSheetId } });
  }
}
