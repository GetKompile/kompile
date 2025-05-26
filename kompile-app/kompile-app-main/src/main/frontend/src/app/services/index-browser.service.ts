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
import { HttpClient, HttpParams, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import { IndexedDocInfo, UpdateDocRequest, SimpleMessageResponse, SearchRequest, SearchResponse, IndexBrowserStatus } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class IndexBrowserService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  getIndexBrowserStatus(): Observable<IndexBrowserStatus> {
    return this.http.get<IndexBrowserStatus>(`${this.backendUrl}/index-browser/status`)
      .pipe(catchError(this.handleError));
  }

  getAllIndexedDocs(offset: number = 0, limit: number = 10): Observable<IndexedDocInfo[]> {
    let params = new HttpParams()
      .set('offset', offset.toString())
      .set('limit', limit.toString());
    return this.http.get<IndexedDocInfo[]>(`${this.backendUrl}/index-browser/documents`, { params })
      .pipe(catchError(this.handleError));
  }

  getIndexedDoc(docId: string): Observable<IndexedDocInfo> {
    return this.http.get<IndexedDocInfo>(`${this.backendUrl}/index-browser/documents/${encodeURIComponent(docId)}`)
      .pipe(catchError(this.handleError));
  }

  updateIndexedDoc(docId: string, content: string): Observable<SimpleMessageResponse> {
    const request: UpdateDocRequest = { content };
    return this.http.put<SimpleMessageResponse>(`${this.backendUrl}/index-browser/documents/${encodeURIComponent(docId)}`, request)
      .pipe(catchError(this.handleError));
  }

  searchIndexedDocs(query: string, maxResults: number = 10): Observable<SearchResponse> {
    const request: SearchRequest = { query, maxResults };
    return this.http.post<SearchResponse>(`${this.backendUrl}/index-browser/search`, request)
      .pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.error?.message || error.error?.error || error.message || 'Server error'}`;
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
