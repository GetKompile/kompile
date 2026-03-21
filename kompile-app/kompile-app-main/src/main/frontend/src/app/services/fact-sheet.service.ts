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
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { BehaviorSubject, Observable, throwError } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  FactSheet,
  Fact,
  CreateFactSheetRequest,
  DeriveFactSheetRequest,
  UpdateFactSheetRequest,
  UpdateFactRequest,
  CopyFactsRequest,
  CopyFactsResponse,
  IndexingStats,
  FactForIndexing,
  MarkIndexedRequest,
  MarkIndexedResponse,
  EmbeddingModelInfo,
  UpdateEmbeddingModelRequest,
  EmbeddingModelUpdateResponse,
  CheckEmbeddingModelChangeRequest,
  EmbeddingModelChangeCheck,
  SetIndexedModelRequest
} from '../models/api-models';

/**
 * Service for managing fact sheets and facts.
 * Provides CRUD operations, sheet switching, and fact copying/moving.
 */
@Injectable({
  providedIn: 'root'
})
export class FactSheetService extends BaseService {

  /** Observable of all fact sheets */
  private sheetsSubject = new BehaviorSubject<FactSheet[]>([]);
  public sheets$ = this.sheetsSubject.asObservable();

  /** Observable of the currently active sheet */
  private activeSheetSubject = new BehaviorSubject<FactSheet | null>(null);
  public activeSheet$ = this.activeSheetSubject.asObservable();

  /** Observable of facts in the active sheet */
  private factsSubject = new BehaviorSubject<Fact[]>([]);
  public facts$ = this.factsSubject.asObservable();

  /** Selected facts for bulk operations */
  private selectedFactsSubject = new BehaviorSubject<Set<number>>(new Set());
  public selectedFacts$ = this.selectedFactsSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT SHEET OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load all fact sheets.
   */
  loadSheets(): Observable<FactSheet[]> {
    return this.http.get<FactSheet[]>(`${this.backendUrl}/fact-sheets`)
      .pipe(
        tap(sheets => this.sheetsSubject.next(sheets)),
        catchError(this.handleError)
      );
  }

  /**
   * Get the currently active fact sheet.
   */
  loadActiveSheet(): Observable<FactSheet> {
    return this.http.get<FactSheet>(`${this.backendUrl}/fact-sheets/active`)
      .pipe(
        tap(sheet => this.activeSheetSubject.next(sheet)),
        catchError(this.handleError)
      );
  }

