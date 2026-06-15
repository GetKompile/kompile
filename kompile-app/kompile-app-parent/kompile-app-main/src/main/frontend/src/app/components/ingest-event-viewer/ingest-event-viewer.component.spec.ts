/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { NO_ERRORS_SCHEMA, ChangeDetectorRef } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { BehaviorSubject, Subject, of, throwError, NEVER } from 'rxjs';

import { IngestEventViewerComponent } from './ingest-event-viewer.component';
import { IngestEventService } from '../../services/ingest-event.service';
import {
  IngestEvent,
  IngestEventType,
  IngestPhase,
  EventLogSummary,
  EventLogStatusResponse,
  RecentEventsResponse,
  ErrorEventsResponse,
  TaskEventsResponse,
  TaskEnvironmentResponse
} from '../../models/api-models';

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

function makeEvent(overrides: Partial<IngestEvent> = {}): IngestEvent {
  return {
    id: 1,
    taskId: 'task-001',
    fileName: 'doc.pdf',
    eventType: 'PROGRESS',
    message: 'Processing',
    timestamp: new Date().toISOString(),
    ...overrides
  };
}

function makeSummary(overrides: Partial<EventLogSummary> = {}): EventLogSummary {
  return {
    lookbackHours: 24,
    totalTasks: 3,
    completed: 2,
    failed: 0,
    cancelled: 0,
    errorCount: 0,
    averageDurationMs: 1000,
    totalEventsInDb: 10,
    ...overrides
  };
}

function makeStatus(overrides: Partial<EventLogStatusResponse> = {}): EventLogStatusResponse {
  return { enabled: true, totalEvents: 10, ...overrides };
}

// ─────────────────────────────────────────────────────────────────────────────
// Test suite
// ─────────────────────────────────────────────────────────────────────────────

