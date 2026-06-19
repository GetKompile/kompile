/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { EventObservationListComponent } from './event-observation-list.component';
import { EventObservationConfigComponent } from './event-observation-config.component';
import { EventPriorHistoryComponent } from './event-prior-history.component';

/**
 * Tools → Event Observation panel. Surfaces observed events, their empirical Beta-Binomial priors
 * (the same priors that now feed the Bayesian/MEBN attribution layer), an event's prior time-series,
 * and the JSON configuration.
 */
@Component({
  selector: 'app-event-observation-dashboard',
  standalone: true,
  imports: [
    CommonModule, MatTabsModule, MatIconModule,
    EventObservationListComponent, EventObservationConfigComponent, EventPriorHistoryComponent
  ],
  template: `
    <div class="eo-dashboard">
      <div class="eo-header">
        <mat-icon>query_stats</mat-icon>
        <div>
          <h2>Event Observation &amp; Empirical Priors</h2>
          <p>Real events observed from crawls — entities by how often they occur, connections that produce
             them — maintained as up-to-date Beta-Binomial priors that drive the Bayesian/MEBN graphs and
             generated business processes.</p>
        </div>
      </div>

      <mat-tab-group [(selectedIndex)]="tab">
        <mat-tab label="Observed Events">
          <app-event-observation-list (viewHistory)="onViewHistory($event)"></app-event-observation-list>
        </mat-tab>
        <mat-tab label="Event Priors">
          <app-event-prior-history [eventKey]="selectedKey"></app-event-prior-history>
        </mat-tab>
        <mat-tab label="Configuration">
          <app-event-observation-config></app-event-observation-config>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .eo-dashboard { padding: 8px; }
    .eo-header { display: flex; gap: 14px; align-items: flex-start; padding: 8px 12px 0; }
    .eo-header mat-icon { font-size: 32px; width: 32px; height: 32px; color: #3f51b5; }
    .eo-header h2 { margin: 0 0 4px; }
    .eo-header p { margin: 0; color: #888; font-size: 13px; max-width: 880px; }
  `]
})
export class EventObservationDashboardComponent {

  tab = 0;
  selectedKey = '';

  onViewHistory(eventKey: string): void {
    this.selectedKey = eventKey;
    this.tab = 1;
  }
}
