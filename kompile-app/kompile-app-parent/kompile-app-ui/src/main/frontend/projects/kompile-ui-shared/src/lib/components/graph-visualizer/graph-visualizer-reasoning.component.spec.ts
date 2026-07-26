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

import { Component, EventEmitter, Input, NO_ERRORS_SCHEMA, Output } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER, of } from 'rxjs';

import { GraphVisualizerComponent } from './graph-visualizer.component';
import { GraphCanvasComponent } from './graph-canvas.component';
import { GraphService } from '../../services/graph.service';
import { SourceWeightService } from '../../services/source-weight.service';
import { AttributionService } from '../../services/attribution.service';
import { ProcessEngineService } from '../../services/process-engine.service';
import { KbGroundingService } from '../../services/kb-grounding.service';
import {
  D3Node,
  D3VisualizationData,
  EdgeType,
  NodeLevel,
  ReasoningLayers
} from '../../models/graph-models';

@Component({
  selector: 'app-graph-canvas',
  standalone: true,
  template: ''
})
class GraphCanvasStubComponent {
  @Input() data: any;
  @Input() forceConfig: any;
  @Input() linkMode = false;
  @Input() showLegend = false;
  @Input() reasoningLayerOverlayEnabled = false;
  @Input() reasoningNodeLayerMap: Map<string, any> = new Map();
  @Input() reasoningEdgeLayerMap: Map<string, any> = new Map();
  @Input() processEvidenceNodeIds: ReadonlySet<string> = new Set();
  @Input() processEvidenceEdgeIds: ReadonlySet<string> = new Set();
  @Output() nodeSelected = new EventEmitter<D3Node | null>();
  @Output() nodeDoubleClicked = new EventEmitter<D3Node>();
  @Output() edgeCreated = new EventEmitter<{ source: string; target: string }>();
  @Output() nodeContextMenu = new EventEmitter<{ node: D3Node; event: MouseEvent }>();
  @Output() linkSourceChanged = new EventEmitter<D3Node | null>();
}

const graphData: D3VisualizationData = {
  nodes: [
    { id: 'n2', type: 'DOCUMENT', label: 'Doc 1' },
    { id: 'n3', type: 'ENTITY', label: 'Entity 1' }
  ],
  links: [
    {
      id: 'edge-2', source: 'n2', target: 'n3', type: 'SHARED_ENTITY', weight: 0.8,
      metadata: { suggestionId: 'process-1' }
    }
  ],
  statistics: { totalNodes: 2, totalEdges: 1 }
};

const graphDataWithNewNode: D3VisualizationData = {
  nodes: [
    ...graphData.nodes,
    { id: 'n4', type: 'ENTITY', label: 'Entity 2' }
  ],
  links: graphData.links,
  statistics: { totalNodes: 3, totalEdges: 1 }
};

