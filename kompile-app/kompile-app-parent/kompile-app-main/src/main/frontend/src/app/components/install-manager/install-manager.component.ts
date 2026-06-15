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
import { CommonModule } from '@angular/common';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatListModule } from '@angular/material/list';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDividerModule } from '@angular/material/divider';
import { backendUrl } from '../../services/base.service';
import { interval, Subscription } from 'rxjs';

export interface ToolInfo {
  id: string;
  name: string;
  description: string;
  installed: boolean;
  path: string;
  version?: string;
}

@Component({
  selector: 'app-install-manager',
  standalone: true,
  templateUrl: './install-manager.component.html',
  styleUrls: ['./install-manager.component.css'],
  imports: [
    CommonModule,
    HttpClientModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatListModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDividerModule
  ]
})
export class InstallManagerComponent implements OnInit, OnDestroy {
  tools: ToolInfo[] = [];
  loading = false;
  error: string | null = null;
  lastRefreshed: Date | null = null;
  /** Tracks in-flight action per toolId to disable buttons during requests. */
  actionInProgress: Record<string, boolean> = {};

  private refreshSub?: Subscription;

  constructor(
    private http: HttpClient,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadTools();
    this.refreshSub = interval(30000).subscribe(() => this.loadTools());
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadTools(): void {
    this.loading = true;
    this.error = null;
    this.http.get<ToolInfo[]>(`${backendUrl}/install/tools`).subscribe({
      next: (data) => {
        this.tools = data;
        this.loading = false;
        this.lastRefreshed = new Date();
      },
      error: (err) => {
        this.error = err?.message || 'Failed to load tools list';
        this.loading = false;
      }
    });
  }

  installTool(tool: ToolInfo): void {
    this.actionInProgress[tool.id] = true;
    this.http.post<{ message: string }>(`${backendUrl}/install/tools/${tool.id}`, {}).subscribe({
      next: (res) => {
        this.snackBar.open(
          res?.message || `Install queued for ${tool.name}`,
          'Dismiss',
          { duration: 4000, panelClass: ['snack-success'] }
        );
        this.actionInProgress[tool.id] = false;
        // Refresh after a short delay to pick up any fast installs
        setTimeout(() => this.loadTools(), 3000);
      },
      error: (err) => {
        this.snackBar.open(
          `Failed to queue install for ${tool.name}: ${err?.error?.message || err?.message || 'Unknown error'}`,
          'Dismiss',
          { duration: 6000, panelClass: ['snack-error'] }
        );
        this.actionInProgress[tool.id] = false;
      }
    });
  }

  uninstallTool(tool: ToolInfo): void {
    this.actionInProgress[tool.id] = true;
    this.http.delete<{ message: string }>(`${backendUrl}/install/tools/${tool.id}`).subscribe({
      next: (res) => {
        this.snackBar.open(
          res?.message || `Uninstall queued for ${tool.name}`,
          'Dismiss',
          { duration: 4000, panelClass: ['snack-success'] }
        );
        this.actionInProgress[tool.id] = false;
        setTimeout(() => this.loadTools(), 3000);
      },
      error: (err) => {
        this.snackBar.open(
          `Failed to queue uninstall for ${tool.name}: ${err?.error?.message || err?.message || 'Unknown error'}`,
          'Dismiss',
          { duration: 6000, panelClass: ['snack-error'] }
        );
        this.actionInProgress[tool.id] = false;
      }
    });
  }

  toolIcon(toolId: string): string {
    switch (toolId) {
      case 'graalvm':  return 'build';
      case 'maven':    return 'integration_instructions';
      case 'python':   return 'terminal';
      case 'cmake':    return 'engineering';
      default:         return 'extension';
    }
  }

  isActionInProgress(toolId: string): boolean {
    return !!this.actionInProgress[toolId];
  }
}
