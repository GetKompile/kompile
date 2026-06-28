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

/**
 * Isolated unit tests for the graph-visualizer perf fixes introduced in the
 * "LOD auto-refresh + in-memory filter" session.
 *
 * Covers:
 *   1. Auto-refresh LOD routing — getTopKVisualization vs getVisualizationData
 *      driven by component.totalAvailableNodes.
 *   2. Auto-refresh skips when document.hidden is true (background-tab guard).
 *   3. toggleNodeTypeFilter / toggleEdgeTypeFilter re-apply filters in-memory
 *      when fullGraphData cache is populated (no network round-trip).
 *   4. The same toggles fall back to loadGraph() (via getStatistics) when the
 *      cache is absent but graphData is already set.
 *   5. Auto-refresh signature gating — graphData is NOT replaced when the
 *      node/edge count is unchanged between ticks.
 *
 * TestBed setup mirrors graph-visualizer.component.spec.ts:
 *   • NO_ERRORS_SCHEMA + overrideComponent to stub GraphCanvasComponent
 *     (avoids Sigma/WebGL instantiation).
 *   • overrideProvider for GraphService / SourceWeightService / MatSnackBar /
 *     MatDialog so the mocks are seen by the standalone component's injector.
 *
 * Key difference from the sibling spec: this spec adds getStatistics and
 * getTopKVisualization to the GraphService spy because loadGraph() now uses
 * getStatistics() → switchMap(…) as its LOD-first entry point.
 *
 * NOTE: graph-canvas.component (Sigma renderer) cannot be instantiated in
 * jsdom without a real WebGL canvas.  All tests here operate exclusively on
 * the GraphVisualizerComponent shell (the orchestrator), which is fully
 * isolatable.  Sigma-level diff tests (test 4 in the spec brief) are NOT
 * included here; a separate harness with a mock graphology Graph would be
 * needed for those — see report at the bottom of this file.
 */

import {
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick
} from '@angular/core/testing';
import {
  Component,
  Input,
  Output,
  EventEmitter,
  NO_ERRORS_SCHEMA
} from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { of, NEVER } from 'rxjs';

import { GraphVisualizerComponent } from './graph-visualizer.component';
import { GraphCanvasComponent } from './graph-canvas.component';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { CompositeEntityDialogComponent } from '../composite-entity-dialog/composite-entity-dialog.component';
import { GraphService } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import {
  D3VisualizationData,
  D3Node,
  NodeLevel,
  EdgeType
} from '../../models/graph-models';

// ─────────────────────────────────────────────────────────────────────────────
// Stub child components — same pattern as the sibling spec.
// GraphCanvasComponent is replaced so Sigma/WebGL is never touched.
// ─────────────────────────────────────────────────────────────────────────────
@Component({ selector: 'app-graph-canvas', standalone: true, template: '' })
class GraphCanvasStubComponent {
  @Input() data: any;
  @Input() forceConfig: any;
  @Input() linkMode: boolean = false;
  @Input() showLegend: boolean = false;
  @Input() focusedNodeId: string | null = null;
  @Output() nodeSelected   = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() edgeCreated    = new EventEmitter<{ source: string; target: string }>();
  @Output() nodeContextMenu = new EventEmitter<{ node: D3Node; event: MouseEvent }>();
  @Output() linkSourceChanged = new EventEmitter<D3Node | null>();
}

@Component({ selector: 'app-confirm-dialog', standalone: true, template: '' })
class ConfirmDialogStubComponent {}

@Component({ selector: 'app-composite-entity-dialog', standalone: true, template: '' })
class CompositeEntityDialogStubComponent {}

// ─────────────────────────────────────────────────────────────────────────────
// Shared test data
// ─────────────────────────────────────────────────────────────────────────────

