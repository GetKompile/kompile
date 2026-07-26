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
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AuthConfig, AuthType } from '@shared/services/rest-mcp-bridge.service';
import { RestMcpBridgeService } from '@shared/services/rest-mcp-bridge.service';

export interface AuthDialogData {
  authConfig: AuthConfig;
  authTypes: AuthType[];
}

@Component({
  selector: 'app-auth-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h3 mat-dialog-title>Authentication Configuration</h3>
    <mat-dialog-content>
      <div class="form-content" *ngIf="authConfig">
        <mat-form-field appearance="outline">
          <mat-label>Authentication Type</mat-label>
          <mat-select [(ngModel)]="authConfig.type" (ngModelChange)="onAuthTypeChange()">
            <mat-option *ngFor="let type of data.authTypes" [value]="type">{{ type }}</mat-option>
          </mat-select>
        </mat-form-field>
        <ng-container *ngIf="authConfig.type === 'API_KEY'">
          <mat-form-field appearance="outline">
            <mat-label>API Key Header</mat-label>
            <input matInput [(ngModel)]="authConfig.apiKeyHeader">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>API Key</mat-label>
            <input matInput [(ngModel)]="authConfig.apiKey" type="password">
          </mat-form-field>
        </ng-container>
        <ng-container *ngIf="authConfig.type === 'BEARER'">
          <mat-form-field appearance="outline">
            <mat-label>Bearer Token</mat-label>
            <input matInput [(ngModel)]="authConfig.bearerToken" type="password">
          </mat-form-field>
        </ng-container>
        <ng-container *ngIf="authConfig.type === 'BASIC'">
          <mat-form-field appearance="outline">
            <mat-label>Username</mat-label>
            <input matInput [(ngModel)]="authConfig.username">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Password</mat-label>
            <input matInput [(ngModel)]="authConfig.password" type="password">
          </mat-form-field>
        </ng-container>
        <ng-container *ngIf="authConfig.type === 'OAUTH2' && authConfig.oauth2">
          <mat-form-field appearance="outline">
            <mat-label>Token URL</mat-label>
            <input matInput [(ngModel)]="authConfig.oauth2!.tokenUrl">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Client ID</mat-label>
            <input matInput [(ngModel)]="authConfig.oauth2!.clientId">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Client Secret</mat-label>
            <input matInput [(ngModel)]="authConfig.oauth2!.clientSecret" type="password">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Scope (optional)</mat-label>
            <input matInput [(ngModel)]="authConfig.oauth2!.scope">
          </mat-form-field>
        </ng-container>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-raised-button color="primary" (click)="onClose()">Close</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 400px; }
    .form-content mat-form-field { width: 100%; }`]
})
export class AuthDialogComponent {
  authConfig: AuthConfig;

  constructor(
    private dialogRef: MatDialogRef<AuthDialogComponent>,
    private bridgeService: RestMcpBridgeService,
    @Inject(MAT_DIALOG_DATA) public data: AuthDialogData
  ) {
    this.authConfig = { ...data.authConfig };
    if (this.authConfig.oauth2) {
      this.authConfig.oauth2 = { ...this.authConfig.oauth2 };
    }
  }

  onAuthTypeChange(): void {
    this.authConfig = this.bridgeService.createDefaultAuthConfig(this.authConfig.type);
  }

  onClose(): void { this.dialogRef.close(this.authConfig); }
}
