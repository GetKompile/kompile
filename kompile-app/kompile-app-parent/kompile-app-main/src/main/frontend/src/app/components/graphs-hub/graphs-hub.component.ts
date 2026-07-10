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
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, ActivatedRoute } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphNode } from '../../models/graph-models';
import { GraphOverviewComponent } from '../graph-overview/graph-overview.component';
import { GraphMaintenanceHubComponent } from '../graph-maintenance-hub/graph-maintenance-hub.component';
import { GraphHealthPanelComponent } from '../graph-health-panel/graph-health-panel.component';
import { GraphRulesPanelComponent } from '../graph-rules-panel/graph-rules-panel.component';
import { GraphIoPanelComponent } from '../graph-io-panel/graph-io-panel.component';
import { GraphPipelinesPanelComponent } from '../graph-pipelines-panel/graph-pipelines-panel.component';
import { GraphProvenancePanelComponent } from '../graph-provenance-panel/graph-provenance-panel.component';
import { GraphDiffPanelComponent } from '../graph-diff-panel/graph-diff-panel.component';
import { GraphEvalDebuggerComponent } from '../graph-eval-debugger/graph-eval-debugger.component';
import { GraphDataPatchComponent } from '../graph-data-patch/graph-data-patch.component';
import { MultiAgentGraphExtractionComponent } from '../multi-agent-graph-extraction/multi-agent-graph-extraction.component';
import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { KnowledgeGraphBuilderComponent } from '../knowledge-graph-builder/knowledge-graph-builder.component';
import { GraphHierarchyComponent } from '../graph-hierarchy/graph-hierarchy.component';
import { EventObservationDashboardComponent } from '../event-observation/event-observation-dashboard.component';
import { CausalAttributionPanelComponent } from '../event-observation/causal-attribution-panel.component';
import { GroundingConsolePanelComponent } from '../grounding-console-panel/grounding-console-panel.component';
import { AuditTimelineComponent } from '../audit-timeline/audit-timeline.component';
import { GroundingMonitorComponent } from '../grounding-monitor/grounding-monitor.component';
import { GraphSimulatorComponent } from '../graph-simulator/graph-simulator.component';
import { FactsByTierPanelComponent } from '../facts-by-tier-panel/facts-by-tier-panel.component';
import { KbWeightsPanelComponent } from '../kb-weights-panel/kb-weights-panel.component';
import { OpinionBrowserComponent } from '../opinion-browser/opinion-browser.component';
import { CommunityPanelComponent } from '../community-panel/community-panel.component';
import { FolRulesBrowserComponent } from '../fol-rules-browser/fol-rules-browser.component';
import { GraphOntologyPanelComponent } from '../graph-ontology-panel/graph-ontology-panel.component';
import { ProcessOntologyComponent } from '../process-engine/process-ontology.component';
import { TaxonomyBrowserComponent } from '../taxonomy-browser/taxonomy-browser.component';
import { CategoryManagerComponent } from '../category-manager/category-manager.component';

/** Outer sections for the two-level navigation. */
export type GraphsSection = 'explore' | 'build' | 'reason' | 'audit' | 'ontology';

/** Every leaf tab, keyed by the value used in query-params (?tab=). */
export type GraphsTab =
  // explore
  'visualizer' | 'hierarchy' | 'overview' | 'communities' |
  // build
  'builder' | 'extract' | 'patch' | 'io' | 'pipelines' | 'maintenance' |
  // reason
  'grounding' | 'liveReasoning' | 'simulator' | 'rules' | 'folRules' |
  'weights' | 'opinions' | 'factsByTier' | 'causalAttribution' | 'eventObservation' |
  // audit
  'provenance' | 'diff' | 'audit' | 'health' | 'eval' |
  // ontology (inner tabs rendered by mat-tab-group inside the panel)
  'ontology';

/** Map each section to its ordered set of leaf tabs. */
const SECTION_TABS: Record<GraphsSection, GraphsTab[]> = {
  explore:  ['visualizer', 'hierarchy', 'overview', 'communities'],
  build:    ['builder', 'extract', 'patch', 'io', 'pipelines', 'maintenance'],
  reason:   ['grounding', 'liveReasoning', 'simulator', 'rules', 'folRules', 'weights', 'opinions', 'factsByTier', 'causalAttribution', 'eventObservation'],
  audit:    ['provenance', 'diff', 'audit', 'health', 'eval'],
  ontology: ['ontology'],
};

