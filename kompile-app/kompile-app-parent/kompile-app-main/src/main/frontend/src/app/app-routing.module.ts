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

import { UnifiedChatComponent } from './components/unified-chat/unified-chat.component';
import { ToolsHubComponent } from './components/tools-hub/tools-hub.component';
import { DeveloperHubComponent } from './components/developer-hub/developer-hub.component';
import { KClawHubComponent } from './components/kclaw-hub/kclaw-hub.component';
import { SettingsComponent } from './components/settings/settings.component';
import { GroundingConsolePanelComponent } from './components/grounding-console-panel/grounding-console-panel.component';
import { GraphSimulatorComponent } from './components/graph-simulator/graph-simulator.component';
import { KnowledgeGraphPageComponent } from './components/knowledge-graph-page/knowledge-graph-page.component';
import { EnforcerHubComponent } from './components/enforcer-hub/enforcer-hub.component';

// Thin route wrappers created for this shell refactor
import { ProjectPageComponent } from './components/project-page/project-page.component';
import { FactSheetPageComponent } from './components/fact-sheet-page/fact-sheet-page.component';
import { GraphPageComponent } from './components/graph-page/graph-page.component';

const routes: Routes = [
  // ── Default ─────────────────────────────────────────────────────────────
  { path: '', redirectTo: 'chat', pathMatch: 'full' },

  // ── Primary nav tabs ────────────────────────────────────────────────────
  { path: 'chat',       component: UnifiedChatComponent,    title: 'Kompile — Chat' },
  { path: 'project',    component: ProjectPageComponent,    title: 'Kompile — Project' },
  { path: 'fact-sheets', component: FactSheetPageComponent, title: 'Kompile — Fact Sheets' },
  { path: 'data',       component: ToolsHubComponent,       title: 'Kompile — Data' },
  { path: 'graph',      component: GraphPageComponent,      title: 'Kompile — Graph' },
  { path: 'developer',  component: DeveloperHubComponent,   title: 'Kompile — Developer' },
  { path: 'agents',     component: KClawHubComponent,       title: 'Kompile — Agents' },
  { path: 'enforcer',   component: EnforcerHubComponent,    title: 'Kompile — Enforcer' },

  // ── Secondary / utility routes ──────────────────────────────────────────
  { path: 'settings',        component: SettingsComponent,              title: 'Kompile — Settings' },
  { path: 'knowledge-graph', component: KnowledgeGraphPageComponent,    title: 'Kompile — Knowledge Graph' },
  { path: 'grounding',       component: GroundingConsolePanelComponent, title: 'Kompile — Grounding' },
  { path: 'graph-simulator', component: GraphSimulatorComponent,        title: 'Kompile — Graph Simulator' },

  // ── Legacy path redirects ────────────────────────────────────────────────
  { path: 'knowledge',     redirectTo: 'fact-sheets', pathMatch: 'full' },
  { path: 'tools',         redirectTo: 'data',        pathMatch: 'full' },
  { path: 'kclaw',         redirectTo: 'agents',      pathMatch: 'full' },
  { path: 'code-projects', redirectTo: 'data',        pathMatch: 'full' },

  // ── Wildcard ─────────────────────────────────────────────────────────────
  { path: '**', redirectTo: 'chat' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
