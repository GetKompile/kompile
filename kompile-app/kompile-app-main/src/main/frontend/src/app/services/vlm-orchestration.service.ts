import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { backendUrl } from './base.service';

export interface VlmOrchestrationConfig {
  releaseEncoderAfterEncoding: boolean;
  encoderDeviceId: number;
  decoderDeviceId: number;
  tritonCacheEnabled: boolean;
  tritonCacheDir: string;
  tritonAutoImport: boolean;
  tritonAutoExport: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class VlmOrchestrationService {
  private readonly apiUrl = `${backendUrl}/vlm-orchestration`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<VlmOrchestrationConfig> {
    return this.http.get<any>(this.apiUrl).pipe(
      map(response => response.config)
    );
  }

  updateConfig(config: Partial<VlmOrchestrationConfig>): Observable<VlmOrchestrationConfig> {
    return this.http.post<any>(this.apiUrl, config).pipe(
      map(response => response.config)
    );
  }

  resetConfig(): Observable<VlmOrchestrationConfig> {
    return this.http.post<any>(`${this.apiUrl}/reset`, {}).pipe(
      map(response => response.config)
    );
  }
}
