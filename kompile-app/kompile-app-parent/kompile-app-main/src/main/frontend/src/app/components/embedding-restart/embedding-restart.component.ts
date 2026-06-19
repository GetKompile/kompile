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

import { Component, OnInit, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  EmbeddingRestartService,
  EmbeddingRestartStatus
} from '../../services/embedding-restart.service';

@Component({
  selector: 'app-embedding-restart',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './embedding-restart.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EmbeddingRestartComponent implements OnInit {

  loading = true;
  saving = false;
  resuming = false;

  status: EmbeddingRestartStatus | null = null;

  // Editable form state (initialized from status on load).
  autoRestartEnabled = true;
  nativeCrashThreshold = 3;

  constructor(
    private embeddingRestartService: EmbeddingRestartService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.embeddingRestartService.getStatus().subscribe({
      next: (s) => this.applyStatus(s),
      error: (err) => {
        console.error('Failed to load embedding restart status', err);
        this.loading = false;
        this.snackBar.open('Failed to load embedding restart status', 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  private applyStatus(s: EmbeddingRestartStatus): void {
    this.status = s;
    this.autoRestartEnabled = s.autoRestartEnabled;
    this.nativeCrashThreshold = s.nativeCrashThreshold;
    this.loading = false;
    this.saving = false;
    this.resuming = false;
    this.cdr.markForCheck();
  }

  save(): void {
    this.saving = true;
    this.cdr.markForCheck();
    this.embeddingRestartService.saveConfig({
      autoRestartEnabled: this.autoRestartEnabled,
      nativeCrashThreshold: this.nativeCrashThreshold
    }).subscribe({
      next: (s) => {
        this.applyStatus(s);
        this.snackBar.open('Embedding restart settings saved', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open('Failed to save: ' + (err.message || 'Unknown error'), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }

  resume(): void {
    this.resuming = true;
    this.cdr.markForCheck();
    this.embeddingRestartService.resume().subscribe({
      next: (s) => {
        this.applyStatus(s);
        this.snackBar.open('Embedding restarts resumed', 'OK', { duration: 3000 });
      },
      error: (err) => {
        this.resuming = false;
        this.snackBar.open('Failed to resume: ' + (err.message || 'Unknown error'), 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      }
    });
  }
}
