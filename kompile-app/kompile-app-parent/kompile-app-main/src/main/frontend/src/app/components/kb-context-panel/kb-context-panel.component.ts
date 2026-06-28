/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, Input, OnChanges, SimpleChanges, Output, EventEmitter, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Citation } from '../../models/api-models';
import { SourceCitationComponent } from '../source-citation/source-citation.component';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSliderModule } from '@angular/material/slider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil, debounceTime, switchMap } from 'rxjs/operators';
import {
  KbGroundingService,
  VerifyResponse,
  ExplainResponse,
  ReasoningTrailDto,
  StrengthBand,
  confidenceToStrengthBand
} from '../../services/kb-grounding.service';
import { StrengthBadgeComponent } from '../strength-badge/strength-badge.component';
import { ReasoningTrailComponent } from '../reasoning-trail/reasoning-trail.component';
import { D3Node } from '../../models/graph-models';

@Component({
  selector: 'app-kb-context-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatTooltipModule,
    MatInputModule,
    MatFormFieldModule,
    MatSliderModule,
    MatSnackBarModule,
    StrengthBadgeComponent,
    ReasoningTrailComponent,
    SourceCitationComponent
  ],
  template: `
    <div class="kb-context-panel">
      <!-- No node selected -->
      <div *ngIf="!node" class="no-selection">
        <mat-icon>touch_app</mat-icon>
        <p>Click a node on the graph to inspect its KB context.</p>
      </div>

      <!-- Node context -->
      <ng-container *ngIf="node">
        <!-- Header row -->
        <div class="context-header">
          <div class="node-title-row">
            <span class="node-label">{{ node.title || node.label || node.id }}</span>
            <app-strength-badge *ngIf="verifyResult" [band]="band"></app-strength-badge>
          </div>
          <div class="verdict-row" *ngIf="verifyResult">
            <span class="verdict" [class]="'verdict-' + verifyResult.verdict.toLowerCase()">
              <mat-icon class="verdict-icon">{{ verdictIcon }}</mat-icon>
              {{ verifyResult.verdict }}
            </span>
            <span class="confidence">{{ (verifyResult.confidence * 100).toFixed(0) }}% confidence</span>
          </div>
          <div *ngIf="verifyLoading" class="verify-loading">
            <mat-spinner diameter="18"></mat-spinner>
            <span>Verifying...</span>
          </div>
          <div *ngIf="verifyError && !verifyLoading" class="verify-error">
            <mat-icon>warning</mat-icon>
            <span>{{ verifyError }}</span>
          </div>
        </div>

        <!-- Evidence atoms -->
        <div class="evidence-section" *ngIf="verifyResult?.evidenceAtoms && verifyResult!.evidenceAtoms!.length">
          <span class="section-label">Evidence</span>
          <mat-chip-set>
            <mat-chip *ngFor="let ev of verifyResult!.evidenceAtoms!.slice(0, 5)" class="evidence-chip">
              {{ ev.length > 50 ? (ev | slice:0:50) + '...' : ev }}
            </mat-chip>
          </mat-chip-set>
        </div>

        <!-- Provenance -->
        <div class="provenance-section" *ngIf="provenance">
          <span class="section-label">Provenance</span>
          <div class="prov-row">
            <span class="prov-type" [class]="'prov-' + provenance.toLowerCase()">{{ provenance }}</span>
            <span class="prov-meta" *ngIf="crawlRunId">Crawl: {{ crawlRunId | slice:0:12 }}...</span>
            <span class="prov-meta" *ngIf="extractedAt">Changed: {{ extractedAt | slice:0:10 }}</span>
          </div>
        </div>

        <!-- Source citation from node metadata -->
        <div class="node-citation-section" *ngIf="node.metadata">
          <app-source-citation [citation]="toCitation(node.metadata)" [compact]="true"></app-source-citation>
        </div>

        <!-- Action buttons -->
        <div class="action-buttons">
          <button mat-stroked-button (click)="onWhy()" [disabled]="trailLoading">
            <mat-icon>psychology</mat-icon> Why?
          </button>
          <button mat-stroked-button (click)="onVerify()" [disabled]="verifyLoading">
            <mat-icon>verified</mat-icon> Verify
          </button>
          <button mat-stroked-button (click)="showCorrect = !showCorrect">
            <mat-icon>edit</mat-icon> Correct
          </button>
          <button mat-stroked-button (click)="onGround.emit(node.id)" matTooltip="Open in Grounding Console">
            <mat-icon>hub</mat-icon> Ground
          </button>
        </div>

        <!-- Inline correction form -->
        <div class="correction-form" *ngIf="showCorrect">
          <span class="section-label">Assert Correction</span>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Value (0 = false, 1 = true)</mat-label>
            <input matInput type="number" min="0" max="1" step="0.01" [(ngModel)]="correctValue">
          </mat-form-field>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Source label</mat-label>
            <input matInput [(ngModel)]="correctSource" placeholder="e.g. USER_CORRECTION">
          </mat-form-field>
          <button mat-raised-button color="primary" (click)="onAssert()" [disabled]="assertLoading">
            <mat-icon>send</mat-icon> Assert
          </button>
          <mat-spinner diameter="18" *ngIf="assertLoading"></mat-spinner>
        </div>

        <!-- Reasoning trail -->
        <div class="trail-section">
          <app-reasoning-trail
            [trail]="trail"
            [loading]="trailLoading"
            [stale]="!!(verifyResult?.meta?.stale)"
            mode="compact">
          </app-reasoning-trail>
        </div>
      </ng-container>
    </div>
  `,
  styleUrls: ['./kb-context-panel.component.css']
})
export class KbContextPanelComponent implements OnChanges, OnDestroy {
  @Input() node: D3Node | null = null;
  @Input() factSheetId: number | null = null;
  /** Pass selectedTabIndex === 0 from the host so auto-verify is gated on panel visibility. */
  @Input() isActive: boolean = false;