/** Infer which section a tab belongs to. */
function sectionOf(tab: GraphsTab): GraphsSection {
  for (const [section, tabs] of Object.entries(SECTION_TABS) as [GraphsSection, GraphsTab[]][]) {
    if ((tabs as string[]).includes(tab)) return section;
  }
  return 'explore';
}

/**
 * Unified "Graphs" workspace — a first-class home for knowledge graphs as assets.
 * Two-level navigation: 5 outer sections → leaf tabs inside each section.
 * Supports ?section= and ?tab= query params for deep-linking.
 */
@Component({
  selector: 'app-graphs-hub',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatSnackBarModule,
    MatTabsModule,
    MatButtonModule,
    MatTooltipModule,
    GraphOverviewComponent,
    GraphMaintenanceHubComponent,
    GraphHealthPanelComponent,
    GraphRulesPanelComponent,
    GraphIoPanelComponent,
    GraphPipelinesPanelComponent,
    GraphProvenancePanelComponent,
    GraphDiffPanelComponent,
    GraphEvalDebuggerComponent,
    GraphDataPatchComponent,
    MultiAgentGraphExtractionComponent,
    GraphVisualizerComponent,
    KnowledgeGraphBuilderComponent,
    GraphHierarchyComponent,
    EventObservationDashboardComponent,
    CausalAttributionPanelComponent,
    GroundingConsolePanelComponent,
    AuditTimelineComponent,
    GroundingMonitorComponent,
    GraphSimulatorComponent,
    FactsByTierPanelComponent,
    KbWeightsPanelComponent,
    OpinionBrowserComponent,
    CommunityPanelComponent,
    FolRulesBrowserComponent,
    GraphOntologyPanelComponent,
    ProcessOntologyComponent,
    TaxonomyBrowserComponent,
    CategoryManagerComponent
  ],
  templateUrl: './graphs-hub.component.html',
  styleUrls: ['./graphs-hub.component.css']
})
export class GraphsHubComponent implements OnInit, OnDestroy, OnChanges {
  /** When set (e.g. from the Index Browser "View in Graph" action) jump to the visualizer. */
  @Input() focusNodeId: string | null = null;

  activeSection: GraphsSection = 'explore';
  activeTab: GraphsTab = 'visualizer';
  activeFactSheet: FactSheet | null = null;
  activeFactSheetId: number | null = null;

  /** Node id handed to the Causal Attribution panel when "Use in Causal Attribution" is clicked. */
  attributionSeedNodeId: string | null = null;

  /**
   * D1 cold-start banner: true when a fact sheet is active but has no facts (nothing crawled yet).
   * Dismissed locally; reappears if the user switches to another empty sheet.
   */
  showColdStartBanner = false;
  coldStartBannerDismissed = false;

  readonly sections: GraphsSection[] = ['explore', 'build', 'reason', 'audit', 'ontology'];
  readonly sectionTabs = SECTION_TABS;

  private destroy$ = new Subject<void>();

  constructor(
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar,
    private router: Router,
    private route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheet => {
        this.activeFactSheet = sheet;
        this.activeFactSheetId = sheet ? sheet.id : null;
        this.coldStartBannerDismissed = false;
        this.showColdStartBanner = sheet != null && (sheet.factCount ?? 0) === 0;
      });

