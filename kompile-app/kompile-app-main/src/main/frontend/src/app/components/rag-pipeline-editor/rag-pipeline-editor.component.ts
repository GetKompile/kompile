import { Component, Inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatRadioModule } from '@angular/material/radio';
import { environment } from '../../../environments/environment';
import {
  RagPipelineDefinition,
  EmbeddingStageConfig,
  RetrievalStageConfig,
  RerankingStageConfig,
  LlmStageConfig
} from '../../models/rag-pipeline-models';

export interface EditorDialogData {
  mode: 'create' | 'edit' | 'view';
  pipeline?: RagPipelineDefinition;
}

@Component({
  selector: 'app-rag-pipeline-editor',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSliderModule,
    MatSlideToggleModule,
    MatButtonModule,
    MatIconModule,
    MatExpansionModule,
    MatRadioModule
  ],
  template: `
    <h2 mat-dialog-title>
      {{ mode === 'create' ? 'Create Pipeline' : mode === 'edit' ? 'Edit Pipeline' : 'View Pipeline' }}
    </h2>
    <mat-dialog-content class="editor-content">
      <!-- Name & Description -->
      <div class="section">
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Pipeline Name</mat-label>
          <input matInput [(ngModel)]="name" [readonly]="mode === 'view'">
        </mat-form-field>
        <mat-form-field appearance="outline" class="full-width">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="description" rows="2" [readonly]="mode === 'view'"></textarea>
        </mat-form-field>
      </div>

      <!-- Embedding Stage -->
      <mat-expansion-panel expanded>
        <mat-expansion-panel-header>
          <mat-panel-title>
            <mat-icon class="section-icon">memory</mat-icon>
            Embedding
          </mat-panel-title>
        </mat-expansion-panel-header>
        <div class="stage-content">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Embedding Model</mat-label>
            <mat-select [(ngModel)]="embeddingModelId" [disabled]="mode === 'view'">
              <mat-option value="none">None (keyword only)</mat-option>
              <mat-option value="bge-base-en-v1.5">BGE Base EN v1.5</mat-option>
              <mat-option value="arctic-embed-l">Arctic Embed L</mat-option>
              <mat-option value="cosdpr-distil">CosDPR Distil</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Model Source</mat-label>
            <mat-select [(ngModel)]="embeddingModelSource" [disabled]="mode === 'view'">
              <mat-option value="default">Default</mat-option>
              <mat-option value="registry">Registry</mat-option>
              <mat-option value="archive">Archive</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
      </mat-expansion-panel>

      <!-- Retrieval Stage -->
      <mat-expansion-panel expanded>
        <mat-expansion-panel-header>
          <mat-panel-title>
            <mat-icon class="section-icon">search</mat-icon>
            Retrieval
          </mat-panel-title>
        </mat-expansion-panel-header>
        <div class="stage-content">
          <mat-radio-group [(ngModel)]="retrievalStrategy" [disabled]="mode === 'view'" class="strategy-group">
            <mat-radio-button value="HYBRID">Hybrid</mat-radio-button>
            <mat-radio-button value="SEMANTIC">Semantic Only</mat-radio-button>
            <mat-radio-button value="KEYWORD">Keyword Only</mat-radio-button>
          </mat-radio-group>
          <mat-form-field appearance="outline">
            <mat-label>Top K</mat-label>
            <input matInput type="number" [(ngModel)]="retrievalTopK" [readonly]="mode === 'view'" min="1" max="100">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Similarity Threshold</mat-label>
            <input matInput type="number" [(ngModel)]="similarityThreshold" [readonly]="mode === 'view'"
                   min="0" max="1" step="0.05">
          </mat-form-field>
        </div>
      </mat-expansion-panel>

      <!-- Reranking Stage -->
      <mat-expansion-panel>
        <mat-expansion-panel-header>
          <mat-panel-title>
            <mat-icon class="section-icon">sort</mat-icon>
            Reranking
          </mat-panel-title>
        </mat-expansion-panel-header>
        <div class="stage-content">
          <mat-slide-toggle [(ngModel)]="rerankingEnabled" [disabled]="mode === 'view'">
            Enable Reranking
          </mat-slide-toggle>
          <div *ngIf="rerankingEnabled" class="reranking-options">
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Reranker Type</mat-label>
              <mat-select [(ngModel)]="rerankerType" [disabled]="mode === 'view'">
                <mat-option value="cross_encoder">Cross Encoder</mat-option>
                <mat-option value="rrf">Reciprocal Rank Fusion (RRF)</mat-option>
                <mat-option value="mmr">Maximal Marginal Relevance (MMR)</mat-option>
                <mat-option value="rm3">RM3 Query Expansion</mat-option>
                <mat-option value="bm25prf">BM25 PRF</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" *ngIf="rerankerType === 'cross_encoder'" class="full-width">
              <mat-label>Cross-Encoder Model</mat-label>
              <mat-select [(ngModel)]="crossEncoderModel" [disabled]="mode === 'view'">
                <mat-option value="ms-marco-MiniLM-L-6-v2">MiniLM L6 (fast)</mat-option>
                <mat-option value="ms-marco-MiniLM-L-12-v2">MiniLM L12 (accurate)</mat-option>
                <mat-option value="stsb-TinyBERT-L-4">TinyBERT L4 (tiny)</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Rerank Top K</mat-label>
              <input matInput type="number" [(ngModel)]="rerankTopK" [readonly]="mode === 'view'" min="1" max="500">
            </mat-form-field>
            <mat-form-field appearance="outline" *ngIf="rerankerType === 'mmr'">
              <mat-label>MMR Lambda (0=diversity, 1=relevance)</mat-label>
              <input matInput type="number" [(ngModel)]="mmrLambda" [readonly]="mode === 'view'"
                     min="0" max="1" step="0.1">
            </mat-form-field>
          </div>
        </div>
      </mat-expansion-panel>

      <!-- LLM Generation Stage -->
      <mat-expansion-panel expanded>
        <mat-expansion-panel-header>
          <mat-panel-title>
            <mat-icon class="section-icon">smart_toy</mat-icon>
            LLM Generation
          </mat-panel-title>
        </mat-expansion-panel-header>
        <div class="stage-content">
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Provider</mat-label>
            <mat-select [(ngModel)]="llmProvider" [disabled]="mode === 'view'">
              <mat-option *ngFor="let p of llmProviders" [value]="p.id">{{ p.displayName }}</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Model</mat-label>
            <input matInput [(ngModel)]="llmModel" [readonly]="mode === 'view'"
                   placeholder="Model ID">
          </mat-form-field>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>System Prompt (optional)</mat-label>
            <textarea matInput [(ngModel)]="systemPrompt" rows="3" [readonly]="mode === 'view'"></textarea>
          </mat-form-field>
          <div class="param-row">
            <mat-form-field appearance="outline">
              <mat-label>Temperature</mat-label>
              <input matInput type="number" [(ngModel)]="temperature" [readonly]="mode === 'view'"
                     min="0" max="2" step="0.1">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Max Tokens</mat-label>
              <input matInput type="number" [(ngModel)]="maxTokens" [readonly]="mode === 'view'"
                     min="1" max="8192">
            </mat-form-field>
          </div>
        </div>
      </mat-expansion-panel>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-raised-button color="primary" (click)="save()" *ngIf="mode !== 'view'">
        {{ mode === 'create' ? 'Create' : 'Save' }}
      </button>
      <button mat-raised-button color="primary" (click)="saveAsCopy()" *ngIf="mode === 'view'">
        Save as Custom Copy
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .editor-content { min-width: 600px; max-height: 70vh; }
    .section { margin-bottom: 16px; }
    .full-width { width: 100%; }
    .stage-content { padding: 8px 0; display: flex; flex-direction: column; gap: 12px; }
    .section-icon { margin-right: 8px; }
    .strategy-group { display: flex; gap: 16px; margin-bottom: 8px; }
    .reranking-options { margin-top: 12px; display: flex; flex-direction: column; gap: 12px; }
    .param-row { display: flex; gap: 16px; }
    .param-row mat-form-field { flex: 1; }
    mat-expansion-panel { margin-bottom: 8px; }
  `]
})
export class RagPipelineEditorComponent implements OnInit {
  mode: 'create' | 'edit' | 'view' = 'create';

