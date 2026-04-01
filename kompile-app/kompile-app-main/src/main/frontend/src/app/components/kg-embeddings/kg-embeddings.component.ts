/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Component, OnInit, OnDestroy, Input, ChangeDetectorRef } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialog } from '@angular/material/dialog';
import { Subject } from 'rxjs';
import { takeUntil, filter } from 'rxjs/operators';
import { ConfirmDialogComponent, ConfirmDialogData } from '../confirm-dialog/confirm-dialog.component';
import { KGEmbeddingsService } from '../../services/kg-embeddings.service';
import {
  KGEmbeddingJob,
  TrainRequest,
  EntityEmbedding,
  EmbeddingStats,
  AlgorithmInfo,
  EmbeddingScore,
  JOB_STATUS_COLORS,
  DEFAULT_TRAIN_CONFIG,
  KGEmbeddingAlgorithm,
  KGEmbeddingSettings,
  TrainConfig,
  GraphRAGConfig,
  Neo4jConfig,
  GraphBuildingConfig,
  GraphBuilderType,
  GraphStorageType,
  DEFAULT_GRAPH_BUILDING_CONFIG,
  AVAILABLE_ENTITY_TYPES
} from '../../models/kg-embedding-models';

@Component({
  selector: 'app-kg-embeddings',
  standalone: false,
  templateUrl: './kg-embeddings.component.html',
  styleUrls: ['./kg-embeddings.component.css']
})
export class KGEmbeddingsComponent implements OnInit, OnDestroy {

  @Input() factSheetId: number = 1;

  private destroy$ = new Subject<void>();

  // State
  isLoading = false;
  activeTab = 0;

  // Algorithms
  algorithms: AlgorithmInfo[] = [];
  selectedAlgorithm: KGEmbeddingAlgorithm = 'TRANSE';

  // Training config
  trainConfig = {
    embeddingDim: 100,
    epochs: 100,
    learningRate: 0.01,
    batchSize: 1024,
    margin: 1.0,
    negativeSamples: 10
  };

  // Jobs
  jobs: KGEmbeddingJob[] = [];
  currentJob: KGEmbeddingJob | null = null;
  isTraining = false;

  // Embeddings
  embeddings: EntityEmbedding[] = [];
  embeddingsSearch = '';
  embeddingsPage = 0;
  embeddingsPageSize = 20;
  stats: EmbeddingStats | null = null;

  // Link Prediction
  predictionHead = '';
  predictionRelation = '';
  predictionTail = '';
  predictionTopK = 10;
  predictionResults: EmbeddingScore[] = [];
  predictionMode: 'tails' | 'heads' | 'relations' = 'tails';
  isPredicting = false;

  // Similar entities
  similarEntityName = '';
  similarResults: EmbeddingScore[] = [];
  isFindingSimilar = false;

  // Settings
  settings: KGEmbeddingSettings | null = null;
  isSavingSettings = false;
  settingsTabIndex = 0;

  // Editable copies of settings
  transeConfig: TrainConfig = {
    embeddingDim: 100,
    epochs: 100,
    learningRate: 0.01,
    batchSize: 1024,
    margin: 1.0,
    negativeSamples: 10
  };

  rotateConfig: TrainConfig = {
    embeddingDim: 100,
    epochs: 100,
    learningRate: 0.001,
    batchSize: 512,
    margin: 6.0,
    negativeSamples: 256
  };

  graphragConfig: GraphRAGConfig = {
    enabled: false,
    kgWeight: 0.3,
    textWeight: 0.7,
    expansionHops: 1,
    topKEntities: 5,
    defaultFactSheetId: 1
  };

  neo4jConfig: Neo4jConfig = {
    enabled: false,
    uri: 'bolt://localhost:7687',
    username: 'neo4j',
    password: ''
  };

  isTestingConnection = false;
  connectionTestMessage = '';
  connectionTestSuccess: boolean | null = null;

  // Graph Building Configuration
  graphBuildingConfig: GraphBuildingConfig = { ...DEFAULT_GRAPH_BUILDING_CONFIG };
  availableEntityTypes = AVAILABLE_ENTITY_TYPES;
  availableLlmProviders: string[] = ['openai', 'anthropic', 'google'];
  availableLlmModels: string[] = ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo'];
  isSavingGraphBuilding = false;
  isLoadingGraphBuilding = false;

  constructor(
    private kgService: KGEmbeddingsService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog
  ) {}

