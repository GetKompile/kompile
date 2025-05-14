/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import { AnseriniSearchResponse, SimpleMessageResponse } from '../models/api-models';
import { BaseService } from './base.service';

@Injectable({
  providedIn: 'root'
})
export class AnseriniService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  searchAnserini(query: string, maxResults: number): Observable<AnseriniSearchResponse> {
    let params = new HttpParams()
      .set('query', query)
      .set('maxResults', maxResults.toString());
    return this.http.get<AnseriniSearchResponse>(`${this.backendUrl}/anserini/search`, { params })
      .pipe(catchError(this.handleError));
  }

  rebuildIndex(): Observable<SimpleMessageResponse> {
    return this.http.post<SimpleMessageResponse>(`${this.backendUrl}/anserini/index/rebuild`, {})
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    // ... (same as in RagService or a shared error handler)
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      if (error.error && error.error.error) {
        errorMessage += `\nDetails: ${error.error.error}`;
      }
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
