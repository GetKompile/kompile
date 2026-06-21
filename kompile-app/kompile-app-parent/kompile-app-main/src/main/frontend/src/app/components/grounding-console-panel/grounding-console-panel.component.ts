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
    StrengthBadgeComponent,
    ReasoningTrailComponent
  ],
  template: `
    <div class="grounding-console">
      <div class="console-header">
        <mat-icon>hub</mat-icon>
        <h3>Grounding Console</h3>
        <p class="subtitle">Interactively verify, query, assert, and explain KB facts.</p>
      </div>

      <div class="console-layout">
        <div class="console-main">

          <!-- Verify Section -->
          <mat-expansion-panel [expanded]="true">
            <mat-expansion-panel-header>
              <mat-panel-title><mat-icon>verified</mat-icon> Verify Atom</mat-panel-title>
            </mat-expansion-panel-header>
            <div class="section-body">
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom (e.g. isEmployedBy(Alice, Acme))</mat-label>
                <input matInput [(ngModel)]="verifyAtom" (keydown.enter)="doVerify()">
              </mat-form-field>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Fact Sheet ID (optional)</mat-label>
                <input matInput type="number" [(ngModel)]="factSheetId">
              </mat-form-field>
              <button mat-raised-button color="primary" (click)="doVerify()" [disabled]="verifyLoading">
                <mat-spinner diameter="18" *ngIf="verifyLoading"></mat-spinner>
                <mat-icon *ngIf="!verifyLoading">search</mat-icon>
                Verify
              </button>
              <div class="verify-result" *ngIf="verifyResult">
                <div class="verdict-card" [class]="'verdict-' + verifyResult.verdict.toLowerCase()">
                  <span class="verdict-label">{{ verifyResult.verdict }}</span>
                  <app-strength-badge [band]="getBand(verifyResult.confidence)"></app-strength-badge>
                  <span class="conf">{{ (verifyResult.confidence * 100).toFixed(0) }}%</span>
                </div>
                <div class="evidence-list" *ngIf="verifyResult.evidence?.length">
                  <strong>Evidence:</strong>
                  <mat-chip-set>
                    <mat-chip *ngFor="let e of verifyResult.evidence!.slice(0, 5)">{{ e }}</mat-chip>
                  </mat-chip-set>
                </div>
              </div>
            </div>
          </mat-expansion-panel>

          <!-- Query Section -->
          <mat-expansion-panel>
            <mat-expansion-panel-header>
              <mat-panel-title><mat-icon>search</mat-icon> Query</mat-panel-title>
            </mat-expansion-panel-header>
            <div class="section-body">
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Predicate</mat-label>
                <input matInput [(ngModel)]="queryPredicate" placeholder="isEmployedBy">
              </mat-form-field>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Args (comma-separated)</mat-label>
                <input matInput [(ngModel)]="queryArgs" placeholder="?X, Acme">
              </mat-form-field>
              <button mat-raised-button color="primary" (click)="doQuery()" [disabled]="queryLoading">
                <mat-spinner diameter="18" *ngIf="queryLoading"></mat-spinner>
                <mat-icon *ngIf="!queryLoading">play_arrow</mat-icon>
                Run Query
              </button>
              <div class="query-result" *ngIf="queryResult">
                <p>{{ queryResult.totalBindings }} bindings</p>
                <table mat-table [dataSource]="queryResult.bindings" class="bindings-table">
                  <ng-container matColumnDef="binding">
                    <th mat-header-cell *matHeaderCellDef>Binding</th>
                    <td mat-cell *matCellDef="let row">
                      <span *ngFor="let b of row">{{ b.variable }}={{ b.value }} ({{ (b.confidence * 100).toFixed(0) }}%) </span>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="['binding']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['binding']"></tr>
                </table>
              </div>
            </div>
          </mat-expansion-panel>

          <!-- Assert Section -->
          <mat-expansion-panel>
            <mat-expansion-panel-header>
              <mat-panel-title><mat-icon>edit_note</mat-icon> Assert / Retract</mat-panel-title>
            </mat-expansion-panel-header>
            <div class="section-body">
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom</mat-label>
                <input matInput [(ngModel)]="assertAtom">
              </mat-form-field>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Value (0=false, 1=true)</mat-label>
                <input matInput type="number" min="0" max="1" step="0.01" [(ngModel)]="assertValue">
              </mat-form-field>
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Source</mat-label>
                <input matInput [(ngModel)]="assertSource" placeholder="USER_CORRECTION">
              </mat-form-field>
              <button mat-raised-button color="primary" (click)="doAssert()" [disabled]="assertLoading">
                <mat-spinner diameter="18" *ngIf="assertLoading"></mat-spinner>
                <mat-icon *ngIf="!assertLoading">send</mat-icon>
                Assert
              </button>
              <div class="assert-result" *ngIf="assertResult">
                <span [class]="assertResult.accepted ? 'success' : 'warn'">
                  {{ assertResult.accepted ? 'Accepted' : 'Rejected' }}: {{ assertResult.message }}
                </span>
              </div>
            </div>
          </mat-expansion-panel>

          <!-- Explain Section -->
          <mat-expansion-panel>
            <mat-expansion-panel-header>
              <mat-panel-title><mat-icon>psychology</mat-icon> Explain</mat-panel-title>
            </mat-expansion-panel-header>
            <div class="section-body">
              <mat-form-field appearance="outline" class="full-width">
                <mat-label>Atom to explain</mat-label>
                <input matInput [(ngModel)]="explainAtom" (keydown.enter)="doExplain()">
              </mat-form-field>
              <button mat-raised-button color="primary" (click)="doExplain()" [disabled]="explainLoading">
                <mat-spinner diameter="18" *ngIf="explainLoading"></mat-spinner>
                <mat-icon *ngIf="!explainLoading">lightbulb</mat-icon>
                Explain
              </button>
              <app-reasoning-trail
                [trail]="explainTrail"
                [loading]="explainLoading"
                mode="full">
              </app-reasoning-trail>
            </div>
          </mat-expansion-panel>

        </div>

        <!-- Session history sidebar -->
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

  // Query state
  queryPredicate = '';
  queryArgs = '';
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
  explainLoading = false;
  explainTrail: ReasoningTrailDto | null = null;

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
    if (!this.queryPredicate.trim()) return;
    this.queryLoading = true;
    this.queryResult = null;
    const args = this.queryArgs.split(',').map(a => a.trim()).filter(a => a);
    this.kbGrounding.query({ predicate: this.queryPredicate.trim(), args, factSheetId: this.factSheetId })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.queryLoading = false;
          this.queryResult = resp;
          this.addHistory('query', `${this.queryPredicate}(${this.queryArgs})`, `${resp.totalBindings} bindings`);
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
    this.kbGrounding.explain({ atom: this.explainAtom.trim(), factSheetId: this.factSheetId, depth: 3 })
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: resp => {
          this.explainLoading = false;
          this.explainTrail = resp.trail ?? null;
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
