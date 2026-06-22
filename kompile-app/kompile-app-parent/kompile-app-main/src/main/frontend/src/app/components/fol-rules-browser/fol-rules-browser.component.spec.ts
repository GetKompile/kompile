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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { MatSnackBar } from '@angular/material/snack-bar';

import { FolRulesBrowserComponent } from './fol-rules-browser.component';
import { GraphFolRulesService, RuleDto } from '../../services/graph-fol-rules.service';

/** Helper: build a minimal RuleDto for test fixtures. */
function makeRule(
  kind: RuleDto['kind'],
  ruleText: string,
  weight = 0.8,
  hard = false,
  body = 'bodyPred(?X)',
  head = 'headPred(?X)'
): RuleDto {
  return { kind, ruleText, weight, hard, body, head };
}

const PSL_RULE = makeRule('PSL',
  '0.8: pred(?X) -> derived_pred(?X) ^2', 0.8);

const ONTOLOGY_RULE = makeRule('ONTOLOGY',
  '0.8: works_at(?X, ?Y) -> has_type_person(?X) ^2', 0.8,
  false, 'works_at(?X, ?Y)', 'has_type_person(?X)');

const FILE_RULE = makeRule('FILE_PSL',
  '0.9: knows(?X, ?Y) -> derived_knows(?X, ?Y) ^2', 0.9,
  false, 'knows(?X, ?Y)', 'derived_knows(?X, ?Y)');

const HARD_RULE = makeRule('PSL',
  'pred(?X) -> derived_pred(?X) .', Infinity, true);

