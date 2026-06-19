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
import { FormsModule } from '@angular/forms';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

import { GraphExtractionService, FallbackConfig, ExtractionProvider } from '../../services/graph-extraction.service';

@Component({
  selector: 'app-extraction-fallback-config',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DragDropModule,
    MatCardModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatChipsModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    MatDividerModule
  ],
  template: `
    <div class="fallback-config-container">
      <mat-card>
        <mat-card-header>
          <mat-icon mat-card-avatar>swap_horiz</mat-icon>
          <mat-card-title>Extraction Provider Fallback</mat-card-title>
          <mat-card-subtitle>Configure provider priority and automatic failover for graph extraction</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content *ngIf="loading" class="loading-container">
          <mat-spinner diameter="40"></mat-spinner>
        </mat-card-content>

        <mat-card-content *ngIf="!loading">
          <!-- Toggles -->
          <div class="toggle-section">
            <div class="toggle-row">
              <mat-slide-toggle
                [(ngModel)]="fallbackEnabled"
                (change)="saveConfig()"
                color="primary">
                Enable provider fallback
              </mat-slide-toggle>
              <span class="toggle-hint">When a provider fails, automatically try the next one in order</span>
            </div>

            <div class="toggle-row" *ngIf="fallbackEnabled">
              <mat-slide-toggle
                [(ngModel)]="fallbackOnTimeouts"
                (change)="saveConfig()"
                color="primary">
                Fallback on timeouts
              </mat-slide-toggle>
              <span class="toggle-hint">Also trigger fallback when a provider times out (not just quota/rate-limit errors)</span>
            </div>
          </div>

          <mat-divider></mat-divider>

          <!-- Timeout -->
          <div class="timeout-section">
            <mat-form-field appearance="outline" class="timeout-field">
              <mat-label>Extraction timeout (seconds)</mat-label>
              <input matInput type="number" [(ngModel)]="extractionTimeoutSeconds"
                     (blur)="saveConfig()" min="30" max="3600">
              <mat-hint>Per-provider timeout before fallback triggers (30-3600s)</mat-hint>
            </mat-form-field>
          </div>

          <mat-divider></mat-divider>

          <!-- Provider Order -->
          <div class="provider-section">
            <h3>Provider Priority Order</h3>
            <p class="section-hint">Drag to reorder. Providers are tried top-to-bottom on failure.</p>

            <div cdkDropList class="provider-list" (cdkDropListDropped)="dropProvider($event)">
              <div class="provider-item" *ngFor="let provider of orderedProviders; let i = index"
                   cdkDrag [cdkDragDisabled]="!fallbackEnabled">
                <div class="provider-drag-handle" cdkDragHandle>
                  <mat-icon>drag_indicator</mat-icon>
                </div>
                <span class="provider-rank">{{i + 1}}</span>
                <div class="provider-info">
                  <span class="provider-name">{{provider.id}}</span>
                  <span class="provider-description">{{provider.description}}</span>
                </div>
                <mat-icon class="provider-status" [class.available]="provider.available"
                          [matTooltip]="provider.available ? 'Available' : 'Unavailable'">
                  {{provider.available ? 'check_circle' : 'cancel'}}
                </mat-icon>
              </div>
            </div>

            <div *ngIf="orderedProviders.length === 0" class="no-providers">
              No extraction providers registered
            </div>
          </div>
        </mat-card-content>

        <mat-card-actions *ngIf="!loading" align="end">
          <button mat-button (click)="resetOrder()" [disabled]="!fallbackEnabled">
            <mat-icon>restart_alt</mat-icon> Reset Order
          </button>
          <button mat-raised-button color="primary" (click)="saveConfig()">
            <mat-icon>save</mat-icon> Save
          </button>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: [`
    .fallback-config-container {
      padding: 16px;
      max-width: 700px;
    }
    .loading-container {
      display: flex;
      justify-content: center;
      padding: 40px;
    }
    .toggle-section {
      padding: 16px 0;
    }
    .toggle-row {
      display: flex;
      flex-direction: column;
      gap: 4px;
      margin-bottom: 16px;
    }
    .toggle-hint {
      font-size: 12px;
      color: rgba(255,255,255,0.5);
      margin-left: 52px;
    }
    .timeout-section {
      padding: 16px 0;
    }
    .timeout-field {
      width: 280px;
    }
    .provider-section {
      padding: 16px 0;
    }
    .provider-section h3 {
      margin: 0 0 4px;
      font-size: 14px;
      font-weight: 500;
    }
    .section-hint {
      font-size: 12px;
      color: rgba(255,255,255,0.5);
      margin: 0 0 12px;
    }
    .provider-list {
      border: 1px solid rgba(255,255,255,0.12);
      border-radius: 4px;
      overflow: hidden;
    }
    .provider-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border-bottom: 1px solid rgba(255,255,255,0.06);
      background: rgba(255,255,255,0.03);
      cursor: move;
    }
    .provider-item:last-child {
      border-bottom: none;
    }
    .provider-drag-handle {
      color: rgba(255,255,255,0.3);
      cursor: grab;
    }
    .provider-rank {
      font-size: 14px;
      font-weight: 600;
      color: rgba(255,255,255,0.5);
      min-width: 20px;
      text-align: center;
    }
    .provider-info {
      flex: 1;
      display: flex;
      flex-direction: column;
    }
    .provider-name {
      font-weight: 500;
    }
    .provider-description {
      font-size: 12px;
      color: rgba(255,255,255,0.5);
    }
    .provider-status {
      font-size: 18px;
      width: 18px;
      height: 18px;
      color: rgba(255,255,255,0.3);
    }
    .provider-status.available {
      color: #4caf50;
    }
    .no-providers {
      padding: 24px;
      text-align: center;
      color: rgba(255,255,255,0.4);
      font-style: italic;
    }
    .cdk-drag-preview {
      box-shadow: 0 4px 12px rgba(0,0,0,0.3);
      border-radius: 4px;
      background: #424242;
    }
    .cdk-drag-placeholder {
      opacity: 0.3;
    }
    mat-divider {
      margin: 8px 0;
    }
  `]
})
export class ExtractionFallbackConfigComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>();

  loading = true;
  fallbackEnabled = true;
  fallbackOnTimeouts = false;
  extractionTimeoutSeconds = 300;
  orderedProviders: ExtractionProvider[] = [];
  allProviders: ExtractionProvider[] = [];

  constructor(
    private graphExtractionService: GraphExtractionService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadConfig(): void {
    this.loading = true;
    this.graphExtractionService.getFallbackConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.fallbackEnabled = config.fallbackEnabled;
          this.fallbackOnTimeouts = config.fallbackOnTimeouts;
          this.extractionTimeoutSeconds = config.extractionTimeoutSeconds;
          this.allProviders = config.availableProviders || [];
          this.buildOrderedList(config.providerOrder || []);
          this.loading = false;
        },
        error: (err) => {
          console.error('Failed to load fallback config', err);
          this.loading = false;
          this.snackBar.open('Failed to load fallback config', 'Dismiss', { duration: 3000 });
        }
      });
  }

  private buildOrderedList(savedOrder: string[]): void {
    const ordered: ExtractionProvider[] = [];
    const remaining = [...this.allProviders];

    // Add providers in saved order first
    for (const id of savedOrder) {
      const idx = remaining.findIndex(p => p.id === id);
      if (idx >= 0) {
        ordered.push(remaining.splice(idx, 1)[0]);
      }
    }
    // Append any providers not in the saved order
    ordered.push(...remaining);
    this.orderedProviders = ordered;
  }

  dropProvider(event: CdkDragDrop<ExtractionProvider[]>): void {
    moveItemInArray(this.orderedProviders, event.previousIndex, event.currentIndex);
    this.saveConfig();
  }

  resetOrder(): void {
    this.orderedProviders = [...this.allProviders];
    this.saveConfig();
  }

  saveConfig(): void {
    const update: Partial<FallbackConfig> = {
      fallbackEnabled: this.fallbackEnabled,
      fallbackOnTimeouts: this.fallbackOnTimeouts,
      extractionTimeoutSeconds: this.extractionTimeoutSeconds,
      providerOrder: this.orderedProviders.map(p => p.id)
    };
    this.graphExtractionService.updateFallbackConfig(update)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.fallbackEnabled = config.fallbackEnabled;
          this.fallbackOnTimeouts = config.fallbackOnTimeouts;
          this.extractionTimeoutSeconds = config.extractionTimeoutSeconds;
          if (config.availableProviders) {
            this.allProviders = config.availableProviders;
            this.buildOrderedList(config.providerOrder || []);
          }
          this.snackBar.open('Fallback config saved', 'OK', { duration: 2000 });
        },
        error: (err) => {
          console.error('Failed to save fallback config', err);
          this.snackBar.open('Failed to save fallback config', 'Dismiss', { duration: 3000 });
        }
      });
  }
}