describe('IngestEventViewerComponent', () => {
  let component: IngestEventViewerComponent;
  let fixture: ComponentFixture<IngestEventViewerComponent>;

  // Service subjects — shared across tests
  let eventsSubject$: BehaviorSubject<IngestEvent[]>;
  let summarySubject$: BehaviorSubject<EventLogSummary | null>;
  let statusSubject$: BehaviorSubject<EventLogStatusResponse | null>;
  let newEventSubject$: Subject<IngestEvent>;
  let wsConnectedSubject$: BehaviorSubject<boolean>;
  let activeRestartsSubject$: BehaviorSubject<Map<string, any>>;

  let ingestSpy: jasmine.SpyObj<IngestEventService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;
  let cdrSpy: jasmine.SpyObj<ChangeDetectorRef>;

  beforeEach(async () => {
    // Initialise subjects fresh for each test
    eventsSubject$ = new BehaviorSubject<IngestEvent[]>([]);
    summarySubject$ = new BehaviorSubject<EventLogSummary | null>(null);
    statusSubject$ = new BehaviorSubject<EventLogStatusResponse | null>(null);
    newEventSubject$ = new Subject<IngestEvent>();
    wsConnectedSubject$ = new BehaviorSubject<boolean>(false);
    activeRestartsSubject$ = new BehaviorSubject<Map<string, any>>(new Map());

    ingestSpy = jasmine.createSpyObj('IngestEventService', [
      'getStatus',
      'getSummary',
      'getRecentEvents',
      'getErrorEvents',
      'getEventsForTask',
      'getTaskEnvironment',
      'deleteTaskEvents',
      'forceCleanup',
      'manualRestart',
      'startPolling',
      'stopPolling',
      'groupEventsByTask',
      'sortEventsByTimestamp',
      'formatDuration',
      'formatTimestamp',
      'getRelativeTime',
      'isRestartEvent',
      'isMemoryEvent'
    ], {
      events$: eventsSubject$.asObservable(),
      summary$: summarySubject$.asObservable(),
      status$: statusSubject$.asObservable(),
      newEvent$: newEventSubject$.asObservable(),
      wsConnected$: wsConnectedSubject$.asObservable(),
      activeRestarts$: activeRestartsSubject$.asObservable()
    });

    // Sensible defaults
    ingestSpy.getStatus.and.returnValue(of(makeStatus()));
    ingestSpy.getSummary.and.returnValue(of(makeSummary()));
    ingestSpy.getRecentEvents.and.returnValue(of({ lookbackHours: 24, eventCount: 0, events: [] } as RecentEventsResponse));
    ingestSpy.getErrorEvents.and.returnValue(of({ lookbackHours: 24, errorCount: 0, errors: [] } as ErrorEventsResponse));
    ingestSpy.groupEventsByTask.and.returnValue(new Map());
    ingestSpy.sortEventsByTimestamp.and.callFake((events: IngestEvent[]) => [...events]);
    ingestSpy.formatDuration.and.returnValue('1.0s');
    ingestSpy.formatTimestamp.and.returnValue('Jan 1, 2025');
    ingestSpy.getRelativeTime.and.returnValue('Just now');
    ingestSpy.isRestartEvent.and.returnValue(false);
    ingestSpy.isMemoryEvent.and.returnValue(false);

    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);

    cdrSpy = jasmine.createSpyObj('ChangeDetectorRef', ['markForCheck', 'detectChanges']);

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule],
      declarations: [IngestEventViewerComponent],
      providers: [
        { provide: IngestEventService, useValue: ingestSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideTemplate(IngestEventViewerComponent, '<div></div>')
    .compileComponents();

    fixture = TestBed.createComponent(IngestEventViewerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    component.ngOnDestroy();
  });

  // ── 1. Creation ────────────────────────────────────────────────────────────

  it('should create', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    discardPeriodicTasks();
    expect(component).toBeTruthy();
  }));

  it('should call loadStatus on init', fakeAsync(() => {
    fixture.detectChanges();
    tick();
    discardPeriodicTasks();
    expect(ingestSpy.getStatus).toHaveBeenCalled();
  }));

  it('should start auto-refresh polling on init when autoRefresh=true', fakeAsync(() => {
    component.autoRefresh = true;
    fixture.detectChanges();
    tick();
    discardPeriodicTasks();
    expect(ingestSpy.startPolling).toHaveBeenCalled();
  }));

  it('should have correct initial state', fakeAsync(() => {
    tick();
    discardPeriodicTasks();
    expect(component.viewMode).toBe('live');
    expect(component.autoRefresh).toBeTrue();
    expect(component.refreshInterval).toBe(5);
    expect(component.lookbackHours).toBe(24);
    expect(component.showErrorsOnly).toBeFalse();
    expect(component.loading).toBeFalse();
    expect(component.error).toBeNull();
  }));

  // ── 2. loadStatus ──────────────────────────────────────────────────────────

  describe('loadStatus()', () => {
    it('should update status and call loadEvents/loadSummary when enabled', fakeAsync(() => {
      const enabledStatus = makeStatus({ enabled: true });
      ingestSpy.getStatus.and.returnValue(of(enabledStatus));
      // Pre-seed the statusSubject$ so the setupSubscriptions() subscription
      // emits the correct value (not the initial null) when component initialises.
      statusSubject$.next(enabledStatus);
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();

      expect(component.status?.enabled).toBeTrue();
      expect(ingestSpy.getRecentEvents).toHaveBeenCalled();
      expect(ingestSpy.getSummary).toHaveBeenCalled();
    }));

    it('should not call loadEvents when status.enabled=false', fakeAsync(() => {
      ingestSpy.getStatus.and.returnValue(of(makeStatus({ enabled: false })));
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();

      expect(ingestSpy.getRecentEvents).not.toHaveBeenCalled();
    }));

    it('should set error string when getStatus fails', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      ingestSpy.getStatus.and.returnValue(throwError(() => new Error('Network error')));
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();

      expect(component.error).toBe('Failed to load event log status');
    }));
  });

  // ── 3. loadEvents ──────────────────────────────────────────────────────────

  describe('loadEvents()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
      // Reset call count after init
      ingestSpy.getRecentEvents.calls.reset();
      ingestSpy.getErrorEvents.calls.reset();
    }));

    it('should call getRecentEvents when showErrorsOnly=false', fakeAsync(() => {
      component.showErrorsOnly = false;
      component.loadEvents();
      tick();

      expect(ingestSpy.getRecentEvents).toHaveBeenCalledWith(component.lookbackHours);
    }));

    it('should call getErrorEvents when showErrorsOnly=true', fakeAsync(() => {
      component.showErrorsOnly = true;
      component.loadEvents();
      tick();

      expect(ingestSpy.getErrorEvents).toHaveBeenCalledWith(component.lookbackHours);
    }));

    it('should set loading=false and populate events on success', fakeAsync(() => {
      const events = [makeEvent({ id: 1 }), makeEvent({ id: 2 })];
      ingestSpy.getRecentEvents.and.returnValue(of({ lookbackHours: 24, eventCount: 2, events }));

      component.loadEvents();
      tick();

      expect(component.events.length).toBe(2);
      expect(component.loading).toBeFalse();
    }));

    it('should set error on getRecentEvents failure', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      ingestSpy.getRecentEvents.and.returnValue(throwError(() => new Error('Failed')));
      component.loadEvents();
      tick();

      expect(component.error).toBe('Failed to load events');
      expect(component.loading).toBeFalse();
    }));

    it('should set error on getErrorEvents failure', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      component.showErrorsOnly = true;
      ingestSpy.getErrorEvents.and.returnValue(throwError(() => new Error('Failed')));
      component.loadEvents();
      tick();

      expect(component.error).toBe('Failed to load events');
      expect(component.loading).toBeFalse();
    }));
  });

  // ── 4. loadSummary ─────────────────────────────────────────────────────────

  describe('loadSummary()', () => {
    it('should update summary on success', fakeAsync(() => {
      const summary = makeSummary({ completed: 5 });
      ingestSpy.getSummary.and.returnValue(of(summary));
      // Pre-seed summarySubject$ so that when setupSubscriptions() subscribes,
      // the BehaviorSubject emits the expected value rather than null.
      summarySubject$.next(summary);

      fixture.detectChanges();
      tick();
      discardPeriodicTasks();

      expect(component.summary?.completed).toBe(5);
    }));

    it('should not set component.error on getSummary failure (logs only)', fakeAsync(() => {
      ingestSpy.getSummary.and.returnValue(throwError(() => new Error('Summary error')));
      spyOn(console, 'error');

      fixture.detectChanges();
      tick();
      discardPeriodicTasks();

      // Error is logged but not propagated to component.error
      expect(console.error).toHaveBeenCalled();
      expect(component.error).toBeNull();
    }));
  });

  // ── 5. loadTaskEvents ──────────────────────────────────────────────────────

  describe('loadTaskEvents()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should call getEventsForTask and getTaskEnvironment', fakeAsync(() => {
      const taskEvents: TaskEventsResponse = { taskId: 'task-001', eventCount: 1, events: [makeEvent()] };
      const envResponse: TaskEnvironmentResponse = {
        taskId: 'task-001', fileName: 'doc.pdf',
        timestamp: new Date().toISOString(), environmentCaptured: true
      };
      ingestSpy.getEventsForTask.and.returnValue(of(taskEvents));
      ingestSpy.getTaskEnvironment.and.returnValue(of(envResponse));

      // Pre-populate taskGroups so the component can find the group and call getTaskEnvironment
      component.taskGroups = [{
        taskId: 'task-001', fileName: 'doc.pdf', events: [],
        latestEvent: makeEvent(), status: 'info', expanded: false
      }] as any[];

      component.loadTaskEvents('task-001');
      tick();

      expect(ingestSpy.getEventsForTask).toHaveBeenCalledWith('task-001');
      expect(ingestSpy.getTaskEnvironment).toHaveBeenCalledWith('task-001');
      expect(component.selectedTaskId).toBe('task-001');
    }));

    it('should set error on getEventsForTask failure', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      ingestSpy.getEventsForTask.and.returnValue(throwError(() => new Error('Fail')));
      ingestSpy.getTaskEnvironment.and.returnValue(NEVER);

      component.loadTaskEvents('task-fail');
      tick();

      expect(component.error).toContain('task-fail');
      expect(component.loading).toBeFalse();
    }));
  });

  // ── 6. getFilteredEvents ───────────────────────────────────────────────────

  describe('getFilteredEvents()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should return all events when no filters are active', () => {
      component.events = [makeEvent(), makeEvent({ id: 2 })];
      expect(component.getFilteredEvents().length).toBe(2);
    });

    it('should filter by event type', () => {
      component.events = [
        makeEvent({ eventType: 'ERROR' }),
        makeEvent({ eventType: 'PROGRESS' }),
        makeEvent({ eventType: 'COMPLETED' })
      ];
      component.filterEventTypes = ['ERROR'];
      const filtered = component.getFilteredEvents();
      expect(filtered.length).toBe(1);
      expect(filtered[0].eventType).toBe('ERROR');
    });

    it('should filter by phase', () => {
      component.events = [
        makeEvent({ phase: IngestPhase.EMBEDDING }),
        makeEvent({ phase: IngestPhase.CHUNKING }),
        makeEvent()
      ];
      component.filterPhases = [IngestPhase.EMBEDDING];
      const filtered = component.getFilteredEvents();
      expect(filtered.length).toBe(1);
    });

    it('should filter by search term (fileName)', () => {
      component.events = [
        makeEvent({ fileName: 'important.pdf' }),
        makeEvent({ fileName: 'other.docx' })
      ];
      component.searchTerm = 'important';
      const filtered = component.getFilteredEvents();
      expect(filtered.length).toBe(1);
      expect(filtered[0].fileName).toBe('important.pdf');
    });

    it('should filter by search term (message)', () => {
      component.events = [
        makeEvent({ message: 'embedding complete' }),
        makeEvent({ message: 'unrelated' })
      ];
      component.searchTerm = 'embedding';
      expect(component.getFilteredEvents().length).toBe(1);
    });

    it('should be case-insensitive for search', () => {
      component.events = [makeEvent({ fileName: 'MyDocument.pdf' })];
      component.searchTerm = 'mydoc';
      expect(component.getFilteredEvents().length).toBe(1);
    });
  });

  // ── 7. getPaginatedEvents ──────────────────────────────────────────────────

  describe('getPaginatedEvents()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should return the first page slice', () => {
      component.events = Array.from({ length: 100 }, (_, i) => makeEvent({ id: i }));
      component.pageSize = 50;
      component.currentPage = 0;
      expect(component.getPaginatedEvents().length).toBe(50);
    });

    it('should return the second page slice', () => {
      component.events = Array.from({ length: 75 }, (_, i) => makeEvent({ id: i }));
      component.pageSize = 50;
      component.currentPage = 1;
      expect(component.getPaginatedEvents().length).toBe(25);
    });
  });

  // ── 8. getTotalPages ───────────────────────────────────────────────────────

  describe('getTotalPages()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should compute total pages correctly', () => {
      component.events = Array.from({ length: 105 }, (_, i) => makeEvent({ id: i }));
      component.pageSize = 50;
      expect(component.getTotalPages()).toBe(3);
    });

    it('should return 0 for empty events', () => {
      component.events = [];
      expect(component.getTotalPages()).toBe(0);
    });
  });

  // ── 9. Auto refresh methods ────────────────────────────────────────────────

  describe('Auto-refresh methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
      ingestSpy.startPolling.calls.reset();
      ingestSpy.stopPolling.calls.reset();
    }));

    it('startAutoRefresh should call startPolling with correct interval', () => {
      component.refreshInterval = 10;
      component.startAutoRefresh();
      expect(ingestSpy.startPolling).toHaveBeenCalledWith(10000, component.lookbackHours);
    });

    it('stopAutoRefresh should call stopPolling', () => {
      component.stopAutoRefresh();
      expect(ingestSpy.stopPolling).toHaveBeenCalled();
    });

    it('toggleAutoRefresh should start polling when autoRefresh becomes true', () => {
      component.autoRefresh = false;
      component.toggleAutoRefresh();
      expect(component.autoRefresh).toBeTrue();
      expect(ingestSpy.startPolling).toHaveBeenCalled();
    });

    it('toggleAutoRefresh should stop polling when autoRefresh becomes false', () => {
      component.autoRefresh = true;
      component.toggleAutoRefresh();
      expect(component.autoRefresh).toBeFalse();
      expect(ingestSpy.stopPolling).toHaveBeenCalled();
    });

    it('onRefreshIntervalChange should restart polling when autoRefresh=true', () => {
      component.autoRefresh = true;
      component.onRefreshIntervalChange();
      expect(ingestSpy.stopPolling).toHaveBeenCalled();
      expect(ingestSpy.startPolling).toHaveBeenCalled();
    });

    it('onRefreshIntervalChange should do nothing when autoRefresh=false', () => {
      component.autoRefresh = false;
      component.onRefreshIntervalChange();
      expect(ingestSpy.stopPolling).not.toHaveBeenCalled();
    });

    it('manualRefresh should call loadEvents and loadSummary', fakeAsync(() => {
      ingestSpy.getRecentEvents.calls.reset();
      ingestSpy.getSummary.calls.reset();

      component.manualRefresh();
      tick();

      expect(ingestSpy.getRecentEvents).toHaveBeenCalled();
      expect(ingestSpy.getSummary).toHaveBeenCalled();
    }));
  });

  // ── 10. View mode ──────────────────────────────────────────────────────────

  describe('onViewModeChange()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should update viewMode', () => {
      component.onViewModeChange('grouped');
      expect(component.viewMode).toBe('grouped');
    });

    it('should update to timeline', () => {
      component.onViewModeChange('timeline');
      expect(component.viewMode).toBe('timeline');
    });
  });

  // ── 11. Filter methods ─────────────────────────────────────────────────────

  describe('Filter methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('clearFilters should reset all filter fields', () => {
      component.filterEventTypes = ['ERROR'];
      component.filterPhases = [IngestPhase.CHUNKING];
      component.searchTerm = 'foo';
      component.showErrorsOnly = true;

      component.clearFilters();

      expect(component.filterEventTypes).toEqual([]);
      expect(component.filterPhases).toEqual([]);
      expect(component.searchTerm).toBe('');
      expect(component.showErrorsOnly).toBeFalse();
    });

    it('toggleEventTypeFilter should add type if not present', () => {
      component.filterEventTypes = [];
      component.toggleEventTypeFilter('ERROR');
      expect(component.filterEventTypes).toContain('ERROR');
    });

    it('toggleEventTypeFilter should remove type if already present', () => {
      component.filterEventTypes = ['ERROR', 'PROGRESS'];
      component.toggleEventTypeFilter('ERROR');
      expect(component.filterEventTypes).not.toContain('ERROR');
      expect(component.filterEventTypes).toContain('PROGRESS');
    });

    it('isEventTypeSelected should return true when type is in filter', () => {
      component.filterEventTypes = ['COMPLETED'];
      expect(component.isEventTypeSelected('COMPLETED')).toBeTrue();
    });

    it('isEventTypeSelected should return false when type is not in filter', () => {
      component.filterEventTypes = [];
      expect(component.isEventTypeSelected('COMPLETED')).toBeFalse();
    });

    it('togglePhaseFilter should add phase if not present', () => {
      component.filterPhases = [];
      component.togglePhaseFilter(IngestPhase.EMBEDDING);
      expect(component.filterPhases).toContain(IngestPhase.EMBEDDING);
    });

    it('togglePhaseFilter should remove phase if already present', () => {
      component.filterPhases = [IngestPhase.EMBEDDING];
      component.togglePhaseFilter(IngestPhase.EMBEDDING);
      expect(component.filterPhases).not.toContain(IngestPhase.EMBEDDING);
    });

    it('isPhaseSelected should return true for selected phase', () => {
      component.filterPhases = [IngestPhase.CHUNKING];
      expect(component.isPhaseSelected(IngestPhase.CHUNKING)).toBeTrue();
    });

    it('onFilterChange should reset currentPage to 0', () => {
      component.currentPage = 3;
      component.onFilterChange();
      expect(component.currentPage).toBe(0);
    });

    it('onLookbackChange should call loadEvents and loadSummary', fakeAsync(() => {
      ingestSpy.getRecentEvents.calls.reset();
      ingestSpy.getSummary.calls.reset();

      component.onLookbackChange();
      tick();

      expect(ingestSpy.getRecentEvents).toHaveBeenCalled();
      expect(ingestSpy.getSummary).toHaveBeenCalled();
    }));
  });

  // ── 12. Cleanup dialog ─────────────────────────────────────────────────────

  describe('Cleanup dialog', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('openCleanupDialog should set showCleanupDialog=true', () => {
      component.openCleanupDialog();
      expect(component.showCleanupDialog).toBeTrue();
    });

    it('closeCleanupDialog should set showCleanupDialog=false', () => {
      component.showCleanupDialog = true;
      component.closeCleanupDialog();
      expect(component.showCleanupDialog).toBeFalse();
    });

    it('performCleanup should call forceCleanup and close dialog on success', fakeAsync(() => {
      ingestSpy.forceCleanup.and.returnValue(of({ olderThanDays: 7, deletedCount: 5 }));
      component.showCleanupDialog = true;

      component.performCleanup();
      tick();

      expect(ingestSpy.forceCleanup).toHaveBeenCalledWith(component.cleanupDays);
      expect(component.showCleanupDialog).toBeFalse();
      expect(component.loading).toBeFalse();
    }));

    it('performCleanup should set error on failure', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      ingestSpy.forceCleanup.and.returnValue(throwError(() => new Error('Cleanup failed')));

      component.performCleanup();
      tick();

      expect(component.error).toBe('Cleanup failed');
      expect(component.loading).toBeFalse();
    }));
  });

  // ── 13. deleteTaskEvents ───────────────────────────────────────────────────

  describe('deleteTaskEvents()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should call dialog.open and then deleteTaskEvents on confirm', fakeAsync(() => {
      ingestSpy.deleteTaskEvents.and.returnValue(of({ taskId: 'task-001', deleted: true }));
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);

      const fakeEvent = { stopPropagation: jasmine.createSpy() } as any;
      component.deleteTaskEvents('task-001', fakeEvent);
      tick();

      expect(fakeEvent.stopPropagation).toHaveBeenCalled();
      expect(dialogSpy.open).toHaveBeenCalled();
      expect(ingestSpy.deleteTaskEvents).toHaveBeenCalledWith('task-001');
    }));

    it('should not call deleteTaskEvents when dialog is cancelled (false)', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(false) } as any);

      const fakeEvent = { stopPropagation: jasmine.createSpy() } as any;
      component.deleteTaskEvents('task-001', fakeEvent);
      tick();

      expect(ingestSpy.deleteTaskEvents).not.toHaveBeenCalled();
    }));

    it('should set error when deleteTaskEvents service call fails', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      ingestSpy.deleteTaskEvents.and.returnValue(throwError(() => new Error('Delete error')));

      const fakeEvent = { stopPropagation: jasmine.createSpy() } as any;
      component.deleteTaskEvents('task-001', fakeEvent);
      tick();

      expect(component.error).toContain('task-001');
    }));
  });

  // ── 14. manualRestart ─────────────────────────────────────────────────────

  describe('manualRestart()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should call dialog.open and then manualRestart on confirm', fakeAsync(() => {
      ingestSpy.manualRestart.and.returnValue(of({}));
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);

      component.manualRestart('task-001');
      tick();

      expect(dialogSpy.open).toHaveBeenCalled();
      expect(ingestSpy.manualRestart).toHaveBeenCalledWith('task-001');
    }));

    it('should not call manualRestart when dialog is cancelled', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(false) } as any);

      component.manualRestart('task-001');
      tick();

      expect(ingestSpy.manualRestart).not.toHaveBeenCalled();
    }));

    it('should set error on manualRestart failure', fakeAsync(() => {
      spyOn(console, 'error').and.stub();
      dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);
      ingestSpy.manualRestart.and.returnValue(
        throwError(() => ({ message: 'Connection refused' }))
      );

      component.manualRestart('task-fail');
      tick();

      expect(component.error).toContain('restart task');
    }));

    it('should call event.stopPropagation when event is provided', fakeAsync(() => {
      dialogSpy.open.and.returnValue({ afterClosed: () => of(false) } as any);
      const fakeEvent = { stopPropagation: jasmine.createSpy() } as any;

      component.manualRestart('task-001', fakeEvent);
      tick();

      expect(fakeEvent.stopPropagation).toHaveBeenCalled();
    }));
  });

  // ── 15. Active state methods ───────────────────────────────────────────────

  describe('Active state & live log methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('getActiveTasks should exclude completed/failed/cancelled groups', () => {
      component.taskGroups = [
        { taskId: 'a', fileName: 'a.pdf', events: [], latestEvent: makeEvent({ eventType: 'PROGRESS' }), status: 'info', expanded: false },
        { taskId: 'b', fileName: 'b.pdf', events: [], latestEvent: makeEvent({ eventType: 'COMPLETED' }), status: 'success', expanded: false },
        { taskId: 'c', fileName: 'c.pdf', events: [], latestEvent: makeEvent({ eventType: 'FAILED' }), status: 'error', expanded: false },
        { taskId: 'd', fileName: 'd.pdf', events: [], latestEvent: makeEvent({ eventType: 'CANCELLED' }), status: 'warning', expanded: false }
      ] as any[];

      const active = component.getActiveTasks();
      expect(active.length).toBe(1);
      expect(active[0].taskId).toBe('a');
    });

    it('getActiveTaskCount should reflect active task count', () => {
      component.taskGroups = [
        { taskId: 'a', fileName: 'a.pdf', events: [], latestEvent: makeEvent({ eventType: 'PHASE_STARTED' }), status: 'info', expanded: false }
      ] as any[];
      expect(component.getActiveTaskCount()).toBe(1);
    });

    it('getErrorEvents should return ERROR, FAILED, WARNING events from liveEvents', () => {
      component.liveEvents = [
        makeEvent({ eventType: 'ERROR' }),
        makeEvent({ eventType: 'FAILED' }),
        makeEvent({ eventType: 'WARNING' }),
        makeEvent({ eventType: 'PROGRESS' })
      ];
      expect(component.getErrorEvents().length).toBe(3);
    });

    it('getLogLineClass should return correct class for different event types', () => {
      expect(component.getLogLineClass(makeEvent({ eventType: 'ERROR' }))).toBe('log-error');
      expect(component.getLogLineClass(makeEvent({ eventType: 'FAILED' }))).toBe('log-error');
      expect(component.getLogLineClass(makeEvent({ eventType: 'WARNING' }))).toBe('log-warning');
      expect(component.getLogLineClass(makeEvent({ eventType: 'COMPLETED' }))).toBe('log-success');
      expect(component.getLogLineClass(makeEvent({ eventType: 'PROGRESS' }))).toBe('log-info');
      expect(component.getLogLineClass(makeEvent({ eventType: 'QUEUED' }))).toBe('');
    });

    it('getCurrentStateClass should return state-idle when not connected and no active tasks', () => {
      component.wsConnected = false;
      component.taskGroups = [];
      component.liveEvents = [];
      expect(component.getCurrentStateClass()).toBe('state-idle');
    });

    it('getCurrentStateClass should return state-processing when active tasks exist', () => {
      component.wsConnected = true;
      component.taskGroups = [
        { taskId: 'a', fileName: 'a.pdf', events: [], latestEvent: makeEvent({ eventType: 'PROGRESS' }), status: 'info', expanded: false }
      ] as any[];
      expect(component.getCurrentStateClass()).toBe('state-processing');
    });

    it('getCurrentStateText should return idle text when nothing is happening', () => {
      component.wsConnected = true;
      component.taskGroups = [];
      component.liveEvents = [];
      component.summary = makeSummary({ completed: 0 });
      expect(component.getCurrentStateText()).toBe('Idle - Waiting for Documents');
    });

    it('getCurrentStateIcon should return wifi_off when ws disconnected', () => {
      component.wsConnected = false;
      expect(component.getCurrentStateIcon()).toBe('wifi_off');
    });
  });

  // ── 16. Restart-related methods ────────────────────────────────────────────

  describe('Restart-related methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('isRestartEvent should delegate to service', () => {
      ingestSpy.isRestartEvent.and.returnValue(true);
      expect(component.isRestartEvent(makeEvent({ eventType: 'RESTART_SCHEDULED' }))).toBeTrue();
      expect(ingestSpy.isRestartEvent).toHaveBeenCalledWith('RESTART_SCHEDULED');
    });

    it('isMemoryEvent should delegate to service', () => {
      ingestSpy.isMemoryEvent.and.returnValue(true);
      expect(component.isMemoryEvent(makeEvent({ eventType: 'MEMORY_ANALYSIS' }))).toBeTrue();
    });

    it('getRestartEvents should return only restart events from liveEvents', () => {
      ingestSpy.isRestartEvent.and.callFake((et: IngestEventType) =>
        ['RESTART_SCHEDULED', 'RESTART_ATTEMPTED'].includes(et)
      );
      component.liveEvents = [
        makeEvent({ eventType: 'RESTART_SCHEDULED' }),
        makeEvent({ eventType: 'PROGRESS' }),
        makeEvent({ eventType: 'RESTART_ATTEMPTED' })
      ];
      expect(component.getRestartEvents().length).toBe(2);
    });

    it('hasActiveRestart should check activeRestarts map', () => {
      component.activeRestarts = new Map([['task-001', {} as any]]);
      expect(component.hasActiveRestart('task-001')).toBeTrue();
      expect(component.hasActiveRestart('task-999')).toBeFalse();
    });

    it('getRestartInfo should return RestartInfo for known task', () => {
      const info = { taskId: 'task-001', attemptNumber: 2, maxAttempts: 3 } as any;
      component.activeRestarts = new Map([['task-001', info]]);
      expect(component.getRestartInfo('task-001')).toEqual(info);
    });

    it('canManuallyRestart should return true for FAILED event with no active restart', () => {
      component.activeRestarts = new Map();
      const event = makeEvent({ taskId: 'task-001', eventType: 'FAILED' });
      expect(component.canManuallyRestart(event)).toBeTrue();
    });

    it('canManuallyRestart should return false when restart is already active', () => {
      component.activeRestarts = new Map([['task-001', {} as any]]);
      const event = makeEvent({ taskId: 'task-001', eventType: 'FAILED' });
      expect(component.canManuallyRestart(event)).toBeFalse();
    });

    it('canManuallyRestart should return false for PROGRESS event', () => {
      component.activeRestarts = new Map();
      const event = makeEvent({ taskId: 'task-001', eventType: 'PROGRESS' });
      expect(component.canManuallyRestart(event)).toBeFalse();
    });

    it('getRestartEventClass should return correct CSS classes', () => {
      expect(component.getRestartEventClass(makeEvent({ eventType: 'RESTART_SCHEDULED' }))).toBe('log-restart-scheduled');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'RESTART_SUCCEEDED' }))).toBe('log-restart-succeeded');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'RESTART_FAILED' }))).toBe('log-restart-failed');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'MEMORY_ANALYSIS' }))).toBe('log-memory-analysis');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'HEAP_ADJUSTED' }))).toBe('log-heap-adjusted');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'THREADS_REDUCED' }))).toBe('log-threads-reduced');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'MANUAL_RESTART' }))).toBe('log-manual-restart');
      expect(component.getRestartEventClass(makeEvent({ eventType: 'PROGRESS' }))).toBe('');
    });

    it('getLogLineClassEnhanced should use restart class when available', () => {
      const event = makeEvent({ eventType: 'RESTART_SCHEDULED' });
      spyOn(component, 'getRestartEventClass').and.returnValue('log-restart-scheduled');
      expect(component.getLogLineClassEnhanced(event)).toBe('log-restart-scheduled');
    });

    it('getLogLineClassEnhanced should fall back to getLogLineClass for non-restart events', () => {
      const event = makeEvent({ eventType: 'ERROR' });
      spyOn(component, 'getRestartEventClass').and.returnValue('');
      expect(component.getLogLineClassEnhanced(event)).toBe('log-error');
    });

    it('getRestartAttemptCount should count restart-related events', () => {
      const group = {
        taskId: 'a',
        events: [
          makeEvent({ eventType: 'RESTART_SCHEDULED' }),
          makeEvent({ eventType: 'RESTART_ATTEMPTED' }),
          makeEvent({ eventType: 'RESTART_SUCCEEDED' }),
          makeEvent({ eventType: 'PROGRESS' })
        ]
      } as any;
      expect(component.getRestartAttemptCount(group)).toBe(3);
    });

    it('hasRestartEvents should return true when restart events exist', () => {
      const group = {
        events: [makeEvent({ eventType: 'RESTART_FAILED' })]
      } as any;
      spyOn(component, 'getRestartAttemptCount').and.returnValue(1);
      expect(component.hasRestartEvents(group)).toBeTrue();
    });
  });

  // ── 17. Utility methods ────────────────────────────────────────────────────

  describe('Utility methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('formatDuration should delegate to service', () => {
      ingestSpy.formatDuration.and.returnValue('5.0s');
      expect(component.formatDuration(5000)).toBe('5.0s');
    });

    it('formatTimestamp should delegate to service', () => {
      ingestSpy.formatTimestamp.and.returnValue('Jan 1, 2025, 12:00 PM');
      expect(component.formatTimestamp('2025-01-01T12:00:00Z')).toBe('Jan 1, 2025, 12:00 PM');
    });

    it('getRelativeTime should delegate to service', () => {
      ingestSpy.getRelativeTime.and.returnValue('5 min ago');
      expect(component.getRelativeTime('2025-01-01T11:55:00Z')).toBe('5 min ago');
    });

    it('getStatusClass should return correct CSS class', () => {
      expect(component.getStatusClass('success')).toBe('status-success');
      expect(component.getStatusClass('warning')).toBe('status-warning');
      expect(component.getStatusClass('error')).toBe('status-error');
      expect(component.getStatusClass('info')).toBe('status-info');
      expect(component.getStatusClass('unknown')).toBe('status-info');
    });

    it('getSeverityClass should return severity- prefixed class', () => {
      // getEventSeverity for COMPLETED returns 'success'
      expect(component.getSeverityClass('COMPLETED')).toBe('severity-success');
      expect(component.getSeverityClass('ERROR')).toBe('severity-error');
    });

    it('trackByTaskId should return taskId', () => {
      const group = { taskId: 'my-task' } as any;
      expect(component.trackByTaskId(0, group)).toBe('my-task');
    });

    it('trackByEventId should return event id when present', () => {
      const event = makeEvent({ id: 42 });
      expect(component.trackByEventId(0, event)).toBe(42);
    });

    it('trackByEventId should return composite key when id is absent', () => {
      const event = makeEvent({ id: undefined, taskId: 'task-abc', timestamp: '2025-01-01T00:00:00Z' });
      const result = component.trackByEventId(0, event);
      expect(result).toBe('task-abc-2025-01-01T00:00:00Z');
    });

    it('formatLogTimestamp should return empty string for empty input', () => {
      expect(component.formatLogTimestamp('')).toBe('');
    });

    it('formatLogTimestamp should return a time string for valid ISO input', () => {
      const result = component.formatLogTimestamp('2025-01-01T14:30:45Z');
      expect(typeof result).toBe('string');
      expect(result.length).toBeGreaterThan(0);
    });

    it('formatBytesToHuman should return N/A for undefined', () => {
      expect(component.formatBytesToHuman(undefined)).toBe('N/A');
    });

    it('formatBytesToHuman should format bytes correctly', () => {
      expect(component.formatBytesToHuman(1024)).toContain('KB');
      expect(component.formatBytesToHuman(1024 * 1024)).toContain('MB');
    });

    it('getRestartCountdown should return "soon" when no timing info', () => {
      const event = makeEvent({ nextRestartTime: undefined, backoffMs: undefined });
      expect(component.getRestartCountdown(event)).toBe('soon');
    });

    it('getRestartCountdown should return backoff in seconds when only backoffMs set', () => {
      const event = makeEvent({ backoffMs: 5000 });
      expect(component.getRestartCountdown(event)).toBe('5s');
    });
  });

  // ── 18. getMemoryDetails ───────────────────────────────────────────────────

  describe('getMemoryDetails()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should return null for non-memory non-restart events', () => {
      ingestSpy.isMemoryEvent.and.returnValue(false);
      const event = makeEvent({ eventType: 'PROGRESS' });
      expect(component.getMemoryDetails(event)).toBeNull();
    });

    it('should return details for memory events', () => {
      ingestSpy.isMemoryEvent.and.returnValue(true);
      const event = makeEvent({
        eventType: 'MEMORY_ANALYSIS',
        systemRamTotal: 16 * 1024 * 1024 * 1024,
        systemRamFree: 8 * 1024 * 1024 * 1024,
        heapSize: '4g',
        heapIncreased: true,
        ompThreads: 4,
        blasThreads: 2,
        memoryAnalysisReason: 'Low memory detected'
      } as any);

      const details = component.getMemoryDetails(event);
      expect(details).not.toBeNull();
      expect(details!.heapSize).toBe('4g');
      expect(details!.heapIncreased).toBeTrue();
      expect(details!.ompThreads).toBe(4);
      expect(details!.reason).toBe('Low memory detected');
    });
  });

  // ── 19. onTaskClick ────────────────────────────────────────────────────────

  describe('onTaskClick()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
    }));

    it('should collapse expanded group', () => {
      const group = {
        taskId: 'task-001', fileName: 'doc.pdf', events: [],
        latestEvent: makeEvent(), status: 'info', expanded: true
      } as any;

      component.selectedTaskId = 'task-001';
      component.onTaskClick(group);

      expect(group.expanded).toBeFalse();
      expect(component.selectedTaskId).toBeNull();
    });

    it('should call loadTaskEvents for collapsed group', () => {
      ingestSpy.getEventsForTask.and.returnValue(of({ taskId: 'task-002', eventCount: 0, events: [] }));
      ingestSpy.getTaskEnvironment.and.returnValue(of({
        taskId: 'task-002', fileName: 'b.pdf',
        timestamp: new Date().toISOString(), environmentCaptured: false
      }));

      const group = {
        taskId: 'task-002', fileName: 'b.pdf', events: [],
        latestEvent: makeEvent({ taskId: 'task-002' }), status: 'info', expanded: false
      } as any;

      component.onTaskClick(group);

      expect(ingestSpy.getEventsForTask).toHaveBeenCalledWith('task-002');
    });
  });

  // ── 20. ngOnDestroy ────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should call stopPolling on destroy', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      discardPeriodicTasks();
      ingestSpy.stopPolling.calls.reset();

      component.ngOnDestroy();

      expect(ingestSpy.stopPolling).toHaveBeenCalled();
    }));
  });
});
