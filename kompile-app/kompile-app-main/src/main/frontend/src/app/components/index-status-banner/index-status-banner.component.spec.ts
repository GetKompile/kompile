/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { NO_ERRORS_SCHEMA } from '@angular/core';

import { IndexStatusBannerComponent, IndexStatus } from './index-status-banner.component';
import { environment } from '../../../environments/environment';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeIndexStatus(overrides: Partial<IndexStatus> = {}): IndexStatus {
  return {
    vectorStorePath: '/data/vector',
    vectorStoreAvailable: true,
    vectorStoreNoOp: false,
    vectorDocumentCount: 50,
    vectorIndexLoaded: true,
    vectorIndexEmpty: false,
    keywordIndexPath: '/data/keyword',
    keywordIndexAvailable: true,
    indexerServiceNoOp: false,
    keywordDocumentCount: 50,
    keywordIndexLoaded: true,
    keywordIndexEmpty: false,
    availableVectorIndices: ['index-1'],
    availableKarchFiles: [],
    anyIndexLoaded: true,
    warningMessage: null,
    ...overrides
  };
}

function makeEmptyIndexStatus(): IndexStatus {
  return makeIndexStatus({
    vectorIndexLoaded: false,
    keywordIndexLoaded: false,
    anyIndexLoaded: false,
    vectorDocumentCount: 0,
    keywordDocumentCount: 0
  });
}

