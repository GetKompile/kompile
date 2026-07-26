/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component, EventEmitter, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { EventObservationService } from '@shared/services/event-observation.service';
import { ConnectionPriorResponse, NodePriorResponse, ObservedEventStat, ObserveRequest } from '@shared/models/event-observation-models';

type Scope = 'entity' | 'connection';

/**
 * Makes the weights/probabilities that ride on the graph concrete: look up the prior the inference
 * layer actually uses for a node or connection (alongside the raw empirical stat that produced it),
 * assign / correct it by hand via {@code POST /observe}, then hand the node to Causal Attribution to
 * watch that prior turn into a posterior.
 */
@Component({
  selector: 'app-event-prior-inspector',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule,
    MatButtonToggleModule, MatIconModule, MatProgressBarModule, MatSnackBarModule
  ],
  template: `
    <div class="inspector">
      <div class="intro">
        <mat-icon>balance</mat-icon>
        <div>
          <strong>What is a "weight" on the graph?</strong>
          <p>Every entity and every connection carries one number: the <em>probability</em> it occurs, learned as a
             Beta-Binomial from <code>occurrences / opportunities</code>. That probability <strong>is</strong> the weight
             the Bayesian / MEBN layer uses as the <em>prior</em> for that node. Look one up, set it by hand, and send it
             straight into Causal Attribution to see it become a posterior.</p>
        </div>
      </div>

      <mat-card class="lookup-card">
        <div class="scope-row">
          <mat-button-toggle-group [(ngModel)]="scope" (ngModelChange)="reset()" aria-label="Scope">
            <mat-button-toggle value="entity"><mat-icon>circle</mat-icon> Entity node</mat-button-toggle>
            <mat-button-toggle value="connection"><mat-icon>trending_flat</mat-icon> Connection</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        <div class="fields" *ngIf="scope === 'entity'">
          <mat-form-field appearance="outline" class="grow">
            <mat-label>Entity node id</mat-label>
            <input matInput [(ngModel)]="nodeId" (keyup.enter)="inspect()" placeholder="paste a KG node id">
          </mat-form-field>
        </div>
        <div class="fields conn" *ngIf="scope === 'connection'">
          <mat-form-field appearance="outline" class="grow">
            <mat-label>Source node id</mat-label>
            <input matInput [(ngModel)]="source" (keyup.enter)="inspect()">
          </mat-form-field>
          <mat-form-field appearance="outline" class="edge">
            <mat-label>Edge type</mat-label>
            <input matInput [(ngModel)]="edgeType" (keyup.enter)="inspect()" placeholder="RELATED_TO">
          </mat-form-field>
          <mat-form-field appearance="outline" class="grow">
            <mat-label>Target node id</mat-label>
            <input matInput [(ngModel)]="target" (keyup.enter)="inspect()">
          </mat-form-field>
        </div>

        <button mat-raised-button color="primary" (click)="inspect()" [disabled]="!canInspect() || loading">
          <mat-icon>search</mat-icon> Inspect weight
        </button>
        <mat-progress-bar *ngIf="loading" mode="indeterminate" class="bar"></mat-progress-bar>
      </mat-card>

      <!-- Results -->
      <mat-card class="result-card" *ngIf="inspected">
        <div *ngIf="!hasPrior && !stat" class="no-prior">
          <mat-icon>help_outline</mat-icon>
          <div>
            <strong>No weight on the graph yet</strong>
            <p>This {{ scope }} has never been observed, or it is below the min-evidence threshold, so the inference
               layer falls back to the configured default prior. Assign one below to give it an empirical weight.</p>
          </div>
        </div>

        <div *ngIf="hasPrior || stat">
          <div class="headline">
            <span class="hl-label">Prior used by inference</span>
            <div class="bar-wrap big">
              <div class="bar-fill" [style.width.%]="pct(prior)" [ngClass]="badge(prior)"></div>
              <span class="bar-num">{{ pct(prior) }}%</span>
            </div>
            <span class="hl-note">this is the weight the Bayesian / MEBN network starts from for this {{ scope }}</span>
          </div>

          <div class="stat-grid" *ngIf="stat">
            <div class="stat">
              <span class="k">Empirical probability</span>
              <span class="v">{{ (stat.probability * 100) | number:'1.1-1' }}%</span>
              <span class="sub">running Beta-Binomial mean</span>
            </div>
            <div class="stat">
              <span class="k">Evidence</span>
              <span class="v">{{ stat.occurrences }} / {{ stat.opportunities }}</span>
              <span class="sub">occurrences / opportunities</span>
            </div>
            <div class="stat">
              <span class="k">Evidence strength</span>
              <span class="v">{{ stat.evidenceStrength | number:'1.0-1' }}</span>
              <span class="sub">how much it outweighs the prior</span>
            </div>
            <div class="stat">
              <span class="k">95% credible interval</span>
              <span class="v">{{ (stat.credibleLow * 100) | number:'1.0-0' }}–{{ (stat.credibleHigh * 100) | number:'1.0-0' }}%</span>
              <span class="sub">where the true rate likely sits</span>
            </div>
            <div class="stat" *ngIf="stat.lastObservedAt">
              <span class="k">Last observed</span>
              <span class="v">{{ stat.lastObservedAt | date:'short' }}</span>
              <span class="sub">decays toward the prior over the half-life</span>
            </div>
            <div class="stat" *ngIf="eventKey">
              <span class="k">Event key</span>
              <span class="v key">{{ eventKey }}</span>
              <span class="sub">how it is stored</span>
            </div>
          </div>

          <div class="use-row">
            <button mat-stroked-button color="primary" (click)="useInAttribution()">
              <mat-icon>psychology</mat-icon> Use in Causal Attribution
            </button>
            <button mat-stroked-button (click)="emitViewHistory()" [disabled]="!eventKey">
              <mat-icon>show_chart</mat-icon> View prior over time
            </button>
          </div>
          <p class="use-hint">
            "Use in Causal Attribution" seeds the {{ scope === 'entity' ? 'node' : 'target node' }} into the inference
            wizard — you'll see this prior on the left and the computed posterior on the right.
          </p>
        </div>
      </mat-card>

      <!-- Assign -->
      <mat-card class="assign-card" *ngIf="inspected">
        <h3><mat-icon>edit</mat-icon> Assign / correct the weight</h3>
        <p class="hint">Records a manual observation (tagged <code>MANUAL</code>) and folds it into the Beta-Binomial —
          the same maths a crawl uses. Set occurrences out of opportunities; e.g. <code>7 / 10</code> teaches ~70%.</p>
        <div class="assign-fields">
          <mat-form-field appearance="outline">
            <mat-label>Occurrences</mat-label>
            <input matInput type="number" [(ngModel)]="occurrences" min="0">
          </mat-form-field>
          <span class="slash">/</span>
          <mat-form-field appearance="outline">
            <mat-label>Opportunities</mat-label>
            <input matInput type="number" [(ngModel)]="opportunities" min="1">
          </mat-form-field>
          <span class="preview" *ngIf="opportunities > 0">
            ≈ {{ (occurrences / opportunities * 100) | number:'1.0-1' }}%
          </span>
          <button mat-raised-button color="primary" (click)="assign()" [disabled]="saving">
            <mat-icon>add_task</mat-icon> Record observation
          </button>
        </div>
      </mat-card>
    </div>
  `,
  styles: [`
    .inspector { padding: 8px 4px; max-width: 900px; }
    .intro { display: flex; gap: 12px; align-items: flex-start;
      background: rgba(63,81,181,0.06); border: 1px solid rgba(63,81,181,0.2); border-radius: 8px;
      padding: 12px 14px; margin-bottom: 14px; }
    .intro mat-icon { color: #3f51b5; flex-shrink: 0; }
    .intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .intro p { margin: 0; font-size: 12.5px; color: #777; line-height: 1.5; }
    .intro code { background: rgba(0,0,0,0.06); padding: 1px 5px; border-radius: 3px; font-size: 11px; }

    .lookup-card, .result-card, .assign-card { padding: 14px 16px; margin-bottom: 12px; }
    .scope-row { margin-bottom: 12px; }
    .scope-row mat-icon { font-size: 16px; width: 16px; height: 16px; vertical-align: middle; }
    .fields { display: flex; gap: 12px; align-items: baseline; flex-wrap: wrap; }
    .fields .grow { flex: 1; min-width: 240px; }
    .fields .edge { width: 180px; }
    .bar { margin-top: 10px; max-width: 360px; }

    .no-prior { display: flex; gap: 12px; align-items: flex-start; color: #8a6d3b; }
    .no-prior mat-icon { color: #e0a800; }
    .no-prior strong { display: block; font-size: 13px; } .no-prior p { margin: 2px 0 0; font-size: 12.5px; color: #888; line-height: 1.5; }

    .headline { margin-bottom: 14px; }
    .hl-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #999; }
    .hl-note { font-size: 11.5px; color: #999; }
    .bar-wrap { position: relative; background: #f0f0f0; border-radius: 5px; height: 22px; margin: 4px 0; }
    .bar-wrap.big { height: 26px; max-width: 520px; }
    .bar-fill { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 5px; opacity: 0.4; }
    .bar-num { position: absolute; left: 10px; line-height: 26px; font-weight: 600; font-size: 13px; }
    .bar-fill.high { background: #66bb6a; } .bar-fill.medium { background: #ffb74d; } .bar-fill.low { background: #ef5350; }

    .stat-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; margin-bottom: 12px; }
    .stat { display: flex; flex-direction: column; gap: 1px; background: #fafafa; border: 1px solid #eee; border-radius: 6px; padding: 8px 10px; }
    .stat .k { font-size: 11px; color: #888; }
    .stat .v { font-size: 16px; font-weight: 600; color: #333; }
    .stat .v.key { font-size: 11px; font-family: monospace; font-weight: 500; word-break: break-all; }
    .stat .sub { font-size: 10.5px; color: #aaa; }

    .use-row { display: flex; gap: 10px; flex-wrap: wrap; }
    .use-hint { font-size: 11.5px; color: #999; margin: 6px 0 0; }

    .assign-card h3 { display: flex; align-items: center; gap: 6px; font-size: 14px; margin: 0 0 4px; }
    .assign-card h3 mat-icon { font-size: 18px; width: 18px; height: 18px; color: #3f51b5; }
    .assign-card .hint { font-size: 12px; color: #888; margin: 0 0 10px; line-height: 1.5; }
    .assign-card code { background: rgba(0,0,0,0.06); padding: 1px 5px; border-radius: 3px; font-size: 11px; }
    .assign-fields { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
    .assign-fields mat-form-field { width: 140px; }
    .slash { font-size: 18px; color: #bbb; }
    .preview { font-size: 13px; font-weight: 600; color: #3f51b5; }
  `]
})
export class EventPriorInspectorComponent {

