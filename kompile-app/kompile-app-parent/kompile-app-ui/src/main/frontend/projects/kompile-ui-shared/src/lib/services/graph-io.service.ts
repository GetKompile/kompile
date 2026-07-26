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
import { HttpClient, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';

/** Mirrors ai.kompile.knowledgegraph.io.model.ImportResult. */
export interface ImportResult {
  format: string;
  nodesCreated: number;
  nodesUpdated: number;
  edgesCreated: number;
  errors: number;
  errorMessages: string[];
}

/** Export/import a fact sheet's graph in interop formats (/api/graph/io) — graph-as-asset Phase 1/9. */
@Injectable({ providedIn: 'root' })
export class GraphIoService extends BaseService {
  private readonly apiPath = '/graph/io';

  constructor(private http: HttpClient) {
    super();
  }

  /** Download the graph in the given format; returns the full response (body blob + headers). */
  export(format: string, factSheetId?: number | null): Observable<HttpResponse<Blob>> {
    const params: { [k: string]: string | number } = { format };
    if (factSheetId != null) {
      params['factSheetId'] = factSheetId;
    }
    return this.http.get(`${this.backendUrl}${this.apiPath}/export`,
      { params, responseType: 'blob', observe: 'response' });
  }

  /** Import a graph file (optionally a second CSV edges file) into the given fact-sheet scope. */
  importGraph(format: string, file: File, factSheetId?: number | null, edgesFile?: File | null): Observable<ImportResult> {
    const form = new FormData();
    form.append('format', format);
    form.append('file', file);
    if (edgesFile) {
      form.append('edgesFile', edgesFile);
    }
    if (factSheetId != null) {
      form.append('factSheetId', String(factSheetId));
    }
    return this.http.post<ImportResult>(`${this.backendUrl}${this.apiPath}/import`, form);
  }
}
