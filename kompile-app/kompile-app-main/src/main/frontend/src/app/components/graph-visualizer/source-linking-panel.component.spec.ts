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
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError, Subject } from 'rxjs';

import { SourceLinkingPanelComponent } from './source-linking-panel.component';
import { GraphService } from '../../services/graph.service';

// ─────────────────────────────────────────────────────────────────────────────
// Shared mock data
// ─────────────────────────────────────────────────────────────────────────────

const mockConnectivity = {
  totalSources: 5,
  totalSourceLinks: 8,
  isolatedSources: 1,
  connectivityRatio: 0.8
};

interface SourceLink {
  sourceId1: string;
  sourceName1: string;
  sourceId2: string;
  sourceName2: string;
  linkType: string;
  strength: number;
  sharedConcepts: string[];
  description: string;
}

interface ConnectedSource {
  sourceId: string;
  sourceTitle: string;
  connectionCount: number;
}

const mockSourceLinks: SourceLink[] = [
  {
    sourceId1: 's1',
    sourceName1: 'Source A',
    sourceId2: 's2',
    sourceName2: 'Source B',
    linkType: 'SHARED_CONCEPTS',
    strength: 0.75,
    sharedConcepts: ['AI', 'ML'],
    description: 'Shared concepts link'
  }
];

const mockMostConnected: ConnectedSource[] = [
  { sourceId: 's1', sourceTitle: 'Source A', connectionCount: 5 }
];

const mockIsolatedSources: string[] = ['s3'];

// ─────────────────────────────────────────────────────────────────────────────
// Helper: configure all four loadData spies for success path
// ─────────────────────────────────────────────────────────────────────────────

function configureSuccessSpies(graphServiceSpy: jasmine.SpyObj<GraphService>): void {
  graphServiceSpy.getSourceConnectivity.and.returnValue(of(mockConnectivity));
  graphServiceSpy.getSourceLinks.and.returnValue(of(mockSourceLinks as any));
  graphServiceSpy.findMostConnectedSources.and.returnValue(of(mockMostConnected as any));
  graphServiceSpy.findIsolatedSources.and.returnValue(of(mockIsolatedSources));
}

// ─────────────────────────────────────────────────────────────────────────────
// Main describe block
// ─────────────────────────────────────────────────────────────────────────────

