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
import { of, throwError } from 'rxjs';

import { ProcessApprovalsComponent } from './process-approvals.component';
import {
  ProcessEngineService,
  ApprovalRequest,
  WorkflowRun
} from '../../services/process-engine.service';

describe('ProcessApprovalsComponent', () => {
  let component: ProcessApprovalsComponent;
  let fixture: ComponentFixture<ProcessApprovalsComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockApprovals: ApprovalRequest[] = [
    {
      id: 'req-001',
      workflowRunId: 'wf-2026-05-001',
      stepId: '3.3',
      stepName: 'Manager Variance Review',
      status: 'PENDING',
      createdAt: '2026-05-17T09:00:00Z',
      slaDeadline: '2026-05-18T09:00:00Z',
      assignedTo: 'M.Chen',
      items: [{ id: 'item-1', description: 'Channel mismatch variance $4,217' }],
      context: { varianceAmount: 4217, region: 'AMER', pattern: 'channel_mismatch' }
    },
    {
      id: 'req-002',
      workflowRunId: 'wf-2026-05-002',
      stepId: '1.4',
      stepName: 'Version Assertion Gate',
      status: 'PENDING',
      createdAt: '2026-05-17T10:00:00Z',
      assignedTo: 'J.Park',
      items: [],
      context: { filesReceived: 3 }
    }
  ];

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'getPendingApprovals', 'submitApproval'
    ]);
    mockService.getPendingApprovals.and.returnValue(of(mockApprovals));
    mockService.submitApproval.and.returnValue(of({} as WorkflowRun));

    await TestBed.configureTestingModule({
      imports: [
        ProcessApprovalsComponent,
        HttpClientTestingModule,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessApprovalsComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessApprovalsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load pending approvals on init', () => {
    expect(mockService.getPendingApprovals).toHaveBeenCalled();
    expect(component.approvalStates.length).toBe(2);
  });

  it('should populate approval state from requests', () => {
    expect(component.approvalStates[0].request.id).toBe('req-001');
    expect(component.approvalStates[0].request.stepName).toBe('Manager Variance Review');
    expect(component.approvalStates[0].request.assignedTo).toBe('M.Chen');
    expect(component.approvalStates[0].expanded).toBeFalse();
    expect(component.approvalStates[0].comment).toBe('');
    expect(component.approvalStates[0].submitting).toBeFalse();
  });

  it('should display SLA deadline and assignee from approval request', () => {
    const state = component.approvalStates[0];
    expect(state.request.slaDeadline).toBe('2026-05-18T09:00:00Z');
    expect(state.request.assignedTo).toBe('M.Chen');
  });

  it('should submit APPROVE action and remove from list', () => {
    const state = component.approvalStates[0];
    state.expanded = true;

    component.submitAction(state, 'APPROVE');

    expect(mockService.submitApproval).toHaveBeenCalledWith(
      'req-001',
      jasmine.objectContaining({
        requestId: 'req-001',
        action: 'APPROVE',
        respondedBy: 'ui-user'
      })
    );
    expect(component.approvalStates.length).toBe(1);
    expect(component.approvalStates[0].request.id).toBe('req-002');
  });

  it('should submit REJECT action', () => {
    const state = component.approvalStates[0];
    state.comment = 'Incorrect classification';

    component.submitAction(state, 'REJECT');

    expect(mockService.submitApproval).toHaveBeenCalledWith(
      'req-001',
      jasmine.objectContaining({
        requestId: 'req-001',
        action: 'REJECT',
        comment: 'Incorrect classification'
      })
    );
  });

  it('should submit ESCALATE action', () => {
    const state = component.approvalStates[1];
    component.submitAction(state, 'ESCALATE');

    expect(mockService.submitApproval).toHaveBeenCalledWith(
      'req-002',
      jasmine.objectContaining({
        action: 'ESCALATE'
      })
    );
  });

  it('should submit DELEGATE action with delegateTo', () => {
    const state = component.approvalStates[0];
    state.delegateTo = 'S.Reyes';

    component.submitAction(state, 'DELEGATE');

    expect(mockService.submitApproval).toHaveBeenCalledWith(
      'req-001',
      jasmine.objectContaining({
        action: 'DELEGATE',
        delegateTo: 'S.Reyes'
      })
    );
  });

  it('should not submit DELEGATE without delegateTo', () => {
    const state = component.approvalStates[0];
    state.delegateTo = '';

    component.submitAction(state, 'DELEGATE');

    expect(mockService.submitApproval).not.toHaveBeenCalled();
  });

  it('should include comment in approval response', () => {
    const state = component.approvalStates[0];
    state.comment = 'Reviewed and confirmed';

    component.submitAction(state, 'APPROVE');

    expect(mockService.submitApproval).toHaveBeenCalledWith(
      'req-001',
      jasmine.objectContaining({ comment: 'Reviewed and confirmed' })
    );
  });

  it('should refresh approvals list on loadApprovals', () => {
    mockService.getPendingApprovals.calls.reset();
    component.loadApprovals();
    expect(mockService.getPendingApprovals).toHaveBeenCalledTimes(1);
  });

  it('should filter by assignee when filterAssignee is set', () => {
    component.filterAssignee = 'M.Chen';
    component.loadApprovals();
    expect(mockService.getPendingApprovals).toHaveBeenCalledWith('M.Chen');
  });

  it('should pass undefined when filterAssignee is empty', () => {
    component.filterAssignee = '';
    component.loadApprovals();
    expect(mockService.getPendingApprovals).toHaveBeenCalledWith(undefined);
  });

  it('should show empty state when no approvals', () => {
    mockService.getPendingApprovals.and.returnValue(of([]));
    component.loadApprovals();
    expect(component.approvalStates.length).toBe(0);
  });

  it('should handle service error gracefully', () => {
    mockService.getPendingApprovals.and.returnValue(throwError(() => new Error('Network error')));
    component.loadApprovals();
    expect(component.loading).toBeFalse();
    // On error, existing approval states are preserved (stale data preferred over empty)
    expect(component.approvalStates.length).toBe(2);
  });

  it('should detect SLA urgency correctly', () => {
    // SLA within 2 hours from now = urgent
    const urgentDeadline = new Date(Date.now() + 60 * 60 * 1000).toISOString(); // 1h from now
    expect(component.isSlaUrgent(urgentDeadline)).toBeTrue();

    // SLA 24 hours from now = not urgent
    const safeDeadline = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    expect(component.isSlaUrgent(safeDeadline)).toBeFalse();
  });

  it('should format context entries', () => {
    const entries = component.getContextEntries({ amount: 4217, region: 'AMER' });
    expect(entries.length).toBe(2);
    expect(entries[0].key).toBe('amount');
    expect(entries[0].value).toBe(4217);
    expect(entries[1].key).toBe('region');
    expect(entries[1].value).toBe('AMER');
  });

  it('should format values correctly', () => {
    expect(component.formatValue(null)).toBe('—');
    expect(component.formatValue(undefined)).toBe('—');
    expect(component.formatValue(42)).toBe('42');
    expect(component.formatValue('text')).toBe('text');
    expect(component.formatValue({ a: 1 })).toBe('{"a":1}');
  });

  it('should check hasKeys correctly', () => {
    expect(component.hasKeys(undefined)).toBeFalse();
    expect(component.hasKeys({})).toBeFalse();
    expect(component.hasKeys({ a: 1 })).toBeTrue();
  });

  it('should toggle delegate field', () => {
    const state = component.approvalStates[0];
    expect(state.delegateTo).toBe('');

    // Toggle should switch between defined/undefined states
    component.toggleDelegate(state);
    // delegateTo becomes undefined (hidden)
    expect(state.delegateTo).toBeUndefined();

    component.toggleDelegate(state);
    // delegateTo becomes '' (shown)
    expect(state.delegateTo).toBe('');
  });
});
