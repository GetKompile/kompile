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

// Module-declared components owned by this app (5).
import { FolderFilesDialogComponent } from './components/folder-files-dialog/folder-files-dialog.component';
import { FolderSidebarComponent } from './components/folder-sidebar/folder-sidebar.component';
import { ProjectManagerComponent } from './components/project-manager/project-manager.component';
import { ProjectPageComponent } from './components/project-page/project-page.component';
import { UnifiedChatComponent } from './components/unified-chat/unified-chat.component';

// Standalone components referenced from the templates above. Standalone components
// reached only from other standalone components carry their own imports and are not
// listed here.
import { AppShellComponent } from '@shared/components/app-shell/app-shell.component';
import { MarkdownRendererComponent } from '@shared/components/markdown-renderer/markdown-renderer.component';
import { ProjectStorePanelComponent } from './components/project-store-panel/project-store-panel.component';
import { ReasoningTrailComponent } from '@shared/components/reasoning-trail/reasoning-trail.component';
import { SourceCitationComponent } from '@shared/components/source-citation/source-citation.component';

/**
 * Root module of the Kompile chat app.
 *
 * Declarations are exactly this app's own module-declared components — see
 * docs/architecture/ui-persona-boundary.md for how each component was assigned. A
 * component belonging to another persona cannot be added here without also adding a
 * cross-project import, which tools/check-project-boundaries.mjs rejects.
 */
@NgModule({
  declarations: [
    AppComponent,
    FolderFilesDialogComponent,
    FolderSidebarComponent,
    ProjectManagerComponent,
    ProjectPageComponent,
    UnifiedChatComponent
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
    MarkdownRendererComponent,
    ProjectStorePanelComponent,
    ReasoningTrailComponent,
    SourceCitationComponent
  ],
  providers: [
    { provide: CURRENT_SERVICE_PERSONA, useValue: 'chat' },
    { provide: HTTP_INTERCEPTORS, useClass: HttpErrorInterceptor, multi: true }
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