  @Output() onGround = new EventEmitter<string>();

  verifyResult: VerifyResponse | null = null;
  verifyLoading = false;
  verifyError: string | null = null;

  trail: ReasoningTrailDto | null = null;
  trailLoading = false;

  showCorrect = false;
  correctValue = 0.5;
  correctSource = 'USER_CORRECTION';
  assertLoading = false;

  private destroy$ = new Subject<void>();
  private whySubject$ = new Subject<string>();
  /** Feeds the debounced + cancel-in-flight auto-verify pipeline. */
  private autoVerify$ = new Subject<D3Node>();
  /** Per-node cache keyed by `nodeId_factSheetId`. Manual Verify bypasses and refreshes. */
  private verifyCache = new Map<string, VerifyResponse>();

  constructor(
    private kbGrounding: KbGroundingService,
    private snackBar: MatSnackBar
  ) {
    // Debounced why? call
    this.whySubject$.pipe(
      debounceTime(300),
      switchMap(atom => {
        this.trailLoading = true;
        this.trail = null;
        return this.kbGrounding.explain({ atom, factSheetId: this.factSheetId, depth: 3 });
      }),
      takeUntil(this.destroy$)
    ).subscribe({
      next: resp => {
        this.trailLoading = false;
        this.trail = resp.trail ?? this.buildTrailFromExplain(resp);
      },
      error: err => {
        this.trailLoading = false;
        this.snackBar.open('Explain failed: ' + (err.error?.message || err.message || 'Unknown error'), 'Dismiss', { duration: 3000 });
      }
    });

    // Debounced + cancel-in-flight auto-verify pipeline.
    // debounceTime(400): rapid node clicks collapse into a single request.
    // switchMap: cancels the in-flight HTTP call when a newer node arrives.
    this.autoVerify$.pipe(
      debounceTime(400),
      switchMap(node => {
        const atom = node.id || node.label || node.title || '';
        this.verifyLoading = true;
        this.verifyError = null;
        return this.kbGrounding.verify({ atom, factSheetId: this.factSheetId });
      }),
      takeUntil(this.destroy$)
    ).subscribe({
      next: resp => {
        this.verifyLoading = false;
        this.verifyResult = resp;
        if (this.node) {
          this.verifyCache.set(this.cacheKey(this.node), resp);
        }
      },
      error: err => {
        this.verifyLoading = false;
        this.verifyError = err.error?.message || 'Verify failed';
      }
    });
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Node changed: check cache first; if not cached, only auto-verify when panel is visible.
    if (changes['node']) {
      this.verifyError = null;
      this.trail = null;
      this.showCorrect = false;
      if (this.node) {
        const cached = this.verifyCache.get(this.cacheKey(this.node));
        if (cached) {
          // Instant cache hit — no HTTP round-trip.
          this.verifyResult = cached;
          this.verifyLoading = false;
        } else {
          this.verifyResult = null;
          if (this.isActive) {
            // Panel is the active tab — push through the debounce + switchMap pipeline.
            this.autoVerify$.next(this.node);
          }
          // else: panel is hidden; deferred until isActive flips true (handled below).
        }
      } else {
        this.verifyResult = null;
      }
    }

    // Panel just became visible with an un-verified node — trigger now.
    if (changes['isActive'] && this.isActive && this.node && !this.verifyResult && !this.verifyLoading) {
      this.autoVerify$.next(this.node);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  /** Stable cache key: nodeId + factSheetId (different sheets must not share results). */
  private cacheKey(node: D3Node): string {
    return `${node.id}_${this.factSheetId ?? ''}`;
  }

  get band(): StrengthBand {
    return this.verifyResult ? confidenceToStrengthBand(this.verifyResult.confidence) : 'PROBABLE';
  }

  get verdictIcon(): string {
    switch (this.verifyResult?.verdict) {
      case 'SUPPORTED': return 'check_circle';
      case 'REFUTED': return 'cancel';
      default: return 'help_outline';
    }
  }

  get provenance(): string | null {
    const meta = this.node?.metadata as Record<string, string> | undefined;
    return meta?.['_provenance'] ?? meta?.['provenance'] ?? null;
  }

  get crawlRunId(): string | null {
    const meta = this.node?.metadata as Record<string, string> | undefined;
    return meta?.['_crawlRunId'] ?? null;
  }

  get extractedAt(): string | null {
    const meta = this.node?.metadata as Record<string, string> | undefined;
    return meta?.['_extractedAt'] ?? null;
  }

  toCitation(meta: Record<string, any>): Citation {
    const prov: Record<string, any> = {};
    if (meta['provenance'] && typeof meta['provenance'] === 'object') {
      Object.assign(prov, meta['provenance']);
    }
    if (meta['_extractedAt']) {
      prov['extractedAt'] = meta['_extractedAt'];
    }
    return {
      sourceId: meta['_sourceDocumentId'] ?? undefined,
      crawlRunId: meta['_crawlRunId'] ?? undefined,
      basisType: meta['_basisType'] ?? undefined,
      pageNumber: meta['page_number'] != null ? Number(meta['page_number']) : undefined,
      chunkIndex: meta['chunk_index'] != null ? Number(meta['chunk_index']) : undefined,
      confidence: meta['confidence'] != null ? Number(meta['confidence']) : undefined,
      provenance: Object.keys(prov).length ? prov : undefined
    };
  }

  /**
   * Manual Verify button: force-fresh (clears cache entry), immediate HTTP call.
   * Staleness guard: ignores the response if the user has already moved to a different node.
   */
  onVerify(): void {
    if (!this.node) return;
    const node = this.node;
    const key = this.cacheKey(node);
    this.verifyCache.delete(key);
    this.verifyLoading = true;
    this.verifyError = null;
    const atom = node.id || node.label || node.title || '';
    this.kbGrounding.verify({ atom, factSheetId: this.factSheetId })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          // Discard if the user has moved to a different node since this request started.
          if (this.node?.id !== node.id) return;
          this.verifyLoading = false;
          this.verifyResult = resp;
          this.verifyCache.set(key, resp);
        },
        error: err => {
          if (this.node?.id !== node.id) return;
          this.verifyLoading = false;
          this.verifyError = err.error?.message || 'Verify failed';
        }
      });
  }

  onWhy(): void {
    if (!this.node) return;
    this.whySubject$.next(this.node.id || this.node.label || this.node.title || '');
  }

  onAssert(): void {
    if (!this.node) return;
    this.assertLoading = true;
    const atom = this.node.id || this.node.label || this.node.title || '';
    this.kbGrounding.assert({
      atom,
      value: this.correctValue,
      source: this.correctSource,
      factSheetId: this.factSheetId
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.assertLoading = false;
          this.showCorrect = false;
          this.snackBar.open(resp.message || 'Assertion recorded', 'OK', { duration: 2000 });
          this.onVerify(); // refresh
        },
        error: err => {
          this.assertLoading = false;
          this.snackBar.open('Assert failed: ' + (err.error?.message || 'Unknown'), 'Dismiss', { duration: 3000 });
        }
      });
  }

  private buildTrailFromExplain(resp: ExplainResponse): ReasoningTrailDto {
    return {
      targetId: this.node?.id ?? '',
      question: resp.atom,
      confidence: this.verifyResult?.confidence ?? 0,
      naturalLanguageSummary: resp.naturalLanguageSummary,
      derivationTree: resp.derivation ? JSON.parse(resp.derivation) : undefined,
      evidence: [],
      activatedRules: [],
      inferenceMode: 'UNKNOWN'
    };
  }
}
