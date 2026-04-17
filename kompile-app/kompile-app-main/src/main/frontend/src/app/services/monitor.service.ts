import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  MonitorRegistration,
  WatchTaskRequest,
  ScheduleOnceRequest,
  ScheduleCronRequest
} from '../models/monitor-models';

@Injectable({ providedIn: 'root' })
export class MonitorService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  watchTask(req: WatchTaskRequest): Observable<MonitorRegistration> {
    return this.http.post<MonitorRegistration>(`${this.backendUrl}/monitor/watch-task`, req);
  }

  scheduleOnce(req: ScheduleOnceRequest): Observable<MonitorRegistration> {
    return this.http.post<MonitorRegistration>(`${this.backendUrl}/monitor/schedule-once`, req);
  }

  scheduleCron(req: ScheduleCronRequest): Observable<MonitorRegistration> {
    return this.http.post<MonitorRegistration>(`${this.backendUrl}/monitor/schedule-cron`, req);
  }

  list(sessionId?: string, all: boolean = false): Observable<MonitorRegistration[]> {
    let params = new HttpParams();
    if (sessionId) params = params.set('sessionId', sessionId);
    if (all) params = params.set('all', 'true');
    return this.http.get<MonitorRegistration[]>(`${this.backendUrl}/monitor`, { params });
  }

  get(monitorId: string): Observable<MonitorRegistration> {
    return this.http.get<MonitorRegistration>(`${this.backendUrl}/monitor/${monitorId}`);
  }

  cancel(monitorId: string): Observable<void> {
    return this.http.delete<void>(`${this.backendUrl}/monitor/${monitorId}`);
  }
}