  // Pipeline fields
  name = '';
  description = '';

  // Embedding
  embeddingModelId = 'bge-base-en-v1.5';
  embeddingModelSource = 'default';

  // Retrieval
  retrievalStrategy: 'SEMANTIC' | 'KEYWORD' | 'HYBRID' = 'HYBRID';
  retrievalTopK = 10;
  similarityThreshold = 0;

  // Reranking
  rerankingEnabled = false;
  rerankerType = 'cross_encoder';
  crossEncoderModel = 'ms-marco-MiniLM-L-6-v2';
  rerankTopK = 100;
  mmrLambda = 0.5;

  // LLM
  llmProvider = 'default';
  llmModel = '';
  systemPrompt = '';
  temperature = 0.7;
  maxTokens = 1024;
  llmProviders: Array<{id: string; displayName: string}> = [
    { id: 'default', displayName: 'Default (Active LLM)' }
  ];

  constructor(
    private dialogRef: MatDialogRef<RagPipelineEditorComponent>,
    @Inject(MAT_DIALOG_DATA) public data: EditorDialogData,
    private http: HttpClient
  ) {}

  ngOnInit(): void {
    this.mode = this.data.mode;
    if (this.data.pipeline) {
      this.loadFromDefinition(this.data.pipeline);
    }
    this.loadLlmProviders();
  }

