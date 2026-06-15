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

import { ProcessEngineDashboardComponent } from './process-engine-dashboard.component';
import { ProcessEngineService, WorkflowRun, ApprovalRequest } from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeWorkflowRun(overrides: Partial<WorkflowRun> = {}): WorkflowRun {
  return {
    id: 'run-001',
    processDefinitionId: 'proc-001',
    processVersion: 1,
    status: 'RUNNING',
    startedAt: new Date().toISOString(),
    stepExecutions: [],
    ...overrides
  };
}

function makeApprovalRequest(overrides: Partial<ApprovalRequest> = {}): ApprovalRequest {
  return {
    id: 'approval-001',
    workflowRunId: 'run-001',
    stepId: 'step-001',
    stepName: 'Review Step',
    status: 'PENDING',
    createdAt: new Date().toISOString(),
    slaDeadline: new Date(Date.now() + 86400000).toISOString(),
    assignedTo: 'reviewer@example.com',
    items: [],
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessEngineDashboardComponent', () => {
  let component: ProcessEngineDashboardComponent;
  let fixture: ComponentFixture<ProcessEngineDashboardComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockActiveRuns: WorkflowRun[] = [
    makeWorkflowRun({ id: 'run-001', status: 'RUNNING' }),
    makeWorkflowRun({ id: 'run-002', status: 'RUNNING' })
  ];

  const mockPendingApprovals: ApprovalRequest[] = [
    makeApprovalRequest({ id: 'approval-001' }),
    makeApprovalRequest({ id: 'approval-002' }),
    makeApprovalRequest({ id: 'approval-003' })
  ];

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'listActiveRuns',
      'listAllRuns',
      'getPendingApprovals',
      'listOntologies',
      'createOntology',
      'getOntology',
      'validateData',
      'listProcessDefinitions',
      'createProcess',
      'getProcess',
      'approveProcess',
      'startRun',
      'getRun',
      'cancelRun',
      'completeHumanStep',
      'submitApproval',
      'listControls',
      'createControl',
      'getControl',
      'evaluateControl',
      'evaluateSpelExpression'
    ]);
    mockService.listActiveRuns.and.returnValue(of(mockActiveRuns));
    mockService.listAllRuns.and.returnValue(of(mockActiveRuns));
    mockService.getPendingApprovals.and.returnValue(of(mockPendingApprovals));
    mockService.listOntologies.and.returnValue(of([]));
    mockService.listProcessDefinitions.and.returnValue(of([]));
    mockService.listControls.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ProcessEngineDashboardComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessEngineDashboardComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessEngineDashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Component creation
  // ─────────────────────────────────────────────────────────────────────────────

  it('should create the component', () => {
    expect(component).toBeTruthy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. Initialization — active runs
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Active runs on init', () => {
    it('should load active runs count on init', () => {
      expect(mockService.listActiveRuns).toHaveBeenCalled();
    });

    it('should store activeRunsCount after loading', () => {
      expect(component.activeRunsCount).toBe(2);
    });

    it('should display run count in rendered output', () => {
      fixture.detectChanges();
      const compiled = fixture.nativeElement as HTMLElement;
      const badgeText = compiled.textContent ?? '';
      expect(badgeText).toContain('2');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. Initialization — pending approvals
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Pending approvals on init', () => {
    it('should load pending approvals count on init', () => {
      expect(mockService.getPendingApprovals).toHaveBeenCalled();
    });

    it('should store pendingApprovalsCount after loading', () => {
      expect(component.pendingApprovalsCount).toBe(3);
    });

    it('should display approval count in rendered output', () => {
      fixture.detectChanges();
      const compiled = fixture.nativeElement as HTMLElement;
      const badgeText = compiled.textContent ?? '';
      expect(badgeText).toContain('3');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. Zero state
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Zero state', () => {
    it('should show 0 active runs when there are none', async () => {
      mockService.listActiveRuns.and.returnValue(of([]));
      mockService.getPendingApprovals.and.returnValue(of([]));

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [ProcessEngineDashboardComponent, HttpClientTestingModule, NoopAnimationsModule],
        schemas: [NO_ERRORS_SCHEMA]
      })
      .overrideComponent(ProcessEngineDashboardComponent, {
        set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
      })
      .compileComponents();

      fixture = TestBed.createComponent(ProcessEngineDashboardComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.activeRunsCount).toBe(0);
    });

    it('should show 0 pending approvals when there are none', async () => {
      mockService.listActiveRuns.and.returnValue(of([]));
      mockService.getPendingApprovals.and.returnValue(of([]));

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [ProcessEngineDashboardComponent, HttpClientTestingModule, NoopAnimationsModule],
        schemas: [NO_ERRORS_SCHEMA]
      })
      .overrideComponent(ProcessEngineDashboardComponent, {
        set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
      })
      .compileComponents();

      fixture = TestBed.createComponent(ProcessEngineDashboardComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.pendingApprovalsCount).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. Error recovery
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Error recovery', () => {
    it('should keep activeRunsCount = 0 when listActiveRuns errors', async () => {
      const { throwError } = await import('rxjs');
      mockService.listActiveRuns.and.returnValue(throwError(() => new Error('Network error')));
      mockService.getPendingApprovals.and.returnValue(of([]));

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [ProcessEngineDashboardComponent, HttpClientTestingModule, NoopAnimationsModule],
        schemas: [NO_ERRORS_SCHEMA]
      })
      .overrideComponent(ProcessEngineDashboardComponent, {
        set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
      })
      .compileComponents();

      fixture = TestBed.createComponent(ProcessEngineDashboardComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.activeRunsCount).toBe(0);
    });

    it('should keep pendingApprovalsCount = 0 when getPendingApprovals errors', async () => {
      const { throwError } = await import('rxjs');
      mockService.listActiveRuns.and.returnValue(of([]));
      mockService.getPendingApprovals.and.returnValue(throwError(() => new Error('Network error')));

      TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [ProcessEngineDashboardComponent, HttpClientTestingModule, NoopAnimationsModule],
        schemas: [NO_ERRORS_SCHEMA]
      })
      .overrideComponent(ProcessEngineDashboardComponent, {
        set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
      })
      .compileComponents();

      fixture = TestBed.createComponent(ProcessEngineDashboardComponent);
      component = fixture.componentInstance;
      fixture.detectChanges();

      expect(component.pendingApprovalsCount).toBe(0);
    });
  });
});
