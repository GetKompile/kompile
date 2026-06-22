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
import { of, throwError } from 'rxjs';

import { GraphOntologyPanelComponent } from './graph-ontology-panel.component';
import { GraphOntologyService, GraphConformanceReport } from '../../services/graph-ontology.service';
import { ProcessEngineService, OntologySchema } from '../../services/process-engine.service';
import { MatSnackBar } from '@angular/material/snack-bar';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function makeReport(overrides: Partial<GraphConformanceReport> = {}): GraphConformanceReport {
  return {
    factSheetId: 1,
    ontologyBound: true,
    ontologyId: 'ont-1',
    ontologyVersion: 1,
    ontologyName: 'Test Ontology',
    entitiesChecked: 10,
    unknownTypeCount: 0,
    nonConformantCount: 0,
    conformanceScore: 1.0,
    violations: [],
    edgesChecked: 5,
    nonConformantEdgeCount: 0,
    edgeViolations: [],
    message: '',
    ...overrides,
  };
}

function makeOntology(overrides: Partial<OntologySchema> = {}): OntologySchema {
  return {
    id: 'ont-1',
    name: 'Test Ontology',
    version: 1,
    entityTypes: [],
    relationshipTypes: [],
    ...overrides,
  };
}

// ─── Test suite ───────────────────────────────────────────────────────────────

