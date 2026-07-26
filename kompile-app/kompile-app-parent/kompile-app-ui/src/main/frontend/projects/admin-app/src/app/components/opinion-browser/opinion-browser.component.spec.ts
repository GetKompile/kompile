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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { OpinionBrowserComponent, BasisTypeValue, SPECULATIVE_SATURATION_THRESHOLD } from './opinion-browser.component';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Minimal FactOpinionRow shape used in tests (mirrors the component interface). */
interface FactOpinionRow {
  atomKey: string;
  confidence: number;
  band: string;
  promotionStatus: string;
  corroborationCount: number;
  belief: number | null;
  disbelief: number | null;
  uncertainty: number | null;
  expectation: number | null;
  baseRate: number | null;
  basisType: string | null;
}

function makeRow(overrides: Partial<FactOpinionRow> = {}): FactOpinionRow {
  return {
    atomKey: 'test:fact',
    confidence: 0.5,
    band: 'PROBABLE',
    promotionStatus: 'PENDING',
    corroborationCount: 1,
    belief: null,
    disbelief: null,
    uncertainty: null,
    expectation: null,
    baseRate: null,
    basisType: 'LLM_EXTRACTION',
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('OpinionBrowserComponent', () => {
  let component: OpinionBrowserComponent;
  let fixture: ComponentFixture<OpinionBrowserComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        OpinionBrowserComponent,
        NoopAnimationsModule,
        HttpClientTestingModule,
      ],
      schemas: [NO_ERRORS_SCHEMA],
    })
      .overrideComponent(OpinionBrowserComponent, {
        set: { schemas: [NO_ERRORS_SCHEMA] },
      })
      .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(OpinionBrowserComponent);
    component = fixture.componentInstance;
    // Do NOT call detectChanges() here — individual tests control initialization
    // to avoid spurious HTTP requests before factSheetId is set.
  });

  afterEach(() => {
    // Drain any outstanding requests (e.g. band-summary) before verifying
    httpMock.match(() => true).forEach(r => r.flush({}));
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. toOpinionDto returns undefined when any opinion field is null
  // ─────────────────────────────────────────────────────────────────────────

  it('toOpinionDto returns undefined when fields are null', () => {
    const row = makeRow({ belief: null, disbelief: null, uncertainty: null, expectation: null });
    expect((component as any).toOpinionDto(row)).toBeUndefined();
  });

  it('toOpinionDto returns undefined when only some fields are null', () => {
    const row = makeRow({ belief: 0.6, disbelief: 0.2, uncertainty: null, expectation: 0.7 });
    expect((component as any).toOpinionDto(row)).toBeUndefined();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. toOpinionDto returns OpinionDto when all fields are present
  // ─────────────────────────────────────────────────────────────────────────

  it('toOpinionDto returns OpinionDto when all fields present', () => {
    const row = makeRow({ belief: 0.6, disbelief: 0.2, uncertainty: 0.2, expectation: 0.7 });
    const dto = (component as any).toOpinionDto(row);
    expect(dto).toEqual({ belief: 0.6, disbelief: 0.2, uncertainty: 0.2, expectation: 0.7 });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. bandSummaryKeys sorts in canonical ESTABLISHED → SUPPRESSED order
  // ─────────────────────────────────────────────────────────────────────────

  it('bandSummaryKeys sorts in canonical ESTABLISHED→SUPPRESSED order', () => {
    component.bandSummary = { SPECULATIVE: 3, ESTABLISHED: 10, HIGH: 5 };
    expect(component.bandSummaryKeys).toEqual(['ESTABLISHED', 'HIGH', 'SPECULATIVE']);
  });

  it('bandSummaryKeys places unknown bands at the end', () => {
    component.bandSummary = { UNKNOWN_BAND: 1, PROBABLE: 4 };
    const keys = component.bandSummaryKeys;
    expect(keys.indexOf('PROBABLE')).toBeLessThan(keys.indexOf('UNKNOWN_BAND'));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. loadData fires GET /api/kb-grounding/{id}/opinions with correct params
  // ─────────────────────────────────────────────────────────────────────────

  it('loadData fires GET /kb-grounding/{id}/opinions with tier param when tier is set', fakeAsync(() => {
    component.factSheetId = 42;
    component.selectedTier = 'HIGH';
    component.searchQuery = '';
    component.maxUncertainty = null;
    component.minExpectation = null;

    component.loadData();
    tick();

    const opinionsReq = httpMock.expectOne(req =>
      req.url.includes('/kb-grounding/42/opinions') && req.url.includes('tier=HIGH')
    );
    expect(opinionsReq.request.method).toBe('GET');
    opinionsReq.flush([]);

    // Flush the band-summary request that loadData also fires
    const summaryReq = httpMock.expectOne(req => req.url.includes('/kb-grounding/42/band-summary'));
    summaryReq.flush({});
  }));

  // ─────────────────────────────────────────────────────────────────────────
  // 6. loadData populates rows sorted by confidence descending
  // ─────────────────────────────────────────────────────────────────────────

  it('loadData populates rows sorted by confidence descending', fakeAsync(() => {
    component.factSheetId = 42;
    component.selectedTier = null;
    component.searchQuery = '';
    component.maxUncertainty = null;
    component.minExpectation = null;

    component.loadData();
    tick();

    const opinionsReq = httpMock.expectOne(req => req.url.includes('/kb-grounding/42/opinions'));

    const serverData: FactOpinionRow[] = [
      makeRow({ atomKey: 'a', confidence: 0.3, band: 'PROBABLE' }),
      makeRow({ atomKey: 'b', confidence: 0.8, band: 'ESTABLISHED' }),
    ];
    opinionsReq.flush(serverData);
    tick();

    // Also flush the band-summary request
    const summaryReq = httpMock.expectOne(req => req.url.includes('/kb-grounding/42/band-summary'));
    summaryReq.flush({});

    expect(component.rows.length).toBe(2);
    expect(component.rows[0].atomKey).toBe('b');
    expect(component.rows[1].atomKey).toBe('a');
    expect(component.loading).toBeFalse();
  }));

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Error path
  // ─────────────────────────────────────────────────────────────────────────

  it('loadData sets error message on HTTP error', fakeAsync(() => {
    component.factSheetId = 42;
    component.selectedTier = null;
    component.searchQuery = '';
    component.maxUncertainty = null;
    component.minExpectation = null;

    component.loadData();
    tick();

    const opinionsReq = httpMock.expectOne(req => req.url.includes('/kb-grounding/42/opinions'));
    opinionsReq.flush({ error: 'Not authorized' }, { status: 403, statusText: 'Forbidden' });
    tick();

    // band-summary also fires; swallow it (error is OK per component logic)
    const summaryReq = httpMock.expectOne(req => req.url.includes('/kb-grounding/42/band-summary'));
    summaryReq.flush({}, { status: 500, statusText: 'Server Error' });

    expect(component.error).toBeTruthy();
    expect(component.loading).toBeFalse();
  }));

  // ─────────────────────────────────────────────────────────────────────────
  // D4: BasisType filter tests
  // ─────────────────────────────────────────────────────────────────────────

  describe('D4: BasisType filter', () => {

    it('basisTypeColor returns correct colour for LLM_EXTRACTION', () => {
      expect(component.basisTypeColor('LLM_EXTRACTION')).toBe('#9C27B0');
    });

    it('basisTypeColor returns correct colour for STRUCTURAL', () => {
      expect(component.basisTypeColor('STRUCTURAL')).toBe('#2196F3');
    });

    it('basisTypeColor returns grey for unknown basis type', () => {
      expect(component.basisTypeColor('UNKNOWN')).toBe('#9E9E9E');
    });

    it('basisTypeColor returns grey for null basis type', () => {
      expect(component.basisTypeColor(null)).toBe('#9E9E9E');
    });

    it('basisTypeAbbrev returns LLM for LLM_EXTRACTION', () => {
      expect(component.basisTypeAbbrev('LLM_EXTRACTION')).toBe('LLM');
    });

    it('basisTypeAbbrev returns STRUCT for STRUCTURAL', () => {
      expect(component.basisTypeAbbrev('STRUCTURAL')).toBe('STRUCT');
    });

    it('basisTypeAbbrev returns PSL for PSL_INFERENCE', () => {
      expect(component.basisTypeAbbrev('PSL_INFERENCE')).toBe('PSL');
    });

    it('basisTypeAbbrev returns MEBN for MEBN_INFERENCE', () => {
      expect(component.basisTypeAbbrev('MEBN_INFERENCE')).toBe('MEBN');
    });

    it('basisTypeAbbrev returns CORR for CORROBORATION', () => {
      expect(component.basisTypeAbbrev('CORROBORATION')).toBe('CORR');
    });

    it('basisTypeAbbrev returns ASSERT for ASSERTED', () => {
      expect(component.basisTypeAbbrev('ASSERTED')).toBe('ASSERT');
    });

    it('toggleBasisType null clears selection', () => {
      component.selectedBasisTypes.add('LLM_EXTRACTION');
      component.selectedBasisTypes.add('PSL_INFERENCE');
      component.rows = [];
      component.toggleBasisType(null);
      expect(component.selectedBasisTypes.size).toBe(0);
    });

    it('toggleBasisType adds a type when not present', () => {
      component.rows = [];
      component.toggleBasisType('STRUCTURAL');
      expect(component.selectedBasisTypes.has('STRUCTURAL')).toBeTrue();
    });

    it('toggleBasisType removes a type when already selected', () => {
      component.rows = [];
      component.selectedBasisTypes.add('STRUCTURAL');
      component.toggleBasisType('STRUCTURAL');
      expect(component.selectedBasisTypes.has('STRUCTURAL')).toBeFalse();
    });

    it('clientFilter keeps only rows matching selected basisTypes', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'a', basisType: 'STRUCTURAL' }),
        makeRow({ atomKey: 'b', basisType: 'LLM_EXTRACTION' }),
        makeRow({ atomKey: 'c', basisType: 'PSL_INFERENCE' }),
      ];
      component.selectedBasisTypes = new Set<BasisTypeValue>(['STRUCTURAL', 'PSL_INFERENCE']);
      component.searchQuery = '';
      const filtered = component.clientFilter(rows);
      expect(filtered.length).toBe(2);
      expect(filtered.map(r => r.atomKey)).toContain('a');
      expect(filtered.map(r => r.atomKey)).toContain('c');
      expect(filtered.map(r => r.atomKey)).not.toContain('b');
    });

    it('clientFilter passes all rows when selectedBasisTypes is empty', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'a', basisType: 'STRUCTURAL' }),
        makeRow({ atomKey: 'b', basisType: 'LLM_EXTRACTION' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = '';
      const filtered = component.clientFilter(rows);
      expect(filtered.length).toBe(2);
    });

    it('clientFilter treats null basisType as LLM_EXTRACTION for filtering', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'a', basisType: null }),
        makeRow({ atomKey: 'b', basisType: 'STRUCTURAL' }),
      ];
      component.selectedBasisTypes = new Set<BasisTypeValue>(['LLM_EXTRACTION']);
      component.searchQuery = '';
      const filtered = component.clientFilter(rows);
      // null basisType is treated as LLM_EXTRACTION
      expect(filtered.length).toBe(1);
      expect(filtered[0].atomKey).toBe('a');
    });

    it('filteredRows is updated when toggleBasisType is called with loaded rows', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'a', basisType: 'STRUCTURAL' }),
        makeRow({ atomKey: 'b', basisType: 'LLM_EXTRACTION' }),
      ];
      component.rows = rows;
      component.searchQuery = '';
      component.toggleBasisType('STRUCTURAL');
      expect(component.filteredRows.length).toBe(1);
      expect(component.filteredRows[0].atomKey).toBe('a');
    });

    it('basisTypeChips contains an entry for all 6 types plus All', () => {
      const values = component.basisTypeChips.map(c => c.value);
      expect(values).toContain(null);          // All
      expect(values).toContain('STRUCTURAL');
      expect(values).toContain('LLM_EXTRACTION');
      expect(values).toContain('PSL_INFERENCE');
      expect(values).toContain('MEBN_INFERENCE');
      expect(values).toContain('CORROBORATION');
      expect(values).toContain('ASSERTED');
      expect(values.length).toBe(7);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // D3: Simplex inspector tests
  // ─────────────────────────────────────────────────────────────────────────

  describe('D3: Simplex inspector', () => {

    it('simplexTrianglePoints returns a non-empty string', () => {
      const pts = component.simplexTrianglePoints();
      expect(typeof pts).toBe('string');
      expect(pts.length).toBeGreaterThan(0);
      // Should contain three coordinate pairs
      const pairs = pts.trim().split(' ');
      expect(pairs.length).toBe(3);
    });

    it('simplexOpinionPoint with b=1 d=0 u=0 returns point at T vertex', () => {
      const row = makeRow({ belief: 1.0, disbelief: 0.0, uncertainty: 0.0 });
      const pt = component.simplexOpinionPoint(row);
      expect(pt.x).toBeCloseTo(component.SIMPLEX_T.x, 5);
      expect(pt.y).toBeCloseTo(component.SIMPLEX_T.y, 5);
    });

    it('simplexOpinionPoint with b=0 d=1 u=0 returns point at F vertex', () => {
      const row = makeRow({ belief: 0.0, disbelief: 1.0, uncertainty: 0.0 });
      const pt = component.simplexOpinionPoint(row);
      expect(pt.x).toBeCloseTo(component.SIMPLEX_F.x, 5);
      expect(pt.y).toBeCloseTo(component.SIMPLEX_F.y, 5);
    });

    it('simplexOpinionPoint with b=0 d=0 u=1 returns point at V vertex', () => {
      const row = makeRow({ belief: 0.0, disbelief: 0.0, uncertainty: 1.0 });
      const pt = component.simplexOpinionPoint(row);
      expect(pt.x).toBeCloseTo(component.SIMPLEX_V.x, 5);
      expect(pt.y).toBeCloseTo(component.SIMPLEX_V.y, 5);
    });

    it('simplexOpinionPoint with equal b=d=u=1/3 returns centroid of triangle', () => {
      const row = makeRow({ belief: 1/3, disbelief: 1/3, uncertainty: 1/3 });
      const pt = component.simplexOpinionPoint(row);
      const T = component.SIMPLEX_T;
      const F = component.SIMPLEX_F;
      const V = component.SIMPLEX_V;
      const cx = (T.x + F.x + V.x) / 3;
      const cy = (T.y + F.y + V.y) / 3;
      expect(pt.x).toBeCloseTo(cx, 4);
      expect(pt.y).toBeCloseTo(cy, 4);
    });

    it('simplexOpinionPoint handles all-null b/d/u gracefully', () => {
      const row = makeRow({ belief: null, disbelief: null, uncertainty: null });
      // Should not throw — nulls default to 0
      expect(() => component.simplexOpinionPoint(row)).not.toThrow();
    });

    it('toggleExpand sets expandedRow when row is different', () => {
      const row = makeRow({ atomKey: 'x' });
      component.toggleExpand(row);
      expect(component.expandedRow).toBe(row);
    });

    it('toggleExpand collapses when same row is toggled twice', () => {
      const row = makeRow({ atomKey: 'x' });
      component.toggleExpand(row);
      component.toggleExpand(row);
      expect(component.expandedRow).toBeNull();
    });

    it('toggleExpand switches to the newly clicked row', () => {
      const rowA = makeRow({ atomKey: 'a' });
      const rowB = makeRow({ atomKey: 'b' });
      component.toggleExpand(rowA);
      component.toggleExpand(rowB);
      expect(component.expandedRow).toBe(rowB);
    });

    it('displayedColumns contains expand and basisType columns', () => {
      expect(component.displayedColumns).toContain('expand');
      expect(component.displayedColumns).toContain('basisType');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // D5: Corpus-level search tests
  // ─────────────────────────────────────────────────────────────────────────

  describe('D5: Corpus-level text search', () => {

    it('clientFilter returns all rows when searchQuery is empty', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'worksAt(alice, acme)' }),
        makeRow({ atomKey: 'worksAt(bob, globex)' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = '';
      expect(component.clientFilter(rows).length).toBe(2);
    });

    it('clientFilter matches on subject within atomKey', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'worksAt(alice, acme)' }),
        makeRow({ atomKey: 'worksAt(bob, globex)' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = 'alice';
      const filtered = component.clientFilter(rows);
      expect(filtered.length).toBe(1);
      expect(filtered[0].atomKey).toContain('alice');
    });

    it('clientFilter matches on predicate within atomKey', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'worksAt(alice, acme)' }),
        makeRow({ atomKey: 'likes(alice, pizza)' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = 'worksat';
      const filtered = component.clientFilter(rows);
      expect(filtered.length).toBe(1);
      expect(filtered[0].atomKey).toContain('worksAt');
    });

    it('clientFilter is case-insensitive', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'WORKSATA(ALICE, ACME)' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = 'alice';
      expect(component.clientFilter(rows).length).toBe(1);
    });

    it('clientFilter returns empty list when no rows match', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'worksAt(alice, acme)' }),
      ];
      component.selectedBasisTypes = new Set();
      component.searchQuery = 'zombo';
      expect(component.clientFilter(rows).length).toBe(0);
    });

    it('applyClientFilters updates filteredRows from loaded rows without fetching', fakeAsync(() => {
      // Pre-populate rows (simulating a previous loadData call)
      component.rows = [
        makeRow({ atomKey: 'worksAt(alice, acme)' }),
        makeRow({ atomKey: 'worksAt(bob, globex)' }),
      ];
      component.filteredRows = [...component.rows];
      component.selectedBasisTypes = new Set();

      component.searchQuery = 'bob';
      component.applyClientFilters();

      // Must NOT have fired any HTTP request
      httpMock.expectNone(() => true);

      expect(component.filteredRows.length).toBe(1);
      expect(component.filteredRows[0].atomKey).toContain('bob');
    }));

    it('loadData passes q param to the server URL', fakeAsync(() => {
      component.factSheetId = 7;
      component.searchQuery = 'acme';
      component.selectedTier = null;
      component.maxUncertainty = null;
      component.minExpectation = null;

      component.loadData();
      tick();

      const req = httpMock.expectOne(r =>
        r.url.includes('/kb-grounding/7/opinions') && r.url.includes('q=acme')
      );
      expect(req.request.method).toBe('GET');
      req.flush([]);

      const summary = httpMock.expectOne(r => r.url.includes('/kb-grounding/7/band-summary'));
      summary.flush({});
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Combined D4+D5: basisType filter + text search
  // ─────────────────────────────────────────────────────────────────────────

  describe('Combined D4+D5 filters', () => {

    it('clientFilter applies both basisType and text search simultaneously', () => {
      const rows: FactOpinionRow[] = [
        makeRow({ atomKey: 'worksAt(alice, acme)',  basisType: 'STRUCTURAL' }),
        makeRow({ atomKey: 'worksAt(bob, globex)',  basisType: 'LLM_EXTRACTION' }),
        makeRow({ atomKey: 'likes(alice, pizza)',   basisType: 'LLM_EXTRACTION' }),
        makeRow({ atomKey: 'worksAt(carol, acme)',  basisType: 'STRUCTURAL' }),
      ];
      component.selectedBasisTypes = new Set<BasisTypeValue>(['STRUCTURAL']);
      component.searchQuery = 'alice';
      const filtered = component.clientFilter(rows);
      // Each predicate on its own keeps two rows — STRUCTURAL matches rows 0 and 3, 'alice' matches
      // rows 0 and 2 — so only a genuine AND of the two leaves exactly one. A fixture where both
      // filters select the same rows cannot tell "both applied" apart from "either applied".
      expect(filtered.length).toBe(1);
      expect(filtered[0].atomKey).toBe('worksAt(alice, acme)');
      expect(filtered[0].basisType).toBe('STRUCTURAL');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // D2: SPECULATIVE-saturation banner
  // ─────────────────────────────────────────────────────────────────────────

  describe('D2: SPECULATIVE saturation banner', () => {

    it('SPECULATIVE_SATURATION_THRESHOLD is 0.8', () => {
      expect(SPECULATIVE_SATURATION_THRESHOLD).toBeCloseTo(0.8);
    });

    it('isSpeculativeSaturated is false when no rows and no bandSummary', () => {
      component.rows = [];
      component.bandSummary = {};
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('isSpeculativeSaturated is true when bandSummary is 100% SPECULATIVE', () => {
      component.bandSummary = { SPECULATIVE: 10 };
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('isSpeculativeSaturated is true when bandSummary is exactly 80% SPECULATIVE', () => {
      component.bandSummary = { SPECULATIVE: 8, PROBABLE: 2 };
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('isSpeculativeSaturated is false when bandSummary is 79% SPECULATIVE', () => {
      component.bandSummary = { SPECULATIVE: 79, ESTABLISHED: 21 };
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('isSpeculativeSaturated falls back to row scan when bandSummary is empty', () => {
      component.bandSummary = {};
      component.rows = [
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'ESTABLISHED' }),
      ];
      // 4/5 = 0.8 — exactly at threshold → true
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('isSpeculativeSaturated is false via row scan when below threshold', () => {
      component.bandSummary = {};
      component.rows = [
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'ESTABLISHED' }),
        makeRow({ band: 'ESTABLISHED' }),
        makeRow({ band: 'ESTABLISHED' }),
      ];
      // 2/5 = 0.4 → false
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('bandSummary takes priority over row-level scan', () => {
      // bandSummary says 20% SPECULATIVE but rows (if scanned) would say 100%
      component.bandSummary = { SPECULATIVE: 1, ESTABLISHED: 4 };
      component.rows = [
        makeRow({ band: 'SPECULATIVE' }),
        makeRow({ band: 'SPECULATIVE' }),
      ];
      expect(component.isSpeculativeSaturated).toBeFalse();
    });
  });
});
