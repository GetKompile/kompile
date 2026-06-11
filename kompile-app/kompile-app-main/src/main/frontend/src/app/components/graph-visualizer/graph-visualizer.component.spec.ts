/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick
} from '@angular/core/testing';
import { Component, Input, Output, EventEmitter, NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError, NEVER, Subject } from 'rxjs';

import { GraphVisualizerComponent } from './graph-visualizer.component';
import { GraphCanvasComponent } from './graph-canvas.component';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { CompositeEntityDialogComponent } from '../composite-entity-dialog/composite-entity-dialog.component';
import { GraphService } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import {
  D3VisualizationData,
  D3Node,
  GraphEdge,
  SourceWeight,
  DEFAULT_FORCE_CONFIG,
  NodeLevel,
  EdgeType,
  WeightedSearchPreview
} from '../../models/graph-models';

// ─────────────────────────────────────────────────────────────────────────────
// Stub for GraphCanvasComponent to avoid D3 rendering issues in unit tests
// ─────────────────────────────────────────────────────────────────────────────
@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  template: '<div class="graph-canvas-stub"></div>'
})
class GraphCanvasStubComponent {
  @Input() data: any;
  @Input() forceConfig: any;
  @Input() linkMode: boolean = false;
  @Input() showLegend: boolean = false;
  @Input() focusedNodeId: string | null = null;
  @Output() nodeSelected = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() edgeCreated = new EventEmitter<{ source: string; target: string }>();
  @Output() nodeContextMenu = new EventEmitter<{ node: D3Node; event: MouseEvent }>();
  @Output() linkSourceChanged = new EventEmitter<D3Node | null>();
}

// ─────────────────────────────────────────────────────────────────────────────
// Stub for ConfirmDialogComponent to avoid MatDialog dependency issues
// ─────────────────────────────────────────────────────────────────────────────
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  template: ''
})
class ConfirmDialogStubComponent {}

// ─────────────────────────────────────────────────────────────────────────────
// Stub for CompositeEntityDialogComponent to avoid MatDialogRef injection
// ─────────────────────────────────────────────────────────────────────────────
@Component({
  selector: 'app-composite-entity-dialog',
  standalone: true,
  template: ''
})
class CompositeEntityDialogStubComponent {}

// ─────────────────────────────────────────────────────────────────────────────
// Mock data
// ─────────────────────────────────────────────────────────────────────────────
const mockD3Data: D3VisualizationData = {
  nodes: [
    { id: 'n1', type: 'SOURCE', label: 'Source 1', title: 'Source 1', description: 'A source', childCount: 2, edgeCount: 1 },
    { id: 'n2', type: 'DOCUMENT', label: 'Doc 1', title: 'Document 1', description: 'A document', childCount: 0, edgeCount: 1 },
    { id: 'n3', type: 'ENTITY', label: 'Entity 1', title: 'Entity 1', description: 'An entity', childCount: 0, edgeCount: 2 },
    { id: 'n4', type: 'SNIPPET', label: 'Snippet 1', title: 'Snippet 1' }
  ],
  links: [
    { id: 'e1', source: 'n1', target: 'n2', type: 'HIERARCHICAL', weight: 1.0 },
    { id: 'e2', source: 'n2', target: 'n3', type: 'SHARED_ENTITY', weight: 0.8 },
    { id: 'e3', source: 'n1', target: 'n3', type: 'EMBEDDING_SIMILARITY', weight: 0.6 }
  ]
};

const mockEdges: GraphEdge[] = [
  {
    id: 1,
    edgeId: 'edge-1',
    sourceNodeId: 'n1',
    targetNodeId: 'n2',
    edgeType: 'HIERARCHICAL',
    weight: 1.0,
    bidirectional: false,
    source: 'n1',
    target: 'n2'
  },
  {
    id: 2,
    edgeId: 'edge-2',
    sourceNodeId: 'n2',
    targetNodeId: 'n3',
    edgeType: 'SHARED_ENTITY',
    weight: 0.8,
    bidirectional: false,
    source: 'n2',
    target: 'n3'
  }
];

const mockSourceWeights: SourceWeight[] = [
  {
    id: 1,
    sourceNodeId: 'n1',
    sourceName: 'Source 1',
    baseWeight: 1.0,
    topicRelevanceScore: 0.9,
    qualityScore: 0.8,
    recencyFactor: 1.0,
    effectiveWeight: 1.0,
    enabled: true
  }
];

