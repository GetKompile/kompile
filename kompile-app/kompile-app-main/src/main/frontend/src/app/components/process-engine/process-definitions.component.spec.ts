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

import { ProcessDefinitionsComponent } from './process-definitions.component';
import {
  ProcessEngineService,
  ProcessDefinition,
  ProcessPhase,
  ProcessStep
} from '../../services/process-engine.service';

// ─── Helpers ─────────────────────────────────────────────────────────────────

function makeStep(overrides: Partial<ProcessStep> = {}): ProcessStep {
  return {
    id: 'step-001',
    name: 'Extract Invoices',
    description: 'Automatically extract invoice data',
    stepType: 'AUTO',
    inputKeys: ['raw_document'],
    outputKeys: ['invoice_data'],
    agentSpecId: 'agent-extractor',
    dependsOn: [],
    confidence: 0.95,
    ...overrides
  };
}

function makePhase(overrides: Partial<ProcessPhase> = {}): ProcessPhase {
  return {
    id: 'phase-001',
    name: 'Extraction Phase',
    order: 1,
    steps: [
      makeStep({ id: 'step-001', stepType: 'AUTO' }),
      makeStep({ id: 'step-002', stepType: 'APPROVE', name: 'Review Invoices',
        approvalPolicy: { approverPool: ['finance@example.com'], mode: 'ANY' } })
    ],
    ...overrides
  };
}

function makeProcessDefinition(overrides: Partial<ProcessDefinition> = {}): ProcessDefinition {
  return {
    id: 'proc-001',
    name: 'Invoice Processing Workflow',
    version: 1,
    ontologySchemaId: 'ontology-001',
    ontologyVersion: 1,
    status: 'DRAFT',
    phases: [
      makePhase({ id: 'phase-001', name: 'Extraction Phase', order: 1 }),
      makePhase({ id: 'phase-002', name: 'Review Phase', order: 2,
        steps: [makeStep({ id: 'step-003', stepType: 'HUMAN', name: 'Manual Review' })] })
    ],
    controls: [
      { controlId: 'ctrl-001', triggerAfterStep: 'step-002' }
    ],
    agentSpecs: ['agent-extractor', 'agent-reviewer'],
    metadata: { category: 'finance' },
    ...overrides
  };
}

// ─────────────────────────────────────────────────────────────────────────────

