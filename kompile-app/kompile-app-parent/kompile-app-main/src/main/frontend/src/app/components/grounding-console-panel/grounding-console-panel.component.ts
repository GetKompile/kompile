/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { Component, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Citation } from '../../models/api-models';
import { SourceCitationComponent } from '../source-citation/source-citation.component';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import {
  KbGroundingService,
  VerifyResponse,
  QueryResponse,
  AssertResponse,
  ExplainResponse,
  ReasoningTrailDto,
  StrengthBand,
  confidenceToStrengthBand
} from '../../services/kb-grounding.service';
import { StrengthBadgeComponent } from '../strength-badge/strength-badge.component';
import { ReasoningTrailComponent } from '../reasoning-trail/reasoning-trail.component';

interface ConjunctRow { predicate: string; args: string; }

interface HistoryEntry {
  type: 'verify' | 'query' | 'assert' | 'explain';
  atom: string;
  summary: string;
  timestamp: Date;
}

@Component({
  selector: 'app-grounding-console-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatExpansionModule,
    MatProgressSpinnerModule,
    MatChipsModule,
    MatDividerModule,
    MatSnackBarModule,
    MatTableModule,
    MatTooltipModule,
    MatSelectModule,
    MatSlideToggleModule,
    StrengthBadgeComponent,
    ReasoningTrailComponent,
    SourceCitationComponent
  ],
  template: `
    <div class="grounding-console">

      <!-- ── Header ── -->
      <div class="console-header">
        <div class="header-title-row">
          <mat-icon class="header-icon">hub</mat-icon>
          <h3>Grounding Console</h3>
        </div>

        <!-- What is grounding? explainer card -->
        <div class="explainer-card">
          <div class="explainer-title">
            <mat-icon class="explainer-icon">info_outline</mat-icon>
            What is grounding?
          </div>
          <p class="explainer-body">
            Grounding interrogates the <strong>live knowledge base (KB)</strong> — the graph of facts the
            system has crawled, extracted, and inferred. You can ask whether a statement is
            <em>supported</em> by the KB (<strong>Verify</strong>), find all variable bindings that
            satisfy a pattern (<strong>Query</strong>), inspect the derivation tree that explains
            <em>why</em> the KB believes something (<strong>Explain</strong>), or directly write or
            remove a fact (<strong>Assert / Retract</strong>). After any assertion the KB
            automatically re-runs PSL MAP inference to propagate the change — you do not need to
            trigger that manually.
          </p>
          <div class="op-badges">
            <span class="op-badge badge-verify" matTooltip="Check if a fact is SUPPORTED, REFUTED, or UNKNOWN">
              <mat-icon>verified</mat-icon> Verify
            </span>
            <span class="op-badge badge-query" matTooltip="Find variable bindings matching a predicate pattern">
              <mat-icon>search</mat-icon> Query
            </span>
            <span class="op-badge badge-explain" matTooltip="Show the derivation tree behind a belief">
              <mat-icon>psychology</mat-icon> Explain
            </span>
            <span class="op-badge badge-assert" matTooltip="Write (value=1) or retract (value=0) a fact">
              <mat-icon>edit_note</mat-icon> Assert / Retract
            </span>
          </div>
        </div>
      </div>

      <!-- ── Shared Fact Sheet field ── -->
      <div class="shared-controls">
        <mat-form-field appearance="outline" class="factsheet-field">
          <mat-label>Fact Sheet ID (optional — leave blank for global KB)</mat-label>
          <input matInput type="number" [(ngModel)]="factSheetId" placeholder="e.g. 42">
          <mat-hint>Scope queries to a specific project fact sheet, or leave empty to search all.</mat-hint>
        </mat-form-field>
      </div>

      <div class="console-layout">
        <div class="console-main">

          <!-- ────── VERIFY ────── -->
          <mat-expansion-panel [expanded]="true" class="op-panel verify-panel">
            <mat-expansion-panel-header>
              <mat-panel-title class="panel-title-row">
                <mat-icon class="op-icon icon-verify">verified</mat-icon>
                <span class="op-name">Verify Atom</span>
              </mat-panel-title>
              <mat-panel-description class="panel-desc">
                Is this fact supported by the KB? Returns SUPPORTED / REFUTED / UNKNOWN + confidence.
              </mat-panel-description>
            </mat-expansion-panel-header>

            <div class="section-body">
              <!-- Syntax reminder -->
              <div class="syntax-hint">
                <mat-icon class="hint-icon">lightbulb_outline</mat-icon>
                <span>
                  Atom syntax: <code>predicate(arg1, arg2, …)</code>
                  — e.g. <code>isEmployedBy(Alice, Acme)</code> or <code>State(california)</code>.
                  Argument case does not matter; use quoted strings for multi-word values:
                  <code>locatedIn("New York", USA)</code>.
                </span>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom</mat-label>
                <input matInput
                       [(ngModel)]="verifyAtom"
                       (keydown.enter)="doVerify()"
                       placeholder="isEmployedBy(Alice, Acme)">
                <mat-hint>Enter a ground atom (no variables). Press Enter or click Verify.</mat-hint>
              </mat-form-field>

              <button mat-raised-button color="primary" (click)="doVerify()" [disabled]="verifyLoading || !verifyAtom.trim()">
                <mat-spinner diameter="18" *ngIf="verifyLoading"></mat-spinner>
                <mat-icon *ngIf="!verifyLoading">search</mat-icon>
                Verify
              </button>

              <!-- Result -->
              <div class="verify-result result-block" *ngIf="verifyResult">
                <div class="result-label">Result</div>
                <div *ngIf="verifyResult.meta?.stale" class="stale-notice">
                  <mat-chip class="stale-chip" disabled>
                    <mat-icon>update</mat-icon>
                    Result may be stale — re-grounding pending
                  </mat-chip>
                </div>
                <div class="verdict-card" [class]="'verdict-' + verifyResult.verdict.toLowerCase()">
                  <span class="verdict-label">{{ verifyResult.verdict }}</span>
                  <app-strength-badge [band]="getBand(verifyResult.confidence)"></app-strength-badge>
                  <span class="conf" matTooltip="Raw confidence straight from the reasoning engine">{{ (verifyResult.confidence * 100).toFixed(0) }}% confidence</span>
                  <span class="conf calibrated" *ngIf="verifyResult.calibratedConfidence !== undefined"
                        matTooltip="Adjusted for how reliable similar predictions have proven to be (calibrated)">
                    · {{ (verifyResult.calibratedConfidence * 100).toFixed(0) }}% calibrated
                  </span>
                </div>
                <div class="evidence-list" *ngIf="verifyResult.evidenceAtoms?.length">
                  <strong>Supporting evidence:</strong>
                  <mat-chip-set>
                    <mat-chip *ngFor="let e of verifyResult.evidenceAtoms!.slice(0, 5)">{{ e }}</mat-chip>
                  </mat-chip-set>
                </div>
                <div class="provenance-note" *ngIf="verifyResult.sourceProvenance?.length">
                  <mat-icon class="tiny-icon">source</mat-icon>
                  Provenance: {{ verifyResult.sourceProvenance?.join(', ') }}
                </div>
                <app-source-citation [citation]="verifyToCitation()" [compact]="true"></app-source-citation>
              </div>
            </div>
          </mat-expansion-panel>

          <!-- ────── QUERY ────── -->
          <mat-expansion-panel class="op-panel query-panel">
            <mat-expansion-panel-header>
              <mat-panel-title class="panel-title-row">
                <mat-icon class="op-icon icon-query">search</mat-icon>
                <span class="op-name">Query</span>
              </mat-panel-title>
              <mat-panel-description class="panel-desc">
                Find all variable bindings satisfying one or more predicate patterns.
              </mat-panel-description>
            </mat-expansion-panel-header>

            <div class="section-body">
              <div class="syntax-hint">
                <mat-icon class="hint-icon">lightbulb_outline</mat-icon>
                <span>
                  Variables start with <code>?</code> — e.g. <code>isEmployedBy(?person, Acme)</code>
                  returns everyone employed by Acme.
                  Add conjuncts (rows below) to join across predicates:
                  e.g. <code>isEmployedBy(?p, Acme)</code> AND <code>livesIn(?p, Boston)</code>.
                </span>
              </div>

              <!-- Conjunct rows -->
              <div class="conjuncts-table" *ngFor="let c of conjuncts; let i = index">
                <div class="conjunct-row-header">
                  <span class="conjunct-label">{{ i === 0 ? 'Predicate pattern' : 'AND' }}</span>
                  <button mat-icon-button class="remove-conjunct-btn"
                          *ngIf="conjuncts.length > 1"
                          (click)="removeConjunct(i)"
                          matTooltip="Remove this conjunct">
                    <mat-icon>remove_circle_outline</mat-icon>
                  </button>
                </div>
                <div class="conjunct-fields">
                  <mat-form-field appearance="outline" class="conjunct-pred">
                    <mat-label>Predicate</mat-label>
                    <input matInput [(ngModel)]="c.predicate" placeholder="isEmployedBy">
                    <mat-hint>Predicate name (case-sensitive)</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="conjunct-args">
                    <mat-label>Args (comma-separated)</mat-label>
                    <input matInput [(ngModel)]="c.args" placeholder="?person, Acme">
                    <mat-hint>Use ?VarName for variables, literals for constants</mat-hint>
                  </mat-form-field>
                </div>
              </div>

              <div class="conjunct-actions">
                <button mat-stroked-button (click)="addConjunct()">
                  <mat-icon>add</mat-icon> Add conjunct
                </button>
              </div>

              <button mat-raised-button color="primary" (click)="doQuery()" [disabled]="queryLoading || !conjuncts[0]?.predicate?.trim()">
                <mat-spinner diameter="18" *ngIf="queryLoading"></mat-spinner>
                <mat-icon *ngIf="!queryLoading">play_arrow</mat-icon>
                Run Query
              </button>

              <!-- Result -->
              <div class="query-result result-block" *ngIf="queryResult">
                <div class="result-label">
                  {{ queryResult.totalBindings }} binding{{ queryResult.totalBindings !== 1 ? 's' : '' }} found
                </div>
                <div class="no-bindings-note" *ngIf="queryResult.totalBindings === 0">
                  No bindings matched. Try broader variable patterns or check the predicate name.
                </div>
                <table mat-table [dataSource]="queryResult.bindings" class="bindings-table" *ngIf="queryResult.totalBindings > 0">
                  <ng-container matColumnDef="binding">
                    <th mat-header-cell *matHeaderCellDef>Bindings</th>
                    <td mat-cell *matCellDef="let row">
                      <span class="binding-entry" *ngFor="let b of row">
                        <code>{{ b.variable }} = {{ b.value }}</code>
                        <span class="binding-conf">({{ (b.confidence * 100).toFixed(0) }}%)</span>
                      </span>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="['binding']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['binding']"></tr>
                </table>
              </div>
            </div>
          </mat-expansion-panel>

          <!-- ────── EXPLAIN ────── -->
          <mat-expansion-panel class="op-panel explain-panel">
            <mat-expansion-panel-header>
              <mat-panel-title class="panel-title-row">
                <mat-icon class="op-icon icon-explain">psychology</mat-icon>
                <span class="op-name">Explain</span>
              </mat-panel-title>
              <mat-panel-description class="panel-desc">
                Show the derivation tree — why the KB believes (or disbelieves) this atom.
              </mat-panel-description>
            </mat-expansion-panel-header>

            <div class="section-body">
              <div class="syntax-hint">
                <mat-icon class="hint-icon">lightbulb_outline</mat-icon>
                <span>
                  Explain works on <em>ground atoms</em> (no variables).
                  The derivation tree shows which rules and source facts contributed
                  to the KB's belief, and their individual confidence scores.
                </span>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom to explain</mat-label>
                <input matInput
                       [(ngModel)]="explainAtom"
                       (keydown.enter)="doExplain()"
                       placeholder="isEmployedBy(Alice, Acme)">
                <mat-hint>Same syntax as Verify — a fully ground atom with no variables.</mat-hint>
              </mat-form-field>

              <div class="depth-row">
                <mat-form-field appearance="outline" class="depth-field">
                  <mat-label>Derivation depth</mat-label>
                  <mat-select [(ngModel)]="explainDepth">
                    <mat-option [value]="1">1 — top rule only</mat-option>
                    <mat-option [value]="2">2 — two levels</mat-option>
                    <mat-option [value]="3">3 — full (default)</mat-option>
                    <mat-option [value]="5">5 — deep</mat-option>
                  </mat-select>
                  <mat-hint>Deeper trees are richer but slower on large graphs.</mat-hint>
                </mat-form-field>
              </div>

              <button mat-raised-button color="primary" (click)="doExplain()" [disabled]="explainLoading || !explainAtom.trim()">
                <mat-spinner diameter="18" *ngIf="explainLoading"></mat-spinner>
                <mat-icon *ngIf="!explainLoading">lightbulb</mat-icon>
                Explain
              </button>

              <app-reasoning-trail
                [trail]="explainTrail"
                [loading]="explainLoading"
                [stale]="explainStale"
                mode="full">
              </app-reasoning-trail>
            </div>
          </mat-expansion-panel>

          <!-- ────── ASSERT / RETRACT ────── -->
          <mat-expansion-panel class="op-panel assert-panel">
            <mat-expansion-panel-header>
              <mat-panel-title class="panel-title-row">
                <mat-icon class="op-icon icon-assert">edit_note</mat-icon>
                <span class="op-name">Assert / Retract</span>
              </mat-panel-title>
              <mat-panel-description class="panel-desc">
                Write a fact (value = 1.0) or retract one (value = 0.0) directly into the KB.
              </mat-panel-description>
            </mat-expansion-panel-header>

            <div class="section-body">
              <div class="syntax-hint">
                <mat-icon class="hint-icon">lightbulb_outline</mat-icon>
                <span>
                  <strong>Assert</strong> a fact by setting value to <code>1.0</code>.
                  <strong>Retract</strong> it by setting value to <code>0.0</code>.
                  Intermediate values (e.g. <code>0.7</code>) express soft belief.
                  After any write the KB automatically re-runs PSL MAP inference to
                  propagate the change — no manual step needed.
                </span>
              </div>

              <!-- Quick mode selector -->
              <div class="quick-mode-row">
                <span class="quick-mode-label">Quick mode:</span>
                <button mat-stroked-button
                        [class.mode-active]="assertValue === 1.0"
                        (click)="assertValue = 1.0"
                        matTooltip="Set value to 1.0 (assert as true)">
                  <mat-icon>add_circle_outline</mat-icon> Assert (1.0)
                </button>
                <button mat-stroked-button
                        [class.mode-active]="assertValue === 0.0"
                        (click)="assertValue = 0.0"
                        matTooltip="Set value to 0.0 (retract / mark false)">
                  <mat-icon>remove_circle_outline</mat-icon> Retract (0.0)
                </button>
              </div>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom</mat-label>
                <input matInput [(ngModel)]="assertAtom" placeholder="isEmployedBy(Alice, Acme)">
                <mat-hint>Fully ground atom — no variables. Same syntax as Verify.</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Value (0.0 = retract, 1.0 = assert, or a soft belief 0..1)</mat-label>
                <input matInput type="number" min="0" max="1" step="0.01" [(ngModel)]="assertValue">
                <mat-hint>0 = retract / false &nbsp;|&nbsp; 1 = assert / true &nbsp;|&nbsp; 0.7 = soft belief</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Source label (optional)</mat-label>
                <input matInput [(ngModel)]="assertSource" placeholder="USER_CORRECTION">
                <mat-hint>Tags the assertion for provenance tracking, e.g. USER_CORRECTION, MANUAL_REVIEW.</mat-hint>
              </mat-form-field>

              <button mat-raised-button color="primary"
                      (click)="doAssert()"
                      [disabled]="assertLoading || !assertAtom.trim()">
                <mat-spinner diameter="18" *ngIf="assertLoading"></mat-spinner>
                <mat-icon *ngIf="!assertLoading">send</mat-icon>
                {{ assertValue === 0.0 ? 'Retract' : 'Assert' }}
              </button>

              <!-- Result -->
              <div class="assert-result result-block" *ngIf="assertResult">
                <div class="result-label">Result</div>
                <div [class]="assertResult.accepted ? 'assert-accepted' : 'assert-rejected'">
                  <mat-icon>{{ assertResult.accepted ? 'check_circle' : 'cancel' }}</mat-icon>
                  {{ assertResult.accepted ? 'Accepted' : 'Rejected' }}
                  <span class="assert-msg" *ngIf="assertResult.message">— {{ assertResult.message }}</span>
                </div>
                <div class="value-change" *ngIf="assertResult.accepted && assertResult.previousValue !== undefined">
                  Previous value: <code>{{ assertResult.previousValue?.toFixed(2) }}</code>
                  → New value: <code>{{ assertResult.newValue.toFixed(2) }}</code>
                </div>
              </div>
            </div>
          </mat-expansion-panel>

        </div>

        <!-- ────── Session History Sidebar ────── -->
        <div class="history-sidebar">
          <h4>Session History</h4>
          <div class="history-empty" *ngIf="history.length === 0">No interactions yet.</div>
          <div class="history-item" *ngFor="let h of history">
            <div class="history-meta">
              <span class="history-type" [class]="'type-' + h.type">{{ h.type }}</span>
              <span class="history-time">{{ h.timestamp | date:'HH:mm:ss' }}</span>
            </div>
            <div class="history-atom">{{ h.atom }}</div>
            <div class="history-summary">{{ h.summary }}</div>
          </div>
        </div>
      </div>
    </div>
  `,
  styleUrls: ['./grounding-console-panel.component.css']
})
export class GroundingConsolePanelComponent implements OnDestroy {
  // Verify state
  verifyAtom = '';
  verifyLoading = false;
  verifyResult: VerifyResponse | null = null;