  /** Emits a node id to seed into the Causal Attribution wizard. */
  @Output() openInAttribution = new EventEmitter<string>();
  /** Emits an event key to open in the prior time-series view. */
  @Output() viewHistory = new EventEmitter<string>();

  scope: Scope = 'entity';
  nodeId = '';
  source = '';
  edgeType = '';
  target = '';

  loading = false;
  inspected = false;
  hasPrior = false;
  prior: number | null = null;
  stat: ObservedEventStat | null = null;
  eventKey = '';

  occurrences = 1;
  opportunities = 1;
  saving = false;

  constructor(private svc: EventObservationService, private snack: MatSnackBar) {}

  reset(): void {
    this.inspected = false;
    this.stat = null;
    this.prior = null;
    this.eventKey = '';
  }

  canInspect(): boolean {
    return this.scope === 'entity'
      ? !!this.nodeId.trim()
      : !!(this.source.trim() && this.edgeType.trim() && this.target.trim());
  }

  inspect(): void {
    if (!this.canInspect()) {
      return;
    }
    this.loading = true;
    const obs: Observable<NodePriorResponse | ConnectionPriorResponse> = this.scope === 'entity'
      ? this.svc.getNodePrior(this.nodeId.trim())
      : this.svc.getConnectionPrior(this.source.trim(), this.edgeType.trim(), this.target.trim());
    obs.subscribe({
      next: (r: NodePriorResponse | ConnectionPriorResponse) => {
        this.hasPrior = r.hasPrior;
        this.prior = r.prior;
        this.stat = r.stat;
        this.eventKey = r.stat?.eventKey || this.computeKey();
        this.inspected = true;
        this.loading = false;
      },
      error: () => { this.loading = false; this.snack.open('Lookup failed', 'OK', { duration: 3000 }); }
    });
  }

