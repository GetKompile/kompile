import { Injectable } from '@angular/core';
import { HttpClient, HttpParams, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { BaseService } from './base.service';
import { IndexedDocInfo, UpdateDocRequest, SimpleMessageResponse } from '../models/api-models';

@Injectable({
  providedIn: 'root'
})
export class IndexBrowserService extends BaseService {

  constructor(private http: HttpClient) {
    super();
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
