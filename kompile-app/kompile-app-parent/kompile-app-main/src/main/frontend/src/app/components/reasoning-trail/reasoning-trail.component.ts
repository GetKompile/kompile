/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, OnChanges, SimpleChanges, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { SourceCitationComponent } from '../source-citation/source-citation.component';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  ReasoningTrailDto,
  DerivationTreeNodeDto,
  ConfidenceBreakdownDto,
  StrengthBand,
  confidenceToStrengthBand
} from '../../services/kb-grounding.service';
import { StrengthBadgeComponent } from '../strength-badge/strength-badge.component';

@Component({
  selector: 'app-reasoning-trail',
  standalone: true,
  imports: [
    CommonModule,
    MatProgressSpinnerModule,
    MatExpansionModule,
    MatChipsModule,
    MatIconModule,
    MatTooltipModule,
    StrengthBadgeComponent,
    SourceCitationComponent
  ],
  template: `
    <div class="reasoning-trail" [class.compact]="mode === 'compact'" [class.full]="mode === 'full'">
      <!-- Loading state -->
      <div *ngIf="loading" class="trail-loading">
        <mat-spinner diameter="20"></mat-spinner>
        <span>Loading reasoning trail...</span>
      </div>

      <!-- Empty state -->
      <div *ngIf="!loading && !trail" class="trail-empty">
        <mat-icon>psychology</mat-icon>
        <span>No reasoning trail loaded. Click "Why?" to explain.</span>
      </div>

      <!-- Trail content -->
      <ng-container *ngIf="!loading && trail">
        <!-- Stale notice: shown when a mutation occurred after this trace was computed -->
        <div *ngIf="stale" class="stale-notice">
          <mat-chip class="stale-chip" disabled>
            <mat-icon>update</mat-icon>
            Trace may be stale — re-grounding pending
          </mat-chip>
        </div>

        <!-- How this conclusion was reached -->
        <div class="inference-mode-row" *ngIf="trail.inferenceMode"
             style="display:flex;align-items:center;gap:4px;margin:2px 0 6px;font-size:12px;opacity:0.85;"
             matTooltip="The reasoning method that produced this conclusion">
          <mat-icon style="font-size:15px;width:15px;height:15px;">psychology_alt</mat-icon>
          <span><strong>Method:</strong> {{ modeLabel(trail.inferenceMode) }}</span>
        </div>

        <!-- Natural language summary -->
        <blockquote class="trail-summary" *ngIf="trail.naturalLanguageSummary">
          {{ trail.naturalLanguageSummary }}
        </blockquote>

        <!-- Confidence + band -->
        <div class="trail-confidence" *ngIf="trail.confidence !== undefined">
          <span class="conf-label">Confidence:</span>
          <span class="conf-value">{{ (trail.confidence * 100).toFixed(0) }}%</span>
          <app-strength-badge [band]="getBand(trail.confidence)"></app-strength-badge>
        </div>

        <!-- Confidence breakdown (full mode only): which signals contributed, and how much -->
        <div class="breakdown-block" *ngIf="mode === 'full' && trail.breakdown && breakdownSegments(trail.breakdown).length">
          <span class="section-label" matTooltip="Each signal's share of the overall confidence">Signal breakdown</span>
          <div class="breakdown-bar">
            <div class="breakdown-segment" *ngFor="let seg of breakdownSegments(trail.breakdown)"
                 [style.width]="seg.pct + '%'"
                 [style.background]="seg.color"
                 [matTooltip]="seg.label + ': ' + (seg.value * 100).toFixed(0) + '%'">
            </div>
          </div>
          <div class="breakdown-legend" style="display:flex;flex-wrap:wrap;gap:10px;margin-top:5px;font-size:11px;">
            <span class="legend-item" *ngFor="let seg of breakdownSegments(trail.breakdown)"
                  style="display:inline-flex;align-items:center;gap:4px;">
              <span [style.background]="seg.color" style="width:9px;height:9px;border-radius:2px;display:inline-block;"></span>
              {{ seg.label }} · {{ (seg.value * 100).toFixed(0) }}%
            </span>
          </div>
        </div>

        <!-- Derivation tree (collapsible) -->
        <mat-expansion-panel class="derivation-panel" *ngIf="trail.derivationTree">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon class="panel-icon">account_tree</mat-icon>
              Derivation Tree
            </mat-panel-title>
          </mat-expansion-panel-header>
          <div class="derivation-tree">
            <ng-container *ngTemplateOutlet="treeNode; context: { node: trail.derivationTree, depth: 0 }"></ng-container>
          </div>
        </mat-expansion-panel>

        <!-- Evidence chips -->
        <div class="evidence-section" *ngIf="trail.evidence && trail.evidence.length > 0">
          <span class="section-label">Evidence:</span>
          <mat-chip-set>
            <mat-chip *ngFor="let e of evidenceSlice" class="evidence-chip" [matTooltip]="e">
              {{ e.length > 40 ? (e | slice:0:40) + '...' : e }}
            </mat-chip>
          </mat-chip-set>
        </div>

        <!-- Activated rules (full mode only) -->
        <div class="rules-section" *ngIf="mode === 'full' && trail.activatedRules && trail.activatedRules.length > 0">
          <span class="section-label">Activated Rules:</span>
          <pre class="rules-code">{{ trail.activatedRules.join('\n') }}</pre>
        </div>
      </ng-container>

      <!-- Tree node template (recursive) -->
      <ng-template #treeNode let-node="node" let-depth="depth">
        <div class="tree-node" [style.padding-left]="(depth * 16) + 'px'">
          <span class="tree-atom" [matTooltip]="node.atom + (node.rule ? ' | ' + node.rule : '')">{{ node.title || node.atom }}</span>
          <span class="tree-conf">[{{ (node.confidence * 100).toFixed(0) }}%]</span>
          <app-source-citation *ngIf="node.source"
            [citation]="{ sourceName: node.source }"
            [compact]="true">
          </app-source-citation>
        </div>
        <ng-container *ngIf="node.children">
          <ng-container *ngFor="let child of node.children">
            <ng-container *ngTemplateOutlet="treeNode; context: { node: child, depth: depth + 1 }"></ng-container>
          </ng-container>
        </ng-container>
      </ng-template>
    </div>
  `,
  styleUrls: ['./reasoning-trail.component.css']
})
export class ReasoningTrailComponent implements OnChanges, OnDestroy {
  @Input() trail: ReasoningTrailDto | null = null;
  @Input() loading = false;
  @Input() mode: 'compact' | 'full' = 'compact';
  /** When true, shows a notice that this trace was computed before a pending re-ground completes. */
  @Input() stale = false;

