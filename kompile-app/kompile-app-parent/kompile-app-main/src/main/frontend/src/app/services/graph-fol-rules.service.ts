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

/**
 * Mirrors ai.kompile.knowledgegraph.reasoning.controller.GraphRulesController.RuleDto.
 *
 * kind values:
 *  - "PSL"       — soft propagation rules built from the fact sheet's observed FactStore atoms
 *  - "ONTOLOGY"  — PSL rules compiled from the bound ontology's DOMAIN/RANGE axioms
 *  - "FILE_PSL"  — rules loaded from project-level <dataDir>/rules/*.psl files
 */
export interface RuleDto {
  kind: 'PSL' | 'ONTOLOGY' | 'FILE_PSL';
  ruleText: string;
  weight: number;
  hard: boolean;
  head: string;
  body: string;
}

/** Calls the GET /api/graph/{factSheetId}/rules endpoint. */
@Injectable({ providedIn: 'root' })
export class GraphFolRulesService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  /** Fetch all active rules for the given fact sheet (PSL + ONTOLOGY + FILE_PSL). */
  getRules(factSheetId: number): Observable<RuleDto[]> {
    return this.http.get<RuleDto[]>(`${this.backendUrl}/api/graph/${factSheetId}/rules`);
  }
}