  private computeKey(): string {
    return this.scope === 'entity'
      ? `entity:${this.nodeId.trim()}`
      : `conn:${this.source.trim()}:${this.edgeType.trim()}:${this.target.trim()}`;
  }

  assign(): void {
    const occ = Number(this.occurrences);
    const opp = Number(this.opportunities);
    if (!(opp > 0) || occ < 0 || occ > opp) {
      this.snack.open('Need 0 ≤ occurrences ≤ opportunities, and opportunities > 0', 'OK', { duration: 4000 });
      return;
    }
    const req: ObserveRequest = this.scope === 'entity'
      ? { eventType: 'ENTITY_OCCURRENCE', subjectNodeId: this.nodeId.trim(), occurrences: occ, opportunities: opp }
      : {
          eventType: 'CONNECTION_OCCURRENCE', sourceNodeId: this.source.trim(),
          edgeType: this.edgeType.trim(), targetNodeId: this.target.trim(), occurrences: occ, opportunities: opp
        };
    this.saving = true;
    this.svc.observe(req).subscribe({
      next: () => {
        this.saving = false;
        this.snack.open('Observation recorded — weight updated', 'OK', { duration: 2500 });
        this.inspect();
      },
      error: () => { this.saving = false; this.snack.open('Could not record observation', 'OK', { duration: 3000 }); }
    });
  }

  pct(v: number | null | undefined): number {
    return v == null ? 0 : Math.round(v * 1000) / 10;
  }

  badge(v: number | null | undefined): string {
    const p = v ?? 0;
    return p >= 0.7 ? 'high' : p >= 0.4 ? 'medium' : 'low';
  }

  useInAttribution(): void {
    const id = this.scope === 'entity' ? this.nodeId.trim() : this.target.trim();
    if (id) {
      this.openInAttribution.emit(id);
    }
  }

  emitViewHistory(): void {
    if (this.eventKey) {
      this.viewHistory.emit(this.eventKey);
    }
  }
}
