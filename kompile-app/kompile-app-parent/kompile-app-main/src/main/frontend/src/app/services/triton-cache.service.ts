import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface TritonCacheStats {
  cacheDir: string;
  enabled: boolean;
  autoImport: boolean;
  autoExport: boolean;
  bundleCount: number;
  totalSizeBytes: number;
  totalSizeMb: number;
  exists: boolean;
}

export interface TritonCacheBundle {
  filename: string;
  modelId: string;
  sizeBytes: number;
  sizeMb: number;
  lastModified: string;
}

@Injectable({
  providedIn: 'root'
})
export class TritonCacheService {
  private readonly apiUrl = `${backendUrl}/triton-cache`;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<TritonCacheStats> {
    return this.http.get<TritonCacheStats>(`${this.apiUrl}/status`);
  }

  listBundles(): Observable<TritonCacheBundle[]> {
    return this.http.get<TritonCacheBundle[]>(`${this.apiUrl}/bundles`);
  }

  exportCache(modelId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/export?modelId=${encodeURIComponent(modelId)}`, {});
  }

  importCache(modelId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/import?modelId=${encodeURIComponent(modelId)}`, {});
  }

  invalidateAll(): Observable<any> {
    return this.http.delete<any>(this.apiUrl);
  }
}
