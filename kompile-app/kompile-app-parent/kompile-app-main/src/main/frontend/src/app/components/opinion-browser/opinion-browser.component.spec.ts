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

import { OpinionBrowserComponent } from './opinion-browser.component';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Minimal FactOpinionRow shape used in tests (mirrors the private interface). */
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
  // Additional: error path
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
});