  private loadLlmProviders(): void {
    this.http.get<any[]>(`${environment.apiUrl}/graph-extraction/model-providers`).subscribe({
      next: (providers) => {
        this.llmProviders = providers.map(p => ({
          id: p.id,
          displayName: p.name || p.id
        }));
      },
      error: () => {} // keep default list on failure
    });
  }

  loadFromDefinition(p: RagPipelineDefinition): void {
    this.name = p.name || '';
    this.description = p.description || '';

    if (p.embedding) {
      this.embeddingModelId = p.embedding.modelId || 'bge-base-en-v1.5';
      this.embeddingModelSource = p.embedding.modelSource || 'default';
    }
    if (p.retrieval) {
      this.retrievalStrategy = p.retrieval.strategy || 'HYBRID';
      this.retrievalTopK = p.retrieval.topK ?? 10;
      this.similarityThreshold = p.retrieval.similarityThreshold ?? 0;
    }
    if (p.reranking) {
      this.rerankingEnabled = p.reranking.enabled;
      this.rerankerType = p.reranking.rerankerType || 'cross_encoder';
      this.crossEncoderModel = p.reranking.crossEncoderModel || 'ms-marco-MiniLM-L-6-v2';
      this.rerankTopK = p.reranking.topK ?? 100;
      this.mmrLambda = p.reranking.mmrLambda ?? 0.5;
    }
    if (p.llm) {
      this.llmProvider = p.llm.provider || 'default';
      this.llmModel = p.llm.model || '';
      this.systemPrompt = p.llm.systemPrompt || '';
      this.temperature = p.llm.temperature ?? 0.7;
      this.maxTokens = p.llm.maxTokens ?? 1024;
    }
  }

  buildDefinition(): Partial<RagPipelineDefinition> {
    return {
      name: this.name,
      description: this.description,
      embedding: {
        modelId: this.embeddingModelId,
        modelSource: this.embeddingModelSource
      },
      retrieval: {
        strategy: this.retrievalStrategy,
        topK: this.retrievalTopK,
        similarityThreshold: this.similarityThreshold
      },
      reranking: {
        enabled: this.rerankingEnabled,
        rerankerType: this.rerankingEnabled ? this.rerankerType : 'none',
        crossEncoderModel: this.rerankerType === 'cross_encoder' ? this.crossEncoderModel : undefined,
        topK: this.rerankTopK,
        mmrLambda: this.mmrLambda
      },
      llm: {
        provider: this.llmProvider,
        model: this.llmModel,
        systemPrompt: this.systemPrompt || undefined,
        temperature: this.temperature,
        maxTokens: this.maxTokens
      }
    };
  }

  save(): void {
    if (!this.name.trim()) return;
    this.dialogRef.close(this.buildDefinition());
  }

  saveAsCopy(): void {
    if (!this.name.trim()) return;
    this.dialogRef.close(this.buildDefinition());
  }
}
