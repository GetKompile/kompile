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

import { Component, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatChipsModule } from '@angular/material/chips';
import { GraphFolRulesService, RuleDto } from '../../services/graph-fol-rules.service';

/** All valid rule kind filter values (plus 'ALL' for no filter). */
export type RuleKindFilter = 'ALL' | 'PSL' | 'ONTOLOGY' | 'FILE_PSL';

/**
 * Standalone FOL / Ontology Rule Browser panel.
 *
 * Displays all active rules for a fact sheet — soft PSL propagation rules, ontology-derived
 * DOMAIN/RANGE rules, and project-level .psl file rules — grouped by kind and filterable.
 * Each row shows the rule text + a weight bar; clicking a row expands the parsed head/body.
 *
 * Selector: app-fol-rules-browser
 * Import path: app/components/fol-rules-browser/fol-rules-browser.component
 */
@Component({
  selector: 'app-fol-rules-browser',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatChipsModule
  ],
  templateUrl: './fol-rules-browser.component.html',
  styleUrls: ['./fol-rules-browser.component.css']
})
export class FolRulesBrowserComponent implements OnChanges {

  /** The fact sheet whose rules to display. Pass null to show the empty state. */
  @Input() factSheetId: number | null = null;

  /** All rules returned by the backend. */
  allRules: RuleDto[] = [];

  /** The currently visible rules after applying the kind filter. */
  filteredRules: RuleDto[] = [];

  /** Which rule kind is currently selected in the filter dropdown. */
  kindFilter: RuleKindFilter = 'ALL';

  /** Set of expanded rule indices (by position in filteredRules). */
  expandedIndices = new Set<number>();

  /** Maximum absolute weight across all rules (used to normalise the weight bar). */
  maxWeight = 1.0;

  loading = false;

  readonly kindOptions: { value: RuleKindFilter; label: string }[] = [
    { value: 'ALL',      label: 'All kinds' },
    { value: 'PSL',      label: 'PSL (FactStore)' },
    { value: 'ONTOLOGY', label: 'Ontology (DOMAIN/RANGE)' },
    { value: 'FILE_PSL', label: 'File (.psl)' }
  ];

  constructor(
    private rulesService: GraphFolRulesService,
    private snackBar: MatSnackBar
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['factSheetId']) {
      this.allRules = [];
      this.filteredRules = [];
      this.expandedIndices.clear();
      if (this.factSheetId != null) {
        this.refresh();
      }
    }
  }

  refresh(): void {
    if (this.factSheetId == null) return;
    this.loading = true;
    this.expandedIndices.clear();
    this.rulesService.getRules(this.factSheetId).subscribe({
      next: (rules) => {
        this.allRules = rules;
        this.maxWeight = this.computeMaxWeight(rules);
        this.applyFilter();
        this.loading = false;
      },
      error: (err) => {
        this.loading = false;
        this.error('Failed to load rules', err);
      }
    });
  }

  onKindFilterChange(): void {
    this.expandedIndices.clear();
    this.applyFilter();
  }

  toggleExpand(index: number): void {
    if (this.expandedIndices.has(index)) {
      this.expandedIndices.delete(index);
    } else {
      this.expandedIndices.add(index);
    }
  }

  isExpanded(index: number): boolean {
    return this.expandedIndices.has(index);
  }

  /**
   * Normalize a rule's weight to 0..1 for the progress bar.
   * Hard rules (infinite weight) are capped at 1.0.
   */
  weightBarPct(rule: RuleDto): number {
    if (rule.hard || !isFinite(rule.weight)) return 1.0;
    if (this.maxWeight <= 0) return 0;
    return Math.min(rule.weight / this.maxWeight, 1.0);
  }

  /** Count rules of a given kind in allRules. */
  countByKind(kind: string): number {
    return this.allRules.filter(r => r.kind === kind).length;
  }

  /** CSS class for the kind badge. */
  kindClass(kind: string): string {
    switch (kind) {
      case 'ONTOLOGY': return 'badge-ontology';
      case 'FILE_PSL': return 'badge-file';
      default:         return 'badge-psl';
    }
  }

  /** Human-readable label for a kind tag. */
  kindLabel(kind: string): string {
    switch (kind) {
      case 'ONTOLOGY': return 'Ontology';
      case 'FILE_PSL': return 'File';
      default:         return 'PSL';
    }
  }

  /** Tooltip text explaining the origin of a rule kind. */
  kindTooltip(kind: string): string {
    switch (kind) {
      case 'ONTOLOGY':
        return 'Ontology rule — derived from the bound ontology\'s DOMAIN/RANGE axioms (e.g. a typing rule asserting the subject of works_at is a Person). Generated at the ontology rule weight.';
      case 'FILE_PSL':
        return 'File rule — hand-authored in a project .psl file. Use these to encode domain knowledge the auto-generator cannot infer.';
      default:
        return 'PSL rule — auto-generated each cascade from the FactStore. One soft propagation rule per observed predicate, starting at the default weight and then weight-learned online.';
    }
  }

  private applyFilter(): void {
    if (this.kindFilter === 'ALL') {
      this.filteredRules = [...this.allRules];
    } else {
      this.filteredRules = this.allRules.filter(r => r.kind === this.kindFilter);
    }
  }

  private computeMaxWeight(rules: RuleDto[]): number {
    let max = 1.0;
    for (const r of rules) {
      if (isFinite(r.weight) && r.weight > max) {
        max = r.weight;
      }
    }
    return max;
  }

  private error(prefix: string, err: any): void {
    const msg = err?.error?.message || err?.message || 'unknown error';
    this.snackBar.open(`${prefix}: ${msg}`, 'Dismiss', { duration: 5000 });
  }
}
