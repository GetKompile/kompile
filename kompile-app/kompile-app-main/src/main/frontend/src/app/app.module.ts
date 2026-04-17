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
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { RouterModule } from '@angular/router';

import { AppComponent } from './app.component';
import { McpToolsViewerComponent } from './components/mcp-tools-viewer/mcp-tools-viewer.component';
import { McpServerBuilderComponent } from './components/mcp-server-builder/mcp-server-builder.component';
import { RestMcpBridgeComponent } from './components/rest-mcp-bridge/rest-mcp-bridge.component';
import { IndexBrowserComponent } from './components/index-browser/index-browser.component';
import { ModelDebugComponent } from './components/model-debug/model-debug.component';
import { UnifiedChatComponent } from './components/unified-chat/unified-chat.component';
import { RagTesterComponent } from './components/rag-tester/rag-tester.component';
import { McpDebuggerComponent } from './components/mcp-debugger/mcp-debugger.component';
import { ToolManagerComponent } from './components/tool-manager/tool-manager.component';
import { PromptTemplateManagerComponent } from './components/prompt-template-manager/prompt-template-manager.component';
import { McpHubComponent } from './components/mcp-hub/mcp-hub.component';
import { McpConfigManagerComponent } from './components/mcp-config-manager/mcp-config-manager.component';
import { IngestEventViewerComponent } from './components/ingest-event-viewer/ingest-event-viewer.component';
import { UnifiedDataManagementComponent } from './components/unified-data-management/unified-data-management.component';
import { JobHistoryComponent } from './components/job-history/job-history.component';
import { BatchSizeConfigComponent } from './components/batch-size-config/batch-size-config.component';
import { SubprocessConfigComponent } from './components/subprocess-config/subprocess-config.component';
import { DeviceRoutingComponent } from './components/device-routing/device-routing.component';
import { OrchestratorHubComponent } from './components/orchestrator-hub/orchestrator-hub.component';
import { FolderSidebarComponent } from './components/folder-sidebar/folder-sidebar.component';
import { FolderFilesDialogComponent } from './components/folder-files-dialog/folder-files-dialog.component';
import { SubprocessLogsComponent } from './components/subprocess-logs/subprocess-logs.component';
import { SettingsComponent } from './components/settings/settings.component';
import { DeveloperHubComponent } from './components/developer-hub/developer-hub.component';
import { ToolsHubComponent } from './components/tools-hub/tools-hub.component';
import { PipelineSettingsPanelComponent } from './components/document-manager/pipeline-settings-panel/pipeline-settings-panel.component';
import { ProcessingSettingsComponent } from './components/processing-settings/processing-settings.component';
import { GraphVisualizerComponent } from './components/graph-visualizer/graph-visualizer.component';
import { EntityBrowserComponent } from './components/entity-browser/entity-browser.component';
import { KnowledgeGraphHubComponent } from './components/knowledge-graph-hub/knowledge-graph-hub.component';
import { ConfirmDialogComponent } from './components/confirm-dialog/confirm-dialog.component';
import { ConnectionsManagerComponent } from './components/connections-manager/connections-manager.component';
import { FactSheetManagerComponent } from './components/fact-sheet-manager/fact-sheet-manager.component';
import { CrossIndexStatusComponent } from './components/cross-index-status/cross-index-status.component';
import { StagingConfigComponent } from './components/staging-config/staging-config.component';
import { ArchiveManagerComponent } from './components/archive-manager/archive-manager.component';
import { ArchiveAssemblyComponent } from './components/archive-assembly/archive-assembly.component';
import { TableRendererComponent } from './components/table-renderer/table-renderer.component';
import { IndexStatusBannerComponent } from './components/index-status-banner/index-status-banner.component';
import { BackupManagerComponent } from './components/backup-manager/backup-manager.component';
import { ModelStatusIndicatorComponent } from './components/model-status-indicator/model-status-indicator.component';
import { SystemDiagnosticsComponent } from './components/system-diagnostics/system-diagnostics.component';
import { Nd4jEnvironmentComponent } from './components/nd4j-environment/nd4j-environment.component';
import { OpTimingComponent } from './components/op-timing/op-timing.component';
import { JobLogViewerComponent } from './components/job-history/job-log-viewer/job-log-viewer.component';
import { LogSettingsComponent } from './components/settings/log-settings/log-settings.component';
import { ChunkManagerComponent } from './components/chunk-manager/chunk-manager.component';
import { KGEmbeddingsComponent } from './components/kg-embeddings/kg-embeddings.component';
import { SameDiffGraphComponent } from './components/samediff-graph/samediff-graph.component';
import { GuardrailsSettingsComponent } from './components/settings/guardrails-settings/guardrails-settings.component';
import { McpOptimizationSettingsComponent } from './components/settings/mcp-optimization-settings/mcp-optimization-settings.component';
import { EvaluationSettingsComponent } from './components/settings/evaluation-settings/evaluation-settings.component';
import { QueryTransformerSettingsComponent } from './components/settings/query-transformer-settings/query-transformer-settings.component';
import { EvalDebuggerComponent } from './components/eval-debugger/eval-debugger.component';
import { FilterChainSettingsComponent } from './components/settings/filter-chain-settings/filter-chain-settings.component';
import { ReactAgentConfigComponent } from './components/react-agent-config/react-agent-config.component';
import { TaskDefinitionEditorComponent } from './components/orchestrator-hub/components/task-definition-editor/task-definition-editor.component';
import { AuditLogViewerComponent } from './components/orchestrator-hub/components/audit-log-viewer/audit-log-viewer.component';
import { OutputClassifierConfigComponent } from './components/orchestrator-hub/components/output-classifier-config/output-classifier-config.component';
import { StateMachineEditorComponent } from './components/orchestrator-hub/components/state-machine-editor/state-machine-editor.component';
import { OcrDebugComponent } from './components/ocr-debug/ocr-debug.component';
import { ContextualRagDebugComponent } from './components/contextual-rag-debug/contextual-rag-debug.component';
import { ManagedEvalComponent } from './components/managed-eval/managed-eval.component';
import { PromptManagerComponent } from './components/prompt-manager/prompt-manager.component';
import { ChunkingLoaderTestComponent } from './components/chunking-loader-test/chunking-loader-test.component';
import { VlmModelsComponent } from './components/vlm-models/vlm-models.component';
import { SdkHubComponent } from './components/sdk-hub/sdk-hub.component';
import { OpenClawHubComponent } from './components/openclaw-hub/openclaw-hub.component';
import { OpenClawAgentManagerComponent } from './components/openclaw-agent-manager/openclaw-agent-manager.component';
import { OpenClawChannelManagerComponent } from './components/openclaw-channel-manager/openclaw-channel-manager.component';
import { OpenClawChatComponent } from './components/openclaw-chat/openclaw-chat.component';
import { OpenClawSessionViewerComponent } from './components/openclaw-session-viewer/openclaw-session-viewer.component';
import { OpenClawHeartbeatManagerComponent } from './components/openclaw-heartbeat-manager/openclaw-heartbeat-manager.component';
import { OpenClawPermissionManagerComponent } from './components/openclaw-permission-manager/openclaw-permission-manager.component';
import { PipelineHubComponent } from './components/pipeline-hub/pipeline-hub.component';
import { BenchmarkRunnerComponent } from './components/benchmark-runner/benchmark-runner.component';
import { KVCacheDashboardComponent } from './components/kvcache/kvcache-dashboard.component';
import { VlmTestWorkflowComponent } from './components/vlm-test-workflow/vlm-test-workflow.component';
import { ToolPermissionsComponent } from './components/tool-permissions/tool-permissions.component';
import { GpuLifecycleComponent } from './components/gpu-lifecycle/gpu-lifecycle.component';
import { VlmOrchestrationComponent } from './components/vlm-orchestration/vlm-orchestration.component';
import { TritonCacheComponent } from './components/triton-cache/triton-cache.component';
import { MonitorsManagerComponent } from './components/monitors-manager/monitors-manager.component';
import { PassthroughChatComponent } from './components/passthrough-chat/passthrough-chat.component';
import { Nd4jFrameworkComponent } from './components/nd4j-framework/nd4j-framework.component';
import { SameDiffLLMModelsComponent } from './components/samediff-llm-models/samediff-llm-models.component';
import { VlmManagementComponent } from './components/developer-hub/vlm-management/vlm-management.component';
import { GpuManagementComponent } from './components/developer-hub/gpu-management/gpu-management.component';
import { ModelStagingComponent } from './components/developer-hub/model-staging/model-staging.component';
import { IngestHistoryComponent } from './components/developer-hub/ingest-history/ingest-history.component';

