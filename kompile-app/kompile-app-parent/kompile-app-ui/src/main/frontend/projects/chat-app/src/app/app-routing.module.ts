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
import { ProjectPageComponent } from './components/project-page/project-page.component';
import { GraphPageComponent } from './components/graph-page/graph-page.component';
import { FactSheetPageComponent } from '@shared/components/fact-sheet-page/fact-sheet-page.component';

/**
 * Chat app routes — chat, project browsing, fact sheets and read-only graph exploration.
 *
 * Deliberately absent: /data, /developer, /agents, /enforcer, /settings and the graph
 * build / reason / audit / ontology surfaces. Those are admin routes and their components are
 * not on this project's classpath, so they cannot be added here by accident.
 */
// Exported so app-routing.module.spec.ts asserts the real table rather than a copy of it.
export const routes: Routes = [
  // ── Default ─────────────────────────────────────────────────────────────
  { path: '', redirectTo: 'chat', pathMatch: 'full' },

  // ── Primary nav tabs ────────────────────────────────────────────────────
  { path: 'chat',        component: UnifiedChatComponent,  title: 'Kompile Chat' },
  { path: 'project',     component: ProjectPageComponent,  title: 'Kompile Chat — Project' },
  { path: 'fact-sheets', component: FactSheetPageComponent, title: 'Kompile Chat — Fact Sheets' },
  { path: 'graph',       component: GraphPageComponent,    title: 'Kompile Chat — Graph' },

  // ── Legacy path redirects ────────────────────────────────────────────────
  { path: 'knowledge', redirectTo: 'fact-sheets', pathMatch: 'full' },

  // ── Wildcard ─────────────────────────────────────────────────────────────
  { path: '**', redirectTo: 'chat' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
