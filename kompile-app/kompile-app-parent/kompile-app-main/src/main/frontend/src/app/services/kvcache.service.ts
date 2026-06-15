import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, BehaviorSubject, catchError, throwError } from 'rxjs';
import { BaseService } from './base.service';
import {
  KVCacheSummary, KVCacheStats, KVCacheConfig, KVCacheStatus,
  KVCacheProperties, CheckpointInfo, PrefixCacheStats, PrefixEntry, StatsSample
} from '../models/kvcache-models';

@Injectable({ providedIn: 'root' })
export class KVCacheService extends BaseService {

  private cachesSubject = new BehaviorSubject<KVCacheSummary[]>([]);
  public caches$ = this.cachesSubject.asObservable();

  constructor(private http: HttpClient) {
    super();
  }

  // Cache CRUD
  listCaches(): Observable<KVCacheSummary[]> {
    return this.http.get<KVCacheSummary[]>(`${this.backendUrl}/kvcache/caches`);
  }

  createCache(name: string, config: KVCacheConfig): Observable<KVCacheSummary> {
    return this.http.post<KVCacheSummary>(`${this.backendUrl}/kvcache/caches`, { name, config });
  }

  getCache(name: string): Observable<KVCacheSummary> {
    return this.http.get<KVCacheSummary>(`${this.backendUrl}/kvcache/caches/${name}`);
  }

  destroyCache(name: string): Observable<any> {
    return this.http.delete(`${this.backendUrl}/kvcache/caches/${name}`);
  }

  // Statistics
  getCacheStats(name: string): Observable<KVCacheStats> {
    return this.http.get<KVCacheStats>(`${this.backendUrl}/kvcache/caches/${name}/stats`);
  }

  getTimeSeries(name: string, windowSeconds: number = 300): Observable<StatsSample[]> {
    return this.http.get<StatsSample[]>(
      `${this.backendUrl}/kvcache/caches/${name}/stats/timeseries?windowSeconds=${windowSeconds}`
    );
  }

  getAggregateStats(): Observable<KVCacheStats> {
    return this.http.get<KVCacheStats>(`${this.backendUrl}/kvcache/stats/aggregate`);
  }

  // Config
  getConfig(): Observable<KVCacheProperties> {
    return this.http.get<KVCacheProperties>(`${this.backendUrl}/kvcache/config`);
  }

  updateConfig(config: Partial<KVCacheProperties>): Observable<KVCacheProperties> {
    return this.http.put<KVCacheProperties>(`${this.backendUrl}/kvcache/config`, config);
  }

  getStatus(): Observable<KVCacheStatus> {
    return this.http.get<KVCacheStatus>(`${this.backendUrl}/kvcache/status`);
  }

  enable(): Observable<any> {
    return this.http.post(`${this.backendUrl}/kvcache/enable`, {});
  }

  disable(): Observable<any> {
    return this.http.post(`${this.backendUrl}/kvcache/disable`, {});
  }

  // Checkpoints
  listCheckpoints(cacheName: string): Observable<CheckpointInfo[]> {
    return this.http.get<CheckpointInfo[]>(`${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints`);
  }

  createCheckpoint(cacheName: string, label?: string): Observable<CheckpointInfo> {
    return this.http.post<CheckpointInfo>(
      `${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints`,
      { label }
    );
  }

  restoreCheckpoint(cacheName: string, checkpointId: string): Observable<any> {
    return this.http.post(`${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints/${checkpointId}/restore`, {});
  }

  deleteCheckpoint(cacheName: string, checkpointId: string): Observable<any> {
    return this.http.delete(`${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints/${checkpointId}`);
  }

  saveCheckpointToDisk(cacheName: string, checkpointId: string): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints/${checkpointId}/save-to-disk`, {}
    );
  }

  loadCheckpointFromDisk(cacheName: string, path: string): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints/load-from-disk`,
      { path }
    );
  }

  rollbackCheckpoint(cacheName: string, checkpointId: string): Observable<any> {
    return this.http.post(
      `${this.backendUrl}/kvcache/caches/${cacheName}/checkpoints/${checkpointId}/rollback`, {}
    );
  }

  // Prefix cache
  getPrefixCacheStats(): Observable<PrefixCacheStats> {
    return this.http.get<PrefixCacheStats>(`${this.backendUrl}/kvcache/prefix-cache/stats`);
  }

  getPrefixCacheEntries(limit: number = 100): Observable<PrefixEntry[]> {
    return this.http.get<PrefixEntry[]>(`${this.backendUrl}/kvcache/prefix-cache/entries?limit=${limit}`);
  }

  savePrefixCache(): Observable<any> {
    return this.http.post(`${this.backendUrl}/kvcache/prefix-cache/save`, {});
  }

  loadPrefixCache(): Observable<any> {
    return this.http.post(`${this.backendUrl}/kvcache/prefix-cache/load`, {});
  }

  // Helper
  refreshCaches(): void {
    this.listCaches().subscribe({
      next: caches => this.cachesSubject.next(caches),
      error: err => console.error('Failed to refresh caches', err)
    });
  }

  formatBytes(bytes: number): string {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }
}
