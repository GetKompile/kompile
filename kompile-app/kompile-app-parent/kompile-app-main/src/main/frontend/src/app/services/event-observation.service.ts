/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { BaseService } from './base.service';
import {
  ConnectionPriorResponse,
  EventObservationConfig,
  NodePriorResponse,
  ObservationHistoryPoint,
  ObservedEventView,
  ScanResult
} from '../models/event-observation-models';

/**
 * Client for the event-observation REST API (`/api/events/observation`): observed events, empirical
 * priors, prior history, manual observe, rescan/decay, and the JSON config.
 */
@Injectable({ providedIn: 'root' })
export class EventObservationService extends BaseService {

  private readonly apiPath = '/events/observation';

  constructor(private http: HttpClient) {
    super();
  }

  listEvents(factSheetId?: number, type?: string, limit = 50): Observable<ObservedEventView[]> {
    let url = `${this.backendUrl}${this.apiPath}/events?limit=${limit}`;
    if (factSheetId != null) {
      url += `&factSheetId=${factSheetId}`;
    }
    if (type) {
      url += `&type=${encodeURIComponent(type)}`;
    }
    return this.http.get<ObservedEventView[]>(url);
  }

  getNodePrior(nodeId: string): Observable<NodePriorResponse> {
    return this.http.get<NodePriorResponse>(
      `${this.backendUrl}${this.apiPath}/priors/node/${encodeURIComponent(nodeId)}`);
  }

  getConnectionPrior(source: string, edgeType: string, target: string): Observable<ConnectionPriorResponse> {
    const p = new URLSearchParams({ source, edgeType, target });
    return this.http.get<ConnectionPriorResponse>(
      `${this.backendUrl}${this.apiPath}/priors/connection?${p.toString()}`);
  }

  getHistory(key: string): Observable<ObservationHistoryPoint[]> {
    return this.http.get<ObservationHistoryPoint[]>(
      `${this.backendUrl}${this.apiPath}/events/history?key=${encodeURIComponent(key)}`);
  }

  getConfig(): Observable<EventObservationConfig> {
    return this.http.get<EventObservationConfig>(`${this.backendUrl}${this.apiPath}/config`);
  }

  updateConfig(cfg: EventObservationConfig): Observable<EventObservationConfig> {
    return this.http.put<EventObservationConfig>(`${this.backendUrl}${this.apiPath}/config`, cfg);
  }

  rescan(factSheetId?: number): Observable<ScanResult> {
    let url = `${this.backendUrl}${this.apiPath}/rescan`;
    if (factSheetId != null) {
      url += `?factSheetId=${factSheetId}`;
    }
    return this.http.post<ScanResult>(url, {});
  }

  decay(): Observable<{ decayed: number }> {
    return this.http.post<{ decayed: number }>(`${this.backendUrl}${this.apiPath}/decay`, {});
  }

  observe(body: unknown): Observable<ObservedEventView> {
    return this.http.post<ObservedEventView>(`${this.backendUrl}${this.apiPath}/observe`, body);
  }
}
