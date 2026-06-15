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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { OrchestratorHubComponent } from './orchestrator-hub.component';
import {
  OrchestratorService,
  OrchestratorInstance,
  TaskInstance,
  Workflow,
  WorkflowStep
} from '../../services/orchestrator.service';
import { AgentService } from '../../services/agent.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { AgentProvider, FactSheet } from '../../models/api-models';
import { TaskDefinition } from '../../models/orchestrator-models';

describe('OrchestratorHubComponent', () => {
  let component: OrchestratorHubComponent;
  let fixture: ComponentFixture<OrchestratorHubComponent>;
  let orchestratorServiceSpy: jasmine.SpyObj<OrchestratorService>;
  let agentServiceSpy: jasmine.SpyObj<AgentService>;
  let factSheetServiceSpy: jasmine.SpyObj<FactSheetService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  const mockOrchestrator: OrchestratorInstance = {
    instanceId: 'orch-1',
    name: 'Test Orchestrator',
    status: 'RUNNING'
  };

  const mockTask: TaskInstance = {
    id: 1,
    taskDefinitionId: 'task-1',
    orchestratorInstanceId: 'orch-1',
    status: 'PENDING'
  };

  const mockWorkflow: Workflow = {
    id: 1,
    name: 'Test Workflow',
    orchestratorInstanceId: 'orch-1',
    status: 'IN_PROGRESS'
  };

  const mockWorkflowStep: WorkflowStep = {
    id: 1,
    workflowId: 1,
    stepNumber: 1,
    status: 'PENDING'
  };

  const mockAgent: AgentProvider = {
    name: 'claude',
    displayName: 'Claude',
    available: true,
    isDefault: true
  } as AgentProvider;

  const mockFactSheet: FactSheet = {
    id: 1,
    name: 'Test Sheet'
  } as FactSheet;

  const mockDialogRef = {
    afterClosed: () => of(false)
  } as unknown as MatDialogRef<any>;

  const mockConfirmDialogRef = {
    afterClosed: () => of(true)
  } as unknown as MatDialogRef<any>;

  beforeEach(async () => {
    orchestratorServiceSpy = jasmine.createSpyObj('OrchestratorService', [
      'getAllOrchestrators', 'createOrchestrator', 'startOrchestrator', 'pauseOrchestrator',
      'resumeOrchestrator', 'stopOrchestrator', 'deleteOrchestrator', 'createSnapshot',
      'recoverOrchestrator', 'getTaskHistory', 'getRunningTasks', 'executeCommand',
      'cancelTask', 'getAllWorkflows', 'getActiveWorkflows', 'getWorkflowSteps',
      'startWorkflow', 'advanceWorkflow', 'approveWorkflowStep', 'rejectWorkflowStep',
      'cancelWorkflow', 'getLlmSession', 'getTask'
    ]);

    orchestratorServiceSpy.getAllOrchestrators.and.returnValue(of([mockOrchestrator]));
    orchestratorServiceSpy.createOrchestrator.and.returnValue(of(mockOrchestrator));
    orchestratorServiceSpy.startOrchestrator.and.returnValue(of({}));
    orchestratorServiceSpy.pauseOrchestrator.and.returnValue(of({}));
    orchestratorServiceSpy.resumeOrchestrator.and.returnValue(of({}));
    orchestratorServiceSpy.stopOrchestrator.and.returnValue(of({}));
    orchestratorServiceSpy.deleteOrchestrator.and.returnValue(of(undefined));
    orchestratorServiceSpy.createSnapshot.and.returnValue(of({}));
    orchestratorServiceSpy.recoverOrchestrator.and.returnValue(of({}));
    orchestratorServiceSpy.getTaskHistory.and.returnValue(of([mockTask]));
    orchestratorServiceSpy.getRunningTasks.and.returnValue(of([mockTask]));
    orchestratorServiceSpy.executeCommand.and.returnValue(of(mockTask));
    orchestratorServiceSpy.cancelTask.and.returnValue(of({}));
    orchestratorServiceSpy.getAllWorkflows.and.returnValue(of([mockWorkflow]));
    orchestratorServiceSpy.getActiveWorkflows.and.returnValue(of([mockWorkflow]));
    orchestratorServiceSpy.getWorkflowSteps.and.returnValue(of([mockWorkflowStep]));
    orchestratorServiceSpy.startWorkflow.and.returnValue(of(mockWorkflow));
    orchestratorServiceSpy.advanceWorkflow.and.returnValue(of({}));
    orchestratorServiceSpy.approveWorkflowStep.and.returnValue(of({}));
    orchestratorServiceSpy.rejectWorkflowStep.and.returnValue(of({}));
    orchestratorServiceSpy.cancelWorkflow.and.returnValue(of({}));
    orchestratorServiceSpy.getLlmSession.and.returnValue(of({ id: 1, orchestratorInstanceId: 'orch-1', status: 'COMPLETED', initialPrompt: 'test', output: 'result' } as any));
    orchestratorServiceSpy.getTask.and.returnValue(of(mockTask));

    agentServiceSpy = jasmine.createSpyObj('AgentService', ['getAllAgents']);
    agentServiceSpy.getAllAgents.and.returnValue(of([mockAgent]));

    factSheetServiceSpy = jasmine.createSpyObj('FactSheetService', ['loadSheets']);
    factSheetServiceSpy.loadSheets.and.returnValue(of([mockFactSheet]));

    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    dialogSpy.open.and.returnValue(mockDialogRef);

    await TestBed.configureTestingModule({
      declarations: [OrchestratorHubComponent],
      imports: [NoopAnimationsModule],
      providers: [
        { provide: OrchestratorService, useValue: orchestratorServiceSpy },
        { provide: AgentService, useValue: agentServiceSpy },
        { provide: FactSheetService, useValue: factSheetServiceSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(OrchestratorHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  afterEach(() => {
    // Clean up any pending timers
    try { discardPeriodicTasks(); } catch {}
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize with instances sub tab', () => {
    expect(component.activeSubTab).toBe('instances');
  });

  it('should load orchestrators on init', () => {
    expect(orchestratorServiceSpy.getAllOrchestrators).toHaveBeenCalled();
    expect(component.orchestrators).toEqual([mockOrchestrator]);
  });

  it('should load agents and fact sheets on init', () => {
    expect(agentServiceSpy.getAllAgents).toHaveBeenCalled();
    expect(factSheetServiceSpy.loadSheets).toHaveBeenCalled();
  });

  it('should auto-select default available agent', () => {
    expect(component.selectedAgentName).toBe('claude');
  });

  describe('loadOrchestrators', () => {
    it('should populate orchestrators', fakeAsync(() => {
      component.loadOrchestrators();
      tick();
      expect(component.orchestrators).toEqual([mockOrchestrator]);
      expect(component.error).toBeNull();
    }));

    it('should set error on failure', fakeAsync(() => {
      orchestratorServiceSpy.getAllOrchestrators.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.loadOrchestrators();
      tick();
      expect(component.error).toContain('Failed to load orchestrators');
    }));
  });

  describe('selectOrchestrator', () => {
    it('should set selectedOrchestrator', fakeAsync(() => {
      component.selectOrchestrator(mockOrchestrator);
      tick();
      expect(component.selectedOrchestrator).toBe(mockOrchestrator);
    }));

    it('should load tasks and workflows', fakeAsync(() => {
      component.selectOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.getTaskHistory).toHaveBeenCalled();
      expect(orchestratorServiceSpy.getAllWorkflows).toHaveBeenCalled();
    }));
  });

  describe('selectAndOpenOrchestrator', () => {
    it('should switch to orchestrator-detail tab', fakeAsync(() => {
      component.selectAndOpenOrchestrator(mockOrchestrator);
      tick();
      expect(component.activeSubTab).toBe('orchestrator-detail');
      expect(component.orchestratorDetailTab).toBe('overview');
    }));
  });

  describe('startOrchestrator', () => {
    it('should call startOrchestrator service', fakeAsync(() => {
      component.startOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.startOrchestrator).toHaveBeenCalledWith('orch-1');
    }));

    it('should reload orchestrators after start', fakeAsync(() => {
      orchestratorServiceSpy.getAllOrchestrators.calls.reset();
      component.startOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.getAllOrchestrators).toHaveBeenCalled();
    }));

    it('should set error on failure', fakeAsync(() => {
      orchestratorServiceSpy.startOrchestrator.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.startOrchestrator(mockOrchestrator);
      tick();
      expect(component.error).toContain('Failed to start orchestrator');
    }));
  });

  describe('pauseOrchestrator', () => {
    it('should call service and reload', fakeAsync(() => {
      component.pauseOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.pauseOrchestrator).toHaveBeenCalledWith('orch-1');
    }));
  });

  describe('resumeOrchestrator', () => {
    it('should call service and reload', fakeAsync(() => {
      component.resumeOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.resumeOrchestrator).toHaveBeenCalledWith('orch-1');
    }));
  });

  describe('stopOrchestrator', () => {
    it('should call service and reload', fakeAsync(() => {
      component.stopOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.stopOrchestrator).toHaveBeenCalledWith('orch-1');
    }));
  });

  describe('deleteOrchestrator', () => {
    it('should open confirm dialog', () => {
      component.deleteOrchestrator(mockOrchestrator);
      expect(dialogSpy.open).toHaveBeenCalled();
    });

    it('should delete when confirmed', fakeAsync(() => {
      dialogSpy.open.and.returnValue(mockConfirmDialogRef);
      component.deleteOrchestrator(mockOrchestrator);
      tick();
      expect(orchestratorServiceSpy.deleteOrchestrator).toHaveBeenCalledWith('orch-1');
    }));

    it('should clear selectedOrchestrator when deleting current', fakeAsync(() => {
      dialogSpy.open.and.returnValue(mockConfirmDialogRef);
      component.selectedOrchestrator = mockOrchestrator;
      component.deleteOrchestrator(mockOrchestrator);
      tick();
      expect(component.selectedOrchestrator).toBeNull();
    }));
  });

  describe('createOrchestrator', () => {
    it('should validate orchestrator name is required', () => {
      component.newOrchestratorName = '';
      component.selectedAgentName = 'claude';
      component.createOrchestrator();
      expect(component.error).toContain('name is required');
      expect(orchestratorServiceSpy.createOrchestrator).not.toHaveBeenCalled();
    });

    it('should validate agent is required', () => {
      component.newOrchestratorName = 'My Orch';
      component.selectedAgentName = '';
      component.createOrchestrator();
      expect(component.error).toContain('select an agent');
    });

    it('should create orchestrator when form is valid', fakeAsync(() => {
      component.newOrchestratorName = 'My Orch';
      component.selectedAgentName = 'claude';
      component.createOrchestrator();
      tick();
      expect(orchestratorServiceSpy.createOrchestrator).toHaveBeenCalled();
    }));

    it('should reset form after creation', fakeAsync(() => {
      component.newOrchestratorName = 'My Orch';
      component.selectedAgentName = 'claude';
      component.createOrchestrator();
      tick();
      expect(component.newOrchestratorName).toBe('');
    }));

    it('should set error on creation failure', fakeAsync(() => {
      component.newOrchestratorName = 'My Orch';
      component.selectedAgentName = 'claude';
      orchestratorServiceSpy.createOrchestrator.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.createOrchestrator();
      tick();
      expect(component.error).toContain('Failed to create orchestrator');
    }));
  });

  describe('resetCreateForm', () => {
    it('should reset all form fields', () => {
      component.newOrchestratorName = 'Name';
      component.enableRag = true;
      component.enableTools = true;
      component.selectedTools = ['tool1'];
      component.resetCreateForm();
      expect(component.newOrchestratorName).toBe('');
      expect(component.enableRag).toBeFalse();
      expect(component.enableTools).toBeFalse();
      expect(component.selectedTools).toEqual([]);
    });
  });

  describe('toggleTool', () => {
    it('should add tool if not selected', () => {
      component.selectedTools = [];
      component.toggleTool('file-read');
      expect(component.selectedTools).toContain('file-read');
    });

    it('should remove tool if already selected', () => {
      component.selectedTools = ['file-read'];
      component.toggleTool('file-read');
      expect(component.selectedTools).not.toContain('file-read');
    });
  });

  describe('isToolSelected', () => {
    it('should return true when tool is in selectedTools', () => {
      component.selectedTools = ['file-read'];
      expect(component.isToolSelected('file-read')).toBeTrue();
    });

    it('should return false when tool is not selected', () => {
      component.selectedTools = [];
      expect(component.isToolSelected('file-read')).toBeFalse();
    });
  });

  describe('getSelectedFactSheetName', () => {
    beforeEach(() => {
      component.factSheets = [mockFactSheet];
    });

    it('should return "None selected" when no fact sheet selected', () => {
      component.selectedFactSheetId = null;
      expect(component.getSelectedFactSheetName()).toBe('None selected');
    });

    it('should return fact sheet name when selected', () => {
      component.selectedFactSheetId = 1;
      expect(component.getSelectedFactSheetName()).toBe('Test Sheet');
    });
  });

  describe('loadTasks', () => {
    it('should not load if no orchestrator selected', () => {
      component.selectedOrchestrator = null;
      component.loadTasks();
      expect(orchestratorServiceSpy.getTaskHistory).not.toHaveBeenCalledWith(undefined as any);
    });

    it('should load task history', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      orchestratorServiceSpy.getTaskHistory.calls.reset();
      component.loadTasks();
      tick();
      expect(orchestratorServiceSpy.getTaskHistory).toHaveBeenCalledWith('orch-1');
      expect(component.tasks).toEqual([mockTask]);
    }));

    it('should set error on task load failure', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      orchestratorServiceSpy.getTaskHistory.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.loadTasks();
      tick();
      expect(component.error).toContain('Failed to load tasks');
    }));
  });

  describe('executeCommand', () => {
    it('should not execute if no orchestrator or empty command', () => {
      component.selectedOrchestrator = null;
      component.commandToExecute = 'ls';
      component.executeCommand();
      expect(orchestratorServiceSpy.executeCommand).not.toHaveBeenCalled();
    });

    it('should execute command and reload tasks', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.commandToExecute = 'ls -la';
      component.executeCommand();
      tick();
      expect(orchestratorServiceSpy.executeCommand).toHaveBeenCalledWith('orch-1', { command: 'ls -la' });
      expect(component.commandToExecute).toBe('');
    }));

    it('should set error on execute failure', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.commandToExecute = 'bad-cmd';
      orchestratorServiceSpy.executeCommand.and.returnValue(throwError(() => ({ message: 'fail' })));
      component.executeCommand();
      tick();
      expect(component.error).toContain('Failed to execute command');
    }));
  });

  describe('cancelTask', () => {
    it('should call cancelTask service', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.cancelTask(mockTask);
      tick();
      expect(orchestratorServiceSpy.cancelTask).toHaveBeenCalledWith('orch-1', 1);
    }));
  });

  describe('loadWorkflows', () => {
    it('should not load if no orchestrator selected', () => {
      component.selectedOrchestrator = null;
      orchestratorServiceSpy.getAllWorkflows.calls.reset();
      component.loadWorkflows();
      expect(orchestratorServiceSpy.getAllWorkflows).not.toHaveBeenCalled();
    });

    it('should load all and active workflows', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      orchestratorServiceSpy.getAllWorkflows.calls.reset();
      component.loadWorkflows();
      tick();
      expect(component.workflows).toEqual([mockWorkflow]);
    }));
  });

  describe('selectWorkflow', () => {
    it('should set selectedWorkflow and load steps', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.selectWorkflow(mockWorkflow);
      tick();
      expect(component.selectedWorkflow).toBe(mockWorkflow);
      expect(orchestratorServiceSpy.getWorkflowSteps).toHaveBeenCalled();
    }));
  });

  describe('startWorkflow', () => {
    it('should require name and prompt', () => {
      component.selectedOrchestrator = mockOrchestrator;
      component.newWorkflowName = '';
      component.newWorkflowPrompt = '';
      component.startWorkflow();
      expect(component.error).toContain('required');
    });

    it('should start workflow with valid data', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.newWorkflowName = 'Workflow 1';
      component.newWorkflowPrompt = 'Do something';
      component.startWorkflow();
      tick();
      expect(orchestratorServiceSpy.startWorkflow).toHaveBeenCalled();
    }));
  });

  describe('advanceWorkflow', () => {
    it('should call advanceWorkflow service', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.advanceWorkflow(mockWorkflow);
      tick();
      expect(orchestratorServiceSpy.advanceWorkflow).toHaveBeenCalledWith('orch-1', 1);
    }));
  });

  describe('approveStep', () => {
    it('should call approveWorkflowStep', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      component.selectedWorkflow = mockWorkflow;
      component.approveStep(mockWorkflowStep);
      tick();
      expect(orchestratorServiceSpy.approveWorkflowStep).toHaveBeenCalledWith('orch-1', 1, 1);
    }));
  });

  describe('cancelWorkflow', () => {
    it('should open confirm dialog', () => {
      component.selectedOrchestrator = mockOrchestrator;
      component.cancelWorkflow(mockWorkflow);
      expect(dialogSpy.open).toHaveBeenCalled();
    });

    it('should cancel when confirmed', fakeAsync(() => {
      component.selectedOrchestrator = mockOrchestrator;
      dialogSpy.open.and.returnValue(mockConfirmDialogRef);
      component.cancelWorkflow(mockWorkflow);
      tick();
      expect(orchestratorServiceSpy.cancelWorkflow).toHaveBeenCalledWith('orch-1', 1);
    }));
  });

  describe('getStatusClass', () => {
    it('should return status-running for RUNNING', () => {
      expect(component.getStatusClass('RUNNING')).toBe('status-running');
    });

    it('should return status-completed for COMPLETED', () => {
      expect(component.getStatusClass('COMPLETED')).toBe('status-completed');
    });

    it('should return status-paused for PAUSED', () => {
      expect(component.getStatusClass('PAUSED')).toBe('status-paused');
    });

    it('should return status-stopped for STOPPED', () => {
      expect(component.getStatusClass('STOPPED')).toBe('status-stopped');
    });

    it('should return status-error for ERROR', () => {
      expect(component.getStatusClass('ERROR')).toBe('status-error');
    });

    it('should return status-default for unknown', () => {
      expect(component.getStatusClass('UNKNOWN')).toBe('status-default');
    });
  });

  describe('clearError', () => {
    it('should set error to null', () => {
      component.error = 'some error';
      component.clearError();
      expect(component.error).toBeNull();
    });
  });

  describe('toggleTaskOutput', () => {
    it('should add taskId to expandedTaskOutputs', () => {
      component.toggleTaskOutput(5);
      expect(component.expandedTaskOutputs.has(5)).toBeTrue();
    });

    it('should remove taskId when already expanded', () => {
      component.expandedTaskOutputs.add(5);
      component.toggleTaskOutput(5);
      expect(component.expandedTaskOutputs.has(5)).toBeFalse();
    });
  });

  describe('toggleStepAnalysis', () => {
    it('should toggle step analysis visibility', () => {
      component.toggleStepAnalysis(3);
      expect(component.expandedStepAnalysis.has(3)).toBeTrue();
      component.toggleStepAnalysis(3);
      expect(component.expandedStepAnalysis.has(3)).toBeFalse();
    });
  });

  describe('toggleStepOutput', () => {
    it('should toggle step output visibility', () => {
      component.toggleStepOutput(7);
      expect(component.expandedStepOutputs.has(7)).toBeTrue();
      component.toggleStepOutput(7);
      expect(component.expandedStepOutputs.has(7)).toBeFalse();
    });
  });

  describe('parseErrorPatterns', () => {
    it('should return null for empty message', () => {
      expect(component.parseErrorPatterns('')).toBeNull();
    });

    it('should detect error pattern', () => {
      const result = component.parseErrorPatterns('Error: Something went wrong\n');
      expect(result).toBeTruthy();
      expect(result!.some(e => e.type === 'Error')).toBeTrue();
    });

    it('should detect timeout pattern', () => {
      const result = component.parseErrorPatterns('Connection timeout occurred');
      expect(result).toBeTruthy();
      expect(result!.some(e => e.type === 'Timeout')).toBeTrue();
    });

    it('should remove duplicate errors', () => {
      const msg = 'Error: fail\nError: fail\n';
      const result = component.parseErrorPatterns(msg);
      if (result) {
        const failErrors = result.filter(e => e.type === 'Error' && e.message === 'fail');
        expect(failErrors.length).toBe(1);
      }
    });
  });

  describe('onTaskDefinitionSaved', () => {
    it('should clear selectedTaskDefinition', () => {
      component.selectedTaskDefinition = { taskId: 'td-1', name: 'Task 1' } as TaskDefinition;
      component.onTaskDefinitionSaved({} as any);
      expect(component.selectedTaskDefinition).toBeNull();
    });
  });

  describe('onTaskDefinitionCancelled', () => {
    it('should clear selectedTaskDefinition', () => {
      component.selectedTaskDefinition = { taskId: 'td-1', name: 'Task 1' } as TaskDefinition;
      component.onTaskDefinitionCancelled();
      expect(component.selectedTaskDefinition).toBeNull();
    });
  });

  describe('editTaskDefinition', () => {
    it('should set selectedTaskDefinition and switch tab', () => {
      const taskDef = { taskId: 'td-1', name: 'Task 1' } as TaskDefinition;
      component.editTaskDefinition(taskDef);
      expect(component.selectedTaskDefinition).toBe(taskDef);
      expect(component.activeSubTab).toBe('orchestrator-detail');
      expect(component.orchestratorDetailTab).toBe('definitions');
    });
  });

  describe('workflow builder', () => {
    describe('addWorkflowStep', () => {
      it('should add prompt step', () => {
        component.addWorkflowStep('prompt');
        expect(component.workflowStepsBuilder.length).toBe(1);
        expect(component.workflowStepsBuilder[0].type).toBe('prompt');
      });

      it('should add tool step', () => {
        component.addWorkflowStep('tool');
        expect(component.workflowStepsBuilder[0].type).toBe('tool');
      });

      it('should add condition step', () => {
        component.addWorkflowStep('condition');
        expect(component.workflowStepsBuilder[0].type).toBe('condition');
      });

      it('should add parallel step', () => {
        component.addWorkflowStep('parallel');
        expect(component.workflowStepsBuilder[0].type).toBe('parallel');
        expect(component.workflowStepsBuilder[0].parallelSteps).toEqual([]);
      });

      it('should set editingStepIndex to last added', () => {
        component.addWorkflowStep('prompt');
        expect(component.editingStepIndex).toBe(0);
      });
    });

    describe('removeWorkflowStep', () => {
      it('should remove step at index', () => {
        component.addWorkflowStep('prompt');
        component.addWorkflowStep('tool');
        component.removeWorkflowStep(0);
        expect(component.workflowStepsBuilder.length).toBe(1);
        expect(component.workflowStepsBuilder[0].type).toBe('tool');
      });

      it('should not remove if index out of bounds', () => {
        component.addWorkflowStep('prompt');
        component.removeWorkflowStep(5);
        expect(component.workflowStepsBuilder.length).toBe(1);
      });

      it('should clean up references to removed step', () => {
        component.addWorkflowStep('condition');
        component.addWorkflowStep('prompt');
        const removedId = component.workflowStepsBuilder[1].id;
        component.workflowStepsBuilder[0].onSuccess = removedId;
        component.removeWorkflowStep(1);
        expect(component.workflowStepsBuilder[0].onSuccess).toBe('');
      });
    });

    describe('moveStepUp', () => {
      it('should swap step with previous', () => {
        component.addWorkflowStep('prompt');
        component.addWorkflowStep('tool');
        component.moveStepUp(1);
        expect(component.workflowStepsBuilder[0].type).toBe('tool');
        expect(component.workflowStepsBuilder[1].type).toBe('prompt');
      });

      it('should not move if already at top', () => {
        component.addWorkflowStep('prompt');
        component.addWorkflowStep('tool');
        component.moveStepUp(0);
        expect(component.workflowStepsBuilder[0].type).toBe('prompt');
      });
    });

    describe('moveStepDown', () => {
      it('should swap step with next', () => {
        component.addWorkflowStep('prompt');
        component.addWorkflowStep('tool');
        component.moveStepDown(0);
        expect(component.workflowStepsBuilder[0].type).toBe('tool');
        expect(component.workflowStepsBuilder[1].type).toBe('prompt');
      });

      it('should not move if at bottom', () => {
        component.addWorkflowStep('prompt');
        component.addWorkflowStep('tool');
        component.moveStepDown(1);
        expect(component.workflowStepsBuilder[1].type).toBe('tool');
      });
    });

    describe('toggleParallelStep', () => {
      it('should add stepId to parallel steps', () => {
        component.addWorkflowStep('parallel');
        const step = component.workflowStepsBuilder[0];
        component.toggleParallelStep(step, 'step-123');
        expect(step.parallelSteps).toContain('step-123');
      });

      it('should remove stepId if already present', () => {
        component.addWorkflowStep('parallel');
        const step = component.workflowStepsBuilder[0];
        step.parallelSteps = ['step-123'];
        component.toggleParallelStep(step, 'step-123');
        expect(step.parallelSteps).not.toContain('step-123');
      });
    });

    describe('clearWorkflowBuilder', () => {
      it('should clear steps and name', () => {
        component.addWorkflowStep('prompt');
        component.newWorkflowName = 'My Workflow';
        component.clearWorkflowBuilder();
        expect(component.workflowStepsBuilder).toEqual([]);
        expect(component.newWorkflowName).toBe('');
        expect(component.editingStepIndex).toBeNull();
      });
    });

    describe('startWorkflowFromBuilder', () => {
      it('should require name and at least one step', () => {
        component.selectedOrchestrator = mockOrchestrator;
        component.newWorkflowName = '';
        component.startWorkflowFromBuilder();
        expect(component.error).toContain('required');
      });

      it('should start workflow from builder steps', fakeAsync(() => {
        component.selectedOrchestrator = mockOrchestrator;
        component.newWorkflowName = 'Builder Workflow';
        component.addWorkflowStep('prompt');
        component.workflowStepsBuilder[0].prompt = 'Do task';
        component.startWorkflowFromBuilder();
        tick();
        expect(orchestratorServiceSpy.startWorkflow).toHaveBeenCalled();
      }));
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
