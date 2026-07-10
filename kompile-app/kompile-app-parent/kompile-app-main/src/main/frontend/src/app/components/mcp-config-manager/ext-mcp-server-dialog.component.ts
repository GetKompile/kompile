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
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ExternalMcpServerConfig, ExternalMcpServerService } from '../../services/external-mcp-server.service';

export interface ExtMcpServerDialogData {
  server: ExternalMcpServerConfig;
  isEditing: boolean;
  envKeys: string[];
  envValues: string[];
  headerKeys: string[];
  headerValues: string[];
}

@Component({
  selector: 'app-ext-mcp-server-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  template: `
    <h3 mat-dialog-title>{{ data.isEditing ? 'Edit' : 'Add' }} External MCP Server ({{ server?.transportType || 'STDIO' }})</h3>
    <mat-dialog-content>
      <div class="form-content" *ngIf="server">
        <mat-form-field appearance="outline">
          <mat-label>Server ID</mat-label>
          <input matInput [(ngModel)]="server.id" placeholder="my-server" [disabled]="data.isEditing">
          <mat-hint>Unique identifier (e.g., filesystem, github)</mat-hint>
        </mat-form-field>
        <div class="transport-info">
          <span class="transport-badge" [class]="server.transportType?.toLowerCase() || 'stdio'">
            {{ server.transportType || 'STDIO' }}
          </span>
        </div>
        <!-- STDIO -->
        <ng-container *ngIf="isStdio()">
          <mat-form-field appearance="outline">
            <mat-label>Command</mat-label>
            <input matInput [(ngModel)]="server.command" placeholder="npx">
            <mat-hint>The command to execute</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Arguments (one per line)</mat-label>
            <textarea matInput [ngModel]="getArgsString()" (ngModelChange)="setArgsFromString($event)" rows="4"
              placeholder="-y&#10;@modelcontextprotocol/server-filesystem&#10;/path/to/files"></textarea>
            <mat-hint>Command arguments, one per line</mat-hint>
          </mat-form-field>
          <div class="env-section">
            <h4>Environment Variables <button mat-icon-button (click)="addEnvVar()"><mat-icon>add</mat-icon></button></h4>
            <div class="env-row" *ngFor="let key of envKeys; let i = index">
              <mat-form-field appearance="outline"><mat-label>Key</mat-label><input matInput [(ngModel)]="envKeys[i]" placeholder="API_KEY"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Value</mat-label><input matInput [(ngModel)]="envValues[i]" type="password"></mat-form-field>
              <button mat-icon-button color="warn" (click)="removeEnvVar(i)"><mat-icon>delete</mat-icon></button>
            </div>
          </div>
        </ng-container>
        <!-- REST/SSE -->
        <ng-container *ngIf="!isStdio()">
          <mat-form-field appearance="outline">
            <mat-label>Server URL</mat-label>
            <input matInput [(ngModel)]="server.url" placeholder="http://localhost:3000/mcp">
          </mat-form-field>
          <ng-container *ngIf="isSse()">
            <mat-form-field appearance="outline">
              <mat-label>SSE Endpoint Path</mat-label>
              <input matInput [(ngModel)]="server.sseEndpoint" placeholder="/sse">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Messages Endpoint Path</mat-label>
              <input matInput [(ngModel)]="server.messagesEndpoint" placeholder="/message">
            </mat-form-field>
          </ng-container>
          <div class="timeout-row">
            <mat-form-field appearance="outline">
              <mat-label>Connection Timeout (ms)</mat-label>
              <input matInput type="number" [(ngModel)]="server.connectionTimeout" placeholder="30000">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Request Timeout (ms)</mat-label>
              <input matInput type="number" [(ngModel)]="server.requestTimeout" placeholder="60000">
            </mat-form-field>
          </div>
          <mat-slide-toggle [(ngModel)]="server.verifySsl">Verify SSL Certificates</mat-slide-toggle>
          <div class="env-section">
            <h4>HTTP Headers <button mat-icon-button (click)="addHeader()"><mat-icon>add</mat-icon></button></h4>
            <div class="env-row" *ngFor="let key of headerKeys; let i = index">
              <mat-form-field appearance="outline"><mat-label>Header Name</mat-label><input matInput [(ngModel)]="headerKeys[i]" placeholder="Authorization"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Header Value</mat-label><input matInput [(ngModel)]="headerValues[i]" type="password"></mat-form-field>
              <button mat-icon-button color="warn" (click)="removeHeader(i)"><mat-icon>delete</mat-icon></button>
            </div>
          </div>
        </ng-container>
        <mat-form-field appearance="outline">
          <mat-label>Description (optional)</mat-label>
          <input matInput [(ngModel)]="server.description" placeholder="Provides filesystem access">
        </mat-form-field>
        <mat-slide-toggle [(ngModel)]="server.enabled">Enabled</mat-slide-toggle>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 500px; max-width: 700px; }
    .form-content mat-form-field { width: 100%; }
    .transport-info { display: flex; align-items: center; gap: 8px; }
    .transport-badge { padding: 4px 12px; border-radius: 12px; font-size: 12px; font-weight: 600; background: #e3f2fd; color: #1565c0; }
    .env-section { border: 1px solid var(--border-color); border-radius: 8px; padding: 12px; }
    .env-section h4 { margin: 0 0 10px 0; font-size: 14px; font-weight: 500; display: flex; align-items: center; gap: 8px; }
    .env-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
    .env-row mat-form-field { flex: 1; }
    .timeout-row { display: flex; gap: 10px; }
    .timeout-row mat-form-field { flex: 1; }`]
})
export class ExtMcpServerDialogComponent {
  server: ExternalMcpServerConfig;
  envKeys: string[];
  envValues: string[];
  headerKeys: string[];
  headerValues: string[];

  constructor(
    private dialogRef: MatDialogRef<ExtMcpServerDialogComponent>,
    private mcpService: ExternalMcpServerService,
    @Inject(MAT_DIALOG_DATA) public data: ExtMcpServerDialogData
  ) {
    this.server = { ...data.server, args: [...(data.server.args || [])], env: { ...(data.server.env || {}) }, headers: { ...(data.server.headers || {}) } };
    this.envKeys = [...data.envKeys];
    this.envValues = [...data.envValues];
    this.headerKeys = [...data.headerKeys];
    this.headerValues = [...data.headerValues];
  }

  isStdio(): boolean { return this.mcpService.isStdio(this.server); }
  isSse(): boolean { return this.mcpService.isSse(this.server); }

  getArgsString(): string { return this.server.args?.join('\n') || ''; }
  setArgsFromString(value: string): void {
    this.server.args = value.split('\n').map(s => s.trim()).filter(s => s);
  }

  addEnvVar(): void { this.envKeys.push(''); this.envValues.push(''); }
  removeEnvVar(i: number): void { this.envKeys.splice(i, 1); this.envValues.splice(i, 1); }
  addHeader(): void { this.headerKeys.push(''); this.headerValues.push(''); }
  removeHeader(i: number): void { this.headerKeys.splice(i, 1); this.headerValues.splice(i, 1); }

  onCancel(): void { this.dialogRef.close(null); }

  onSave(): void {
    if (this.isStdio()) {
      this.server.env = {};
      for (let i = 0; i < this.envKeys.length; i++) {
        const key = this.envKeys[i].trim();
        if (key) this.server.env[key] = this.envValues[i] || '';
      }
    } else {
      this.server.headers = {};
      for (let i = 0; i < this.headerKeys.length; i++) {
        const key = this.headerKeys[i].trim();
        if (key) this.server.headers![key] = this.headerValues[i] || '';
      }
    }
    this.dialogRef.close(this.server);
  }
}
