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

import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';

import { DeveloperHubComponent } from './components/developer-hub/developer-hub.component';
import { KClawHubComponent } from './components/kclaw-hub/kclaw-hub.component';
import { EnforcerHubComponent } from './components/enforcer-hub/enforcer-hub.component';
import { SettingsComponent } from './components/settings/settings.component';
import { KnowledgeGraphPageComponent } from './components/knowledge-graph-page/knowledge-graph-page.component';
import { GraphSimulatorComponent } from './components/graph-simulator/graph-simulator.component';
import { GraphPageComponent } from './components/graph-page/graph-page.component';

import { FactSheetPageComponent } from '@shared/components/fact-sheet-page/fact-sheet-page.component';
import { GroundingConsolePanelComponent } from '@shared/components/grounding-console-panel/grounding-console-panel.component';

/**
 * Admin console routes. `/graph` mounts the full GraphsHubComponent — build, reason, audit,
 * ontology, maintenance, rules, health and simulator — as opposed to the explore-only hub the
 * end-user apps route.
 *
 * Fact Sheets stays here because admin still creates and repairs them; the same shared page
 * component serves all three apps.
 *
 * Deliberately absent: /chat, /project and /crawl. Those personas are served by
 * kompile-app-chat (:8081) and kompile-app-crawl-manager (:8082), and after Phase 4 this app's
 * backend no longer mounts their APIs.
 */
// Exported so app-routing.module.spec.ts asserts the real table rather than a copy of it.
export const routes: Routes = [
  // ── Default ─────────────────────────────────────────────────────────────
  { path: '', redirectTo: 'developer', pathMatch: 'full' },

  // ── Primary nav tabs ────────────────────────────────────────────────────
  { path: 'fact-sheets', component: FactSheetPageComponent, title: 'Kompile Admin — Fact Sheets' },
  { path: 'graph',       component: GraphPageComponent,     title: 'Kompile Admin — Graph' },
  { path: 'developer',   component: DeveloperHubComponent,  title: 'Kompile Admin — Developer' },
  { path: 'agents',      component: KClawHubComponent,      title: 'Kompile Admin — Agents' },
  { path: 'enforcer',    component: EnforcerHubComponent,   title: 'Kompile Admin — Enforcer' },

  // ── Secondary / utility routes ──────────────────────────────────────────
  { path: 'settings',        component: SettingsComponent,              title: 'Kompile Admin — Settings' },
  { path: 'knowledge-graph', component: KnowledgeGraphPageComponent,    title: 'Kompile Admin — Knowledge Graph' },
  { path: 'grounding',       component: GroundingConsolePanelComponent, title: 'Kompile Admin — Grounding' },
  { path: 'graph-simulator', component: GraphSimulatorComponent,        title: 'Kompile Admin — Graph Simulator' },

  // ── Legacy path redirects ────────────────────────────────────────────────
  { path: 'knowledge', redirectTo: 'fact-sheets', pathMatch: 'full' },
  { path: 'kclaw',     redirectTo: 'agents',      pathMatch: 'full' },

  // ── Wildcard ─────────────────────────────────────────────────────────────
  { path: '**', redirectTo: 'developer' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
