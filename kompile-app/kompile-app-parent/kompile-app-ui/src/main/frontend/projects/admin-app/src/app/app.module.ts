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
import { ServiceEndpointRoutingModule } from '@shared/services/service-endpoint-routing';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';

// Module-declared components owned by this app (38).
import { AutoConfigureComponent } from './components/developer-hub/auto-configure/auto-configure.component';
import { ContextualRagDebugComponent } from './components/contextual-rag-debug/contextual-rag-debug.component';
import { CrossIndexStatusComponent } from './components/cross-index-status/cross-index-status.component';
import { DeveloperHubComponent } from './components/developer-hub/developer-hub.component';
import { DocumentDebuggerComponent } from './components/document-manager/document-debugger/document-debugger.component';
import { ExperimentsComponent } from './components/developer-hub/experiments/experiments.component';
import { GpuManagementComponent } from './components/developer-hub/gpu-management/gpu-management.component';
import { IndexSystemStatusComponent } from './components/index-system-status/index-system-status.component';
import { IngestEventViewerComponent } from './components/ingest-event-viewer/ingest-event-viewer.component';
import { IngestHistoryComponent } from './components/developer-hub/ingest-history/ingest-history.component';
import { JobHistoryComponent } from './components/job-history/job-history.component';
import { JobResumeComponent } from './components/job-resume/job-resume.component';
import { KClawAgentManagerComponent } from './components/kclaw-agent-manager/kclaw-agent-manager.component';
import { KClawChannelManagerComponent } from './components/kclaw-channel-manager/kclaw-channel-manager.component';
import { KClawChatComponent } from './components/kclaw-chat/kclaw-chat.component';
import { KClawHeartbeatManagerComponent } from './components/kclaw-heartbeat-manager/kclaw-heartbeat-manager.component';
import { KClawHubComponent } from './components/kclaw-hub/kclaw-hub.component';
import { KClawPermissionManagerComponent } from './components/kclaw-permission-manager/kclaw-permission-manager.component';
import { KClawSessionViewerComponent } from './components/kclaw-session-viewer/kclaw-session-viewer.component';
import { KGEmbeddingsComponent } from './components/kg-embeddings/kg-embeddings.component';
import { LifecycleTrackingComponent } from './components/developer-hub/lifecycle-tracking/lifecycle-tracking.component';
import { MemoryWatchdogComponent } from './components/developer-hub/memory-watchdog/memory-watchdog.component';
import { ModelAdmissionComponent } from './components/developer-hub/model-admission/model-admission.component';
import { ModelDebugComponent } from './components/model-debug/model-debug.component';
import { ModelSchedulerPanelComponent } from './components/developer-hub/model-scheduler-panel/model-scheduler-panel.component';
import { ModelStagingComponent } from './components/developer-hub/model-staging/model-staging.component';
import { ModelWarmupCacheComponent } from './components/developer-hub/model-warmup-cache/model-warmup-cache.component';
import { OcrDebugComponent } from './components/ocr-debug/ocr-debug.component';
import { PipelineSchedulesComponent } from './components/developer-hub/pipeline-schedules/pipeline-schedules.component';
import { ProcessingSettingsComponent } from './components/processing-settings/processing-settings.component';
import { RagTesterComponent } from './components/rag-tester/rag-tester.component';
import { SchedulerDashboardComponent } from './components/developer-hub/scheduler-dashboard/scheduler-dashboard.component';
import { SettingsComponent } from './components/settings/settings.component';
import { SkillsManagerComponent } from './components/skills-manager/skills-manager.component';
import { TrainingHistoryComponent } from './components/developer-hub/training-history/training-history.component';
import { VlmManagementComponent } from './components/developer-hub/vlm-management/vlm-management.component';
import { VlmModelsComponent } from './components/vlm-models/vlm-models.component';

