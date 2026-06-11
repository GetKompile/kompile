import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface AutoConfigureResult {
  hardware: Record<string, any>;
  recommended?: Record<string, any>;
  applied?: Record<string, any>;
  note?: string;
  message?: string;
}

@Injectable({ providedIn: 'root' })
export class AutoConfigureService {
  private baseUrl = `${environment.apiUrl}/auto-configure`;

  constructor(private http: HttpClient) {}

  detect(hasLocalEmbedding = true): Observable<AutoConfigureResult> {
    return this.http.get<AutoConfigureResult>(`${this.baseUrl}/detect`, {
      params: { hasLocalEmbedding }
    });
  }

  apply(hasLocalEmbedding = true): Observable<AutoConfigureResult> {
    return this.http.post<AutoConfigureResult>(`${this.baseUrl}/apply`, { hasLocalEmbedding });
  }
}