    // Read query params for deep-linking (?section=build&tab=extract&focusNode=<id>)
    this.route.queryParams
      .pipe(takeUntil(this.destroy$))
      .subscribe(params => {
        const sectionParam = params['section'] as GraphsSection | undefined;
        const tabParam = params['tab'] as GraphsTab | undefined;
        const focusNodeParam = params['focusNode'] as string | undefined;
        if (sectionParam && this.sections.includes(sectionParam)) {
          this.activeSection = sectionParam;
          // Default to first tab in section if none specified
          this.activeTab = (SECTION_TABS[sectionParam][0]) as GraphsTab;
        }
        if (tabParam && this.isValidTab(tabParam)) {
          this.activeSection = sectionOf(tabParam);
          this.activeTab = tabParam;
        }
        // ?focusNode= deep-link: jump to Explore → Visualizer and expose the node
        if (focusNodeParam) {
          this.focusNodeId = focusNodeParam;
          this.activeSection = 'explore';
          this.activeTab = 'visualizer';
        }
      });
  }

  private isValidTab(tab: string): tab is GraphsTab {
    return Object.values(SECTION_TABS).flat().includes(tab as GraphsTab);
  }

  dismissColdStartBanner(): void {
    this.showColdStartBanner = false;
    this.coldStartBannerDismissed = true;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['focusNodeId'] && this.focusNodeId) {
      this.activeSection = 'explore';
      this.activeTab = 'visualizer';
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectSection(section: GraphsSection): void {
    this.activeSection = section;
    this.activeTab = SECTION_TABS[section][0];
  }

  selectTab(tab: GraphsTab): void {
    this.activeSection = sectionOf(tab);
    this.activeTab = tab;
  }

  /** Legacy compat: called from other components that used selectSubTab. */
  selectSubTab(tab: GraphsTab): void {
    this.selectTab(tab);
  }

  tabsForSection(section: GraphsSection): GraphsTab[] {
    return SECTION_TABS[section];
  }

  sectionLabel(section: GraphsSection): string {
    return { explore: 'Explore', build: 'Build', reason: 'Reason', audit: 'Audit', ontology: 'Ontology' }[section];
  }

  sectionIcon(section: GraphsSection): string {
    return {
      explore:  'travel_explore',
      build:    'construction',
      reason:   'psychology',
      audit:    'history',
      ontology: 'schema'
    }[section];
  }

  tabLabel(tab: GraphsTab): string {
    const labels: Record<GraphsTab, string> = {
      visualizer: 'Visualizer', hierarchy: 'Hierarchy', overview: 'Overview', communities: 'Communities',
      builder: 'Builder', extract: 'Extraction', patch: 'Data Patch', io: 'Import / Export',
      pipelines: 'Channel Pipelines', maintenance: 'Maintenance',
      grounding: 'Grounding', liveReasoning: 'Live Reasoning', simulator: 'Simulator',
      rules: 'Rules', folRules: 'FOL Rules', weights: 'Weights', opinions: 'Opinions',
      factsByTier: 'Facts by Tier', causalAttribution: 'Causal Attribution', eventObservation: 'Event Observation',
      provenance: 'Provenance', diff: 'Diff', audit: 'Audit Timeline', health: 'Health', eval: 'Eval Debugger',
      ontology: 'Ontology'
    };
    return labels[tab] ?? tab;
  }

  tabIcon(tab: GraphsTab): string {
    const icons: Record<GraphsTab, string> = {
      visualizer: 'scatter_plot', hierarchy: 'lan', overview: 'account_tree', communities: 'bubble_chart',
      builder: 'construction', extract: 'auto_awesome', patch: 'edit_note', io: 'import_export',
      pipelines: 'cable', maintenance: 'build',
      grounding: 'insights', liveReasoning: 'monitor', simulator: 'science',
      rules: 'rule', folRules: 'rule', weights: 'scale', opinions: 'psychology_alt',
      factsByTier: 'grade', causalAttribution: 'psychology', eventObservation: 'query_stats',
      provenance: 'history_edu', diff: 'difference', audit: 'history', health: 'monitor_heart', eval: 'bug_report',
      ontology: 'schema'
    };
    return icons[tab] ?? 'circle';
  }

  onEntitySelected(entity: GraphNode): void {
    console.log('Entity selected:', entity);
  }

  onNavigateToGraph(entity: GraphNode): void {
    this.activeSection = 'explore';
    this.activeTab = 'visualizer';
    this.snackBar.open(`Showing "${entity.title || entity.nodeId}" in graph`, 'Dismiss', { duration: 2000 });
  }

  /** Event Observation → "Use in Causal Attribution": seed the node and switch to that panel. */
  onAttributeNode(nodeId: string): void {
    if (!nodeId) return;
    this.attributionSeedNodeId = nodeId;
    this.activeSection = 'reason';
    this.activeTab = 'causalAttribution';
  }

  /**
   * Simulator "Open in Graphs Hub": the sandbox sheet is already activated by the simulator,
   * so showing it means jumping to the visualizer sub-tab.
   */
  onSimulatorNavigate(): void {
    this.activeSection = 'explore';
    this.activeTab = 'visualizer';
  }

  /** Navigate to the chat route to ask about this graph. */
  openChat(): void {
    this.router.navigate(['/chat']);
  }
}
