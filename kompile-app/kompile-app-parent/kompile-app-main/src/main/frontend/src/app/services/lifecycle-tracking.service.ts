import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class LifecycleTrackingService {
  private baseUrl = `${environment.apiUrl}/lifecycle`;

  constructor(private http: HttpClient) {}

  getConfig(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/config`);
  }

  setTracking(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/tracking`, null, { params: { enabled } });
  }

  enable(): Observable<any> {
    return this.http.post(`${this.baseUrl}/enable`, {});
  }

  disable(): Observable<any> {
    return this.http.post(`${this.baseUrl}/disable`, {});
  }

  setTrackViews(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/track-views`, null, { params: { enabled } });
  }

  setTrackDeletions(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/track-deletions`, null, { params: { enabled } });
  }

  setSnapshotFiles(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/snapshot-files`, null, { params: { enabled } });
  }

  setTrackOperations(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/track-operations`, null, { params: { enabled } });
  }

  setStackDepth(depth: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/stack-depth`, null, { params: { depth } });
  }

  setReportInterval(seconds: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/report-interval`, null, { params: { seconds } });
  }

  setMaxDeletionHistory(size: number): Observable<any> {
    return this.http.post(`${this.baseUrl}/max-deletion-history`, null, { params: { size } });
  }

  applyPreset(preset: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/preset`, null, { params: { preset } });
  }

  printReport(): Observable<any> {
    return this.http.post(`${this.baseUrl}/print-lifecycle-report`, {});
  }

  getPeriodicReportingConfig(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/periodic-reporting-config`);
  }

  enablePeriodicReporting(intervalSeconds = 120, enableSnapshots = true): Observable<any> {
    return this.http.post(`${this.baseUrl}/enable-periodic-reporting`, null, {
      params: { intervalSeconds, enableSnapshots }
    });
  }

  disablePeriodicReporting(): Observable<any> {
    return this.http.post(`${this.baseUrl}/disable-periodic-reporting`, {});
  }

  getShapeCacheStats(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/cache/shape/stats`);
  }

  getTadCacheStats(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/cache/tad/stats`);
  }

  getAllCacheStats(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/cache/stats`);
  }

  browseShapeCache(maxDepth = 10, maxEntries = 100): Observable<any> {
    return this.http.get(`${this.baseUrl}/cache/shape/browse`, { params: { maxDepth, maxEntries } });
  }

  browseTadCache(maxDepth = 10, maxEntries = 100): Observable<any> {
    return this.http.get(`${this.baseUrl}/cache/tad/browse`, { params: { maxDepth, maxEntries } });
  }

  clearShapeCache(): Observable<any> {
    return this.http.post(`${this.baseUrl}/cache/shape/clear`, {});
  }

  clearTadCache(): Observable<any> {
    return this.http.post(`${this.baseUrl}/cache/tad/clear`, {});
  }

  clearAllCaches(): Observable<any> {
    return this.http.post(`${this.baseUrl}/cache/clear-all`, {});
  }

  getCleanupConfig(): Observable<Record<string, any>> {
    return this.http.get<Record<string, any>>(`${this.baseUrl}/cleanup/config`);
  }

  setCleanupEnabled(enabled: boolean): Observable<any> {
    return this.http.post(`${this.baseUrl}/cleanup/enabled`, null, { params: { enabled } });
  }

  updateCleanupConfig(maxAgeDays?: number, maxFiles?: number): Observable<any> {
    let params = new HttpParams();
    if (maxAgeDays != null) params = params.set('maxAgeDays', maxAgeDays);
    if (maxFiles != null) params = params.set('maxFiles', maxFiles);
    return this.http.post(`${this.baseUrl}/cleanup/config`, null, { params });
  }

  triggerCleanup(): Observable<any> {
    return this.http.post(`${this.baseUrl}/cleanup/trigger`, {});
  }
}
