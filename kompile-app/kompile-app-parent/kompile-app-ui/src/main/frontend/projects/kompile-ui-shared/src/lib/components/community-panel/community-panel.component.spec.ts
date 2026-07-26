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
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { SimpleChange } from '@angular/core';
import { CommunityPanelComponent } from './community-panel.component';

describe('CommunityPanelComponent', () => {
  let component: CommunityPanelComponent;
  let fixture: ComponentFixture<CommunityPanelComponent>;
  let httpMock: HttpTestingController;

  const FACT_SHEET_ID = 42;

  /** Minimal detect-response with two communities. */
  const detectResponse = {
    communityCount: 2,
    modularity: 0.42,
    nodeCount: 3,
    nodeToCommunit: { node1: 0, node2: 0, node3: 1 },
    communities: { '0': ['node1', 'node2'], '1': ['node3'] }
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        CommunityPanelComponent,
        HttpClientTestingModule,
        NoopAnimationsModule
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(CommunityPanelComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Basic rendering
  // ──────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should show empty-state prompt when no factSheetId is set', () => {
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.empty-state')).toBeTruthy();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // State reset on factSheetId change
  // ──────────────────────────────────────────────────────────────────────────

  it('should clear summaries and summarizingId when factSheetId changes', () => {
    component.summaries = { 0: 'old summary' };
    component.summaryError = { 0: 'old error' };
    component.summarizingId = 0;

    component.factSheetId = FACT_SHEET_ID;
    component.ngOnChanges({
      factSheetId: new SimpleChange(null, FACT_SHEET_ID, false)
    });

    expect(component.summaries).toEqual({});
    expect(component.summaryError).toEqual({});
    expect(component.summarizingId).toBeNull();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // detect() — clears summary state before each new detection run
  // ──────────────────────────────────────────────────────────────────────────

  it('detect() should clear summaries and issue GET communities request', () => {
    component.factSheetId = FACT_SHEET_ID;
    component.summaries = { 0: 'stale' };
    component.summaryError = { 0: 'stale error' };

    component.detect();

    expect(component.summaries).toEqual({});
    expect(component.summaryError).toEqual({});
    expect(component.loading).toBeTrue();

    const req = httpMock.expectOne((r) =>
      r.url.includes(`/graph/${FACT_SHEET_ID}/communities`)
      && !r.url.includes('/summary')
    );
    expect(req.request.method).toBe('GET');
    req.flush(detectResponse);

    expect(component.loading).toBeFalse();
    expect(component.result).toEqual(detectResponse as any);
    expect(component.communityIds).toEqual([0, 1]);
  });

  // ──────────────────────────────────────────────────────────────────────────
  // summarizeCommunity() — happy path
  // ──────────────────────────────────────────────────────────────────────────

  it('summarizeCommunity() should GET the summary endpoint and store result', () => {
    component.factSheetId = FACT_SHEET_ID;
    component.result = detectResponse as any;
    component.communityIds = [0, 1];

    component.summarizeCommunity(0);

    expect(component.summarizingId).toBe(0);
    // Clicking summarize should select the community
    expect(component.selectedCommunityId).toBe(0);

    const summaryResp = { communityId: 0, summary: 'Cluster of Alpha and Beta.', memberCount: 2 };
    const req = httpMock.expectOne(
      `${component['backendUrl']}/graph/${FACT_SHEET_ID}/communities/0/summary`
    );
    expect(req.request.method).toBe('GET');
    req.flush(summaryResp);

    expect(component.summarizingId).toBeNull();
    expect(component.summaries[0]).toBe('Cluster of Alpha and Beta.');
    expect(component.summaryError[0]).toBeUndefined();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // summarizeCommunity() — error path
  // ──────────────────────────────────────────────────────────────────────────

  it('summarizeCommunity() should store error message on HTTP failure', () => {
    component.factSheetId = FACT_SHEET_ID;
    component.result = detectResponse as any;

    component.summarizeCommunity(1);

    const req = httpMock.expectOne(
      `${component['backendUrl']}/graph/${FACT_SHEET_ID}/communities/1/summary`
    );
    req.flush({ error: 'LLM unavailable' }, { status: 503, statusText: 'Service Unavailable' });

    expect(component.summarizingId).toBeNull();
    expect(component.summaryError[1]).toBe('LLM unavailable');
    expect(component.summaries[1]).toBeUndefined();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // summarizeCommunity() — no-op when already summarizing
  // ──────────────────────────────────────────────────────────────────────────

  it('summarizeCommunity() should no-op when already summarizing the same community', () => {
    component.factSheetId = FACT_SHEET_ID;
    component.summarizingId = 0;

    component.summarizeCommunity(0);

    httpMock.expectNone((r) => r.url.includes('/summary'));
  });

  // ──────────────────────────────────────────────────────────────────────────
  // summarizeCommunity() — no-op without factSheetId
  // ──────────────────────────────────────────────────────────────────────────

  it('summarizeCommunity() should no-op without a factSheetId', () => {
    component.factSheetId = null;

    component.summarizeCommunity(0);

    httpMock.expectNone((r) => r.url.includes('/summary'));
    expect(component.summarizingId).toBeNull();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // selectCommunity() — toggles selection
  // ──────────────────────────────────────────────────────────────────────────

  it('selectCommunity() should toggle selection on and off', () => {
    component.selectCommunity(0);
    expect(component.selectedCommunityId).toBe(0);

    component.selectCommunity(0);
    expect(component.selectedCommunityId).toBeNull();
  });

  // ──────────────────────────────────────────────────────────────────────────
  // Density caveat — minimum-density guard (Task 3)
  // ──────────────────────────────────────────────────────────────────────────

  it('densityCaveat should be set when nodeCount is below COMMUNITY_MIN_NODES (20)', () => {
    component.factSheetId = FACT_SHEET_ID;

    // Sparse response: only 3 nodes
    const sparseResponse = {
      communityCount: 2,
      modularity: 0.30,
      nodeCount: 3,
      nodeToCommunit: { node1: 0, node2: 0, node3: 1 },
      communities: { '0': ['node1', 'node2'], '1': ['node3'] }
    };

    component.detect();
    const req = httpMock.expectOne((r) =>
      r.url.includes(`/graph/${FACT_SHEET_ID}/communities`) && !r.url.includes('/summary')
    );
    req.flush(sparseResponse);

    expect(component.densityCaveat).not.toBeNull();
    expect(component.densityCaveat).toContain('denser graph');
  });

  it('densityCaveat should be set when every node is its own community (degenerate)', () => {
    component.factSheetId = FACT_SHEET_ID;

    // Every node its own community = degenerate
    const degenerateResponse = {
      communityCount: 25,
      modularity: 0.01,
      nodeCount: 25,
      nodeToCommunit: {},
      communities: {}
    };

    component.detect();
    const req = httpMock.expectOne((r) =>
      r.url.includes(`/graph/${FACT_SHEET_ID}/communities`) && !r.url.includes('/summary')
    );
    req.flush(degenerateResponse);

    expect(component.densityCaveat).not.toBeNull();
    expect(component.densityCaveat).toContain('degenerate');
  });

  it('densityCaveat should be set when modularity is below threshold (degenerate structure)', () => {
    component.factSheetId = FACT_SHEET_ID;

    // Enough nodes but near-zero modularity = no real structure
    const lowModularityResponse = {
      communityCount: 3,
      modularity: 0.02,
      nodeCount: 50,
      nodeToCommunit: {},
      communities: { '0': [], '1': [], '2': [] }
    };

    component.detect();
    const req = httpMock.expectOne((r) =>
      r.url.includes(`/graph/${FACT_SHEET_ID}/communities`) && !r.url.includes('/summary')
    );
    req.flush(lowModularityResponse);

    expect(component.densityCaveat).not.toBeNull();
    expect(component.densityCaveat).toContain('degenerate');
  });

  it('densityCaveat should be null for a healthy dense-graph result', () => {
    component.factSheetId = FACT_SHEET_ID;

    // 50 nodes, 5 communities, reasonable modularity
    const healthyResponse = {
      communityCount: 5,
      modularity: 0.45,
      nodeCount: 50,
      nodeToCommunit: {},
      communities: { '0': [], '1': [], '2': [], '3': [], '4': [] }
    };

    component.detect();
    const req = httpMock.expectOne((r) =>
      r.url.includes(`/graph/${FACT_SHEET_ID}/communities`) && !r.url.includes('/summary')
    );
    req.flush(healthyResponse);

    expect(component.densityCaveat).toBeNull();
  });

  it('densityCaveat should be cleared when factSheetId changes', () => {
    component.densityCaveat = 'Some prior warning';

    component.factSheetId = FACT_SHEET_ID;
    component.ngOnChanges({
      factSheetId: new SimpleChange(null, FACT_SHEET_ID, false)
    });

    expect(component.densityCaveat).toBeNull();
  });
});
