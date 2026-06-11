import { Component, OnInit, OnDestroy, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';
import { RagPipelineService } from '../../services/rag-pipeline.service';
import {
  RagPipelineDefinition,
  PipelineModelStatus,
  ModelRequirement
} from '../../models/rag-pipeline-models';
import { RagPipelineEditorComponent } from '../rag-pipeline-editor/rag-pipeline-editor.component';

@Component({
  selector: 'app-rag-pipeline-selector',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatFormFieldModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    MatSnackBarModule
  ],
  template: `
    <div class="pipeline-selector-container">
      <div class="selector-row">
        <mat-form-field appearance="outline" class="pipeline-field">
          <mat-label>RAG Pipeline</mat-label>
          <mat-select [(ngModel)]="selectedPipelineId"
                      [ngModelOptions]="{standalone: true}"
                      (selectionChange)="onPipelineSelected()">
            <mat-optgroup label="Built-in Templates">
              <mat-option *ngFor="let p of builtinPipelines" [value]="p.id">
                {{ p.name }}
              </mat-option>
            </mat-optgroup>
            <mat-optgroup label="Custom Pipelines" *ngIf="customPipelines.length > 0">
              <mat-option *ngFor="let p of customPipelines" [value]="p.id">
                {{ p.name }}
              </mat-option>
            </mat-optgroup>
          </mat-select>
        </mat-form-field>

        <div class="status-indicator" *ngIf="modelStatus">
          <mat-icon [class]="getStatusClass()"
                    [matTooltip]="getStatusTooltip()">
            {{ getStatusIcon() }}
          </mat-icon>
        </div>

        <button mat-icon-button matTooltip="Edit pipeline"
                (click)="editPipeline()" *ngIf="selectedPipeline">
          <mat-icon>tune</mat-icon>
        </button>

        <button mat-icon-button matTooltip="Create custom pipeline"
                (click)="createPipeline()">
          <mat-icon>add_circle_outline</mat-icon>
        </button>

        <button mat-icon-button matTooltip="Delete pipeline" color="warn"
                (click)="deletePipeline()"
                *ngIf="selectedPipeline && !selectedPipeline.builtin">
          <mat-icon>delete_outline</mat-icon>
        </button>
      </div>

      <div class="pipeline-details" *ngIf="selectedPipeline">
        <span class="detail-chip" *ngIf="selectedPipeline.embedding">
          Embed: {{ selectedPipeline.embedding.modelId || 'none' }}
        </span>
        <span class="detail-chip" *ngIf="selectedPipeline.retrieval">
          {{ selectedPipeline.retrieval.strategy }} (k={{ selectedPipeline.retrieval.topK }})
        </span>
        <span class="detail-chip" *ngIf="selectedPipeline.reranking?.enabled">
          Rerank: {{ selectedPipeline.reranking?.rerankerType }}
        </span>
        <span class="detail-chip" *ngIf="selectedPipeline.llm">
          LLM: {{ selectedPipeline.llm.provider }}
        </span>
      </div>

      <div class="model-requirements" *ngIf="modelStatus && modelStatus.requirements.length > 0">
        <div class="requirement" *ngFor="let req of modelStatus.requirements">
          <mat-icon [class]="'req-' + req.status" class="req-icon">
            {{ req.status === 'ready' ? 'check_circle' : req.status === 'available' ? 'pending' : 'error' }}
          </mat-icon>
          <span class="req-label">{{ req.stage }}:</span>
          <span class="req-model">{{ req.modelId }}</span>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .pipeline-selector-container {
      padding: 8px 0;
    }
    .selector-row {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .pipeline-field {
      flex: 1;
      min-width: 250px;
    }
    .status-indicator {
      display: flex;
      align-items: center;
    }
    .status-ready { color: #4caf50; }
    .status-partial { color: #ff9800; }
    .status-missing { color: #f44336; }
    .pipeline-details {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin-top: 4px;
      padding-left: 4px;
    }
    .detail-chip {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 12px;
      background: rgba(25, 118, 210, 0.1);
      color: #1976d2;
      font-size: 11px;
      font-weight: 500;
    }
    .model-requirements {
      margin-top: 8px;
      padding: 8px;
      background: rgba(0,0,0,0.02);
      border-radius: 4px;
    }
    .requirement {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 12px;
      padding: 2px 0;
    }
    .req-icon { font-size: 16px; width: 16px; height: 16px; }
    .req-ready { color: #4caf50; }
    .req-available { color: #ff9800; }
    .req-staging { color: #2196f3; }
    .req-missing { color: #f44336; }
    .req-label { font-weight: 500; text-transform: capitalize; }
    .req-model { color: #666; }
  `]
})
export class RagPipelineSelectorComponent implements OnInit, OnDestroy {
  @Input() factSheetId?: number;
  @Output() pipelineChanged = new EventEmitter<RagPipelineDefinition>();

