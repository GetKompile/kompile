/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import { FactsByTierPanelComponent, FactTierRow, SPECULATIVE_SATURATION_THRESHOLD } from './facts-by-tier-panel.component';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function row(overrides: Partial<FactTierRow> = {}): FactTierRow {
  return {
    atomKey: 'testAtom(X)',
    confidence: 0.8,
    band: 'HIGH',
    promotionStatus: 'NONE',
    corroborationCount: 1,
    validFrom: null,
    validTo: null,
    ...overrides,
  };
}

/** Epoch millis for 2025-01-15T00:00:00Z */
const JAN_15 = new Date('2025-01-15T00:00:00Z').getTime();
/** Epoch millis for 2025-06-01T00:00:00Z */
const JUN_01 = new Date('2025-06-01T00:00:00Z').getTime();

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

describe('FactsByTierPanelComponent', () => {
  let component: FactsByTierPanelComponent;
  let fixture: ComponentFixture<FactsByTierPanelComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        FactsByTierPanelComponent,
        HttpClientTestingModule,
        NoopAnimationsModule,
      ],
      schemas: [NO_ERRORS_SCHEMA],
    })
      .overrideComponent(FactsByTierPanelComponent, {
        set: { schemas: [NO_ERRORS_SCHEMA] },
      })
      .compileComponents();

    httpMock  = TestBed.inject(HttpTestingController);
    fixture   = TestBed.createComponent(FactsByTierPanelComponent);
    component = fixture.componentInstance;
    // Do NOT call detectChanges() here — factSheetId starts null so no requests fire;
    // individual tests that need ngOnInit call fixture.detectChanges() themselves.
  });

  afterEach(() => {
    // Drain any outstanding requests before verifying
    httpMock.match(() => true).forEach(r => r.flush({}));
    httpMock.verify();
  });

  // ── Basic creation ─────────────────────────────────────────────────────────

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  // ── D8 — Band-count stacked bar ─────────────────────────────────────────────

  describe('D8 — buildBandSegments (band-count stacked bar)', () => {

    it('returns empty array for all-zero summary', () => {
      const segs = component.buildBandSegments({ ESTABLISHED: 0, HIGH: 0 });
      expect(segs).toEqual([]);
    });

    it('returns empty array for empty summary', () => {
      expect(component.buildBandSegments({})).toEqual([]);
    });

    it('computes correct percentages summing to 100', () => {
      const summary = { ESTABLISHED: 50, HIGH: 30, PROBABLE: 20 };
      const segs = component.buildBandSegments(summary);

      const totalPct = segs.reduce((s, seg) => s + seg.pct, 0);
      expect(Math.round(totalPct)).toBe(100);
    });

    it('omits zero-count bands', () => {
      const summary = { ESTABLISHED: 10, HIGH: 0, PROBABLE: 5, SPECULATIVE: 0, SUPPRESSED: 0 };
      const segs = component.buildBandSegments(summary);

      expect(segs.map(s => s.band)).toEqual(['ESTABLISHED', 'PROBABLE']);
    });

    it('respects canonical band order (ESTABLISHED first)', () => {
      const summary = { SUPPRESSED: 3, ESTABLISHED: 10, HIGH: 5 };
      const segs = component.buildBandSegments(summary);

      expect(segs[0].band).toBe('ESTABLISHED');
      expect(segs[segs.length - 1].band).toBe('SUPPRESSED');
    });

    it('assigns the correct colour for each band', () => {
      const summary = { ESTABLISHED: 1, SUPPRESSED: 1 };
      const segs = component.buildBandSegments(summary);

      const estSeg = segs.find(s => s.band === 'ESTABLISHED')!;
      const supSeg = segs.find(s => s.band === 'SUPPRESSED')!;
      expect(estSeg.color).toBe('#4CAF50');
      expect(supSeg.color).toBe('#F44336');
    });

    it('single band segment has 100%', () => {
      const segs = component.buildBandSegments({ PROBABLE: 42 });
      expect(segs.length).toBe(1);
      expect(segs[0].pct).toBeCloseTo(100, 1);
      expect(segs[0].count).toBe(42);
    });

    it('loads band-summary endpoint on factSheetId set and populates bandSegments', fakeAsync(() => {
      component.factSheetId = 5;
      component.ngOnInit();
      tick();

      // Flush /facts first
      const factsReq = httpMock.expectOne(req => req.url.includes('/facts'));
      factsReq.flush([]);

      // Flush /band-summary
      const summaryReq = httpMock.expectOne(req => req.url.includes('/band-summary'));
      summaryReq.flush({ ESTABLISHED: 3, HIGH: 7, PROBABLE: 2 });

      tick();
      fixture.detectChanges();

      expect(component.bandSegments.length).toBeGreaterThan(0);
      const highSeg = component.bandSegments.find(s => s.band === 'HIGH')!;
      expect(highSeg.count).toBe(7);
    }));

    it('bandSegments stays empty when band-summary returns error', fakeAsync(() => {
      component.factSheetId = 6;
      component.ngOnInit();
      tick();

      const factsReq = httpMock.expectOne(req => req.url.includes('/facts'));
      factsReq.flush([]);

      const summaryReq = httpMock.expectOne(req => req.url.includes('/band-summary'));
      summaryReq.flush({ error: 'unavailable' }, { status: 503, statusText: 'Service Unavailable' });

      tick();
      fixture.detectChanges();

      expect(component.bandSegments).toEqual([]);
    }));
  });

  // ── D7 — Temporal filter ────────────────────────────────────────────────────

  describe('D7 — temporal filter UI state', () => {

    it('initial temporal state is empty / false', () => {
      expect(component.validFromDate).toBe('');
      expect(component.validToDate).toBe('');
      expect(component.excludeUndated).toBeFalse();
    });

    it('clearTemporalFilter resets all temporal fields (no factSheetId → no HTTP)', () => {
      component.validFromDate  = '2025-01-01';
      component.validToDate    = '2025-06-30';
      component.excludeUndated = true;

      component.clearTemporalFilter(); // factSheetId is null → no HTTP

      expect(component.validFromDate).toBe('');
      expect(component.validToDate).toBe('');
      expect(component.excludeUndated).toBeFalse();
    });

    it('loadFacts includes validFrom query param when validFromDate is set', fakeAsync(() => {
      component.factSheetId   = 7;
      component.validFromDate = '2025-01-15';
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      const url = req.request.urlWithParams;
      expect(url).toContain('validFrom=');
      const match = url.match(/validFrom=(\d+)/);
      expect(match).toBeTruthy();
      expect(parseInt(match![1], 10)).toBe(JAN_15);
      req.flush([]);
    }));

    it('loadFacts includes validTo + 86399999 (end-of-day) when validToDate is set', fakeAsync(() => {
      component.factSheetId  = 7;
      component.validToDate  = '2025-01-15';
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      const url = req.request.urlWithParams;
      const match = url.match(/validTo=(\d+)/);
      expect(match).toBeTruthy();
      expect(parseInt(match![1], 10)).toBe(JAN_15 + 86399999);
      req.flush([]);
    }));

    it('loadFacts includes excludeUndated=true when flag is set', fakeAsync(() => {
      component.factSheetId    = 7;
      component.excludeUndated = true;
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      expect(req.request.urlWithParams).toContain('excludeUndated=true');
      req.flush([]);
    }));

    it('loadFacts sends no temporal params when fields are empty', fakeAsync(() => {
      component.factSheetId    = 7;
      component.validFromDate  = '';
      component.validToDate    = '';
      component.excludeUndated = false;
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      expect(req.request.urlWithParams).not.toContain('validFrom');
      expect(req.request.urlWithParams).not.toContain('validTo');
      expect(req.request.urlWithParams).not.toContain('excludeUndated');
      req.flush([]);
    }));

    it('rows are sorted by confidence descending even with temporal fields', fakeAsync(() => {
      component.factSheetId = 8;
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      req.flush([
        row({ atomKey: 'a', confidence: 0.5, validFrom: JAN_15 }),
        row({ atomKey: 'b', confidence: 0.9, validFrom: JUN_01 }),
        row({ atomKey: 'c', confidence: 0.7, validFrom: null }),
      ]);

      tick();
      fixture.detectChanges();

      expect(component.rows.map(r => r.atomKey)).toEqual(['b', 'c', 'a']);
    }));
  });

  // ── FactTierRow interface ──────────────────────────────────────────────────

  describe('FactTierRow shape', () => {

    it('rows with validFrom/validTo null are accepted', fakeAsync(() => {
      component.factSheetId = 9;
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      req.flush([ row({ validFrom: null, validTo: null }) ]);

      tick();
      fixture.detectChanges();

      expect(component.rows[0].validFrom).toBeNull();
      expect(component.rows[0].validTo).toBeNull();
    }));

    it('rows with validFrom/validTo set are stored intact', fakeAsync(() => {
      component.factSheetId = 10;
      component.loadFacts();
      tick();

      const req = httpMock.expectOne(r => r.url.includes('/facts'));
      req.flush([ row({ validFrom: JAN_15, validTo: JUN_01 }) ]);

      tick();
      fixture.detectChanges();

      expect(component.rows[0].validFrom).toBe(JAN_15);
      expect(component.rows[0].validTo).toBe(JUN_01);
    }));
  });

  // ── displayedColumns ──────────────────────────────────────────────────────

  describe('displayedColumns', () => {

    it('includes validFrom and validTo columns', () => {
      expect(component.displayedColumns).toContain('validFrom');
      expect(component.displayedColumns).toContain('validTo');
    });
  });

  // ── bandColor helper ──────────────────────────────────────────────────────

  describe('bandColor', () => {

    it('returns correct colour for known bands', () => {
      expect(component.bandColor('ESTABLISHED')).toBe('#4CAF50');
      expect(component.bandColor('SUPPRESSED')).toBe('#F44336');
    });

    it('returns grey for unknown band', () => {
      expect(component.bandColor('UNKNOWN')).toBe('#9E9E9E');
    });
  });

  // ── D2: SPECULATIVE-saturation banner ────────────────────────────────────

  describe('D2 — isSpeculativeSaturated (cold-start banner)', () => {

    it('SPECULATIVE_SATURATION_THRESHOLD is 0.8', () => {
      expect(SPECULATIVE_SATURATION_THRESHOLD).toBeCloseTo(0.8);
    });

    it('returns false when rows and bandSummaryRaw are both empty', () => {
      component.rows = [];
      component.bandSummaryRaw = {};
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('returns true when bandSummaryRaw is 100% SPECULATIVE', () => {
      component.bandSummaryRaw = { SPECULATIVE: 7 };
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('returns true when bandSummaryRaw is exactly 80% SPECULATIVE', () => {
      component.bandSummaryRaw = { SPECULATIVE: 4, HIGH: 1 };
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('returns false when bandSummaryRaw is 79% SPECULATIVE', () => {
      component.bandSummaryRaw = { SPECULATIVE: 79, ESTABLISHED: 21 };
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('falls back to row scan when bandSummaryRaw is empty', () => {
      component.bandSummaryRaw = {};
      component.rows = [
        row({ band: 'SPECULATIVE' }),
        row({ band: 'SPECULATIVE' }),
        row({ band: 'SPECULATIVE' }),
        row({ band: 'SPECULATIVE' }),
        row({ band: 'HIGH' }),
      ];
      // 4/5 = 0.8 → at threshold → true
      expect(component.isSpeculativeSaturated).toBeTrue();
    });

    it('row scan returns false when below threshold', () => {
      component.bandSummaryRaw = {};
      component.rows = [
        row({ band: 'SPECULATIVE' }),
        row({ band: 'ESTABLISHED' }),
        row({ band: 'ESTABLISHED' }),
      ];
      // 1/3 ≈ 0.33 → false
      expect(component.isSpeculativeSaturated).toBeFalse();
    });

    it('bandSummaryRaw is populated by loadBandSummary response', fakeAsync(() => {
      component.factSheetId = 11;
      component.ngOnInit();
      tick();

      const factsReq = httpMock.expectOne(req => req.url.includes('/facts'));
      factsReq.flush([]);

      const summaryReq = httpMock.expectOne(req => req.url.includes('/band-summary'));
      summaryReq.flush({ SPECULATIVE: 9, ESTABLISHED: 1 });

      tick();
      fixture.detectChanges();

      expect(component.bandSummaryRaw['SPECULATIVE']).toBe(9);
      expect(component.isSpeculativeSaturated).toBeTrue();
    }));
  });
});
