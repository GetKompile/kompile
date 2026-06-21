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

import { Component, OnInit, OnDestroy, OnChanges, SimpleChanges, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, takeUntil } from 'rxjs';

import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphNode } from '../../models/graph-models';
import { GraphOverviewComponent } from '../graph-overview/graph-overview.component';
import { GraphMaintenanceHubComponent } from '../graph-maintenance-hub/graph-maintenance-hub.component';
import { GraphHealthPanelComponent } from '../graph-health-panel/graph-health-panel.component';
import { GraphRulesPanelComponent } from '../graph-rules-panel/graph-rules-panel.component';
import { GraphOntologyPanelComponent } from '../graph-ontology-panel/graph-ontology-panel.component';
import { GraphIoPanelComponent } from '../graph-io-panel/graph-io-panel.component';
import { GraphPipelinesPanelComponent } from '../graph-pipelines-panel/graph-pipelines-panel.component';
import { GraphProvenancePanelComponent } from '../graph-provenance-panel/graph-provenance-panel.component';
import { GraphDiffPanelComponent } from '../graph-diff-panel/graph-diff-panel.component';
import { GraphEvalDebuggerComponent } from '../graph-eval-debugger/graph-eval-debugger.component';
import { GraphDataPatchComponent } from '../graph-data-patch/graph-data-patch.component';
import { MultiAgentGraphExtractionComponent } from '../multi-agent-graph-extraction/multi-agent-graph-extraction.component';
import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { EntityBrowserComponent } from '../entity-browser/entity-browser.component';
import { KnowledgeGraphBuilderComponent } from '../knowledge-graph-builder/knowledge-graph-builder.component';
import { GraphHierarchyComponent } from '../graph-hierarchy/graph-hierarchy.component';
import { EventObservationDashboardComponent } from '../event-observation/event-observation-dashboard.component';
import { CausalAttributionPanelComponent } from '../event-observation/causal-attribution-panel.component';
import { GroundingConsolePanelComponent } from '../grounding-console-panel/grounding-console-panel.component';
import { ProcessMiningPanelComponent } from '../process-mining-panel/process-mining-panel.component';
import { AuditTimelineComponent } from '../audit-timeline/audit-timeline.component';
import { HydrationProgressPanelComponent } from '../hydration-progress-panel/hydration-progress-panel.component';
import { FactsByTierPanelComponent } from '../facts-by-tier-panel/facts-by-tier-panel.component';
import { KbWeightsPanelComponent } from '../kb-weights-panel/kb-weights-panel.component';
import { ProcessLineagePanelComponent } from '../process-lineage-panel/process-lineage-panel.component';

type GraphsTab = 'visualizer' | 'entityBrowser' | 'hierarchy' | 'builder'
  | 'eventObservation' | 'causalAttribution'
  | 'overview' | 'health' | 'ontology' | 'pipelines' | 'provenance' | 'diff'
  | 'maintenance' | 'rules' | 'extract' | 'patch' | 'eval' | 'io'
  | 'grounding' | 'processes' | 'audit' | 'hydration'
  | 'factsByTier' | 'weights' | 'processLineage';

/**
 * Unified "Graphs" workspace — a first-class home for knowledge graphs as assets. Combines the
 * interactive explorer views (visualizer, entity browser, hierarchy, builder — previously the
 * separate knowledge-graph-hub), the probabilistic reasoning views (event observation, causal
 * attribution — previously buried under Tools) and the graph-as-asset management panels (overview
 * registry, health, ontology, provenance, diff, maintenance, rules, extraction, data patch, eval,
 * import / export). Hosted under the Index Browser's "Graph" tab. (graph-as-asset Phase 8.)
 */
@Component({
  selector: 'app-graphs-hub',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatSnackBarModule,
    GraphOverviewComponent,
    GraphMaintenanceHubComponent,
    GraphHealthPanelComponent,
    GraphRulesPanelComponent,
    GraphOntologyPanelComponent,
    GraphIoPanelComponent,
    GraphPipelinesPanelComponent,
    GraphProvenancePanelComponent,
    GraphDiffPanelComponent,
    GraphEvalDebuggerComponent,
    GraphDataPatchComponent,
    MultiAgentGraphExtractionComponent,
    GraphVisualizerComponent,
    EntityBrowserComponent,
    KnowledgeGraphBuilderComponent,
    GraphHierarchyComponent,
    EventObservationDashboardComponent,
    CausalAttributionPanelComponent,
    GroundingConsolePanelComponent,
    ProcessMiningPanelComponent,
    AuditTimelineComponent,
    HydrationProgressPanelComponent,
    FactsByTierPanelComponent,
    KbWeightsPanelComponent,
    ProcessLineagePanelComponent
  ],
  templateUrl: './graphs-hub.component.html',
  styleUrls: ['./graphs-hub.component.css']
})
export class GraphsHubComponent implements OnInit, OnDestroy, OnChanges {
  /** When set (e.g. from the Index Browser "View in Graph" action) jump to the visualizer. */
  @Input() focusNodeId: string | null = null;

  activeSubTab: GraphsTab = 'visualizer';
  activeFactSheet: FactSheet | null = null;
  activeFactSheetId: number | null = null;

  /** Node id handed to the Causal Attribution panel when "Use in Causal Attribution" is clicked. */
  attributionSeedNodeId: string | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheet => {
        this.activeFactSheet = sheet;
        this.activeFactSheetId = sheet ? sheet.id : null;
      });
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['focusNodeId'] && this.focusNodeId) {
      this.activeSubTab = 'visualizer';
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectSubTab(tab: GraphsTab): void {
    this.activeSubTab = tab;
  }

  onEntitySelected(entity: GraphNode): void {
    // Selection surfaces detail within the entity browser itself; hook kept for parity.
    console.log('Entity selected:', entity);
  }

  onNavigateToGraph(entity: GraphNode): void {
    this.activeSubTab = 'visualizer';
    this.snackBar.open(`Showing "${entity.title || entity.nodeId}" in graph`, 'Dismiss', { duration: 2000 });
  }

  /** Event Observation → "Use in Causal Attribution": seed the node and switch to that tab. */
  onAttributeNode(nodeId: string): void {
    if (!nodeId) {
      return;
    }
    this.attributionSeedNodeId = nodeId;
    this.activeSubTab = 'causalAttribution';
  }
}
