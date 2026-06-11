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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { of } from 'rxjs';

import { ProcessRunsComponent } from './process-runs.component';
import {
  ProcessEngineService,
  WorkflowRun,
  StepExecution
} from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeStepExecution(overrides: Partial<StepExecution> = {}): StepExecution {
  return {
    stepId: 'step-001',
    stepName: 'Extract Invoices',
    status: 'COMPLETED',
    startedAt: new Date(Date.now() - 5000).toISOString(),
    completedAt: new Date().toISOString(),
    executedBy: 'agent-extractor',
    inputs: { raw_document: 'invoice.pdf' },
    outputs: { invoice_data: { amount: 1500, vendor: 'Acme Corp' } },
    inputHash: 'sha256:abc123',
    outputHash: 'sha256:def456',
    ...overrides
  };
}

function makeWorkflowRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 'run-001',
    processDefinitionId: 'proc-001',
    processVersion: 1,
    status: 'RUNNING',
    startedAt: new Date(Date.now() - 10000).toISOString(),
    stepExecutions: [
      makeStepExecution({ stepId: 'step-001', status: 'COMPLETED' }),
      makeStepExecution({ stepId: 'step-002', stepName: 'Review Invoices', status: 'RUNNING',
        completedAt: undefined }),
      makeStepExecution({ stepId: 'step-003', stepName: 'Manual Review', status: 'PENDING',
        startedAt: undefined, completedAt: undefined })
    ],
    pendingApprovals: [],
    controlResults: [],
    runData: { initialPayload: 'invoice.pdf' },
    metrics: { totalDurationMs: 10000, stepCount: 3 },
    ...overrides
  };
}

function makeCompletedRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return makeWorkflowRun({
    id: 'run-002',
    status: 'COMPLETED',
    completedAt: new Date().toISOString(),
    stepExecutions: [
      makeStepExecution({ stepId: 'step-001', status: 'COMPLETED' }),
      makeStepExecution({ stepId: 'step-002', status: 'COMPLETED' })
    ],
    ...overrides
  });
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessRunsComponent', () => {
  let component: ProcessRunsComponent;
  let fixture: ComponentFixture<ProcessRunsComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockRun = makeWorkflowRun();
  const mockCompletedRun = makeCompletedRun();
  const mockActiveRuns: WorkflowRun[] = [
    mockRun,
    makeWorkflowRun({ id: 'run-003', processDefinitionId: 'proc-002' })
  ];
  const mockNewRun = makeWorkflowRun({ id: 'run-new', processDefinitionId: 'proc-001', status: 'RUNNING' });

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'listActiveRuns',
      'getRun',
      'startRun',
      'resumeAfterApproval',
      'getPendingApprovals'
    ]);
    mockService.listActiveRuns.and.returnValue(of(mockActiveRuns));
    mockService.getRun.and.returnValue(of(mockRun));
    mockService.startRun.and.returnValue(of(mockNewRun));

    await TestBed.configureTestingModule({
      imports: [ProcessRunsComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessRunsComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessRunsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. Load active runs on init
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Loading active runs on init', () => {
    it('should load active runs on init', () => {
      expect(mockService.listActiveRuns).toHaveBeenCalled();
    });

    it('should populate activeRuns after loading', () => {
      expect(component.runs.length).toBe(2);
    });

    it('should store each run id correctly', () => {
      const ids = component.runs.map((r: WorkflowRun) => r.id);
      expect(ids).toContain('run-001');
      expect(ids).toContain('run-003');
    });

    it('should set loading to false after load completes', () => {
      expect(component.loading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. Status and progress helpers
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Status and progress helpers', () => {
    it('getStatusIcon() should return a non-empty string for RUNNING', () => {
      const icon = component.getStatusIcon('RUNNING');
      expect(icon).toBeTruthy();
      expect(typeof icon).toBe('string');
    });

    it('getStatusIcon() should return a non-empty string for COMPLETED', () => {
      expect(component.getStatusIcon('COMPLETED')).toBeTruthy();
    });

    it('getStatusIcon() should return a non-empty string for FAILED', () => {
      expect(component.getStatusIcon('FAILED')).toBeTruthy();
    });

    it('getStatusIcon() should return a non-empty string for PENDING_APPROVAL', () => {
      expect(component.getStatusIcon('PENDING_APPROVAL')).toBeTruthy();
    });

    it('getStatusIcon() should return a non-empty string for PAUSED', () => {
      expect(component.getStatusIcon('PAUSED')).toBeTruthy();
    });

    it('getStatusIcon() should return a fallback for unknown status', () => {
      expect(component.getStatusIcon('UNKNOWN')).toBeTruthy();
    });

    it('getStepStatusIcon() should return a string for COMPLETED', () => {
      expect(component.getStepStatusIcon('COMPLETED')).toBeTruthy();
    });

    it('getStepStatusIcon() should return a string for FAILED', () => {
      expect(component.getStepStatusIcon('FAILED')).toBeTruthy();
    });

    it('getStepStatusIcon() should return a string for RUNNING', () => {
      expect(component.getStepStatusIcon('RUNNING')).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. Step counts and progress
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Step counts and progress', () => {
    it('countCompleted() should count only COMPLETED steps', () => {
      const count = component.countCompleted(mockRun);
      expect(count).toBe(1); // only step-001 is COMPLETED
    });

    it('countTotal() should return total step executions count', () => {
      const total = component.countTotal(mockRun);
      expect(total).toBe(3);
    });

    it('getProgressPercent() should return a value between 0 and 100', () => {
      const progress = component.getProgressPercent(mockRun);
      expect(progress).toBeGreaterThanOrEqual(0);
      expect(progress).toBeLessThanOrEqual(100);
    });

    it('getProgressPercent() should return 100 for a fully completed run', () => {
      const progress = component.getProgressPercent(mockCompletedRun);
      expect(progress).toBe(100);
    });

    it('getProgressPercent() should return non-zero when some steps are complete', () => {
      const progress = component.getProgressPercent(mockRun);
      expect(progress).toBeGreaterThan(0);
    });

    it('getProgressPercent() should return 0 for a run with no step executions', () => {
      const emptyRun = makeWorkflowRun({ stepExecutions: [], status: 'RUNNING' });
      const progress = component.getProgressPercent(emptyRun);
      expect(progress).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. Start run form
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Start run form', () => {
    it('should start with showStartForm = false', () => {
      expect(component.showStartForm).toBeFalse();
    });

    it('should start with starting = false', () => {
      expect(component.starting).toBeFalse();
    });

    it('should call startRun when startRun() is invoked with a process id', () => {
      component.startProcessId = 'proc-001';
      component.startRun();
      expect(mockService.startRun).toHaveBeenCalledWith('proc-001', jasmine.any(Object));
    });

    it('should add the new run to activeRuns after startRun() succeeds', () => {
      component.startProcessId = 'proc-001';
      component.startRun();
      // The new run is appended
      const ids = component.runs.map((r: WorkflowRun) => r.id);
      expect(ids).toContain('run-new');
    });

    it('should hide start form and reset fields after successful startRun()', () => {
      component.startProcessId = 'proc-001';
      component.startRun();
      expect(component.showStartForm).toBeFalse();
      expect(component.startProcessId).toBe('');
      expect(component.startInitialData).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. Toggle expand
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleExpand()', () => {
    it('should add the run id to expandedRuns when toggled once', () => {
      component.toggleExpand(mockRun);
      expect(component.expandedRuns.has('run-001')).toBeTrue();
    });

    it('should remove the run id from expandedRuns when toggled twice', () => {
      component.toggleExpand(mockRun);
      component.toggleExpand(mockRun);
      expect(component.expandedRuns.has('run-001')).toBeFalse();
    });

    it('should fetch run detail from service when expanding for the first time', () => {
      mockService.getRun.calls.reset();
      component.toggleExpand(mockRun);
      expect(mockService.getRun).toHaveBeenCalledWith('run-001');
    });

    it('should NOT fetch run detail when collapsing', () => {
      component.toggleExpand(mockRun); // expand
      mockService.getRun.calls.reset();
      component.toggleExpand(mockRun); // collapse
      expect(mockService.getRun).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. loadRuns
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadRuns()', () => {
    it('should call listActiveRuns when loadRuns() is invoked', () => {
      mockService.listActiveRuns.calls.reset();
      component.loadRuns();
      expect(mockService.listActiveRuns).toHaveBeenCalled();
    });

    it('should update activeRuns after reload', () => {
      const newRuns = [makeWorkflowRun({ id: 'run-fresh' })];
      mockService.listActiveRuns.and.returnValue(of(newRuns));
      component.loadRuns();
      expect(component.runs.length).toBe(1);
      expect(component.runs[0].id).toBe('run-fresh');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. formatDate helper
  // ─────────────────────────────────────────────────────────────────────────────

  describe('formatDate()', () => {
    it('should return a formatted date string for a valid ISO string', () => {
      const result = component.formatDate(new Date().toISOString());
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
    });

    it('should return the original string for an unparseable date', () => {
      const result = component.formatDate('not-a-date');
      expect(typeof result).toBe('string');
    });
  });
});