  ngOnChanges(_changes: SimpleChanges): void {}
  ngOnDestroy(): void {}

  getBand(confidence: number): StrengthBand {
    return confidenceToStrengthBand(confidence);
  }

  /** Human-readable label for the reasoning method that produced this trail. */
  modeLabel(mode: string | undefined | null): string {
    switch ((mode || '').toUpperCase()) {
      case 'GROUNDING': return 'Rule-based grounding';
      case 'HYBRID': return 'Hybrid (structure + meaning)';
      case 'CAUSAL': return 'Causal attribution';
      case 'PSL': return 'PSL soft logic';
      case 'MEBN': return 'Bayesian network (MEBN)';
      default: return mode || '';
    }
  }

  get evidenceSlice(): string[] {
    if (!this.trail?.evidence) return [];
    return this.mode === 'compact' ? this.trail.evidence.slice(0, 5) : this.trail.evidence.slice(0, 20);
  }

  breakdownSegments(bd: ConfidenceBreakdownDto): { label: string; value: number; pct: number; color: string }[] {
    const entries = [
      { label: 'PSL', value: bd.pslScore ?? 0, color: '#3498db' },
      { label: 'MEBN', value: bd.mebnScore ?? 0, color: '#9b59b6' },
      { label: 'Embedding', value: bd.embeddingScore ?? 0, color: '#2ecc71' },
      { label: 'Grounding', value: bd.groundingScore ?? 0, color: '#f39c12' },
    ].filter(s => s.value > 0);
    const total = entries.reduce((s, e) => s + e.value, 0) || 1;
    return entries.map(e => ({ ...e, pct: (e.value / total) * 100 }));
  }
}
