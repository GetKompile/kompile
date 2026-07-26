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
import { EndpointMapping } from '@shared/services/rest-mcp-bridge.service';
import { RestMcpBridgeService } from '@shared/services/rest-mcp-bridge.service';

export interface MappingDialogData {
  mapping: EndpointMapping;
  httpMethods: string[];
  parameterTypes: string[];
}

@Component({
  selector: 'app-mapping-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule, MatCheckboxModule],
  template: `
    <h3 mat-dialog-title>{{ mapping.id ? 'Edit' : 'Create' }} Endpoint Mapping</h3>
    <mat-dialog-content>
      <div class="form-content">
        <div class="config-section">
          <h4>REST Endpoint</h4>
          <div class="row">
            <mat-form-field appearance="outline" class="method-field">
              <mat-label>Method</mat-label>
              <mat-select [(ngModel)]="mapping.restEndpoint.method">
                <mat-option *ngFor="let method of data.httpMethods" [value]="method">{{ method }}</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="path-field">
              <mat-label>Path</mat-label>
              <input matInput [(ngModel)]="mapping.restEndpoint.path" placeholder="/api/users/{id}">
            </mat-form-field>
          </div>
          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Content Type</mat-label>
              <input matInput [(ngModel)]="mapping.restEndpoint.contentType">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Accept Type</mat-label>
              <input matInput [(ngModel)]="mapping.restEndpoint.acceptType">
            </mat-form-field>
          </div>
          <div class="params-section">
            <h5>Path Parameters <button mat-icon-button (click)="addPathParam()"><mat-icon>add</mat-icon></button></h5>
            <div class="param-row" *ngFor="let param of mapping.restEndpoint.pathParams; let i = index">
              <mat-form-field appearance="outline"><mat-label>Name</mat-label><input matInput [(ngModel)]="param.name"></mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Type</mat-label>
                <mat-select [(ngModel)]="param.type">
                  <mat-option *ngFor="let t of data.parameterTypes" [value]="t">{{ t }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-checkbox [(ngModel)]="param.required">Required</mat-checkbox>
              <button mat-icon-button color="warn" (click)="removePathParam(i)"><mat-icon>delete</mat-icon></button>
            </div>
          </div>
          <div class="params-section">
            <h5>Query Parameters <button mat-icon-button (click)="addQueryParam()"><mat-icon>add</mat-icon></button></h5>
            <div class="param-row" *ngFor="let param of mapping.restEndpoint.queryParams; let i = index">
              <mat-form-field appearance="outline"><mat-label>Name</mat-label><input matInput [(ngModel)]="param.name"></mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Type</mat-label>
                <mat-select [(ngModel)]="param.type">
                  <mat-option *ngFor="let t of data.parameterTypes" [value]="t">{{ t }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-checkbox [(ngModel)]="param.required">Required</mat-checkbox>
              <button mat-icon-button color="warn" (click)="removeQueryParam(i)"><mat-icon>delete</mat-icon></button>
            </div>
          </div>
        </div>
        <div class="config-section">
          <h4>MCP Tool</h4>
          <mat-form-field appearance="outline">
            <mat-label>Tool Name</mat-label>
            <input matInput [(ngModel)]="mapping.mcpTool.name" placeholder="get_users">
            <mat-hint>Use snake_case for tool names</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Description</mat-label>
            <textarea matInput [(ngModel)]="mapping.mcpTool.description" rows="2"></textarea>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Category (optional)</mat-label>
            <input matInput [(ngModel)]="mapping.mcpTool.category">
          </mat-form-field>
        </div>
        <mat-slide-toggle [(ngModel)]="mapping.enabled">Enabled</mat-slide-toggle>
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
    .params-section { border-top: 1px solid var(--border-color); padding-top: 10px; }
    .params-section h5 { margin: 0 0 8px 0; display: flex; align-items: center; gap: 8px; }
    .row { display: flex; gap: 10px; }
    .row mat-form-field { flex: 1; }
    .method-field { flex: 0 0 120px; }
    .path-field { flex: 1; }
    .param-row { display: flex; gap: 10px; align-items: center; }
    .param-row mat-form-field { flex: 1; min-width: 100px; }`]
})
export class MappingDialogComponent {
  mapping: EndpointMapping;

  constructor(
    private dialogRef: MatDialogRef<MappingDialogComponent>,
    private bridgeService: RestMcpBridgeService,
    @Inject(MAT_DIALOG_DATA) public data: MappingDialogData
  ) {
    this.mapping = JSON.parse(JSON.stringify(data.mapping));
    if (!this.mapping.restEndpoint.pathParams) this.mapping.restEndpoint.pathParams = [];
    if (!this.mapping.restEndpoint.queryParams) this.mapping.restEndpoint.queryParams = [];
  }

  addPathParam(): void {
    this.mapping.restEndpoint.pathParams!.push(this.bridgeService.createDefaultParameter());
  }
  removePathParam(i: number): void { this.mapping.restEndpoint.pathParams!.splice(i, 1); }
  addQueryParam(): void {
    this.mapping.restEndpoint.queryParams!.push(this.bridgeService.createDefaultParameter());
  }
  removeQueryParam(i: number): void { this.mapping.restEndpoint.queryParams!.splice(i, 1); }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.mapping); }
}
