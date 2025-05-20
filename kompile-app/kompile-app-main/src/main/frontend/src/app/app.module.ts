/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms'; // For ngModel used in components
import { CommonModule } from '@angular/common'; // For *ngIf, *ngFor, etc.
import { BrowserAnimationsModule } from '@angular/platform-browser/animations'; // Required for Angular Material

import { AppComponent } from './app.component';
import { ChatInterfaceComponent } from './components/chat-interface/chat-interface.component';
import { DocumentManagerComponent } from './components/document-manager/document-manager.component';
import { McpToolsViewerComponent } from './components/mcp-tools-viewer/mcp-tools-viewer.component';

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
import { CdkTextareaAutosize } from '@angular/cdk/text-field';

// AddSourceDialogComponent is standalone and imports its own modules.
// import { AddSourceDialogComponent } from './components/document-manager/add-source-dialog/add-source-dialog.component';


@NgModule({
  declarations: [
    AppComponent,
    ChatInterfaceComponent,
    DocumentManagerComponent,
    McpToolsViewerComponent
    // AddSourceDialogComponent is standalone, so it's not declared here.
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    FormsModule,      // Needed for [(ngModel)]
    ReactiveFormsModule, // Often used with Material forms
    CommonModule,      // Needed for *ngIf, *ngFor, etc.
    BrowserAnimationsModule, // Required for Angular Material animations

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
    CdkTextareaAutosize // Import if cdkTextareaAutosize is used directly in DocumentManagerComponent templates
  ],
  providers: [
    // Services are typically provided in root if using providedIn: 'root'
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
