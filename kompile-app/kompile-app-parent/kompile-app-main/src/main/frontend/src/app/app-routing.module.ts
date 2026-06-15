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
import { FactSheetManagerComponent } from './components/fact-sheet-manager/fact-sheet-manager.component';
import { CodeProjectsHubComponent } from './components/code-projects-hub/code-projects-hub.component';
import { ToolsHubComponent } from './components/tools-hub/tools-hub.component';
import { DeveloperHubComponent } from './components/developer-hub/developer-hub.component';
import { KClawHubComponent } from './components/kclaw-hub/kclaw-hub.component';
import { SettingsComponent } from './components/settings/settings.component';

const routes: Routes = [
  { path: '', redirectTo: 'chat', pathMatch: 'full' },
  { path: 'chat', component: UnifiedChatComponent, data: { title: 'Chat' } },
  { path: 'knowledge', component: FactSheetManagerComponent, data: { title: 'Knowledge' } },
  { path: 'code-projects', component: CodeProjectsHubComponent, data: { title: 'Code Projects' } },
  { path: 'tools', component: ToolsHubComponent, data: { title: 'Tools' } },
  { path: 'settings', component: SettingsComponent, data: { title: 'Settings' } },
  { path: 'developer', component: DeveloperHubComponent, data: { title: 'Developer' } },
  { path: 'kclaw', component: KClawHubComponent, data: { title: 'KClaw' } },
  { path: '**', redirectTo: 'chat' }
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { useHash: true })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
