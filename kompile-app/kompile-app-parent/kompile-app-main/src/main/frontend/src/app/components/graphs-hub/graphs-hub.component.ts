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

import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { Subject, takeUntil } from 'rxjs';

import { FactSheetService } from '../../services/fact-sheet.service';
import { GraphOverviewComponent } from '../graph-overview/graph-overview.component';
import { GraphMaintenanceHubComponent } from '../graph-maintenance-hub/graph-maintenance-hub.component';
import { GraphHealthPanelComponent } from '../graph-health-panel/graph-health-panel.component';
import { GraphRulesPanelComponent } from '../graph-rules-panel/graph-rules-panel.component';
import { GraphOntologyPanelComponent } from '../graph-ontology-panel/graph-ontology-panel.component';
import { GraphIoPanelComponent } from '../graph-io-panel/graph-io-panel.component';
import { GraphPipelinesPanelComponent } from '../graph-pipelines-panel/graph-pipelines-panel.component';
import { GraphProvenancePanelComponent } from '../graph-provenance-panel/graph-provenance-panel.component';

type GraphsTab = 'overview' | 'health' | 'ontology' | 'pipelines' | 'provenance' | 'maintenance' | 'rules' | 'io';

/**
 * Top-level "Graphs" workspace — a first-class home for managing knowledge graphs as assets. Wires
 * together the previously-orphaned graph registry (overview) + maintenance hub and the Phase-7 health
 * panel, scoped to the active fact sheet. (graph-as-asset Phase 8.)
 */
@Component({
  selector: 'app-graphs-hub',
  standalone: true,
  imports: [
    CommonModule,
    MatIconModule,
    GraphOverviewComponent,
    GraphMaintenanceHubComponent,
    GraphHealthPanelComponent,
    GraphRulesPanelComponent,
    GraphOntologyPanelComponent,
    GraphIoPanelComponent,
    GraphPipelinesPanelComponent,
    GraphProvenancePanelComponent
  ],
  templateUrl: './graphs-hub.component.html',
  styleUrls: ['./graphs-hub.component.css']
})
export class GraphsHubComponent implements OnInit, OnDestroy {
  activeSubTab: GraphsTab = 'overview';
  activeFactSheetId: number | null = null;

  private destroy$ = new Subject<void>();

  constructor(private factSheetService: FactSheetService) {}

  ngOnInit(): void {
    this.factSheetService.activeSheet$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheet => (this.activeFactSheetId = sheet ? sheet.id : null));
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  selectSubTab(tab: GraphsTab): void {
    this.activeSubTab = tab;
  }
}
