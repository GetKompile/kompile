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

import { Component, OnInit, OnDestroy, OnChanges, SimpleChanges, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, ActivatedRoute } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';

import { FactSheetService } from '../../services/fact-sheet.service';
import { FactSheet } from '../../models/api-models';
import { GraphNode } from '../../models/graph-models';
import { GraphVisualizerComponent } from '../graph-visualizer/graph-visualizer.component';
import { GraphHierarchyComponent } from '../graph-hierarchy/graph-hierarchy.component';
import { GraphOverviewComponent } from '../graph-overview/graph-overview.component';
import { CommunityPanelComponent } from '../community-panel/community-panel.component';
import { PartitionCoveragePanelComponent } from '../partition-coverage-panel/partition-coverage-panel.component';

/**
 * Read-only exploration tabs. This is `SECTION_TABS.explore` from admin's GraphsHubComponent plus
 * Coverage; the build / reason / audit / ontology sections stay admin-only.
 *
 * Coverage belongs here rather than in admin because it answers an end-user question — how much of
 * the available evidence is this graph's answer actually based on — and someone reading the graph
 * without it cannot tell a well-grounded answer from a confident guess.
 */
export type ExploreTab = 'visualizer' | 'hierarchy' | 'overview' | 'communities' | 'coverage';

const EXPLORE_TABS: ExploreTab[] = ['visualizer', 'hierarchy', 'overview', 'communities', 'coverage'];

/**
 * End-user graph explorer: the Explore half of admin's GraphsHubComponent, and nothing else.
 *
 * This exists as a separate component rather than a section-gated reuse of GraphsHubComponent
 * because that component is standalone and eagerly `imports:` all 29 graph panels. Angular
 * resolves an `imports:` array at build time, so routing an end-user app at it would pull the
 * whole maintenance / rules / health / simulator / OWL suite into the chat and crawl bundles no
 * matter which section the runtime happens to show. Gating cannot undo a static import.
 *
 * The four panels it does import all live in this library and are read-only against the graph
 * API, so this component adds no admin surface to an end-user bundle.
 *
 * Deep links are compatible with admin's hub: `?tab=hierarchy` and `?focusNode=<id>` behave
 * identically, and a `?section=explore` from an admin link is honoured. Links naming an
 * admin-only section or tab are ignored rather than erroring — the explorer just stays put.
 */
@Component({
  selector: 'app-graph-explore-hub',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    MatSnackBarModule,
    MatButtonModule,
    MatTooltipModule,
    GraphVisualizerComponent,
    GraphHierarchyComponent,
    GraphOverviewComponent,
    CommunityPanelComponent,
    PartitionCoveragePanelComponent
  ],
  templateUrl: './graph-explore-hub.component.html',
  styleUrls: ['./graph-explore-hub.component.css']
})
export class GraphExploreHubComponent implements OnInit, OnDestroy, OnChanges {
  /** When set (e.g. from the Index Browser "View in Graph" action) jump to the visualizer. */
  @Input() focusNodeId: string | null = null;

  /**
   * Router path of this app's chat surface, or null when it has none. The "Ask about this graph"
   * button renders only when a path is supplied — chat-app passes '/chat', crawl-app leaves it
   * unset rather than offering a button that would dead-end on a route it does not declare.
   */
  @Input() chatRoute: string | null = null;

  activeTab: ExploreTab = 'visualizer';
  activeFactSheet: FactSheet | null = null;
  activeFactSheetId: number | null = null;

  /**
   * Cold-start banner: true when a fact sheet is active but has no facts (nothing crawled yet).
   * Dismissed locally; reappears if the user switches to another empty sheet.
   */
  showColdStartBanner = false;
  coldStartBannerDismissed = false;

  readonly tabs = EXPLORE_TABS;

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

    // Deep-linking (?tab=hierarchy&focusNode=<id>). Tabs outside Explore belong to the admin
    // console and are silently ignored here.
    this.route.queryParams
      .pipe(takeUntil(this.destroy$))
      .subscribe(params => {
        const tabParam = params['tab'] as string | undefined;
        const focusNodeParam = params['focusNode'] as string | undefined;
        if (tabParam && this.isExploreTab(tabParam)) {
          this.activeTab = tabParam;
        }
        if (focusNodeParam) {
          this.focusNodeId = focusNodeParam;
          this.activeTab = 'visualizer';
        }
      });
  }

  private isExploreTab(tab: string): tab is ExploreTab {
    return EXPLORE_TABS.includes(tab as ExploreTab);
  }

  dismissColdStartBanner(): void {
    this.showColdStartBanner = false;
    this.coldStartBannerDismissed = true;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['focusNodeId'] && this.focusNodeId) {
      this.activeTab = 'visualizer';
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectTab(tab: ExploreTab): void {
    this.activeTab = tab;
  }

  tabLabel(tab: ExploreTab): string {
    const labels: Record<ExploreTab, string> = {
      visualizer: 'Visualizer', hierarchy: 'Hierarchy', overview: 'Overview', communities: 'Communities',
      coverage: 'Coverage'
    };
    return labels[tab] ?? tab;
  }

  tabIcon(tab: ExploreTab): string {
    const icons: Record<ExploreTab, string> = {
      visualizer: 'scatter_plot', hierarchy: 'lan', overview: 'account_tree', communities: 'bubble_chart',
      coverage: 'fact_check'
    };
    return icons[tab] ?? 'circle';
  }

  onNavigateToGraph(entity: GraphNode): void {
    this.activeTab = 'visualizer';
    this.snackBar.open(`Showing "${entity.title || entity.nodeId}" in graph`, 'Dismiss', { duration: 2000 });
  }

  /** Navigate to this app's chat route to ask about the graph. */
  openChat(): void {
    if (this.chatRoute) {
      this.router.navigate([this.chatRoute]);
    }
  }

  /** Send an unconfigured user to the fact-sheet workspace. */
  openFactSheetCreation(): void {
    this.router.navigate(['/fact-sheets']);
  }
}
