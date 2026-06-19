/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component, EventEmitter, OnInit, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { EventObservationService } from '../../services/event-observation.service';
import { EVENT_TYPES, ObservedEventView } from '../../models/event-observation-models';

/**
 * Top observed events: probability, evidence, occurrence counts. Triggers rescan / decay and emits a
 * request to view an event's prior history.
 */
@Component({
  selector: 'app-event-observation-list',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatTableModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatProgressBarModule, MatSnackBarModule
  ],
  template: `
    <mat-card class="eo-list">
      <div class="toolbar">
        <mat-form-field appearance="outline" class="fs">
          <mat-label>Fact sheet id</mat-label>
          <input matInput type="number" [(ngModel)]="factSheetId" (keyup.enter)="load()">
        </mat-form-field>
        <mat-form-field appearance="outline" class="type">
          <mat-label>Event type</mat-label>
          <mat-select [(ngModel)]="typeFilter" (selectionChange)="load()">
            <mat-option [value]="''">All</mat-option>
            <mat-option *ngFor="let t of eventTypes" [value]="t">{{ t }}</mat-option>
          </mat-select>
        </mat-form-field>
        <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
        <button mat-stroked-button color="primary" (click)="rescan()"><mat-icon>radar</mat-icon> Rescan</button>
        <button mat-stroked-button (click)="decay()"><mat-icon>trending_down</mat-icon> Decay</button>
      </div>

      <mat-progress-bar *ngIf="loading" mode="indeterminate"></mat-progress-bar>

      <table mat-table [dataSource]="events" *ngIf="events.length > 0" class="events-table">
        <ng-container matColumnDef="eventType">
          <th mat-header-cell *matHeaderCellDef>Type</th>
          <td mat-cell *matCellDef="let e"><span class="chip">{{ shortType(e.eventType) }}</span></td>
        </ng-container>
        <ng-container matColumnDef="eventKey">
          <th mat-header-cell *matHeaderCellDef>Event</th>
          <td mat-cell *matCellDef="let e" class="key">{{ e.eventKey }}</td>
        </ng-container>
        <ng-container matColumnDef="probability">
          <th mat-header-cell *matHeaderCellDef>Probability</th>
          <td mat-cell *matCellDef="let e">
            <div class="bar-wrap">
              <div class="bar-fill" [style.width.%]="e.probability * 100" [ngClass]="badge(e.probability)"></div>
              <span class="bar-label" [ngClass]="badge(e.probability)">{{ (e.probability * 100) | number:'1.1-1' }}%</span>
            </div>
          </td>
        </ng-container>
        <ng-container matColumnDef="evidence">
          <th mat-header-cell *matHeaderCellDef>Evidence</th>
          <td mat-cell *matCellDef="let e">{{ e.evidenceStrength | number:'1.0-1' }}</td>
        </ng-container>
        <ng-container matColumnDef="occurrences">
          <th mat-header-cell *matHeaderCellDef>Occ / Opp</th>
          <td mat-cell *matCellDef="let e">{{ e.occurrenceCount }} / {{ e.opportunityCount }}</td>
        </ng-container>
        <ng-container matColumnDef="lastObservedAt">
          <th mat-header-cell *matHeaderCellDef>Last observed</th>
          <td mat-cell *matCellDef="let e">{{ e.lastObservedAt | date:'short' }}</td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let e">
            <button mat-icon-button title="View prior history" (click)="viewHistory.emit(e.eventKey)">
              <mat-icon>show_chart</mat-icon>
            </button>
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
        <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
      </table>

      <div *ngIf="!loading && events.length === 0" class="empty">
        No observed events yet. Run a crawl, or press <strong>Rescan</strong> to scan the graph now.
      </div>
    </mat-card>
  `,
  styles: [`
    .eo-list { padding: 16px; }
    .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
    .fs { width: 140px; } .type { width: 220px; }
    .events-table { width: 100%; }
    .key { font-family: monospace; font-size: 12px; max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chip { background: #eef; border-radius: 10px; padding: 2px 8px; font-size: 11px; }
    .bar-wrap { position: relative; background: #f0f0f0; border-radius: 4px; height: 18px; width: 130px; }
    .bar-fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 4px; opacity: 0.35; }
    .bar-label { position: absolute; left: 6px; font-size: 11px; line-height: 18px; }
    .bar-fill.high, .bar-label.high { color: #2e7d32; background-color: #66bb6a; }
    .bar-fill.medium, .bar-label.medium { color: #b26a00; background-color: #ffb74d; }
    .bar-fill.low, .bar-label.low { color: #c62828; background-color: #ef5350; }
    .bar-label.high { background: none; } .bar-label.medium { background: none; } .bar-label.low { background: none; }
    .empty { color: #888; padding: 24px 0; }
  `]
})
export class EventObservationListComponent implements OnInit {

  @Output() viewHistory = new EventEmitter<string>();

  events: ObservedEventView[] = [];
  displayedColumns = ['eventType', 'eventKey', 'probability', 'evidence', 'occurrences', 'lastObservedAt', 'actions'];
  eventTypes = EVENT_TYPES;
  factSheetId?: number;
  typeFilter = '';
  loading = false;

  constructor(private svc: EventObservationService, private snack: MatSnackBar) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.svc.listEvents(this.factSheetId ?? undefined, this.typeFilter || undefined, 100).subscribe({
      next: e => { this.events = e || []; this.loading = false; },
      error: () => { this.loading = false; this.snack.open('Failed to load events', 'OK', { duration: 3000 }); }
    });
  }

  rescan(): void {
    this.svc.rescan(this.factSheetId ?? undefined).subscribe({
      next: r => {
        this.snack.open(`Observed ${r.entitiesObserved + r.connectionsObserved} events`, 'OK', { duration: 2500 });
        this.load();
      },
      error: () => this.snack.open('Rescan failed', 'OK', { duration: 3000 })
    });
  }

  decay(): void {
    this.svc.decay().subscribe({
      next: r => { this.snack.open(`Decayed ${r.decayed} priors`, 'OK', { duration: 2500 }); this.load(); },
      error: () => this.snack.open('Decay failed', 'OK', { duration: 3000 })
    });
  }

  badge(p: number): string {
    return p >= 0.7 ? 'high' : p >= 0.4 ? 'medium' : 'low';
  }

  shortType(t: string): string {
    switch (t) {
      case 'ENTITY_OCCURRENCE': return 'entity';
      case 'CONNECTION_OCCURRENCE': return 'connection';
      case 'PROCESS_STEP_OCCURRENCE': return 'step';
      default: return 'custom';
    }
  }
}
