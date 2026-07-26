/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, Input, OnChanges, OnInit, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import {
  DocumentCoverageReport,
  PartitionCoverageReport,
  PartitionCoverageService,
  SubjectCoverage
} from '../../services/partition-coverage.service';

/**
 * What the graph has actually read, and what it has not.
 *
 * <p>The entity-partition pass records a durable claim per subject — the evidence it looked for,
 * the evidence it read, and the evidence it knowingly did not. This panel is the read side of
 * that. Without it the claims exist but nothing can see them, which is indistinguishable from
 * never having made them: an answer drawn from the graph looks equally confident either way.</p>
 *
 * <p>Gaps are shown, never netted out. A subject that finished holding material it could not read
 * is marked complete-with-gaps rather than complete, and the chunk ids behind every gap are listed
 * because a count cannot be acted on.</p>
 */
@Component({
  selector: 'app-partition-coverage-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatButtonModule,
    MatTooltipModule,
    MatProgressSpinnerModule
  ],
  templateUrl: './partition-coverage-panel.component.html',
  styleUrls: ['./partition-coverage-panel.component.css']
})
export class PartitionCoveragePanelComponent implements OnInit, OnChanges {

  /** Fact sheet to scope the claims to; null asks for every partition in the project. */
  @Input() factSheetId: number | null = null;

  report: PartitionCoverageReport | null = null;
  loading = false;
  errorMessage: string | null = null;

  /** Partition ids whose gap detail is expanded. */
  expanded = new Set<string>();

  documentId = '';
  documentReport: DocumentCoverageReport | null = null;
  documentLoading = false;
  documentError: string | null = null;

  constructor(private coverage: PartitionCoverageService) {}

  ngOnInit(): void {
    this.load();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId'] && !changes['factSheetId'].firstChange) {
      this.load();
    }
  }

  load(): void {
    this.loading = true;
    this.errorMessage = null;
    const request$ = this.factSheetId != null
      ? this.coverage.getCoverageForFactSheet(this.factSheetId)
      : this.coverage.getCoverage();
    request$.subscribe({
      next: report => {
        this.report = report;
        this.loading = false;
      },
      error: err => {
        // 503 means the partition components are not wired into this deployment. That is a
        // different fact from "nothing has been covered", and saying so is the point.
        this.errorMessage = err?.status === 503
          ? 'The partition store is not available in this deployment, so no coverage claims can be read.'
          : (err?.error || err?.message || 'Could not load partition coverage');
        this.report = null;
        this.loading = false;
      }
    });
  }

  lookupDocument(): void {
    const id = this.documentId.trim();
    if (!id) {
      this.documentReport = null;
      this.documentError = null;
      return;
    }
    this.documentLoading = true;
    this.documentError = null;
    this.coverage.getCoverageForDocument(id).subscribe({
      next: report => {
        this.documentReport = report;
        this.documentLoading = false;
      },
      error: err => {
        this.documentError = err?.error || err?.message || 'Could not load document coverage';
        this.documentReport = null;
        this.documentLoading = false;
      }
    });
  }

  clearDocument(): void {
    this.documentId = '';
    this.documentReport = null;
    this.documentError = null;
  }

  toggle(partitionId: string): void {
    if (this.expanded.has(partitionId)) {
      this.expanded.delete(partitionId);
    } else {
      this.expanded.add(partitionId);
    }
  }

  isExpanded(partitionId: string): boolean {
    return this.expanded.has(partitionId);
  }

  /** Percent for display; the report carries the fraction. */
  percent(fraction: number): number {
    return Math.round((fraction || 0) * 100);
  }

  /** Everything a subject still owes or could not read, in one list for the detail row. */
  gapCount(subject: SubjectCoverage): number {
    return (subject.outstanding?.length || 0)
      + (subject.inaccessible?.length || 0)
      + (subject.invalidated?.length || 0);
  }

  /**
   * How a subject's claim should read at a glance.
   *
   * <p>Complete-with-gaps is deliberately not "complete": the partition believes it is done and
   * knows it is missing evidence that exists.</p>
   */
  claimLabel(subject: SubjectCoverage): string {
    if (subject.completeWithGaps) {
      return 'Complete with gaps';
    }
    if (subject.provisionallyComplete) {
      return 'Provisionally complete';
    }
    return 'Open';
  }

  claimClass(subject: SubjectCoverage): string {
    if (subject.completeWithGaps) {
      return 'claim-gaps';
    }
    return subject.provisionallyComplete ? 'claim-complete' : 'claim-open';
  }

  entries(counts: { [key: string]: number } | null | undefined): { key: string; value: number }[] {
    if (!counts) {
      return [];
    }
    return Object.keys(counts).sort().map(key => ({ key, value: counts[key] }));
  }

  trackBySubject(_index: number, subject: SubjectCoverage): string {
    return subject.partitionId;
  }
}
