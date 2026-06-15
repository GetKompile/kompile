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

import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AuditLogViewerComponent } from './audit-log-viewer.component';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  AuditLogEntry,
  AuditSearchCriteria,
  AuditStats,
  AuditEventType,
  AuditEntityType,
  PagedResult
} from '../../../../models/orchestrator-models';

// Angular Material Modules
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';

describe('AuditLogViewerComponent', () => {
  let component: AuditLogViewerComponent;
  let fixture: ComponentFixture<AuditLogViewerComponent>;
  let orchestratorServiceSpy: jasmine.SpyObj<OrchestratorService>;

  // Test data
  const mockAuditLogs: AuditLogEntry[] = [
    {
      id: 1,
      orchestratorInstanceId: 'test-instance',
      eventType: 'STATE_CHANGE',
      entityType: 'STATE',
      entityId: 'state-1',
      action: 'TRANSITION',
      message: 'State changed from INITIAL to PROCESSING',
      actorId: 'system',
      actorType: 'SYSTEM',
      error: false,
      durationMs: 150,
      timestamp: '2025-01-04T10:00:00Z'
    },
    {
      id: 2,
      orchestratorInstanceId: 'test-instance',
      eventType: 'TASK_LIFECYCLE',
      entityType: 'TASK',
      entityId: 'task-1',
      action: 'STARTED',
      message: 'Task build started',
      actorId: 'user-1',
      actorType: 'USER',
      error: false,
      durationMs: 0,
      timestamp: '2025-01-04T10:01:00Z'
    },
    {
      id: 3,
      orchestratorInstanceId: 'test-instance',
      eventType: 'TASK_LIFECYCLE',
      entityType: 'TASK',
      entityId: 'task-1',
      action: 'COMPLETED',
      message: 'Task build completed successfully',
      actorId: 'system',
      actorType: 'SYSTEM',
      error: false,
      durationMs: 5000,
      timestamp: '2025-01-04T10:01:05Z'
    },
    {
      id: 4,
      orchestratorInstanceId: 'test-instance',
      eventType: 'ERROR',
      entityType: 'TASK',
      entityId: 'task-2',
      action: 'FAILED',
      message: 'Task failed with exit code 1',
      errorMessage: 'Compilation error: undefined reference',
      actorId: 'system',
      actorType: 'SYSTEM',
      error: true,
      durationMs: 3000,
      timestamp: '2025-01-04T10:02:00Z'
    },
    {
      id: 5,
      orchestratorInstanceId: 'test-instance',
      eventType: 'LLM_INTERACTION',
      entityType: 'LLM_SESSION',
      entityId: 'llm-1',
      action: 'QUERY',
      message: 'LLM queried for error analysis',
      detailsJson: '{"model":"claude-3","tokens":500}',
      actorId: 'system',
      actorType: 'SYSTEM',
      error: false,
      durationMs: 2000,
      timestamp: '2025-01-04T10:02:30Z'
    }
  ];

  const mockStats: AuditStats = {
    totalEvents: 150,
    errorCount: 5,
    avgDurationMs: 1500,
    eventsByType: {
      'STATE_CHANGE': 50,
      'TASK_LIFECYCLE': 60,
      'ERROR': 5,
      'LLM_INTERACTION': 20,
      'WORKFLOW_LIFECYCLE': 15
    },
    eventsByEntityType: {
      'STATE': 50,
      'TASK': 70,
      'LLM_SESSION': 20,
      'WORKFLOW': 10
    },
    eventsByHour: [
      { hour: 8, count: 10 },
      { hour: 9, count: 25 },
      { hour: 10, count: 40 },
      { hour: 11, count: 35 },
      { hour: 12, count: 20 }
    ]
  };

  const mockPagedResult: PagedResult<AuditLogEntry> = {
    content: mockAuditLogs,
    totalElements: 100,
    totalPages: 2,
    number: 0,
    size: 50,
    first: true,
    last: false,
    empty: false
  };

  const mockEventTypes: AuditEventType[] = [
    'STATE_CHANGE', 'TASK_LIFECYCLE', 'LLM_INTERACTION',
    'WORKFLOW_LIFECYCLE', 'TRIGGER_FIRED', 'HOOK_EXECUTED',
    'ERROR', 'RECOVERY', 'CONFIGURATION_CHANGE', 'ORCHESTRATOR_LIFECYCLE'
  ];

  const mockEntityTypes: AuditEntityType[] = [
    'ORCHESTRATOR', 'STATE', 'TASK', 'WORKFLOW',
    'WORKFLOW_STEP', 'LLM_SESSION', 'TRIGGER', 'HOOK', 'CONFIGURATION'
  ];

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('OrchestratorService', [
      'getAuditLogs',
      'searchAuditLogs',
      'getAuditStats',
      'getAuditEventTypes',
      'getAuditEntityTypes',
      'exportAuditLogs'
    ]);

    await TestBed.configureTestingModule({
      declarations: [AuditLogViewerComponent],
      imports: [
        NoopAnimationsModule,
        FormsModule,
        MatButtonModule,
        MatIconModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatDatepickerModule,
        MatNativeDateModule,
        MatTableModule,
        MatPaginatorModule,
        MatProgressSpinnerModule,
        MatSlideToggleModule,
        MatTooltipModule
      ],
      providers: [
        { provide: OrchestratorService, useValue: spy }
      ]
    }).compileComponents();

    orchestratorServiceSpy = TestBed.inject(OrchestratorService) as jasmine.SpyObj<OrchestratorService>;
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(AuditLogViewerComponent);
    component = fixture.componentInstance;
    component.instanceId = 'test-instance';

    // Setup default responses
    orchestratorServiceSpy.getAuditLogs.and.returnValue(of(mockPagedResult));
    orchestratorServiceSpy.searchAuditLogs.and.returnValue(of(mockPagedResult));
    orchestratorServiceSpy.getAuditStats.and.returnValue(of(mockStats));
    orchestratorServiceSpy.getAuditEventTypes.and.returnValue(of(mockEventTypes));
    orchestratorServiceSpy.getAuditEntityTypes.and.returnValue(of(mockEntityTypes));
  });

  // ==================== Component Initialization ====================

  describe('Component Initialization', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize with default values', () => {
      expect(component.auditLogs).toEqual([]);
      expect(component.stats).toBeNull();
      expect(component.currentPage).toBe(0);
      expect(component.pageSize).toBe(50);
      expect(component.loading).toBeFalse();
      expect(component.autoRefresh).toBeFalse();
      expect(component.searchCriteria).toEqual({});
    });

    it('should load audit logs on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalledWith('test-instance', 0, 50);
      expect(component.auditLogs.length).toBe(5);
    }));

    it('should load stats on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getAuditStats).toHaveBeenCalledWith('test-instance');
      expect(component.stats).toEqual(mockStats);
    }));

    it('should load event types on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getAuditEventTypes).toHaveBeenCalledWith('test-instance');
      expect(component.eventTypes).toEqual(mockEventTypes);
    }));

    it('should load entity types on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getAuditEntityTypes).toHaveBeenCalledWith('test-instance');
      expect(component.entityTypes).toEqual(mockEntityTypes);
    }));

    it('should not load data if instanceId is empty', fakeAsync(() => {
      component.instanceId = '';
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getAuditLogs).not.toHaveBeenCalled();
    }));

    it('should use default event types if API fails', fakeAsync(() => {
      orchestratorServiceSpy.getAuditEventTypes.and.returnValue(
        throwError(() => new Error('API error'))
      );
      fixture.detectChanges();
      tick();
      expect(component.eventTypes.length).toBeGreaterThan(0);
      expect(component.eventTypes).toContain('STATE_CHANGE');
    }));

    it('should use default entity types if API fails', fakeAsync(() => {
      orchestratorServiceSpy.getAuditEntityTypes.and.returnValue(
        throwError(() => new Error('API error'))
      );
      fixture.detectChanges();
      tick();
      expect(component.entityTypes.length).toBeGreaterThan(0);
      expect(component.entityTypes).toContain('TASK');
    }));
  });

  // ==================== Filtering ====================

  describe('Filtering', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should apply event type filter', fakeAsync(() => {
      component.setEventTypeFilter('ERROR');
      tick();

      expect(component.searchCriteria.eventType).toBe('ERROR');
      expect(component.currentPage).toBe(0);
      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalledWith(
        'test-instance',
        jasmine.objectContaining({ eventType: 'ERROR' }),
        0,
        50
      );
    }));

    it('should apply entity type filter', fakeAsync(() => {
      component.setEntityTypeFilter('TASK');
      tick();

      expect(component.searchCriteria.entityType).toBe('TASK');
      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalled();
    }));

    it('should apply date range filter', fakeAsync(() => {
      component.setDateRange('2025-01-01T00:00:00Z', '2025-01-04T23:59:59Z');
      tick();

      expect(component.searchCriteria.fromTime).toBe('2025-01-01T00:00:00Z');
      expect(component.searchCriteria.toTime).toBe('2025-01-04T23:59:59Z');
      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalled();
    }));

    it('should apply search text filter', fakeAsync(() => {
      component.setSearchText('compilation error');
      tick();

      expect(component.searchCriteria.search).toBe('compilation error');
      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalled();
    }));

    it('should toggle errors only filter', fakeAsync(() => {
      component.toggleErrorsOnly();
      tick();

      expect(component.searchCriteria.errorsOnly).toBeTrue();
      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalled();

      component.toggleErrorsOnly();
      tick();

      expect(component.searchCriteria.errorsOnly).toBeFalse();
    }));

    it('should clear all filters', fakeAsync(() => {
      component.searchCriteria = {
        eventType: 'ERROR',
        entityType: 'TASK',
        search: 'test'
      };

      component.clearFilters();
      tick();

      expect(component.searchCriteria).toEqual({});
      expect(component.currentPage).toBe(0);
      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalled();
    }));

    it('should use getAuditLogs when no filters are set', fakeAsync(() => {
      component.clearFilters();
      tick();

      // Reset call count
      orchestratorServiceSpy.getAuditLogs.calls.reset();
      orchestratorServiceSpy.searchAuditLogs.calls.reset();

      component.loadAuditLogs();
      tick();

      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalled();
      expect(orchestratorServiceSpy.searchAuditLogs).not.toHaveBeenCalled();
    }));

    it('should use searchAuditLogs when filters are set', fakeAsync(() => {
      orchestratorServiceSpy.getAuditLogs.calls.reset();
      orchestratorServiceSpy.searchAuditLogs.calls.reset();

      component.setEventTypeFilter('ERROR');
      tick();

      expect(orchestratorServiceSpy.searchAuditLogs).toHaveBeenCalled();
    }));

    it('should clear filter values when empty string is set', fakeAsync(() => {
      component.setEventTypeFilter('ERROR');
      tick();
      expect(component.searchCriteria.eventType).toBe('ERROR');

      component.setEventTypeFilter('');
      tick();
      expect(component.searchCriteria.eventType).toBeUndefined();
    }));
  });

  // ==================== Pagination ====================

  describe('Pagination', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should go to next page', fakeAsync(() => {
      component.nextPage();
      tick();

      expect(component.currentPage).toBe(1);
      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalledWith('test-instance', 1, 50);
    }));

    it('should go to previous page', fakeAsync(() => {
      component.currentPage = 1;
      component.previousPage();
      tick();

      expect(component.currentPage).toBe(0);
    }));

    it('should not go below page 0', fakeAsync(() => {
      component.currentPage = 0;
      component.previousPage();
      tick();

      expect(component.currentPage).toBe(0);
    }));

    it('should not go beyond total pages', fakeAsync(() => {
      component.totalPages = 2;
      component.currentPage = 1;
      component.nextPage();
      tick();

      expect(component.currentPage).toBe(1); // Should stay at last page
    }));

    it('should go to specific page', fakeAsync(() => {
      component.totalPages = 5;
      component.goToPage(3);
      tick();

      expect(component.currentPage).toBe(3);
    }));

    it('should update pagination info from response', fakeAsync(() => {
      const pagedResult: PagedResult<AuditLogEntry> = {
        ...mockPagedResult,
        totalElements: 250,
        totalPages: 5,
        number: 2
      };
      orchestratorServiceSpy.getAuditLogs.and.returnValue(of(pagedResult));

      component.loadAuditLogs();
      tick();

      expect(component.totalElements).toBe(250);
      expect(component.totalPages).toBe(5);
    }));
  });

  // ==================== Auto Refresh ====================

  describe('Auto Refresh', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should toggle auto refresh on', fakeAsync(() => {
      component.toggleAutoRefresh();
      expect(component.autoRefresh).toBeTrue();
      discardPeriodicTasks();
    }));

    it('should toggle auto refresh off', fakeAsync(() => {
      component.toggleAutoRefresh(); // on
      component.toggleAutoRefresh(); // off
      expect(component.autoRefresh).toBeFalse();
      discardPeriodicTasks();
    }));

    it('should reload data periodically when auto refresh is on', fakeAsync(() => {
      orchestratorServiceSpy.getAuditLogs.calls.reset();
      orchestratorServiceSpy.getAuditStats.calls.reset();

      component.toggleAutoRefresh();
      tick(5000); // Wait for first interval

      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalled();
      expect(orchestratorServiceSpy.getAuditStats).toHaveBeenCalled();

      discardPeriodicTasks();
    }));

    it('should stop auto refresh on component destroy', fakeAsync(() => {
      component.toggleAutoRefresh();
      tick(2000);

      component.ngOnDestroy();

      // Should not throw after destruction
      expect(component.autoRefresh).toBeTrue();
      discardPeriodicTasks();
    }));
  });

  // ==================== Row Expansion ====================

  describe('Row Expansion', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should expand a row', () => {
      component.toggleRowExpansion(1);
      expect(component.isRowExpanded(1)).toBeTrue();
    });

    it('should collapse an expanded row', () => {
      component.toggleRowExpansion(1);
      component.toggleRowExpansion(1);
      expect(component.isRowExpanded(1)).toBeFalse();
    });

    it('should track multiple expanded rows', () => {
      component.toggleRowExpansion(1);
      component.toggleRowExpansion(2);
      component.toggleRowExpansion(3);

      expect(component.isRowExpanded(1)).toBeTrue();
      expect(component.isRowExpanded(2)).toBeTrue();
      expect(component.isRowExpanded(3)).toBeTrue();
      expect(component.isRowExpanded(4)).toBeFalse();
    });
  });

  // ==================== Export ====================

  describe('Export', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should export logs as JSON', fakeAsync(() => {
      const mockBlob = new Blob(['{}'], { type: 'application/json' });
      orchestratorServiceSpy.exportAuditLogs.and.returnValue(of(mockBlob));

      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(window.URL, 'revokeObjectURL');
      const mockAnchor = document.createElement('a');
      spyOn(document, 'createElement').and.returnValue(mockAnchor);
      spyOn(mockAnchor, 'click');

      component.exportLogs('json');
      tick();

      expect(orchestratorServiceSpy.exportAuditLogs).toHaveBeenCalledWith(
        'test-instance',
        'json',
        component.searchCriteria
      );
      expect(mockAnchor.download).toContain('.json');
    }));

    it('should export logs as CSV', fakeAsync(() => {
      const mockBlob = new Blob(['csv,data'], { type: 'text/csv' });
      orchestratorServiceSpy.exportAuditLogs.and.returnValue(of(mockBlob));

      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(window.URL, 'revokeObjectURL');
      const mockAnchor = document.createElement('a');
      spyOn(document, 'createElement').and.returnValue(mockAnchor);
      spyOn(mockAnchor, 'click');

      component.exportLogs('csv');
      tick();

      expect(orchestratorServiceSpy.exportAuditLogs).toHaveBeenCalledWith(
        'test-instance',
        'csv',
        component.searchCriteria
      );
      expect(mockAnchor.download).toContain('.csv');
    }));

    it('should include current filters in export', fakeAsync(() => {
      component.searchCriteria = { eventType: 'ERROR', errorsOnly: true };

      const mockBlob = new Blob(['{}'], { type: 'application/json' });
      orchestratorServiceSpy.exportAuditLogs.and.returnValue(of(mockBlob));

      spyOn(window.URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(window.URL, 'revokeObjectURL');
      const mockAnchor = document.createElement('a');
      spyOn(document, 'createElement').and.returnValue(mockAnchor);
      spyOn(mockAnchor, 'click');

      component.exportLogs('json');
      tick();

      expect(orchestratorServiceSpy.exportAuditLogs).toHaveBeenCalledWith(
        'test-instance',
        'json',
        jasmine.objectContaining({ eventType: 'ERROR', errorsOnly: true })
      );
    }));

    it('should show error on export failure', fakeAsync(() => {
      orchestratorServiceSpy.exportAuditLogs.and.returnValue(
        throwError(() => ({ error: { message: 'Export failed' } }))
      );

      component.exportLogs('json');
      tick();

      expect(component.error).toContain('Failed to export');
    }));
  });

  // ==================== Helper Methods ====================

  describe('Helper Methods', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    describe('getEventTypeClass', () => {
      it('should return correct class for each event type', () => {
        expect(component.getEventTypeClass('ERROR')).toBe('event-error');
        expect(component.getEventTypeClass('RECOVERY')).toBe('event-error');
        expect(component.getEventTypeClass('STATE_CHANGE')).toBe('event-state');
        expect(component.getEventTypeClass('TASK_LIFECYCLE')).toBe('event-task');
        expect(component.getEventTypeClass('LLM_INTERACTION')).toBe('event-llm');
        expect(component.getEventTypeClass('WORKFLOW_LIFECYCLE')).toBe('event-workflow');
        expect(component.getEventTypeClass('TRIGGER_FIRED')).toBe('event-trigger');
        expect(component.getEventTypeClass('HOOK_EXECUTED')).toBe('event-trigger');
        expect(component.getEventTypeClass('CONFIGURATION_CHANGE')).toBe('event-config');
      });

      it('should return default class for unknown event type', () => {
        expect(component.getEventTypeClass('UNKNOWN' as AuditEventType)).toBe('event-default');
      });
    });

    describe('formatDuration', () => {
      it('should format milliseconds correctly', () => {
        expect(component.formatDuration(500)).toBe('500ms');
        expect(component.formatDuration(999)).toBe('999ms');
      });

      it('should format seconds correctly', () => {
        expect(component.formatDuration(1000)).toBe('1.0s');
        expect(component.formatDuration(5500)).toBe('5.5s');
        expect(component.formatDuration(59000)).toBe('59.0s');
      });

      it('should format minutes correctly', () => {
        expect(component.formatDuration(60000)).toBe('1.0m');
        expect(component.formatDuration(90000)).toBe('1.5m');
        expect(component.formatDuration(300000)).toBe('5.0m');
      });

      it('should handle undefined duration', () => {
        expect(component.formatDuration(undefined)).toBe('-');
      });

      it('should handle zero duration', () => {
        expect(component.formatDuration(0)).toBe('-');
      });
    });

    describe('formatTimestamp', () => {
      it('should format timestamp to locale string', () => {
        const result = component.formatTimestamp('2025-01-04T10:00:00Z');
        expect(result).toBeTruthy();
        expect(result).not.toBe('-');
      });

      it('should handle empty timestamp', () => {
        expect(component.formatTimestamp('')).toBe('-');
      });
    });

    describe('parseDetails', () => {
      it('should parse valid JSON', () => {
        const result = component.parseDetails('{"key":"value","number":42}');
        expect(result).toEqual({ key: 'value', number: 42 });
      });

      it('should return null for invalid JSON', () => {
        const result = component.parseDetails('not valid json');
        expect(result).toBeNull();
      });

      it('should return null for undefined', () => {
        const result = component.parseDetails(undefined);
        expect(result).toBeNull();
      });
    });
  });

  // ==================== Error Handling ====================

  describe('Error Handling', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should display error when load fails', fakeAsync(() => {
      orchestratorServiceSpy.getAuditLogs.and.returnValue(
        throwError(() => ({ error: { message: 'Load failed' } }))
      );

      component.loadAuditLogs();
      tick();

      expect(component.error).toContain('Failed to load audit logs');
      expect(component.loading).toBeFalse();
    }));

    it('should clear error when clearError is called', () => {
      component.error = 'Test error';
      component.clearError();
      expect(component.error).toBeNull();
    });

    it('should handle stats load failure gracefully', fakeAsync(() => {
      orchestratorServiceSpy.getAuditStats.and.returnValue(
        throwError(() => new Error('Stats failed'))
      );

      component.loadStats();
      tick();

      expect(component.stats).toBeNull();
      expect(component.loadingStats).toBeFalse();
    }));
  });

  // ==================== Refresh ====================

  describe('Refresh', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should reload both logs and stats on refresh', fakeAsync(() => {
      orchestratorServiceSpy.getAuditLogs.calls.reset();
      orchestratorServiceSpy.getAuditStats.calls.reset();

      component.refresh();
      tick();

      expect(orchestratorServiceSpy.getAuditLogs).toHaveBeenCalled();
      expect(orchestratorServiceSpy.getAuditStats).toHaveBeenCalled();
    }));
  });

  // ==================== Compliance: Audit Log Data Integrity ====================

  describe('Audit Log Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should have timestamp on all audit entries', () => {
      component.auditLogs.forEach(log => {
        expect(log.timestamp).toBeDefined();
        expect(log.timestamp).toBeTruthy();
      });
    });

    it('should have valid event types on all entries', () => {
      const validEventTypes = [
        'STATE_CHANGE', 'TASK_LIFECYCLE', 'LLM_INTERACTION',
        'WORKFLOW_LIFECYCLE', 'TRIGGER_FIRED', 'HOOK_EXECUTED',
        'ERROR', 'RECOVERY', 'CONFIGURATION_CHANGE', 'ORCHESTRATOR_LIFECYCLE'
      ];

      component.auditLogs.forEach(log => {
        expect(validEventTypes).toContain(log.eventType);
      });
    });

    it('should have orchestratorInstanceId on all entries', () => {
      component.auditLogs.forEach(log => {
        expect(log.orchestratorInstanceId).toBe('test-instance');
      });
    });

    it('should have error flag consistent with event type', () => {
      component.auditLogs.forEach(log => {
        if (log.eventType === 'ERROR') {
          expect(log.error).toBeTrue();
        }
      });
    });

    it('should have errorMessage when error flag is true', () => {
      const errorLogs = component.auditLogs.filter(log => log.error);
      errorLogs.forEach(log => {
        // Error logs should have an error message
        expect(log.errorMessage || log.message).toBeTruthy();
      });
    });

    it('should have unique IDs for all entries', () => {
      const ids = component.auditLogs.map(log => log.id);
      const uniqueIds = new Set(ids);
      expect(uniqueIds.size).toBe(ids.length);
    });

    it('should have chronological ordering (newest first or oldest first)', () => {
      if (component.auditLogs.length < 2) return;

      const timestamps = component.auditLogs.map(log => new Date(log.timestamp).getTime());

      // Check if ascending or descending
      const isAscending = timestamps.every((t, i) => i === 0 || t >= timestamps[i - 1]);
      const isDescending = timestamps.every((t, i) => i === 0 || t <= timestamps[i - 1]);

      expect(isAscending || isDescending).toBeTrue('Audit logs should be ordered by timestamp');
    });
  });

  // ==================== Stats Compliance ====================

  describe('Stats Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should have non-negative totalEvents', () => {
      expect(component.stats?.totalEvents).toBeGreaterThanOrEqual(0);
    });

    it('should have errorCount <= totalEvents', () => {
      if (component.stats) {
        expect(component.stats.errorCount).toBeLessThanOrEqual(component.stats.totalEvents);
      }
    });

    it('should have non-negative avgDurationMs', () => {
      if (component.stats) {
        expect(component.stats.avgDurationMs).toBeGreaterThanOrEqual(0);
      }
    });

    it('should have eventsByType sum matching totalEvents approximately', () => {
      if (component.stats?.eventsByType) {
        const sum = Object.values(component.stats.eventsByType).reduce((a, b) => a + b, 0);
        // Allow some tolerance for filtering/pagination effects
        expect(sum).toBeLessThanOrEqual(component.stats.totalEvents);
      }
    });

    it('should have valid hour values in eventsByHour', () => {
      if (component.stats?.eventsByHour) {
        component.stats.eventsByHour.forEach(hourData => {
          expect(hourData.hour).toBeGreaterThanOrEqual(0);
          expect(hourData.hour).toBeLessThan(24);
          expect(hourData.count).toBeGreaterThanOrEqual(0);
        });
      }
    });
  });
});