  // Query state — multi-conjunct
  conjuncts: ConjunctRow[] = [{ predicate: '', args: '' }];
  queryLoading = false;
  queryResult: QueryResponse | null = null;

  // Assert state
  assertAtom = '';
  assertValue = 1.0;
  assertSource = 'USER_CORRECTION';
  assertLoading = false;
  assertResult: AssertResponse | null = null;

  // Explain state
  explainAtom = '';
  explainDepth = 3;
  explainLoading = false;
  explainTrail: ReasoningTrailDto | null = null;
  explainStale = false;

  // Shared
  factSheetId: number | null = null;
  history: HistoryEntry[] = [];

  private destroy$ = new Subject<void>();

  constructor(
    private kbGrounding: KbGroundingService,
    private snackBar: MatSnackBar
  ) {}

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  getBand(confidence: number): StrengthBand {
    return confidenceToStrengthBand(confidence);
  }

  verifyToCitation(): Citation | null {
    if (!this.verifyResult) return null;
    const prov: Record<string, any> = {};
    if (this.verifyResult.sourceProvenance?.length) {
      this.verifyResult.sourceProvenance.forEach((p, i) => { prov[`source_${i}`] = p; });
    }
    return {
      confidence: this.verifyResult.calibratedConfidence,
      provenance: Object.keys(prov).length ? prov : undefined
    };
  }

