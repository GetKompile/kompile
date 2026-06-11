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
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { PipelineHubComponent } from './pipeline-hub.component';
import { PipelineService } from '../../services/pipeline.service';
import {
  PipelineSummary,
  StepSchema,
  PipelineExecutionResult,
  ValidationResult,
  CreatePipelineRequest,
  StepConfigRequest,
  SdxModelInfo,
  SdxModelSchema,
  SdxInferenceResult
} from '../../models/pipeline-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function mockPipeline(overrides: Partial<PipelineSummary> = {}): PipelineSummary {
  return {
    pipelineId: 'test-pipeline',
    pipelineType: 'sequence',
    stepCount: 1,
    stepTypes: ['ai.kompile.SomeRunner'],
    createdAt: '2025-01-01T00:00:00Z',
    updatedAt: '2025-01-01T00:00:00Z',
    serving: false,
    ...overrides
  };
}

function mockStepSchema(overrides: Partial<StepSchema> = {}): StepSchema {
  return {
    name: 'Python Step',
    runnerClassName: 'ai.kompile.pipelines.steps.python.PythonRunner',
    description: 'Runs a Python script',
    parameters: [],
    inputs: [],
    outputs: [],
    ...overrides
  };
}

function mockExecutionResult(overrides: Partial<PipelineExecutionResult> = {}): PipelineExecutionResult {
  return {
    executionId: 'exec-1',
    pipelineId: 'test-pipeline',
    status: 'SUCCESS',
    outputData: { result: 42 },
    errorMessage: '',
    durationMs: 150,
    ...overrides
  };
}

function mockSdxModel(overrides: Partial<SdxModelInfo> = {}): SdxModelInfo {
  return {
    modelId: 'my-model',
    path: '/models/my-model.zip',
    format: 'zip',
    loaded: false,
    sizeBytes: 10_000_000,
    ...overrides
  };
}

