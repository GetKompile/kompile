/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component, EventEmitter, OnInit, Output, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { EventObservationListComponent } from './event-observation-list.component';
import { EventObservationConfigComponent } from './event-observation-config.component';
import { EventPriorHistoryComponent } from './event-prior-history.component';
import { EventPriorInspectorComponent } from './event-prior-inspector.component';
import { EventObservationService } from '@shared/services/event-observation.service';
import { EventObservationConfig, OPPORTUNITY_MODELS, ScanResult } from '@shared/models/event-observation-models';

/**
 * Event Observation panel (lives under Graphs → Event Observation). Surfaces observed events, their
 * empirical Beta-Binomial priors (the same priors that feed the Bayesian / MEBN attribution layer),
 * an event's prior time-series, and the JSON configuration — fronted by a guided setup wizard and an
 * always-available "how it works" explainer so the probabilistic plumbing is approachable.
 */
@Component({
  selector: 'app-event-observation-dashboard',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatTabsModule, MatIconModule, MatButtonModule, MatCheckboxModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatExpansionModule, MatProgressBarModule,
    MatStepperModule, MatSnackBarModule,
    EventObservationListComponent, EventObservationConfigComponent, EventPriorHistoryComponent,
    EventPriorInspectorComponent
  ],
  template: `
    <div class="eo-dashboard">
      <div class="eo-header">
        <mat-icon>query_stats</mat-icon>
        <div>
          <h2>Event Observation &amp; Empirical Priors</h2>
          <p>Kompile watches what actually shows up in your crawled knowledge graph — which entities recur,
             which connections keep forming — and turns those frequencies into <strong>Beta-Binomial priors</strong>.
             Those priors are the empirical starting point for the Causal Attribution (Bayesian / MEBN) graphs
             and for generated business processes.</p>
        </div>
      </div>

      <mat-expansion-panel class="how-it-works" [expanded]="true">
        <mat-expansion-panel-header>
          <mat-panel-title><mat-icon>school</mat-icon>&nbsp; How event observation works</mat-panel-title>
        </mat-expansion-panel-header>
        <div class="hiw-flow">
          <div class="hiw-step">
            <span class="n">1</span>
            <div><strong>Observe</strong>
              <p>Every crawl (or graph mutation) emits events: an entity occurred, a connection formed, a process step ran.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">2</span>
            <div><strong>Count</strong>
              <p>Each event accrues <em>occurrences</em> over <em>opportunities</em>. The opportunity model sets the denominator.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">3</span>
            <div><strong>Estimate</strong>
              <p>A Beta(&alpha;,&beta;) prior is updated to a probability with a credible interval — stronger evidence ⇒ tighter interval.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">4</span>
            <div><strong>Feed</strong>
              <p>Priors blend into Causal Attribution inference and decay over a half-life so they track recent reality.</p></div>
          </div>
        </div>
        <p class="hiw-foot">Each probability <strong>is</strong> the weight the Causal Attribution layer uses as that node's
           <em>prior</em>. <strong>Guided setup</strong> tunes how priors are learned and scans the graph;
           <strong>Inspect &amp; Assign</strong> looks up the weight on any node or connection and lets you set it by hand;
           the remaining tabs show raw observed events, an event's prior over time, and the full JSON config.</p>
      </mat-expansion-panel>

      <mat-tab-group [(selectedIndex)]="tab" class="eo-tabs">
        <mat-tab label="Guided setup">
          <mat-stepper #wizard class="eo-wizard" [animationDuration]="'200ms'">

            <!-- Step 1: Scope -->
            <mat-step [editable]="true">
              <ng-template matStepLabel>Scope</ng-template>
              <div class="step-body">
                <div class="wizard-intro">
                  <mat-icon>radar</mat-icon>
                  <div>
                    <strong>What should Kompile observe?</strong>
                    <p>Pick which kinds of events become priors, and (optionally) restrict scanning to one fact sheet.
                       Turning a category off stops new priors of that kind from being learned.</p>
                  </div>
                </div>

                <div class="setting">
                  <mat-checkbox [(ngModel)]="cfg.enabled" color="primary">Event observation enabled</mat-checkbox>
                  <p class="setting-hint">Master switch. When off, crawls won't record any events.</p>
                </div>

                <div class="toggles">
                  <mat-checkbox [(ngModel)]="cfg.entityEventsEnabled" [disabled]="!cfg.enabled" color="primary">Entity occurrences</mat-checkbox>
                  <mat-checkbox [(ngModel)]="cfg.connectionEventsEnabled" [disabled]="!cfg.enabled" color="primary">Connection occurrences</mat-checkbox>
                  <mat-checkbox [(ngModel)]="cfg.processStepEventsEnabled" [disabled]="!cfg.enabled" color="primary">Process-step occurrences</mat-checkbox>
                  <mat-checkbox [(ngModel)]="cfg.fineGrainedMutationsEnabled" [disabled]="!cfg.enabled" color="primary">Fine-grained mutation updates</mat-checkbox>
                </div>

                <mat-form-field appearance="outline" class="fs-field">
                  <mat-label>Limit to fact sheet id (optional)</mat-label>
                  <input matInput type="number" [(ngModel)]="scopeFactSheetId" placeholder="leave blank to scan all">
                  <mat-hint>Only this fact sheet's graph is scanned in the Scan step.</mat-hint>
                </mat-form-field>

                <div class="step-actions">
                  <span class="spacer"></span>
                  <button mat-raised-button color="primary" matStepperNext>Next <mat-icon>arrow_forward</mat-icon></button>
                </div>
              </div>
            </mat-step>

            <!-- Step 2: Priors -->
            <mat-step [editable]="true">
              <ng-template matStepLabel>Priors</ng-template>
              <div class="step-body">
                <div class="wizard-intro">
                  <mat-icon>functions</mat-icon>
                  <div>
                    <strong>How each probability is estimated</strong>
                    <p>An event's probability is a Beta-Binomial estimate: a Beta(&alpha;,&beta;) prior updated by observed
                       occurrences / opportunities. Stronger evidence pulls the estimate away from the prior; the half-life
                       slowly forgets stale evidence.</p>
                  </div>
                </div>

                <mat-form-field appearance="outline" class="full">
                  <mat-label>Opportunity (denominator) model</mat-label>
                  <mat-select [(ngModel)]="cfg.opportunityModel">
                    <mat-option *ngFor="let m of models" [value]="m">{{ m }}</mat-option>
                  </mat-select>
                  <mat-hint>{{ opportunityHint(cfg.opportunityModel) }}</mat-hint>
                </mat-form-field>

                <div class="form-grid">
                  <mat-form-field appearance="outline">
                    <mat-label>Prior &alpha; (pseudo-successes)</mat-label>
                    <input matInput type="number" [(ngModel)]="cfg.priorAlpha" min="0">
                    <mat-hint>Higher ⇒ stronger belief the event happens.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Prior &beta; (pseudo-failures)</mat-label>
                    <input matInput type="number" [(ngModel)]="cfg.priorBeta" min="0">
                    <mat-hint>Higher ⇒ stronger belief it doesn't.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Blend k</mat-label>
                    <input matInput type="number" [(ngModel)]="cfg.priorBlendK" min="0">
                    <mat-hint>How many observations before evidence outweighs the prior.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Min evidence for prior</mat-label>
                    <input matInput type="number" [(ngModel)]="cfg.minEvidenceForPrior" min="0">
                    <mat-hint>Events with less evidence are treated as too sparse.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Half-life (days)</mat-label>
                    <input matInput type="number" [(ngModel)]="cfg.halfLifeDays" min="0">
                    <mat-hint>Age at which an observation's weight halves. 0 = never decay.</mat-hint>
                  </mat-form-field>
                </div>

                <div class="setting">
                  <mat-checkbox [(ngModel)]="cfg.decayOnEachCrawl" color="primary">Apply decay on each crawl</mat-checkbox>
                  <p class="setting-hint">Re-weights every prior toward recent evidence automatically when a crawl runs.</p>
                </div>

                <div class="step-actions">
                  <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                  <span class="spacer"></span>
                  <button mat-raised-button color="primary" matStepperNext>Next <mat-icon>arrow_forward</mat-icon></button>
                </div>
              </div>
            </mat-step>

            <!-- Step 3: Scan -->
            <mat-step [editable]="true">
              <ng-template matStepLabel>Scan</ng-template>
              <div class="step-body">
                <div class="wizard-intro">
                  <mat-icon>play_circle</mat-icon>
                  <div>
                    <strong>Save &amp; learn priors now</strong>
                    <p>This saves the settings above, then scans the
                       {{ scopeFactSheetId != null ? 'selected fact sheet' : 'whole graph' }}
                       to (re)compute priors from what is already indexed. Future crawls keep them up to date automatically.</p>
                  </div>
                </div>

                <button mat-raised-button color="primary" [disabled]="saving" (click)="saveAndScan()">
                  <mat-icon>radar</mat-icon> Save &amp; scan now
                </button>
                <mat-progress-bar *ngIf="saving" mode="indeterminate" class="scan-bar"></mat-progress-bar>

                <div class="step-actions">
                  <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                </div>
              </div>
            </mat-step>

            <!-- Step 4: Done -->
            <mat-step [editable]="true">
              <ng-template matStepLabel>Done</ng-template>
              <div class="step-body">
                <div class="wizard-intro success-intro" *ngIf="scanResult; else notScanned">
                  <mat-icon>check_circle</mat-icon>
                  <div>
                    <strong>Priors updated</strong>
                    <p>Observed <strong>{{ scanResult.entitiesObserved || 0 }}</strong> entity events and
                       <strong>{{ scanResult.connectionsObserved || 0 }}</strong> connection events. They now feed the
                       Causal Attribution graphs.</p>
                  </div>
                </div>
                <ng-template #notScanned>
                  <div class="wizard-intro">
                    <mat-icon>info</mat-icon>
                    <div><strong>Nothing scanned yet</strong>
                      <p>Go back to the Scan step and press <em>Save &amp; scan now</em> to learn priors.</p></div>
                  </div>
                </ng-template>
                <div class="done-actions">
                  <button mat-raised-button color="primary" (click)="goToEvents()">
                    <mat-icon>table_rows</mat-icon> View observed events
                  </button>
                  <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
                </div>
              </div>
            </mat-step>
          </mat-stepper>
        </mat-tab>

        <mat-tab label="Inspect &amp; Assign">
          <app-event-prior-inspector
            (openInAttribution)="openInAttribution.emit($event)"
            (viewHistory)="onViewHistory($event)">
          </app-event-prior-inspector>
        </mat-tab>

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
    .eo-header p { margin: 0; color: #888; font-size: 13px; max-width: 920px; line-height: 1.5; }

    .how-it-works { margin: 12px 12px 8px; box-shadow: none; border: 1px solid rgba(63,81,181,0.18); border-radius: 8px; }
    .how-it-works mat-panel-title { display: flex; align-items: center; font-size: 13px; font-weight: 600; color: #3f51b5; }
    .how-it-works mat-panel-title mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .hiw-flow { display: flex; align-items: stretch; flex-wrap: wrap; gap: 6px; padding: 4px 0 10px; }
    .hiw-step { display: flex; gap: 8px; align-items: flex-start; flex: 1 1 180px; min-width: 180px;
      background: rgba(63,81,181,0.05); border: 1px solid rgba(63,81,181,0.12); border-radius: 8px; padding: 8px 10px; }
    .hiw-step .n { flex-shrink: 0; width: 20px; height: 20px; border-radius: 50%; background: #3f51b5; color: #fff;
      font-size: 12px; line-height: 20px; text-align: center; font-weight: 600; }
    .hiw-step strong { display: block; font-size: 12.5px; margin-bottom: 2px; }
    .hiw-step p { margin: 0; font-size: 12px; color: #777; line-height: 1.4; }
    .hiw-arrow { align-self: center; color: #bbb; }
    .hiw-foot { margin: 4px 0 0; font-size: 12px; color: #888; line-height: 1.5; }

    .eo-tabs { margin-top: 4px; }
    .eo-wizard { background: transparent; padding-top: 4px; }
    .step-body { padding: 8px 4px 4px; max-width: 820px; }

    .wizard-intro {
      display: flex; gap: 12px; align-items: flex-start;
      background: rgba(63,81,181,0.06); border: 1px solid rgba(63,81,181,0.2);
      border-radius: 8px; padding: 12px 14px; margin-bottom: 16px;
    }
    .wizard-intro mat-icon { color: #3f51b5; flex-shrink: 0; }
    .wizard-intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .wizard-intro p { margin: 0; font-size: 12.5px; color: #777; line-height: 1.5; }
    .success-intro { background: rgba(76,175,80,0.08); border-color: rgba(76,175,80,0.3); }
    .success-intro mat-icon { color: #43a047; }

    .setting { padding: 8px 0; }
    .setting-hint { margin: 4px 0 0; font-size: 12px; color: #999; line-height: 1.4; }
    .toggles { display: flex; flex-direction: column; gap: 8px; padding: 6px 0 12px; }
    .fs-field { width: 320px; max-width: 100%; }
    .full { width: 100%; }
    .form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px 16px; padding: 8px 0; }
    .scan-bar { margin-top: 12px; max-width: 360px; }

    .step-actions { display: flex; align-items: center; gap: 8px; padding-top: 12px; }
    .done-actions { display: flex; gap: 12px; padding-top: 8px; }
    .spacer { flex: 1; }
  `]
})
export class EventObservationDashboardComponent implements OnInit {