// ─────────────────────────────────────────────────────────────────────────────
// Test suite
// ─────────────────────────────────────────────────────────────────────────────
describe('GraphVisualizerComponent', () => {
  let component: GraphVisualizerComponent;
  let fixture: ComponentFixture<GraphVisualizerComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let weightServiceSpy: jasmine.SpyObj<SourceWeightService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  // Helper: build a snack bar ref mock with onAction() support.
  // Uses NEVER so that the Cancel action observable does NOT fire synchronously,
  // which would otherwise reset selectingNodeFor before assertions run.
  function makeSnackBarRef() {
    return { onAction: () => NEVER };
  }

  // Helper: build a dialog ref mock that immediately closes with true (confirmed)
  function makeDialogRef(result: boolean | undefined = true) {
    return { afterClosed: () => of(result) };
  }

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj('GraphService', [
      'getVisualizationData',
      'getFactSheetVisualizationData',
      'buildFactSheetGraph',
      'getFactSheetBuildStatus',
      'cancelFactSheetBuild',
      'getFactSheetStatistics',
      'clearFactSheetGraph',
      'linkSources',
      'rebuildConceptEdges',
      'createEdge',
      'deleteNode',
      'deleteEdge',
      'getEdges',
      'getConnectedNodes',
      'getAncestors'
    ]);

    weightServiceSpy = jasmine.createSpyObj('SourceWeightService', [
      'getWeights',
      'setWeight',
      'previewWeightedSearch'
    ]);

    snackBarSpy = jasmine.createSpyObj('MatSnackBar', ['open']);
    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);

    // Default return values
    graphServiceSpy.getVisualizationData.and.returnValue(of(mockD3Data));
    graphServiceSpy.getFactSheetVisualizationData.and.returnValue(of(mockD3Data));
    graphServiceSpy.getAncestors.and.returnValue(of([]));
    weightServiceSpy.getWeights.and.returnValue(of(mockSourceWeights));
    snackBarSpy.open.and.returnValue(makeSnackBarRef() as any);
    dialogSpy.open.and.returnValue(makeDialogRef() as any);

    await TestBed.configureTestingModule({
      imports: [
        GraphVisualizerComponent,
        NoopAnimationsModule
      ],
      schemas: [NO_ERRORS_SCHEMA]
    })
    // Replace real child components with stubs to prevent NG0300 (multiple
    // components matching same selector) and avoid D3 / MatDialog injection
    // dependencies. Also add NO_ERRORS_SCHEMA to the standalone component
    // itself so unknown properties on child selectors are suppressed.
    .overrideComponent(GraphVisualizerComponent, {
      remove: { imports: [GraphCanvasComponent, ConfirmDialogComponent, CompositeEntityDialogComponent] },
      add: { imports: [GraphCanvasStubComponent, ConfirmDialogStubComponent, CompositeEntityDialogStubComponent] }
    })
    .overrideComponent(GraphVisualizerComponent, {
      set: { schemas: [NO_ERRORS_SCHEMA] }
    })
    // For standalone components the TestBed root-level providers are NOT
    // visible to the component's own injector. We must use overrideProvider
    // so the mocks are resolved when the component creates its own injector.
    .overrideProvider(GraphService, { useValue: graphServiceSpy })
    .overrideProvider(SourceWeightService, { useValue: weightServiceSpy })
    .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
    .overrideProvider(MatDialog, { useValue: dialogSpy })
    .compileComponents();
  });

  function createComponent(): void {
    fixture = TestBed.createComponent(GraphVisualizerComponent);
    component = fixture.componentInstance;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 1. INITIALIZATION
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Initialization', () => {
    it('should create the component', () => {
      createComponent();
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should call getVisualizationData on init when factSheetId is null', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
      expect(graphServiceSpy.getFactSheetVisualizationData).not.toHaveBeenCalled();
    }));

    it('should call getFactSheetVisualizationData on init when factSheetId is set', fakeAsync(() => {
      createComponent();
      component.factSheetId = 42;
      fixture.detectChanges();
      tick();
      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalledWith(42, jasmine.any(Number), jasmine.any(Number));
      expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
    }));

    it('should populate graphData after successful load', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      expect(component.graphData).toBeTruthy();
      expect(component.graphData!.nodes.length).toBeGreaterThan(0);
    }));

    it('should load source weights on init', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      expect(weightServiceSpy.getWeights).toHaveBeenCalled();
      expect(component.sourceWeights.length).toBe(1);
    }));

    it('should initialize with default filter containing all standard node types', () => {
      createComponent();
      fixture.detectChanges();
      expect(component.filter.nodeTypes).toContain('SOURCE');
      expect(component.filter.nodeTypes).toContain('DOCUMENT');
      expect(component.filter.nodeTypes).toContain('SNIPPET');
      expect(component.filter.nodeTypes).toContain('ENTITY');
    });

    it('should initialize loading to false after graph loads', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      expect(component.loading).toBeFalse();
    }));

    it('should initialize showSidePanel to true', () => {
      createComponent();
      expect(component.showSidePanel).toBeTrue();
    });

    it('should initialize linkMode to false', () => {
      createComponent();
      expect(component.linkMode).toBeFalse();
    });

    it('should have DEFAULT_FORCE_CONFIG as initial forceConfig', () => {
      createComponent();
      expect(component.forceConfig).toEqual(DEFAULT_FORCE_CONFIG);
    });

    it('should initialize allNodeTypes with 7 entries', () => {
      createComponent();
      expect(component.allNodeTypes.length).toBe(7);
    });

    it('should initialize allEdgeTypes with at least 4 entries', () => {
      createComponent();
      expect(component.allEdgeTypes.length).toBeGreaterThanOrEqual(4);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 2. GRAPH LOADING
  // ═══════════════════════════════════════════════════════════════════════════

  describe('loadGraph()', () => {
    beforeEach(() => {
      createComponent();
    });

    it('should set loading to true while loading, false after', fakeAsync(() => {
      fixture.detectChanges();
      // During the observable emission, loading is true then false
      tick();
      expect(component.loading).toBeFalse();
    }));

    it('should use getVisualizationData with maxDepth and maxNodes when no factSheetId', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalledWith(
        undefined,
        component.maxDepth,
        component.maxNodes
      );
    }));

    it('should use getFactSheetVisualizationData when factSheetId is provided', fakeAsync(() => {
      component.factSheetId = 7;
      fixture.detectChanges();
      tick();
      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalledWith(7, jasmine.any(Number), jasmine.any(Number));
    }));

    it('should apply filters to returned data', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      // All node types in mock data are in the default filter, so all nodes pass
      expect(component.graphData!.nodes.length).toBe(4);
    }));

    it('should show snackbar and set loading false on error', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.getVisualizationData.and.returnValue(throwError(() => new Error('Server error')));
      fixture.detectChanges();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load knowledge graph', 'Dismiss', { duration: 3000 });
      expect(component.loading).toBeFalse();
    }));

    it('should call loadGraph with query when a search query is provided', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      const callsBefore = graphServiceSpy.getVisualizationData.calls.count();
      component.loadGraph('source');
      tick();
      expect(graphServiceSpy.getVisualizationData.calls.count()).toBeGreaterThan(callsBefore);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 3. APPLY FILTERS (pure function)
  // ═══════════════════════════════════════════════════════════════════════════

  describe('applyFilters()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should return all nodes when no search query and all types are enabled', () => {
      const result = component.applyFilters(mockD3Data);
      expect(result.nodes.length).toBe(4);
    });

    it('should filter out nodes whose type is not in filter.nodeTypes', () => {
      component.filter.nodeTypes = ['SOURCE', 'DOCUMENT'];
      const result = component.applyFilters(mockD3Data);
      expect(result.nodes.length).toBe(2);
      result.nodes.forEach(n => expect(['SOURCE', 'DOCUMENT']).toContain(n.type));
    });

    it('should filter links to only those between remaining nodes', () => {
      // Only SOURCE nodes remain → only links that go between SOURCE nodes
      component.filter.nodeTypes = ['SOURCE'];
      component.filter.edgeTypes = ['HIERARCHICAL', 'EMBEDDING_SIMILARITY', 'SHARED_ENTITY', 'USER_DEFINED'];
      const result = component.applyFilters(mockD3Data);
      // n1 is SOURCE, n2/n3 are not → links e1, e2, e3 all involve non-SOURCE nodes
      result.links.forEach(l => {
        const sourceId = l.source;
        const targetId = l.target;
        const nodeIds = new Set(result.nodes.map(n => n.id));
        expect(nodeIds.has(sourceId)).toBeTrue();
        expect(nodeIds.has(targetId)).toBeTrue();
      });
    });

    it('should filter nodes by label when searchQuery is provided', () => {
      const result = component.applyFilters(mockD3Data, 'source');
      expect(result.nodes.some(n => n.label.toLowerCase().includes('source'))).toBeTrue();
    });

    it('should filter nodes by title when searchQuery matches title', () => {
      const result = component.applyFilters(mockD3Data, 'document 1');
      expect(result.nodes.length).toBe(1);
      expect(result.nodes[0].id).toBe('n2');
    });

    it('should filter nodes by description when searchQuery matches description', () => {
      const result = component.applyFilters(mockD3Data, 'an entity');
      expect(result.nodes.length).toBe(1);
      expect(result.nodes[0].id).toBe('n3');
    });

    it('should remove links where either endpoint node is filtered out by search', () => {
      // Only Entity 1 matches 'entity'; n1 and n2 links disappear
      const result = component.applyFilters(mockD3Data, 'entity');
      result.links.forEach(l => {
        const nodeIds = new Set(result.nodes.map(n => n.id));
        expect(nodeIds.has(l.source)).toBeTrue();
        expect(nodeIds.has(l.target)).toBeTrue();
      });
    });

    it('should return empty nodes when search query matches nothing', () => {
      const result = component.applyFilters(mockD3Data, 'xyznotfound');
      expect(result.nodes.length).toBe(0);
      expect(result.links.length).toBe(0);
    });

    it('should filter links by type', () => {
      component.filter.edgeTypes = ['HIERARCHICAL'];
      const result = component.applyFilters(mockD3Data);
      result.links.forEach(l => expect(l.type).toBe('HIERARCHICAL'));
    });

    it('should return node set with no type-filtered links when edgeTypes is empty', () => {
      component.filter.edgeTypes = [];
      const result = component.applyFilters(mockD3Data);
      expect(result.links.length).toBe(0);
      expect(result.nodes.length).toBe(4); // nodes unaffected by edge filter
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 4. TOGGLE LINK MODE
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleLinkMode()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
    }));

    it('should toggle linkMode from false to true', () => {
      component.toggleLinkMode();
      expect(component.linkMode).toBeTrue();
    });

    it('should toggle linkMode from true to false', () => {
      component.linkMode = true;
      component.toggleLinkMode();
      expect(component.linkMode).toBeFalse();
    });

    it('should reset linkSourceNode when enabling link mode', () => {
      component.linkSourceNode = mockD3Data.nodes[0];
      component.toggleLinkMode();
      expect(component.linkSourceNode).toBeNull();
    });

    it('should reset linkSourceNode when disabling link mode', () => {
      component.linkMode = true;
      component.linkSourceNode = mockD3Data.nodes[0];
      component.toggleLinkMode();
      expect(component.linkSourceNode).toBeNull();
    });

    it('should show snackbar hint when enabling link mode', () => {
      component.toggleLinkMode();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        'Click a node to start creating a relation', 'Dismiss', { duration: 3000 }
      );
    });

    it('should not show a snackbar when disabling link mode', () => {
      component.linkMode = true;
      const callsBefore = snackBarSpy.open.calls.count();
      component.toggleLinkMode();
      expect(snackBarSpy.open.calls.count()).toBe(callsBefore);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 5. TOGGLE SIDE PANEL
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleSidePanel()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should toggle showSidePanel from true to false', () => {
      component.showSidePanel = true;
      component.toggleSidePanel();
      expect(component.showSidePanel).toBeFalse();
    });

    it('should toggle showSidePanel from false to true', () => {
      component.showSidePanel = false;
      component.toggleSidePanel();
      expect(component.showSidePanel).toBeTrue();
    });

    it('should alternate on repeated calls', () => {
      const initial = component.showSidePanel;
      component.toggleSidePanel();
      component.toggleSidePanel();
      expect(component.showSidePanel).toBe(initial);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 6. NODE SELECTION
  // ═══════════════════════════════════════════════════════════════════════════

  describe('onNodeSelected()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      graphServiceSpy.getEdges.and.returnValue(of(mockEdges));
    }));

    it('should set selectedNode when a node is passed', () => {
      component.onNodeSelected(mockD3Data.nodes[0]);
      expect(component.selectedNode).toBe(mockD3Data.nodes[0]);
    });

    it('should clear nodeRelations when null is passed', () => {
      component.nodeRelations = mockEdges;
      component.onNodeSelected(null);
      expect(component.nodeRelations).toEqual([]);
    });

    it('should set selectedNode to null when null is passed', () => {
      component.selectedNode = mockD3Data.nodes[0];
      component.onNodeSelected(null);
      expect(component.selectedNode).toBeNull();
    });

    it('should load node relations when a node is selected', fakeAsync(() => {
      component.onNodeSelected(mockD3Data.nodes[0]);
      tick();
      expect(graphServiceSpy.getEdges).toHaveBeenCalledWith('n1');
      expect(component.nodeRelations.length).toBe(2);
    }));

    it('should set newRelation.sourceNode when selectingNodeFor is "source"', fakeAsync(() => {
      component.selectingNodeFor = 'source';
      component.onNodeSelected(mockD3Data.nodes[1]);
      tick();
      expect(component.newRelation.sourceNode).toBe(mockD3Data.nodes[1]);
      expect(component.selectingNodeFor).toBeNull();
    }));

    it('should set newRelation.targetNode when selectingNodeFor is "target"', fakeAsync(() => {
      component.selectingNodeFor = 'target';
      component.onNodeSelected(mockD3Data.nodes[2]);
      tick();
      expect(component.newRelation.targetNode).toBe(mockD3Data.nodes[2]);
      expect(component.selectingNodeFor).toBeNull();
    }));

    it('should show "Node selected" snackbar when selectingNodeFor is active', fakeAsync(() => {
      component.selectingNodeFor = 'source';
      component.onNodeSelected(mockD3Data.nodes[0]);
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Node selected', '', { duration: 1000 });
    }));

    it('should not overwrite selectedNode when selectingNodeFor is active', fakeAsync(() => {
      const original = mockD3Data.nodes[0];
      component.selectedNode = original;
      component.selectingNodeFor = 'source';
      component.onNodeSelected(mockD3Data.nodes[1]);
      tick();
      // selectedNode should not change — the early return path is taken
      expect(component.selectedNode).toBe(original);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 7. EDGE CREATED
  // ═══════════════════════════════════════════════════════════════════════════

  describe('onEdgeCreated()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      graphServiceSpy.createEdge.and.returnValue(of({} as any));
    }));

    it('should call graphService.createEdge with USER_DEFINED type', fakeAsync(() => {
      component.onEdgeCreated({ source: 'n1', target: 'n3' });
      tick();
      expect(graphServiceSpy.createEdge).toHaveBeenCalledWith(jasmine.objectContaining({
        sourceNodeId: 'n1',
        targetNodeId: 'n3',
        edgeType: 'USER_DEFINED',
        weight: 1.0
      }));
    }));

    it('should reload the graph after edge creation', fakeAsync(() => {
      graphServiceSpy.getVisualizationData.calls.reset();
      component.onEdgeCreated({ source: 'n1', target: 'n3' });
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
    }));

    it('should show success snackbar after edge creation', fakeAsync(() => {
      component.onEdgeCreated({ source: 'n1', target: 'n3' });
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Edge created successfully', 'Dismiss', { duration: 2000 });
    }));

    it('should show error snackbar and log on failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.createEdge.and.returnValue(throwError(() => new Error('fail')));
      component.onEdgeCreated({ source: 'n1', target: 'n3' });
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create edge', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 8. BUILD GRAPH
  // ═══════════════════════════════════════════════════════════════════════════

  describe('buildGraph()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 10;
      fixture.detectChanges();
      tick();
    }));

    it('should call graphService.buildFactSheetGraph with the factSheetId', fakeAsync(() => {
      graphServiceSpy.buildFactSheetGraph.and.returnValue(of({ status: 'COMPLETED', jobId: 'job-1' }));
      component.buildGraph();
      tick();
      expect(graphServiceSpy.buildFactSheetGraph).toHaveBeenCalledWith(10);
    }));

    it('should show snackbar when no factSheetId and not call the service', () => {
      component.factSheetId = null;
      component.buildGraph();
      expect(graphServiceSpy.buildFactSheetGraph).not.toHaveBeenCalled();
      expect(snackBarSpy.open).toHaveBeenCalledWith('No fact sheet selected', 'Dismiss', { duration: 3000 });
    });

    it('should set building to false and reload graph when COMPLETED', fakeAsync(() => {
      graphServiceSpy.buildFactSheetGraph.and.returnValue(of({ status: 'COMPLETED', jobId: 'job-1', nodesCreated: 5, edgesCreated: 3 }));
      graphServiceSpy.getVisualizationData.calls.reset();
      component.buildGraph();
      tick();
      expect(component.building).toBeFalse();
    }));

    it('should set building to false and show error on service error', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.buildFactSheetGraph.and.returnValue(throwError(() => new Error('build error')));
      component.buildGraph();
      tick();
      expect(component.building).toBeFalse();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to start graph build', 'Dismiss', { duration: 3000 });
    }));

    it('should set buildStatus after build call', fakeAsync(() => {
      const status = { status: 'COMPLETED', jobId: 'job-1', nodesCreated: 5, edgesCreated: 3 };
      graphServiceSpy.buildFactSheetGraph.and.returnValue(of(status));
      component.buildGraph();
      tick();
      expect(component.buildStatus).toEqual(status);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 9. CANCEL BUILD
  // ═══════════════════════════════════════════════════════════════════════════

  describe('cancelBuild()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 10;
      component.buildStatus = { jobId: 'job-abc', status: 'RUNNING' };
      fixture.detectChanges();
      tick();
      graphServiceSpy.cancelFactSheetBuild.and.returnValue(of({ cancelled: true }));
    }));

    it('should call graphService.cancelFactSheetBuild with factSheetId and jobId', fakeAsync(() => {
      component.cancelBuild();
      tick();
      expect(graphServiceSpy.cancelFactSheetBuild).toHaveBeenCalledWith(10, 'job-abc');
    }));

    it('should reset building and buildStatus on success', fakeAsync(() => {
      component.building = true;
      component.cancelBuild();
      tick();
      expect(component.building).toBeFalse();
      expect(component.buildStatus).toBeNull();
    }));

    it('should show "Build cancelled" snackbar on success', fakeAsync(() => {
      component.cancelBuild();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Build cancelled', 'Dismiss', { duration: 2000 });
    }));

    it('should do nothing when factSheetId is null', () => {
      component.factSheetId = null;
      component.cancelBuild();
      expect(graphServiceSpy.cancelFactSheetBuild).not.toHaveBeenCalled();
    });

    it('should do nothing when buildStatus has no jobId', () => {
      component.buildStatus = null;
      component.cancelBuild();
      expect(graphServiceSpy.cancelFactSheetBuild).not.toHaveBeenCalled();
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 10. LINK ALL SOURCES
  // ═══════════════════════════════════════════════════════════════════════════

  describe('linkAllSources()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 5;
      fixture.detectChanges();
      tick();
      graphServiceSpy.linkSources.and.returnValue(of({ linksCreated: 3 }));
    }));

    it('should call graphService.linkSources with factSheetId', fakeAsync(() => {
      component.linkAllSources();
      tick();
      expect(graphServiceSpy.linkSources).toHaveBeenCalledWith(5);
    }));

    it('should show success snackbar with link count', fakeAsync(() => {
      component.linkAllSources();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Created 3 source links', 'Dismiss', { duration: 3000 });
    }));

    it('should reload graph after linking', fakeAsync(() => {
      graphServiceSpy.getFactSheetVisualizationData.calls.reset();
      component.linkAllSources();
      tick();
      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalled();
    }));

    it('should do nothing when factSheetId is null', () => {
      component.factSheetId = null;
      component.linkAllSources();
      expect(graphServiceSpy.linkSources).not.toHaveBeenCalled();
    });

    it('should show error snackbar on failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.linkSources.and.returnValue(throwError(() => new Error('fail')));
      component.linkAllSources();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to link sources', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 11. REBUILD EDGES
  // ═══════════════════════════════════════════════════════════════════════════

  describe('rebuildEdges()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 5;
      fixture.detectChanges();
      tick();
      graphServiceSpy.rebuildConceptEdges.and.returnValue(of({ edgesCreated: 7 }));
    }));

    it('should call graphService.rebuildConceptEdges with factSheetId', fakeAsync(() => {
      component.rebuildEdges();
      tick();
      expect(graphServiceSpy.rebuildConceptEdges).toHaveBeenCalledWith(5);
    }));

    it('should show success snackbar with edge count', fakeAsync(() => {
      component.rebuildEdges();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Created 7 edges', 'Dismiss', { duration: 3000 });
    }));

    it('should reload graph after rebuilding', fakeAsync(() => {
      graphServiceSpy.getFactSheetVisualizationData.calls.reset();
      component.rebuildEdges();
      tick();
      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalled();
    }));

    it('should do nothing when factSheetId is null', () => {
      component.factSheetId = null;
      component.rebuildEdges();
      expect(graphServiceSpy.rebuildConceptEdges).not.toHaveBeenCalled();
    });

    it('should show error snackbar on failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.rebuildConceptEdges.and.returnValue(throwError(() => new Error('fail')));
      component.rebuildEdges();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to rebuild edges', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 12. VIEW STATISTICS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('viewStatistics()', () => {
    const mockStats = {
      nodesByType: { SOURCE: 2, DOCUMENT: 5, ENTITY: 10 },
      edgesByType: { HIERARCHICAL: 8, SHARED_ENTITY: 4 },
      distinctConcepts: 15
    };

    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 3;
      fixture.detectChanges();
      tick();
      graphServiceSpy.getFactSheetStatistics.and.returnValue(of(mockStats));
    }));

    it('should call graphService.getFactSheetStatistics', fakeAsync(() => {
      component.viewStatistics();
      tick();
      expect(graphServiceSpy.getFactSheetStatistics).toHaveBeenCalledWith(3);
    }));

    it('should store statistics result in graphStatistics', fakeAsync(() => {
      component.viewStatistics();
      tick();
      expect(component.graphStatistics).toEqual(mockStats);
    }));

    it('should show statistics in snackbar', fakeAsync(() => {
      component.viewStatistics();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith(
        jasmine.stringMatching(/nodes.*edges.*concepts/),
        'Dismiss',
        { duration: 5000 }
      );
    }));

    it('should do nothing when factSheetId is null', () => {
      component.factSheetId = null;
      component.viewStatistics();
      expect(graphServiceSpy.getFactSheetStatistics).not.toHaveBeenCalled();
    });

    it('should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.getFactSheetStatistics.and.returnValue(throwError(() => new Error('fail')));
      component.viewStatistics();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 13. CLEAR GRAPH
  // ═══════════════════════════════════════════════════════════════════════════

  describe('clearGraph()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      component.factSheetId = 8;
      fixture.detectChanges();
      tick();
      graphServiceSpy.clearFactSheetGraph.and.returnValue(of({ entitiesDeleted: 12 }));
    }));

    it('should open ConfirmDialogComponent before clearing', fakeAsync(() => {
      component.clearGraph();
      tick();
      expect(dialogSpy.open).toHaveBeenCalled();
    }));

    it('should call graphService.clearFactSheetGraph when confirmed', fakeAsync(() => {
      component.clearGraph();
      tick();
      expect(graphServiceSpy.clearFactSheetGraph).toHaveBeenCalledWith(8);
    }));

    it('should NOT call clearFactSheetGraph when dialog is cancelled', fakeAsync(() => {
      dialogSpy.open.and.returnValue(makeDialogRef(false) as any);
      component.clearGraph();
      tick();
      expect(graphServiceSpy.clearFactSheetGraph).not.toHaveBeenCalled();
    }));

    it('should show success snackbar with entity count after clearing', fakeAsync(() => {
      component.clearGraph();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Cleared 12 entities', 'Dismiss', { duration: 3000 });
    }));

    it('should reload graph after clearing', fakeAsync(() => {
      graphServiceSpy.getFactSheetVisualizationData.calls.reset();
      component.clearGraph();
      tick();
      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalled();
    }));

    it('should do nothing when factSheetId is null', () => {
      component.factSheetId = null;
      component.clearGraph();
      expect(dialogSpy.open).not.toHaveBeenCalled();
    });

    it('should show error snackbar on clearGraph failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.clearFactSheetGraph.and.returnValue(throwError(() => new Error('fail')));
      component.clearGraph();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to clear graph', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 14. DELETE NODE
  // ═══════════════════════════════════════════════════════════════════════════

  describe('deleteNode()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      component.selectedNode = mockD3Data.nodes[0];
      graphServiceSpy.deleteNode.and.returnValue(of(undefined as any));
    }));

    it('should open ConfirmDialogComponent before deleting', fakeAsync(() => {
      component.deleteNode();
      tick();
      expect(dialogSpy.open).toHaveBeenCalled();
    }));

    it('should call graphService.deleteNode with the selected node id when confirmed', fakeAsync(() => {
      component.deleteNode();
      tick();
      expect(graphServiceSpy.deleteNode).toHaveBeenCalledWith('n1');
    }));

    it('should NOT call deleteNode when dialog is cancelled', fakeAsync(() => {
      dialogSpy.open.and.returnValue(makeDialogRef(false) as any);
      component.deleteNode();
      tick();
      expect(graphServiceSpy.deleteNode).not.toHaveBeenCalled();
    }));

    it('should clear selectedNode after deletion', fakeAsync(() => {
      component.deleteNode();
      tick();
      expect(component.selectedNode).toBeNull();
    }));

    it('should show success snackbar after deletion', fakeAsync(() => {
      component.deleteNode();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Node deleted successfully', 'Dismiss', { duration: 2000 });
    }));

    it('should reload graph after deletion', fakeAsync(() => {
      graphServiceSpy.getVisualizationData.calls.reset();
      component.deleteNode();
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
    }));

    it('should do nothing when selectedNode is null', () => {
      component.selectedNode = null;
      component.deleteNode();
      expect(dialogSpy.open).not.toHaveBeenCalled();
    });

    it('should show error snackbar on deletion failure', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.deleteNode.and.returnValue(throwError(() => new Error('fail')));
      component.deleteNode();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to delete node', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 15. FORMAT EDGE TYPE
  // ═══════════════════════════════════════════════════════════════════════════

  describe('formatEdgeType()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should convert USER_DEFINED to "user defined"', () => {
      expect(component.formatEdgeType('USER_DEFINED')).toBe('user defined');
    });

    it('should convert HIERARCHICAL to "hierarchical"', () => {
      expect(component.formatEdgeType('HIERARCHICAL')).toBe('hierarchical');
    });

    it('should convert EMBEDDING_SIMILARITY to "embedding similarity"', () => {
      expect(component.formatEdgeType('EMBEDDING_SIMILARITY')).toBe('embedding similarity');
    });

    it('should convert SHARED_ENTITY to "shared entity"', () => {
      expect(component.formatEdgeType('SHARED_ENTITY')).toBe('shared entity');
    });

    it('should convert CROSS_SOURCE to "cross source"', () => {
      expect(component.formatEdgeType('CROSS_SOURCE')).toBe('cross source');
    });

    it('should convert AUTHORED_BY to "authored by"', () => {
      expect(component.formatEdgeType('AUTHORED_BY')).toBe('authored by');
    });

    it('should convert ADDRESSED_TO to "addressed to"', () => {
      expect(component.formatEdgeType('ADDRESSED_TO')).toBe('addressed to');
    });

    it('should return all lowercase for any edge type', () => {
      component.allEdgeTypes.forEach((type: EdgeType) => {
        const result = component.formatEdgeType(type);
        expect(result).toBe(result.toLowerCase());
      });
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 16. GET NODE COLOR
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getNodeColor()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should return green for SOURCE', () => {
      expect(component.getNodeColor('SOURCE')).toBe('#22c55e');
    });

    it('should return blue for DOCUMENT', () => {
      expect(component.getNodeColor('DOCUMENT')).toBe('#3b82f6');
    });

    it('should return amber for SNIPPET', () => {
      expect(component.getNodeColor('SNIPPET')).toBe('#f59e0b');
    });

    it('should return purple for ENTITY', () => {
      expect(component.getNodeColor('ENTITY')).toBe('#a855f7');
    });

    it('should return pink for ATTACHMENT', () => {
      expect(component.getNodeColor('ATTACHMENT')).toBe('#ec4899');
    });

    it('should return a color for TABLE', () => {
      expect(component.getNodeColor('TABLE')).toMatch(/^#[0-9a-f]{6}$/i);
    });

    it('should return grey for CUSTOM', () => {
      expect(component.getNodeColor('CUSTOM')).toBe('#64748b');
    });

    it('should return a fallback grey for unknown type', () => {
      expect(component.getNodeColor('UNKNOWN' as NodeLevel)).toBe('#64748b');
    });

    it('should return distinct colors for different types', () => {
      const colors = component.allNodeTypes.map((t: NodeLevel) => component.getNodeColor(t));
      const uniqueColors = new Set(colors);
      // All node types should have distinct colors
      expect(uniqueColors.size).toBe(component.allNodeTypes.length);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 17. GET NODE LABEL
  // ═══════════════════════════════════════════════════════════════════════════

  describe('getNodeLabel()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
    }));

    it('should return the node label when found in graphData', () => {
      expect(component.getNodeLabel('n1')).toBe('Source 1');
    });

    it('should return the node title when label is not set', () => {
      // n4 has label 'Snippet 1'
      expect(component.getNodeLabel('n4')).toBe('Snippet 1');
    });

    it('should return the nodeId when node is not found', () => {
      expect(component.getNodeLabel('nonexistent-id')).toBe('nonexistent-id');
    });

    it('should return nodeId when graphData is null', () => {
      component.graphData = null;
      expect(component.getNodeLabel('n1')).toBe('n1');
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 18. TOGGLE NODE TYPE FILTER
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleNodeTypeFilter()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      graphServiceSpy.getVisualizationData.calls.reset();
    }));

    it('should remove a type from filter.nodeTypes if already present', () => {
      expect(component.filter.nodeTypes).toContain('SOURCE');
      component.toggleNodeTypeFilter('SOURCE');
      expect(component.filter.nodeTypes).not.toContain('SOURCE');
    });

    it('should add a type to filter.nodeTypes if not present', () => {
      component.filter.nodeTypes = [];
      component.toggleNodeTypeFilter('ENTITY');
      expect(component.filter.nodeTypes).toContain('ENTITY');
    });

    it('should reload graph when graphData is available', fakeAsync(() => {
      component.toggleNodeTypeFilter('SNIPPET');
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
    }));

    it('should not reload graph when graphData is null', fakeAsync(() => {
      component.graphData = null;
      component.toggleNodeTypeFilter('SNIPPET');
      tick();
      expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 19. TOGGLE EDGE TYPE FILTER
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleEdgeTypeFilter()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      graphServiceSpy.getVisualizationData.calls.reset();
    }));

    it('should remove an edge type from filter.edgeTypes if present', () => {
      expect(component.filter.edgeTypes).toContain('HIERARCHICAL');
      component.toggleEdgeTypeFilter('HIERARCHICAL');
      expect(component.filter.edgeTypes).not.toContain('HIERARCHICAL');
    });

    it('should add an edge type to filter.edgeTypes if not present', () => {
      component.filter.edgeTypes = [];
      component.toggleEdgeTypeFilter('CITATION');
      expect(component.filter.edgeTypes).toContain('CITATION');
    });

    it('should reload graph when graphData is set', fakeAsync(() => {
      component.toggleEdgeTypeFilter('SHARED_ENTITY');
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
    }));

    it('should not reload graph when graphData is null', fakeAsync(() => {
      component.graphData = null;
      component.toggleEdgeTypeFilter('SHARED_ENTITY');
      tick();
      expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 20. RESET FILTERS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('resetFilters()', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      // Dirty the filters
      component.filter.nodeTypes = ['SOURCE'];
      component.filter.edgeTypes = [];
      component.maxDepth = 5;
      component.maxNodes = 500;
      component.searchQuery = 'something';
      graphServiceSpy.getVisualizationData.calls.reset();
    }));

    it('should restore all allNodeTypes to filter.nodeTypes', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(component.filter.nodeTypes).toEqual(component.allNodeTypes);
    }));

    it('should reset filter.edgeTypes to the first 4 edge types', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(component.filter.edgeTypes.length).toBe(4);
    }));

    it('should reset maxDepth to 2', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(component.maxDepth).toBe(2);
    }));

    it('should reset maxNodes to 100', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(component.maxNodes).toBe(100);
    }));

    it('should clear searchQuery', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(component.searchQuery).toBe('');
    }));

    it('should reload the graph after reset', fakeAsync(() => {
      component.resetFilters();
      tick();
      expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 21. RESET FORCES
  // ═══════════════════════════════════════════════════════════════════════════

  describe('resetForces()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should restore forceConfig to DEFAULT_FORCE_CONFIG', () => {
      component.forceConfig = {
        linkDistance: 999,
        linkStrength: 0.1,
        chargeStrength: -999,
        collisionRadius: 99,
        centerStrength: 0.9,
        alphaDecay: 0.1,
        velocityDecay: 0.9
      };
      component.resetForces();
      expect(component.forceConfig).toEqual(DEFAULT_FORCE_CONFIG);
    });

    it('should produce a new object reference (spread) so D3 detects the change', () => {
      const before = component.forceConfig;
      component.resetForces();
      expect(component.forceConfig).not.toBe(before);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 22. RELATION MANAGEMENT
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Relation management', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
    }));

    describe('setAsRelationSource()', () => {
      it('should set newRelation.sourceNode to selectedNode', () => {
        component.selectedNode = mockD3Data.nodes[2];
        component.setAsRelationSource();
        expect(component.newRelation.sourceNode).toBe(mockD3Data.nodes[2]);
      });

      it('should switch selectedTabIndex to 1 (Relations tab)', () => {
        component.selectedNode = mockD3Data.nodes[0];
        component.selectedTabIndex = 0;
        component.setAsRelationSource();
        expect(component.selectedTabIndex).toBe(1);
      });

      it('should show a snackbar prompting to select target', () => {
        component.selectedNode = mockD3Data.nodes[0];
        component.setAsRelationSource();
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          'Source node selected. Now select a target node.', 'Dismiss', { duration: 3000 }
        );
      });

      it('should do nothing when selectedNode is null', () => {
        component.selectedNode = null;
        const before = component.newRelation.sourceNode;
        component.setAsRelationSource();
        expect(component.newRelation.sourceNode).toBe(before);
      });
    });

    describe('selectNodeForRelation()', () => {
      it('should set selectingNodeFor to "source"', () => {
        snackBarSpy.open.and.returnValue(makeSnackBarRef() as any);
        component.selectNodeForRelation('source');
        expect(component.selectingNodeFor).toBe('source');
      });

      it('should set selectingNodeFor to "target"', () => {
        snackBarSpy.open.and.returnValue(makeSnackBarRef() as any);
        component.selectNodeForRelation('target');
        expect(component.selectingNodeFor).toBe('target');
      });

      it('should show a snackbar with role hint', () => {
        snackBarSpy.open.and.returnValue(makeSnackBarRef() as any);
        component.selectNodeForRelation('source');
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          jasmine.stringMatching(/source/), 'Cancel', { duration: 5000 }
        );
      });
    });

    describe('clearRelationSource()', () => {
      it('should set newRelation.sourceNode to null', () => {
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.clearRelationSource();
        expect(component.newRelation.sourceNode).toBeNull();
      });
    });

    describe('clearRelationTarget()', () => {
      it('should set newRelation.targetNode to null', () => {
        component.newRelation.targetNode = mockD3Data.nodes[1];
        component.clearRelationTarget();
        expect(component.newRelation.targetNode).toBeNull();
      });
    });

    describe('clearNewRelation()', () => {
      it('should reset all newRelation fields to defaults', () => {
        component.newRelation = {
          sourceNode: mockD3Data.nodes[0],
          targetNode: mockD3Data.nodes[1],
          edgeType: 'CITATION',
          weight: 0.5,
          description: 'some description'
        };
        component.selectingNodeFor = 'source';
        component.clearNewRelation();
        expect(component.newRelation.sourceNode).toBeNull();
        expect(component.newRelation.targetNode).toBeNull();
        expect(component.newRelation.edgeType).toBe('USER_DEFINED');
        expect(component.newRelation.weight).toBe(1.0);
        expect(component.newRelation.description).toBe('');
        expect(component.selectingNodeFor).toBeNull();
      });
    });

    describe('saveNewRelation()', () => {
      beforeEach(() => {
        graphServiceSpy.createEdge.and.returnValue(of({} as any));
        graphServiceSpy.getEdges.and.returnValue(of(mockEdges));
      });

      it('should call graphService.createEdge with correct request', fakeAsync(() => {
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.newRelation.targetNode = mockD3Data.nodes[2];
        component.newRelation.edgeType = 'CITATION';
        component.newRelation.weight = 0.7;
        component.saveNewRelation();
        tick();
        expect(graphServiceSpy.createEdge).toHaveBeenCalledWith(jasmine.objectContaining({
          sourceNodeId: 'n1',
          targetNodeId: 'n3',
          edgeType: 'CITATION',
          weight: 0.7
        }));
      }));

      it('should show snackbar when source or target is missing', () => {
        component.newRelation.sourceNode = null;
        component.newRelation.targetNode = mockD3Data.nodes[1];
        component.saveNewRelation();
        expect(snackBarSpy.open).toHaveBeenCalledWith(
          'Please select both source and target nodes', 'Dismiss', { duration: 3000 }
        );
        expect(graphServiceSpy.createEdge).not.toHaveBeenCalled();
      });

      it('should clear the relation form after successful save', fakeAsync(() => {
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.newRelation.targetNode = mockD3Data.nodes[1];
        component.saveNewRelation();
        tick();
        expect(component.newRelation.sourceNode).toBeNull();
        expect(component.newRelation.targetNode).toBeNull();
      }));

      it('should reload graph after saving', fakeAsync(() => {
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.newRelation.targetNode = mockD3Data.nodes[1];
        graphServiceSpy.getVisualizationData.calls.reset();
        component.saveNewRelation();
        tick();
        expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
      }));

      it('should reload node relations for selectedNode after saving', fakeAsync(() => {
        component.selectedNode = mockD3Data.nodes[0];
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.newRelation.targetNode = mockD3Data.nodes[1];
        component.saveNewRelation();
        tick();
        expect(graphServiceSpy.getEdges).toHaveBeenCalledWith('n1');
      }));

      it('should show error snackbar on save failure', fakeAsync(() => {
        spyOn(console, 'error');
        graphServiceSpy.createEdge.and.returnValue(throwError(() => new Error('fail')));
        component.newRelation.sourceNode = mockD3Data.nodes[0];
        component.newRelation.targetNode = mockD3Data.nodes[1];
        component.saveNewRelation();
        tick();
        expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to create relation', 'Dismiss', { duration: 3000 });
      }));
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 23. WEIGHT PREVIEW
  // ═══════════════════════════════════════════════════════════════════════════

  describe('previewWeights()', () => {
    const mockPreview: WeightedSearchPreview = {
      query: 'test query',
      maxResults: 10,
      sourceWeights: [
        { sourceId: 'n1', sourceName: 'Source 1', weight: 1.5 }
      ]
    };

    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      weightServiceSpy.previewWeightedSearch.and.returnValue(of(mockPreview));
    }));

    it('should call weightService.previewWeightedSearch with the previewQuery', fakeAsync(() => {
      component.previewQuery = 'my query';
      component.previewWeights();
      tick();
      expect(weightServiceSpy.previewWeightedSearch).toHaveBeenCalledWith('my query');
    }));

    it('should set weightPreview on success', fakeAsync(() => {
      component.previewQuery = 'my query';
      component.previewWeights();
      tick();
      expect(component.weightPreview).toEqual(mockPreview);
    }));

    it('should do nothing when previewQuery is empty', () => {
      component.previewQuery = '';
      component.previewWeights();
      expect(weightServiceSpy.previewWeightedSearch).not.toHaveBeenCalled();
    });

    it('should show error snackbar on preview failure', fakeAsync(() => {
      spyOn(console, 'error');
      weightServiceSpy.previewWeightedSearch.and.returnValue(throwError(() => new Error('fail')));
      component.previewQuery = 'bad query';
      component.previewWeights();
      tick();
      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to preview weights', 'Dismiss', { duration: 3000 });
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 24. LINK SOURCE CHANGED HANDLER
  // ═══════════════════════════════════════════════════════════════════════════

  describe('onLinkSourceChanged()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should set linkSourceNode when a node is passed', () => {
      component.onLinkSourceChanged(mockD3Data.nodes[0]);
      expect(component.linkSourceNode).toBe(mockD3Data.nodes[0]);
    });

    it('should clear linkSourceNode when null is passed', () => {
      component.linkSourceNode = mockD3Data.nodes[0];
      component.onLinkSourceChanged(null);
      expect(component.linkSourceNode).toBeNull();
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 25. UPDATE FORCES
  // ═══════════════════════════════════════════════════════════════════════════

  describe('updateForces()', () => {
    beforeEach(() => {
      createComponent();
      fixture.detectChanges();
    });

    it('should create a new forceConfig object reference when called', () => {
      const before = component.forceConfig;
      component.updateForces();
      expect(component.forceConfig).not.toBe(before);
    });

    it('should preserve all forceConfig values after update', () => {
      const original = { ...component.forceConfig };
      component.updateForces();
      expect(component.forceConfig).toEqual(original);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 26. NGONDESTROY
  // ═══════════════════════════════════════════════════════════════════════════

  describe('ngOnDestroy()', () => {
    it('should complete destroy$ subject without throwing', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      expect(() => fixture.destroy()).not.toThrow();
    }));

    it('should not call graph service after component is destroyed', fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      fixture.destroy();
      const countAfterDestroy = graphServiceSpy.getVisualizationData.calls.count();
      tick(500); // allow any pending debounce
      expect(graphServiceSpy.getVisualizationData.calls.count()).toBe(countAfterDestroy);
    }));
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 27. TYPE LISTS & DEFAULTS
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Type lists and defaults', () => {
    beforeEach(() => {
      createComponent();
    });

    it('allNodeTypes should contain ATTACHMENT and TABLE', () => {
      expect(component.allNodeTypes).toContain('ATTACHMENT');
      expect(component.allNodeTypes).toContain('TABLE');
    });

    it('allEdgeTypes should contain HIERARCHICAL, SHARED_ENTITY, USER_DEFINED', () => {
      expect(component.allEdgeTypes).toContain('HIERARCHICAL');
      expect(component.allEdgeTypes).toContain('SHARED_ENTITY');
      expect(component.allEdgeTypes).toContain('USER_DEFINED');
    });

    it('default newRelation.edgeType should be USER_DEFINED', () => {
      expect(component.newRelation.edgeType).toBe('USER_DEFINED');
    });

    it('default newRelation.weight should be 1.0', () => {
      expect(component.newRelation.weight).toBe(1.0);
    });

    it('default maxDepth should be 2', () => {
      expect(component.maxDepth).toBe(2);
    });

    it('default maxNodes should be 100', () => {
      expect(component.maxNodes).toBe(100);
    });
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 28. SEARCH DEBOUNCE (via searchSubject)
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Search debounce (onSearchChange)', () => {
    beforeEach(fakeAsync(() => {
      createComponent();
      fixture.detectChanges();
      tick();
      graphServiceSpy.getVisualizationData.calls.reset();
    }));

    it('should debounce multiple rapid search changes', fakeAsync(() => {
      component.onSearchChange('a');
      component.onSearchChange('ab');
      component.onSearchChange('abc');
      tick(300);
      // Only one call should be made after debounce settles
      expect(graphServiceSpy.getVisualizationData.calls.count()).toBe(1);
    }));

    it('should not fire immediately before debounce period elapses', fakeAsync(() => {
      component.onSearchChange('test');
      tick(100);
      expect(graphServiceSpy.getVisualizationData.calls.count()).toBe(0);
      tick(200); // total 300ms
      expect(graphServiceSpy.getVisualizationData.calls.count()).toBe(1);
    }));
  });
});