  builtinPipelines: RagPipelineDefinition[] = [];
  customPipelines: RagPipelineDefinition[] = [];
  selectedPipelineId: string | null = null;
  selectedPipeline: RagPipelineDefinition | null = null;
  modelStatus: PipelineModelStatus | null = null;

  private destroy$ = new Subject<void>();

  constructor(
    private ragPipelineService: RagPipelineService,
    private dialog: MatDialog,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadPipelines();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadPipelines(): void {
    this.ragPipelineService.listPipelines()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (pipelines) => {
          this.builtinPipelines = pipelines.filter(p => p.builtin);
          this.customPipelines = pipelines.filter(p => !p.builtin);
          // Auto-select first if nothing selected
          if (!this.selectedPipelineId && this.builtinPipelines.length > 0) {
            this.selectedPipelineId = this.builtinPipelines[0].id;
            this.onPipelineSelected();
          }
        },
        error: (err) => console.error('Failed to load pipelines', err)
      });
  }

  onPipelineSelected(): void {
    if (!this.selectedPipelineId) return;
    const all = [...this.builtinPipelines, ...this.customPipelines];
    this.selectedPipeline = all.find(p => p.id === this.selectedPipelineId) || null;

    if (this.selectedPipeline) {
      this.pipelineChanged.emit(this.selectedPipeline);
      this.loadModelStatus();
    }
  }

  loadModelStatus(): void {
    if (!this.selectedPipelineId) return;
    this.ragPipelineService.getModelStatus(this.selectedPipelineId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (status) => this.modelStatus = status,
        error: () => this.modelStatus = null
      });
  }

  getStatusClass(): string {
    if (!this.modelStatus) return '';
    if (this.modelStatus.allModelsReady) return 'status-ready';
    const hasReady = this.modelStatus.requirements.some(r => r.status === 'ready');
    return hasReady ? 'status-partial' : 'status-missing';
  }

  getStatusIcon(): string {
    if (!this.modelStatus) return 'help';
    if (this.modelStatus.allModelsReady) return 'check_circle';
    return 'warning';
  }

  getStatusTooltip(): string {
    if (!this.modelStatus) return 'Loading status...';
    if (this.modelStatus.allModelsReady) return 'All models ready';
    const missing = this.modelStatus.requirements
      .filter(r => r.status !== 'ready')
      .map(r => `${r.stage}: ${r.modelId} (${r.status})`)
      .join(', ');
    return `Models not ready: ${missing}`;
  }

  createPipeline(): void {
    const dialogRef = this.dialog.open(RagPipelineEditorComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: 'create' }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.ragPipelineService.createPipeline(result)
          .pipe(takeUntil(this.destroy$))
          .subscribe({
            next: (created) => {
              this.snackBar.open(`Pipeline "${created.name}" created`, 'OK', { duration: 3000 });
              this.loadPipelines();
              this.selectedPipelineId = created.id;
              this.onPipelineSelected();
            },
            error: (err) => this.snackBar.open('Failed to create pipeline', 'OK', { duration: 3000 })
          });
      }
    });
  }

  editPipeline(): void {
    if (!this.selectedPipeline) return;
    const dialogRef = this.dialog.open(RagPipelineEditorComponent, {
      width: '700px',
      maxHeight: '90vh',
      data: { mode: this.selectedPipeline.builtin ? 'view' : 'edit', pipeline: this.selectedPipeline }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result && this.selectedPipelineId) {
        if (this.selectedPipeline?.builtin) {
          // Create as custom copy
          result.id = null;
          result.name = result.name + ' (Custom)';
          this.ragPipelineService.createPipeline(result)
            .pipe(takeUntil(this.destroy$))
            .subscribe({
              next: (created) => {
                this.snackBar.open(`Custom pipeline "${created.name}" created`, 'OK', { duration: 3000 });
                this.loadPipelines();
                this.selectedPipelineId = created.id;
                this.onPipelineSelected();
              }
            });
        } else {
          this.ragPipelineService.updatePipeline(this.selectedPipelineId, result)
            .pipe(takeUntil(this.destroy$))
            .subscribe({
              next: (updated) => {
                this.snackBar.open(`Pipeline "${updated.name}" updated`, 'OK', { duration: 3000 });
                this.loadPipelines();
              }
            });
        }
      }
    });
  }

  deletePipeline(): void {
    if (!this.selectedPipeline || this.selectedPipeline.builtin) return;
    if (!confirm(`Delete pipeline "${this.selectedPipeline.name}"?`)) return;

    this.ragPipelineService.deletePipeline(this.selectedPipelineId!)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: () => {
          this.snackBar.open('Pipeline deleted', 'OK', { duration: 3000 });
          this.selectedPipelineId = this.builtinPipelines[0]?.id || null;
          this.loadPipelines();
          this.onPipelineSelected();
        }
      });
  }
}