describe('FolRulesBrowserComponent', () => {
  let component: FolRulesBrowserComponent;
  let fixture: ComponentFixture<FolRulesBrowserComponent>;
  let rulesSpy: jasmine.SpyObj<GraphFolRulesService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    rulesSpy = jasmine.createSpyObj('GraphFolRulesService', ['getRules']);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [FolRulesBrowserComponent, NoopAnimationsModule],
      providers: [
        { provide: GraphFolRulesService, useValue: rulesSpy },
        { provide: MatSnackBar, useValue: snackBarSpy }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(FolRulesBrowserComponent);
    component = fixture.componentInstance;
  });

  // ─── Initial state ──────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('starts with no rules and no loading', () => {
    expect(component.allRules).toEqual([]);
    expect(component.filteredRules).toEqual([]);
    expect(component.loading).toBeFalse();
  });

  // ─── No fact sheet ─────────────────────────────────────────────────────────

  it('does not call service when factSheetId is null', () => {
    component.factSheetId = null;
    fixture.detectChanges();
    expect(rulesSpy.getRules).not.toHaveBeenCalled();
  });

  // ─── Loading + rendering rules ─────────────────────────────────────────────

  it('loads rules when factSheetId is set via ngOnChanges', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE]));

    component.factSheetId = 42;
    component.ngOnChanges({ factSheetId: { currentValue: 42, previousValue: null, firstChange: true, isFirstChange: () => true } });

    expect(rulesSpy.getRules).toHaveBeenCalledWith(42);
    expect(component.allRules.length).toBe(2);
    expect(component.loading).toBeFalse();
  });

  it('shows all rules when kind filter is ALL', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE, FILE_RULE]));

    component.factSheetId = 1;
    component.refresh();

    expect(component.kindFilter).toBe('ALL');
    expect(component.filteredRules.length).toBe(3);
  });

  // ─── Kind filtering ────────────────────────────────────────────────────────

  it('filters to only PSL rules when kind filter is PSL', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE, FILE_RULE]));
    component.factSheetId = 1;
    component.refresh();

    component.kindFilter = 'PSL';
    component.onKindFilterChange();

    expect(component.filteredRules.length).toBe(1);
    expect(component.filteredRules[0].kind).toBe('PSL');
  });

  it('filters to only ONTOLOGY rules when kind filter is ONTOLOGY', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE, FILE_RULE]));
    component.factSheetId = 1;
    component.refresh();

    component.kindFilter = 'ONTOLOGY';
    component.onKindFilterChange();

    expect(component.filteredRules.length).toBe(1);
    expect(component.filteredRules[0].kind).toBe('ONTOLOGY');
  });

  it('filters to only FILE_PSL rules when kind filter is FILE_PSL', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE, FILE_RULE]));
    component.factSheetId = 1;
    component.refresh();

    component.kindFilter = 'FILE_PSL';
    component.onKindFilterChange();

    expect(component.filteredRules.length).toBe(1);
    expect(component.filteredRules[0].kind).toBe('FILE_PSL');
  });

  it('clears expanded indices when filter changes', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE]));
    component.factSheetId = 1;
    component.refresh();

    component.toggleExpand(0);
    expect(component.isExpanded(0)).toBeTrue();

    component.kindFilter = 'ONTOLOGY';
    component.onKindFilterChange();

    expect(component.expandedIndices.size).toBe(0);
  });

  // ─── Expand/collapse ───────────────────────────────────────────────────────

  it('toggleExpand opens a row and second toggle closes it', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE]));
    component.factSheetId = 1;
    component.refresh();

    expect(component.isExpanded(0)).toBeFalse();

    component.toggleExpand(0);
    expect(component.isExpanded(0)).toBeTrue();

    component.toggleExpand(0);
    expect(component.isExpanded(0)).toBeFalse();
  });

  it('multiple rows can be expanded simultaneously', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, ONTOLOGY_RULE]));
    component.factSheetId = 1;
    component.refresh();

    component.toggleExpand(0);
    component.toggleExpand(1);

    expect(component.isExpanded(0)).toBeTrue();
    expect(component.isExpanded(1)).toBeTrue();
  });

  // ─── Weight bar ────────────────────────────────────────────────────────────

  it('weightBarPct normalises correctly: max-weight rule = 1.0', () => {
    rulesSpy.getRules.and.returnValue(of([
      makeRule('PSL', 'r1', 0.4),
      makeRule('PSL', 'r2', 0.8)
    ]));
    component.factSheetId = 1;
    component.refresh();

    // maxWeight = 0.8; rule with weight 0.8 → 1.0
    expect(component.weightBarPct(component.allRules[1])).toBeCloseTo(1.0);
    // rule with weight 0.4 → 0.5
    expect(component.weightBarPct(component.allRules[0])).toBeCloseTo(0.5);
  });

  it('weightBarPct returns 1.0 for hard rules regardless of weight', () => {
    const pct = component.weightBarPct(HARD_RULE);
    expect(pct).toBe(1.0);
  });

  // ─── Count by kind ─────────────────────────────────────────────────────────

  it('countByKind returns correct counts', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE, PSL_RULE, ONTOLOGY_RULE]));
    component.factSheetId = 1;
    component.refresh();

    expect(component.countByKind('PSL')).toBe(2);
    expect(component.countByKind('ONTOLOGY')).toBe(1);
    expect(component.countByKind('FILE_PSL')).toBe(0);
  });

  // ─── Kind class / label helpers ────────────────────────────────────────────

  it('kindClass returns badge-psl for PSL kind', () => {
    expect(component.kindClass('PSL')).toBe('badge-psl');
  });

  it('kindClass returns badge-ontology for ONTOLOGY kind', () => {
    expect(component.kindClass('ONTOLOGY')).toBe('badge-ontology');
  });

  it('kindClass returns badge-file for FILE_PSL kind', () => {
    expect(component.kindClass('FILE_PSL')).toBe('badge-file');
  });

  it('kindLabel returns human-readable labels', () => {
    expect(component.kindLabel('PSL')).toBe('PSL');
    expect(component.kindLabel('ONTOLOGY')).toBe('Ontology');
    expect(component.kindLabel('FILE_PSL')).toBe('File');
  });

  // ─── Error handling ────────────────────────────────────────────────────────

  it('shows snack bar on service error', () => {
    rulesSpy.getRules.and.returnValue(throwError(() => ({ message: 'Network error' })));
    component.factSheetId = 1;
    component.refresh();

    expect(snackBarSpy.open).toHaveBeenCalledWith(
      jasmine.stringContaining('Failed to load rules'),
      'Dismiss',
      jasmine.any(Object)
    );
    expect(component.loading).toBeFalse();
  });

  it('clears rules when factSheetId changes to null', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE]));
    component.factSheetId = 1;
    component.ngOnChanges({ factSheetId: { currentValue: 1, previousValue: null, firstChange: true, isFirstChange: () => true } });

    expect(component.allRules.length).toBe(1);

    component.factSheetId = null;
    component.ngOnChanges({ factSheetId: { currentValue: null, previousValue: 1, firstChange: false, isFirstChange: () => false } });

    expect(component.allRules).toEqual([]);
    expect(component.filteredRules).toEqual([]);
  });

  // ─── Refresh resets expanded state ────────────────────────────────────────

  it('refresh resets expanded indices', () => {
    rulesSpy.getRules.and.returnValue(of([PSL_RULE]));
    component.factSheetId = 1;
    component.refresh();
    component.toggleExpand(0);

    component.refresh();

    expect(component.expandedIndices.size).toBe(0);
  });
});
