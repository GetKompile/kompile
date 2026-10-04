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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { Component, EventEmitter, Input, NO_ERRORS_SCHEMA, Output } from '@angular/core';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { By } from '@angular/platform-browser';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { NEVER, Subject, of, throwError } from 'rxjs';

import { GraphVisualizerComponent } from './graph-visualizer.component';
import { GraphCanvasComponent } from './graph-canvas.component';
import { visualizationLinkKey } from './visualization-merge';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { CompositeEntityDialogComponent } from '../composite-entity-dialog/composite-entity-dialog.component';
import { GraphService } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import { AttributionService } from '../../services/attribution.service';
import { ProcessEngineService } from '../../services/process-engine.service';
import { KbGroundingService } from '../../services/kb-grounding.service';
import { D3Link, D3Node, D3VisualizationData, GraphEdge, NodeLevel } from '../../models/graph-models';

// What the visualizer keeps between loads: the linked focus node, the neighborhoods fetched for
// a focus or an expand, and the focal view, across reloads, refreshes, deletes and sheet changes.

@Component({ selector: 'app-graph-canvas', standalone: true, template: '' })
class GraphCanvasStubComponent {
  @Input() data: D3VisualizationData | null = null;
  @Input() focusNodeId: string | null = null;
  @Output() nodeSelected = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() focused = new EventEmitter<D3Node>();
  readonly addNodesToGraph = jasmine.createSpy('addNodesToGraph');
}

@Component({ selector: 'app-confirm-dialog', standalone: true, template: '' })
class ConfirmDialogStubComponent {}

@Component({ selector: 'app-composite-entity-dialog', standalone: true, template: '' })
class CompositeEntityDialogStubComponent {}

const SHEET = 7;

function node(id: string, type: NodeLevel = 'ENTITY'): D3Node {
  return { id, type, label: id, title: id };
}

function sharedEntity(source: string, target: string): D3Link {
  return { id: `${source}::${target}::SHARED_ENTITY`, source, target, type: 'SHARED_ENTITY', weight: 1 };
}

/** The sheet's graph as loaded. */
function base(): D3VisualizationData {
  return {
    nodes: [node('n1', 'SOURCE'), node('n2', 'DOCUMENT')],
    links: [{ id: 'e1', source: 'n1', target: 'n2', type: 'HIERARCHICAL', weight: 1 }]
  };
}

/** The sheet's graph after a crawl added n3. */
function grown(): D3VisualizationData {
  const data = base();
  return { ...data, nodes: [...data.nodes, node('n3')] };
}

/** The expand view of center: the center, one neighbor and the edge between them. */
function neighborhood(center: string, neighbor: string): D3VisualizationData {
  return { nodes: [node(center), node(neighbor)], links: [sharedEntity(center, neighbor)] };
}

/** The subgraph endpoint's answer for seed n1. */
function focalResult(): any {
  return {
    nodes: [node('n1', 'SOURCE'), node('f1')],
    links: [sharedEntity('n1', 'f1')],
    statistics: { nodeCount: 2, edgeCount: 1, radius: 2 }
  };
}

