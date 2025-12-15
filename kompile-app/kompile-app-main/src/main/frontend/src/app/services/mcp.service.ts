/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { McpToolInfo } from '../models/api-models';
import { BaseService } from './base.service';

export interface ToolInvocationRequest {
  toolName: string;
  arguments: { [key: string]: any };
}

export interface ToolInvocationResponse {
  toolName: string;
  result: any;
  error?: string;
}

export interface ActionLogEntry {
  id: number;
  toolName: string;
  toolCategory: string;
  arguments: { [key: string]: any };
  timestamp: string;
  actionType: string;
  undoable: boolean;
  undone: boolean;
  undoResult?: string;
  undoTimestamp?: string;
  hasResult: boolean;
  sessionId?: string;
  userId?: string;
}

export interface ActionLogStats {
  totalActions: number;
  maxEntries: number;
  retentionHours: number;
  byActionType: { [key: string]: number };
  undoableTotal: number;
  undoablePending: number;
  undone: number;
  topToolsByUsage: { [key: string]: number };
  registeredUndoHandlers: string[];
}

export interface UndoResult {
  status: string;
  actionId?: number;
  toolName?: string;
  undoResult?: any;
  message?: string;
  error?: string;
  entriesCleared?: number;
}

export interface UndoResponse {
  toolName: string;
  result: UndoResult;
}

@Injectable({
  providedIn: 'root'
})
export class McpService extends BaseService {

  constructor(private http: HttpClient) {
    super();
  }

  listTools(): Observable<McpToolInfo[]> {
    return this.http.get<McpToolInfo[]>(`${this.backendUrl}/mcp/tools/list`)
      .pipe(catchError(this.handleError));
  }

  invokeTool(request: ToolInvocationRequest): Observable<ToolInvocationResponse> {
    return this.http.post<ToolInvocationResponse>(`${this.backendUrl}/mcp/tools/invoke-direct`, request)
      .pipe(catchError(this.handleError));
  }

  // Action Log methods
  getActionLog(limit?: number, toolName?: string, actionType?: string, undoableOnly?: boolean): Observable<any> {
    const args: { [key: string]: any } = {};
    if (limit) args['limit'] = limit;
    if (toolName) args['toolName'] = toolName;
    if (actionType) args['actionType'] = actionType;
    if (undoableOnly) args['undoableOnly'] = undoableOnly;

    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'get_action_log',
      arguments: args
    }).pipe(catchError(this.handleError));
  }

  getAction(actionId: number): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'get_action',
      arguments: { actionId }
    }).pipe(catchError(this.handleError));
  }

  undoAction(actionId: number): Observable<UndoResponse> {
    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'undo_action',
      arguments: { actionId }
    }).pipe(catchError(this.handleError));
  }

  undoLastAction(): Observable<UndoResponse> {
    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'undo_last_action',
      arguments: {}
    }).pipe(catchError(this.handleError));
  }

  getActionStats(): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'get_action_stats',
      arguments: {}
    }).pipe(catchError(this.handleError));
  }

  clearActionLog(confirm: boolean): Observable<any> {
    return this.http.post<any>(`${this.backendUrl}/mcp/tools/invoke-direct`, {
      toolName: 'clear_action_log',
      arguments: { confirm }
    }).pipe(catchError(this.handleError));
  }

  private handleError(error: HttpErrorResponse) {
    let errorMessage = 'Unknown error!';
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      if (error.error && error.error.error) {
        errorMessage += `\nDetails: ${error.error.error}`;
      }
    }
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
