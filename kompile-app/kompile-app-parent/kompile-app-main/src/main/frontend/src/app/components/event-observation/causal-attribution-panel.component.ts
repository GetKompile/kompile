/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { BayesianPanelComponent } from '../graph-visualizer/bayesian-panel.component';

/**
 * Gives the previously-buried Bayesian / MEBN / causal-attribution UI a first-class home under
 * Tools. Wraps the existing {@link BayesianPanelComponent} with a graph-node picker so it works
 * outside the graph canvas. The posteriors/priors it shows now reflect observed event frequencies
 * (the empirical priors flow into the same inference).
 */
@Component({
  selector: 'app-causal-attribution-panel',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, BayesianPanelComponent
  ],
  template: `
    <mat-card class="ca-panel">
      <div class="ca-header">
        <mat-icon>account_tree</mat-icon>
        <div>
          <h2>Causal Attribution &amp; Bayesian / MEBN</h2>
          <p>Probabilistic inference over the knowledge graph — posteriors vs priors, MEBN structure,
             most-probable-explanation, sensitivity and what-if. Priors are blended with empirical
             observed-event frequencies.</p>
        </div>
      </div>
      <div class="lookup">
        <mat-form-field appearance="outline" class="node-field">
          <mat-label>Graph node id</mat-label>
          <input matInput [(ngModel)]="nodeIdInput" (keyup.enter)="apply()"
                 placeholder="paste a KG node id to run inference on">
        </mat-form-field>
        <button mat-raised-button color="primary" (click)="apply()">
          <mat-icon>psychology</mat-icon> Run inference
        </button>
      </div>
      <app-bayesian-panel [nodeId]="activeNodeId"></app-bayesian-panel>
    </mat-card>
  `,
  styles: [`
    .ca-panel { padding: 16px; }
    .ca-header { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 12px; }
    .ca-header mat-icon { font-size: 30px; width: 30px; height: 30px; color: #3f51b5; }
    .ca-header h2 { margin: 0 0 4px; }
    .ca-header p { margin: 0; color: #888; font-size: 13px; max-width: 880px; }
    .lookup { display: flex; gap: 12px; align-items: baseline; }
    .node-field { flex: 1; min-width: 340px; }
  `]
})
export class CausalAttributionPanelComponent {

  nodeIdInput = '';
  activeNodeId = '';

  apply(): void {
    this.activeNodeId = this.nodeIdInput.trim();
  }
}