describe('IndexStatusBannerComponent', () => {
  let component: IndexStatusBannerComponent;
  let fixture: ComponentFixture<IndexStatusBannerComponent>;
  let httpMock: HttpTestingController;
  const statusUrl = `${environment.apiUrl}/services/index-status`;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()]
    })
    .overrideComponent(IndexStatusBannerComponent, {
      set: {
        imports: [CommonModule],
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);

    fixture = TestBed.createComponent(IndexStatusBannerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    // Flush any pending HTTP requests to avoid afterEach errors
    httpMock.match(r => true).forEach(r => r.flush(makeIndexStatus()));
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should start with showBanner=false', () => {
    expect(component.showBanner).toBeFalse();
  });

  it('should start with status=null', () => {
    expect(component.status).toBeNull();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // checkIndexStatus()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('checkIndexStatus()', () => {
    it('should hide banner when anyIndexLoaded=true and no warning', () => {
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush(makeIndexStatus({ anyIndexLoaded: true, warningMessage: null }));

      expect(component.showBanner).toBeFalse();
      expect(component.status).not.toBeNull();
    });

    it('should show banner when anyIndexLoaded=false', () => {
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush(makeEmptyIndexStatus());

      expect(component.showBanner).toBeTrue();
    });

    it('should show banner when there is a warning message', () => {
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush(makeIndexStatus({ anyIndexLoaded: true, warningMessage: 'Index needs rebuild' }));

      expect(component.showBanner).toBeTrue();
    });

    it('should populate status from response', () => {
      const status = makeIndexStatus({ vectorDocumentCount: 100 });
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush(status);

      expect(component.status?.vectorDocumentCount).toBe(100);
    });

    it('should show banner and set error status on HTTP error', () => {
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush('Server Error', { status: 500, statusText: 'Internal Server Error' });

      expect(component.showBanner).toBeTrue();
      expect(component.status?.anyIndexLoaded).toBeFalse();
      expect(component.status?.warningMessage).toContain('Unable to check index status');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // ngOnInit — initial status check + interval setup
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnInit()', () => {
    it('should call checkIndexStatus on init', () => {
      spyOn(component, 'checkIndexStatus');
      component.ngOnInit();
      expect(component.checkIndexStatus).toHaveBeenCalled();
      // Cancel the interval
      component.ngOnDestroy();
    });

    it('should set up 30-second refresh interval', fakeAsync(() => {
      spyOn(component, 'checkIndexStatus').and.callThrough();
      component.ngOnInit();

      // Flush the initial status check
      const req1 = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req1.flush(makeEmptyIndexStatus()); // showBanner=true triggers interval calls

      const initialCallCount = (component.checkIndexStatus as jasmine.Spy).calls.count();

      tick(30000);

      // Only checks when showBanner is true (see component implementation)
      if (component.showBanner) {
        const req2 = httpMock.expectOne(r => r.url.includes('/services/index-status'));
        req2.flush(makeEmptyIndexStatus());
        expect((component.checkIndexStatus as jasmine.Spy).calls.count()).toBeGreaterThan(initialCallCount);
      }

      component.ngOnDestroy();
      discardPeriodicTasks();
    }));

    it('should NOT refresh when banner is hidden (showBanner=false)', fakeAsync(() => {
      spyOn(component, 'checkIndexStatus').and.callThrough();
      component.ngOnInit();

      // Flush with anyIndexLoaded=true so showBanner=false
      const req1 = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req1.flush(makeIndexStatus({ anyIndexLoaded: true, warningMessage: null }));

      expect(component.showBanner).toBeFalse();
      const callCount = (component.checkIndexStatus as jasmine.Spy).calls.count();

      tick(30000); // Interval fires but showBanner=false, so checkIndexStatus is NOT called at all

      // No new HTTP requests should have been made (interval guard: if (showBanner) {...})
      httpMock.expectNone(r => r.url.includes('/services/index-status'));
      // The count should be unchanged — interval does not invoke checkIndexStatus when showBanner=false
      expect((component.checkIndexStatus as jasmine.Spy).calls.count()).toBe(callCount);

      component.ngOnDestroy();
      discardPeriodicTasks();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // ngOnDestroy()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should unsubscribe refresh subscription', fakeAsync(() => {
      component.ngOnInit();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush(makeIndexStatus());

      component.ngOnDestroy();
      // No error should occur after destroy
      expect(() => tick(30000)).not.toThrow();

      discardPeriodicTasks();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // hasWarning getter
  // ─────────────────────────────────────────────────────────────────────────────

  describe('hasWarning getter', () => {
    it('should return false when status is null', () => {
      component.status = null;
      expect(component.hasWarning).toBeFalse();
    });

    it('should return false when warningMessage is null', () => {
      component.status = makeIndexStatus({ warningMessage: null });
      expect(component.hasWarning).toBeFalse();
    });

    it('should return true when warningMessage is set', () => {
      component.status = makeIndexStatus({ warningMessage: 'Some warning' });
      expect(component.hasWarning).toBeTrue();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getAvailableVectorIndicesCount()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAvailableVectorIndicesCount()', () => {
    it('should return 0 when status is null', () => {
      component.status = null;
      expect(component.getAvailableVectorIndicesCount()).toBe(0);
    });

    it('should return 0 when availableVectorIndices is empty', () => {
      component.status = makeIndexStatus({ availableVectorIndices: [] });
      expect(component.getAvailableVectorIndicesCount()).toBe(0);
    });

    it('should return correct count of available indices', () => {
      component.status = makeIndexStatus({ availableVectorIndices: ['idx-1', 'idx-2', 'idx-3'] });
      expect(component.getAvailableVectorIndicesCount()).toBe(3);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getAvailableKarchFilesCount()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getAvailableKarchFilesCount()', () => {
    it('should return 0 when status is null', () => {
      component.status = null;
      expect(component.getAvailableKarchFilesCount()).toBe(0);
    });

    it('should return count of karch files', () => {
      component.status = makeIndexStatus({ availableKarchFiles: ['archive.karch'] });
      expect(component.getAvailableKarchFilesCount()).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // dismissBanner()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('dismissBanner()', () => {
    it('should set showBanner=false', () => {
      component.showBanner = true;
      component.dismissBanner();
      expect(component.showBanner).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // goToDocumentManager()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('goToDocumentManager()', () => {
    it('should emit navigateToTab with "sources" and dismiss banner', () => {
      component.showBanner = true;
      let emittedTab: string | undefined;
      component.navigateToTab.subscribe((tab: string) => emittedTab = tab);

      component.goToDocumentManager();

      expect(emittedTab).toBe('sources');
      expect(component.showBanner).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // goToArchiveManager()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('goToArchiveManager()', () => {
    it('should emit navigateToTab with "archiveAssembly" and dismiss banner', () => {
      component.showBanner = true;
      let emittedTab: string | undefined;
      component.navigateToTab.subscribe((tab: string) => emittedTab = tab);

      component.goToArchiveManager();

      expect(emittedTab).toBe('archiveAssembly');
      expect(component.showBanner).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // Error status fallback shape
  // ─────────────────────────────────────────────────────────────────────────────

  describe('error fallback status', () => {
    it('should have vectorStoreNoOp=true in error fallback', () => {
      component.checkIndexStatus();
      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush('Error', { status: 503, statusText: 'Service Unavailable' });

      expect(component.status?.vectorStoreNoOp).toBeTrue();
      expect(component.status?.indexerServiceNoOp).toBeTrue();
    });

    it('should have empty availableVectorIndices in error fallback', () => {
      component.checkIndexStatus();
      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      req.flush('Error', { status: 503, statusText: 'Service Unavailable' });

      expect(component.status?.availableVectorIndices).toEqual([]);
      expect(component.status?.availableKarchFiles).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // HTTP request verification
  // ─────────────────────────────────────────────────────────────────────────────

  describe('HTTP request to status endpoint', () => {
    it('should make GET request to /services/index-status', () => {
      component.checkIndexStatus();

      const req = httpMock.expectOne(r => r.url.includes('/services/index-status'));
      expect(req.request.method).toBe('GET');
      req.flush(makeIndexStatus());
    });
  });
});
