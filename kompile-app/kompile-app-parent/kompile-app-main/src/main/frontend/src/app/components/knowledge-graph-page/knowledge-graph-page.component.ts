/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { GraphsHubComponent } from '../graphs-hub/graphs-hub.component';

/**
 * Routed home for the Graphs hub (#/knowledge-graph). Deep-link target for
 * "view in graph" actions across the app (chat source citations, process
 * diagrams): pass ?nodeId=<id> to jump straight to that node in the visualizer.
 * The active fact sheet comes from the global FactSheetService, same as when
 * the hub is embedded in the Index Browser.
 */
@Component({
  selector: 'app-knowledge-graph-page',
  standalone: true,
  imports: [CommonModule, GraphsHubComponent],
  template: `<app-graphs-hub [focusNodeId]="focusNodeId"></app-graphs-hub>`
})
export class KnowledgeGraphPageComponent implements OnInit, OnDestroy {
  focusNodeId: string | null = null;

  private destroy$ = new Subject<void>();

  constructor(private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(takeUntil(this.destroy$))
      .subscribe(params => {
        this.focusNodeId = params.get('nodeId');
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }
}
