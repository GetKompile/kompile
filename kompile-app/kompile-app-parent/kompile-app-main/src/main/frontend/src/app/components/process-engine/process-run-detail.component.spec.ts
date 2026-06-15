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
import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { of, throwError } from 'rxjs';

import { ProcessRunDetailComponent } from './process-run-detail.component';
import {
  ProcessEngineService,
  WorkflowRun,
  StepExecution,
  ControlAttestation
} from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeStep(overrides: Partial<StepExecution> = {}): StepExecution {
  return {
    stepId: 'step-001',
    stepName: 'Extract Data',
    status: 'COMPLETED',
    startedAt: new Date(Date.now() - 4000).toISOString(),
    completedAt: new Date().toISOString(),
    executedBy: 'agent-01',
    inputs: { doc: 'report.pdf' },
    outputs: { rows: 42 },
    inputHash: 'sha256:abc123',
    outputHash: 'sha256:def456',
    graphNodeIds: ['node-aaa', 'node-bbb'],
    ...overrides
  };
}

function makeRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 'run-001',
    processDefinitionId: 'proc-001',
    processVersion: 2,
    status: 'RUNNING',
    startedAt: new Date(Date.now() - 10000).toISOString(),
    stepExecutions: [
      makeStep({ stepId: 'step-001', status: 'COMPLETED' }),
      makeStep({ stepId: 'step-002', stepName: 'Review', status: 'RUNNING', completedAt: undefined }),
      makeStep({ stepId: 'step-003', stepName: 'Approve', status: 'PENDING',
                 startedAt: undefined, completedAt: undefined, graphNodeIds: [] })
    ],
    pendingApprovals: [],
    controlResults: [],
    runData: { key: 'val' },
    graphNodeIds: ['node-run-1', 'node-run-2'],
    metrics: { duration: 5000 },
    ...overrides
  };
}

