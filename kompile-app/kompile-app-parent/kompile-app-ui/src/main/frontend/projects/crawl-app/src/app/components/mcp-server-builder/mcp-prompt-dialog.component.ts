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
import { McpPromptConfig } from '@shared/services/mcp-server-builder.service';

export interface McpPromptDialogData {
  prompt: McpPromptConfig;
  messageRoles: string[];
}

@Component({
  selector: 'app-mcp-prompt-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule, MatCheckboxModule],
  template: `
    <h3 mat-dialog-title>{{ prompt.name ? 'Edit' : 'Create' }} Prompt</h3>
    <mat-dialog-content>
      <div class="form-content">
        <mat-form-field appearance="outline">
          <mat-label>Prompt Name</mat-label>
          <input matInput [(ngModel)]="prompt.name" placeholder="my_prompt">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="prompt.description" rows="2"></textarea>
        </mat-form-field>
        <div class="args-section">
          <h4>Arguments <button mat-icon-button (click)="addArgument()"><mat-icon>add</mat-icon></button></h4>
          <div class="arg-row" *ngFor="let arg of prompt.arguments; let i = index">
            <mat-form-field appearance="outline">
              <mat-label>Name</mat-label>
              <input matInput [(ngModel)]="arg.name">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Description</mat-label>
              <input matInput [(ngModel)]="arg.description">
            </mat-form-field>
            <mat-checkbox [(ngModel)]="arg.required">Required</mat-checkbox>
            <button mat-icon-button color="warn" (click)="removeArgument(i)">
              <mat-icon>delete</mat-icon>
            </button>
          </div>
        </div>
        <div class="messages-section">
          <h4>Messages <button mat-icon-button (click)="addMessage()"><mat-icon>add</mat-icon></button></h4>
          <div class="message-row" *ngFor="let msg of prompt.messages; let i = index">
            <div class="message-row-fields">
              <mat-form-field appearance="outline">
                <mat-label>Role</mat-label>
                <mat-select [(ngModel)]="msg.role">
                  <mat-option *ngFor="let role of data.messageRoles" [value]="role">{{ role }}</mat-option>
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline" class="message-content">
                <mat-label>Content</mat-label>
                <textarea matInput [(ngModel)]="msg.content" rows="3" placeholder="Use {argument} for placeholders"></textarea>
              </mat-form-field>
              <button mat-icon-button color="warn" (click)="removeMessage(i)">
                <mat-icon>delete</mat-icon>
              </button>
            </div>
          </div>
        </div>
        <mat-slide-toggle [(ngModel)]="prompt.enabled">Enabled</mat-slide-toggle>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>
  `,
  styles: [`.form-content { display: flex; flex-direction: column; gap: 15px; min-width: 580px; max-width: 780px; }
    .form-content mat-form-field { width: 100%; }
    .args-section, .messages-section { border: 1px solid var(--border-color); border-radius: 8px; padding: 15px; background: var(--bg-body); }
    .args-section h4, .messages-section h4 { margin: 0 0 15px 0; font-size: 14px; font-weight: 500; display: flex; align-items: center; gap: 10px; }
    .arg-row { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
    .arg-row mat-form-field { flex: 1; min-width: 120px; }
    .message-row-fields { display: flex; gap: 10px; align-items: flex-start; margin-bottom: 10px; }
    .message-content { flex: 1; }`]
})
export class McpPromptDialogComponent {
  prompt: McpPromptConfig;

  constructor(
    private dialogRef: MatDialogRef<McpPromptDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: McpPromptDialogData
  ) {
    this.prompt = {
      ...data.prompt,
      arguments: [...(data.prompt.arguments || [])],
      messages: [...(data.prompt.messages || [])]
    };
  }

  addArgument(): void {
    this.prompt.arguments.push({ name: '', description: '', required: false });
  }

  removeArgument(index: number): void {
    this.prompt.arguments.splice(index, 1);
  }

  addMessage(): void {
    this.prompt.messages.push({ role: 'USER', contentType: 'TEXT', content: '' });
  }

  removeMessage(index: number): void {
    this.prompt.messages.splice(index, 1);
  }

  onCancel(): void { this.dialogRef.close(null); }
  onSave(): void { this.dialogRef.close(this.prompt); }
}
