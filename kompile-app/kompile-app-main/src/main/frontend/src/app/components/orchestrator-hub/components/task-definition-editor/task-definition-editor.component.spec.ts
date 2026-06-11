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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { of, throwError } from 'rxjs';
import { TaskDefinitionEditorComponent } from './task-definition-editor.component';
import {
  OrchestratorService,
  EnhancedTaskDefinition
} from '../../../../services/orchestrator.service';

describe('TaskDefinitionEditorComponent', () => {
  let component: TaskDefinitionEditorComponent;
  let fixture: ComponentFixture<TaskDefinitionEditorComponent>;
  let orchestratorServiceSpy: jasmine.SpyObj<OrchestratorService>;

  const mockEnhancedTaskDef: EnhancedTaskDefinition = {
    taskId: 'task-001',
    name: 'My Task',
    description: 'A test task',
    taskType: 'SHELL',
    command: 'echo hello',
    timeoutSeconds: 300,
    retryCount: 0,
    retryDelaySeconds: 5,
    enableRag: false,
    enableTools: false,
    autoInvokeLlmOnError: false,
    ragMaxResults: 5,
    ragSimilarityThreshold: 0.7,
    ragIncludeKeywordSearch: true,
    ragIncludeSemanticSearch: true,
    skipPermissions: false
  };

  const savedTask: EnhancedTaskDefinition = { ...mockEnhancedTaskDef };

  beforeEach(async () => {
    orchestratorServiceSpy = jasmine.createSpyObj('OrchestratorService', [
      'getAvailableAgents', 'getOutputClassifiers', 'getRagFolders', 'getStates',
      'getTaskDefinitions', 'getAgentTools', 'testPattern',
      'createEnhancedTaskDefinition', 'updateEnhancedTaskDefinition'
    ]);

    orchestratorServiceSpy.getAvailableAgents.and.returnValue(of([]));
    orchestratorServiceSpy.getOutputClassifiers.and.returnValue(of([]));
    orchestratorServiceSpy.getRagFolders.and.returnValue(of([]));
    orchestratorServiceSpy.getStates.and.returnValue(of([]));
    orchestratorServiceSpy.getTaskDefinitions.and.returnValue(of([]));
    orchestratorServiceSpy.getAgentTools.and.returnValue(of(['tool-1', 'tool-2']));
    orchestratorServiceSpy.testPattern.and.returnValue(of({ matches: true, groups: [] } as any));
    orchestratorServiceSpy.createEnhancedTaskDefinition.and.returnValue(of(savedTask));
    orchestratorServiceSpy.updateEnhancedTaskDefinition.and.returnValue(of(savedTask));

    await TestBed.configureTestingModule({
      declarations: [TaskDefinitionEditorComponent],
      imports: [
        NoopAnimationsModule, ReactiveFormsModule, FormsModule,
        MatSelectModule, MatInputModule, MatCheckboxModule, MatSlideToggleModule
      ],
      providers: [
        { provide: OrchestratorService, useValue: orchestratorServiceSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(TaskDefinitionEditorComponent);
    component = fixture.componentInstance;
    component.instanceId = 'orch-1';
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize the form', () => {
    expect(component.taskForm).toBeTruthy();
    expect(component.taskForm.get('taskId')).toBeTruthy();
    expect(component.taskForm.get('name')).toBeTruthy();
  });

  it('should load options on init with a valid instanceId', () => {
    expect(orchestratorServiceSpy.getAvailableAgents).toHaveBeenCalledWith('orch-1');
    expect(orchestratorServiceSpy.getOutputClassifiers).toHaveBeenCalledWith('orch-1');
    expect(orchestratorServiceSpy.getRagFolders).toHaveBeenCalledWith('orch-1');
  });

  it('should not load options when instanceId is empty', fakeAsync(() => {
    orchestratorServiceSpy.getAvailableAgents.calls.reset();
    component.instanceId = '';
    component.ngOnInit();
    tick();
    expect(orchestratorServiceSpy.getAvailableAgents).not.toHaveBeenCalled();
  }));

  describe('populateForm with taskDefinition input', () => {
    beforeEach(() => {
      component.taskDefinition = mockEnhancedTaskDef;
      component.ngOnInit();
      fixture.detectChanges();
    });

    it('should populate taskId from input', () => {
      expect(component.taskForm.get('taskId')?.value).toBe('task-001');
    });

    it('should populate name from input', () => {
      expect(component.taskForm.get('name')?.value).toBe('My Task');
    });

    it('should populate command from input', () => {
      expect(component.taskForm.get('command')?.value).toBe('echo hello');
    });

    it('should populate taskType from input', () => {
      expect(component.taskForm.get('taskType')?.value).toBe('SHELL');
    });

    it('should populate timeoutSeconds from input', () => {
      expect(component.taskForm.get('timeoutSeconds')?.value).toBe(300);
    });
  });

  describe('form validation', () => {
    it('should be invalid without taskId', () => {
      component.taskForm.patchValue({ taskId: '', name: 'Test' });
      expect(component.taskForm.get('taskId')?.valid).toBeFalse();
    });

    it('should be invalid without name', () => {
      component.taskForm.patchValue({ taskId: 'id-1', name: '' });
      expect(component.taskForm.get('name')?.valid).toBeFalse();
    });

    it('should be invalid with non-alphanumeric taskId', () => {
      component.taskForm.patchValue({ taskId: 'invalid task id!', name: 'Test' });
      expect(component.taskForm.get('taskId')?.valid).toBeFalse();
    });

    it('should be valid with proper taskId and name', () => {
      component.taskForm.patchValue({ taskId: 'valid-task-id', name: 'Test Task' });
      expect(component.taskForm.get('taskId')?.valid).toBeTrue();
      expect(component.taskForm.get('name')?.valid).toBeTrue();
    });
  });

  describe('save - create mode', () => {
    beforeEach(() => {
      component.taskDefinition = null;
      component.taskForm.patchValue({
        taskId: 'new-task',
        name: 'New Task',
        taskType: 'SHELL'
      });
    });

    it('should set error when form is invalid', () => {
      component.taskForm.patchValue({ taskId: '', name: '' });
      component.save();
      expect(component.error).toContain('validation errors');
      expect(orchestratorServiceSpy.createEnhancedTaskDefinition).not.toHaveBeenCalled();
    });

    it('should call createEnhancedTaskDefinition when form is valid', fakeAsync(() => {
      component.save();
      tick();
      expect(orchestratorServiceSpy.createEnhancedTaskDefinition).toHaveBeenCalled();
    }));

    it('should emit saved event on success', fakeAsync(() => {
      let emittedValue: any = null;
      component.saved.subscribe((v: EnhancedTaskDefinition) => emittedValue = v);
      component.save();
      tick();
      expect(emittedValue).toBeTruthy();
      expect(emittedValue.taskId).toBe(savedTask.taskId);
    }));

    it('should set error on save failure', fakeAsync(() => {
      orchestratorServiceSpy.createEnhancedTaskDefinition.and.returnValue(
        throwError(() => ({ message: 'Save failed' }))
      );
      component.save();
      tick();
      expect(component.error).toContain('Failed to save task definition');
      expect(component.saving).toBeFalse();
    }));
  });

  describe('save - update mode', () => {
    beforeEach(() => {
      component.taskDefinition = mockEnhancedTaskDef;
      component.ngOnInit();
      fixture.detectChanges();
    });

    it('should call updateEnhancedTaskDefinition when editing', fakeAsync(() => {
      component.save();
      tick();
      expect(orchestratorServiceSpy.updateEnhancedTaskDefinition).toHaveBeenCalled();
    }));

    it('should set saving to false after completion', fakeAsync(() => {
      component.save();
      tick();
      expect(component.saving).toBeFalse();
    }));
  });

  describe('cancel', () => {
    it('should emit cancelled event', () => {
      let cancelCalled = false;
      component.cancelled.subscribe(() => cancelCalled = true);
      component.cancel();
      expect(cancelCalled).toBeTrue();
    });
  });

  describe('setActivePanel', () => {
    it('should set active panel to agent', () => {
      component.setActivePanel('agent');
      expect(component.activePanel).toBe('agent');
    });

    it('should set active panel to rag', () => {
      component.setActivePanel('rag');
      expect(component.activePanel).toBe('rag');
    });

    it('should set active panel to tools', () => {
      component.setActivePanel('tools');
      expect(component.activePanel).toBe('tools');
    });

    it('should set active panel to actions', () => {
      component.setActivePanel('actions');
      expect(component.activePanel).toBe('actions');
    });
  });

  describe('isToolSelected', () => {
    it('should return true when tool is in allowedTools', () => {
      component.taskForm.patchValue({ allowedTools: 'tool-1, tool-2' });
      expect(component.isToolSelected('tool-1')).toBeTrue();
    });

    it('should return false when tool is not in allowedTools', () => {
      component.taskForm.patchValue({ allowedTools: 'other-tool' });
      expect(component.isToolSelected('tool-1')).toBeFalse();
    });
  });

  describe('toggleTool', () => {
    it('should add tool to allowedTools', () => {
      component.taskForm.patchValue({ allowedTools: '' });
      component.toggleTool('new-tool');
      expect(component.taskForm.get('allowedTools')?.value).toContain('new-tool');
    });

    it('should remove tool from allowedTools', () => {
      component.taskForm.patchValue({ allowedTools: 'tool-1, tool-2' });
      component.toggleTool('tool-1');
      const val = component.taskForm.get('allowedTools')?.value;
      expect(val).not.toContain('tool-1');
    });
  });

  describe('requiredVariables FormArray', () => {
    it('should return requiredVariables FormArray', () => {
      expect(component.requiredVariables).toBeTruthy();
    });

    it('should remove variable at given index', () => {
      component.requiredVariables.push(component['fb'].control('varA'));
      component.requiredVariables.push(component['fb'].control('varB'));
      component.removeRequiredVariable(0);
      expect(component.requiredVariables.length).toBe(1);
      expect(component.requiredVariables.at(0).value).toBe('varB');
    });
  });

  describe('testSuccessPattern / testFailurePattern', () => {
    it('should call testPattern for success pattern', fakeAsync(() => {
      component.taskForm.patchValue({ successPattern: '.*success.*' });
      component.testPatternInput = 'success result';
      component.testSuccessPattern();
      tick();
      expect(orchestratorServiceSpy.testPattern).toHaveBeenCalledWith('orch-1', '.*success.*', 'success result');
    }));

    it('should call testPattern for failure pattern', fakeAsync(() => {
      component.taskForm.patchValue({ failurePattern: '.*error.*' });
      component.testPatternInput = 'error occurred';
      component.testFailurePattern();
      tick();
      expect(orchestratorServiceSpy.testPattern).toHaveBeenCalledWith('orch-1', '.*error.*', 'error occurred');
    }));

    it('should not call testPattern when pattern is empty', fakeAsync(() => {
      component.taskForm.patchValue({ successPattern: '' });
      component.testPatternInput = 'some input';
      orchestratorServiceSpy.testPattern.calls.reset();
      component.testSuccessPattern();
      tick();
      expect(orchestratorServiceSpy.testPattern).not.toHaveBeenCalled();
    }));

    it('should set testPatternResult on error', fakeAsync(() => {
      component.taskForm.patchValue({ successPattern: 'bad-regex' });
      component.testPatternInput = 'input text';
      orchestratorServiceSpy.testPattern.and.returnValue(throwError(() => ({ message: 'Invalid regex' })));
      component.testSuccessPattern();
      tick();
      expect(component.testPatternResult).toBeTruthy();
      expect(component.testPatternResult.valid).toBeFalse();
    }));
  });

  describe('clearError', () => {
    it('should clear error message', () => {
      component.error = 'Some error';
      component.clearError();
      expect(component.error).toBeNull();
    });
  });

  describe('getSelectedAgent', () => {
    it('should return agent matching selected agentName', () => {
      component.agents = [{ name: 'claude', displayName: 'Claude' } as any];
      component.taskForm.patchValue({ agentName: 'claude' });
      const agent = component.getSelectedAgent();
      expect(agent?.name).toBe('claude');
    });

    it('should return undefined when no agent matches', () => {
      component.agents = [];
      component.taskForm.patchValue({ agentName: 'unknown' });
      expect(component.getSelectedAgent()).toBeUndefined();
    });
  });

  describe('ngOnDestroy', () => {
    it('should complete destroy$ subject', () => {
      spyOn(component['destroy$'], 'next');
      spyOn(component['destroy$'], 'complete');
      component.ngOnDestroy();
      expect(component['destroy$'].next).toHaveBeenCalled();
      expect(component['destroy$'].complete).toHaveBeenCalled();
    });
  });
});
