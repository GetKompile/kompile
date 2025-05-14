/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms'; // For ngModel used in components
import { CommonModule } from '@angular/common'; // For *ngIf, *ngFor, etc.

import { AppComponent } from './app.component';
import { ChatInterfaceComponent } from './components/chat-interface/chat-interface.component';
import { DocumentManagerComponent } from './components/document-manager/document-manager.component';
import { McpToolsViewerComponent } from './components/mcp-tools-viewer/mcp-tools-viewer.component';

// Services are usually auto-provided with providedIn: 'root'
// No need to list them in providers array here.

@NgModule({
  declarations: [
    AppComponent,
    ChatInterfaceComponent,
    DocumentManagerComponent,
    McpToolsViewerComponent
    // Make sure all components you create and use are declared here
  ],
  imports: [
    BrowserModule,
    HttpClientModule,
    FormsModule,      // Needed for [(ngModel)]
    CommonModule      // Needed for *ngIf, *ngFor, etc. in AppComponent and potentially others
  ],
  providers: [
    // Services are typically provided in root if using providedIn: 'root'
  ],
  bootstrap: [AppComponent]
})
export class AppModule { }
