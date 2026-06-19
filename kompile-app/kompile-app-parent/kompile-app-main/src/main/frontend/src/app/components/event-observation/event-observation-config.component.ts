/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { EventObservationService } from '../../services/event-observation.service';
import { EventObservationConfig, OPPORTUNITY_MODELS } from '../../models/event-observation-models';

@Component({
  selector: 'app-event-observation-config',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatButtonModule, MatIconModule, MatSnackBarModule
  ],
  template: `
    <mat-card class="eo-config">
      <h3>Event Observation Configuration</h3>
      <p class="hint">Empirical Beta-Binomial priors learned from observed events. Stored in the
        backends below; the probability model controls the Binomial denominator.</p>

      <div class="row">
        <mat-checkbox [(ngModel)]="cfg.enabled">Enabled</mat-checkbox>
        <mat-checkbox [(ngModel)]="cfg.decayOnEachCrawl">Decay on each crawl</mat-checkbox>
      </div>

      <mat-form-field appearance="outline">
        <mat-label>Probability (opportunity) model</mat-label>
        <mat-select [(ngModel)]="cfg.opportunityModel">
          <mat-option *ngFor="let m of models" [value]="m">{{ m }}</mat-option>
        </mat-select>
      </mat-form-field>

      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Half-life (days)</mat-label>
          <input matInput type="number" [(ngModel)]="cfg.halfLifeDays">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Prior &alpha;</mat-label>
          <input matInput type="number" [(ngModel)]="cfg.priorAlpha">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Prior &beta;</mat-label>
          <input matInput type="number" [(ngModel)]="cfg.priorBeta">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Blend k</mat-label>
          <input matInput type="number" [(ngModel)]="cfg.priorBlendK">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Min evidence for prior</mat-label>
          <input matInput type="number" [(ngModel)]="cfg.minEvidenceForPrior">
        </mat-form-field>
      </div>

      <div class="row">
        <span class="label">Storage backends:</span>
        <mat-checkbox [ngModel]="backendOn('jpa')" (ngModelChange)="toggleBackend('jpa', $event)">JPA</mat-checkbox>
        <mat-checkbox [ngModel]="backendOn('vector')" (ngModelChange)="toggleBackend('vector', $event)">Vector store</mat-checkbox>
      </div>

      <div class="row">
        <mat-checkbox [(ngModel)]="cfg.entityEventsEnabled">Entity events</mat-checkbox>
        <mat-checkbox [(ngModel)]="cfg.connectionEventsEnabled">Connection events</mat-checkbox>
        <mat-checkbox [(ngModel)]="cfg.processStepEventsEnabled">Process-step events</mat-checkbox>
        <mat-checkbox [(ngModel)]="cfg.fineGrainedMutationsEnabled">Fine-grained mutation updates</mat-checkbox>
      </div>

      <div class="actions">
        <button mat-raised-button color="primary" [disabled]="saving" (click)="save()">
          <mat-icon>save</mat-icon> Save
        </button>
        <button mat-button (click)="load()">Reload</button>
      </div>
    </mat-card>
  `,
  styles: [`
    .eo-config { padding: 16px; max-width: 760px; }
    .hint { color: #888; font-size: 12px; margin-bottom: 16px; }
    .row { display: flex; gap: 18px; align-items: center; flex-wrap: wrap; margin: 10px 0; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
    .label { font-weight: 600; }
    .actions { margin-top: 16px; display: flex; gap: 12px; }
    mat-form-field { width: 100%; }
  `]
})
export class EventObservationConfigComponent implements OnInit {

  cfg: EventObservationConfig = {};
  models = OPPORTUNITY_MODELS;
  saving = false;

  constructor(private svc: EventObservationService, private snack: MatSnackBar) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.svc.getConfig().subscribe({
      next: c => this.cfg = c,
      error: () => this.snack.open('Failed to load config', 'OK', { duration: 3000 })
    });
  }

  save(): void {
    this.saving = true;
    this.svc.updateConfig(this.cfg).subscribe({
      next: c => { this.cfg = c; this.saving = false; this.snack.open('Configuration saved', 'OK', { duration: 2000 }); },
      error: () => { this.saving = false; this.snack.open('Save failed', 'OK', { duration: 3000 }); }
    });
  }

  backendOn(name: string): boolean {
    return (this.cfg.storageBackends || []).includes(name);
  }

  toggleBackend(name: string, on: boolean): void {
    let backends = this.cfg.storageBackends ? [...this.cfg.storageBackends] : [];
    if (on && !backends.includes(name)) {
      backends.push(name);
    } else if (!on) {
      backends = backends.filter(b => b !== name);
    }
    this.cfg.storageBackends = backends;
  }
}
