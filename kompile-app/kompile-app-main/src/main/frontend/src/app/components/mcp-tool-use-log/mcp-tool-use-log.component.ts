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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { Subject, interval } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { BaseService } from '../../services/base.service';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';

interface ActionLogEntry {
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
}

interface ActionLogStats {
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

@Component({
  standalone: false,
  selector: 'app-mcp-tool-use-log',
  templateUrl: './mcp-tool-use-log.component.html',
  styleUrls: ['./mcp-tool-use-log.component.css']
})
export class McpToolUseLogComponent extends BaseService implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  actions: ActionLogEntry[] = [];
  stats: ActionLogStats | null = null;
  isLoading = false;
  autoRefresh = true;

  // Filters
  limitFilter = 50;
  actionTypeFilter = '';
  toolNameFilter = '';
  undoableOnly = false;

  actionTypes = ['', 'READ', 'WRITE', 'DELETE', 'EXECUTE', 'CONFIG'];

  // Expanded action detail
  expandedActionId: number | null = null;
  expandedDetail: any = null;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar,
    private dialog: MatDialog
  ) {
    super();
  }

  ngOnInit(): void {
    this.loadActions();
    this.loadStats();
    interval(5000)
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => {
        if (this.autoRefresh) {
          this.loadActions();
          this.loadStats();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadActions(): void {
    let url = `${this.backendUrl}/mcp/action-log?limit=${this.limitFilter}&undoableOnly=${this.undoableOnly}`;
    if (this.actionTypeFilter) url += `&actionType=${this.actionTypeFilter}`;
    if (this.toolNameFilter) url += `&toolName=${this.toolNameFilter}`;

    this.http.get<any>(url).subscribe({
      next: (response) => {
        this.actions = response.actions || [];
        this.isLoading = false;
      },
      error: () => {
        this.isLoading = false;
      }
    });
  }

  loadStats(): void {
    this.http.get<ActionLogStats>(`${this.backendUrl}/mcp/action-log/stats`).subscribe({
      next: (stats) => {
        this.stats = stats;
      }
    });
  }

  toggleExpand(action: ActionLogEntry): void {
    if (this.expandedActionId === action.id) {
      this.expandedActionId = null;
      this.expandedDetail = null;
      return;
    }
    this.expandedActionId = action.id;
    this.http.get<any>(`${this.backendUrl}/mcp/action-log/${action.id}`).subscribe({
      next: (detail) => {
        this.expandedDetail = detail;
      }
    });
  }

  undoAction(actionId: number): void {
    this.http.post<any>(`${this.backendUrl}/mcp/action-log/${actionId}/undo`, {}).subscribe({
      next: (result) => {
        if (result.status === 'success') {
          this.snackBar.open('Action undone successfully', 'Close', { duration: 3000 });
          this.loadActions();
          this.loadStats();
        } else {
          this.snackBar.open(result.error || 'Undo failed', 'Close', { duration: 5000 });
        }
      },
      error: (err) => {
        this.snackBar.open('Undo failed: ' + err.message, 'Close', { duration: 5000 });
      }
    });
  }

  undoLast(): void {
    this.http.post<any>(`${this.backendUrl}/mcp/action-log/undo-last`, {}).subscribe({
      next: (result) => {
        if (result.status === 'success') {
          this.snackBar.open(`Undid: ${result.toolName}`, 'Close', { duration: 3000 });
          this.loadActions();
          this.loadStats();
        } else {
          this.snackBar.open(result.error || 'No undoable actions', 'Close', { duration: 5000 });
        }
      }
    });
  }

  clearLog(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Clear Action Log',
      message: 'Clear all action log entries? This cannot be undone.',
      confirmText: 'Clear All',
      confirmColor: 'warn',
      icon: 'delete_forever'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(filter(confirmed => confirmed === true))
      .subscribe(() => {
        this.http.delete<any>(`${this.backendUrl}/mcp/action-log`).subscribe({
          next: (result) => {
            this.snackBar.open(`Cleared ${result.entriesCleared} entries`, 'Close', { duration: 3000 });
            this.actions = [];
            this.loadStats();
          }
        });
      });
  }

  getActionTypeIcon(type: string): string {
    switch (type) {
      case 'READ': return 'visibility';
      case 'WRITE': return 'edit';
      case 'DELETE': return 'delete';
      case 'EXECUTE': return 'play_arrow';
      case 'CONFIG': return 'settings';
      default: return 'help_outline';
    }
  }

  getActionTypeColor(type: string): string {
    switch (type) {
      case 'READ': return '#16a34a';
      case 'WRITE': return '#2563eb';
      case 'DELETE': return '#dc2626';
      case 'EXECUTE': return '#ea580c';
      case 'CONFIG': return '#7c3aed';
      default: return '#6b7280';
    }
  }

  formatTime(timestamp: string): string {
    const d = new Date(timestamp);
    return d.toLocaleTimeString();
  }

  formatDate(timestamp: string): string {
    const d = new Date(timestamp);
    return d.toLocaleDateString();
  }

  formatJson(obj: any): string {
    try {
      return JSON.stringify(obj, null, 2);
    } catch {
      return String(obj);
    }
  }

  getTopTools(): { name: string; count: number }[] {
    if (!this.stats?.topToolsByUsage) return [];
    return Object.entries(this.stats.topToolsByUsage)
      .map(([name, count]) => ({ name, count: count as number }))
      .sort((a, b) => b.count - a.count);
  }

  copyToClipboard(text: string): void {
    navigator.clipboard.writeText(text).then(() => {
      this.snackBar.open('Copied to clipboard', 'Close', { duration: 2000 });
    });
  }
}