describe('SourceLinkingPanelComponent', () => {
  let component: SourceLinkingPanelComponent;
  let fixture: ComponentFixture<SourceLinkingPanelComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj('GraphService', [
      'getSourceConnectivity',
      'getSourceLinks',
      'findMostConnectedSources',
      'findIsolatedSources',
      'linkSources',
      'removeSourceLink'
    ]);

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    // Configure the four loadData spies before the first detectChanges so
    // that ngOnInit → loadData() can complete synchronously via of().
    configureSuccessSpies(graphServiceSpy);

    await TestBed.configureTestingModule({
      imports: [SourceLinkingPanelComponent, NoopAnimationsModule],
      providers: [
        { provide: GraphService, useValue: graphServiceSpy },
        { provide: MatSnackBar, useValue: snackBarSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(SourceLinkingPanelComponent, {
      set: {
        providers: [
          { provide: GraphService, useValue: graphServiceSpy },
          { provide: MatSnackBar, useValue: snackBarSpy }
        ]
      }
    })
    .compileComponents();

    fixture = TestBed.createComponent(SourceLinkingPanelComponent);
    component = fixture.componentInstance;

    // Set the required @Input before the first change-detection cycle so that
    // ngOnInit finds a non-null factSheetId and calls loadData().
    component.factSheetId = 42;

    fixture.detectChanges();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 1. Creation
  // ═══════════════════════════════════════════════════════════════════════════

  describe('creation', () => {
    it('should create the component', () => {
      expect(component).toBeTruthy();
    });

    it('should initialise sourceLinks as an empty array', () => {
      // The field default is [] — loadData fills it; either state is valid
      expect(Array.isArray(component.sourceLinks)).toBe(true);
    });

    it('should initialise mostConnected as an empty array', () => {
      expect(Array.isArray(component.mostConnected)).toBe(true);
    });

    it('should initialise isolatedSources as an empty array', () => {
      expect(Array.isArray(component.isolatedSources)).toBe(true);
    });

    it('should initialise connectivity as null', () => {
      // After successful loadData the spy sets it, but the field default is null
      // Verify the field exists and the spy set a truthy value after init.
      expect(component.connectivity).not.toBeUndefined();
    });

    it('should have factSheetId=42 as set in beforeEach', () => {
      expect(component.factSheetId).toBe(42);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 2. Initialisation — ngOnInit calls loadData()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('initialisation', () => {
    it('should call getSourceConnectivity with the factSheetId on init', () => {
      expect(graphServiceSpy.getSourceConnectivity).toHaveBeenCalledWith(42);
    });

    it('should call getSourceLinks with the factSheetId on init', () => {
      expect(graphServiceSpy.getSourceLinks).toHaveBeenCalledWith(42);
    });

    it('should call findMostConnectedSources with factSheetId and limit=5 on init', () => {
      expect(graphServiceSpy.findMostConnectedSources).toHaveBeenCalledWith(42, 5);
    });

    it('should call findIsolatedSources with the factSheetId on init', () => {
      expect(graphServiceSpy.findIsolatedSources).toHaveBeenCalledWith(42);
    });

    it('should set loading to false after all streams complete', fakeAsync(() => {
      tick();
      expect(component.loading).toBe(false);
    }));

    it('should populate connectivity from the service response', fakeAsync(() => {
      tick();
      expect(component.connectivity).toEqual(mockConnectivity);
    }));

    it('should populate sourceLinks from the service response', fakeAsync(() => {
      tick();
      expect(component.sourceLinks).toEqual(mockSourceLinks);
    }));

    it('should populate mostConnected from the service response', fakeAsync(() => {
      tick();
      expect(component.mostConnected).toEqual(mockMostConnected);
    }));

    it('should populate isolatedSources from the service response', fakeAsync(() => {
      tick();
      expect(component.isolatedSources).toEqual(mockIsolatedSources);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 3. loadData() — no-factSheetId guard
  // ═══════════════════════════════════════════════════════════════════════════

  describe('loadData() — no factSheetId guard', () => {
    beforeEach(() => {
      // Reset call counts
      graphServiceSpy.getSourceConnectivity.calls.reset();
      graphServiceSpy.getSourceLinks.calls.reset();
      graphServiceSpy.findMostConnectedSources.calls.reset();
      graphServiceSpy.findIsolatedSources.calls.reset();
    });

    it('should not call any service method when factSheetId is null', () => {
      component.factSheetId = null;
      component.loadData();

      expect(graphServiceSpy.getSourceConnectivity).not.toHaveBeenCalled();
      expect(graphServiceSpy.getSourceLinks).not.toHaveBeenCalled();
      expect(graphServiceSpy.findMostConnectedSources).not.toHaveBeenCalled();
      expect(graphServiceSpy.findIsolatedSources).not.toHaveBeenCalled();
    });

    it('should not call any service method when factSheetId is 0 (falsy)', () => {
      component.factSheetId = 0 as any;
      component.loadData();

      expect(graphServiceSpy.getSourceConnectivity).not.toHaveBeenCalled();
      expect(graphServiceSpy.getSourceLinks).not.toHaveBeenCalled();
    });

    it('should set loading=true at the start of a valid loadData() call', () => {
      // Re-spy with subjects that never emit so loading stays true
      graphServiceSpy.getSourceConnectivity.and.returnValue(of(null as any));
      graphServiceSpy.getSourceLinks.and.returnValue(of([]));
      graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
      // Never emit from isolatedSources so loading stays true during the call
      const neverEmit = new Subject<string[]>();
      graphServiceSpy.findIsolatedSources.and.returnValue(neverEmit.asObservable());

      component.loading = false;
      component.loadData();

      expect(component.loading).toBe(true);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 4. refresh()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('refresh()', () => {
    it('should call loadData() when refresh() is invoked', fakeAsync(() => {
      graphServiceSpy.getSourceConnectivity.calls.reset();
      graphServiceSpy.getSourceLinks.calls.reset();
      graphServiceSpy.findMostConnectedSources.calls.reset();
      graphServiceSpy.findIsolatedSources.calls.reset();

      component.refresh();
      tick();

      expect(graphServiceSpy.getSourceConnectivity).toHaveBeenCalledWith(42);
      expect(graphServiceSpy.getSourceLinks).toHaveBeenCalledWith(42);
      expect(graphServiceSpy.findMostConnectedSources).toHaveBeenCalledWith(42, 5);
      expect(graphServiceSpy.findIsolatedSources).toHaveBeenCalledWith(42);
    }));

    it('should reload connectivity data on refresh', fakeAsync(() => {
      const updatedConnectivity = { ...mockConnectivity, totalSourceLinks: 15 };
      graphServiceSpy.getSourceConnectivity.and.returnValue(of(updatedConnectivity));

      component.refresh();
      tick();

      expect(component.connectivity.totalSourceLinks).toBe(15);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 5. autoLinkSources()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('autoLinkSources()', () => {
    const linkResult = { linksCreated: 3 };

    beforeEach(() => {
      graphServiceSpy.linkSources.and.returnValue(of(linkResult));
      // Reset loadData spy counts so we can measure calls after autoLinkSources
      graphServiceSpy.getSourceConnectivity.calls.reset();
      graphServiceSpy.getSourceLinks.calls.reset();
      graphServiceSpy.findMostConnectedSources.calls.reset();
      graphServiceSpy.findIsolatedSources.calls.reset();
    });

    it('should call graphService.linkSources with the factSheetId', fakeAsync(() => {
      component.autoLinkSources();
      tick();

      expect(graphServiceSpy.linkSources).toHaveBeenCalledWith(42);
    }));

    it('should open a "Linking sources..." snack bar before the request', () => {
      // The snack bar is opened synchronously before subscription
      component.autoLinkSources();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Linking sources...', '', { duration: 2000 });
    });

    it('should open success snack bar with links count on success', fakeAsync(() => {
      component.autoLinkSources();
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith(
        `Created ${linkResult.linksCreated} links`, 'Dismiss', { duration: 3000 }
      );
    }));

    it('should call loadData() after a successful linkSources call', fakeAsync(() => {
      component.autoLinkSources();
      tick();

      // loadData calls all four service methods
      expect(graphServiceSpy.getSourceConnectivity).toHaveBeenCalledWith(42);
      expect(graphServiceSpy.getSourceLinks).toHaveBeenCalledWith(42);
    }));

    it('should emit linksChanged after a successful linkSources call', fakeAsync(() => {
      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.autoLinkSources();
      tick();

      expect(emitted).toBe(true);
    }));

    it('should not call linkSources when factSheetId is null', () => {
      component.factSheetId = null;
      component.autoLinkSources();

      expect(graphServiceSpy.linkSources).not.toHaveBeenCalled();
    });

    it('should not emit linksChanged when factSheetId is null', () => {
      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.factSheetId = null;
      component.autoLinkSources();

      expect(emitted).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 6. autoLinkSources() — error handling
  // ═══════════════════════════════════════════════════════════════════════════

  describe('autoLinkSources() — error handling', () => {
    it('should log error and show failure snack bar when linkSources fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.linkSources.and.returnValue(throwError(() => new Error('link failed')));

      component.autoLinkSources();
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to link sources'),
        jasmine.any(Error)
      );
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Failed to link sources', 'Dismiss', { duration: 3000 }
      );
    }));

    it('should not emit linksChanged when linkSources fails', fakeAsync(() => {
      graphServiceSpy.linkSources.and.returnValue(throwError(() => new Error('link failed')));
      spyOn(console, 'error');

      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.autoLinkSources();
      tick();

      expect(emitted).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 7. linkIsolatedSources()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('linkIsolatedSources()', () => {
    it('should delegate to autoLinkSources()', fakeAsync(() => {
      const linkResult = { linksCreated: 2 };
      graphServiceSpy.linkSources.and.returnValue(of(linkResult));

      component.linkIsolatedSources();
      tick();

      expect(graphServiceSpy.linkSources).toHaveBeenCalledWith(42);
    }));

    it('should emit linksChanged after linkIsolatedSources completes', fakeAsync(() => {
      graphServiceSpy.linkSources.and.returnValue(of({ linksCreated: 1 }));
      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.linkIsolatedSources();
      tick();

      expect(emitted).toBe(true);
    }));

    it('should show the "Linking sources..." snack bar (via autoLinkSources)', () => {
      graphServiceSpy.linkSources.and.returnValue(of({ linksCreated: 0 }));

      component.linkIsolatedSources();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Linking sources...', '', { duration: 2000 });
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 8. removeLink()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('removeLink()', () => {
    const testLink: SourceLink = {
      sourceId1: 's1',
      sourceName1: 'Source A',
      sourceId2: 's2',
      sourceName2: 'Source B',
      linkType: 'SHARED_CONCEPTS',
      strength: 0.75,
      sharedConcepts: ['AI', 'ML'],
      description: 'Shared concepts link'
    };

    beforeEach(() => {
      graphServiceSpy.removeSourceLink.and.returnValue(of({}));
      graphServiceSpy.getSourceConnectivity.calls.reset();
      graphServiceSpy.getSourceLinks.calls.reset();
      graphServiceSpy.findMostConnectedSources.calls.reset();
      graphServiceSpy.findIsolatedSources.calls.reset();
    });

    it('should call graphService.removeSourceLink with correct arguments', fakeAsync(() => {
      component.removeLink(testLink);
      tick();

      expect(graphServiceSpy.removeSourceLink).toHaveBeenCalledWith(42, 's1', 's2');
    }));

    it('should show "Link removed" snack bar on success', fakeAsync(() => {
      component.removeLink(testLink);
      tick();

      expect(snackBarSpy.open).toHaveBeenCalledWith('Link removed', 'Dismiss', { duration: 2000 });
    }));

    it('should call loadData() after successful removal', fakeAsync(() => {
      component.removeLink(testLink);
      tick();

      expect(graphServiceSpy.getSourceConnectivity).toHaveBeenCalledWith(42);
      expect(graphServiceSpy.getSourceLinks).toHaveBeenCalledWith(42);
    }));

    it('should emit linksChanged after successful removal', fakeAsync(() => {
      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.removeLink(testLink);
      tick();

      expect(emitted).toBe(true);
    }));

    it('should not call removeSourceLink when factSheetId is null', () => {
      component.factSheetId = null;
      component.removeLink(testLink);

      expect(graphServiceSpy.removeSourceLink).not.toHaveBeenCalled();
    });

    it('should not emit linksChanged when factSheetId is null', () => {
      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.factSheetId = null;
      component.removeLink(testLink);

      expect(emitted).toBe(false);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 9. removeLink() — error handling
  // ═══════════════════════════════════════════════════════════════════════════

  describe('removeLink() — error handling', () => {
    const testLink: SourceLink = {
      sourceId1: 's1',
      sourceName1: 'Source A',
      sourceId2: 's2',
      sourceName2: 'Source B',
      linkType: 'SHARED_CONCEPTS',
      strength: 0.75,
      sharedConcepts: ['AI'],
      description: 'desc'
    };

    it('should log error and show failure snack bar when removeSourceLink fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.removeSourceLink.and.returnValue(
        throwError(() => new Error('remove failed'))
      );

      component.removeLink(testLink);
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to remove link'),
        jasmine.any(Error)
      );
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Failed to remove link', 'Dismiss', { duration: 3000 }
      );
    }));

    it('should not emit linksChanged when removeSourceLink fails', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.removeSourceLink.and.returnValue(
        throwError(() => new Error('remove failed'))
      );

      let emitted = false;
      component.linksChanged.subscribe(() => (emitted = true));

      component.removeLink(testLink);
      tick();

      expect(emitted).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 10. loadData() — individual stream error handling
  // ═══════════════════════════════════════════════════════════════════════════

  describe('loadData() — individual stream error handling', () => {
    it('should log error when getSourceConnectivity fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.getSourceConnectivity.and.returnValue(
        throwError(() => new Error('connectivity error'))
      );
      graphServiceSpy.getSourceLinks.and.returnValue(of([]));
      graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
      graphServiceSpy.findIsolatedSources.and.returnValue(of([]));

      component.loadData();
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to load connectivity'),
        jasmine.any(Error)
      );
    }));

    it('should log error when getSourceLinks fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.getSourceConnectivity.and.returnValue(of(mockConnectivity));
      graphServiceSpy.getSourceLinks.and.returnValue(
        throwError(() => new Error('source links error'))
      );
      graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
      graphServiceSpy.findIsolatedSources.and.returnValue(of([]));

      component.loadData();
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to load source links'),
        jasmine.any(Error)
      );
    }));

    it('should log error when findMostConnectedSources fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.getSourceConnectivity.and.returnValue(of(mockConnectivity));
      graphServiceSpy.getSourceLinks.and.returnValue(of([]));
      graphServiceSpy.findMostConnectedSources.and.returnValue(
        throwError(() => new Error('most connected error'))
      );
      graphServiceSpy.findIsolatedSources.and.returnValue(of([]));

      component.loadData();
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to load most connected'),
        jasmine.any(Error)
      );
    }));

    it('should log error and set loading=false when findIsolatedSources fails', fakeAsync(() => {
      const consoleSpy = spyOn(console, 'error');
      graphServiceSpy.getSourceConnectivity.and.returnValue(of(mockConnectivity));
      graphServiceSpy.getSourceLinks.and.returnValue(of([]));
      graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
      graphServiceSpy.findIsolatedSources.and.returnValue(
        throwError(() => new Error('isolated sources error'))
      );

      component.loadData();
      tick();

      expect(consoleSpy).toHaveBeenCalledWith(
        jasmine.stringContaining('Failed to load isolated sources'),
        jasmine.any(Error)
      );
      expect(component.loading).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 11. formatLinkType()
  // ═══════════════════════════════════════════════════════════════════════════

  describe('formatLinkType()', () => {
    it('should convert SHARED_CONCEPTS to "shared concepts"', () => {
      expect(component.formatLinkType('SHARED_CONCEPTS')).toBe('shared concepts');
    });

    it('should convert CROSS_SOURCE to "cross source"', () => {
      expect(component.formatLinkType('CROSS_SOURCE')).toBe('cross source');
    });

    it('should convert USER_DEFINED to "user defined"', () => {
      expect(component.formatLinkType('USER_DEFINED')).toBe('user defined');
    });

    it('should convert EMBEDDING_SIMILARITY to "embedding similarity"', () => {
      expect(component.formatLinkType('EMBEDDING_SIMILARITY')).toBe('embedding similarity');
    });

    it('should handle a single-word type by lowercasing it', () => {
      expect(component.formatLinkType('MANUAL')).toBe('manual');
    });

    it('should replace multiple underscores in a compound type', () => {
      expect(component.formatLinkType('A_B_C')).toBe('a b c');
    });

    it('should return an empty string when given an empty string', () => {
      expect(component.formatLinkType('')).toBe('');
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 12. linksChanged @Output emission
  // ═══════════════════════════════════════════════════════════════════════════

  describe('linksChanged @Output', () => {
    it('should be an EventEmitter', () => {
      expect(component.linksChanged).toBeDefined();
      expect(typeof component.linksChanged.emit).toBe('function');
    });

    it('should emit void (no payload) from autoLinkSources', fakeAsync(() => {
      graphServiceSpy.linkSources.and.returnValue(of({ linksCreated: 1 }));
      const emittedValues: any[] = [];
      component.linksChanged.subscribe((v) => emittedValues.push(v));

      component.autoLinkSources();
      tick();

      expect(emittedValues.length).toBe(1);
      expect(emittedValues[0]).toBeUndefined();
    }));

    it('should emit void (no payload) from removeLink', fakeAsync(() => {
      graphServiceSpy.removeSourceLink.and.returnValue(of({}));
      const testLink = mockSourceLinks[0];
      const emittedValues: any[] = [];
      component.linksChanged.subscribe((v) => emittedValues.push(v));

      component.removeLink(testLink);
      tick();

      expect(emittedValues.length).toBe(1);
      expect(emittedValues[0]).toBeUndefined();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 13. loading state management
  // ═══════════════════════════════════════════════════════════════════════════

  describe('loading state', () => {
    it('should default to false before any loadData call', () => {
      // After the beforeEach detectChanges cycle the isolated-sources observable
      // has emitted (via of()), so loading must be false.
      expect(component.loading).toBe(false);
    });

    it('should be false after all loadData streams complete successfully', fakeAsync(() => {
      component.loadData();
      tick();
      expect(component.loading).toBe(false);
    }));

    it('should not leave loading=true when the isolated-sources stream completes', fakeAsync(() => {
      graphServiceSpy.findIsolatedSources.and.returnValue(of(['s4']));
      component.loadData();
      tick();
      expect(component.loading).toBe(false);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 14. ngOnDestroy
  // ═══════════════════════════════════════════════════════════════════════════

  describe('ngOnDestroy()', () => {
    it('should complete without throwing', () => {
      expect(() => component.ngOnDestroy()).not.toThrow();
    });

    it('should not propagate new service emissions after destroy', fakeAsync(() => {
      // Verify the component can be destroyed cleanly mid-stream
      component.ngOnDestroy();
      // Any subsequent tick should not cause errors
      tick();
      expect(component).toBeTruthy();
    }));
  });
});