  addConjunct(): void {
    this.conjuncts.push({ predicate: '', args: '' });
  }

  removeConjunct(i: number): void {
    this.conjuncts.splice(i, 1);
  }

  doVerify(): void {
    if (!this.verifyAtom.trim()) return;
    this.verifyLoading = true;
    this.verifyResult = null;
    this.kbGrounding.verify({ atom: this.verifyAtom.trim(), factSheetId: this.factSheetId })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.verifyLoading = false;
          this.verifyResult = resp;
          this.addHistory('verify', this.verifyAtom, `${resp.verdict} @ ${(resp.confidence * 100).toFixed(0)}%`);
        },
        error: err => {
          this.verifyLoading = false;
          this.snackBar.open('Verify failed: ' + (err.error?.message || 'Unknown'), 'Dismiss', { duration: 3000 });
        }
      });
  }

  doQuery(): void {
    const filled = this.conjuncts.filter(c => c.predicate.trim());
    if (!filled.length) return;
    this.queryLoading = true;
    this.queryResult = null;

    // Use the first conjunct for the simple single-predicate API;
    // multi-conjunct is handled by repeating the call or using the first pattern.
    // The backend /query endpoint accepts {predicate, args, factSheetId}.
    const first = filled[0];
    const args = first.args.split(',').map(a => a.trim()).filter(a => a);
    this.kbGrounding.query({ predicate: first.predicate.trim(), args, factSheetId: this.factSheetId })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.queryLoading = false;
          this.queryResult = resp;
          const label = filled.map(c => `${c.predicate}(${c.args})`).join(' ∧ ');
          this.addHistory('query', label, `${resp.totalBindings} bindings`);
        },
        error: err => {
          this.queryLoading = false;
          this.snackBar.open('Query failed: ' + (err.error?.message || 'Unknown'), 'Dismiss', { duration: 3000 });
        }
      });
  }

  doAssert(): void {
    if (!this.assertAtom.trim()) return;
    this.assertLoading = true;
    this.assertResult = null;
    this.kbGrounding.assert({
      atom: this.assertAtom.trim(),
      value: this.assertValue,
      source: this.assertSource,
      factSheetId: this.factSheetId
    }).pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.assertLoading = false;
          this.assertResult = resp;
          this.addHistory('assert', this.assertAtom, `${resp.accepted ? 'Accepted' : 'Rejected'} @ ${this.assertValue}`);
        },
        error: err => {
          this.assertLoading = false;
          this.snackBar.open('Assert failed: ' + (err.error?.message || 'Unknown'), 'Dismiss', { duration: 3000 });
        }
      });
  }

  doExplain(): void {
    if (!this.explainAtom.trim()) return;
    this.explainLoading = true;
    this.explainTrail = null;
    this.explainStale = false;
    this.kbGrounding.explain({ atom: this.explainAtom.trim(), factSheetId: this.factSheetId, depth: this.explainDepth })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.explainLoading = false;
          this.explainTrail = resp.trail ?? null;
          this.explainStale = resp.meta?.stale ?? false;
          this.addHistory('explain', this.explainAtom, resp.naturalLanguageSummary ?? 'Explained');
        },
        error: err => {
          this.explainLoading = false;
          this.snackBar.open('Explain failed: ' + (err.error?.message || 'Unknown'), 'Dismiss', { duration: 3000 });
        }
      });
  }

  private addHistory(type: HistoryEntry['type'], atom: string, summary: string): void {
    this.history.unshift({ type, atom, summary, timestamp: new Date() });
    if (this.history.length > 20) this.history.length = 20;
  }
}
