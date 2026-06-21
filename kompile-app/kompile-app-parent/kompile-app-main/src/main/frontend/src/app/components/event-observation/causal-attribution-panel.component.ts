/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
import { Component, Input, OnChanges, SimpleChanges, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatStepper, MatStepperModule } from '@angular/material/stepper';
import { BayesianPanelComponent } from '../graph-visualizer/bayesian-panel.component';

/**
 * Causal Attribution panel (lives under Graphs → Causal Attribution). Gives the Bayesian / MEBN /
 * causal-attribution inference a first-class, self-explaining home: a "how it works" explainer, a
 * guided wizard that collects the target node + inference settings, and the {@link BayesianPanelComponent}
 * for the results. Priors are blended with empirical observed-event frequencies (see Event Observation).
 */
@Component({
  selector: 'app-causal-attribution-panel',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatFormFieldModule, MatInputModule,
    MatButtonModule, MatIconModule, MatExpansionModule, MatSlideToggleModule, MatStepperModule,
    BayesianPanelComponent
  ],
  template: `
    <div class="ca-panel">
      <div class="ca-header">
        <mat-icon>account_tree</mat-icon>
        <div>
          <h2>Causal Attribution &amp; Bayesian / MEBN</h2>
          <p>Ask <em>"why is this likely?"</em> of any node in the knowledge graph. Kompile builds a Bayesian
             network (optionally a multi-entity MEBN theory) around it and computes posterior probabilities,
             a most-probable explanation, sensitivities and what-ifs. Priors come from observed-event frequencies.</p>
        </div>
      </div>

      <mat-expansion-panel class="how-it-works" [expanded]="true">
        <mat-expansion-panel-header>
          <mat-panel-title><mat-icon>school</mat-icon>&nbsp; How causal attribution works</mat-panel-title>
        </mat-expansion-panel-header>
        <div class="hiw-flow">
          <div class="hiw-step">
            <span class="n">1</span>
            <div><strong>Seed</strong><p>You pick a target node — the event or entity you want to reason about.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">2</span>
            <div><strong>Build</strong><p>Neighbours within the chosen reach become network variables; causal edges become dependencies.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">3</span>
            <div><strong>Infer</strong><p>Empirical priors are updated to <em>posteriors</em> by variable elimination over the network.</p></div>
          </div>
          <mat-icon class="hiw-arrow">arrow_forward</mat-icon>
          <div class="hiw-step">
            <span class="n">4</span>
            <div><strong>Explain</strong><p>Read posteriors vs priors, run a most-probable explanation, sensitivity, or hypothetical what-ifs.</p></div>
          </div>
        </div>
        <p class="hiw-foot"><strong>MEBN</strong> (Multi-Entity Bayesian Network) groups variables by entity type into reusable
           fragments (MFrags) — better when the same relationship recurs across many entities. Plain Bayesian builds one
           flat network around the seed. Both share the same priors learned in <strong>Event Observation</strong>.</p>
      </mat-expansion-panel>

      <mat-stepper #wizard class="ca-wizard" [animationDuration]="'200ms'">

        <!-- Step 1: Target -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Target event</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>my_location</mat-icon>
              <div>
                <strong>Which node do you want to explain?</strong>
                <p>Paste a knowledge-graph node id. You can copy one from <em>Graphs → Entity Browser</em> or the
                   <em>Visualizer</em> (a node's id), or from a chat citation. Node ids may contain slashes — paste the whole thing.</p>
              </div>
            </div>

            <mat-form-field appearance="outline" class="node-field">
              <mat-label>Graph node id</mat-label>
              <input matInput [(ngModel)]="nodeIdInput" (keyup.enter)="runAndAdvance()"
                     placeholder="e.g. entity:acme-q3-revenue-miss">
              <mat-hint>The event/entity whose probability you want attributed.</mat-hint>
            </mat-form-field>

            <div class="step-actions">
              <span class="spacer"></span>
              <button mat-raised-button color="primary" matStepperNext [disabled]="!nodeIdInput.trim()">
                Next <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 2: Method & depth -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Method &amp; reach</ng-template>
          <div class="step-body">
            <div class="wizard-intro">
              <mat-icon>tune</mat-icon>
              <div>
                <strong>How should the network be built?</strong>
                <p>Reach controls how far from the seed node Kompile expands. Larger reach captures more context but
                   costs more to compute and can dilute the signal.</p>
              </div>
            </div>

            <div class="setting">
              <mat-slide-toggle [(ngModel)]="useMebn" color="primary">Use MEBN (multi-entity)</mat-slide-toggle>
              <p class="setting-hint">
                {{ useMebn
                   ? 'On: groups variables by entity type into reusable MFrags — best when relationships recur across many entities.'
                   : 'Off: builds one flat Bayesian network around the seed node.' }}
              </p>
            </div>

            <div class="form-grid">
              <mat-form-field appearance="outline">
                <mat-label>Max depth (hops from seed)</mat-label>
                <input matInput type="number" [(ngModel)]="maxDepth" min="1" max="8">
                <mat-hint>How many edges out to expand. 2–4 is usually enough.</mat-hint>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Max nodes (network size cap)</mat-label>
                <input matInput type="number" [(ngModel)]="maxNodes" min="5" max="500">
                <mat-hint>Upper bound on variables, to keep inference fast.</mat-hint>
              </mat-form-field>
            </div>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
              <span class="spacer"></span>
              <button mat-raised-button color="primary" (click)="runAndAdvance()" [disabled]="!nodeIdInput.trim()">
                <mat-icon>psychology</mat-icon> Run inference
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 3: Results & interpretation -->
        <mat-step [editable]="true">
          <ng-template matStepLabel>Results</ng-template>
          <div class="step-body">
            <div class="run-bar">
              <div class="run-target" *ngIf="activeNodeId">
                <span class="run-label">Target</span>
                <code>{{ activeNodeId }}</code>
                <span class="run-method">{{ useMebn ? 'MEBN' : 'Bayesian' }} · depth {{ maxDepth }} · ≤{{ maxNodes }} nodes</span>
              </div>
              <span class="spacer"></span>
              <button mat-stroked-button (click)="run()" [disabled]="!nodeIdInput.trim()">
                <mat-icon>refresh</mat-icon> Re-run
              </button>
            </div>

            <app-bayesian-panel
              [nodeId]="activeNodeId || null"
              [useMebn]="useMebn"
              [maxDepth]="maxDepth"
              [maxNodes]="maxNodes">
            </app-bayesian-panel>

            <mat-expansion-panel class="legend">
              <mat-expansion-panel-header>
                <mat-panel-title><mat-icon>menu_book</mat-icon>&nbsp; How to read these results</mat-panel-title>
              </mat-expansion-panel-header>
              <ul class="legend-list">
                <li><strong>Posterior</strong> — the probability after inference. The bar is the posterior; the tick mark is the <strong>prior</strong> (the empirical starting belief).</li>
                <li><strong>Prior → Posterior</strong> — a big shift means the network's evidence strongly moved the belief.</li>
                <li><strong>Sensitivity</strong> — how much that variable influences the seed's posterior; high sensitivity ⇒ a strong lever.</li>
                <li><strong>Most Probable Explanation</strong> — the single most-likely TRUE/FALSE assignment across all variables together.</li>
                <li><strong>What-If</strong> — pin variables TRUE/FALSE and re-infer to test a hypothesis.</li>
                <li><strong>MFrag / RESIDENT / INPUT</strong> (MEBN only) — which entity-type fragment a variable belongs to and its role in it.</li>
              </ul>
            </mat-expansion-panel>

            <div class="step-actions">
              <button mat-stroked-button matStepperPrevious><mat-icon>arrow_back</mat-icon> Back</button>
            </div>
          </div>
        </mat-step>
      </mat-stepper>
    </div>
  `,
  styles: [`
    .ca-panel { padding: 12px; }
    .ca-header { display: flex; gap: 14px; align-items: flex-start; margin-bottom: 8px; }
    .ca-header mat-icon { font-size: 30px; width: 30px; height: 30px; color: #3f51b5; }
    .ca-header h2 { margin: 0 0 4px; }
    .ca-header p { margin: 0; color: #888; font-size: 13px; max-width: 920px; line-height: 1.5; }

    .how-it-works { margin: 0 0 8px; box-shadow: none; border: 1px solid rgba(63,81,181,0.18); border-radius: 8px; }
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

    .ca-wizard { background: transparent; }
    .step-body { padding: 8px 4px 4px; max-width: 860px; }

    .wizard-intro {
      display: flex; gap: 12px; align-items: flex-start;
      background: rgba(63,81,181,0.06); border: 1px solid rgba(63,81,181,0.2);
      border-radius: 8px; padding: 12px 14px; margin-bottom: 16px;
    }
    .wizard-intro mat-icon { color: #3f51b5; flex-shrink: 0; }
    .wizard-intro strong { display: block; font-size: 13px; margin-bottom: 2px; }
    .wizard-intro p { margin: 0; font-size: 12.5px; color: #777; line-height: 1.5; }

    .setting { padding: 8px 0; }
    .setting-hint { margin: 4px 0 0; font-size: 12px; color: #999; line-height: 1.4; }
    .node-field { width: 460px; max-width: 100%; }
    .form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px 16px; padding: 8px 0; }

    .run-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
    .run-target { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .run-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #999; }
    .run-target code { font-size: 12px; background: rgba(63,81,181,0.08); padding: 2px 8px; border-radius: 4px;
      max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .run-method { font-size: 11px; color: #888; }

    .legend { margin-top: 12px; box-shadow: none; border: 1px solid #e0e0e0; border-radius: 8px; }
    .legend mat-panel-title { display: flex; align-items: center; font-size: 13px; font-weight: 600; }
    .legend mat-panel-title mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .legend-list { margin: 0; padding-left: 18px; }
    .legend-list li { font-size: 12.5px; color: #666; line-height: 1.6; }

    .step-actions { display: flex; align-items: center; gap: 8px; padding-top: 12px; }
    .spacer { flex: 1; }
  `]
})
export class CausalAttributionPanelComponent implements OnChanges {

  /** When set by the host (e.g. Event Observation's "Use in Causal Attribution"), prefill + run. */
  @Input() seedNodeId: string | null = null;

  nodeIdInput = '';
  activeNodeId = '';
  useMebn = true;
  maxDepth = 3;
  maxNodes = 100;

  @ViewChild('wizard') wizard?: MatStepper;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['seedNodeId'] && this.seedNodeId) {
      this.nodeIdInput = this.seedNodeId;
      this.run();
      // Jump to the results step once the view (and the stepper) exist.
      setTimeout(() => { if (this.wizard) { this.wizard.selectedIndex = 2; } }, 0);
    }
  }

  run(): void {
    this.activeNodeId = this.nodeIdInput.trim();
  }

  runAndAdvance(): void {
    if (!this.nodeIdInput.trim()) {
      return;
    }
    this.run();
    setTimeout(() => this.wizard?.next(), 0);
  }
}
