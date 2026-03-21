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
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule, Routes } from '@angular/router';

// Angular Material imports
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatBadgeModule } from '@angular/material/badge';
import { MatMenuModule } from '@angular/material/menu';
import { MatDividerModule } from '@angular/material/divider';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatRadioModule } from '@angular/material/radio';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatStepperModule } from '@angular/material/stepper';

// Components
import { AppComponent } from './app.component';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { ModelCatalogComponent } from './components/model-catalog/model-catalog.component';
import { StagingProgressComponent } from './components/staging-progress/staging-progress.component';
import { RegistryBrowserComponent } from './components/registry-browser/registry-browser.component';
import { ExportImportComponent } from './components/export-import/export-import.component';
import { ArchiveManagerComponent } from './components/archive-manager/archive-manager.component';
import { DownloadModelComponent } from './components/download-model/download-model.component';
import { ModelDetailsDialogComponent } from './components/model-details-dialog/model-details-dialog.component';
import { ModelConverterComponent } from './components/model-converter/model-converter.component';
import { ArchiveConfigComponent } from './components/archive-config/archive-config.component';
import { AlignmentConfigComponent } from './components/alignment-config/alignment-config.component';
import { DatasetManagerComponent } from './components/dataset-manager/dataset-manager.component';
import { DistillationConfigComponent } from './components/distillation-config/distillation-config.component';
import { DspCompilerComponent } from './components/dsp-compiler/dsp-compiler.component';
import { EnvironmentDashboardComponent } from './components/environment-dashboard/environment-dashboard.component';
import { EvaluationViewerComponent } from './components/evaluation-viewer/evaluation-viewer.component';
import { LlmExecutionComponent } from './components/llm-execution/llm-execution.component';
import { OptimizationWizardComponent } from './components/optimization-wizard/optimization-wizard.component';
import { PeftConfigComponent } from './components/peft-config/peft-config.component';
import { TrainingConfigComponent } from './components/training-config/training-config.component';
import { TrainingDashboardComponent } from './components/training-dashboard/training-dashboard.component';
import { TrainingMetricsComponent } from './components/training-metrics/training-metrics.component';
import { VlmExecutionComponent } from './components/vlm-execution/vlm-execution.component';

// Routes
const routes: Routes = [
  { path: '', redirectTo: '/dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'catalog', component: ModelCatalogComponent },
  { path: 'download', component: DownloadModelComponent },
  { path: 'convert', component: ModelConverterComponent },
  { path: 'staging', component: StagingProgressComponent },
  { path: 'registry', component: RegistryBrowserComponent },
  { path: 'export-import', component: ExportImportComponent },
  { path: 'archives', component: ArchiveManagerComponent },
  { path: 'config', component: ArchiveConfigComponent },
  { path: 'alignment', component: AlignmentConfigComponent },
  { path: 'datasets', component: DatasetManagerComponent },
  { path: 'distillation', component: DistillationConfigComponent },
  { path: 'dsp-compiler', component: DspCompilerComponent },
  { path: 'environment', component: EnvironmentDashboardComponent },
  { path: 'evaluation', component: EvaluationViewerComponent },
  { path: 'llm', component: LlmExecutionComponent },
  { path: 'optimization', component: OptimizationWizardComponent },
  { path: 'peft', component: PeftConfigComponent },
  { path: 'training', component: TrainingDashboardComponent },
  { path: 'training/new', component: TrainingConfigComponent },
  { path: 'training/:jobId/metrics', component: TrainingMetricsComponent },
  { path: 'vlm', component: VlmExecutionComponent }
];

@NgModule({
  declarations: [
    AppComponent,
    DashboardComponent,
    ModelCatalogComponent,
    StagingProgressComponent,
    RegistryBrowserComponent,
    ExportImportComponent,
    ArchiveManagerComponent,
    DownloadModelComponent,
    ModelDetailsDialogComponent,
    ModelConverterComponent,
    ArchiveConfigComponent,
    AlignmentConfigComponent,
    DatasetManagerComponent,
    DistillationConfigComponent,
    DspCompilerComponent,
    EnvironmentDashboardComponent,
    EvaluationViewerComponent,
    LlmExecutionComponent,
    OptimizationWizardComponent,
    PeftConfigComponent,
    TrainingConfigComponent,
    TrainingDashboardComponent,
    TrainingMetricsComponent,
    VlmExecutionComponent
  ],
  imports: [
    BrowserModule,
    BrowserAnimationsModule,
    HttpClientModule,
    FormsModule,
    ReactiveFormsModule,
    RouterModule.forRoot(routes),
    // Angular Material
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatTabsModule,
    MatTableModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatTooltipModule,
    MatExpansionModule,
    MatListModule,
    MatSidenavModule,
    MatCheckboxModule,
    MatBadgeModule,
    MatMenuModule,
    MatDividerModule,
    MatButtonToggleModule,
    MatRadioModule,
    MatSlideToggleModule,
    MatStepperModule
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