  /** mat-tab index: 0 = Guided setup, 1 = Inspect & Assign, 2 = Observed Events, 3 = Event Priors, 4 = Configuration. */
  tab = 0;
  selectedKey = '';

  /** Bubbles a node id up to the Graphs hub to seed the Causal Attribution wizard. */
  @Output() openInAttribution = new EventEmitter<string>();

  cfg: EventObservationConfig = {};
  models = OPPORTUNITY_MODELS;
  scopeFactSheetId?: number;
  saving = false;
  scanResult: ScanResult | null = null;

  @ViewChild('wizard') wizard?: MatStepper;

  constructor(private svc: EventObservationService, private snack: MatSnackBar) {}

  ngOnInit(): void {
    this.loadConfig();
  }

  loadConfig(): void {
    this.svc.getConfig().subscribe({
      next: c => this.cfg = c || {},
      error: () => this.snack.open('Could not load event-observation config', 'OK', { duration: 3000 })
    });
  }

  opportunityHint(model?: string): string {
    switch (model) {
      case 'PRESENCE': return 'Counts each crawl/scan as one opportunity — "did we see it at all?". Simple and stable.';
      case 'RELATIVE_FREQUENCY': return 'Opportunities scale with how many comparable items were seen — "how often, relative to peers?".';
      case 'DECAYED_RATE': return 'Older observations are down-weighted by the half-life, so the prior tracks recent behaviour.';
      default: return 'Sets the denominator used when turning occurrence counts into a probability.';
    }
  }

  saveAndScan(): void {
    this.saving = true;
    this.svc.updateConfig(this.cfg).subscribe({
      next: () => {
        this.svc.rescan(this.scopeFactSheetId ?? undefined).subscribe({
          next: r => {
            this.scanResult = r;
            this.saving = false;
            const total = (r.entitiesObserved || 0) + (r.connectionsObserved || 0);
            this.snack.open(`Observed ${total} events`, 'OK', { duration: 2500 });
            setTimeout(() => this.wizard?.next(), 0);
          },
          error: () => { this.saving = false; this.snack.open('Scan failed', 'OK', { duration: 3000 }); }
        });
      },
      error: () => { this.saving = false; this.snack.open('Could not save configuration', 'OK', { duration: 3000 }); }
    });
  }

  goToEvents(): void {
    this.tab = 2;
  }

  onViewHistory(eventKey: string): void {
    this.selectedKey = eventKey;
    this.tab = 3;
  }
}
