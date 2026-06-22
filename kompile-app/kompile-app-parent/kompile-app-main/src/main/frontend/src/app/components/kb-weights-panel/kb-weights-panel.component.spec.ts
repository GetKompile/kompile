/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { KbWeightsPanelComponent } from './kb-weights-panel.component';

describe('KbWeightsPanelComponent', () => {
  let component: KbWeightsPanelComponent;
  let fixture: ComponentFixture<KbWeightsPanelComponent>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        KbWeightsPanelComponent,
        HttpClientTestingModule,
        NoopAnimationsModule,
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(KbWeightsPanelComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── PSL weights ─────────────────────────────────────────────────────────────

  describe('PSL weights', () => {

    it('should create and call the PSL weights endpoint on init', () => {
      component.factSheetId = null;
      fixture.detectChanges();

      const req = httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/'));
      expect(req.request.method).toBe('GET');
      req.flush({
        programId: 'default',
        version: 1,
        weights: { 'A -> B': 0.8 },
        availablePrograms: [],
      });
    });

    it('should populate weightRows from the PSL response', () => {
      component.factSheetId = null;
      fixture.detectChanges();

      const req = httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/'));
      req.flush({
        programId: 'default',
        version: 2,
        weights: { 'rule1': 0.9, 'rule2': 0.5 },
        availablePrograms: [],
      });

      expect(component.weightRows.length).toBe(2);
      // sorted descending by weight
      expect(component.weightRows[0].rule).toBe('rule1');
      expect(component.weightRows[0].weight).toBeCloseTo(0.9);
    });

    it('should show empty state when no weights returned', () => {
      component.factSheetId = null;
      fixture.detectChanges();

      const req = httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/'));
      req.flush({ programId: 'default', version: 0, weights: {}, availablePrograms: [] });

      expect(component.weightRows.length).toBe(0);
      expect(component.weightsError).toBeNull();
    });

    it('weightBarPct should clamp to 0–100', () => {
      expect(component.weightBarPct(0)).toBe(0);
      expect(component.weightBarPct(0.5)).toBeCloseTo(50);
      expect(component.weightBarPct(1.0)).toBeCloseTo(100);
      expect(component.weightBarPct(2.0)).toBe(100);
    });

    it('weightColor should return green for strong weights', () => {
      expect(component.weightColor(0.9)).toBe('#4CAF50');
      expect(component.weightColor(0.5)).toBe('#FFC107');
      expect(component.weightColor(0.2)).toBe('#FF9800');
      expect(component.weightColor(0.05)).toBe('#F44336');
    });
  });

  // ── MEBN weights ─────────────────────────────────────────────────────────────

  describe('MEBN Theory Inspector (D6)', () => {

    it('should call the MEBN endpoint when factSheetId is set on init', () => {
      component.factSheetId = 42;
      fixture.detectChanges();

      // PSL
      httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/')).flush({
        programId: '42', version: 0, weights: {}, availablePrograms: [],
      });
      // Audit history
      httpMock.expectOne(r => r.url.includes('/kb-grounding/42/audit')).flush([]);
      // MEBN
      const mebnReq = httpMock.expectOne(r => r.url.includes('/kb/weights/mebn/42'));
      expect(mebnReq.request.method).toBe('GET');
      mebnReq.flush([]);
    });

    it('should populate mebnRows from the MEBN response', () => {
      component.factSheetId = 7;
      fixture.detectChanges();

      httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/')).flush({
        programId: '7', version: 0, weights: {}, availablePrograms: [],
      });
      httpMock.expectOne(r => r.url.includes('/kb-grounding/7/audit')).flush([]);

      const mebnReq = httpMock.expectOne(r => r.url.includes('/kb/weights/mebn/7'));
      mebnReq.flush([
        { mFragName: 'FragA', conditionDescription: 'cause->isActive', learnedStrength: 0.75 },
        { mFragName: 'FragB', conditionDescription: 'parent->child',   learnedStrength: 0.9  },
      ]);

      expect(component.mebnRows.length).toBe(2);
      expect(component.mebnRows[0].mFragName).toBe('FragA');
      expect(component.mebnRows[0].learnedStrength).toBeCloseTo(0.75);
      expect(component.mebnRows[1].mFragName).toBe('FragB');
      expect(component.mebnError).toBeNull();
    });

    it('should set mebnRows to empty and no error on 503 (adapter absent)', () => {
      component.factSheetId = 10;
      fixture.detectChanges();

      httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/')).flush({
        programId: '10', version: 0, weights: {}, availablePrograms: [],
      });
      httpMock.expectOne(r => r.url.includes('/kb-grounding/10/audit')).flush([]);

      const mebnReq = httpMock.expectOne(r => r.url.includes('/kb/weights/mebn/10'));
      mebnReq.flush('Service unavailable', { status: 503, statusText: 'Service Unavailable' });

      expect(component.mebnRows.length).toBe(0);
      expect(component.mebnError).toBeNull();
    });

    it('should NOT call MEBN endpoint when factSheetId is null', () => {
      component.factSheetId = null;
      fixture.detectChanges();

      httpMock.expectOne(r => r.url.includes('/kb/weights') && !r.url.includes('/mebn/')).flush({
        programId: 'default', version: 0, weights: {}, availablePrograms: [],
      });

      // No MEBN call expected
      httpMock.expectNone(r => r.url.includes('/kb/weights/mebn/'));
    });

    it('loadMebnWeights should be a no-op when factSheetId is null', () => {
      component.factSheetId = null;
      // Should not throw
      expect(() => component.loadMebnWeights()).not.toThrow();
    });
  });
});
