import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class ModelWarmupService {
  private baseUrl = `${environment.apiUrl}/model-warmup`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/config`);
  }

  updateConfig(config: Record<string, any>): Observable<any> {
    return this.http.put(`${this.baseUrl}/config`, config);
  }

  resetConfig(): Observable<any> {
    return this.http.post(`${this.baseUrl}/config/reset`, {});
  }

  getStatus(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/status`);
  }

  triggerAll(): Observable<any> {
    return this.http.post(`${this.baseUrl}/trigger`, {});
  }

  triggerService(serviceType: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/trigger/${serviceType}`, {});
  }
}
