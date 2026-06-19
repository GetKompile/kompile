/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface TableSummary {
  id: string;
  headers: string[];
  rowCount: number;
  columnCount: number;
  sourceFile: string | null;
  pageNumber: number | null;
  preview: string;
  fullContent: string | null;
}

export interface TableListResponse {
  tables: TableSummary[];
  total: number;
  offset: number;
  limit: number;
}

export interface TableDetail {
  id: string;
  headers: string[];
  rowCount: number;
  columnCount: number;
  sourceFile: string | null;
  pageNumber: number | null;
  fullContent: string;
  metadata: Record<string, any>;
}

export interface ColumnStats {
  type: 'numeric' | 'text';
  min?: number;
  max?: number;
  sum?: number;
  mean?: number;
  numericCount?: number;
  uniqueValues?: number;
  mostFrequent?: Array<{ value: string; count: number }>;
  nonEmpty: number;
  totalValues: number;
}

export interface TableAnalysis {
  rowCount: number;
  columnCount: number;
  headers: string[];
  columnStats: Record<string, ColumnStats>;
}

export interface SingleColumnAnalysis {
  column: string;
  columnStats: ColumnStats;
}

export interface TableSearchResult {
  id: string;
  headers: string[];
  rowCount: number;
  columnCount: number;
  sourceFile: string | null;
  score: number;
  preview: string;
  fullContent: string | null;
}

export interface TableSearchResponse {
  results: TableSearchResult[];
  total: number;
}

/**
 * Service for table browsing and analysis operations.
 * Provides methods to list, retrieve, search, analyze, filter,
 * sort, and export tables extracted from indexed documents.
 */
@Injectable({
  providedIn: 'root'
})
export class TableSearchService {
  private readonly baseUrl = `${environment.apiUrl}/tables`;

  constructor(private http: HttpClient) {}

  // ═══════════════════════════════════════════════════════════════════════════
  // LISTING & RETRIEVAL
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get a paginated list of all available tables.
   */
  listTables(offset: number = 0, limit: number = 20): Observable<TableListResponse> {
    const params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());

    return this.http.get<TableListResponse>(`${this.baseUrl}`, { params });
  }

  /**
   * Get full detail for a single table by ID.
   */
  getTable(tableId: string): Observable<TableDetail> {
    return this.http.get<TableDetail>(`${this.baseUrl}/${tableId}`);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SEARCH
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Search tables by a natural-language or keyword query.
   */
  searchTables(query: string, maxResults: number = 10): Observable<TableSearchResponse> {
    return this.http.post<TableSearchResponse>(`${this.baseUrl}/search`, {
      query,
      maxResults
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ANALYSIS
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Analyze a table, optionally scoping the analysis to a single column.
   * Returns full TableAnalysis when no column is specified, or
   * SingleColumnAnalysis when a column name is provided.
   */
  analyzeTable(tableId: string, column?: string): Observable<TableAnalysis | SingleColumnAnalysis> {
    const body: Record<string, any> = {};
    if (column !== undefined) {
      body['column'] = column;
    }
    return this.http.post<TableAnalysis | SingleColumnAnalysis>(
      `${this.baseUrl}/${tableId}/analyze`,
      body
    );
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FILTER & SORT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Filter a table by applying a comparison on a column.
   * Returns the filtered table as a markdown string.
   */
  filterTable(
    tableId: string,
    column: string,
    operator: string,
    value: string
  ): Observable<{ content: string }> {
    return this.http.post<{ content: string }>(`${this.baseUrl}/${tableId}/filter`, {
      column,
      operator,
      value
    });
  }

  /**
   * Sort a table by a column, ascending or descending.
   * Returns the sorted table as a markdown string.
   */
  sortTable(
    tableId: string,
    column: string,
    descending: boolean = false
  ): Observable<{ content: string }> {
    return this.http.post<{ content: string }>(`${this.baseUrl}/${tableId}/sort`, {
      column,
      descending
    });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EXPORT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Export a table in the specified format (e.g. 'csv', 'json', 'markdown').
   * Returns the serialized content and the resolved format name.
   */
  exportTable(tableId: string, format: string): Observable<{ content: string; format: string }> {
    return this.http.post<{ content: string; format: string }>(
      `${this.baseUrl}/${tableId}/export`,
      { format }
    );
  }
}
