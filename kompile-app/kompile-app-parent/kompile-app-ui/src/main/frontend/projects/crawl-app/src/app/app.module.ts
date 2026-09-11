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
import { CommonModule } from '@angular/common';
import { BrowserModule } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientModule, HTTP_INTERCEPTORS } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';

// Angular Material / CDK — the same set the monolith imported, so every
// module-declared component keeps the directives its template already uses.
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatCardModule } from '@angular/material/card';
import { MatListModule } from '@angular/material/list';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatSortModule } from '@angular/material/sort';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialogModule } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTabsModule } from '@angular/material/tabs';
import { MatChipsModule } from '@angular/material/chips';
import { MatRadioModule } from '@angular/material/radio';
import { MatDividerModule } from '@angular/material/divider';
import { TextFieldModule } from '@angular/cdk/text-field';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { MatMenuModule } from '@angular/material/menu';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTreeModule } from '@angular/material/tree';
import { MatBadgeModule } from '@angular/material/badge';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';

import { HttpErrorInterceptor } from '@shared/services/http-error.interceptor';
import { CURRENT_SERVICE_PERSONA, ServiceEndpointRoutingModule } from '@shared/services/service-endpoint-routing';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';

// Module-declared components owned by this app (21).
import { AuditLogViewerComponent } from './components/orchestrator-hub/components/audit-log-viewer/audit-log-viewer.component';
import { ChunkManagerComponent } from './components/chunk-manager/chunk-manager.component';
import { ClusterWorkersComponent } from './components/developer-hub/cluster-workers/cluster-workers.component';
import { IndexBrowserComponent } from './components/index-browser/index-browser.component';
import { McpCliInjectionComponent } from './components/mcp-cli-injection/mcp-cli-injection.component';
import { McpConfigManagerComponent } from './components/mcp-config-manager/mcp-config-manager.component';
import { McpDebuggerComponent } from './components/mcp-debugger/mcp-debugger.component';
import { McpHubComponent } from './components/mcp-hub/mcp-hub.component';
import { McpServerBuilderComponent } from './components/mcp-server-builder/mcp-server-builder.component';
import { McpToolUseLogComponent } from './components/mcp-tool-use-log/mcp-tool-use-log.component';
import { McpToolsViewerComponent } from './components/mcp-tools-viewer/mcp-tools-viewer.component';
import { OrchestratorHubComponent } from './components/orchestrator-hub/orchestrator-hub.component';
import { OutputClassifierConfigComponent } from './components/orchestrator-hub/components/output-classifier-config/output-classifier-config.component';
import { PromptManagerComponent } from './components/prompt-manager/prompt-manager.component';
import { PromptTemplateManagerComponent } from './components/prompt-template-manager/prompt-template-manager.component';
import { RestMcpBridgeComponent } from './components/rest-mcp-bridge/rest-mcp-bridge.component';
import { StateMachineEditorComponent } from './components/orchestrator-hub/components/state-machine-editor/state-machine-editor.component';
import { TaskDefinitionEditorComponent } from './components/orchestrator-hub/components/task-definition-editor/task-definition-editor.component';
import { ToolManagerComponent } from './components/tool-manager/tool-manager.component';
import { ToolsHubComponent } from './components/tools-hub/tools-hub.component';

// Standalone components referenced from the templates above. Standalone components
// reached only from other standalone components carry their own imports and are not
// listed here.
import { AppShellComponent } from '@shared/components/app-shell/app-shell.component';
import { BackupManagerComponent } from './components/backup-manager/backup-manager.component';
import { CodeDiffBrowserComponent } from './components/code-diff-browser/code-diff-browser.component';
import { CodeProjectsHubComponent } from './components/code-projects-hub/code-projects-hub.component';
import { ComputeGraphDashboardComponent } from './components/compute-graph/compute-graph-dashboard.component';
import { CrawlerManagerComponent } from './components/crawler-manager/crawler-manager.component';
import { EntityBrowserComponent } from './components/entity-browser/entity-browser.component';
import { PipelineSettingsPanelComponent } from '@shared/components/document-manager/pipeline-settings-panel/pipeline-settings-panel.component';
import { ProcessEngineDashboardComponent } from './components/process-engine/process-engine-dashboard.component';
import { ReactAgentConfigComponent } from './components/react-agent-config/react-agent-config.component';
import { SourceCitationComponent } from '@shared/components/source-citation/source-citation.component';
import { TableRendererComponent } from '@shared/components/table-renderer/table-renderer.component';
import { ToolCallCatalogComponent } from './components/tool-call-catalog/tool-call-catalog.component';
import { WorkflowsHubComponent } from './components/workflows-hub/workflows-hub.component';

/**
 * Root module of the Kompile crawl manager app.
 *
 * Declarations are exactly this app's own module-declared components — see
 * docs/architecture/ui-persona-boundary.md for how each component was assigned. A
 * component belonging to another persona cannot be added here without also adding a
 * cross-project import, which tools/check-project-boundaries.mjs rejects.
 */
@NgModule({
  declarations: [
    AppComponent,
    AuditLogViewerComponent,
    ChunkManagerComponent,
    ClusterWorkersComponent,
    IndexBrowserComponent,
    McpCliInjectionComponent,
    McpConfigManagerComponent,
    McpDebuggerComponent,
    McpHubComponent,
    McpServerBuilderComponent,
    McpToolUseLogComponent,
    McpToolsViewerComponent,
    OrchestratorHubComponent,
    OutputClassifierConfigComponent,
    PromptManagerComponent,
    PromptTemplateManagerComponent,
    RestMcpBridgeComponent,
    StateMachineEditorComponent,
    TaskDefinitionEditorComponent,
    ToolManagerComponent,
    ToolsHubComponent
  ],
  imports: [
    BrowserModule,
    BrowserAnimationsModule,
    CommonModule,
    HttpClientModule,
    ServiceEndpointRoutingModule,
    FormsModule,
    ReactiveFormsModule,
    AppRoutingModule,

    // Material Modules
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatCardModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatPaginatorModule,
    MatSortModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatDialogModule,
    MatExpansionModule,
    MatProgressSpinnerModule,
    MatTabsModule,
    MatChipsModule,
    MatRadioModule,
    MatDividerModule,
    TextFieldModule,
    DragDropModule,
    MatMenuModule,
    MatCheckboxModule,
    MatButtonToggleModule,
    MatSlideToggleModule,
    MatSliderModule,
    MatToolbarModule,
    MatTreeModule,
    MatBadgeModule,
    MatDatepickerModule,
    MatNativeDateModule,

    // Standalone components
    AppShellComponent,
    BackupManagerComponent,
    CodeDiffBrowserComponent,
    CodeProjectsHubComponent,
    ComputeGraphDashboardComponent,
    CrawlerManagerComponent,
    EntityBrowserComponent,
    PipelineSettingsPanelComponent,
    ProcessEngineDashboardComponent,
    ReactAgentConfigComponent,
    SourceCitationComponent,
    TableRendererComponent,
    ToolCallCatalogComponent,
    WorkflowsHubComponent
  ],
  providers: [
    { provide: CURRENT_SERVICE_PERSONA, useValue: 'crawl' },
    { provide: HTTP_INTERCEPTORS, useClass: HttpErrorInterceptor, multi: true }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