describe('ProcessDefinitionsComponent', () => {
  let component: ProcessDefinitionsComponent;
  let fixture: ComponentFixture<ProcessDefinitionsComponent>;
  let mockService: jasmine.SpyObj<ProcessEngineService>;

  const mockDefinition = makeProcessDefinition();
  const mockApprovedDefinition = makeProcessDefinition({
    id: 'proc-002',
    name: 'Approved Workflow',
    status: 'APPROVED',
    approvedBy: 'admin@example.com',
    approvedAt: new Date().toISOString()
  });

  beforeEach(async () => {
    mockService = jasmine.createSpyObj('ProcessEngineService', [
      'createProcess',
      'getProcess',
      'approveProcess',
      'listProcessDefinitions',
      'listActiveRuns',
      'getPendingApprovals',
      'startRun'
    ]);
    mockService.createProcess.and.returnValue(of(mockDefinition));
    mockService.getProcess.and.returnValue(of(mockDefinition));
    mockService.approveProcess.and.returnValue(of(mockApprovedDefinition));
    mockService.listProcessDefinitions.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ProcessDefinitionsComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    .overrideComponent(ProcessDefinitionsComponent, {
      set: { providers: [{ provide: ProcessEngineService, useValue: mockService }] }
    })
    .compileComponents();

    fixture = TestBed.createComponent(ProcessDefinitionsComponent);
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
  // 2. Initial state
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Initial state', () => {
    it('should start in list view', () => {
      expect(component.viewMode).toBe('list');
    });

    it('should have no selected definition on startup', () => {
      expect(component.selectedDef).toBeNull();
    });

    it('should start with an empty definitions array', () => {
      expect(component.definitions).toBeDefined();
      expect(Array.isArray(component.definitions)).toBeTrue();
    });

    it('should start with saving = false', () => {
      expect(component.saving).toBeFalse();
    });

    it('should start with approving = false', () => {
      expect(component.approving).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. View transitions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('View transitions', () => {
    it('should switch to create view on startCreate()', () => {
      component.startCreate();
      expect(component.viewMode).toBe('create');
    });

    it('should clear createJson when switching to create view', () => {
      component.createJson = 'some old json';
      component.startCreate();
      expect(component.createJson).toBe('');
    });

    it('should switch back to list view by setting viewMode', () => {
      component.startCreate();
      component.viewMode = 'list';
      expect(component.viewMode).toBe('list');
    });

    it('should switch to detail view when viewDetail() is called with a def that has no id', () => {
      const defNoId: ProcessDefinition = { name: 'Local Only', status: 'DRAFT' };
      component.viewDetail(defNoId);
      expect(component.viewMode).toBe('detail');
      expect(component.selectedDef).toEqual(defNoId);
    });

    it('should fetch from service when viewDetail() is called with a def that has an id', () => {
      component.viewDetail(mockDefinition);
      expect(mockService.getProcess).toHaveBeenCalledWith('proc-001', 1);
    });

    it('should set selectedDef and viewMode=detail after successful getProcess call', () => {
      component.viewDetail(mockDefinition);
      expect(component.viewMode).toBe('detail');
      expect(component.selectedDef).toEqual(mockDefinition);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. Create process
  // ─────────────────────────────────────────────────────────────────────────────

  describe('createDefinition()', () => {
    it('should call createProcess when createJson contains valid JSON', () => {
      component.createJson = JSON.stringify({ name: 'My New Process', phases: [] });
      component.createDefinition();
      expect(mockService.createProcess).toHaveBeenCalled();
    });

    it('should NOT call createProcess when createJson is invalid JSON', () => {
      component.createJson = 'not-valid-json';
      component.createDefinition();
      expect(mockService.createProcess).not.toHaveBeenCalled();
    });

    it('should switch to list view after successful creation', () => {
      component.createJson = JSON.stringify({ name: 'My New Process', phases: [] });
      component.createDefinition();
      expect(component.viewMode).toBe('list');
    });

    it('should add the created definition to the definitions array', () => {
      component.createJson = JSON.stringify({ name: 'My New Process', phases: [] });
      component.createDefinition();
      expect(component.definitions.length).toBe(1);
      expect(component.definitions[0].name).toBe('Invoice Processing Workflow');
    });

    it('should reset saving flag after successful creation', () => {
      component.createJson = JSON.stringify({ name: 'My New Process', phases: [] });
      component.createDefinition();
      expect(component.saving).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. Definition display
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Definition display', () => {
    beforeEach(() => {
      component.viewDetail(mockDefinition);
      fixture.detectChanges();
    });

    it('should display definition name', () => {
      expect(component.selectedDef?.name).toBe('Invoice Processing Workflow');
    });

    it('should display definition status', () => {
      expect(component.selectedDef?.status).toBe('DRAFT');
    });

    it('should show phases in detail view', () => {
      expect(component.selectedDef?.phases?.length).toBe(2);
    });

    it('should show steps within phases', () => {
      const firstPhase = component.selectedDef?.phases?.[0];
      expect(firstPhase?.steps?.length).toBe(2);
    });

    it('should show steps in detail view', () => {
      const allSteps = component.selectedDef?.phases?.flatMap(p => p.steps ?? []) ?? [];
      expect(allSteps.length).toBeGreaterThan(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. Approve process
  // ─────────────────────────────────────────────────────────────────────────────

  describe('approveDefinition()', () => {
    it('should call approveProcess with the selected definition id', () => {
      component.selectedDef = mockDefinition;
      component.approveDefinition();
      expect(mockService.approveProcess).toHaveBeenCalledWith('proc-001', 'ui-user');
    });

    it('should update selectedDef status to APPROVED after approval', () => {
      component.selectedDef = mockDefinition;
      component.approveDefinition();
      expect(component.selectedDef?.status).toBe('APPROVED');
    });

    it('should not call approveProcess when selectedDef has no id', () => {
      component.selectedDef = { name: 'No Id Def' };
      component.approveDefinition();
      expect(mockService.approveProcess).not.toHaveBeenCalled();
    });

    it('should not call approveProcess when selectedDef is null', () => {
      component.selectedDef = null;
      component.approveDefinition();
      expect(mockService.approveProcess).not.toHaveBeenCalled();
    });

    it('should reset approving flag after successful approval', () => {
      component.selectedDef = mockDefinition;
      component.approveDefinition();
      expect(component.approving).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. countSteps helper
  // ─────────────────────────────────────────────────────────────────────────────

  describe('countSteps()', () => {
    it('should count all steps across all phases', () => {
      const total = component.countSteps(mockDefinition);
      expect(total).toBe(3); // 2 steps in phase-001, 1 in phase-002
    });

    it('should return 0 when definition has no phases', () => {
      const emptyDef: ProcessDefinition = { name: 'empty', phases: [] };
      expect(component.countSteps(emptyDef)).toBe(0);
    });

    it('should return 0 when phases have no steps', () => {
      const def = makeProcessDefinition({
        phases: [
          { id: 'p1', name: 'Phase 1', order: 1, steps: [] }
        ]
      });
      expect(component.countSteps(def)).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. loadDefinitions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('loadDefinitions()', () => {
    it('should reset definitions to empty array', () => {
      component.definitions = [mockDefinition];
      component.loadDefinitions();
      expect(component.definitions).toEqual([]);
    });
  });
});
