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

import { AppComponent } from './app.component';
import { DocumentManagerComponent } from './components/document-manager/document-manager.component';
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
import { OrchestratorHubComponent } from './components/orchestrator-hub/orchestrator-hub.component';
import { FolderSidebarComponent } from './components/folder-sidebar/folder-sidebar.component';
import { FolderFilesDialogComponent } from './components/folder-files-dialog/folder-files-dialog.component';
import { SubprocessLogsComponent } from './components/subprocess-logs/subprocess-logs.component';

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
import { MatDividerModule } from '@angular/material/divider';
import { TextFieldModule } from '@angular/cdk/text-field';
import { MatMenuModule } from '@angular/material/menu';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import {DocumentDebuggerComponent} from './components/document-manager/document-debugger/document-debugger.component';
import {MatSlideToggle} from '@angular/material/slide-toggle';
import { MatSliderModule } from '@angular/material/slider';
import { MatToolbarModule } from '@angular/material/toolbar';

@NgModule({
  declarations: [
    AppComponent,
    DocumentManagerComponent,
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
    FolderFilesDialogComponent
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    CommonModule,
    BrowserAnimationsModule,

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
    MatDividerModule,
    TextFieldModule,
    MatSlideToggle,
    MatMenuModule,
    MatCheckboxModule,
    MatButtonToggleModule,
    MatSliderModule,
    MatToolbarModule,
    // Standalone components
    BatchSizeConfigComponent,
    SubprocessConfigComponent,
    SubprocessLogsComponent,
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
