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

import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ExternalMcpServerConfig } from '@shared/services/external-mcp-server.service';

export interface ExtMcpDetailsDialogData {
  server: ExternalMcpServerConfig;
  serverJson: string;
}

export const EXT_MCP_DETAILS_EDIT_ACTION = 'EDIT';

@Component({
  selector: 'app-ext-mcp-details-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <h3 mat-dialog-title>Server Details: {{ data.server.id }}</h3>
    <mat-dialog-content>
      <div class="details-content">
        <div class="details-section">
          <h4>Configuration</h4>
          <div class="details-grid">
            <div class="detail-item">
              <span class="detail-label">Server ID</span>
              <code class="detail-value">{{ data.server.id }}</code>
            </div>
            <div class="detail-item">
              <span class="detail-label">Status</span>
              <span class="status-badge" [class]="data.server.status?.toLowerCase() || 'stopped'">
                {{ data.server.status || 'STOPPED' }}
              </span>
            </div>
            <div class="detail-item" *ngIf="data.server.command">
              <span class="detail-label">Command</span>
              <code class="detail-value">{{ data.server.command }}</code>
            </div>
            <div class="detail-item" *ngIf="data.server.pid">
              <span class="detail-label">Process ID</span>
              <code class="detail-value">{{ data.server.pid }}</code>
            </div>
            <div class="detail-item full-width" *ngIf="data.server.description">
              <span class="detail-label">Description</span>
              <span class="detail-value">{{ data.server.description }}</span>
            </div>
          </div>
        </div>
        <div class="details-section" *ngIf="data.server.args?.length">
          <h4>Arguments</h4>
          <div class="args-display">
            <code *ngFor="let arg of data.server.args; let i = index" class="arg-display-item">
              <span class="arg-index">{{ i }}</span>{{ arg }}
            </code>
          </div>
        </div>
        <div class="details-section" *ngIf="data.server.env && objectKeys(data.server.env).length > 0">
          <h4>Environment Variables</h4>
          <div class="env-display">
            <div *ngFor="let key of objectKeys(data.server.env)" class="env-display-item">
              <code class="env-display-key">{{ key }}</code>
              <code class="env-display-value">{{ data.server.env![key] }}</code>
            </div>
          </div>
        </div>
        <div class="details-section">
          <h4>
            JSON Configuration
            <button mat-icon-button (click)="copyServerJson()" matTooltip="Copy to clipboard">
              <mat-icon>content_copy</mat-icon>
            </button>
          </h4>
          <pre class="json-display">{{ data.serverJson }}</pre>
        </div>
        <div class="details-section error-section" *ngIf="data.server.errorMessage">
          <h4>Error</h4>
          <p class="error-message">{{ data.server.errorMessage }}</p>
        </div>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="copyServerJson()">
        <mat-icon>content_copy</mat-icon> Copy JSON
      </button>
      <button mat-button (click)="onEdit()">
        <mat-icon>edit</mat-icon> Edit
      </button>
      <button mat-raised-button color="primary" (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
  styles: [`.details-content { min-width: 580px; max-width: 780px; }
    .details-section { margin-bottom: 20px; }
    .details-section h4 { font-size: 14px; font-weight: 500; margin: 0 0 10px 0; display: flex; align-items: center; gap: 8px; }
    .details-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
    .detail-item { display: flex; flex-direction: column; gap: 4px; }
    .detail-item.full-width { grid-column: 1 / -1; }
    .detail-label { font-size: 12px; color: var(--text-secondary); text-transform: uppercase; }
    .detail-value, .env-display-key, .env-display-value { font-family: monospace; font-size: 13px; background: var(--bg-surface-elevated); padding: 3px 8px; border-radius: 3px; }
    .status-badge { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 12px; background: #e0e0e0; }
    .args-display, .env-display { display: flex; flex-direction: column; gap: 4px; }
    .arg-display-item { display: flex; gap: 12px; align-items: center; background: var(--bg-surface-elevated); padding: 4px 8px; border-radius: 3px; font-size: 13px; }
    .arg-index { color: var(--text-secondary); min-width: 20px; }
    .env-display-item { display: flex; gap: 8px; align-items: center; }
    .json-display { background: var(--bg-body); border: 1px solid var(--border-color); border-radius: 4px; padding: 12px; font-size: 12px; overflow: auto; max-height: 250px; white-space: pre; }
    .error-section p { color: #c62828; margin: 0; }`]
})
export class ExtMcpDetailsDialogComponent {
  constructor(
    private dialogRef: MatDialogRef<ExtMcpDetailsDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: ExtMcpDetailsDialogData
  ) {}

  objectKeys(obj: any): string[] { return obj ? Object.keys(obj) : []; }

  copyServerJson(): void {
    navigator.clipboard.writeText(this.data.serverJson).catch(() => {});
  }

  onEdit(): void { this.dialogRef.close(EXT_MCP_DETAILS_EDIT_ACTION); }
  onClose(): void { this.dialogRef.close(null); }
}
