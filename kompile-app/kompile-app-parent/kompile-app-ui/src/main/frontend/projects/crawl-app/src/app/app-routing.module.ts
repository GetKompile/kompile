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

import { UnifiedCrawlComponent } from './components/unified-crawl/unified-crawl.component';
import { ToolsHubComponent } from './components/tools-hub/tools-hub.component';
import { GraphPageComponent } from './components/graph-page/graph-page.component';
import { FactSheetPageComponent } from '@shared/components/fact-sheet-page/fact-sheet-page.component';

/**
 * Crawl manager routes — running crawls, fact sheets, the documents / index browser, note sync
 * and connections (both reached through the Fact Sheets tab), and read-only graph exploration.
 *
 * `/crawl` is the first route UnifiedCrawlComponent has ever had. It was written as a complete
 * standalone crawl UI but was never referenced by a route or module in the monolith, so it
 * shipped in the bundle and was unreachable. It is this app's landing page.
 *
 * Deliberately absent: /chat, /developer, /agents, /enforcer, /settings and the graph
 * build / reason / audit / ontology surfaces.
 */
// Exported so app-routing.module.spec.ts asserts the real table rather than a copy of it.
export const routes: Routes = [
  // ── Default ─────────────────────────────────────────────────────────────
  { path: '', redirectTo: 'crawl', pathMatch: 'full' },

  // ── Primary nav tabs ────────────────────────────────────────────────────
  { path: 'crawl',       component: UnifiedCrawlComponent, title: 'Kompile Crawl Manager' },
  { path: 'fact-sheets', component: FactSheetPageComponent, title: 'Kompile Crawl — Fact Sheets' },
  { path: 'data',        component: ToolsHubComponent,     title: 'Kompile Crawl — Data' },
  { path: 'graph',       component: GraphPageComponent,    title: 'Kompile Crawl — Graph' },

  // ── Legacy path redirects ────────────────────────────────────────────────
  { path: 'knowledge',     redirectTo: 'fact-sheets', pathMatch: 'full' },
  { path: 'tools',         redirectTo: 'data',        pathMatch: 'full' },
  { path: 'code-projects', redirectTo: 'data',        pathMatch: 'full' },

  // ── Wildcard ─────────────────────────────────────────────────────────────
  { path: '**', redirectTo: 'crawl' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