const reasoningLayers: ReasoningLayers = {
  factSheetId: 42,
  nodes: [
    {
      nodeId: 'n3',
      nodeType: 'ENTITY' as NodeLevel,
      label: 'Entity 1',
      ontology: {
        declaredType: 'Person',
        inferredTypes: ['Agent'],
        typeCandidates: [
          { type: 'Agent', confidence: 0.91, source: 'psl', basis: 'domain' },
          { type: 'Organization', confidence: 0.38, source: 'neural', basis: 'label' }
        ],
        typeHierarchy: [
          {
            type: 'RedWine',
            parentType: 'Wine',
            depth: 1,
            confidence: 0.93,
            source: 'crawl-schema',
            basis: 'entity_type/entity_category'
          }
        ],
        inferredRelations: [
          {
            relationType: 'IS_A',
            sourceType: 'RedWine',
            targetType: 'Wine',
            confidence: 0.93,
            inferenceSource: 'crawl-schema',
            basis: 'entity_type/entity_category'
          }
        ],
        conformant: false,
        violations: ['Missing required identifier']
      },
      psl: {
        ruleId: 'r1',
        truthValue: 0.82,
        incompatibility: 0.12,
        bindings: ['A=n2', 'B=n3']
      },
      mebn: {
        mfrag: 'IdentityMFrag',
        residentVariable: 'IsSameEntity',
        prior: 0.4,
        posterior: 0.91,
        findings: ['observed-name-match']
      },
      provenance: { details: { sourceDocument: 'doc-1' } },
      opinion: { belief: 0.7, disbelief: 0.1, uncertainty: 0.2, confidence: 0.8 },
      neuralScores: { scores: { kgEmbeddingScore: 0.93 }, embeddingAlgorithm: 'SameDiffTransE', embeddingVersion: 2 }
    }
  ],
  edges: [
    {
      edgeId: 'edge-2',
      sourceNodeId: 'n2',
      targetNodeId: 'n3',
      edgeType: 'SHARED_ENTITY' as EdgeType,
      weight: 0.8,
      psl: { ruleId: 'edge-rule', truthValue: 0.77 },
      neuralScores: { scores: { linkPredictionScore: 0.88 } }
    }
  ],
  statistics: {
    nodeCount: 1,
    edgeCount: 1,
    ontologyCount: 1,
    pslCount: 2,
    mebnCount: 1,
    provenanceCount: 1,
    opinionCount: 1,
    neuralScoreCount: 2,
    typeCandidateCount: 2,
    typeHierarchyCount: 1,
    inferredRelationCount: 1
  }
};

