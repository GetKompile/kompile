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
    StrengthBadgeComponent
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

        <!-- Confidence breakdown bar (full mode only) -->
        <div class="breakdown-bar" *ngIf="mode === 'full' && trail.breakdown">
          <div class="breakdown-segment" *ngFor="let seg of breakdownSegments(trail.breakdown)"
               [style.width]="seg.pct + '%'"
               [style.background]="seg.color"
               [matTooltip]="seg.label + ': ' + (seg.value * 100).toFixed(0) + '%'">
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
          <span class="tree-atom" [matTooltip]="node.rule || ''">{{ node.atom }}</span>
          <span class="tree-conf">[{{ (node.confidence * 100).toFixed(0) }}%]</span>
          <span class="tree-source" *ngIf="node.source">{{ node.source }}</span>
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

  ngOnChanges(_changes: SimpleChanges): void {}
  ngOnDestroy(): void {}

  getBand(confidence: number): StrengthBand {
    return confidenceToStrengthBand(confidence);
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
