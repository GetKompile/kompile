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
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import {
  SourceWeight,
  SetWeightRequest,
  WeightedSearchPreview,
  FeedbackRequest
} from '../models/graph-models';

@Injectable({
  providedIn: 'root'
})
export class SourceWeightService extends BaseService {

  private readonly apiPath = '/knowledge-graph';

  constructor(private http: HttpClient) {
    super();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // WEIGHT MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all weights for a source or all sources
   */
  getWeights(sourceId?: string): Observable<SourceWeight[]> {
    let params = new HttpParams();
    if (sourceId) {
      params = params.set('sourceId', sourceId);
    }
    return this.http.get<SourceWeight[]>(`${this.backendUrl}${this.apiPath}/weights`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get weight for a specific source
   */
  getWeight(sourceId: string, topic?: string): Observable<SourceWeight> {
    let params = new HttpParams();
    if (topic) {
      params = params.set('topic', topic);
    }
    return this.http.get<SourceWeight>(`${this.backendUrl}${this.apiPath}/weights/${sourceId}`, { params })
      .pipe(catchError(this.handleError));
  }

  /**
   * Set or update a source weight
   */
  setWeight(request: SetWeightRequest): Observable<SourceWeight> {
    return this.http.post<SourceWeight>(`${this.backendUrl}${this.apiPath}/weights`, request)
      .pipe(catchError(this.handleError));
  }

  /**
   * Remove a weight configuration
   */
  removeWeight(sourceId: string, topic?: string, userId?: string): Observable<void> {
    let params = new HttpParams();
    if (topic) {
      params = params.set('topic', topic);
    }
    if (userId) {
      params = params.set('userId', userId);
    }
    return this.http.delete<void>(`${this.backendUrl}${this.apiPath}/weights/${sourceId}`, { params })
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // PREVIEW & FEEDBACK
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Preview weighted search results for a query
   */
  previewWeightedSearch(query: string, maxResults: number = 10): Observable<WeightedSearchPreview> {
    return this.http.post<WeightedSearchPreview>(`${this.backendUrl}${this.apiPath}/weights/preview`, {
      query,
      maxResults
    }).pipe(catchError(this.handleError));
  }

  /**
   * Submit feedback for a source (updates quality score)
   */
  submitFeedback(request: FeedbackRequest): Observable<void> {
    return this.http.post<void>(`${this.backendUrl}${this.apiPath}/weights/feedback`, request)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TOPIC MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Get all defined topics
   */
  getTopics(): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}${this.apiPath}/topics`)
      .pipe(catchError(this.handleError));
  }

  /**
   * Assign a topic to a source
   */
  assignTopic(sourceId: string, topic: string): Observable<void> {
    return this.http.post<void>(`${this.backendUrl}${this.apiPath}/topics/${sourceId}`, { topic })
      .pipe(catchError(this.handleError));
  }

  /**
   * Get all sources for a topic
   */
  getSourcesForTopic(topic: string): Observable<string[]> {
    return this.http.get<string[]>(`${this.backendUrl}${this.apiPath}/topics/${topic}/sources`)
      .pipe(catchError(this.handleError));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPER METHODS
  // ═══════════════════════════════════════════════════════════════════════════

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
    console.error('SourceWeightService Error:', errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
