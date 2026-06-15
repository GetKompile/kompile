export type MonitorType = 'TASK_COMPLETION' | 'SCHEDULED_ONCE' | 'SCHEDULED_CRON';
export type MonitorStatus = 'ACTIVE' | 'FIRED' | 'CANCELLED' | 'ERROR';

export interface MonitorRegistration {
  id?: number;
  monitorId: string;
  type: MonitorType;
  status: MonitorStatus;
  sessionId: string;
  taskId?: string;
  cronExpression?: string;
  fireAtEpochMs?: number;
  description?: string;
  payload?: string;
  createdAt: string;
  firedAt?: string;
  cancelledAt?: string;
  fireCount?: number;
}

export interface MonitorEvent {
  monitorId: string;
  type: string;
  sessionId: string;
  taskId?: string;
  title: string;
  message: string;
  payload?: string;
  success: boolean;
  firedAt: string;
}

export interface WatchTaskRequest {
  sessionId: string;
  taskId: string;
  description?: string;
  payload?: string;
}

export interface ScheduleOnceRequest {
  sessionId: string;
  fireAtEpochMs: number;
  description?: string;
  payload?: string;
}

export interface ScheduleCronRequest {
  sessionId: string;
  cronExpression: string;
  description?: string;
  payload?: string;
}
