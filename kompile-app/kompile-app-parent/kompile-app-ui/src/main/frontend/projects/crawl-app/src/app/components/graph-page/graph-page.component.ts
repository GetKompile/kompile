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

import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GraphExploreHubComponent } from '@shared/components/graph-explore-hub/graph-explore-hub.component';

/**
 * Top-level route wrapper for the crawl manager's /graph route.
 *
 * Wraps the read-only GraphExploreHubComponent, not admin's GraphsHubComponent: the build /
 * reason / audit / ontology sections are admin surfaces and their panels must not enter this
 * bundle. chatRoute is left unset — this app declares no /chat route, so the hub hides the
 * "Ask about this graph" button rather than offering a dead link.
 */
@Component({
  standalone: true,
  selector: 'app-graph-page',
  imports: [CommonModule, GraphExploreHubComponent],
  template: `<app-graph-explore-hub></app-graph-explore-hub>`
})
export class GraphPageComponent {}
