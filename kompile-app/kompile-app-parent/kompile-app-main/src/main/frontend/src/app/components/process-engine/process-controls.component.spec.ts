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

import { ProcessControlsComponent } from './process-controls.component';
import {
  ProcessEngineService,
  ControlDefinition,
  ControlAttestation,
  SpelResult
} from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeControl(overrides: Partial<ControlDefinition> = {}): ControlDefinition {
  return {
    id: 'ctrl-001',
    name: 'sox-revenue-threshold',
    description: 'Revenue must not exceed SOX threshold',
    gateType: 'HARD',
    expression: '#data[\'amount\'] < 10000',
    severity: 'CRITICAL',
    regulatoryReference: 'SOX Section 404',
    inputKeys: ['amount', 'approved'],
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

function makeSpelResult(overrides: Partial<SpelResult> = {}): SpelResult {
  return {
    result: true,
    type: 'Boolean',
    error: null,
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessControlsComponent', () => {
  let component: ProcessControlsComponent;
  let fixture: ComponentFixture<ProcessControlsComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockControls: ControlDefinition[] = [
    makeControl({ id: 'ctrl-001', name: 'sox-revenue-threshold', gateType: 'HARD' }),
    makeControl({ id: 'ctrl-002', name: 'aml-transaction-check', gateType: 'SOFT', severity: 'HIGH' }),
    makeControl({ id: 'ctrl-003', name: 'data-completeness', gateType: 'SOFT', severity: 'MEDIUM' })
  ];

  const mockNewControl = makeControl({ id: 'ctrl-new', name: 'new-control', gateType: 'SOFT', severity: 'LOW' });
  const mockAttestation = makeAttestation({ passed: true });

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'listControls',
      'createControl',
      'getControl',
      'evaluateControl',
      'evaluateSpelExpression'
    ]);
    mockService.listControls.and.returnValue(of(mockControls));
    mockService.createControl.and.returnValue(of(mockNewControl));
    mockService.getControl.and.returnValue(of(mockControls[0]));
    mockService.evaluateControl.and.returnValue(of(mockAttestation));
    mockService.evaluateSpelExpression.and.returnValue(of(makeSpelResult()));

    await TestBed.configureTestingModule({
      imports: [ProcessControlsComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessControlsComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessControlsComponent);
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
  // 2. Controls list loading
  // ─────────────────────────────────────────────────────────────────────────

  describe('Controls list loading', () => {
    it('should call listControls on init', () => {
      expect(mockService.listControls).toHaveBeenCalled();
    });

    it('should populate controls array after loading', () => {
      expect(component.controls.length).toBe(3);
    });

    it('should store control names correctly', () => {
      const names = component.controls.map(c => c.name);
      expect(names).toContain('sox-revenue-threshold');
      expect(names).toContain('aml-transaction-check');
    });

    it('should start in list viewMode', () => {
      expect(component.viewMode).toBe('list');
    });

    it('should handle empty controls list gracefully', () => {
      mockService.listControls.and.returnValue(of([]));
      component.loadControls();
      expect(component.controls.length).toBe(0);
    });

    it('should set controls to [] on error', () => {
      mockService.listControls.and.returnValue(throwError(() => new Error('server error')));
      component.loadControls();
      expect(component.controls).toEqual([]);
    });

    it('loadControls() should refresh the list when called again', () => {
      const freshControls = [makeControl({ id: 'ctrl-fresh', name: 'fresh-control' })];
      mockService.listControls.and.returnValue(of(freshControls));
      component.loadControls();
      expect(component.controls.length).toBe(1);
      expect(component.controls[0].name).toBe('fresh-control');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Create control form
  // ─────────────────────────────────────────────────────────────────────────

  describe('Create control form', () => {
    it('should switch to create viewMode when startCreate() is called', () => {
      component.startCreate();
      expect(component.viewMode).toBe('create');
    });

    it('should reset newControl when startCreate() is called', () => {
      component.newControl.name = 'old-name';
      component.startCreate();
      expect(component.newControl.name).toBe('');
    });

    it('newControl should have default gateType SOFT after startCreate()', () => {
      component.startCreate();
      expect(component.newControl.gateType).toBe('SOFT');
    });

    it('newControl should have default severity MEDIUM after startCreate()', () => {
      component.startCreate();
      expect(component.newControl.severity).toBe('MEDIUM');
    });

    it('should reset testResult when startCreate() is called', () => {
      component.testResult = makeSpelResult();
      component.startCreate();
      expect(component.testResult).toBeNull();
    });

    it('should call createControl service when createControl() is invoked with a name', () => {
      component.startCreate();
      component.newControl.name = 'my-new-control';
      component.createControl();
      expect(mockService.createControl).toHaveBeenCalledWith(
        jasmine.objectContaining({ name: 'my-new-control' })
      );
    });

    it('should append the new control to controls array after successful creation', () => {
      component.startCreate();
      component.newControl.name = 'my-new-control';
      component.createControl();
      const names = component.controls.map(c => c.name);
      expect(names).toContain('new-control'); // from mockNewControl
    });

    it('should switch back to list viewMode after successful creation', () => {
      component.startCreate();
      component.newControl.name = 'test';
      component.createControl();
      expect(component.viewMode).toBe('list');
    });

    it('should return to list viewMode when backToList() is called from create', () => {
      component.startCreate();
      expect(component.viewMode).toBe('create');
      component.backToList();
      expect(component.viewMode).toBe('list');
    });

    it('saving should be false initially', () => {
      expect(component.saving).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 4. SpEL evaluation test panel
  // ─────────────────────────────────────────────────────────────────────────

  describe('SpEL evaluation test panel', () => {
    it('should have an initial testContextJson string', () => {
      expect(component.testContextJson).toBeTruthy();
      expect(typeof component.testContextJson).toBe('string');
    });

    it('testResult should be null initially', () => {
      expect(component.testResult).toBeNull();
    });

    it('testingExpr should be false initially', () => {
      expect(component.testingExpr).toBeFalse();
    });

    it('testExpression() should call evaluateSpelExpression with the expression', () => {
      component.newControl.expression = '#data[\'amount\'] < 10000';
      component.testContextJson = '{"runData": {"amount": 5000}}';
      component.testExpression();
      expect(mockService.evaluateSpelExpression).toHaveBeenCalledWith(
        '#data[\'amount\'] < 10000',
        jasmine.any(Object)
      );
    });

    it('testExpression() should set testResult after a successful evaluation', () => {
      component.newControl.expression = '#data[\'amount\'] < 10000';
      component.testContextJson = '{"runData": {"amount": 5000}}';
      component.testExpression();
      expect(component.testResult).toBeTruthy();
      expect(component.testResult!.type).toBe('Boolean');
    });

    it('testExpression() should not call the service when expression is empty', () => {
      mockService.evaluateSpelExpression.calls.reset();
      component.newControl.expression = '';
      component.testExpression();
      expect(mockService.evaluateSpelExpression).not.toHaveBeenCalled();
    });

    it('testExpression() should set testResult.error on service error', () => {
      mockService.evaluateSpelExpression.and.returnValue(
        throwError(() => ({ error: { message: 'bad expression' }, message: 'bad expression' }))
      );
      component.newControl.expression = 'invalid!!!';
      component.testContextJson = '{"runData": {}}';
      component.testExpression();
      expect(component.testResult).toBeTruthy();
      expect(component.testResult!.error).toBeTruthy();
    });

    it('testExpression() should set testingExpr back to false after completion', () => {
      component.newControl.expression = '#data[\'x\'] > 0';
      component.testContextJson = '{"runData": {"x": 1}}';
      component.testExpression();
      expect(component.testingExpr).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 5. getSeverityIcon helper
  // ─────────────────────────────────────────────────────────────────────────

  describe('getSeverityIcon()', () => {
    it('should return "info" for LOW severity', () => {
      expect(component.getSeverityIcon('LOW')).toBe('info');
    });

    it('should return "warning" for MEDIUM severity', () => {
      expect(component.getSeverityIcon('MEDIUM')).toBe('warning');
    });

    it('should return "report_problem" for HIGH severity', () => {
      expect(component.getSeverityIcon('HIGH')).toBe('report_problem');
    });

    it('should return "error" for CRITICAL severity', () => {
      expect(component.getSeverityIcon('CRITICAL')).toBe('error');
    });

    it('should return a fallback string for unknown severity', () => {
      const icon = component.getSeverityIcon('UNKNOWN');
      expect(typeof icon).toBe('string');
      expect(icon.length).toBeGreaterThan(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 6. viewDetail and detail view
  // ─────────────────────────────────────────────────────────────────────────

  describe('viewDetail()', () => {
    it('should switch to detail viewMode', () => {
      component.viewDetail(mockControls[0]);
      expect(component.viewMode).toBe('detail');
    });

    it('should call getControl when the control has an id', () => {
      component.viewDetail(mockControls[0]);
      expect(mockService.getControl).toHaveBeenCalledWith('ctrl-001');
    });

    it('should set selectedControl to the fetched control', () => {
      component.viewDetail(mockControls[0]);
      expect(component.selectedControl).toBeTruthy();
      expect(component.selectedControl!.id).toBe('ctrl-001');
    });

    it('should fall back to the passed control when getControl fails', () => {
      mockService.getControl.and.returnValue(throwError(() => new Error('not found')));
      component.viewDetail(mockControls[0]);
      expect(component.selectedControl).toBeTruthy();
      expect(component.selectedControl!.name).toBe('sox-revenue-threshold');
    });

    it('should set selectedControl directly when control has no id', () => {
      const noIdControl: ControlDefinition = { name: 'temp-control' };
      component.viewDetail(noIdControl);
      expect(component.selectedControl!.name).toBe('temp-control');
    });

    it('should reset evalResult when entering detail view', () => {
      component.evalResult = mockAttestation;
      component.viewDetail(mockControls[0]);
      expect(component.evalResult).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // 7. Control evaluation
  // ─────────────────────────────────────────────────────────────────────────

  describe('Control evaluation', () => {
    beforeEach(() => {
      component.viewDetail(mockControls[0]);
    });

    it('evalRunId should be empty string initially', () => {
      expect(component.evalRunId).toBe('');
    });

    it('evalData should be empty string initially', () => {
      expect(component.evalData).toBe('');
    });

    it('evaluateControl() should call service with correct args', () => {
      component.evalRunId = 'run-001';
      component.evalData = '{"amount": 5000}';
      component.evaluateControl();
      expect(mockService.evaluateControl).toHaveBeenCalledWith(
        'ctrl-001',
        'run-001',
        jasmine.any(Object)
      );
    });

    it('should set evalResult after successful evaluation', () => {
      component.evalRunId = 'run-001';
      component.evalData = '{"amount": 5000}';
      component.evaluateControl();
      expect(component.evalResult).toBeTruthy();
      expect(component.evalResult!.passed).toBeTrue();
    });

    it('evaluating should be false after evaluation completes', () => {
      component.evalRunId = 'run-001';
      component.evalData = '{"amount": 5000}';
      component.evaluateControl();
      expect(component.evaluating).toBeFalse();
    });
  });
});
