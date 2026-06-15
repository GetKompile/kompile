import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { backendUrl } from './base.service';

export interface GpuDevice {
  name: string;
  totalMemory: number;
  freeMemory: number;
  usedMemory: number;
  nvidiaSmiIndex: number;
  cudaRuntimeIndex: number;
}

export interface JobHold {
  jobId: string;
  serviceType: string;
  device: string;
  acquiredAt: string;
  heldForMs: number;
  description: string;
}

export interface BudgetInfo {
  budgetMb: number;
  priority: number;
  hasReservation: boolean;
  reservationCount: number;
}

@Injectable({
  providedIn: 'root'
})
export class GpuLifecycleService {
  private readonly apiUrl = `${backendUrl}/gpu-lifecycle`;

  constructor(private http: HttpClient) {}

  getStatus(): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/status`);
  }

  getDevices(): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/devices`);
  }

  getJobs(): Observable<any> {
    return this.http.get<any>(`${this.apiUrl}/jobs`);
  }

  getBudgets(): Observable<{ [serviceType: string]: BudgetInfo }> {
    return this.http.get<{ [serviceType: string]: BudgetInfo }>(`${this.apiUrl}/budgets`);
  }

  updateBudget(serviceType: string, budgetMb: number): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/budgets/${serviceType}?budgetMb=${budgetMb}`, {});
  }

  updatePriority(serviceType: string, priority: number): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/priorities/${serviceType}?priority=${priority}`, {});
  }

  forceReleaseJob(jobId: string): Observable<any> {
    return this.http.delete<any>(`${this.apiUrl}/jobs/${jobId}`);
  }
}
