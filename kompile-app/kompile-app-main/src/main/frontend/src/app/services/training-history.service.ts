import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export type TrainingJobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'MEMORY_KILLED';
export type TrainingType = 'FINETUNE' | 'LORA' | 'DISTILLATION' | 'ALIGNMENT';
export type TrainingFailureReason = 'NONE' | 'OUT_OF_MEMORY' | 'MEMORY_KILLED' | 'USER_CANCELLED' |
  'MODEL_NOT_FOUND' | 'DATASET_ERROR' | 'TRAINING_ERROR' | 'CHECKPOINT_ERROR' | 'IO_ERROR' | 'TIMEOUT' | 'UNKNOWN';

export interface TrainingJobHistory {
  id: number;
  taskId: string;
  trainingType: TrainingType;
  modelId: string;
  datasetId?: string;
  status: TrainingJobStatus;
  startTime: string;
  endTime?: string;
  totalDurationMs?: number;
  currentEpoch?: number;
  totalEpochs?: number;
  currentStep?: number;
  totalSteps?: number;
  finalLoss?: number;
  finalEvalLoss?: number;
  learningRate?: number;
  batchSize?: number;
  gradientAccumulationSteps?: number;
  lrSchedule?: string;
  warmupRatio?: number;
  maxGradNorm?: number;
  fp16?: boolean;
  bf16?: boolean;
  peftType?: string;
  seed?: number;
  outputModelPath?: string;
  javaVersion?: string;
  osInfo?: string;
  availableProcessors?: number;
  maxHeapMemoryBytes?: number;
  nd4jBackend?: string;
  errorMessage?: string;
  errorType?: string;
  stackTrace?: string;
  failureReason?: TrainingFailureReason;
  restartAttempts?: number;
  maxRestartAttempts?: number;
  lastRestartTime?: string;
  recoveredAfterRestart?: boolean;
  restartHistoryJson?: string;
  additionalDetails?: string;
}

export interface TrainingJobRealtimeStatus {
  jobId: string;
  status: string;
  modelId: string;
  datasetId: string;
  currentEpoch: number;
  totalEpochs: number;
  currentStep: number;
  totalSteps: number;
  loss: number;
  learningRate: number;
  epochProgress: number;
  overallProgress: number;
  metrics: Record<string, number>;
  startedAt: string;
  completedAt: string;
  elapsedMs: number;
  error: string;
  outputModelPath: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

@Injectable({ providedIn: 'root' })
export class TrainingHistoryService {
  private baseUrl = `${environment.apiUrl}/training/history`;

  constructor(private http: HttpClient) {}

  getHistory(page = 0, size = 20): Observable<PageResponse<TrainingJobHistory>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<PageResponse<TrainingJobHistory>>(this.baseUrl, { params });
  }

  getJob(taskId: string): Observable<TrainingJobHistory> {
    return this.http.get<TrainingJobHistory>(`${this.baseUrl}/${taskId}`);
  }

  getByStatus(status: string): Observable<TrainingJobHistory[]> {
    return this.http.get<TrainingJobHistory[]>(`${this.baseUrl}/status/${status}`);
  }

  getByType(type: string): Observable<TrainingJobHistory[]> {
    return this.http.get<TrainingJobHistory[]>(`${this.baseUrl}/type/${type}`);
  }

  getByModel(modelId: string): Observable<TrainingJobHistory[]> {
    return this.http.get<TrainingJobHistory[]>(`${this.baseUrl}/model/${modelId}`);
  }

  getRecent(hours = 24): Observable<TrainingJobHistory[]> {
    const params = new HttpParams().set('hours', hours);
    return this.http.get<TrainingJobHistory[]>(`${this.baseUrl}/recent`, { params });
  }

  getActive(): Observable<TrainingJobHistory[]> {
    return this.http.get<TrainingJobHistory[]>(`${this.baseUrl}/active`);
  }

  getStatistics(hours = 24): Observable<Record<string, any>> {
    const params = new HttpParams().set('hours', hours);
    return this.http.get<Record<string, any>>(`${this.baseUrl}/statistics`, { params });
  }

  deleteJob(taskId: string): Observable<any> {
    return this.http.delete(`${this.baseUrl}/${taskId}`);
  }

  cleanup(days = 30): Observable<any> {
    const params = new HttpParams().set('days', days);
    return this.http.post(`${this.baseUrl}/cleanup`, null, { params });
  }

  getStatusClass(status: TrainingJobStatus): string {
    switch (status) {
      case 'COMPLETED': return 'status-completed';
      case 'RUNNING': return 'status-running';
      case 'QUEUED': return 'status-queued';
      case 'FAILED': return 'status-failed';
      case 'CANCELLED': return 'status-cancelled';
      case 'MEMORY_KILLED': return 'status-memory-killed';
      default: return '';
    }
  }

  getFailureIcon(reason: TrainingFailureReason): string {
    switch (reason) {
      case 'OUT_OF_MEMORY': case 'MEMORY_KILLED': return 'memory';
      case 'TIMEOUT': return 'timer_off';
      case 'USER_CANCELLED': return 'cancel';
      case 'DATASET_ERROR': return 'table_chart';
      case 'IO_ERROR': return 'folder_off';
      default: return 'error';
    }
  }
}
