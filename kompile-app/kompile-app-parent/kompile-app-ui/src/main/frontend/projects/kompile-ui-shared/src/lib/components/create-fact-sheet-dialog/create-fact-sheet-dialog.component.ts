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

import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface CreateFactSheetDialogResult {
  name: string;
  description: string;
}

@Component({
  selector: 'app-create-fact-sheet-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Create New Fact Sheet</h2>
    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Name</mat-label>
        <input matInput [(ngModel)]="name" placeholder="My Fact Sheet">
      </mat-form-field>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Description (optional)</mat-label>
        <textarea matInput [(ngModel)]="description" rows="2" placeholder="What is this fact sheet for?"></textarea>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-raised-button color="primary" (click)="onCreate()" [disabled]="!name.trim()">Create</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 8px; }
    mat-dialog-content { display: flex; flex-direction: column; min-width: 360px; }
  `]
})
export class CreateFactSheetDialogComponent {
  name = '';
  description = '';

  constructor(private dialogRef: MatDialogRef<CreateFactSheetDialogComponent>) {}

  onCancel(): void {
    this.dialogRef.close(null);
  }

  onCreate(): void {
    if (!this.name.trim()) return;
    const result: CreateFactSheetDialogResult = {
      name: this.name.trim(),
      description: this.description.trim()
    };
    this.dialogRef.close(result);
  }
}