function makeAttestation(overrides: Partial<ControlAttestation> = {}): ControlAttestation {
  return {
    id: 'att-001',
    controlId: 'ctrl-001',
    workflowRunId: 'run-001',
    passed: true,
    expressionEvaluated: '#data[\'amount\'] < 10000',
    evaluatedAt: new Date().toISOString(),
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessRunDetailComponent', () => {
  let component: ProcessRunDetailComponent;
  let fixture: ComponentFixture<ProcessRunDetailComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockRun = makeRun();

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'getRun',
      'cancelRun',
      'submitApproval',
      'listControls'
    ]);
    mockService.getRun.and.returnValue(of(mockRun));
    mockService.cancelRun.and.returnValue(of({ ...mockRun, status: 'CANCELLED' }));

    await TestBed.configureTestingModule({
      imports: [ProcessRunDetailComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessRunDetailComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessRunDetailComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Loads run data when runId is set
  // ─────────────────────────────────────────────────────────────────────────

  describe('Loading run data when runId is set', () => {
    it('should call getRun when runId input changes', () => {
      component.runId = 'run-001';
      component.ngOnChanges({
        runId: new SimpleChange(null, 'run-001', true)
      });
      expect(mockService.getRun).toHaveBeenCalledWith('run-001');
    });

    it('should populate run property after successful load', () => {
      component.runId = 'run-001';
      component.ngOnChanges({
        runId: new SimpleChange(null, 'run-001', true)
      });
      expect(component.run).toBeTruthy();
      expect(component.run!.id).toBe('run-001');
    });

    it('should set loadError when getRun fails', () => {
      mockService.getRun.and.returnValue(throwError(() => new Error('not found')));
      component.runId = 'run-bad';
      component.ngOnChanges({
        runId: new SimpleChange(null, 'run-bad', true)
      });
      expect(component.loadError).toBeTruthy();
      expect(component.loadError).toContain('run-bad');
    });

    it('should clear loadError on successful load', () => {
      // First trigger an error
      mockService.getRun.and.returnValue(throwError(() => new Error('fail')));
      component.runId = 'run-bad';
      component.ngOnChanges({ runId: new SimpleChange(null, 'run-bad', true) });
      expect(component.loadError).toBeTruthy();

      // Then succeed
      mockService.getRun.and.returnValue(of(mockRun));
      component.runId = 'run-001';
      component.ngOnChanges({ runId: new SimpleChange('run-bad', 'run-001', false) });
      expect(component.loadError).toBeNull();
    });

    it('should not call getRun when runId is null', () => {
      mockService.getRun.calls.reset();
      component.runId = null;
      component.ngOnChanges({
        runId: new SimpleChange('run-001', null, false)
      });
      expect(mockService.getRun).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Step pipeline
  // ─────────────────────────────────────────────────────────────────────────

  describe('Step pipeline rendering', () => {
    beforeEach(() => {
      component.runId = 'run-001';
      component.ngOnChanges({ runId: new SimpleChange(null, 'run-001', true) });
    });

    it('should have totalSteps equal to stepExecutions length', () => {
      expect(component.totalSteps).toBe(3);
    });

    it('should count only COMPLETED (and SKIPPED) steps in completedSteps', () => {
      expect(component.completedSteps).toBe(1); // only step-001 is COMPLETED
    });

    it('should compute progressPercent between 0 and 100', () => {
      expect(component.progressPercent).toBeGreaterThan(0);
      expect(component.progressPercent).toBeLessThanOrEqual(100);
    });

    it('should return 0 progress for a run with no steps', () => {
      component.run = { ...mockRun, stepExecutions: [] };
      expect(component.progressPercent).toBe(0);
    });

    it('should return 100 progress when all steps are completed', () => {
      const allDone = makeRun({
        status: 'COMPLETED',
        stepExecutions: [
          makeStep({ stepId: 's1', status: 'COMPLETED' }),
          makeStep({ stepId: 's2', status: 'COMPLETED' })
        ]
      });
      component.run = allDone;
      expect(component.progressPercent).toBe(100);
    });

    it('stepStatusIcon() should return a non-empty string for each known status', () => {
      for (const s of ['COMPLETED', 'RUNNING', 'AWAITING_APPROVAL', 'FAILED', 'SKIPPED', 'PENDING']) {
        const icon = component.stepStatusIcon(s);
        expect(icon).toBeTruthy();
        expect(typeof icon).toBe('string');
      }
    });

    it('should expose selectedStep as null when no step is selected', () => {
      component.selectedStepIndex = -1;
      expect(component.selectedStep).toBeNull();
    });

    it('should expose the correct selectedStep when selectedStepIndex is set', () => {
      component.selectedStepIndex = 0;
      expect(component.selectedStep).toBeTruthy();
      expect(component.selectedStep!.stepId).toBe('step-001');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Graph node chips
  // ─────────────────────────────────────────────────────────────────────────

  describe('Graph node chips', () => {
    beforeEach(() => {
      component.runId = 'run-001';
      component.ngOnChanges({ runId: new SimpleChange(null, 'run-001', true) });
    });

    it('should expose graphNodeIds from the loaded run', () => {
      expect(component.run!.graphNodeIds).toEqual(['node-run-1', 'node-run-2']);
    });

    it('togglePopover() should set activePopoverNodeId when toggled once', () => {
      component.togglePopover('node-run-1');
      expect(component.activePopoverNodeId).toBe('node-run-1');
    });

    it('togglePopover() should clear activePopoverNodeId when toggled twice on the same id', () => {
      component.togglePopover('node-run-1');
      component.togglePopover('node-run-1');
      expect(component.activePopoverNodeId).toBeNull();
    });

    it('should switch active popover when a different node is toggled', () => {
      component.togglePopover('node-run-1');
      component.togglePopover('node-run-2');
      expect(component.activePopoverNodeId).toBe('node-run-2');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. Step status badges (via statusIcon helper)
  // ─────────────────────────────────────────────────────────────────────────

  describe('Step status badges via statusIcon()', () => {
    it('should return play_circle for RUNNING', () => {
      expect(component.statusIcon('RUNNING')).toBe('play_circle');
    });

    it('should return check_circle for COMPLETED', () => {
      expect(component.statusIcon('COMPLETED')).toBe('check_circle');
    });

    it('should return error for FAILED', () => {
      expect(component.statusIcon('FAILED')).toBe('error');
    });

    it('should return cancel for CANCELLED', () => {
      expect(component.statusIcon('CANCELLED')).toBe('cancel');
    });

    it('should return a fallback icon for unknown status', () => {
      const icon = component.statusIcon('UNKNOWN_STATUS');
      expect(typeof icon).toBe('string');
      expect(icon.length).toBeGreaterThan(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. Closed event
  // ─────────────────────────────────────────────────────────────────────────

  describe('Closed event emission', () => {
    it('should emit closed event when closed.emit() is called', () => {
      const spy = spyOn(component.closed, 'emit');
      component.closed.emit();
      expect(spy).toHaveBeenCalled();
    });

    it('closed EventEmitter should be defined', () => {
      expect(component.closed).toBeTruthy();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. isActive helper
  // ─────────────────────────────────────────────────────────────────────────

  describe('isActive()', () => {
    it('should return true for RUNNING status', () => {
      expect(component.isActive('RUNNING')).toBeTrue();
    });

    it('should return true for PAUSED_FOR_APPROVAL status', () => {
      expect(component.isActive('PAUSED_FOR_APPROVAL')).toBeTrue();
    });

    it('should return true for PAUSED_FOR_HUMAN status', () => {
      expect(component.isActive('PAUSED_FOR_HUMAN')).toBeTrue();
    });

    it('should return false for COMPLETED status', () => {
      expect(component.isActive('COMPLETED')).toBeFalse();
    });

    it('should return false for FAILED status', () => {
      expect(component.isActive('FAILED')).toBeFalse();
    });

    it('should return false for undefined status', () => {
      expect(component.isActive(undefined)).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 8. onOpenInGraph
  // ─────────────────────────────────────────────────────────────────────────

  describe('onOpenInGraph()', () => {
    it('should emit navigateToGraph with the given nodeId', () => {
      const spy = spyOn(component.navigateToGraph, 'emit');
      component.onOpenInGraph('node-xyz');
      expect(spy).toHaveBeenCalledWith('node-xyz');
    });

    it('should clear the active popover when opening a graph node', () => {
      component.activePopoverNodeId = 'node-xyz';
      component.onOpenInGraph('node-xyz');
      expect(component.activePopoverNodeId).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 9. Duration helpers
  // ─────────────────────────────────────────────────────────────────────────

  describe('Duration helpers', () => {
    it('getElapsed() should return a non-empty string for a valid ISO start time', () => {
      const result = component.getElapsed(new Date(Date.now() - 5000).toISOString());
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
    });

    it('getDuration() should return a non-empty string for valid start and end times', () => {
      const start = new Date(Date.now() - 3000).toISOString();
      const end = new Date().toISOString();
      const result = component.getDuration(start, end);
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
    });

    it('getDuration() should report milliseconds for sub-second durations', () => {
      const now = new Date().toISOString();
      const result = component.getDuration(now, now);
      expect(result).toMatch(/^0ms$|^\d+ms$/);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 10. hasKeys utility
  // ─────────────────────────────────────────────────────────────────────────

  describe('hasKeys()', () => {
    it('should return true for an object with keys', () => {
      expect(component.hasKeys({ a: 1 })).toBeTrue();
    });

    it('should return false for an empty object', () => {
      expect(component.hasKeys({})).toBeFalse();
    });

    it('should return false for null', () => {
      expect(component.hasKeys(null)).toBeFalsy();
    });
  });
});
