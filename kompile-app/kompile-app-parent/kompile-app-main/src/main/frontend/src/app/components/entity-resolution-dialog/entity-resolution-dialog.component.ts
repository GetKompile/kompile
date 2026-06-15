/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { Component, Inject, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSliderModule } from '@angular/material/slider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatBadgeModule } from '@angular/material/badge';
import {
  EntityResolutionService,
  MatchCandidate,
  CompactionPreview,
  CompactionRequest,
  CompactionResult,
  MatchExplanation
} from '../../services/entity-resolution.service';

export interface EntityResolutionDialogData {
  searchQuery: string;
  factSheetId?: number;
}

export interface EntityResolutionDialogResult {
  merged: boolean;
  mergeCount: number;
}

@Component({
  selector: 'app-entity-resolution-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSliderModule,
    MatSlideToggleModule,
    MatChipsModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatExpansionModule,
    MatBadgeModule
  ],
  templateUrl: './entity-resolution-dialog.component.html',
  styleUrls: ['./entity-resolution-dialog.component.css']
})
export class EntityResolutionDialogComponent implements OnInit {
  candidates: MatchCandidate[] = [];
  loading = false;
  threshold = 0.85;
  crossTypeMerging = false;
  entityTypeCorrection = false;
  crossLanguageResolution = false;

  skippedCandidates = new Set<string>();
  mergingPair: string | null = null;
  totalMerged = 0;

  selectedExplanation: MatchExplanation | null = null;
  explanationLoading = false;
  explanationForPair: string | null = null;

  showSettings = false;

  constructor(
    public dialogRef: MatDialogRef<EntityResolutionDialogComponent, EntityResolutionDialogResult>,
    @Inject(MAT_DIALOG_DATA) public data: EntityResolutionDialogData,
    private entityResolutionService: EntityResolutionService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadCandidates();
  }

  loadCandidates(): void {
    this.loading = true;
    this.selectedExplanation = null;

    const hasAdvanced = this.crossTypeMerging || this.entityTypeCorrection || this.crossLanguageResolution;

    if (hasAdvanced) {
      const request: CompactionRequest = {
        threshold: this.threshold,
        factSheetId: this.data.factSheetId,
        crossTypeMerging: this.crossTypeMerging,
        entityTypeCorrection: this.entityTypeCorrection,
        crossLanguageResolution: this.crossLanguageResolution
      };
      this.entityResolutionService.previewCandidatesAdvanced(request).subscribe({
        next: (preview: CompactionPreview) => {
          this.candidates = this.filterByQuery(preview.candidates || []);
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loading = false;
          this.snackBar.open('Failed to load candidates', 'Close', { duration: 3000 });
        }
      });
    } else {
      this.entityResolutionService.previewCandidates(this.threshold, this.data.factSheetId).subscribe({
        next: (preview: CompactionPreview) => {
          this.candidates = this.filterByQuery(preview.candidates || []);
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: () => {
          this.loading = false;
          this.snackBar.open('Failed to load candidates', 'Close', { duration: 3000 });
        }
      });
    }
  }

  private filterByQuery(candidates: MatchCandidate[]): MatchCandidate[] {
    if (!this.data.searchQuery) return candidates;
    const q = this.data.searchQuery.toLowerCase();
    return candidates.filter(c =>
      c.titleA.toLowerCase().includes(q) || c.titleB.toLowerCase().includes(q)
    );
  }

  get visibleCandidates(): MatchCandidate[] {
    return this.candidates.filter(c => !this.isSkipped(c));
  }

  private candidateKey(c: MatchCandidate): string {
    return c.nodeIdA + '|' + c.nodeIdB;
  }

  isSkipped(c: MatchCandidate): boolean {
    return this.skippedCandidates.has(this.candidateKey(c));
  }

  isMerging(c: MatchCandidate): boolean {
    return this.mergingPair === this.candidateKey(c);
  }

  skip(c: MatchCandidate): void {
    this.skippedCandidates.add(this.candidateKey(c));
  }

  merge(candidate: MatchCandidate): void {
    const key = this.candidateKey(candidate);
    this.mergingPair = key;
    this.entityResolutionService.mergePair(candidate.nodeIdA, candidate.nodeIdB).subscribe({
      next: (result: CompactionResult) => {
        this.mergingPair = null;
        this.totalMerged++;
        const decision = result.decisions?.[0];
        this.snackBar.open(
          `Merged "${candidate.titleA}" + "${candidate.titleB}" → "${decision?.canonicalTitle || 'merged'}"`,
          'OK', { duration: 3000 }
        );
        this.candidates = this.candidates.filter(c =>
          !(c.nodeIdA === candidate.nodeIdA && c.nodeIdB === candidate.nodeIdB));
        this.cdr.markForCheck();
      },
      error: () => {
        this.mergingPair = null;
        this.snackBar.open('Merge failed', 'Close', { duration: 3000 });
      }
    });
  }

  mergeAll(): void {
    const toMerge = this.visibleCandidates;
    if (toMerge.length === 0) return;

    const hasAdvanced = this.crossTypeMerging || this.entityTypeCorrection || this.crossLanguageResolution;
    const obs = hasAdvanced
      ? this.entityResolutionService.compactAdvanced({
          threshold: this.threshold,
          factSheetId: this.data.factSheetId,
          crossTypeMerging: this.crossTypeMerging,
          entityTypeCorrection: this.entityTypeCorrection,
          crossLanguageResolution: this.crossLanguageResolution
        })
      : this.entityResolutionService.compact(this.threshold, this.data.factSheetId);

    this.loading = true;
    obs.subscribe({
      next: (result: CompactionResult) => {
        this.loading = false;
        this.totalMerged += result.entitiesMerged;
        this.snackBar.open(
          `Merged ${result.entitiesMerged} entities (${result.originalEntityCount} → ${result.finalEntityCount})`,
          'OK', { duration: 5000 }
        );
        this.loadCandidates();
      },
      error: () => {
        this.loading = false;
        this.snackBar.open('Bulk merge failed', 'Close', { duration: 3000 });
      }
    });
  }

  explain(candidate: MatchCandidate): void {
    const key = this.candidateKey(candidate);
    if (this.explanationForPair === key && this.selectedExplanation) {
      this.selectedExplanation = null;
      this.explanationForPair = null;
      return;
    }
    this.explanationLoading = true;
    this.explanationForPair = key;
    this.selectedExplanation = null;
    this.entityResolutionService.explain(candidate.nodeIdA, candidate.nodeIdB).subscribe({
      next: (explanation: MatchExplanation) => {
        this.selectedExplanation = explanation;
        this.explanationLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.explanationLoading = false;
        this.explanationForPair = null;
        this.snackBar.open('Failed to get explanation', 'Close', { duration: 3000 });
      }
    });
  }

  isExplaining(c: MatchCandidate): boolean {
    return this.explanationForPair === this.candidateKey(c);
  }

  formatThreshold(value: number): string {
    return (value * 100).toFixed(0) + '%';
  }

  getScoreClass(score: number): string {
    if (score >= 0.9) return 'high-score';
    if (score >= 0.7) return 'medium-score';
    return 'low-score';
  }

  close(): void {
    this.dialogRef.close({ merged: this.totalMerged > 0, mergeCount: this.totalMerged });
  }
}