/** Small graph returned by getVisualizationData (< 2 000 nodes). */
const smallGraphData: D3VisualizationData = {
  nodes: [
    { id: 'n1', type: 'SOURCE',   label: 'Source 1',   title: 'Source 1',   description: '' },
    { id: 'n2', type: 'DOCUMENT', label: 'Document 1', title: 'Document 1', description: '' }
  ],
  links: [
    { id: 'e1', source: 'n1', target: 'n2', type: 'HIERARCHICAL', weight: 1.0 }
  ]
};

/** Distinct data returned by getTopKVisualization (LOD path). */
const topKGraphData: D3VisualizationData = {
  nodes: [
    { id: 'topk1', type: 'ENTITY', label: 'Top-K Entity', title: 'Top-K Entity', description: '' }
  ],
  links: []
};

/** Graph with one extra node — used to verify signature-change detection. */
const grownGraphData: D3VisualizationData = {
  nodes: [
    ...smallGraphData.nodes,
    { id: 'n3', type: 'ENTITY', label: 'New Entity', title: 'New Entity', description: '' }
  ],
  links: smallGraphData.links
};

// ─────────────────────────────────────────────────────────────────────────────
describe('GraphVisualizerComponent — perf fixes (LOD auto-refresh + in-memory filters)', () => {

  let component: GraphVisualizerComponent;
  let fixture: ComponentFixture<GraphVisualizerComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let weightServiceSpy: jasmine.SpyObj<SourceWeightService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  function makeSnackBarRef() { return { onAction: () => NEVER }; }
  function makeDialogRef(result: boolean | undefined = true) {
    return { afterClosed: () => of(result) };
  }

  // Helper: create, detect changes, and wire the interval in one step.
  // Must be called INSIDE a fakeAsync block so the interval registration
  // and the first loadGraph() tick share the same fake clock.
  function createAndInit(): void {
    fixture   = TestBed.createComponent(GraphVisualizerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges(); // triggers ngOnInit → loadGraph() + interval(5000)
  }

  // ─────────────────────────────────────────────────────────────────────────
  beforeEach(async () => {
    // GraphService spy — must include getStatistics + getTopKVisualization
    // because loadGraph() now pings /statistics first (LOD-first design).
    graphServiceSpy = jasmine.createSpyObj<GraphService>('GraphService', [
      'getStatistics',
      'getVisualizationData',
      'getTopKVisualization',
      'getTemporalBounds',
      'getNodeNeighborhood',
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

    weightServiceSpy = jasmine.createSpyObj<SourceWeightService>('SourceWeightService', [
      'getWeights', 'setWeight', 'previewWeightedSearch'
    ]);
    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialogSpy   = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    // Default returns — small graph so the initial loadGraph() uses getVisualizationData.
    graphServiceSpy.getStatistics.and.returnValue(of({ totalNodes: 100 } as any));
    graphServiceSpy.getVisualizationData.and.returnValue(of(smallGraphData));
    graphServiceSpy.getTopKVisualization.and.returnValue(of(topKGraphData));
    graphServiceSpy.getTemporalBounds.and.returnValue(of(null as any));
    graphServiceSpy.getNodeNeighborhood.and.returnValue(of(smallGraphData));
    graphServiceSpy.getFactSheetVisualizationData.and.returnValue(of(smallGraphData));
    graphServiceSpy.getAncestors.and.returnValue(of([]));
    weightServiceSpy.getWeights.and.returnValue(of([]));
    snackBarSpy.open.and.returnValue(makeSnackBarRef() as any);
    dialogSpy.open.and.returnValue(makeDialogRef() as any);

    await TestBed.configureTestingModule({
      imports: [GraphVisualizerComponent, NoopAnimationsModule, HttpClientTestingModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
    // Replace real child components with stubs (avoids Sigma/WebGL and MatDialog
    // injection chains) — exact same pattern as graph-visualizer.component.spec.ts.
    .overrideComponent(GraphVisualizerComponent, {
      remove: {
        imports: [GraphCanvasComponent, ConfirmDialogComponent, CompositeEntityDialogComponent]
      },
      add: {
        imports: [GraphCanvasStubComponent, ConfirmDialogStubComponent, CompositeEntityDialogStubComponent]
      }
    })
    .overrideComponent(GraphVisualizerComponent, {
      set: { schemas: [NO_ERRORS_SCHEMA] }
    })
    // For standalone components the root-level providers are NOT visible to
    // the component's own injector, so we must use overrideProvider.
    .overrideProvider(GraphService,       { useValue: graphServiceSpy })
    .overrideProvider(SourceWeightService, { useValue: weightServiceSpy })
    .overrideProvider(MatSnackBar,         { useValue: snackBarSpy })
    .overrideProvider(MatDialog,           { useValue: dialogSpy })
    .compileComponents();
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 1. AUTO-REFRESH LOD ROUTING
  //    The 5-second interval callback (ngOnInit, line ≈2597) decides between
  //    getTopKVisualization and getVisualizationData based on the cached
  //    totalAvailableNodes value — NOT on a fresh statistics call.
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Auto-refresh LOD routing', () => {

    it('uses getTopKVisualization (not getVisualizationData) when totalAvailableNodes > 2000',
      fakeAsync(() => {
        createAndInit();
        tick(); // resolve initial loadGraph()

        component.totalAvailableNodes = 2001;

        // Isolate: only count calls made during the refresh tick.
        graphServiceSpy.getTopKVisualization.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(5000); // fire the interval once

        expect(graphServiceSpy.getTopKVisualization)
          .toHaveBeenCalledWith(300, 'pagerank', undefined);
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
      })
    );

    it('uses getTopKVisualization with factSheetId when graph is large and factSheetId is set',
      fakeAsync(() => {
        createAndInit();
        component.factSheetId = 42;
        tick();

        component.totalAvailableNodes = 9999;
        graphServiceSpy.getTopKVisualization.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(5000);

        expect(graphServiceSpy.getTopKVisualization)
          .toHaveBeenCalledWith(300, 'pagerank', 42);
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
      })
    );

    it('uses getVisualizationData (not getTopKVisualization) when totalAvailableNodes <= 2000',
      fakeAsync(() => {
        createAndInit();
        tick();

        component.totalAvailableNodes = 2000; // boundary: exactly 2000 → small path

        graphServiceSpy.getTopKVisualization.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(5000);

        expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );

    it('uses getVisualizationData when totalAvailableNodes is null (no stats yet)',
      fakeAsync(() => {
        createAndInit();
        tick();

        component.totalAvailableNodes = null; // reset to "unknown"

        graphServiceSpy.getTopKVisualization.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(5000);

        expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );

    it('does NOT call getStatistics during the refresh tick (refresh is cheaper than loadGraph)',
      fakeAsync(() => {
        createAndInit();
        tick();

        component.totalAvailableNodes = 500;
        graphServiceSpy.getStatistics.calls.reset();

        tick(5000);

        // The auto-refresh reads totalAvailableNodes directly — it must NOT
        // hit /statistics on every tick (that would defeat the LOD optimisation).
        expect(graphServiceSpy.getStatistics).not.toHaveBeenCalled();
      })
    );

    it('fires at the 5000 ms boundary, not before',
      fakeAsync(() => {
        createAndInit();
        tick();

        component.totalAvailableNodes = 500;
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(4999); // just under the interval
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();

        tick(1); // now at exactly 5000 ms
        expect(graphServiceSpy.getVisualizationData).toHaveBeenCalledTimes(1);
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 2. AUTO-REFRESH SKIPS WHEN BROWSER TAB IS HIDDEN
  //    Guard (line ≈2598): if (!this.autoRefresh || this.loading || document.hidden) return;
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Auto-refresh skips when document.hidden', () => {

    // Store any existing descriptor so we can restore it cleanly.
    let originalHiddenDescriptor: PropertyDescriptor | undefined;

    beforeEach(() => {
      originalHiddenDescriptor = Object.getOwnPropertyDescriptor(document, 'hidden');
    });

    afterEach(() => {
      if (originalHiddenDescriptor) {
        Object.defineProperty(document, 'hidden', originalHiddenDescriptor);
      } else {
        // Property came from the prototype — remove the own override so the
        // prototype getter takes over again.
        try {
          delete (document as any).hidden;
        } catch {
          // Non-configurable in some environments — ignore.
        }
      }
    });

    it('makes no graphService call on the interval tick when document.hidden is true',
      fakeAsync(() => {
        createAndInit();
        tick(); // let initial loadGraph complete

        Object.defineProperty(document, 'hidden', { get: () => true, configurable: true });

        graphServiceSpy.getTopKVisualization.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        tick(5000); // interval fires but the guard should short-circuit

        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
      })
    );

    it('resumes making calls once document.hidden transitions back to false',
      fakeAsync(() => {
        createAndInit();
        tick();

        // First tick: tab is hidden.
        Object.defineProperty(document, 'hidden', { get: () => true, configurable: true });
        graphServiceSpy.getVisualizationData.calls.reset();
        graphServiceSpy.getTopKVisualization.calls.reset();
        tick(5000);
        const callsWhileHidden = graphServiceSpy.getVisualizationData.calls.count()
          + graphServiceSpy.getTopKVisualization.calls.count();
        expect(callsWhileHidden).toBe(0);

        // Second tick: tab becomes visible again.
        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
        component.totalAvailableNodes = 100; // small → getVisualizationData path
        graphServiceSpy.getVisualizationData.calls.reset();
        tick(5000);
        expect(graphServiceSpy.getVisualizationData).toHaveBeenCalled();
      })
    );

    it('respects autoRefresh = false independent of document.hidden',
      fakeAsync(() => {
        createAndInit();
        tick();

        component.autoRefresh = false;

        graphServiceSpy.getVisualizationData.calls.reset();
        graphServiceSpy.getTopKVisualization.calls.reset();

        tick(5000);

        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 3. TOGGLE NODE TYPE FILTER — IN-MEMORY PATH
  //    When fullGraphData cache is populated, the toggle must re-apply filters
  //    locally (applyFilters) and must NOT call any graphService method.
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleNodeTypeFilter — in-memory when fullGraphData cache is present', () => {

    // Shared setup: initial load runs, populating fullGraphData.
    // We then reset spy counts and operate on the component.
    function initAndResetCounts(): void {
      createAndInit();
      tick(); // resolve initial loadGraph, which sets (component as any).fullGraphData
      graphServiceSpy.getStatistics.calls.reset();
      graphServiceSpy.getVisualizationData.calls.reset();
      graphServiceSpy.getTopKVisualization.calls.reset();
    }

    it('does NOT call getStatistics, getVisualizationData, or getTopKVisualization',
      fakeAsync(() => {
        initAndResetCounts();

        // Verify the cache is actually populated by the initial load.
        expect((component as any).fullGraphData).not.toBeNull();

        component.toggleNodeTypeFilter('SOURCE');

        expect(graphServiceSpy.getStatistics).not.toHaveBeenCalled();
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );

    it('synchronously updates graphData (no tick required) when cache is present',
      fakeAsync(() => {
        initAndResetCounts();

        // Force a known cache state with SOURCE + DOCUMENT nodes.
        (component as any).fullGraphData = smallGraphData;
        component.filter.nodeTypes = ['SOURCE', 'DOCUMENT'];

        component.toggleNodeTypeFilter('SOURCE'); // removes SOURCE

        // No async work — result is immediate.
        expect(component.graphData).not.toBeNull();
        const nodeTypes = component.graphData!.nodes.map(n => n.type);
        expect(nodeTypes).not.toContain('SOURCE');
        expect(nodeTypes).toContain('DOCUMENT');
      })
    );

    it('re-adds a previously removed type and immediately expands graphData',
      fakeAsync(() => {
        initAndResetCounts();

        (component as any).fullGraphData = smallGraphData;
        component.filter.nodeTypes = ['DOCUMENT']; // SOURCE is absent

        component.toggleNodeTypeFilter('SOURCE'); // adds SOURCE back

        const nodeTypes = component.graphData!.nodes.map(n => n.type);
        expect(nodeTypes).toContain('SOURCE');
      })
    );

    it('removes the type from filter.nodeTypes when it was present',
      fakeAsync(() => {
        initAndResetCounts();

        expect(component.filter.nodeTypes).toContain('ENTITY');
        component.toggleNodeTypeFilter('ENTITY');
        expect(component.filter.nodeTypes).not.toContain('ENTITY');
      })
    );

    it('adds the type to filter.nodeTypes when it was absent',
      fakeAsync(() => {
        initAndResetCounts();

        component.filter.nodeTypes = [];
        component.toggleNodeTypeFilter('ENTITY');
        expect(component.filter.nodeTypes).toContain('ENTITY');
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 4. TOGGLE EDGE TYPE FILTER — IN-MEMORY PATH
  //    Mirror of the node-type tests for the edge-type toggle.
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleEdgeTypeFilter — in-memory when fullGraphData cache is present', () => {

    function initAndResetCounts(): void {
      createAndInit();
      tick();
      graphServiceSpy.getStatistics.calls.reset();
      graphServiceSpy.getVisualizationData.calls.reset();
      graphServiceSpy.getTopKVisualization.calls.reset();
    }

    it('does NOT call any graphService method when fullGraphData is cached',
      fakeAsync(() => {
        initAndResetCounts();
        expect((component as any).fullGraphData).not.toBeNull();

        component.toggleEdgeTypeFilter('HIERARCHICAL');

        expect(graphServiceSpy.getStatistics).not.toHaveBeenCalled();
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );

    it('removes edge type from filter and filters out those links in graphData immediately',
      fakeAsync(() => {
        initAndResetCounts();

        (component as any).fullGraphData = smallGraphData;
        component.filter.nodeTypes = ['SOURCE', 'DOCUMENT', 'ENTITY'];
        component.filter.edgeTypes = ['HIERARCHICAL'];

        component.toggleEdgeTypeFilter('HIERARCHICAL'); // removes HIERARCHICAL

        expect(component.filter.edgeTypes).not.toContain('HIERARCHICAL');
        // With no edge types active, all links must be filtered away.
        expect(component.graphData!.links.length).toBe(0);
        // But nodes remain (edge filter does not remove nodes).
        expect(component.graphData!.nodes.length).toBeGreaterThan(0);
      })
    );

    it('adds edge type and widens graphData links in-memory',
      fakeAsync(() => {
        initAndResetCounts();

        (component as any).fullGraphData = smallGraphData;
        component.filter.nodeTypes = ['SOURCE', 'DOCUMENT'];
        component.filter.edgeTypes = []; // start with no edge types — all links hidden

        component.toggleEdgeTypeFilter('HIERARCHICAL'); // adds HIERARCHICAL

        expect(component.filter.edgeTypes).toContain('HIERARCHICAL');
        expect(component.graphData!.links.length).toBeGreaterThan(0);
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 5. TOGGLE FILTER — FALLBACK TO loadGraph() WHEN CACHE IS ABSENT
  //    When fullGraphData is null but graphData is set, the toggle must call
  //    loadGraph(), which in turn calls getStatistics().
  //    When both are null, it does nothing.
  // ═══════════════════════════════════════════════════════════════════════════

  describe('toggleNodeTypeFilter — loadGraph fallback when cache is absent', () => {

    it('calls getStatistics (via loadGraph) when fullGraphData is null but graphData is set',
      fakeAsync(() => {
        createAndInit();
        tick();

        // Simulate state: UI has data to show, but the raw cache was discarded.
        (component as any).fullGraphData = null;
        component.graphData = smallGraphData;

        graphServiceSpy.getStatistics.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        component.toggleNodeTypeFilter('ENTITY');
        tick(); // let loadGraph() → getStatistics → getVisualizationData resolve

        // loadGraph() always starts with getStatistics().
        expect(graphServiceSpy.getStatistics).toHaveBeenCalled();
      })
    );

    it('does NOT call any service when both fullGraphData and graphData are null',
      fakeAsync(() => {
        createAndInit();
        tick();

        (component as any).fullGraphData = null;
        component.graphData = null;

        graphServiceSpy.getStatistics.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();
        graphServiceSpy.getTopKVisualization.calls.reset();

        component.toggleNodeTypeFilter('ENTITY');

        expect(graphServiceSpy.getStatistics).not.toHaveBeenCalled();
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
        expect(graphServiceSpy.getTopKVisualization).not.toHaveBeenCalled();
      })
    );
  });

  describe('toggleEdgeTypeFilter — loadGraph fallback when cache is absent', () => {

    it('calls getStatistics (via loadGraph) when fullGraphData is null but graphData is set',
      fakeAsync(() => {
        createAndInit();
        tick();

        (component as any).fullGraphData = null;
        component.graphData = smallGraphData;

        graphServiceSpy.getStatistics.calls.reset();

        component.toggleEdgeTypeFilter('CITATION');
        tick();

        expect(graphServiceSpy.getStatistics).toHaveBeenCalled();
      })
    );

    it('does NOT call any service when both fullGraphData and graphData are null',
      fakeAsync(() => {
        createAndInit();
        tick();

        (component as any).fullGraphData = null;
        component.graphData = null;

        graphServiceSpy.getStatistics.calls.reset();
        graphServiceSpy.getVisualizationData.calls.reset();

        component.toggleEdgeTypeFilter('CITATION');

        expect(graphServiceSpy.getStatistics).not.toHaveBeenCalled();
        expect(graphServiceSpy.getVisualizationData).not.toHaveBeenCalled();
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 6. AUTO-REFRESH SIGNATURE GATING
  //    The refresh callback computes a "nodeCount:edgeCount" signature.  If the
  //    signature matches the last seen value, graphData is left untouched (no
  //    unnecessary re-render / layout jank).
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Auto-refresh signature gating', () => {

    it('does NOT replace graphData when the returned node/edge count is unchanged',
      fakeAsync(() => {
        createAndInit();
        tick();

        // After initial load, the component holds a reference to the filtered data.
        const referenceBeforeRefresh = component.graphData;

        // The refresh returns the identical dataset → same "2:1" signature.
        graphServiceSpy.getVisualizationData.and.returnValue(of(smallGraphData));
        component.totalAvailableNodes = 100; // use getVisualizationData path

        // Spy on applyFilters to detect whether a re-render was attempted.
        const applyFiltersSpy = spyOn<any>(component, 'applyFilters').and.callThrough();

        tick(5000);

        // Same signature → guard returns early → applyFilters should NOT have been called.
        expect(applyFiltersSpy).not.toHaveBeenCalled();
        expect(component.graphData).toBe(referenceBeforeRefresh);
      })
    );

    it('DOES replace graphData when the node count increases (graph grew)',
      fakeAsync(() => {
        createAndInit();
        tick();

        const countBefore = component.graphData?.nodes.length ?? 0;

        // Refresh returns a larger dataset — different "3:1" signature.
        graphServiceSpy.getVisualizationData.and.returnValue(of(grownGraphData));
        component.totalAvailableNodes = 100;

        tick(5000);

        expect(component.graphData!.nodes.length).toBeGreaterThan(countBefore);
      })
    );

    it('updates the lastGraphSignature after a signature change so subsequent identical ticks are skipped',
      fakeAsync(() => {
        createAndInit();
        tick();

        // First refresh: new node appears (signature changes → re-render happens).
        graphServiceSpy.getVisualizationData.and.returnValue(of(grownGraphData));
        component.totalAvailableNodes = 100;
        tick(5000);

        // Second refresh: same grown data — signature is now known.
        const applyFiltersSpy = spyOn<any>(component, 'applyFilters').and.callThrough();
        tick(5000);
        expect(applyFiltersSpy).not.toHaveBeenCalled();
      })
    );
  });

  // ═══════════════════════════════════════════════════════════════════════════
  // 7. FULL-GRAPH CACHE POPULATED AFTER INITIAL loadGraph
  //    Confirms that the fullGraphData cache is set as a side-effect of the
  //    initial load, which is the prerequisite for the in-memory filter path.
  // ═══════════════════════════════════════════════════════════════════════════

  describe('fullGraphData cache wiring', () => {

    it('fullGraphData is null before the first loadGraph resolves', fakeAsync(() => {
      // Make the LOD entry point (getStatistics) never emit, so loadGraph stays
      // genuinely in-flight and fullGraphData remains at its initial null.
      // (With a synchronous of(...) mock, loadGraph would resolve instantly.)
      graphServiceSpy.getStatistics.and.returnValue(NEVER);
      createAndInit();
      expect((component as any).fullGraphData).toBeNull();
      tick(); // drain any pending microtasks (getStatistics never emits).
    }));

    it('fullGraphData is populated after initial load', fakeAsync(() => {
      createAndInit();
      tick();
      expect((component as any).fullGraphData).not.toBeNull();
      expect((component as any).fullGraphData!.nodes.length).toBeGreaterThan(0);
    }));

    it('fullGraphData is updated when the auto-refresh receives changed data', fakeAsync(() => {
      createAndInit();
      tick();

      graphServiceSpy.getVisualizationData.and.returnValue(of(grownGraphData));
      component.totalAvailableNodes = 100;

      tick(5000);

      expect((component as any).fullGraphData!.nodes.length).toBe(grownGraphData.nodes.length);
    }));
  });

});

/*
 * ─────────────────────────────────────────────────────────────────────────────
 * REPORT: what could NOT be isolated in this spec file
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Test 4 (task brief) — incremental diff in GraphCanvasComponent.updateGraph():
 *
 *   The brief asks to verify that calling updateGraph() twice with the same
 *   node/edge set does NOT call graph.clear(), and that adding one new node
 *   calls graph.addNode() exactly once without repositioning existing nodes.
 *
 *   Why it cannot be done here:
 *     GraphCanvasComponent imports Sigma and graphology directly and constructs
 *     a real Sigma instance from a DOM canvas element in ngOnInit / ngOnChanges.
 *     jsdom (the test environment) does not implement WebGL, so
 *     `new Sigma(graph, container)` throws or returns an unusable stub.
 *     There is no injection seam to replace the Sigma/Graph instances — they
 *     are constructed directly with `new Graph()` and `new Sigma(...)` inside
 *     the component, not via DI tokens.
 *
 *   What IS verifiable from the source:
 *     The updateGraph() implementation (line ≈631) reads:
 *       1. Compute incomingNodeIds from this.data.nodes.
 *       2. Drop nodes absent from incomingNodeIds (graph.dropNode per absent node).
 *       3. For each incoming node: graph.hasNode(id) → setNodeAttribute (existing)
 *                                                     → graph.addNode (new only).
 *       4. No graph.clear() call exists anywhere in the method.
 *     This textually confirms the desired behaviour; the runtime cannot be
 *     exercised without a WebGL-capable environment (e.g. a headless Chromium /
 *     Puppeteer harness with canvas enabled, or a graphology mock injected via
 *     a factory token added to the component).
 *
 * RUN AFTER BUILD: ng test (scoped to this perf spec file).
 */
