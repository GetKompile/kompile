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
import { ReactiveFormsModule, FormBuilder } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { StateMachineEditorComponent } from './state-machine-editor.component';
import { OrchestratorService } from '../../../../services/orchestrator.service';
import {
  StateDefinition,
  StateTransition,
  StateMachineConfig,
  StateCategory,
  TransitionConditionType
} from '../../../../models/orchestrator-models';

// Angular Material Modules
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

describe('StateMachineEditorComponent', () => {
  let component: StateMachineEditorComponent;
  let fixture: ComponentFixture<StateMachineEditorComponent>;
  let orchestratorServiceSpy: jasmine.SpyObj<OrchestratorService>;

  // Test data
  const mockStates: StateDefinition[] = [
    {
      stateId: 'initial',
      name: 'Initial State',
      description: 'The starting state',
      category: 'INITIAL',
      positionX: 100,
      positionY: 100,
      timeoutSeconds: 300,
      autoAdvance: false
    },
    {
      stateId: 'processing',
      name: 'Processing',
      description: 'Processing state',
      category: 'PROCESSING',
      positionX: 300,
      positionY: 100,
      timeoutSeconds: 600,
      autoAdvance: true
    },
    {
      stateId: 'completed',
      name: 'Completed',
      description: 'Terminal state',
      category: 'TERMINAL',
      positionX: 500,
      positionY: 100,
      timeoutSeconds: 0,
      autoAdvance: false
    },
    {
      stateId: 'error',
      name: 'Error',
      description: 'Error state',
      category: 'ERROR',
      positionX: 300,
      positionY: 250,
      timeoutSeconds: 0,
      autoAdvance: false
    }
  ];

  const mockTransitions: StateTransition[] = [
    {
      id: 1,
      orchestratorInstanceId: 'test-instance',
      fromStateId: 'initial',
      toStateId: 'processing',
      name: 'Start Processing',
      conditionType: 'ON_SUCCESS',
      autoTrigger: true,
      priority: 0,
      enabled: true
    },
    {
      id: 2,
      orchestratorInstanceId: 'test-instance',
      fromStateId: 'processing',
      toStateId: 'completed',
      name: 'Complete',
      conditionType: 'ON_SUCCESS',
      autoTrigger: true,
      priority: 0,
      enabled: true
    },
    {
      id: 3,
      orchestratorInstanceId: 'test-instance',
      fromStateId: 'processing',
      toStateId: 'error',
      name: 'On Error',
      conditionType: 'ON_FAILURE',
      autoTrigger: true,
      priority: 1,
      enabled: true
    }
  ];

  const mockConfig: StateMachineConfig = {
    instanceId: 'test-instance',
    states: mockStates,
    transitions: mockTransitions,
    stateCount: mockStates.length,
    transitionCount: mockTransitions.length
  };

  beforeEach(async () => {
    const spy = jasmine.createSpyObj('OrchestratorService', [
      'getStateMachineConfig',
      'createState',
      'updateState',
      'deleteState',
      'updateStatePositions',
      'createTransition',
      'updateTransition',
      'deleteTransition',
      'createDefaultStates',
      'exportStateMachineConfig'
    ]);

    await TestBed.configureTestingModule({
      declarations: [StateMachineEditorComponent],
      imports: [
        ReactiveFormsModule,
        NoopAnimationsModule,
        MatButtonModule,
        MatIconModule,
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        MatCheckboxModule,
        MatProgressSpinnerModule,
        MatTooltipModule
      ],
      providers: [
        FormBuilder,
        { provide: OrchestratorService, useValue: spy }
      ]
    }).compileComponents();

    orchestratorServiceSpy = TestBed.inject(OrchestratorService) as jasmine.SpyObj<OrchestratorService>;
  });

  beforeEach(() => {
    fixture = TestBed.createComponent(StateMachineEditorComponent);
    component = fixture.componentInstance;
    component.instanceId = 'test-instance';
    orchestratorServiceSpy.getStateMachineConfig.and.returnValue(of(mockConfig));
  });

  // ==================== Component Initialization ====================

  describe('Component Initialization', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialize with default values', () => {
      fixture.detectChanges();
      expect(component.nodes).toEqual([]);
      expect(component.edges).toEqual([]);
      expect(component.selectedNode).toBeNull();
      expect(component.selectedEdge).toBeNull();
      expect(component.loading).toBeFalse();
      expect(component.saving).toBeFalse();
      expect(component.editingState).toBeFalse();
      expect(component.editingTransition).toBeFalse();
    });

    it('should load state machine on init when instanceId is set', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getStateMachineConfig).toHaveBeenCalledWith('test-instance');
      expect(component.config).toEqual(mockConfig);
    }));

    it('should not load state machine if instanceId is empty', fakeAsync(() => {
      component.instanceId = '';
      fixture.detectChanges();
      tick();
      expect(orchestratorServiceSpy.getStateMachineConfig).not.toHaveBeenCalled();
    }));
  });

  // ==================== Form Initialization ====================

  describe('Form Initialization', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should initialize stateForm with correct fields', () => {
      expect(component.stateForm).toBeDefined();
      expect(component.stateForm.contains('stateId')).toBeTrue();
      expect(component.stateForm.contains('name')).toBeTrue();
      expect(component.stateForm.contains('description')).toBeTrue();
      expect(component.stateForm.contains('category')).toBeTrue();
      expect(component.stateForm.contains('timeoutSeconds')).toBeTrue();
      expect(component.stateForm.contains('autoAdvance')).toBeTrue();
      expect(component.stateForm.contains('polling')).toBeTrue();
      expect(component.stateForm.contains('pollingIntervalMs')).toBeTrue();
      expect(component.stateForm.contains('onEnterTaskId')).toBeTrue();
      expect(component.stateForm.contains('onExitTaskId')).toBeTrue();
    });

    it('should initialize transitionForm with correct fields', () => {
      expect(component.transitionForm).toBeDefined();
      expect(component.transitionForm.contains('name')).toBeTrue();
      expect(component.transitionForm.contains('description')).toBeTrue();
      expect(component.transitionForm.contains('conditionType')).toBeTrue();
      expect(component.transitionForm.contains('conditionExpression')).toBeTrue();
      expect(component.transitionForm.contains('autoTrigger')).toBeTrue();
      expect(component.transitionForm.contains('priority')).toBeTrue();
      expect(component.transitionForm.contains('onTransitionTaskId')).toBeTrue();
      expect(component.transitionForm.contains('label')).toBeTrue();
    });

    it('should have required validators on stateId and name', () => {
      const stateIdControl = component.stateForm.get('stateId');
      const nameControl = component.stateForm.get('name');

      stateIdControl?.setValue('');
      nameControl?.setValue('');

      expect(stateIdControl?.valid).toBeFalse();
      expect(nameControl?.valid).toBeFalse();

      stateIdControl?.setValue('valid_id');
      nameControl?.setValue('Valid Name');

      expect(stateIdControl?.valid).toBeTrue();
      expect(nameControl?.valid).toBeTrue();
    });

    it('should validate stateId pattern (alphanumeric with underscore and dash)', () => {
      const stateIdControl = component.stateForm.get('stateId');

      stateIdControl?.setValue('valid-id_123');
      expect(stateIdControl?.valid).toBeTrue();

      stateIdControl?.setValue('invalid id!');
      expect(stateIdControl?.valid).toBeFalse();

      stateIdControl?.setValue('with spaces');
      expect(stateIdControl?.valid).toBeFalse();
    });
  });

  // ==================== Visual Graph Building ====================

  describe('Visual Graph Building', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should build nodes from states', () => {
      expect(component.nodes.length).toBe(4);
      expect(component.nodes[0].state.stateId).toBe('initial');
      expect(component.nodes[1].state.stateId).toBe('processing');
      expect(component.nodes[2].state.stateId).toBe('completed');
      expect(component.nodes[3].state.stateId).toBe('error');
    });

    it('should position nodes based on positionX and positionY', () => {
      const initialNode = component.nodes.find(n => n.state.stateId === 'initial');
      expect(initialNode?.x).toBe(100);
      expect(initialNode?.y).toBe(100);

      const processingNode = component.nodes.find(n => n.state.stateId === 'processing');
      expect(processingNode?.x).toBe(300);
      expect(processingNode?.y).toBe(100);
    });

    it('should assign default dimensions to nodes', () => {
      expect(component.nodes[0].width).toBe(component.nodeWidth);
      expect(component.nodes[0].height).toBe(component.nodeHeight);
    });

    it('should build edges from transitions', () => {
      expect(component.edges.length).toBe(3);
    });

    it('should correctly link edges to from/to nodes', () => {
      const startProcessingEdge = component.edges.find(
        e => e.transition.name === 'Start Processing'
      );
      expect(startProcessingEdge?.fromNode.state.stateId).toBe('initial');
      expect(startProcessingEdge?.toNode.state.stateId).toBe('processing');
    });

    it('should initialize nodes and edges as unselected', () => {
      component.nodes.forEach(node => {
        expect(node.selected).toBeFalse();
        expect(node.dragging).toBeFalse();
      });
      component.edges.forEach(edge => {
        expect(edge.selected).toBeFalse();
      });
    });
  });

  // ==================== State Selection ====================

  describe('State Selection', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should select a node when clicked', () => {
      const node = component.nodes[0];
      const mockEvent = new MouseEvent('click');
      spyOn(mockEvent, 'stopPropagation');

      component.selectNode(node, mockEvent);

      expect(mockEvent.stopPropagation).toHaveBeenCalled();
      expect(node.selected).toBeTrue();
      expect(component.selectedNode).toBe(node);
      expect(component.selectedEdge).toBeNull();
    });

    it('should deselect other nodes when selecting a new node', () => {
      const node1 = component.nodes[0];
      const node2 = component.nodes[1];
      const mockEvent = new MouseEvent('click');

      component.selectNode(node1, mockEvent);
      expect(node1.selected).toBeTrue();

      component.selectNode(node2, mockEvent);
      expect(node1.selected).toBeFalse();
      expect(node2.selected).toBeTrue();
      expect(component.selectedNode).toBe(node2);
    });

    it('should select an edge when clicked', () => {
      const edge = component.edges[0];
      const mockEvent = new MouseEvent('click');
      spyOn(mockEvent, 'stopPropagation');

      component.selectEdge(edge, mockEvent);

      expect(mockEvent.stopPropagation).toHaveBeenCalled();
      expect(edge.selected).toBeTrue();
      expect(component.selectedEdge).toBe(edge);
      expect(component.selectedNode).toBeNull();
    });

    it('should deselect all when deselectAll is called', () => {
      component.selectNode(component.nodes[0], new MouseEvent('click'));
      expect(component.selectedNode).not.toBeNull();

      component.deselectAll();

      expect(component.selectedNode).toBeNull();
      expect(component.selectedEdge).toBeNull();
      component.nodes.forEach(n => expect(n.selected).toBeFalse());
      component.edges.forEach(e => expect(e.selected).toBeFalse());
    });

    it('should clear connectingFrom when deselecting all', () => {
      component.connectingFrom = component.nodes[0];
      component.deselectAll();
      expect(component.connectingFrom).toBeNull();
    });
  });

  // ==================== State CRUD Operations ====================

  describe('State CRUD Operations', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    describe('Create State', () => {
      it('should open state form for new state', () => {
        component.newState();

        expect(component.editingState).toBeTrue();
        expect(component.selectedNode).toBeNull();
        expect(component.stateForm.get('category')?.value).toBe('PROCESSING');
        expect(component.stateForm.get('timeoutSeconds')?.value).toBe(300);
      });

      it('should save new state and reload config', fakeAsync(() => {
        const newState: StateDefinition = {
          stateId: 'new_state',
          name: 'New State',
          category: 'PROCESSING',
          timeoutSeconds: 300,
          autoAdvance: false,
          polling: false,
          pollingIntervalMs: 5000,
          positionX: 200,
          positionY: 200
        };

        orchestratorServiceSpy.createState.and.returnValue(of(newState));
        orchestratorServiceSpy.getStateMachineConfig.and.returnValue(of({
          ...mockConfig,
          states: [...mockStates, newState]
        }));

        component.newState();
        component.stateForm.patchValue({
          stateId: 'new_state',
          name: 'New State',
          category: 'PROCESSING'
        });

        component.saveState();
        tick();

        expect(orchestratorServiceSpy.createState).toHaveBeenCalledWith(
          'test-instance',
          jasmine.objectContaining({
            stateId: 'new_state',
            name: 'New State',
            category: 'PROCESSING'
          })
        );
        expect(component.editingState).toBeFalse();
      }));

      it('should not save if form is invalid', () => {
        component.newState();
        component.stateForm.patchValue({
          stateId: '',
          name: ''
        });

        component.saveState();

        expect(orchestratorServiceSpy.createState).not.toHaveBeenCalled();
      });

      it('should show error on save failure', fakeAsync(() => {
        orchestratorServiceSpy.createState.and.returnValue(
          throwError(() => ({ error: { message: 'Save failed' } }))
        );

        component.newState();
        component.stateForm.patchValue({
          stateId: 'new_state',
          name: 'New State'
        });

        component.saveState();
        tick();

        expect(component.error).toContain('Failed to save state');
        expect(component.saving).toBeFalse();
      }));
    });

    describe('Edit State', () => {
      it('should populate form with selected state data', () => {
        const node = component.nodes[0];
        component.selectNode(node, new MouseEvent('click'));

        component.editState();

        expect(component.editingState).toBeTrue();
        expect(component.stateForm.get('stateId')?.value).toBe('initial');
        expect(component.stateForm.get('name')?.value).toBe('Initial State');
        expect(component.stateForm.get('category')?.value).toBe('INITIAL');
        expect(component.stateForm.get('description')?.value).toBe('The starting state');
      });

      it('should not edit if no node is selected', () => {
        component.selectedNode = null;
        component.editState();
        expect(component.editingState).toBeFalse();
      });

      it('should update state and reload config', fakeAsync(() => {
        const node = component.nodes[0];
        component.selectNode(node, new MouseEvent('click'));
        component.editState();

        const updatedState = { ...node.state, name: 'Updated Name' };
        orchestratorServiceSpy.updateState.and.returnValue(of(updatedState));

        component.stateForm.patchValue({ name: 'Updated Name' });
        component.saveState();
        tick();

        expect(orchestratorServiceSpy.updateState).toHaveBeenCalledWith(
          'test-instance',
          'initial',
          jasmine.objectContaining({ name: 'Updated Name' })
        );
      }));
    });

    describe('Delete State', () => {
      it('should delete state after confirmation', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteState.and.returnValue(of(void 0));

        const node = component.nodes[0];
        component.selectNode(node, new MouseEvent('click'));

        component.deleteState();
        tick();

        expect(orchestratorServiceSpy.deleteState).toHaveBeenCalledWith(
          'test-instance',
          'initial'
        );
        expect(component.selectedNode).toBeNull();
      }));

      it('should not delete if user cancels confirmation', () => {
        spyOn(window, 'confirm').and.returnValue(false);

        const node = component.nodes[0];
        component.selectNode(node, new MouseEvent('click'));

        component.deleteState();

        expect(orchestratorServiceSpy.deleteState).not.toHaveBeenCalled();
      });

      it('should not delete if no node is selected', () => {
        component.selectedNode = null;
        component.deleteState();
        expect(orchestratorServiceSpy.deleteState).not.toHaveBeenCalled();
      });

      it('should show error on delete failure', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteState.and.returnValue(
          throwError(() => ({ error: { message: 'Delete failed' } }))
        );

        component.selectNode(component.nodes[0], new MouseEvent('click'));
        component.deleteState();
        tick();

        expect(component.error).toContain('Failed to delete state');
      }));
    });

    describe('Cancel Edit State', () => {
      it('should close state editor without saving', () => {
        component.editingState = true;
        component.stateForm.patchValue({ name: 'Unsaved Changes' });

        component.cancelEditState();

        expect(component.editingState).toBeFalse();
        expect(orchestratorServiceSpy.createState).not.toHaveBeenCalled();
        expect(orchestratorServiceSpy.updateState).not.toHaveBeenCalled();
      });
    });
  });

  // ==================== Transition CRUD Operations ====================

  describe('Transition CRUD Operations', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    describe('Create Transition', () => {
      it('should enter connection mode when starting connection', () => {
        const node = component.nodes[0];
        const mockEvent = new MouseEvent('mousedown', { clientX: 100, clientY: 100 });
        spyOn(mockEvent, 'stopPropagation');

        // Mock SVG methods
        spyOn(component as any, 'getSvgPoint').and.returnValue({ x: 100, y: 100 });

        component.startConnection(node, mockEvent);

        expect(mockEvent.stopPropagation).toHaveBeenCalled();
        expect(component.connectingFrom).toBe(node);
      });

      it('should create transition when selecting target node in connection mode', fakeAsync(() => {
        const fromNode = component.nodes[0];
        const toNode = component.nodes[1];

        orchestratorServiceSpy.createTransition.and.returnValue(of({
          id: 4,
          orchestratorInstanceId: 'test-instance',
          fromStateId: 'initial',
          toStateId: 'processing',
          conditionType: 'ALWAYS',
          autoTrigger: true,
          enabled: true
        }));

        component.connectingFrom = fromNode;
        component.selectNode(toNode, new MouseEvent('click'));
        tick();

        expect(orchestratorServiceSpy.createTransition).toHaveBeenCalledWith(
          'test-instance',
          jasmine.objectContaining({
            fromStateId: 'initial',
            toStateId: 'processing',
            conditionType: 'ALWAYS'
          })
        );
        expect(component.connectingFrom).toBeNull();
      }));

      it('should not create self-transition', fakeAsync(() => {
        const node = component.nodes[0];
        component.connectingFrom = node;

        component.selectNode(node, new MouseEvent('click'));
        tick();

        expect(orchestratorServiceSpy.createTransition).not.toHaveBeenCalled();
      }));

      it('should show error on transition creation failure', fakeAsync(() => {
        orchestratorServiceSpy.createTransition.and.returnValue(
          throwError(() => ({ error: { message: 'Creation failed' } }))
        );

        component.connectingFrom = component.nodes[0];
        component.selectNode(component.nodes[1], new MouseEvent('click'));
        tick();

        expect(component.error).toContain('Failed to create transition');
      }));
    });

    describe('Edit Transition', () => {
      it('should populate form with selected transition data', () => {
        const edge = component.edges[0];
        component.selectEdge(edge, new MouseEvent('click'));

        component.editTransition();

        expect(component.editingTransition).toBeTrue();
        expect(component.transitionForm.get('name')?.value).toBe('Start Processing');
        expect(component.transitionForm.get('conditionType')?.value).toBe('ON_SUCCESS');
        expect(component.transitionForm.get('autoTrigger')?.value).toBeTrue();
      });

      it('should not edit if no edge is selected', () => {
        component.selectedEdge = null;
        component.editTransition();
        expect(component.editingTransition).toBeFalse();
      });

      it('should update transition and reload config', fakeAsync(() => {
        const edge = component.edges[0];
        component.selectEdge(edge, new MouseEvent('click'));
        component.editTransition();

        orchestratorServiceSpy.updateTransition.and.returnValue(of({
          ...edge.transition,
          name: 'Updated Transition'
        }));

        component.transitionForm.patchValue({ name: 'Updated Transition' });
        component.saveTransition();
        tick();

        expect(orchestratorServiceSpy.updateTransition).toHaveBeenCalledWith(
          'test-instance',
          1,
          jasmine.objectContaining({ name: 'Updated Transition' })
        );
      }));

      it('should not save if transition has no id', () => {
        const edge = component.edges[0];
        edge.transition.id = undefined;
        component.selectEdge(edge, new MouseEvent('click'));
        component.editTransition();

        component.saveTransition();

        expect(orchestratorServiceSpy.updateTransition).not.toHaveBeenCalled();
      });
    });

    describe('Delete Transition', () => {
      it('should delete transition after confirmation', fakeAsync(() => {
        spyOn(window, 'confirm').and.returnValue(true);
        orchestratorServiceSpy.deleteTransition.and.returnValue(of(void 0));

        const edge = component.edges[0];
        component.selectEdge(edge, new MouseEvent('click'));

        component.deleteTransition();
        tick();

        expect(orchestratorServiceSpy.deleteTransition).toHaveBeenCalledWith(
          'test-instance',
          1
        );
        expect(component.selectedEdge).toBeNull();
      }));

      it('should not delete if user cancels confirmation', () => {
        spyOn(window, 'confirm').and.returnValue(false);

        const edge = component.edges[0];
        component.selectEdge(edge, new MouseEvent('click'));

        component.deleteTransition();

        expect(orchestratorServiceSpy.deleteTransition).not.toHaveBeenCalled();
      });

      it('should not delete if transition has no id', () => {
        const edge = component.edges[0];
        edge.transition.id = undefined;
        component.selectEdge(edge, new MouseEvent('click'));

        component.deleteTransition();

        expect(orchestratorServiceSpy.deleteTransition).not.toHaveBeenCalled();
      });
    });

    describe('Cancel Edit Transition', () => {
      it('should close transition editor without saving', () => {
        component.editingTransition = true;
        component.transitionForm.patchValue({ name: 'Unsaved Changes' });

        component.cancelEditTransition();

        expect(component.editingTransition).toBeFalse();
        expect(orchestratorServiceSpy.updateTransition).not.toHaveBeenCalled();
      });
    });
  });

  // ==================== Default States ====================

  describe('Default States Creation', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should create default states and reload config', fakeAsync(() => {
      orchestratorServiceSpy.createDefaultStates.and.returnValue(of([]));

      component.createDefaultStates();
      tick();

      expect(orchestratorServiceSpy.createDefaultStates).toHaveBeenCalledWith('test-instance');
      expect(component.saving).toBeFalse();
    }));

    it('should show error on default states creation failure', fakeAsync(() => {
      orchestratorServiceSpy.createDefaultStates.and.returnValue(
        throwError(() => ({ error: { message: 'Creation failed' } }))
      );

      component.createDefaultStates();
      tick();

      expect(component.error).toContain('Failed to create default states');
      expect(component.saving).toBeFalse();
    }));
  });

  // ==================== Export Configuration ====================

  describe('Export Configuration', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should export config as JSON', fakeAsync(() => {
      orchestratorServiceSpy.exportStateMachineConfig.and.returnValue(of(mockConfig));

      // Mock URL and document methods
      const mockBlob = new Blob(['{}'], { type: 'application/json' });
      spyOn(URL, 'createObjectURL').and.returnValue('blob:mock');
      spyOn(URL, 'revokeObjectURL');

      const mockAnchor = document.createElement('a');
      spyOn(document, 'createElement').and.returnValue(mockAnchor);
      spyOn(mockAnchor, 'click');

      component.exportConfig();
      tick();

      expect(orchestratorServiceSpy.exportStateMachineConfig).toHaveBeenCalledWith('test-instance');
      expect(mockAnchor.download).toContain('state-machine-test-instance');
    }));

    it('should show error on export failure', fakeAsync(() => {
      orchestratorServiceSpy.exportStateMachineConfig.and.returnValue(
        throwError(() => ({ error: { message: 'Export failed' } }))
      );

      component.exportConfig();
      tick();

      expect(component.error).toContain('Failed to export');
    }));
  });

  // ==================== SVG Helper Methods ====================

  describe('SVG Helper Methods', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    describe('getNodeCenter', () => {
      it('should return center point of node', () => {
        const node = component.nodes[0];
        const center = component.getNodeCenter(node);

        expect(center.x).toBe(node.x + node.width / 2);
        expect(center.y).toBe(node.y + node.height / 2);
      });
    });

    describe('getEdgePath', () => {
      it('should return a valid SVG path string', () => {
        const edge = component.edges[0];
        const path = component.getEdgePath(edge);

        expect(path).toMatch(/^M\s+[\d.]+\s+[\d.]+\s+Q\s+[\d.]+\s+[\d.]+\s+[\d.]+\s+[\d.]+$/);
      });
    });

    describe('getArrowPath', () => {
      it('should return a valid SVG path for arrow', () => {
        const edge = component.edges[0];
        const path = component.getArrowPath(edge);

        expect(path).toMatch(/^M\s+[\d.-]+\s+[\d.-]+\s+L\s+[\d.-]+\s+[\d.-]+\s+L\s+[\d.-]+\s+[\d.-]+\s+Z$/);
      });
    });
  });

  // ==================== Color Mapping ====================

  describe('Color Mapping', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    describe('getCategoryColor', () => {
      it('should return correct colors for each category', () => {
        expect(component.getCategoryColor('INITIAL')).toBe('#4caf50');
        expect(component.getCategoryColor('PROCESSING')).toBe('#2196f3');
        expect(component.getCategoryColor('WAITING')).toBe('#ff9800');
        expect(component.getCategoryColor('TERMINAL')).toBe('#9c27b0');
        expect(component.getCategoryColor('ERROR')).toBe('#f44336');
      });

      it('should return default color for unknown category', () => {
        expect(component.getCategoryColor('UNKNOWN' as StateCategory)).toBe('#757575');
      });
    });

    describe('getConditionColor', () => {
      it('should return correct colors for each condition type', () => {
        expect(component.getConditionColor('ON_SUCCESS')).toBe('#4caf50');
        expect(component.getConditionColor('ON_FAILURE')).toBe('#f44336');
        expect(component.getConditionColor('ALWAYS')).toBe('#2196f3');
        expect(component.getConditionColor('MANUAL')).toBe('#ff9800');
      });

      it('should return default color for unknown condition type', () => {
        expect(component.getConditionColor('UNKNOWN' as TransitionConditionType)).toBe('#757575');
      });
    });
  });

  // ==================== Error Handling ====================

  describe('Error Handling', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should display error message when error is set', () => {
      component.error = 'Test error message';
      expect(component.error).toBe('Test error message');
    });

    it('should clear error when clearError is called', () => {
      component.error = 'Test error message';
      component.clearError();
      expect(component.error).toBeNull();
    });

    it('should handle load error gracefully', fakeAsync(() => {
      orchestratorServiceSpy.getStateMachineConfig.and.returnValue(
        throwError(() => ({ error: { message: 'Load failed' } }))
      );

      component.loadStateMachine();
      tick();

      expect(component.error).toContain('Failed to load state machine');
      expect(component.loading).toBeFalse();
    }));
  });

  // ==================== Position Saving ====================

  describe('Position Saving', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should save node position after drag ends', fakeAsync(() => {
      orchestratorServiceSpy.updateStatePositions.and.returnValue(of({}));

      const node = component.nodes[0];
      node.x = 250;
      node.y = 150;

      // Simulate drag end
      component.draggingNode = node;
      node.dragging = true;
      component.onMouseUp(new MouseEvent('mouseup'));
      tick();

      expect(orchestratorServiceSpy.updateStatePositions).toHaveBeenCalledWith(
        'test-instance',
        [{
          stateId: 'initial',
          x: 250,
          y: 150
        }]
      );
    }));

    it('should show error on position save failure', fakeAsync(() => {
      orchestratorServiceSpy.updateStatePositions.and.returnValue(
        throwError(() => ({ error: { message: 'Save position failed' } }))
      );

      const node = component.nodes[0];
      component.draggingNode = node;
      node.dragging = true;
      component.onMouseUp(new MouseEvent('mouseup'));
      tick();

      expect(component.error).toContain('Failed to save position');
    }));
  });

  // ==================== State Categories and Condition Types ====================

  describe('Available Options', () => {
    beforeEach(() => {
      fixture.detectChanges();
    });

    it('should have all state categories available', () => {
      expect(component.stateCategories).toEqual([
        'INITIAL', 'PROCESSING', 'WAITING', 'TERMINAL', 'ERROR'
      ]);
    });

    it('should have all condition types available', () => {
      expect(component.conditionTypes).toEqual([
        'ALWAYS', 'ON_SUCCESS', 'ON_FAILURE', 'PATTERN_MATCH',
        'CLASSIFICATION', 'EXPRESSION', 'MANUAL'
      ]);
    });
  });

  // ==================== Compliance: State Machine Invariants ====================

  describe('State Machine Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should have at least one INITIAL state in a valid config', () => {
      const initialStates = component.nodes.filter(n => n.state.category === 'INITIAL');
      expect(initialStates.length).toBeGreaterThanOrEqual(1);
    });

    it('should have at least one TERMINAL state in a valid config', () => {
      const terminalStates = component.nodes.filter(n => n.state.category === 'TERMINAL');
      expect(terminalStates.length).toBeGreaterThanOrEqual(1);
    });

    it('should have all transitions with valid from/to state references', () => {
      const stateIds = component.nodes.map(n => n.state.stateId);

      component.edges.forEach(edge => {
        expect(stateIds).toContain(edge.transition.fromStateId);
        expect(stateIds).toContain(edge.transition.toStateId);
      });
    });

    it('should have unique state IDs', () => {
      const stateIds = component.nodes.map(n => n.state.stateId);
      const uniqueIds = new Set(stateIds);
      expect(uniqueIds.size).toBe(stateIds.length);
    });

    it('should have each transition with a valid condition type', () => {
      const validConditionTypes = component.conditionTypes;

      component.edges.forEach(edge => {
        expect(validConditionTypes).toContain(edge.transition.conditionType);
      });
    });

    it('should ensure ERROR state exists for proper error handling', () => {
      const errorStates = component.nodes.filter(n => n.state.category === 'ERROR');
      expect(errorStates.length).toBeGreaterThanOrEqual(1);
    });

    it('should have transitions from non-terminal states', () => {
      const nonTerminalStates = component.nodes.filter(
        n => n.state.category !== 'TERMINAL' && n.state.category !== 'ERROR'
      );

      nonTerminalStates.forEach(state => {
        const outgoingTransitions = component.edges.filter(
          e => e.transition.fromStateId === state.state.stateId
        );
        // Each non-terminal state should have at least one outgoing transition
        // or be an auto-advance state
        expect(
          outgoingTransitions.length > 0 || state.state.autoAdvance
        ).toBeTrue(`State ${state.state.stateId} has no outgoing transitions`);
      });
    });

    it('should have reachable states from INITIAL', () => {
      const initialState = component.nodes.find(n => n.state.category === 'INITIAL');
      expect(initialState).toBeDefined();

      // BFS to find reachable states
      const reachable = new Set<string>();
      const queue = [initialState!.state.stateId];

      while (queue.length > 0) {
        const current = queue.shift()!;
        if (reachable.has(current)) continue;
        reachable.add(current);

        const outgoing = component.edges
          .filter(e => e.transition.fromStateId === current)
          .map(e => e.transition.toStateId);

        queue.push(...outgoing);
      }

      // All states should be reachable (except possibly orphaned error states)
      const unreachable = component.nodes.filter(
        n => !reachable.has(n.state.stateId) && n.state.category !== 'ERROR'
      );

      expect(unreachable.length).toBe(0,
        `Unreachable states: ${unreachable.map(n => n.state.stateId).join(', ')}`
      );
    });
  });

  // ==================== Transition Compliance Tests ====================

  describe('Transition Compliance', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should not allow transitions from TERMINAL states', () => {
      const terminalStates = component.nodes.filter(n => n.state.category === 'TERMINAL');
      terminalStates.forEach(terminal => {
        const outgoing = component.edges.filter(
          e => e.transition.fromStateId === terminal.state.stateId
        );
        expect(outgoing.length).toBe(0,
          `TERMINAL state ${terminal.state.stateId} should not have outgoing transitions`
        );
      });
    });

    it('should have valid priority values for transitions', () => {
      component.edges.forEach(edge => {
        const priority = edge.transition.priority ?? 0;
        expect(typeof priority).toBe('number');
        expect(priority).toBeGreaterThanOrEqual(0);
      });
    });

    it('should have enabled status defined for all transitions', () => {
      component.edges.forEach(edge => {
        expect(edge.transition.enabled).toBeDefined();
      });
    });

    it('should have ON_SUCCESS and ON_FAILURE transitions from PROCESSING states', () => {
      const processingStates = component.nodes.filter(n => n.state.category === 'PROCESSING');

      processingStates.forEach(processing => {
        const outgoing = component.edges.filter(
          e => e.transition.fromStateId === processing.state.stateId
        );

        const hasSuccess = outgoing.some(e => e.transition.conditionType === 'ON_SUCCESS');
        const hasFailure = outgoing.some(e => e.transition.conditionType === 'ON_FAILURE');

        // Either should have both success/failure handling or use ALWAYS
        const hasAlways = outgoing.some(e => e.transition.conditionType === 'ALWAYS');

        expect(
          (hasSuccess && hasFailure) || hasAlways || processing.state.autoAdvance
        ).toBeTrue(
          `PROCESSING state ${processing.state.stateId} should handle success/failure`
        );
      });
    });

    it('should allow EXPRESSION condition type to have expression defined', () => {
      const expressionTransitions = component.edges.filter(
        e => e.transition.conditionType === 'EXPRESSION'
      );

      expressionTransitions.forEach(edge => {
        // Expression transitions should have a condition expression
        // This is a soft validation - expressions may be configured later
        if (edge.transition.conditionExpression) {
          expect(edge.transition.conditionExpression.length).toBeGreaterThan(0);
        }
      });
    });

    it('should allow PATTERN_MATCH condition type to have expression defined', () => {
      const patternTransitions = component.edges.filter(
        e => e.transition.conditionType === 'PATTERN_MATCH'
      );

      patternTransitions.forEach(edge => {
        // Pattern match transitions should have a pattern
        if (edge.transition.conditionExpression) {
          expect(edge.transition.conditionExpression.length).toBeGreaterThan(0);
        }
      });
    });
  });
});
