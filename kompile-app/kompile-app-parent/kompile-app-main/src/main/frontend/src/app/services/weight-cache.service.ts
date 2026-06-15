import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class WeightCacheService {
  private baseUrl = `${environment.apiUrl}/weight-cache`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/config`);
  }

  updateConfig(config: Record<string, any>): Observable<any> {
    return this.http.put(`${this.baseUrl}/config`, config);
  }

  getStatus(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/status`);
  }

  demote(modelId: string, layerName?: string): Observable<any> {
    const params: any = {};
    if (layerName) params.layerName = layerName;
    return this.http.post(`${this.baseUrl}/demote/${modelId}`, null, { params });
  }

  promote(modelId: string, layerName?: string): Observable<any> {
    const params: any = {};
    if (layerName) params.layerName = layerName;
    return this.http.post(`${this.baseUrl}/promote/${modelId}`, null, { params });
  }
}