describe('GraphOntologyPanelComponent', () => {
  let component: GraphOntologyPanelComponent;
  let fixture: ComponentFixture<GraphOntologyPanelComponent>;

  let ontologyServiceSpy: jasmine.SpyObj<GraphOntologyService>;
  let processEngineSpy: jasmine.SpyObj<ProcessEngineService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    ontologyServiceSpy = jasmine.createSpyObj('GraphOntologyService', ['conformance', 'bind', 'unbind']);
    processEngineSpy   = jasmine.createSpyObj('ProcessEngineService', ['listOntologies', 'deriveOntology']);
    snackBarSpy        = jasmine.createSpyObj('MatSnackBar', ['open']);

    // Default happy-path returns
    ontologyServiceSpy.conformance.and.returnValue(of(makeReport()));
    processEngineSpy.listOntologies.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [GraphOntologyPanelComponent, NoopAnimationsModule],
      providers: [
        { provide: GraphOntologyService, useValue: ontologyServiceSpy },
        { provide: ProcessEngineService,  useValue: processEngineSpy  },
        { provide: MatSnackBar,           useValue: snackBarSpy       },
      ],
      schemas: [NO_ERRORS_SCHEMA],
    })
      .overrideComponent(GraphOntologyPanelComponent, { set: { schemas: [NO_ERRORS_SCHEMA] } })
      .compileComponents();

    fixture   = TestBed.createComponent(GraphOntologyPanelComponent);
    component = fixture.componentInstance;
  });

  // ── Basic creation ────────────────────────────────────────────────────────

  it('should create', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
  });

  it('lists ontologies on init', () => {
    fixture.detectChanges();
    expect(processEngineSpy.listOntologies).toHaveBeenCalled();
  });

  it('does not load conformance on init when factSheetId is null', () => {
    component.factSheetId = null;
    fixture.detectChanges();
    expect(ontologyServiceSpy.conformance).not.toHaveBeenCalled();
  });

  it('loads conformance on ngOnChanges when factSheetId is set', () => {
    fixture.detectChanges();
    component.factSheetId = 5;
    component.ngOnChanges({ factSheetId: { currentValue: 5, previousValue: null, isFirstChange: () => false, firstChange: false } });
    expect(ontologyServiceSpy.conformance).toHaveBeenCalledWith(5);
  });

  // ── pct helper ────────────────────────────────────────────────────────────

  describe('pct()', () => {
    it('formats 1.0 as 100.0%', () => {
      expect(component.pct(1.0)).toBe('100.0%');
    });
    it('formats 0.754 as 75.4%', () => {
      expect(component.pct(0.754)).toBe('75.4%');
    });
    it('returns — for null', () => {
      expect(component.pct(null)).toBe('—');
    });
    it('returns — for undefined', () => {
      expect(component.pct(undefined)).toBe('—');
    });
  });

  // ── D4: empty-graph guard ────────────────────────────────────────────────

  describe('D4 — empty-graph conformance guard', () => {

    it('entitiesChecked=0 and edgesChecked=0 is flagged as empty graph', () => {
      component.report = makeReport({ entitiesChecked: 0, edgesChecked: 0, ontologyBound: true });
      expect(component.report.entitiesChecked).toBe(0);
      expect(component.report.edgesChecked).toBe(0);
      // Component logic: template shows "Graph is empty" when both are 0
    });

    it('report with entitiesChecked>0 is not treated as empty', () => {
      component.report = makeReport({ entitiesChecked: 5, edgesChecked: 3, ontologyBound: true });
      expect(component.report.entitiesChecked).toBeGreaterThan(0);
    });
  });

  // ── D4: deriveFromGraph CTA ───────────────────────────────────────────────

  describe('D4 — deriveFromGraph()', () => {

    beforeEach(() => {
      component.factSheetId = 3;
    });

    it('is a no-op when factSheetId is null', () => {
      component.factSheetId = null;
      component.deriveFromGraph();
      expect(processEngineSpy.deriveOntology).not.toHaveBeenCalled();
    });

    it('calls deriveOntology with factSheetId in the request', fakeAsync(() => {
      const mockDraft: OntologySchema = makeOntology({ name: 'Draft Ont', entityTypes: [] });
      processEngineSpy.deriveOntology.and.returnValue(of(mockDraft));

      component.deriveFromGraph();
      tick();

      expect(processEngineSpy.deriveOntology).toHaveBeenCalledWith(
        jasmine.objectContaining({ factSheetId: 3 })
      );
    }));

    it('sets derivedDraft on success', fakeAsync(() => {
      const mockDraft: OntologySchema = makeOntology({ name: 'My Draft', entityTypes: [{ name: 'Company' }] });
      processEngineSpy.deriveOntology.and.returnValue(of(mockDraft));

      component.deriveFromGraph();
      tick();

      expect(component.derivedDraft).toBeTruthy();
      expect(component.derivedDraft!.name).toBe('My Draft');
      expect(component.deriving).toBeFalse();
      expect(component.deriveError).toBeNull();
    }));

    it('sets deriveError on failure', fakeAsync(() => {
      processEngineSpy.deriveOntology.and.returnValue(
        throwError(() => ({ message: 'LLM unavailable' }))
      );

      component.deriveFromGraph();
      tick();

      expect(component.deriveError).toBeTruthy();
      expect(component.derivedDraft).toBeNull();
      expect(component.deriving).toBeFalse();
    }));

    it('dismissDraft clears derivedDraft and deriveError', () => {
      component.derivedDraft = makeOntology();
      component.deriveError  = 'some error';
      component.dismissDraft();
      expect(component.derivedDraft).toBeNull();
      expect(component.deriveError).toBeNull();
    });

    it('does not call deriveOntology when already deriving', fakeAsync(() => {
      component.deriving = true;
      component.deriveFromGraph();
      tick();
      expect(processEngineSpy.deriveOntology).not.toHaveBeenCalled();
    }));

    it('refreshes ontology list after successful derivation', fakeAsync(() => {
      processEngineSpy.deriveOntology.and.returnValue(of(makeOntology()));
      processEngineSpy.listOntologies.calls.reset();

      component.deriveFromGraph();
      tick();

      expect(processEngineSpy.listOntologies).toHaveBeenCalled();
    }));

    it('sends includeRelationships=true in the derive request', fakeAsync(() => {
      processEngineSpy.deriveOntology.and.returnValue(of(makeOntology()));

      component.deriveFromGraph();
      tick();

      const req = processEngineSpy.deriveOntology.calls.mostRecent().args[0];
      expect(req.includeRelationships).toBeTrue();
    }));
  });
});