describe('GraphVisualizerComponent reasoning layers', () => {
  let fixture: ComponentFixture<GraphVisualizerComponent>;
  let component: GraphVisualizerComponent;
  let graphServiceSpy: jasmine.SpyObj<GraphService>;
  let processEngineSpy: jasmine.SpyObj<ProcessEngineService>;

  beforeEach(async () => {
    graphServiceSpy = jasmine.createSpyObj<GraphService>('GraphService', [
      'getStatistics',
      'getFactSheetStatistics',
      'getVisualizationData',
      'getFactSheetVisualizationData',
      'getTopKVisualization',
      'getTemporalBounds',
      'getReasoningLayers',
      'getEdges',
      'getSourceConnectivity',
      'getSourceLinks',
      'findMostConnectedSources',
      'findIsolatedSources',
      'linkSources',
      'removeSourceLink'
    ]);
    graphServiceSpy.getStatistics.and.returnValue(of({ totalNodes: 2 }));
    graphServiceSpy.getFactSheetStatistics.and.returnValue(of({ totalNodes: 2 }));
    graphServiceSpy.getVisualizationData.and.returnValue(of(graphData));
    graphServiceSpy.getFactSheetVisualizationData.and.returnValue(of(graphData));
    graphServiceSpy.getTopKVisualization.and.returnValue(of(graphData));
    graphServiceSpy.getTemporalBounds.and.returnValue(of(null as any));
    graphServiceSpy.getReasoningLayers.and.returnValue(of(reasoningLayers));
    graphServiceSpy.getEdges.and.returnValue(of([]));
    graphServiceSpy.getSourceConnectivity.and.returnValue(of({}));
    graphServiceSpy.getSourceLinks.and.returnValue(of([]));
    graphServiceSpy.findMostConnectedSources.and.returnValue(of([]));
    graphServiceSpy.findIsolatedSources.and.returnValue(of([]));
    graphServiceSpy.linkSources.and.returnValue(of({}));
    graphServiceSpy.removeSourceLink.and.returnValue(of(undefined as any));

    processEngineSpy = jasmine.createSpyObj<ProcessEngineService>('ProcessEngineService', [
      'listStoredSuggestions', 'getStoredSuggestionTrace', 'mineProcesses', 'listOntologies'
    ]);
    processEngineSpy.listStoredSuggestions.and.returnValue(of({
      count: 2,
      suggestions: [
        { id: 'process-2', name: 'Second process', confidence: 0.94, reasoningRank: 2 },
        {
          id: 'process-1', name: 'First process', confidence: 0.82, reasoningRank: 1,
          reasoningProjection: 'RELATION_EVENTS', reasoningFamily: 'communication',
          sourceGraphNodeIds: ['n2'], sourceGraphRelationIds: ['explicit-edge'],
          reasoningTraceId: 'trace-process-1',
          hybridReasoning: {
            interpretation: 'ACTIVITY_ACTIVATION_CONSENSUS', score: 0.7,
            pslScore: 0.68, bayesianScore: 0.72,
            pslStructuralScore: 0.68, bayesianStructuralScore: 0.72, semanticScore: 0,
            semanticMode: 'STRUCTURAL_ONLY', embeddedActivityCount: 0, activityCount: 2,
            structuralWeight: 1, semanticWeight: 0,
            pslAvailable: true, bayesianAvailable: true, warnings: [],
            activities: [
              { activity: 'Send', score: 0.76, pslScore: 0.74, bayesianScore: 0.78,
                pslStructuralScore: 0.74, bayesianStructuralScore: 0.78,
                semanticScore: 0, embedded: false },
              { activity: 'Receive', score: 0.64, pslScore: 0.62, bayesianScore: 0.66,
                pslStructuralScore: 0.62, bayesianStructuralScore: 0.66,
                semanticScore: 0, embedded: false }
            ]
          }
        }
      ]
    }));
    processEngineSpy.getStoredSuggestionTrace.and.returnValue(of({
      size: 2,
      depth: 2,
      conclusion: {
        kind: 'CONCLUSION', conclusion: 'First process', confidence: 0.9,
        premises: [{ kind: 'EVIDENCE', conclusion: 'edge-2 observed', confidence: 0.8 }]
      }
    }));
    processEngineSpy.mineProcesses.and.returnValue(of(null));
    processEngineSpy.listOntologies.and.returnValue(of([]));

    const sourceWeightSpy = jasmine.createSpyObj<SourceWeightService>('SourceWeightService', ['getWeights']);
    sourceWeightSpy.getWeights.and.returnValue(of([]));

    const snackBarSpy = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snackBarSpy.open.and.returnValue({ onAction: () => NEVER } as any);

    const dialogSpy = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    const attributionSpy = jasmine.createSpyObj<AttributionService>('AttributionService', ['explainQuick', 'predictQuick']);
    const groundingSpy = jasmine.createSpyObj<KbGroundingService>('KbGroundingService', ['batchVerify']);
    groundingSpy.batchVerify.and.returnValue(of({
      results: {},
      totalCount: 0,
      factSheetId: 42,
      timestamp: '2026-06-29T00:00:00Z'
    }));

    await TestBed.configureTestingModule({
      imports: [GraphVisualizerComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    })
      .overrideComponent(GraphVisualizerComponent, {
        remove: { imports: [GraphCanvasComponent] },
        add: { imports: [GraphCanvasStubComponent] }
      })
      .overrideComponent(GraphVisualizerComponent, {
        set: { schemas: [NO_ERRORS_SCHEMA] }
      })
      .overrideProvider(GraphService, { useValue: graphServiceSpy })
      .overrideProvider(SourceWeightService, { useValue: sourceWeightSpy })
      .overrideProvider(AttributionService, { useValue: attributionSpy })
      .overrideProvider(ProcessEngineService, { useValue: processEngineSpy })
      .overrideProvider(KbGroundingService, { useValue: groundingSpy })
      .overrideProvider(MatSnackBar, { useValue: snackBarSpy })
      .overrideProvider(MatDialog, { useValue: dialogSpy })
      .compileComponents();
  });

  function create(): void {
    fixture = TestBed.createComponent(GraphVisualizerComponent);
    component = fixture.componentInstance;
    component.factSheetId = 42;
    component.showSidePanel = false;
    fixture.detectChanges();
  }

  it('loads reasoning layers for fact-sheet graphs', fakeAsync(() => {
    create();
    tick();

    expect(graphServiceSpy.getReasoningLayers).toHaveBeenCalledWith(42);
    expect(component.reasoningLayers).toEqual(reasoningLayers);
  }));

  it('treats an empty fact-sheet graph as valid without loading optional overlays', fakeAsync(() => {
    graphServiceSpy.getFactSheetStatistics.and.returnValue(of({ totalNodes: 0, totalEdges: 0 }));
    graphServiceSpy.getFactSheetVisualizationData.and.returnValue(of({
      nodes: [],
      links: [],
      statistics: { totalNodes: 0, totalEdges: 0 }
    }));

    create();
    tick();

    expect(graphServiceSpy.getFactSheetVisualizationData).toHaveBeenCalledWith(42, 500);
    expect(graphServiceSpy.getReasoningLayers).not.toHaveBeenCalled();
    expect(component.reasoningLayers?.factSheetId).toBe(42);
    expect(component.reasoningLayers?.nodes).toEqual([]);
    expect(component.reasoningLayers?.edges).toEqual([]);
    expect(component.reasoningLayersError).toBeNull();
  }));

  it('maps node overlays for inspector and canvas rendering', fakeAsync(() => {
    create();
    tick();

    const nodeReasoning = component.reasoningNodeMap.get('n3');
    const visual = component.reasoningNodeLayerMap.get('n3');

    expect(nodeReasoning?.ontology?.declaredType).toBe('Person');
    expect(nodeReasoning?.ontology?.typeCandidates?.[0]?.type).toBe('Agent');
    expect(nodeReasoning?.ontology?.typeCandidates?.[0]?.confidence).toBe(0.91);
    expect(nodeReasoning?.ontology?.typeHierarchy?.[0]?.type).toBe('RedWine');
    expect(nodeReasoning?.ontology?.typeHierarchy?.[0]?.parentType).toBe('Wine');
    expect(nodeReasoning?.ontology?.inferredRelations?.[0]?.relationType).toBe('IS_A');
    expect(visual?.activeLayers).toContain('ontology');
    expect(visual?.activeLayers).toContain('psl');
    expect(visual?.activeLayers).toContain('mebn');
    expect(visual?.activeLayers).toContain('provenance');
    expect(visual?.activeLayers).toContain('opinion');
    expect(visual?.activeLayers).toContain('neural');
    expect(visual?.ontologyViolation).toBeTrue();
    expect(visual?.inferredRelationship).toBeTrue();
    expect(visual?.inferredRelationshipScore).toBe(0.93);
    expect(visual?.hierarchyDepth).toBe(1);
    expect(visual?.mebnPosterior).toBe(0.91);
    expect(visual?.neuralScore).toBe(0.93);
    expect(component.reasoningViolationCount).toBe(1);
  }));

  it('renders ontology type candidates in the selected-node reasoning panel', fakeAsync(() => {
    create();
    tick();
    component.showSidePanel = true;
    component.selectedTabIndex = 1;
    component.selectedNode = graphData.nodes[1];
    fixture.detectChanges();
    tick();

    const text = (fixture.nativeElement as HTMLElement).textContent || '';

    expect(text).toContain('Agent');
    expect(text).toContain('0.91');
    expect(text).toContain('psl / domain');
    expect(text).toContain('Organization');
    expect(text).toContain('neural / label');
    expect(text).toContain('RedWine -> Wine');
    expect(text).toContain('RedWine IS_A Wine');
    expect(text).toContain('crawl-schema / entity_type/entity_category');
  }));

  it('maps edge overlays and respects per-layer toggles', fakeAsync(() => {
    create();
    tick();

    expect(component.reasoningEdgeLayerMap.get('edge-2')?.neuralScore).toBe(0.88);

    component.setReasoningLayer('neural', false);

    expect(component.reasoningNodeLayerMap.get('n3')?.activeLayers).not.toContain('neural');
    expect(component.reasoningEdgeLayerMap.get('edge-2')?.activeLayers).not.toContain('neural');
  }));

  it('loads layers when the canvas overlay is explicitly enabled', fakeAsync(() => {
    create();
    tick();
    component.reasoningLayers = null;
    graphServiceSpy.getReasoningLayers.calls.reset();

    component.toggleReasoningLayerOverlay(true);
    tick();

    expect(component.reasoningLayerOverlayEnabled).toBeTrue();
    expect(graphServiceSpy.getReasoningLayers).toHaveBeenCalledWith(42);
  }));

  it('refreshes reasoning layers on manual graph reload even when canvas overlay is off', fakeAsync(() => {
    create();
    tick();
    graphServiceSpy.getReasoningLayers.calls.reset();
    component.reasoningLayerOverlayEnabled = false;
    component.reasoningLayers = reasoningLayers;

    component.loadGraph();
    tick();

    expect(graphServiceSpy.getReasoningLayers).toHaveBeenCalledWith(42);
  }));

  it('refreshes reasoning layers when auto-refresh detects graph changes', fakeAsync(() => {
    let visualizationCalls = 0;
    // create() sets factSheetId = 42, so both the initial loadGraph() and the auto-refresh tick go
    // through getFactSheetVisualizationData — stubbing getVisualizationData here would leave the
    // fact-sheet stub returning a constant payload, the signature unchanged, and the refresh
    // short-circuited before it ever reaches the reasoning-layer reload this test is about.
    graphServiceSpy.getFactSheetVisualizationData.and.callFake(
      () => of(visualizationCalls++ === 0 ? graphData : graphDataWithNewNode));
    create();
    tick();
    graphServiceSpy.getReasoningLayers.calls.reset();
    component.reasoningLayers = reasoningLayers;
    component.reasoningLayerOverlayEnabled = true;

    tick(5000);

    expect(component.graphData?.nodes.length).toBe(3);
    expect(graphServiceSpy.getReasoningLayers).toHaveBeenCalledWith(42);
  }));

  it('maps id-less edge overlays with backend and canvas fallback keys', fakeAsync(() => {
    create();
    tick();
    component.reasoningLayers = {
      ...reasoningLayers,
      edges: [
        {
          edgeId: '',
          sourceNodeId: 'n2',
          targetNodeId: 'n3',
          edgeType: 'SHARED_ENTITY' as EdgeType,
          weight: 0.8,
          psl: { ruleId: 'fallback-rule', truthValue: 0.71 }
        }
      ]
    };

    (component as any).rebuildReasoningLayerMaps();

    expect(component.reasoningEdgeLayerMap.get('n2->n3:SHARED_ENTITY')?.pslTruthValue).toBe(0.71);
    expect(component.reasoningEdgeLayerMap.get('n2→n3:SHARED_ENTITY')?.pslTruthValue).toBe(0.71);
  }));

  it('loads ranked process candidates and resolves their graph evidence', fakeAsync(() => {
    create();
    tick();

    expect(processEngineSpy.listStoredSuggestions).toHaveBeenCalledWith(42);
    expect(component.processCandidates.map(candidate => candidate.id)).toEqual([
      'process-1', 'process-2'
    ]);
    expect(component.selectedProcessCandidate?.id).toBe('process-1');
    expect([...component.processEvidenceNodeIds].sort()).toEqual(['n2', 'n3']);
    expect([...component.processEvidenceEdgeIds].sort()).toEqual(['edge-2', 'explicit-edge']);
  }));

  it('loads and flattens the selected process reasoning trace', fakeAsync(() => {
    create();
    tick();

    component.loadSelectedProcessTrace();
    tick();

    expect(processEngineSpy.getStoredSuggestionTrace).toHaveBeenCalledWith('process-1');
    expect(component.selectedProcessTraceSteps().map(step => step.conclusion)).toEqual([
      'First process', 'edge-2 observed'
    ]);
    expect(component.selectedProcessTraceSteps().map(step => step.depthLevel)).toEqual([0, 1]);
  }));

  it('exposes the selected process hybrid mode and ranked activity scores', fakeAsync(() => {
    create();
    tick();
    const selected = component.selectedProcessCandidate!;

    expect(component.processHybridMode(selected.hybridReasoning!)).toBe('Structural only');
    expect(component.processHybridActivities(selected).map(activity => activity.activity))
      .toEqual(['Send', 'Receive']);
  }));
});