// Angular Material Modules
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
import { DocumentDebuggerComponent } from './components/document-manager/document-debugger/document-debugger.component';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatBadgeModule } from '@angular/material/badge';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';

@NgModule({
  declarations: [
    AppComponent,
    DocumentDebuggerComponent,
    McpToolsViewerComponent,
    McpServerBuilderComponent,
    RestMcpBridgeComponent,
    IndexBrowserComponent,
    ModelDebugComponent,
    UnifiedChatComponent,
    RagTesterComponent,
    McpDebuggerComponent,
    ToolManagerComponent,
    PromptTemplateManagerComponent,
    McpHubComponent,
    McpConfigManagerComponent,
    IngestEventViewerComponent,
    UnifiedDataManagementComponent,
    JobHistoryComponent,
    OrchestratorHubComponent,
    FolderSidebarComponent,
    FolderFilesDialogComponent,
    SettingsComponent,
    DeveloperHubComponent,
    ToolsHubComponent,
    ContextualRagDebugComponent,
    ProcessingSettingsComponent,
    CrossIndexStatusComponent,
    TableRendererComponent,
    ChunkManagerComponent,
    KGEmbeddingsComponent,
    TaskDefinitionEditorComponent,
    AuditLogViewerComponent,
    OutputClassifierConfigComponent,
    StateMachineEditorComponent,
    OcrDebugComponent,
    PromptManagerComponent,
    VlmModelsComponent,
    OpenClawHubComponent,
    OpenClawAgentManagerComponent,
    OpenClawChannelManagerComponent,
    OpenClawChatComponent,
    OpenClawSessionViewerComponent,
    OpenClawHeartbeatManagerComponent,
    OpenClawPermissionManagerComponent,
    PipelineHubComponent,
    VlmTestWorkflowComponent,
    VlmManagementComponent,
    GpuManagementComponent,
    ModelStagingComponent,
    IngestHistoryComponent
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    CommonModule,
    BrowserAnimationsModule,
    RouterModule.forRoot([]),  // Required for ActivatedRoute

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
    MatSlideToggleModule,
    MatMenuModule,
    MatCheckboxModule,
    MatButtonToggleModule,
    MatSliderModule,
    MatToolbarModule,
    MatBadgeModule,
    DragDropModule,
    MatDatepickerModule,
    MatNativeDateModule,
    // Standalone components
    BatchSizeConfigComponent,
    SubprocessConfigComponent,
    DeviceRoutingComponent,
    SubprocessLogsComponent,
    PipelineSettingsPanelComponent,
    GraphVisualizerComponent,
    EntityBrowserComponent,
    KnowledgeGraphHubComponent,
    ConfirmDialogComponent,
    ConnectionsManagerComponent,
    FactSheetManagerComponent,
    StagingConfigComponent,
    ArchiveManagerComponent,
    ArchiveAssemblyComponent,
    IndexStatusBannerComponent,
    BackupManagerComponent,
    ModelStatusIndicatorComponent,
    SystemDiagnosticsComponent,
    Nd4jEnvironmentComponent,
    OpTimingComponent,
    JobLogViewerComponent,
    LogSettingsComponent,
    SameDiffGraphComponent,
    GuardrailsSettingsComponent,
    McpOptimizationSettingsComponent,
    EvaluationSettingsComponent,
    QueryTransformerSettingsComponent,
    EvalDebuggerComponent,
    FilterChainSettingsComponent,
    ReactAgentConfigComponent,
    ManagedEvalComponent,
    ChunkingLoaderTestComponent,
    SdkHubComponent,
    BenchmarkRunnerComponent,
    KVCacheDashboardComponent,
    ToolPermissionsComponent,
    GpuLifecycleComponent,
    VlmOrchestrationComponent,
    TritonCacheComponent,
    MonitorsManagerComponent,
    PassthroughChatComponent,
    Nd4jFrameworkComponent,
    SameDiffLLMModelsComponent
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