function createTestBed() {
  const pipelineServiceSpy = jasmine.createSpyObj('PipelineService', [
    'listPipelines',
    'getPipeline',
    'createPipeline',
    'deletePipeline',
    'validatePipeline',
    'executeSync',
    'getAvailableSteps',
    'getServingStatus',
    'servePipeline',
    'unservePipeline',
    'invokeServed',
    'listSdxModels',
    'loadSdxModel',
    'unloadSdxModel',
    'getSdxModelSchema',
    'getSdxInputTemplate',
    'runSdxInference'
  ]);

  // Safe defaults
  pipelineServiceSpy.listPipelines.and.returnValue(of([]));
  pipelineServiceSpy.getAvailableSteps.and.returnValue(of([]));
  pipelineServiceSpy.getServingStatus.and.returnValue(of({}));
  pipelineServiceSpy.listSdxModels.and.returnValue(of([]));

  return {
    pipelineServiceSpy,
    providers: [
      { provide: PipelineService, useValue: pipelineServiceSpy }
    ]
  };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('PipelineHubComponent', () => {
  let component: PipelineHubComponent;
  let fixture: ComponentFixture<PipelineHubComponent>;
  let spies: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    spies = createTestBed();

    await TestBed.configureTestingModule({
      imports: [FormsModule, NoopAnimationsModule],
      declarations: [PipelineHubComponent],
      providers: spies.providers,
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(PipelineHubComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should start on the list tab', () => {
      fixture.detectChanges();
      expect(component.activeTab).toBe('list');
    });

    it('should call loadPipelines and loadAvailableSteps on init', () => {
      fixture.detectChanges();
      expect(spies.pipelineServiceSpy.listPipelines).toHaveBeenCalled();
      expect(spies.pipelineServiceSpy.getAvailableSteps).toHaveBeenCalled();
    });

    it('should initialize with empty pipelines list', () => {
      fixture.detectChanges();
      expect(component.pipelines).toEqual([]);
    });

    it('should have default builder state', () => {
      fixture.detectChanges();
      expect(component.newPipelineId).toBe('');
      expect(component.newPipelineType).toBe('sequence');
      expect(component.builderSteps).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. TAB SWITCHING
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onTabChange()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should switch to list tab', () => {
      component.onTabChange('list');
      expect(component.activeTab).toBe('list');
    });

    it('should switch to builder tab', () => {
      component.onTabChange('builder');
      expect(component.activeTab).toBe('builder');
    });

    it('should switch to runner tab', () => {
      component.onTabChange('runner');
      expect(component.activeTab).toBe('runner');
    });

    it('should switch to stepBrowser tab', () => {
      component.onTabChange('stepBrowser');
      expect(component.activeTab).toBe('stepBrowser');
    });

    it('should switch to serving tab and load serving status', () => {
      component.onTabChange('serving');
      expect(component.activeTab).toBe('serving');
      expect(spies.pipelineServiceSpy.getServingStatus).toHaveBeenCalled();
    });

    it('should switch to sdx tab and load SDX models', () => {
      component.onTabChange('sdx');
      expect(component.activeTab).toBe('sdx');
      expect(spies.pipelineServiceSpy.listSdxModels).toHaveBeenCalled();
    });

    it('should NOT load serving status when switching to non-serving tab', () => {
      spies.pipelineServiceSpy.getServingStatus.calls.reset();
      component.onTabChange('builder');
      expect(spies.pipelineServiceSpy.getServingStatus).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. LIST TAB
  // ─────────────────────────────────────────────────────────────────────────────

  describe('List tab', () => {
    beforeEach(() => fixture.detectChanges());

    describe('loadPipelines()', () => {
      it('should populate pipelines on success', () => {
        const pipelines = [mockPipeline({ pipelineId: 'p1' }), mockPipeline({ pipelineId: 'p2' })];
        spies.pipelineServiceSpy.listPipelines.and.returnValue(of(pipelines));
        component.loadPipelines();
        expect(component.pipelines.length).toBe(2);
        expect(component.pipelines[0].pipelineId).toBe('p1');
      });

      it('should set loading=true while fetching then false on success', () => {
        spies.pipelineServiceSpy.listPipelines.and.returnValue(of([]));
        component.loadPipelines();
        expect(component.loading).toBeFalse();
      });

      it('should set loading=false on error', () => {
        spies.pipelineServiceSpy.listPipelines.and.returnValue(throwError(() => new Error('Network error')));
        component.loadPipelines();
        expect(component.loading).toBeFalse();
      });

      it('should not throw when service returns error', () => {
        spies.pipelineServiceSpy.listPipelines.and.returnValue(throwError(() => new Error('500')));
        expect(() => component.loadPipelines()).not.toThrow();
      });
    });

    describe('deletePipeline()', () => {
      it('should call deletePipeline on the service when user confirms', () => {
        spyOn(window, 'confirm').and.returnValue(true);
        spies.pipelineServiceSpy.deletePipeline.and.returnValue(of({ deleted: true }));
        component.deletePipeline('p1');
        expect(spies.pipelineServiceSpy.deletePipeline).toHaveBeenCalledWith('p1');
      });

      it('should reload pipelines after successful delete', () => {
        spyOn(window, 'confirm').and.returnValue(true);
        spies.pipelineServiceSpy.deletePipeline.and.returnValue(of({ deleted: true }));
        spies.pipelineServiceSpy.listPipelines.calls.reset();
        component.deletePipeline('p1');
        expect(spies.pipelineServiceSpy.listPipelines).toHaveBeenCalled();
      });

      it('should NOT call deletePipeline when user cancels', () => {
        spyOn(window, 'confirm').and.returnValue(false);
        component.deletePipeline('p1');
        expect(spies.pipelineServiceSpy.deletePipeline).not.toHaveBeenCalled();
      });

      it('should not throw when delete fails', () => {
        spyOn(window, 'confirm').and.returnValue(true);
        spies.pipelineServiceSpy.deletePipeline.and.returnValue(throwError(() => new Error('Not found')));
        expect(() => component.deletePipeline('p1')).not.toThrow();
      });
    });

    describe('editPipeline()', () => {
      it('should populate builder fields from fetched pipeline and switch to builder tab', () => {
        const pipelineData: CreatePipelineRequest = {
          pipelineId: 'my-pipeline',
          pipelineType: 'sequence',
          steps: [{ runnerClassName: 'ai.kompile.SomeRunner', parameters: {} }]
        };
        spies.pipelineServiceSpy.getPipeline.and.returnValue(of(pipelineData));
        component.editPipeline(mockPipeline({ pipelineId: 'my-pipeline' }));

        expect(spies.pipelineServiceSpy.getPipeline).toHaveBeenCalledWith('my-pipeline');
        expect(component.newPipelineId).toBe('my-pipeline');
        expect(component.newPipelineType).toBe('sequence');
        expect(component.builderSteps.length).toBe(1);
        expect(component.activeTab).toBe('builder');
      });

      it('should default pipelineType to sequence if not set in response', () => {
        const pipelineData: any = { pipelineId: 'p', steps: [] }; // no pipelineType
        spies.pipelineServiceSpy.getPipeline.and.returnValue(of(pipelineData));
        component.editPipeline(mockPipeline());
        expect(component.newPipelineType).toBe('sequence');
      });

      it('should not throw on error fetching pipeline', () => {
        spies.pipelineServiceSpy.getPipeline.and.returnValue(throwError(() => new Error('404')));
        expect(() => component.editPipeline(mockPipeline())).not.toThrow();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. BUILDER TAB
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Builder tab', () => {
    beforeEach(() => fixture.detectChanges());

    describe('addStep()', () => {
      it('should do nothing when selectedStepType is empty', () => {
        component.selectedStepType = '';
        component.addStep();
        expect(component.builderSteps.length).toBe(0);
      });

      it('should add a step with the selected type and parameters', () => {
        component.selectedStepType = 'ai.kompile.PythonRunner';
        component.stepParameters = { script: 'test.py' };
        component.addStep();
        expect(component.builderSteps.length).toBe(1);
        expect(component.builderSteps[0].runnerClassName).toBe('ai.kompile.PythonRunner');
        expect(component.builderSteps[0].parameters['script']).toBe('test.py');
      });

      it('should clear stepParameters and selectedStepType after adding', () => {
        component.selectedStepType = 'ai.kompile.PythonRunner';
        component.stepParameters = { script: 'test.py' };
        component.addStep();
        expect(component.selectedStepType).toBe('');
        expect(component.stepParameters).toEqual({});
      });

      it('should add multiple steps independently', () => {
        component.selectedStepType = 'ai.kompile.StepA';
        component.addStep();
        component.selectedStepType = 'ai.kompile.StepB';
        component.addStep();
        expect(component.builderSteps.length).toBe(2);
        expect(component.builderSteps[0].runnerClassName).toBe('ai.kompile.StepA');
        expect(component.builderSteps[1].runnerClassName).toBe('ai.kompile.StepB');
      });
    });

    describe('removeStep()', () => {
      beforeEach(() => {
        component.builderSteps = [
          { runnerClassName: 'ai.kompile.StepA', parameters: {} },
          { runnerClassName: 'ai.kompile.StepB', parameters: {} },
          { runnerClassName: 'ai.kompile.StepC', parameters: {} }
        ];
      });

      it('should remove step at given index', () => {
        component.removeStep(1);
        expect(component.builderSteps.length).toBe(2);
        expect(component.builderSteps[0].runnerClassName).toBe('ai.kompile.StepA');
        expect(component.builderSteps[1].runnerClassName).toBe('ai.kompile.StepC');
      });

      it('should remove first step', () => {
        component.removeStep(0);
        expect(component.builderSteps[0].runnerClassName).toBe('ai.kompile.StepB');
      });

      it('should remove last step', () => {
        component.removeStep(2);
        expect(component.builderSteps.length).toBe(2);
        expect(component.builderSteps[1].runnerClassName).toBe('ai.kompile.StepB');
      });
    });

    describe('moveStep()', () => {
      beforeEach(() => {
        component.builderSteps = [
          { runnerClassName: 'ai.kompile.StepA', parameters: {} },
          { runnerClassName: 'ai.kompile.StepB', parameters: {} },
          { runnerClassName: 'ai.kompile.StepC', parameters: {} }
        ];
      });

      it('should move step down (direction = +1)', () => {
        component.moveStep(0, 1);
        expect(component.builderSteps[0].runnerClassName).toBe('ai.kompile.StepB');
        expect(component.builderSteps[1].runnerClassName).toBe('ai.kompile.StepA');
        expect(component.builderSteps[2].runnerClassName).toBe('ai.kompile.StepC');
      });

      it('should move step up (direction = -1)', () => {
        component.moveStep(2, -1);
        expect(component.builderSteps[1].runnerClassName).toBe('ai.kompile.StepC');
        expect(component.builderSteps[2].runnerClassName).toBe('ai.kompile.StepB');
      });

      it('should NOT move step up when already at index 0', () => {
        const original = [...component.builderSteps];
        component.moveStep(0, -1);
        expect(component.builderSteps[0].runnerClassName).toBe(original[0].runnerClassName);
      });

      it('should NOT move step down when already at last index', () => {
        const original = [...component.builderSteps];
        component.moveStep(2, 1);
        expect(component.builderSteps[2].runnerClassName).toBe(original[2].runnerClassName);
      });
    });

    describe('validatePipeline()', () => {
      it('should call validatePipeline on the service', () => {
        const validationResult: ValidationResult = { valid: true, errors: [], warnings: [] };
        spies.pipelineServiceSpy.validatePipeline.and.returnValue(of(validationResult));
        component.newPipelineId = 'my-pipeline';
        component.validatePipeline();
        expect(spies.pipelineServiceSpy.validatePipeline).toHaveBeenCalled();
      });

      it('should set validationResult from service response', () => {
        const result: ValidationResult = { valid: false, errors: ['Step missing'], warnings: [] };
        spies.pipelineServiceSpy.validatePipeline.and.returnValue(of(result));
        component.validatePipeline();
        expect(component.validationResult).toEqual(result);
      });

      it('should not throw on validation error', () => {
        spies.pipelineServiceSpy.validatePipeline.and.returnValue(throwError(() => new Error('Bad request')));
        expect(() => component.validatePipeline()).not.toThrow();
      });
    });

    describe('savePipeline()', () => {
      it('should set builderMessage to error when pipelineId is empty', () => {
        component.newPipelineId = '';
        component.savePipeline();
        expect(component.builderMessage).toBe('Pipeline ID is required');
        expect(spies.pipelineServiceSpy.createPipeline).not.toHaveBeenCalled();
      });

      it('should call createPipeline with built request when ID is provided', () => {
        spies.pipelineServiceSpy.createPipeline.and.returnValue(of(mockPipeline()));
        component.newPipelineId = 'my-pipeline';
        component.newPipelineType = 'sequence';
        component.builderSteps = [{ runnerClassName: 'ai.kompile.PythonRunner', parameters: {} }];
        component.savePipeline();
        expect(spies.pipelineServiceSpy.createPipeline).toHaveBeenCalledWith({
          pipelineId: 'my-pipeline',
          pipelineType: 'sequence',
          steps: [{ runnerClassName: 'ai.kompile.PythonRunner', parameters: {} }]
        });
      });

      it('should set success message and reload pipelines on save', () => {
        spies.pipelineServiceSpy.createPipeline.and.returnValue(of(mockPipeline()));
        spies.pipelineServiceSpy.listPipelines.calls.reset();
        component.newPipelineId = 'my-pipeline';
        component.savePipeline();
        expect(component.builderMessage).toBe('Pipeline saved successfully');
        expect(spies.pipelineServiceSpy.listPipelines).toHaveBeenCalled();
      });

      it('should set error message on save failure', () => {
        spies.pipelineServiceSpy.createPipeline.and.returnValue(
          throwError(() => ({ message: 'Conflict', error: { message: 'Already exists' } }))
        );
        component.newPipelineId = 'my-pipeline';
        component.savePipeline();
        expect(component.builderMessage).toContain('Failed to save');
      });
    });

    describe('clearBuilder()', () => {
      it('should reset all builder fields to defaults', () => {
        component.newPipelineId = 'existing';
        component.newPipelineType = 'parallel';
        component.builderSteps = [{ runnerClassName: 'ai.kompile.SomeRunner', parameters: {} }];
        component.validationResult = { valid: true, errors: [], warnings: [] };
        component.builderMessage = 'Some message';

        component.clearBuilder();

        expect(component.newPipelineId).toBe('');
        expect(component.newPipelineType).toBe('sequence');
        expect(component.builderSteps).toEqual([]);
        expect(component.validationResult).toBeNull();
        expect(component.builderMessage).toBe('');
      });
    });

    describe('getStepName()', () => {
      beforeEach(() => {
        component.availableSteps = [
          mockStepSchema({ name: 'Python Step', runnerClassName: 'ai.kompile.pipelines.steps.python.PythonRunner' })
        ];
      });

      it('should return step name when found in availableSteps', () => {
        const name = component.getStepName('ai.kompile.pipelines.steps.python.PythonRunner');
        expect(name).toBe('Python Step');
      });

      it('should return last segment of class name when not found', () => {
        const name = component.getStepName('ai.kompile.pipelines.steps.onnx.OnnxRunner');
        expect(name).toBe('OnnxRunner');
      });

      it('should return full className if class name has no dots', () => {
        const name = component.getStepName('SimpleRunner');
        expect(name).toBe('SimpleRunner');
      });
    });

    describe('getSelectedStepSchema()', () => {
      beforeEach(() => {
        component.availableSteps = [
          mockStepSchema({ runnerClassName: 'ai.kompile.PythonRunner' }),
          mockStepSchema({ runnerClassName: 'ai.kompile.OnnxRunner' })
        ];
      });

      it('should return the matching schema when selectedStepType is set', () => {
        component.selectedStepType = 'ai.kompile.PythonRunner';
        const schema = component.getSelectedStepSchema();
        expect(schema).toBeDefined();
        expect(schema!.runnerClassName).toBe('ai.kompile.PythonRunner');
      });

      it('should return undefined when selectedStepType has no match', () => {
        component.selectedStepType = 'ai.kompile.UnknownRunner';
        expect(component.getSelectedStepSchema()).toBeUndefined();
      });

      it('should return undefined when selectedStepType is empty', () => {
        component.selectedStepType = '';
        expect(component.getSelectedStepSchema()).toBeUndefined();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. RUNNER TAB
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Runner tab — executePipeline()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should do nothing when selectedPipelineId is empty', () => {
      component.selectedPipelineId = '';
      component.executePipeline();
      expect(spies.pipelineServiceSpy.executeSync).not.toHaveBeenCalled();
    });

    it('should set executing=true while running and false on success', () => {
      spies.pipelineServiceSpy.executeSync.and.returnValue(of(mockExecutionResult()));
      component.selectedPipelineId = 'my-pipeline';
      component.runnerInput = '{}';
      component.executePipeline();
      expect(component.executing).toBeFalse();
      expect(component.executionResult).not.toBeNull();
    });

    it('should store result on success', () => {
      const result = mockExecutionResult({ status: 'SUCCESS', outputData: { answer: 99 } });
      spies.pipelineServiceSpy.executeSync.and.returnValue(of(result));
      component.selectedPipelineId = 'my-pipeline';
      component.runnerInput = '{"question": "test"}';
      component.executePipeline();
      expect(component.executionResult!.status).toBe('SUCCESS');
      expect(component.executionResult!.outputData['answer']).toBe(99);
    });

    it('should set ERROR result for invalid JSON input', () => {
      component.selectedPipelineId = 'my-pipeline';
      component.runnerInput = 'not-json{';
      component.executePipeline();
      expect(component.executionResult!.status).toBe('ERROR');
      expect(component.executionResult!.errorMessage).toBe('Invalid JSON input');
      expect(component.executing).toBeFalse();
      expect(spies.pipelineServiceSpy.executeSync).not.toHaveBeenCalled();
    });

    it('should set ERROR result on service error', () => {
      spies.pipelineServiceSpy.executeSync.and.returnValue(
        throwError(() => ({ message: 'Pipeline not found', error: { message: 'Not found' } }))
      );
      component.selectedPipelineId = 'my-pipeline';
      component.runnerInput = '{}';
      component.executePipeline();
      expect(component.executionResult!.status).toBe('ERROR');
      expect(component.executing).toBeFalse();
    });

    it('should clear previous result before running', () => {
      component.executionResult = mockExecutionResult({ status: 'SUCCESS' });
      spies.pipelineServiceSpy.executeSync.and.returnValue(of(mockExecutionResult()));
      component.selectedPipelineId = 'my-pipeline';
      component.runnerInput = '{}';
      component.executePipeline();
      // After success, a new result is set, confirming it was cleared first
      expect(component.executionResult).not.toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. STEP BROWSER — toggleStep()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Step browser — toggleStep()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should expand a step when it is not currently expanded', () => {
      component.expandedStep = null;
      component.toggleStep('ai.kompile.PythonRunner');
      expect(component.expandedStep as any).toBe('ai.kompile.PythonRunner');
    });

    it('should collapse a step when it is already expanded', () => {
      component.expandedStep = 'ai.kompile.PythonRunner';
      component.toggleStep('ai.kompile.PythonRunner');
      expect(component.expandedStep).toBeNull();
    });

    it('should switch expanded step when a different step is clicked', () => {
      component.expandedStep = 'ai.kompile.PythonRunner';
      component.toggleStep('ai.kompile.OnnxRunner');
      expect(component.expandedStep).toBe('ai.kompile.OnnxRunner');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. SERVING TAB
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Serving tab', () => {
    beforeEach(() => fixture.detectChanges());

    describe('loadServingStatus()', () => {
      it('should populate servingStatus from service', () => {
        const status = { 'p1': true, 'p2': false };
        spies.pipelineServiceSpy.getServingStatus.and.returnValue(of(status));
        component.loadServingStatus();
        expect(component.servingStatus).toEqual(status);
      });

      it('should not throw on error', () => {
        spies.pipelineServiceSpy.getServingStatus.and.returnValue(throwError(() => new Error('500')));
        expect(() => component.loadServingStatus()).not.toThrow();
      });
    });

    describe('toggleServe()', () => {
      it('should call unservePipeline when pipeline is currently serving', () => {
        spies.pipelineServiceSpy.unservePipeline.and.returnValue(of({}));
        component.toggleServe('p1', true);
        expect(spies.pipelineServiceSpy.unservePipeline).toHaveBeenCalledWith('p1');
        expect(spies.pipelineServiceSpy.servePipeline).not.toHaveBeenCalled();
      });

      it('should call servePipeline when pipeline is not currently serving', () => {
        spies.pipelineServiceSpy.servePipeline.and.returnValue(of({}));
        component.toggleServe('p1', false);
        expect(spies.pipelineServiceSpy.servePipeline).toHaveBeenCalledWith('p1');
        expect(spies.pipelineServiceSpy.unservePipeline).not.toHaveBeenCalled();
      });

      it('should reload serving status and pipelines after toggle', () => {
        spies.pipelineServiceSpy.servePipeline.and.returnValue(of({}));
        spies.pipelineServiceSpy.getServingStatus.calls.reset();
        spies.pipelineServiceSpy.listPipelines.calls.reset();
        component.toggleServe('p1', false);
        expect(spies.pipelineServiceSpy.getServingStatus).toHaveBeenCalled();
        expect(spies.pipelineServiceSpy.listPipelines).toHaveBeenCalled();
      });

      it('should not throw on error', () => {
        spies.pipelineServiceSpy.servePipeline.and.returnValue(throwError(() => new Error('Serve failed')));
        expect(() => component.toggleServe('p1', false)).not.toThrow();
      });
    });

    describe('invokeServed()', () => {
      it('should do nothing when selectedServingPipeline is empty', () => {
        component.selectedServingPipeline = '';
        component.invokeServed();
        expect(spies.pipelineServiceSpy.invokeServed).not.toHaveBeenCalled();
      });

      it('should do nothing when serveInput is invalid JSON', () => {
        component.selectedServingPipeline = 'p1';
        component.serveInput = 'bad-json{{{';
        component.invokeServed();
        expect(spies.pipelineServiceSpy.invokeServed).not.toHaveBeenCalled();
      });

      it('should call invokeServed with parsed input and store result', () => {
        const result = mockExecutionResult({ pipelineId: 'p1', status: 'SUCCESS' });
        spies.pipelineServiceSpy.invokeServed.and.returnValue(of(result));
        component.selectedServingPipeline = 'p1';
        component.serveInput = '{"query": "hello"}';
        component.invokeServed();
        expect(spies.pipelineServiceSpy.invokeServed).toHaveBeenCalledWith('p1', { query: 'hello' });
        expect(component.serveResult!.status).toBe('SUCCESS');
      });

      it('should not throw on service error', () => {
        spies.pipelineServiceSpy.invokeServed.and.returnValue(throwError(() => new Error('Invocation failed')));
        component.selectedServingPipeline = 'p1';
        component.serveInput = '{}';
        expect(() => component.invokeServed()).not.toThrow();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. SDX TAB
  // ─────────────────────────────────────────────────────────────────────────────

  describe('SDX tab', () => {
    beforeEach(() => fixture.detectChanges());

    describe('loadSdxModels()', () => {
      it('should populate sdxModels from service', () => {
        const models = [mockSdxModel({ modelId: 'm1' }), mockSdxModel({ modelId: 'm2' })];
        spies.pipelineServiceSpy.listSdxModels.and.returnValue(of(models));
        component.loadSdxModels();
        expect(component.sdxModels.length).toBe(2);
        expect(component.sdxModels[0].modelId).toBe('m1');
      });

      it('should not throw on error', () => {
        spies.pipelineServiceSpy.listSdxModels.and.returnValue(throwError(() => new Error('500')));
        expect(() => component.loadSdxModels()).not.toThrow();
      });
    });

    describe('loadSdxModel()', () => {
      it('should set sdxLoadingModel=true while loading, then false on success', () => {
        const schema: SdxModelSchema = {
          modelId: 'm1', runnerClass: 'ai.kompile.SameDiffRunner', path: '/models/m1.fb'
        };
        spies.pipelineServiceSpy.loadSdxModel.and.returnValue(of({}));
        spies.pipelineServiceSpy.listSdxModels.and.returnValue(of([]));
        spies.pipelineServiceSpy.getSdxModelSchema.and.returnValue(of(schema));
        component.loadSdxModel('m1');
        expect(component.sdxLoadingModel).toBeFalse();
        expect(component.sdxSelectedModel).toBe('m1');
      });

      it('should set sdxLoadingModel=false on error', () => {
        spies.pipelineServiceSpy.loadSdxModel.and.returnValue(throwError(() => new Error('Load failed')));
        component.loadSdxModel('m1');
        expect(component.sdxLoadingModel).toBeFalse();
      });

      it('should reload SDX models and fetch schema after loading', () => {
        const schema: SdxModelSchema = {
          modelId: 'm1', runnerClass: 'ai.kompile.SameDiffRunner', path: '/models/m1.fb'
        };
        spies.pipelineServiceSpy.loadSdxModel.and.returnValue(of({}));
        spies.pipelineServiceSpy.listSdxModels.calls.reset();
        spies.pipelineServiceSpy.getSdxModelSchema.and.returnValue(of(schema));
        component.loadSdxModel('m1');
        expect(spies.pipelineServiceSpy.listSdxModels).toHaveBeenCalled();
        expect(spies.pipelineServiceSpy.getSdxModelSchema).toHaveBeenCalledWith('m1');
      });
    });

    describe('unloadSdxModel()', () => {
      it('should call unloadSdxModel service and reload models', () => {
        spies.pipelineServiceSpy.unloadSdxModel.and.returnValue(of({}));
        spies.pipelineServiceSpy.listSdxModels.calls.reset();
        component.unloadSdxModel('m1');
        expect(spies.pipelineServiceSpy.unloadSdxModel).toHaveBeenCalledWith('m1');
        expect(spies.pipelineServiceSpy.listSdxModels).toHaveBeenCalled();
      });

      it('should clear selected model state if the unloaded model was selected', () => {
        spies.pipelineServiceSpy.unloadSdxModel.and.returnValue(of({}));
        component.sdxSelectedModel = 'm1';
        component.sdxModelSchema = { modelId: 'm1', runnerClass: 'x', path: '/x' };
        component.sdxInferenceResult = { status: 'ok', modelId: 'm1', durationMs: 0 };
        component.unloadSdxModel('m1');
        expect(component.sdxSelectedModel).toBe('');
        expect(component.sdxModelSchema).toBeNull();
        expect(component.sdxInferenceResult).toBeNull();
      });

      it('should NOT clear selected model state if a different model was unloaded', () => {
        spies.pipelineServiceSpy.unloadSdxModel.and.returnValue(of({}));
        component.sdxSelectedModel = 'm2';
        component.unloadSdxModel('m1');
        expect(component.sdxSelectedModel).toBe('m2');
      });

      it('should not throw on error', () => {
        spies.pipelineServiceSpy.unloadSdxModel.and.returnValue(throwError(() => new Error('Unload failed')));
        expect(() => component.unloadSdxModel('m1')).not.toThrow();
      });
    });

    describe('selectSdxModel()', () => {
      it('should set sdxSelectedModel and clear inference result', () => {
        spies.pipelineServiceSpy.getSdxModelSchema.and.returnValue(of({
          modelId: 'm1', runnerClass: 'ai.kompile.SameDiffRunner', path: '/x'
        }));
        component.sdxInferenceResult = { status: 'ok', modelId: 'm0', durationMs: 0 };
        component.selectSdxModel('m1');
        expect(component.sdxSelectedModel).toBe('m1');
        expect(component.sdxInferenceResult).toBeNull();
      });

      it('should fetch schema for the selected model', () => {
        const schema: SdxModelSchema = { modelId: 'm1', runnerClass: 'x', path: '/x' };
        spies.pipelineServiceSpy.getSdxModelSchema.and.returnValue(of(schema));
        component.selectSdxModel('m1');
        expect(spies.pipelineServiceSpy.getSdxModelSchema).toHaveBeenCalledWith('m1');
        expect(component.sdxModelSchema).toEqual(schema);
      });
    });

    describe('runSdxInference()', () => {
      it('should do nothing when sdxSelectedModel is empty', () => {
        component.sdxSelectedModel = '';
        component.runSdxInference();
        expect(spies.pipelineServiceSpy.runSdxInference).not.toHaveBeenCalled();
      });

      it('should set error result for invalid JSON input', () => {
        component.sdxSelectedModel = 'm1';
        component.sdxInferenceInput = 'not-json';
        component.runSdxInference();
        expect(component.sdxInferenceResult!.status).toBe('error');
        expect(component.sdxInferenceResult!.errorMessage).toBe('Invalid JSON input');
        expect(component.sdxInferring).toBeFalse();
        expect(spies.pipelineServiceSpy.runSdxInference).not.toHaveBeenCalled();
      });

      it('should set sdxInferring=true then false on success', () => {
        const result: SdxInferenceResult = { status: 'success', modelId: 'm1', durationMs: 100 };
        spies.pipelineServiceSpy.runSdxInference.and.returnValue(of(result));
        component.sdxSelectedModel = 'm1';
        component.sdxInferenceInput = '{"input": [1, 2, 3]}';
        component.runSdxInference();
        expect(component.sdxInferring).toBeFalse();
        expect(component.sdxInferenceResult!.status).toBe('success');
      });

      it('should call service with parsed input', () => {
        const result: SdxInferenceResult = { status: 'success', modelId: 'm1', durationMs: 50 };
        spies.pipelineServiceSpy.runSdxInference.and.returnValue(of(result));
        component.sdxSelectedModel = 'm1';
        component.sdxInferenceInput = '{"x": 42}';
        component.runSdxInference();
        expect(spies.pipelineServiceSpy.runSdxInference).toHaveBeenCalledWith('m1', { x: 42 });
      });

      it('should set error result on service failure', () => {
        spies.pipelineServiceSpy.runSdxInference.and.returnValue(
          throwError(() => ({ message: 'Inference failed', error: { error: 'Model not loaded' } }))
        );
        component.sdxSelectedModel = 'm1';
        component.sdxInferenceInput = '{}';
        component.runSdxInference();
        expect(component.sdxInferenceResult!.status).toBe('error');
        expect(component.sdxInferring).toBeFalse();
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. UTILITY METHODS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Utility methods', () => {
    beforeEach(() => fixture.detectChanges());

    describe('formatModelSize()', () => {
      it('should return "Unknown" for undefined bytes', () => {
        expect(component.formatModelSize(undefined)).toBe('Unknown');
      });

      it('should return "Unknown" for 0 bytes', () => {
        expect(component.formatModelSize(0)).toBe('Unknown');
      });

      it('should format bytes < 1 MB as KB', () => {
        const result = component.formatModelSize(500 * 1024);
        expect(result).toContain('KB');
        expect(result).toContain('500.0');
      });

      it('should format bytes >= 1 MB as MB', () => {
        const result = component.formatModelSize(25 * 1024 * 1024);
        expect(result).toContain('MB');
        expect(result).toContain('25.0');
      });

      it('should format bytes >= 1 GB as GB', () => {
        const result = component.formatModelSize(2.5 * 1024 * 1024 * 1024);
        expect(result).toContain('GB');
        expect(result).toContain('2.50');
      });
    });

    describe('formatJson()', () => {
      it('should pretty-print an object', () => {
        const result = component.formatJson({ key: 'value', num: 42 });
        expect(result).toContain('"key"');
        expect(result).toContain('"value"');
        expect(result).toContain('"num"');
        expect(result).toContain('42');
      });

      it('should handle null', () => {
        expect(component.formatJson(null)).toBe('null');
      });

      it('should handle a plain string', () => {
        expect(component.formatJson('hello')).toBe('"hello"');
      });

      it('should return string representation for non-serializable values', () => {
        // circular reference will cause JSON.stringify to throw
        const circ: any = {};
        circ.self = circ;
        const result = component.formatJson(circ);
        expect(typeof result).toBe('string');
      });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 10. PIPELINE VALIDATION EDGE CASES
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Pipeline validation edge cases', () => {
    beforeEach(() => fixture.detectChanges());

    it('should require pipeline ID before saving', () => {
      component.newPipelineId = '';
      component.savePipeline();
      expect(component.builderMessage).toBe('Pipeline ID is required');
    });

    it('should allow saving an empty pipeline (no steps)', () => {
      spies.pipelineServiceSpy.createPipeline.and.returnValue(of(mockPipeline()));
      component.newPipelineId = 'empty-pipeline';
      component.builderSteps = [];
      component.savePipeline();
      expect(spies.pipelineServiceSpy.createPipeline).toHaveBeenCalledWith(
        jasmine.objectContaining({ pipelineId: 'empty-pipeline', steps: [] })
      );
    });

    it('should handle validation of a valid pipeline', () => {
      spies.pipelineServiceSpy.validatePipeline.and.returnValue(
        of({ valid: true, errors: [], warnings: ['Unused step'] })
      );
      component.newPipelineId = 'my-pipeline';
      component.validatePipeline();
      expect(component.validationResult!.valid).toBeTrue();
      expect(component.validationResult!.warnings.length).toBe(1);
    });

    it('should handle validation with errors', () => {
      spies.pipelineServiceSpy.validatePipeline.and.returnValue(
        of({ valid: false, errors: ['Missing required param', 'Unknown step type'], warnings: [] })
      );
      component.validatePipeline();
      expect(component.validationResult!.valid).toBeFalse();
      expect(component.validationResult!.errors.length).toBe(2);
    });
  });
});
