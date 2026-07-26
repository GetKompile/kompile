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
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  RestMcpBridgeConfig, BridgeDirection, AuthType
} from '@shared/services/rest-mcp-bridge.service';

export interface BridgeDialogData {
  bridge: RestMcpBridgeConfig;
  isEditing: boolean;
  bridgeDirections: BridgeDirection[];
  authTypes: AuthType[];
}

@Component({
  selector: 'app-bridge-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule, MatCheckboxModule],
  template: `
    <h3 mat-dialog-title>{{ data.isEditing ? 'Edit' : 'Create' }} REST-MCP Bridge</h3>
    <mat-dialog-content>
      <div class="form-content" *ngIf="bridge">
        <mat-form-field appearance="outline">
          <mat-label>Bridge Name</mat-label>
          <input matInput [(ngModel)]="bridge.name" placeholder="My API Bridge">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="bridge.description" rows="2"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Bridge Direction</mat-label>
          <mat-select [(ngModel)]="bridge.direction">
            <mat-option *ngFor="let dir of data.bridgeDirections" [value]="dir">{{ dir }}</mat-option>
          </mat-select>
        </mat-form-field>
        <div class="config-section">
          <h4>REST API Configuration</h4>
          <mat-form-field appearance="outline">
            <mat-label>Base URL</mat-label>
            <input matInput [(ngModel)]="bridge.restApiConfig.baseUrl" placeholder="https://api.example.com">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>OpenAPI URL (optional)</mat-label>
            <input matInput [(ngModel)]="bridge.restApiConfig.openApiUrl" placeholder="https://api.example.com/openapi.json">
          </mat-form-field>
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Timeout (ms)</mat-label>
              <input matInput type="number" [(ngModel)]="bridge.restApiConfig.timeoutMs">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Rate Limit (req/sec)</mat-label>
              <input matInput type="number" [(ngModel)]="bridge.restApiConfig.rateLimitPerSecond" placeholder="0 = unlimited">
            </mat-form-field>
          </div>
          <mat-checkbox [(ngModel)]="bridge.restApiConfig.verifySsl">Verify SSL Certificates</mat-checkbox>
        </div>
        <div class="config-section">
          <h4>MCP Server Configuration</h4>
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Port</mat-label>
              <input matInput type="number" [(ngModel)]="bridge.mcpServerRef.port" min="1" max="65535">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Base Path</mat-label>
              <input matInput [(ngModel)]="bridge.mcpServerRef.basePath" placeholder="/mcp-bridge">
            </mat-form-field>
          </div>
        </div>
        <mat-slide-toggle [(ngModel)]="bridge.enabled">Enabled</mat-slide-toggle>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 580px; max-width: 780px; }
    .form-content mat-form-field { width: 100%; }
    .config-section { border: 1px solid var(--border-color); border-radius: 8px; padding: 15px; background: var(--bg-body); display: flex; flex-direction: column; gap: 10px; }
    .config-section h4 { margin: 0 0 10px 0; font-size: 14px; font-weight: 500; }
    .row { display: flex; gap: 10px; }
    .row mat-form-field { flex: 1; }`]
})
export class BridgeDialogComponent {
  bridge: RestMcpBridgeConfig;

  constructor(
    private dialogRef: MatDialogRef<BridgeDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: BridgeDialogData
  ) {
    this.bridge = JSON.parse(JSON.stringify(data.bridge));
  }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.bridge); }
}
