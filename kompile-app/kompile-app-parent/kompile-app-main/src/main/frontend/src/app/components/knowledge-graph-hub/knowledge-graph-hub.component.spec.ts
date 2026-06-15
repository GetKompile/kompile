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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BehaviorSubject } from 'rxjs';

import { KnowledgeGraphHubComponent } from './knowledge-graph-hub.component';
import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphNode, NamedGraph } from '../../models/graph-models';

// ═══════════════════════════════════════════════════════════════════════════════
// Test data helpers
// ═══════════════════════════════════════════════════════════════════════════════

const mockFactSheet: FactSheet = {
  id: 1,
  name: 'Test Fact Sheet',
  description: null,
  isActive: true,
  derivedFromId: null,
  color: '#667eea',
  icon: 'description',
  vectorStorePath: null,
  keywordIndexPath: null,
  embeddingModel: null,
  embeddingModelSource: null,
  embeddingArchiveId: null,
  indexedWithModel: null,
  indexedAt: null,
  needsReindex: false,
  rerankingEnabled: false,
} as FactSheet;

const mockGraphNode: GraphNode = {
  id: 1,
  nodeId: 'node-1',
  nodeType: 'ENTITY',
  title: 'Test Entity',
  childCount: 0,
  edgeCount: 0
};

const mockNamedGraph: NamedGraph = {
  graphId: 'named-graph-1',
  name: 'Medical Ontology',
  nodeCount: 20,
  edgeCount: 15,
  childGraphCount: 3
};