  /**
   * Get a fact sheet by ID.
   */
  getSheet(id: number): Observable<FactSheet> {
    return this.http.get<FactSheet>(`${this.backendUrl}/fact-sheets/${id}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Create a new fact sheet.
   */
  createSheet(request: CreateFactSheetRequest): Observable<FactSheet> {
    return this.http.post<FactSheet>(`${this.backendUrl}/fact-sheets`, request)
      .pipe(
        tap(() => this.loadSheets().subscribe()),
        catchError(this.handleError)
      );
  }

  /**
   * Derive a new fact sheet from an existing one (copies all facts).
   */
  deriveSheet(sourceId: number, request: DeriveFactSheetRequest): Observable<FactSheet> {
    return this.http.post<FactSheet>(`${this.backendUrl}/fact-sheets/${sourceId}/derive`, request)
      .pipe(
        tap(() => this.loadSheets().subscribe()),
        catchError(this.handleError)
      );
  }

  /**
   * Update a fact sheet.
   */
  updateSheet(id: number, request: UpdateFactSheetRequest): Observable<FactSheet> {
    return this.http.put<FactSheet>(`${this.backendUrl}/fact-sheets/${id}`, request)
      .pipe(
        tap(() => this.loadSheets().subscribe()),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a fact sheet.
   */
  deleteSheet(id: number): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/fact-sheets/${id}`)
      .pipe(
        tap(() => {
          this.loadSheets().subscribe();
          this.loadActiveSheet().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Activate a fact sheet (switch to it).
   */
  activateSheet(id: number): Observable<FactSheet> {
    return this.http.post<FactSheet>(`${this.backendUrl}/fact-sheets/${id}/activate`, {})
      .pipe(
        tap(sheet => {
          this.activeSheetSubject.next(sheet);
          this.loadSheets().subscribe();
          this.loadActiveFacts().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Get the current active sheet synchronously.
   */
  getActiveSheet(): FactSheet | null {
    return this.activeSheetSubject.value;
  }

  /**
   * Get all sheets synchronously.
   */
  getSheets(): FactSheet[] {
    return this.sheetsSubject.value;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // FACT OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Load facts for the active sheet.
   */
  loadActiveFacts(): Observable<Fact[]> {
    return this.http.get<Fact[]>(`${this.backendUrl}/fact-sheets/active/facts`)
      .pipe(
        tap(facts => this.factsSubject.next(facts)),
        catchError(this.handleError)
      );
  }

  /**
   * Load facts for a specific sheet.
   */
  loadSheetFacts(sheetId: number): Observable<Fact[]> {
    return this.http.get<Fact[]>(`${this.backendUrl}/fact-sheets/${sheetId}/facts`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get a specific fact.
   */
  getFact(factId: number): Observable<Fact> {
    return this.http.get<Fact>(`${this.backendUrl}/fact-sheets/facts/${factId}`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update a fact (title, notes, tags).
   */
  updateFact(factId: number, request: UpdateFactRequest): Observable<Fact> {
    return this.http.put<Fact>(`${this.backendUrl}/fact-sheets/facts/${factId}`, request)
      .pipe(
        tap(() => this.loadActiveFacts().subscribe()),
        catchError(this.handleError)
      );
  }

  /**
   * Delete a fact.
   */
  deleteFact(factId: number): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/fact-sheets/facts/${factId}`)
      .pipe(
        tap(() => {
          this.loadActiveFacts().subscribe();
          this.loadSheets().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Delete multiple facts.
   */
  deleteFacts(factIds: number[]): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/fact-sheets/facts`, {
      body: { factIds }
    })
      .pipe(
        tap(() => {
          this.loadActiveFacts().subscribe();
          this.loadSheets().subscribe();
          this.clearSelection();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Copy facts from one sheet to another.
   */
  copyFacts(sourceId: number, targetId: number, factIds: number[]): Observable<CopyFactsResponse> {
    const request: CopyFactsRequest = { factIds };
    return this.http.post<CopyFactsResponse>(
      `${this.backendUrl}/fact-sheets/${sourceId}/copy-to/${targetId}`,
      request
    )
      .pipe(
        tap(() => this.loadSheets().subscribe()),
        catchError(this.handleError)
      );
  }

  /**
   * Move facts from one sheet to another.
   */
  moveFacts(sourceId: number, targetId: number, factIds: number[]): Observable<CopyFactsResponse> {
    const request: CopyFactsRequest = { factIds };
    return this.http.post<CopyFactsResponse>(
      `${this.backendUrl}/fact-sheets/${sourceId}/move-to/${targetId}`,
      request
    )
      .pipe(
        tap(() => {
          this.loadSheets().subscribe();
          this.loadActiveFacts().subscribe();
          this.clearSelection();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Search facts in the active sheet.
   */
  searchFacts(query: string): Observable<Fact[]> {
    return this.http.get<Fact[]>(`${this.backendUrl}/fact-sheets/active/facts/search`, {
      params: { q: query }
    })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get facts synchronously.
   */
  getFacts(): Fact[] {
    return this.factsSubject.value;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // SELECTION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Select a fact.
   */
  selectFact(factId: number): void {
    const selected = new Set(this.selectedFactsSubject.value);
    selected.add(factId);
    this.selectedFactsSubject.next(selected);
  }

  /**
   * Deselect a fact.
   */
  deselectFact(factId: number): void {
    const selected = new Set(this.selectedFactsSubject.value);
    selected.delete(factId);
    this.selectedFactsSubject.next(selected);
  }

  /**
   * Toggle fact selection.
   */
  toggleFactSelection(factId: number): void {
    const selected = new Set(this.selectedFactsSubject.value);
    if (selected.has(factId)) {
      selected.delete(factId);
    } else {
      selected.add(factId);
    }
    this.selectedFactsSubject.next(selected);
  }

  /**
   * Select all facts.
   */
  selectAllFacts(): void {
    const facts = this.factsSubject.value;
    const selected = new Set(facts.map(f => f.id));
    this.selectedFactsSubject.next(selected);
  }

  /**
   * Clear all selections.
   */
  clearSelection(): void {
    this.selectedFactsSubject.next(new Set());
  }

  /**
   * Check if a fact is selected.
   */
  isFactSelected(factId: number): boolean {
    return this.selectedFactsSubject.value.has(factId);
  }

  /**
   * Get selected fact IDs.
   */
  getSelectedFactIds(): number[] {
    return Array.from(this.selectedFactsSubject.value);
  }

  /**
   * Get count of selected facts.
   */
  getSelectedCount(): number {
    return this.selectedFactsSubject.value.size;
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // INDEXING STATUS OPERATIONS
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get indexing statistics for the active sheet.
   */
  getActiveSheetIndexingStats(): Observable<IndexingStats> {
    return this.http.get<IndexingStats>(`${this.backendUrl}/fact-sheets/active/indexing-stats`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get indexing statistics for a specific sheet.
   */
  getSheetIndexingStats(sheetId: number): Observable<IndexingStats> {
    return this.http.get<IndexingStats>(`${this.backendUrl}/fact-sheets/${sheetId}/indexing-stats`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get unindexed facts in the active sheet.
   */
  getUnindexedActiveFacts(): Observable<Fact[]> {
    return this.http.get<Fact[]>(`${this.backendUrl}/fact-sheets/active/facts/unindexed`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get unindexed facts in a specific sheet.
   */
  getUnindexedFacts(sheetId: number): Observable<Fact[]> {
    return this.http.get<Fact[]>(`${this.backendUrl}/fact-sheets/${sheetId}/facts/unindexed`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get facts pending indexing with file path information for the active sheet.
   */
  getFactsPendingIndexing(): Observable<FactForIndexing[]> {
    return this.http.get<FactForIndexing[]>(`${this.backendUrl}/fact-sheets/active/facts/pending-indexing`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Get facts pending indexing for a specific sheet.
   */
  getSheetFactsPendingIndexing(sheetId: number): Observable<FactForIndexing[]> {
    return this.http.get<FactForIndexing[]>(`${this.backendUrl}/fact-sheets/${sheetId}/facts/pending-indexing`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Mark specific facts as indexed (call after successful indexing).
   */
  markFactsAsIndexed(factIds: number[]): Observable<MarkIndexedResponse> {
    const request: MarkIndexedRequest = { factIds };
    return this.http.post<MarkIndexedResponse>(`${this.backendUrl}/fact-sheets/facts/mark-indexed`, request)
      .pipe(
        tap(() => {
          this.loadActiveFacts().subscribe();
          this.loadSheets().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  /**
   * Mark a single fact as indexed.
   */
  markFactAsIndexed(factId: number): Observable<void> {
    return this.http.post<void>(`${this.backendUrl}/fact-sheets/facts/${factId}/mark-indexed`, {})
      .pipe(
        tap(() => {
          this.loadActiveFacts().subscribe();
          this.loadSheets().subscribe();
        }),
        catchError(this.handleError)
      );
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // EMBEDDING MODEL MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════════

  /**
   * Get detailed embedding model info for a fact sheet.
   * Includes model mismatch detection and reindex status.
   */
  getEmbeddingModelInfo(sheetId: number): Observable<EmbeddingModelInfo> {
    return this.http.get<EmbeddingModelInfo>(`${this.backendUrl}/fact-sheets/${sheetId}/embedding-model-info`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Update the embedding model for a fact sheet.
   * WARNING: If the sheet has indexed documents and the model is changing,
   * this will mark all facts as unindexed (requiring a full reindex).
   */
  updateEmbeddingModel(sheetId: number, request: UpdateEmbeddingModelRequest): Observable<EmbeddingModelUpdateResponse> {
    return this.http.put<EmbeddingModelUpdateResponse>(
      `${this.backendUrl}/fact-sheets/${sheetId}/embedding-model`,
      request
    ).pipe(
      tap(() => {
        this.loadSheets().subscribe();
        if (this.activeSheetSubject.value?.id === sheetId) {
          this.loadActiveSheet().subscribe();
        }
      }),
      catchError(this.handleError)
    );
  }

  /**
   * Check if switching to a new embedding model would require reindexing.
   * Use this before updating to warn the user about consequences.
   */
  checkEmbeddingModelChange(sheetId: number, newModelId: string): Observable<EmbeddingModelChangeCheck> {
    const request: CheckEmbeddingModelChangeRequest = { newModelId };
    return this.http.post<EmbeddingModelChangeCheck>(
      `${this.backendUrl}/fact-sheets/${sheetId}/embedding-model/check-change`,
      request
    ).pipe(catchError(this.handleError));
  }

  /**
   * Record that indexing completed with a specific model.
   * Called after vector population completes successfully.
   */
  setIndexedWithModel(sheetId: number, modelId: string): Observable<any> {
    const request: SetIndexedModelRequest = { modelId };
    return this.http.post<any>(
      `${this.backendUrl}/fact-sheets/${sheetId}/set-indexed-model`,
      request
    ).pipe(
      tap(() => {
        this.loadSheets().subscribe();
        if (this.activeSheetSubject.value?.id === sheetId) {
          this.loadActiveSheet().subscribe();
        }
      }),
      catchError(this.handleError)
    );
  }

  /**
   * Check if a fact sheet needs reindexing due to model mismatch.
   */
  checkNeedsReindex(sheetId: number): Observable<{ needsReindex: boolean; configuredModel: string; indexedWithModel: string }> {
    return this.http.get<any>(`${this.backendUrl}/fact-sheets/${sheetId}/needs-reindex`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // ERROR HANDLING
  // ═══════════════════════════════════════════════════════════════════════════════

  private handleError(error: HttpErrorResponse): Observable<never> {
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
    console.error('FactSheetService error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
