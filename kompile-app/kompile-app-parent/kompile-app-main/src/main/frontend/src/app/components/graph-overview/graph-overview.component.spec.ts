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
import { ReactiveFormsModule, FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { GraphOverviewComponent } from './graph-overview.component';
import { GraphService } from '../../services/graph.service';
import { NamedGraph } from '../../models/graph-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test data helpers
// ═══════════════════════════════════════════════════════════════════════════════

function makeGraph(overrides: Partial<NamedGraph> = {}): NamedGraph {
  return {
    graphId: 'graph-1',
    name: 'Graph One',
    description: 'First graph',
    nodeCount: 10,
    edgeCount: 5,
    childGraphCount: 0,
    ...overrides
  };
}

const mockRootGraph: NamedGraph = makeGraph();
const mockChildGraph: NamedGraph = makeGraph({
  graphId: 'child-1',
  name: 'Child Graph',
  nodeCount: 2,
  edgeCount: 1,
  childGraphCount: 0
});
const mockGraphWithChildren: NamedGraph = makeGraph({
  graphId: 'parent-1',
  name: 'Parent Graph',
  childGraphCount: 2
});

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('GraphOverviewComponent', () => {
  let component: GraphOverviewComponent;
  let fixture: ComponentFixture<GraphOverviewComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj('GraphService', [
      'getNamedGraphs',
      'getNamedGraph',
      'createNamedGraph',
      'updateNamedGraph',
      'deleteNamedGraph',
      'getChildGraphs',
      'moveGraph'
    ]);
    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);

    // Default happy-path stubs
    graphServiceSpy.getNamedGraphs.and.returnValue(of([mockRootGraph]));
    graphServiceSpy.createNamedGraph.and.returnValue(of(mockRootGraph));
    graphServiceSpy.deleteNamedGraph.and.returnValue(of(void 0));
    graphServiceSpy.getChildGraphs.and.returnValue(of([mockChildGraph]));
    graphServiceSpy.moveGraph.and.returnValue(of(mockRootGraph));

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(GraphOverviewComponent, {
      set: {
        imports: [CommonModule, FormsModule, ReactiveFormsModule],
        template: '<div></div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(GraphService, { useValue: graphServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(GraphOverviewComponent);
    component = fixture.componentInstance;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. COMPONENT CREATION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialise loading to false before ngOnInit', () => {
      expect(component.loading).toBe(false);
    });

    it('should initialise showCreateForm to false', () => {
      expect(component.showCreateForm).toBe(false);
    });

    it('should initialise movingGraph to null', () => {
      expect(component.movingGraph).toBeNull();
    });

    it('should initialise selectedGraph to null', () => {
      expect(component.selectedGraph).toBeNull();
    });

    it('should initialise searchQuery to empty string', () => {
      expect(component.searchQuery).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. ngOnInit — loadGraphs
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnInit() — loadGraphs', () => {
    it('should call getNamedGraphs on init', () => {
      fixture.detectChanges();
      expect(graphServiceSpy.getNamedGraphs).toHaveBeenCalledTimes(1);
    });

    it('should populate dataSource with root graphs (no parentGraphId)', () => {
      graphServiceSpy.getNamedGraphs.and.returnValue(of([mockRootGraph]));
      fixture.detectChanges();
      expect(component.dataSource.data.length).toBe(1);
      expect(component.dataSource.data[0].graphId).toBe('graph-1');
    });

    it('should filter out non-root graphs from dataSource', () => {
      const childWithParent: NamedGraph = makeGraph({
        graphId: 'has-parent',
        name: 'Has Parent',
        parentGraphId: 'some-root'
      });
      graphServiceSpy.getNamedGraphs.and.returnValue(of([mockRootGraph, childWithParent]));
      fixture.detectChanges();
      // Only the root (no parentGraphId) should appear at top level
      expect(component.dataSource.data.length).toBe(1);
      expect(component.dataSource.data[0].graphId).toBe('graph-1');
    });

    it('should populate allGraphIds from every returned graph', () => {
      graphServiceSpy.getNamedGraphs.and.returnValue(of([mockRootGraph, mockChildGraph]));
      fixture.detectChanges();
      expect(component.allGraphIds).toContain('graph-1');
      expect(component.allGraphIds).toContain('child-1');
    });

    it('should set loading to false after successful load', () => {
      fixture.detectChanges();
      expect(component.loading).toBe(false);
    });

    it('should set loading to false and empty dataSource on error', () => {
      graphServiceSpy.getNamedGraphs.and.returnValue(throwError(() => new Error('fail')));
      fixture.detectChanges();
      expect(component.loading).toBe(false);
      expect(component.dataSource.data.length).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. SEARCH
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onSearchChange()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should call getNamedGraphs with the current searchQuery', () => {
      component.searchQuery = 'test';
      component.onSearchChange();
      expect(graphServiceSpy.getNamedGraphs).toHaveBeenCalledWith('test');
    });

    it('should call getNamedGraphs with undefined when query is empty', () => {
      component.searchQuery = '';
      component.onSearchChange();
      // The second call (after init) should pass undefined
      const calls = graphServiceSpy.getNamedGraphs.calls.allArgs();
      const lastCall = calls[calls.length - 1];
      expect(lastCall[0]).toBeUndefined();
    });

    it('should call getNamedGraphs with undefined when query is whitespace only', () => {
      component.searchQuery = '   ';
      component.onSearchChange();
      const calls = graphServiceSpy.getNamedGraphs.calls.allArgs();
      const lastCall = calls[calls.length - 1];
      expect(lastCall[0]).toBeUndefined();
    });

    it('should strip leading/trailing whitespace from query', () => {
      component.searchQuery = '  medical  ';
      component.onSearchChange();
      expect(graphServiceSpy.getNamedGraphs).toHaveBeenCalledWith('medical');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. viewGraph — graphSelected EventEmitter
  // ─────────────────────────────────────────────────────────────────────────────

  describe('viewGraph()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should emit graphSelected when viewGraph is called', () => {
      spyOn(component.graphSelected, 'emit');
      component.viewGraph(mockRootGraph);
      expect(component.graphSelected.emit).toHaveBeenCalledWith(mockRootGraph);
    });

    it('should emit the exact graph object passed in', () => {
      const emitted: NamedGraph[] = [];
      component.graphSelected.subscribe((g: NamedGraph) => emitted.push(g));
      component.viewGraph(mockRootGraph);
      expect(emitted.length).toBe(1);
      expect(emitted[0]).toBe(mockRootGraph);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. toggleCreateForm
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleCreateForm()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should set showCreateForm to true on first call', () => {
      expect(component.showCreateForm).toBe(false);
      component.toggleCreateForm();
      expect(component.showCreateForm).toBe(true);
    });

    it('should toggle showCreateForm back to false on second call', () => {
      component.toggleCreateForm();
      component.toggleCreateForm();
      expect(component.showCreateForm).toBe(false);
    });

    it('should reset createForm when hiding', () => {
      component.toggleCreateForm(); // show
      component.createForm.get('name')!.setValue('My Graph');
      component.toggleCreateForm(); // hide — should reset
      expect(component.createForm.get('name')!.value).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. submitCreateForm
  // ─────────────────────────────────────────────────────────────────────────────

  describe('submitCreateForm()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should not call createNamedGraph when form is invalid (empty name)', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('');
      component.submitCreateForm();
      // Only the ngOnInit call should have happened
      expect(graphServiceSpy.createNamedGraph).not.toHaveBeenCalled();
    });

    it('should call createNamedGraph with the form values', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('Medical Ontology');
      component.createForm.get('description')!.setValue('A medical ontology');
      component.submitCreateForm();
      expect(graphServiceSpy.createNamedGraph).toHaveBeenCalledWith(
        jasmine.objectContaining({ name: 'Medical Ontology', description: 'A medical ontology' })
      );
    });

    it('should trim whitespace from name before submitting', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('  Test Graph  ');
      component.submitCreateForm();
      expect(graphServiceSpy.createNamedGraph).toHaveBeenCalledWith(
        jasmine.objectContaining({ name: 'Test Graph' })
      );
    });

    it('should hide the create form and reload graphs on success', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('New Graph');
      graphServiceSpy.createNamedGraph.and.returnValue(of(mockRootGraph));
      const initialCallCount = graphServiceSpy.getNamedGraphs.calls.count();
      component.submitCreateForm();
      expect(component.showCreateForm).toBe(false);
      expect(graphServiceSpy.getNamedGraphs.calls.count()).toBeGreaterThan(initialCallCount);
    });

    it('should show a snackbar with the created graph name on success', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('Graph One');
      graphServiceSpy.createNamedGraph.and.returnValue(of(mockRootGraph));
      component.submitCreateForm();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        `Graph "${mockRootGraph.name}" created`,
        'Dismiss',
        { duration: 3000 }
      );
    });

    it('should show error snackbar and not close form on failure', () => {
      component.showCreateForm = true;
      component.createForm.get('name')!.setValue('Fail Graph');
      graphServiceSpy.createNamedGraph.and.returnValue(throwError(() => new Error('error')));
      component.submitCreateForm();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create graph', 'Dismiss', { duration: 3000 });
      expect(component.creating).toBe(false);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. deleteGraph
  // ─────────────────────────────────────────────────────────────────────────────

  describe('deleteGraph()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should call deleteNamedGraph when user confirms deletion', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.deleteGraph(mockRootGraph, event);
      expect(graphServiceSpy.deleteNamedGraph).toHaveBeenCalledWith('graph-1');
    });

    it('should not call deleteNamedGraph when user cancels', () => {
      spyOn(window, 'confirm').and.returnValue(false);
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.deleteGraph(mockRootGraph, event);
      expect(graphServiceSpy.deleteNamedGraph).not.toHaveBeenCalled();
    });

    it('should clear selectedGraph if it matches the deleted graph', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.selectedGraph = mockRootGraph;
      component.deleteGraph(mockRootGraph, event);
      expect(component.selectedGraph).toBeNull();
    });

    it('should show success snackbar after deletion', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.deleteGraph(mockRootGraph, event);
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        `Graph "${mockRootGraph.name}" deleted`,
        'Dismiss',
        { duration: 3000 }
      );
    });

    it('should show error snackbar on delete failure', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      graphServiceSpy.deleteNamedGraph.and.returnValue(throwError(() => new Error('fail')));
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.deleteGraph(mockRootGraph, event);
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete graph', 'Dismiss', { duration: 3000 });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. startMove / cancelMove
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startMove()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should set movingGraph when startMove is called', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.startMove(mockRootGraph, event);
      expect(component.movingGraph).toBe(mockRootGraph);
    });

    it('should set moveTargetId to the graph parentGraphId when present', () => {
      const graphWithParent: NamedGraph = makeGraph({ graphId: 'g-2', parentGraphId: 'parent-x' });
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.startMove(graphWithParent, event);
      expect(component.moveTargetId).toBe('parent-x');
    });

    it('should set moveTargetId to empty string when graph has no parent', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.startMove(mockRootGraph, event);
      expect(component.moveTargetId).toBe('');
    });
  });

  describe('cancelMove()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should clear movingGraph', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.startMove(mockRootGraph, event);
      expect(component.movingGraph).toBe(mockRootGraph);
      component.cancelMove();
      expect(component.movingGraph).toBeNull();
    });

    it('should clear moveTargetId', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      component.startMove(mockRootGraph, event);
      component.moveTargetId = 'some-target';
      component.cancelMove();
      expect(component.moveTargetId).toBe('');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 9. toggleNode — lazy-load children
  // ─────────────────────────────────────────────────────────────────────────────

  describe('toggleNode() — lazy-load children', () => {
    beforeEach(() => fixture.detectChanges());

    it('should call getChildGraphs when node has childGraphCount > 0 and no loaded children', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      // Node is collapsed (default) and has children to load
      component.toggleNode(mockGraphWithChildren, event);
      expect(graphServiceSpy.getChildGraphs).toHaveBeenCalledWith('parent-1');
    });

    it('should assign loaded children to the node', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      graphServiceSpy.getChildGraphs.and.returnValue(of([mockChildGraph]));
      component.toggleNode(mockGraphWithChildren, event);
      expect(mockGraphWithChildren.childGraphs).toEqual([mockChildGraph]);
    });

    it('should not call getChildGraphs when node has no children (childGraphCount === 0)', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      // Leaf node — childGraphCount is 0
      component.toggleNode(mockRootGraph, event);
      expect(graphServiceSpy.getChildGraphs).not.toHaveBeenCalled();
    });

    it('should not call getChildGraphs when children are already loaded', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      const alreadyLoaded: NamedGraph = makeGraph({
        graphId: 'loaded-parent',
        childGraphCount: 1,
        childGraphs: [mockChildGraph]
      });
      component.toggleNode(alreadyLoaded, event);
      expect(graphServiceSpy.getChildGraphs).not.toHaveBeenCalled();
    });

    it('should still expand the node even when getChildGraphs errors', () => {
      const event = new MouseEvent('click');
      spyOn(event, 'stopPropagation');
      graphServiceSpy.getChildGraphs.and.returnValue(throwError(() => new Error('fail')));
      // Should not throw
      expect(() => component.toggleNode(mockGraphWithChildren, event)).not.toThrow();
    });
  });
});