function createTestBed() {
  const activeSheetSubject = new BehaviorSubject<FactSheet | null>(null);
  const factSheetServiceStub = {
    activeSheet$: activeSheetSubject.asObservable()
  };
  const snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
  return { activeSheetSubject, factSheetServiceStub, snackBarSpy };
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('KnowledgeGraphHubComponent', () => {
  let component: KnowledgeGraphHubComponent;
  let fixture: ComponentFixture<KnowledgeGraphHubComponent>;
  let deps: ReturnType<typeof createTestBed>;

  beforeEach(async () => {
    deps = createTestBed();

    await TestBed.configureTestingModule({
      imports: [NoopAnimationsModule]
    })
    .overrideComponent(KnowledgeGraphHubComponent, {
      set: {
        imports: [CommonModule, FormsModule],
        template: '<div>{{activeTab}}</div>',
        schemas: [NO_ERRORS_SCHEMA]
      }
    })
    .overrideProvider(FactSheetService, { useValue: deps.factSheetServiceStub })
    .overrideProvider(MatSnackBar, { useValue: deps.snackBarSpy })
    .compileComponents();

    fixture = TestBed.createComponent(KnowledgeGraphHubComponent);
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

    it('should default activeTab to "visualizer"', () => {
      expect(component.activeTab).toBe('visualizer');
    });

    it('should default activeFactSheet to null', () => {
      expect(component.activeFactSheet).toBeNull();
    });

    it('should default focusedNodeId to null', () => {
      expect(component.focusedNodeId).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. TAB SWITCHING — setActiveTab()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('setActiveTab()', () => {
    beforeEach(() => fixture.detectChanges());

    it('should switch to "visualizer" tab', () => {
      component.setActiveTab('browser');
      component.setActiveTab('visualizer');
      expect(component.activeTab).toBe('visualizer');
    });

    it('should switch to "browser" tab', () => {
      component.setActiveTab('browser');
      expect(component.activeTab).toBe('browser');
    });

    it('should switch to "hierarchy" tab', () => {
      component.setActiveTab('hierarchy');
      expect(component.activeTab).toBe('hierarchy');
    });

    it('should switch to "builder" tab', () => {
      component.setActiveTab('builder');
      expect(component.activeTab).toBe('builder');
    });

    it('should switch to "graphs" tab', () => {
      component.setActiveTab('graphs');
      expect(component.activeTab).toBe('graphs');
    });

    it('should be idempotent when setting the same tab twice', () => {
      component.setActiveTab('visualizer');
      component.setActiveTab('visualizer');
      expect(component.activeTab).toBe('visualizer');
    });

    it('should not affect focusedNodeId when switching tabs', () => {
      component.focusedNodeId = 'some-node';
      component.setActiveTab('browser');
      expect(component.focusedNodeId).toBe('some-node');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. onHierarchyNavigateToGraph()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onHierarchyNavigateToGraph()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.activeTab = 'hierarchy';
    });

    it('should switch activeTab to "visualizer"', () => {
      component.onHierarchyNavigateToGraph({ id: '123', label: 'Root' });
      expect(component.activeTab).toBe('visualizer');
    });

    it('should set focusedNodeId to node.id', () => {
      component.onHierarchyNavigateToGraph({ id: '123', label: 'Root' });
      expect(component.focusedNodeId).toBe('123');
    });

    it('should fall back to node.nodeId when node.id is falsy', () => {
      component.onHierarchyNavigateToGraph({ id: '', nodeId: 'fallback-id', label: 'Node' });
      expect(component.focusedNodeId).toBe('fallback-id');
    });

    it('should open a snackbar showing the node label', () => {
      component.onHierarchyNavigateToGraph({ id: '123', label: 'Root Node' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching('Root Node'),
        'Dismiss',
        { duration: 2000 }
      );
    });

    it('should use node.title in snackbar when label is absent', () => {
      component.onHierarchyNavigateToGraph({ id: '456', title: 'Entity Title' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching('Entity Title'),
        'Dismiss',
        { duration: 2000 }
      );
    });

    it('should update focusedNodeId when called multiple times', () => {
      component.onHierarchyNavigateToGraph({ id: 'first' });
      component.onHierarchyNavigateToGraph({ id: 'second' });
      expect(component.focusedNodeId).toBe('second');
    });

    it('should not throw when node has only an id', () => {
      expect(() => component.onHierarchyNavigateToGraph({ id: 'bare' })).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. onHierarchyViewSubGraph()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onHierarchyViewSubGraph()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.activeTab = 'hierarchy';
    });

    it('should switch activeTab to "visualizer"', () => {
      component.onHierarchyViewSubGraph({ id: 'node-x', subGraphId: 'sub-1', label: 'X' });
      expect(component.activeTab).toBe('visualizer');
    });

    it('should set focusedNodeId to node.subGraphId when present', () => {
      component.onHierarchyViewSubGraph({ id: 'node-x', subGraphId: 'sub-1', label: 'X' });
      expect(component.focusedNodeId).toBe('sub-1');
    });

    it('should fall back to node.id when subGraphId is falsy', () => {
      component.onHierarchyViewSubGraph({ id: 'node-x', label: 'No SubGraph' });
      expect(component.focusedNodeId).toBe('node-x');
    });

    it('should fall back to node.nodeId when both subGraphId and id are falsy', () => {
      component.onHierarchyViewSubGraph({ nodeId: 'only-node-id', label: 'Node' });
      expect(component.focusedNodeId).toBe('only-node-id');
    });

    it('should open a snackbar mentioning the node label', () => {
      component.onHierarchyViewSubGraph({ id: 'comp-1', subGraphId: 'sub-1', label: 'Composite Org' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching('Composite Org'),
        'Dismiss',
        { duration: 2000 }
      );
    });

    it('should use node.title in snackbar when label is absent', () => {
      component.onHierarchyViewSubGraph({ id: 'comp-2', subGraphId: 'sub-2', title: 'Org Title' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching('Org Title'),
        'Dismiss',
        { duration: 2000 }
      );
    });

    it('should overwrite an existing focusedNodeId', () => {
      component.focusedNodeId = 'old-node';
      component.onHierarchyViewSubGraph({ id: 'new-id', subGraphId: 'new-sub' });
      expect(component.focusedNodeId).toBe('new-sub');
    });

    it('should not throw when node has neither title nor label', () => {
      expect(() => component.onHierarchyViewSubGraph({ id: 'bare', subGraphId: 'bare-sub' })).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. onNamedGraphSelected()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('onNamedGraphSelected()', () => {
    beforeEach(() => {
      fixture.detectChanges();
      component.activeTab = 'graphs';
    });

    it('should switch activeTab to "visualizer"', () => {
      component.onNamedGraphSelected(mockNamedGraph);
      expect(component.activeTab).toBe('visualizer');
    });

    it('should open a snackbar with the graph name', () => {
      component.onNamedGraphSelected(mockNamedGraph);
      expect(deps.snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching('Medical Ontology'),
        'Dismiss',
        { duration: 2000 }
      );
    });

    it('should not throw when called with a minimal NamedGraph', () => {
      const minimal: NamedGraph = {
        graphId: 'min-1',
        name: 'Minimal',
        nodeCount: 0,
        edgeCount: 0,
        childGraphCount: 0
      };
      expect(() => component.onNamedGraphSelected(minimal)).not.toThrow();
    });

    it('should switch to visualizer even from the "builder" tab', () => {
      component.activeTab = 'builder';
      component.onNamedGraphSelected(mockNamedGraph);
      expect(component.activeTab).toBe('visualizer');
    });

    it('should call snackBar exactly once per invocation', () => {
      component.onNamedGraphSelected(mockNamedGraph);
      expect(deps.snackBarSpy.open).toHaveBeenCalledTimes(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. activeSheet$ SUBSCRIPTION
  // ─────────────────────────────────────────────────────────────────────────────

  describe('activeSheet$ subscription', () => {
    it('should set activeFactSheet when activeSheet$ emits a FactSheet', () => {
      fixture.detectChanges();
      deps.activeSheetSubject.next(mockFactSheet);
      expect(component.activeFactSheet).toEqual(mockFactSheet);
    });

    it('should set activeFactSheet to null when activeSheet$ emits null', () => {
      fixture.detectChanges();
      deps.activeSheetSubject.next(mockFactSheet);
      deps.activeSheetSubject.next(null);
      expect(component.activeFactSheet).toBeNull();
    });

    it('should reflect the latest emission when the subject emits multiple times', () => {
      fixture.detectChanges();
      deps.activeSheetSubject.next(mockFactSheet);
      const second: FactSheet = { ...mockFactSheet, id: 2, name: 'Second Sheet' };
      deps.activeSheetSubject.next(second);
      expect(component.activeFactSheet!.id).toBe(2);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 7. ngOnDestroy()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy()', () => {
    it('should not throw when destroyed', () => {
      fixture.detectChanges();
      expect(() => fixture.destroy()).not.toThrow();
    });

    it('should stop receiving activeSheet$ updates after destroy', () => {
      fixture.detectChanges();
      deps.activeSheetSubject.next(mockFactSheet);
      expect(component.activeFactSheet).toBe(mockFactSheet);
      fixture.destroy();
      deps.activeSheetSubject.next(null);
      // State is preserved after destroy — no update occurs
      expect(component.activeFactSheet).toBe(mockFactSheet);
    });

    it('should complete the internal destroy$ subject without errors', () => {
      fixture.detectChanges();
      expect(() => component.ngOnDestroy()).not.toThrow();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 8. COMBINED SCENARIOS
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Combined interaction scenarios', () => {
    beforeEach(() => fixture.detectChanges());

    it('should navigate from hierarchy to visualizer via onHierarchyNavigateToGraph', () => {
      component.setActiveTab('hierarchy');
      component.onHierarchyNavigateToGraph({ id: '42', label: 'Root' });
      expect(component.activeTab).toBe('visualizer');
      expect(component.focusedNodeId).toBe('42');
    });

    it('should view sub-graph from hierarchy via onHierarchyViewSubGraph', () => {
      component.setActiveTab('hierarchy');
      component.onHierarchyViewSubGraph({ id: 'cmp', subGraphId: 'sub-x', label: 'Composite' });
      expect(component.activeTab).toBe('visualizer');
      expect(component.focusedNodeId).toBe('sub-x');
    });

    it('should switch to visualizer from graphs tab via onNamedGraphSelected', () => {
      component.setActiveTab('graphs');
      component.onNamedGraphSelected(mockNamedGraph);
      expect(component.activeTab).toBe('visualizer');
    });

    it('should preserve focusedNodeId after calling setActiveTab manually', () => {
      component.onHierarchyNavigateToGraph({ id: 'node-99' });
      expect(component.focusedNodeId).toBe('node-99');
      component.setActiveTab('browser');
      expect(component.focusedNodeId).toBe('node-99');
    });

    it('should call snackBar exactly once per onHierarchyNavigateToGraph call', () => {
      component.onHierarchyNavigateToGraph({ id: 'a', label: 'A' });
      component.onHierarchyNavigateToGraph({ id: 'b', label: 'B' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledTimes(2);
    });

    it('should call snackBar exactly once per onHierarchyViewSubGraph call', () => {
      component.onHierarchyViewSubGraph({ id: 'c', subGraphId: 'sub-c', label: 'C' });
      expect(deps.snackBarSpy.open).toHaveBeenCalledTimes(1);
    });

    it('should call snackBar exactly once per onNamedGraphSelected call', () => {
      component.onNamedGraphSelected(mockNamedGraph);
      expect(deps.snackBarSpy.open).toHaveBeenCalledTimes(1);
    });

    it('should reflect the active fact sheet name from the service', () => {
      deps.activeSheetSubject.next(mockFactSheet);
      expect(component.activeFactSheet!.name).toBe('Test Fact Sheet');
    });
  });
});