// Standalone components referenced from the templates above. Standalone components
// reached only from other standalone components carry their own imports and are not
// listed here.
import { AgentModelConfigComponent } from './components/agent-model-config/agent-model-config.component';
import { AgentBundlesComponent } from './components/agent-bundles/agent-bundles.component';
import { AgentTasksComponent } from './components/agent-tasks/agent-tasks.component';
import { AppShellComponent } from '@shared/components/app-shell/app-shell.component';
import { ArchiveAssemblyComponent } from './components/archive-assembly/archive-assembly.component';
import { BatchSizeConfigComponent } from './components/batch-size-config/batch-size-config.component';
import { BenchmarkRunnerComponent } from './components/benchmark-runner/benchmark-runner.component';
import { ChunkingLoaderTestComponent } from './components/chunking-loader-test/chunking-loader-test.component';
import { CrawlStepMonitorComponent } from '@shared/components/crawl-step-monitor/crawl-step-monitor.component';
import { DeviceRoutingComponent } from './components/device-routing/device-routing.component';
import { EmbeddingRestartComponent } from './components/embedding-restart/embedding-restart.component';
import { EvalDebuggerComponent } from './components/eval-debugger/eval-debugger.component';
import { EvaluationSettingsComponent } from './components/settings/evaluation-settings/evaluation-settings.component';
import { FilterChainSettingsComponent } from './components/settings/filter-chain-settings/filter-chain-settings.component';
import { GpuLifecycleComponent } from './components/gpu-lifecycle/gpu-lifecycle.component';
import { GuardrailsSettingsComponent } from './components/settings/guardrails-settings/guardrails-settings.component';
import { JobLogViewerComponent } from '@shared/components/job-history/job-log-viewer/job-log-viewer.component';
import { KVCacheDashboardComponent } from './components/kvcache/kvcache-dashboard.component';
import { KbConfidenceSettingsComponent } from './components/settings/kb-confidence-settings/kb-confidence-settings.component';
import { LogSettingsComponent } from './components/settings/log-settings/log-settings.component';
import { ManagedEvalComponent } from './components/managed-eval/managed-eval.component';
import { McpOptimizationSettingsComponent } from './components/settings/mcp-optimization-settings/mcp-optimization-settings.component';
import { MonitorsManagerComponent } from './components/monitors-manager/monitors-manager.component';
import { Nd4jEnvironmentComponent } from './components/nd4j-environment/nd4j-environment.component';
import { Nd4jFrameworkComponent } from './components/nd4j-framework/nd4j-framework.component';
import { OpTimingComponent } from './components/op-timing/op-timing.component';
import { PassthroughChatComponent } from './components/passthrough-chat/passthrough-chat.component';
import { ProcessMiningSettingsComponent } from './components/settings/process-mining-settings/process-mining-settings.component';
import { QueryTransformerSettingsComponent } from './components/settings/query-transformer-settings/query-transformer-settings.component';
import { SameDiffGraphComponent } from './components/samediff-graph/samediff-graph.component';
import { SameDiffLLMModelsComponent } from './components/samediff-llm-models/samediff-llm-models.component';
import { SdkHubComponent } from './components/sdk-hub/sdk-hub.component';
import { SourceCitationComponent } from '@shared/components/source-citation/source-citation.component';
import { StagingConfigComponent } from './components/staging-config/staging-config.component';
import { SubprocessConfigComponent } from './components/subprocess-config/subprocess-config.component';
import { SubprocessLogsComponent } from '@shared/components/subprocess-logs/subprocess-logs.component';
import { SystemDiagnosticsComponent } from './components/system-diagnostics/system-diagnostics.component';
import { SystemInfoComponent } from './components/system-info/system-info.component';
import { TableRendererComponent } from '@shared/components/table-renderer/table-renderer.component';
import { ToolPermissionsComponent } from './components/tool-permissions/tool-permissions.component';
import { TrainingDashboardComponent } from './components/developer-hub/training-dashboard/training-dashboard.component';
import { TrainingLaunchComponent } from './components/developer-hub/training-launch/training-launch.component';
import { TritonCacheComponent } from './components/triton-cache/triton-cache.component';
import { VlmOrchestrationComponent } from './components/vlm-orchestration/vlm-orchestration.component';

/**
 * Root module of the Kompile admin console app.
 *
 * Declarations are exactly this app's own module-declared components — see
 * docs/architecture/ui-persona-boundary.md for how each component was assigned. A
 * component belonging to another persona cannot be added here without also adding a
 * cross-project import, which tools/check-project-boundaries.mjs rejects.
 */
@NgModule({
  declarations: [
    AppComponent,
    AgentBundlesComponent,
    AutoConfigureComponent,
    ContextualRagDebugComponent,
    CrossIndexStatusComponent,
    DeveloperHubComponent,
    DocumentDebuggerComponent,
    ExperimentsComponent,
    GpuManagementComponent,
    IndexSystemStatusComponent,
    IngestEventViewerComponent,
    IngestHistoryComponent,
    JobHistoryComponent,
    JobResumeComponent,
    KClawAgentManagerComponent,
    KClawChannelManagerComponent,
    KClawChatComponent,
    KClawHeartbeatManagerComponent,
    KClawHubComponent,
    KClawPermissionManagerComponent,
    KClawSessionViewerComponent,
    KGEmbeddingsComponent,
    LifecycleTrackingComponent,
    MemoryWatchdogComponent,
    ModelAdmissionComponent,
    ModelDebugComponent,
    ModelSchedulerPanelComponent,
    ModelStagingComponent,
    ModelWarmupCacheComponent,
    OcrDebugComponent,
    PipelineSchedulesComponent,
    ProcessingSettingsComponent,
    RagTesterComponent,
    SchedulerDashboardComponent,
    SettingsComponent,
    SkillsManagerComponent,
    TrainingHistoryComponent,
    VlmManagementComponent,
    VlmModelsComponent
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
    AgentModelConfigComponent,
    AgentTasksComponent,
    AppShellComponent,
    ArchiveAssemblyComponent,
    BatchSizeConfigComponent,
    BenchmarkRunnerComponent,
    ChunkingLoaderTestComponent,
    CrawlStepMonitorComponent,
    DeviceRoutingComponent,
    EmbeddingRestartComponent,
    EvalDebuggerComponent,
    EvaluationSettingsComponent,
    FilterChainSettingsComponent,
    GpuLifecycleComponent,
    GuardrailsSettingsComponent,
    JobLogViewerComponent,
    KVCacheDashboardComponent,
    KbConfidenceSettingsComponent,
    LogSettingsComponent,
    ManagedEvalComponent,
    McpOptimizationSettingsComponent,
    MonitorsManagerComponent,
    Nd4jEnvironmentComponent,
    Nd4jFrameworkComponent,
    OpTimingComponent,
    PassthroughChatComponent,
    ProcessMiningSettingsComponent,
    QueryTransformerSettingsComponent,
    SameDiffGraphComponent,
    SameDiffLLMModelsComponent,
    SdkHubComponent,
    SourceCitationComponent,
    StagingConfigComponent,
    SubprocessConfigComponent,
    SubprocessLogsComponent,
    SystemDiagnosticsComponent,
    SystemInfoComponent,
    TableRendererComponent,
    ToolPermissionsComponent,
    TrainingDashboardComponent,
    TrainingLaunchComponent,
    TritonCacheComponent,
    VlmOrchestrationComponent
  ],
  providers: [
    { provide: HTTP_INTERCEPTORS, useClass: HttpErrorInterceptor, multi: true }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