describe('GraphVisualizerComponent focus and kept neighborhoods', () => {
  let component: GraphVisualizerComponent;
  let fixture: ComponentFixture<GraphVisualizerComponent>;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let snackBarSpy: jasmine.SpyObj<MatSnackBar>;
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj<GraphService>('GraphService', [
      'getVisualizationData',
      'getStatistics',
      'getTopKVisualization',
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
      'getAncestors',
      'getReasoningLayers',
      'getTemporalBounds',
      'getNodeNeighborhood',
      'exportNativeGraph',
      'importNativeGraph',
      'getSourceConnectivity',
      'getSourceLinks',
      'findMostConnectedSources',
      'findIsolatedSources',
      'removeSourceLink'
    ]);
    graphServiceSpy.getStatistics.and.returnValue(of({ totalNodes: 2 }));
    graphServiceSpy.getFactSheetStatistics.and.returnValue(of({ totalNodes: 2 }));
    graphServiceSpy.getVisualizationData.and.callFake(() => of(base()));
    graphServiceSpy.getFactSheetVisualizationData.and.callFake(() => of(base()));
    graphServiceSpy.getTopKVisualization.and.callFake(() => of(base()));
    graphServiceSpy.getTemporalBounds.and.returnValue(of(null as any));
    graphServiceSpy.getReasoningLayers.and.returnValue(of({
      factSheetId: SHEET,
      nodes: [],
      edges: [],
      statistics: { nodeCount: 0, edgeCount: 0 }
    }));
    graphServiceSpy.getAncestors.and.returnValue(of([]));
    graphServiceSpy.getEdges.and.returnValue(of([]));
    graphServiceSpy.deleteNode.and.returnValue(of(undefined));
    graphServiceSpy.deleteEdge.and.returnValue(of(undefined));
    graphServiceSpy.getSourceConnectivity.and.returnValue(of({}));
    graphServiceSpy.getSourceLinks.and.returnValue(of([]));
    graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
    graphServiceSpy.findIsolatedSources.and.returnValue(of([]));

    const weightServiceSpy = jasmine.createSpyObj<SourceWeightService>('SourceWeightService', [
      'getWeights', 'setWeight', 'previewWeightedSearch'
    ]);
    weightServiceSpy.getWeights.and.returnValue(of([]));

    snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snackBarSpy.open.and.returnValue({ onAction: () => NEVER } as any);
    const dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialogSpy.open.and.returnValue({ afterClosed: () => of(true) } as any);

    const attributionSpy = jasmine.createSpyObj<AttributionService>('AttributionService', ['explainQuick', 'predictQuick']);
    const processEngineSpy = jasmine.createSpyObj<ProcessEngineService>('ProcessEngineService', [
      'listStoredSuggestions', 'getStoredSuggestionTrace', 'mineProcesses', 'listOntologies'
    ]);
    processEngineSpy.listStoredSuggestions.and.returnValue(of({ count: 0, suggestions: [] }));
    processEngineSpy.mineProcesses.and.returnValue(of(null));
    processEngineSpy.listOntologies.and.returnValue(of([]));
    const groundingSpy = jasmine.createSpyObj<KbGroundingService>('KbGroundingService', ['batchVerify', 'unifiedExplain']);
    groundingSpy.batchVerify.and.returnValue(of({
      results: {},
      totalCount: 0,
      factSheetId: SHEET,
      timestamp: '2026-10-03T00:00:00Z'
    }));

    await TestBed.configureTestingModule({
      imports: [GraphVisualizerComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideComponent(GraphVisualizerComponent, {
        remove: { imports: [GraphCanvasComponent, ConfirmDialogComponent, CompositeEntityDialogComponent] },
        add: { imports: [GraphCanvasStubComponent, ConfirmDialogStubComponent, CompositeEntityDialogStubComponent] }
      })
      .overrideComponent(GraphVisualizerComponent, {
        set: { schemas: [NO_ERRORS_SCHEMA] }
      })
      .overrideProvider(GraphService, { useValue: graphServiceSpy })
      .overrideProvider(SourceWeightService, { useValue: weightServiceSpy })
      .overrideProvider(AttributionService, { useValue: attributionSpy })
      .overrideProvider(ProcessEngineService, { useValue: processEngineSpy })
      .overrideProvider(KbGroundingService, { useValue: groundingSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .overrideProvider(MatDialog, { useValue: dialogSpy })
      .compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  /** Shows the sheet, linked to focusNodeId if given, and lets the first load land. */
  function create(focusNodeId?: string): void {
    fixture = TestBed.createComponent(GraphVisualizerComponent);
    component = fixture.componentInstance;
    component.autoRefresh = false; // a refresh would reload the graph under the test
    component.showSidePanel = false;
    fixture.componentRef.setInput('factSheetId', SHEET);
    if (focusNodeId) {
      fixture.componentRef.setInput('focusNodeId', focusNodeId);
    }
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  function canvas(): GraphCanvasStubComponent {
    return fixture.debugElement.query(By.directive(GraphCanvasStubComponent)).componentInstance;
  }

  function drawnNodeIds(): string[] {
    return (canvas().data?.nodes ?? []).map(n => n.id).sort();
  }

  function drawnLinkKeys(): string[] {
    return (canvas().data?.links ?? []).map(link => visualizationLinkKey(link)).sort();
  }

  function select(nodeId: string): void {
    canvas().nodeSelected.emit(node(nodeId));
  }

  /** Double-clicks the node on the canvas, which expands it. */
  function expand(nodeId: string): void {
    canvas().nodeDoubleClicked.emit(node(nodeId));
    tick();
    fixture.detectChanges();
  }

  function changeSheet(factSheetId: number): void {
    fixture.componentRef.setInput('factSheetId', factSheetId);
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
  }

  /** Opens the focal view around n1. */
  function openFocalView(): void {
    select('n1');
    component.buildFocalView();
    httpMock.expectOne(`/api/graph/${SHEET}/subgraph`).flush(focalResult());
    fixture.detectChanges();
  }

  describe('a linked node', () => {
    it('fetches the node\'s neighborhood in the sheet and draws it with the graph', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('x', 'n1')));
      create('x');

      expect(graphServiceSpy.getNodeNeighborhood).toHaveBeenCalledOnceWith('x', 50, undefined, SHEET);
      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'x']);
      expect(drawnLinkKeys()).toEqual(['e1', 'x::n1::SHARED_ENTITY']);
      expect(canvas().focusNodeId).toBe('x');
    }));

    it('becomes the selected node once the canvas centers it', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('x', 'n1')));
      create('x');

      canvas().focused.emit(node('x'));
      fixture.detectChanges();

      expect(component.selectedNode?.id).toBe('x');
      expect(graphServiceSpy.getEdges).toHaveBeenCalledWith('x');
      expect(component.focusRequest).toBeNull();
      expect(canvas().focusNodeId).toBeNull();
    }));

    it('is reported when the sheet does not have it', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('a', 'b')));
      create('missing');

      expect(snackBarSpy.open).toHaveBeenCalledWith('Node missing is not in this graph', 'Dismiss', { duration: 4000 });
      expect(component.focusRequest).toBeNull();
      expect(canvas().focusNodeId).toBeNull();
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));

    it('stays requested when its neighborhood fails to load', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.getNodeNeighborhood.and.returnValue(throwError(() => new Error('down')));
      create('x');

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to load the linked node', 'Dismiss', { duration: 3000 });
      expect(component.focusRequest).toBe('x');
      expect(canvas().focusNodeId).toBe('x');
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));

    it('drops the neighborhood of a linked node since replaced', fakeAsync(() => {
      const pending: Record<string, Subject<D3VisualizationData>> = { x: new Subject(), y: new Subject() };
      graphServiceSpy.getNodeNeighborhood.and.callFake((nodeId: string) => pending[nodeId]);
      create('x');
      fixture.componentRef.setInput('focusNodeId', 'y');
      fixture.detectChanges();

      pending['x'].next(neighborhood('x', 'n5'));
      pending['y'].next(neighborhood('y', 'n6'));
      fixture.detectChanges();

      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n6', 'y']);
      expect(canvas().focusNodeId).toBe('y');
    }));
  });

  describe('an expanded neighborhood', () => {
    it('is fetched in the sheet and placed around the nodes already drawn', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('n1', 'n7')));
      create();

      expand('n1');

      expect(graphServiceSpy.getNodeNeighborhood).toHaveBeenCalledOnceWith('n1', 50, undefined, SHEET);
      const placed = canvas().addNodesToGraph.calls.mostRecent().args[0] as D3VisualizationData;
      expect(placed.nodes.map(n => n.id)).toEqual(['n1', 'n7']);
      expect(placed.links).toEqual([]);
      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n7']);
      expect(drawnLinkKeys()).toEqual(['e1', 'n1::n7::SHARED_ENTITY']);
    }));

    it('is reported when it fails to load', fakeAsync(() => {
      spyOn(console, 'error');
      graphServiceSpy.getNodeNeighborhood.and.returnValue(throwError(() => new Error('down')));
      create();

      expand('n1');

      expect(snackBarSpy.open).toHaveBeenCalledWith('Failed to expand node', 'Dismiss', { duration: 3000 });
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));

    it('is kept through a reload', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('n1', 'n7')));
      create();
      expand('n1');

      component.loadGraph();
      tick();
      fixture.detectChanges();

      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n7']);
      expect(drawnLinkKeys()).toEqual(['e1', 'n1::n7::SHARED_ENTITY']);
    }));

    it('is kept through a live refresh', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('n1', 'n7')));
      create();
      expand('n1');

      graphServiceSpy.getFactSheetVisualizationData.and.callFake(() => of(grown()));
      component.autoRefresh = true;
      tick(5000);
      fixture.detectChanges();

      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n3', 'n7']);
    }));

    it('loses a deleted node for good', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('n1', 'n7')));
      create();
      expand('n1');

      select('n7');
      component.deleteNode();
      tick();
      fixture.detectChanges();

      expect(graphServiceSpy.deleteNode).toHaveBeenCalledOnceWith('n7');
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
      expect(drawnLinkKeys()).toEqual(['e1']);
    }));

    it('loses a deleted relation for good, in both directions', fakeAsync(() => {
      // The expand view holds a bidirectional edge once per direction
      const both = neighborhood('n1', 'n7');
      both.links.push(sharedEntity('n7', 'n1'));
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(both));
      create();
      expand('n1');
      expect(drawnLinkKeys()).toContain('n7::n1::SHARED_ENTITY');

      const relation: GraphEdge = {
        id: 1,
        edgeId: 'n1::n7::SHARED_ENTITY',
        sourceNodeId: 'n1',
        targetNodeId: 'n7',
        edgeType: 'SHARED_ENTITY',
        weight: 1,
        bidirectional: true,
        source: 'n1',
        target: 'n7'
      };
      component.deleteRelation(relation);
      tick();
      fixture.detectChanges();

      expect(graphServiceSpy.deleteEdge).toHaveBeenCalledOnceWith('n1::n7::SHARED_ENTITY');
      expect(drawnLinkKeys()).toEqual(['e1']);
      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n7']);
    }));

    it('is dropped when it arrives after the sheet changed', fakeAsync(() => {
      const pending = new Subject<D3VisualizationData>();
      graphServiceSpy.getNodeNeighborhood.and.returnValue(pending);
      create();
      expand('n1');

      changeSheet(8);
      pending.next(neighborhood('n1', 'n7'));
      fixture.detectChanges();

      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
      expect(canvas().addNodesToGraph).not.toHaveBeenCalled();
    }));

    it('is not carried over to another sheet', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('n1', 'n7')));
      create();
      expand('n1');
      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n7']);

      changeSheet(8);

      expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalledWith(8, component.maxNodes);
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));
  });

  describe('the focal view', () => {
    it('stays open through a reload and shows the latest graph on exit', fakeAsync(() => {
      create();
      openFocalView();
      expect(component.focalViewActive).toBeTrue();
      expect(drawnNodeIds()).toEqual(['f1', 'n1']);

      graphServiceSpy.getFactSheetVisualizationData.and.callFake(() => of(grown()));
      component.loadGraph();
      tick();
      fixture.detectChanges();
      expect(component.focalViewActive).toBeTrue();
      expect(drawnNodeIds()).toEqual(['f1', 'n1']);

      component.exitFocalView();
      fixture.detectChanges();
      expect(drawnNodeIds()).toEqual(['n1', 'n2', 'n3']);
    }));

    it('adds a neighborhood expanded inside it and keeps it on exit', fakeAsync(() => {
      graphServiceSpy.getNodeNeighborhood.and.returnValue(of(neighborhood('f1', 'n9')));
      create();
      openFocalView();

      expand('f1');
      expect(drawnNodeIds()).toEqual(['f1', 'n1', 'n9']);

      component.exitFocalView();
      fixture.detectChanges();
      expect(drawnNodeIds()).toEqual(['f1', 'n1', 'n2', 'n9']);
    }));

    it('loses a deleted node', fakeAsync(() => {
      create();
      openFocalView();

      select('f1');
      component.deleteNode();
      tick();
      fixture.detectChanges();

      expect(component.focalViewActive).toBeTrue();
      expect(drawnNodeIds()).toEqual(['n1']);
      expect(drawnLinkKeys()).toEqual([]);
    }));

    it('closes when the sheet changes', fakeAsync(() => {
      create();
      openFocalView();

      changeSheet(8);

      expect(component.focalViewActive).toBeFalse();
      expect(component.selectedNode).toBeNull();
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));

    it('is dropped when it arrives after the sheet changed', fakeAsync(() => {
      create();
      select('n1');
      component.buildFocalView();
      const request = httpMock.expectOne(`/api/graph/${SHEET}/subgraph`);
      expect(request.request.body.seedNodeIds).toEqual(['n1']);

      changeSheet(8);
      request.flush(focalResult());
      fixture.detectChanges();

      expect(component.focalViewActive).toBeFalse();
      expect(component.focalViewLoading).toBeFalse();
      expect(drawnNodeIds()).toEqual(['n1', 'n2']);
    }));
  });
});
