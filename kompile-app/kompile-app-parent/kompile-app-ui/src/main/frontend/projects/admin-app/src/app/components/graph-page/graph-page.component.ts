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
import { GraphsHubComponent } from '../graphs-hub/graphs-hub.component';

/**
 * Top-level route wrapper for GraphsHubComponent.
 * focusNodeId is optional and defaults to null inside GraphsHubComponent.
 * Standalone so it can import the standalone GraphsHubComponent directly.
 */
@Component({
  standalone: true,
  selector: 'app-graph-page',
  imports: [CommonModule, GraphsHubComponent],
  template: `<app-graphs-hub></app-graphs-hub>`
})
export class GraphPageComponent {}