  ngOnInit(): void {
    this.loadAlgorithms();
    this.loadJobs();
    this.loadStats();
    this.loadEmbeddings();
    this.loadSettings();
    this.loadGraphBuildingConfig();
    this.loadLlmProviders();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ALGORITHMS
  // ═══════════════════════════════════════════════════════════════════════════

  loadAlgorithms(): void {
    this.kgService.getAlgorithms()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (algorithms) => {
          this.algorithms = algorithms;
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.error('Failed to load algorithms:', err);
        }
      });
  }

  onAlgorithmChange(): void {
    const defaults = DEFAULT_TRAIN_CONFIG[this.selectedAlgorithm];
    if (defaults) {
      this.trainConfig = { ...defaults };
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // TRAINING
  // ═══════════════════════════════════════════════════════════════════════════

  startTraining(): void {
    if (this.isTraining) return;

    this.isTraining = true;

    const request: TrainRequest = {
      factSheetId: this.factSheetId,
      algorithm: this.selectedAlgorithm,
      ...this.trainConfig
    };

    this.kgService.startTraining(request)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (job) => {
          this.currentJob = job;
          this.showSnackbar(`Training started: ${job.jobId}`);
          this.pollJobStatus(job.jobId);
        },
        error: (err) => {
          this.isTraining = false;
          this.showSnackbar('Failed to start training: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  pollJobStatus(jobId: string): void {
    this.kgService.pollJobStatus(jobId, 2000)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (job) => {
          this.currentJob = job;
          this.cdr.detectChanges();
        },
        complete: () => {
          this.isTraining = false;
          if (this.currentJob?.status === 'COMPLETED') {
            this.showSnackbar('Training completed successfully!');
            this.loadStats();
            this.loadEmbeddings();
          } else if (this.currentJob?.status === 'FAILED') {
            this.showSnackbar('Training failed: ' + (this.currentJob?.errorMessage || 'Unknown error'), true);
          }
          this.loadJobs();
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isTraining = false;
          this.showSnackbar('Error polling job status: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  cancelTraining(): void {
    if (!this.currentJob) return;

    this.kgService.cancelJob(this.currentJob.jobId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.showSnackbar('Training cancellation requested');
        },
        error: (err) => {
          this.showSnackbar('Failed to cancel: ' + err.message, true);
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // JOBS
  // ═══════════════════════════════════════════════════════════════════════════

  loadJobs(): void {
    this.kgService.getJobs(this.factSheetId, 0, 10)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (page) => {
          this.jobs = page.content;
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.error('Failed to load jobs:', err);
        }
      });
  }

  getJobStatusColor(status: string): string {
    return JOB_STATUS_COLORS[status as keyof typeof JOB_STATUS_COLORS] || '#9E9E9E';
  }

  formatDuration(ms: number | undefined): string {
    if (!ms) return '-';
    const seconds = Math.floor(ms / 1000);
    const minutes = Math.floor(seconds / 60);
    if (minutes > 0) {
      return `${minutes}m ${seconds % 60}s`;
    }
    return `${seconds}s`;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // EMBEDDINGS
  // ═══════════════════════════════════════════════════════════════════════════

  loadStats(): void {
    this.kgService.getStats(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats) => {
          this.stats = stats;
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.error('Failed to load stats:', err);
        }
      });
  }

  loadEmbeddings(): void {
    this.isLoading = true;
    this.kgService.getEntityEmbeddings(
      this.factSheetId,
      this.embeddingsSearch || undefined,
      this.embeddingsPage,
      this.embeddingsPageSize
    )
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (embeddings) => {
          this.embeddings = embeddings;
          this.isLoading = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isLoading = false;
          console.error('Failed to load embeddings:', err);
          this.cdr.detectChanges();
        }
      });
  }

  onEmbeddingsSearchChange(): void {
    this.embeddingsPage = 0;
    this.loadEmbeddings();
  }

  nextEmbeddingsPage(): void {
    this.embeddingsPage++;
    this.loadEmbeddings();
  }

  prevEmbeddingsPage(): void {
    if (this.embeddingsPage > 0) {
      this.embeddingsPage--;
      this.loadEmbeddings();
    }
  }

  clearEmbeddings(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Clear Embeddings',
      message: 'Are you sure you want to clear all embeddings for this fact sheet?',
      confirmText: 'Clear All',
      confirmColor: 'warn',
      icon: 'delete_forever'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.kgService.clearEmbeddings(this.factSheetId)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: () => {
              this.showSnackbar('Embeddings cleared');
              this.loadStats();
              this.loadEmbeddings();
              this.cdr.detectChanges();
            },
            error: (err) => {
              this.showSnackbar('Failed to clear embeddings: ' + err.message, true);
              this.cdr.detectChanges();
            }
          });
      });
  }

  formatEmbeddingPreview(embedding: number[]): string {
    if (!embedding || embedding.length === 0) return '-';
    const preview = embedding.slice(0, 5).map(v => v.toFixed(4)).join(', ');
    return `[${preview}${embedding.length > 5 ? '...' : ''}]`;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LINK PREDICTION
  // ═══════════════════════════════════════════════════════════════════════════

  runPrediction(): void {
    if (this.isPredicting) return;

    this.isPredicting = true;
    this.predictionResults = [];

    const request = this.kgService.createPredictRequest(
      this.factSheetId,
      this.predictionHead || undefined,
      this.predictionRelation || undefined,
      this.predictionTail || undefined,
      this.predictionTopK
    );

    let observable;
    switch (this.predictionMode) {
      case 'tails':
        observable = this.kgService.predictTails(request);
        break;
      case 'heads':
        observable = this.kgService.predictHeads(request);
        break;
      case 'relations':
        observable = this.kgService.predictRelations(request);
        break;
    }

    observable.pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results) => {
          this.predictionResults = results;
          this.isPredicting = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isPredicting = false;
          this.showSnackbar('Prediction failed: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SIMILAR ENTITIES
  // ═══════════════════════════════════════════════════════════════════════════

  findSimilarEntities(): void {
    if (!this.similarEntityName || this.isFindingSimilar) return;

    this.isFindingSimilar = true;
    this.similarResults = [];

    this.kgService.findSimilarEntities(this.factSheetId, this.similarEntityName, 10)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (results) => {
          this.similarResults = results;
          this.isFindingSimilar = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isFindingSimilar = false;
          this.showSnackbar('Failed to find similar entities: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // SETTINGS
  // ═══════════════════════════════════════════════════════════════════════════

  loadSettings(): void {
    this.kgService.getConfig()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (settings) => {
          this.settings = settings;
          this.transeConfig = { ...settings.transe };
          this.rotateConfig = { ...settings.rotate };
          this.graphragConfig = { ...settings.graphrag };
          this.neo4jConfig = { ...settings.neo4j };
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.error('Failed to load settings:', err);
        }
      });
  }

  saveTransEConfig(): void {
    this.isSavingSettings = true;
    this.kgService.updateTransEConfig(this.transeConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (settings) => {
          this.settings = settings;
          this.transeConfig = { ...settings.transe };
          this.isSavingSettings = false;
          this.showSnackbar('TransE settings saved');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isSavingSettings = false;
          this.showSnackbar('Failed to save TransE settings: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  saveRotatEConfig(): void {
    this.isSavingSettings = true;
    this.kgService.updateRotatEConfig(this.rotateConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (settings) => {
          this.settings = settings;
          this.rotateConfig = { ...settings.rotate };
          this.isSavingSettings = false;
          this.showSnackbar('RotatE settings saved');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isSavingSettings = false;
          this.showSnackbar('Failed to save RotatE settings: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  saveGraphRAGConfig(): void {
    this.isSavingSettings = true;
    this.kgService.updateGraphRAGConfig(this.graphragConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (settings) => {
          this.settings = settings;
          this.graphragConfig = { ...settings.graphrag };
          this.isSavingSettings = false;
          this.showSnackbar('GraphRAG settings saved');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isSavingSettings = false;
          this.showSnackbar('Failed to save GraphRAG settings: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  saveNeo4jConfig(): void {
    this.isSavingSettings = true;
    this.kgService.updateNeo4jConfig(this.neo4jConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (settings) => {
          this.settings = settings;
          this.neo4jConfig = { ...settings.neo4j };
          this.isSavingSettings = false;
          this.showSnackbar('Neo4j settings saved');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isSavingSettings = false;
          this.showSnackbar('Failed to save Neo4j settings: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  testNeo4jConnection(): void {
    this.isTestingConnection = true;
    this.connectionTestMessage = '';
    this.connectionTestSuccess = null;

    this.kgService.testNeo4jConnection(this.neo4jConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (result) => {
          this.isTestingConnection = false;
          this.connectionTestSuccess = result.success;
          this.connectionTestMessage = result.message;
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isTestingConnection = false;
          this.connectionTestSuccess = false;
          this.connectionTestMessage = 'Connection test failed: ' + err.message;
          this.cdr.detectChanges();
        }
      });
  }

  resetSettings(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Reset Settings',
      message: 'Reset all KG embedding settings to defaults?',
      confirmText: 'Reset',
      confirmColor: 'warn',
      icon: 'settings_backup_restore'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.isSavingSettings = true;
        this.kgService.resetConfig()
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (settings) => {
              this.settings = settings;
              this.transeConfig = { ...settings.transe };
              this.rotateConfig = { ...settings.rotate };
              this.graphragConfig = { ...settings.graphrag };
              this.neo4jConfig = { ...settings.neo4j };
              this.isSavingSettings = false;
              this.showSnackbar('Settings reset to defaults');
              this.cdr.detectChanges();
            },
            error: (err) => {
              this.isSavingSettings = false;
              this.showSnackbar('Failed to reset settings: ' + err.message, true);
              this.cdr.detectChanges();
            }
          });
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // GRAPH BUILDING CONFIGURATION
  // ═══════════════════════════════════════════════════════════════════════════

  loadGraphBuildingConfig(): void {
    this.isLoadingGraphBuilding = true;
    this.kgService.getGraphBuildingConfig(this.factSheetId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.graphBuildingConfig = { ...config };
          this.isLoadingGraphBuilding = false;
          this.cdr.detectChanges();
        },
        error: (err) => {
          // If endpoint doesn't exist yet, use defaults
          console.log('Using default graph building config:', err.message);
          this.graphBuildingConfig = { ...DEFAULT_GRAPH_BUILDING_CONFIG };
          this.isLoadingGraphBuilding = false;
          this.cdr.detectChanges();
        }
      });
  }

  loadLlmProviders(): void {
    this.kgService.getLlmProviders()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (providers) => {
          if (providers && providers.length > 0) {
            this.availableLlmProviders = providers;
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.log('Using default LLM providers:', err.message);
        }
      });
  }

  onLlmProviderChange(): void {
    // Load models for the selected provider
    this.kgService.getLlmModels(this.graphBuildingConfig.modelProvider)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (models) => {
          if (models && models.length > 0) {
            this.availableLlmModels = models;
            // Reset to first model if current not available
            if (!models.includes(this.graphBuildingConfig.modelName)) {
              this.graphBuildingConfig.modelName = models[0];
            }
          }
          this.cdr.detectChanges();
        },
        error: (err) => {
          console.log('Using default models for provider:', err.message);
        }
      });
  }

  saveGraphBuildingConfig(): void {
    this.isSavingGraphBuilding = true;
    this.kgService.updateGraphBuildingConfig(this.factSheetId, this.graphBuildingConfig)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (config) => {
          this.graphBuildingConfig = { ...config };
          this.isSavingGraphBuilding = false;
          this.showSnackbar('Graph building settings saved');
          this.cdr.detectChanges();
        },
        error: (err) => {
          this.isSavingGraphBuilding = false;
          this.showSnackbar('Failed to save graph building settings: ' + err.message, true);
          this.cdr.detectChanges();
        }
      });
  }

  toggleEntityType(entityType: string): void {
    const index = this.graphBuildingConfig.entityTypes.indexOf(entityType);
    if (index > -1) {
      this.graphBuildingConfig.entityTypes.splice(index, 1);
    } else {
      this.graphBuildingConfig.entityTypes.push(entityType);
    }
  }

  isEntityTypeSelected(entityType: string): boolean {
    return this.graphBuildingConfig.entityTypes.includes(entityType);
  }

  selectAllEntityTypes(): void {
    this.graphBuildingConfig.entityTypes = [...this.availableEntityTypes];
  }

  deselectAllEntityTypes(): void {
    this.graphBuildingConfig.entityTypes = [];
  }

  resetGraphBuildingConfig(): void {
    const dialogData: ConfirmDialogData = {
      title: 'Reset Graph Building Settings',
      message: 'Reset graph building settings to defaults?',
      confirmText: 'Reset',
      confirmColor: 'warn',
      icon: 'settings_backup_restore'
    };

    this.dialog.open(ConfirmDialogComponent, { data: dialogData })
      .afterClosed()
      .pipe(
        filter(confirmed => confirmed === true),
        takeUntil(this.destroy$)
      )
      .subscribe(() => {
        this.graphBuildingConfig = { ...DEFAULT_GRAPH_BUILDING_CONFIG };
        this.showSnackbar('Graph building settings reset to defaults');
        this.cdr.detectChanges();
      });
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // HELPERS
  // ═══════════════════════════════════════════════════════════════════════════

  private showSnackbar(message: string, isError = false): void {
    this.snackBar.open(message, 'Close', {
      duration: 4000,
      horizontalPosition: 'center',
      verticalPosition: 'top',
      panelClass: isError ? 'snackbar-error' : 'snackbar-success'
    });
  }

  getProgressPercent(): number {
    if (!this.currentJob?.epochs || !this.currentJob?.currentEpoch) return 0;
    return (this.currentJob.currentEpoch / this.currentJob.epochs) * 100;
  }

  getSelectedAlgorithmDescription(): string {
    const algo = this.algorithms.find(a => a.id === this.selectedAlgorithm);
    return algo?.description || '';
  }

  calculateJobDuration(job: KGEmbeddingJob): string {
    if (!job.completedAt || !job.startedAt) return '-';
    const ms = new Date(job.completedAt).getTime() - new Date(job.startedAt).getTime();
    return this.formatDuration(ms);
  }
}
