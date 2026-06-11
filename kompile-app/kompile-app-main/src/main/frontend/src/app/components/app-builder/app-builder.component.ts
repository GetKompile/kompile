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

import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpClientModule } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatStepperModule } from '@angular/material/stepper';
import { backendUrl } from '../../services/base.service';

export const MODULE_CATEGORIES = [
  'CORE',
  'LLM',
  'EMBEDDING',
  'VECTORSTORE',
  'LOADER',
  'CHUNKER',
  'TOOL',
  'ENTERPRISE',
  'ADVANCED',
  'PIPELINE'
];

@Component({
  standalone: true,
  selector: 'app-app-builder',
  imports: [
    CommonModule,
    FormsModule,
    HttpClientModule,
    MatCardModule,
    MatIconModule,
    MatButtonModule,
    MatSelectModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatExpansionModule,
    MatSlideToggleModule,
    MatStepperModule
  ],
  templateUrl: './app-builder.component.html',
  styleUrls: ['./app-builder.component.css']
})
export class AppBuilderComponent implements OnInit {
  readonly backendUrl = backendUrl;
  readonly categories = MODULE_CATEGORIES;

  // Data from backend
  presets: any[] = [];
  moduleCatalog: any = null;

  // Wizard state
  selectedPreset: string = 'hosted-llm-rag';
  selectedModules: Set<string> = new Set();

  // Build options
  configName: string = '';
  appTitle: string = 'Kompile RAG Console';
  buildNative: boolean = true;
  skipTests: boolean = true;
  backend: string = 'nd4j-cuda-12.9';
  javacppPlatform: string = 'linux-x86_64';

  // UI state
  building: boolean = false;
  buildResult: any = null;

  constructor(private http: HttpClient, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.loadPresets();
    this.loadModuleCatalog();
  }

  loadPresets(): void {
    this.http.get<any[]>(`${this.backendUrl}/build/presets`).subscribe({
      next: (presets) => {
        this.presets = presets || [];
        // Apply the default preset if data is available
        const defaultPreset = this.presets.find(p => p.name === this.selectedPreset);
        if (defaultPreset && defaultPreset.modules) {
          this.selectedModules = new Set<string>(defaultPreset.modules);
        }
      },
      error: (err) => {
        console.warn('Could not load presets from backend, using defaults:', err);
        this.presets = this.getDefaultPresets();
        const defaultPreset = this.presets.find(p => p.name === this.selectedPreset);
        if (defaultPreset && defaultPreset.modules) {
          this.selectedModules = new Set<string>(defaultPreset.modules);
        }
      }
    });
  }

  loadModuleCatalog(): void {
    this.http.get<any>(`${this.backendUrl}/build/modules`).subscribe({
      next: (catalog) => {
        this.moduleCatalog = catalog;
      },
      error: (err) => {
        console.warn('Could not load module catalog from backend, using defaults:', err);
        this.moduleCatalog = this.getDefaultModuleCatalog();
      }
    });
  }

  onPresetChange(presetName: string): void {
    this.selectedPreset = presetName;
    const preset = this.presets.find(p => p.name === presetName);
    if (preset && preset.modules) {
      this.selectedModules = new Set<string>(preset.modules);
    } else {
      this.selectedModules = new Set<string>();
    }
  }

  toggleModule(moduleId: string): void {
    if (this.selectedModules.has(moduleId)) {
      this.selectedModules.delete(moduleId);
    } else {
      this.selectedModules.add(moduleId);
    }
    // Create a new Set reference so Angular change detection picks it up
    this.selectedModules = new Set(this.selectedModules);
  }

  isModuleSelected(moduleId: string): boolean {
    return this.selectedModules.has(moduleId);
  }

  getModulesByCategory(category: string): any[] {
    if (!this.moduleCatalog || !this.moduleCatalog.modules) {
      return [];
    }
    return (this.moduleCatalog.modules as any[]).filter(
      (m: any) => m.category === category
    );
  }

  getModuleCountByCategory(category: string): string {
    const mods = this.getModulesByCategory(category);
    const selected = mods.filter(m => this.selectedModules.has(m.id)).length;
    return `${selected}/${mods.length}`;
  }

  getSelectedPresetObject(): any {
    return this.presets.find(p => p.name === this.selectedPreset) || null;
  }

  getSelectedModuleList(): string[] {
    return Array.from(this.selectedModules);
  }

  startBuild(): void {
    if (!this.configName.trim()) {
      this.snackBar.open('App name is required.', 'Close', { duration: 3000 });
      return;
    }

    this.building = true;
    this.buildResult = null;

    const payload = {
      configName: this.configName.trim(),
      appTitle: this.appTitle,
      modules: Array.from(this.selectedModules),
      buildNative: this.buildNative,
      skipTests: this.skipTests,
      backend: this.backend,
      javacppPlatform: this.javacppPlatform,
      preset: this.selectedPreset
    };

    this.http.post<any>(`${this.backendUrl}/build/app`, payload).subscribe({
      next: (result) => {
        this.building = false;
        this.buildResult = result;
        this.snackBar.open(
          `Build started for "${this.configName}"`,
          'Close',
          { duration: 4000, panelClass: ['success-snackbar'] }
        );
      },
      error: (err) => {
        this.building = false;
        this.buildResult = { error: err.error?.message || err.message || 'Build failed' };
        this.snackBar.open(
          `Build failed: ${this.buildResult.error}`,
          'Close',
          { duration: 6000, panelClass: ['error-snackbar'] }
        );
      }
    });
  }

  // ─── Fallback data (used when backend is unavailable) ───────────────────────

  private getDefaultPresets(): any[] {
    return [
      {
        name: 'hosted-llm-rag',
        label: 'Hosted LLM RAG',
        description: 'Minimal RAG app using a hosted LLM (OpenAI, Anthropic, Gemini). No local model inference required.',
        modules: ['core', 'openai-llm', 'anserini-embedding', 'anserini-vectorstore', 'tika-loader', 'rag-tool']
      },
      {
        name: 'samediff-rag',
        label: 'SameDiff RAG',
        description: 'Full local RAG stack using SameDiff for embeddings and inference. Runs entirely on-premise.',
        modules: ['core', 'samediff-llm', 'samediff-embedding', 'anserini-vectorstore', 'pdf-loader', 'rag-tool']
      },
      {
        name: 'pgvector-rag',
        label: 'PostgreSQL / pgvector RAG',
        description: 'RAG app backed by PostgreSQL with the pgvector extension for vector similarity search.',
        modules: ['core', 'openai-llm', 'openai-embedding', 'pgvector-vectorstore', 'tika-loader', 'rag-tool']
      },
      {
        name: 'graph-rag',
        label: 'Graph RAG (Neo4j)',
        description: 'Hybrid vector + knowledge-graph RAG using Neo4j for entity relationships.',
        modules: ['core', 'openai-llm', 'anserini-embedding', 'anserini-vectorstore', 'neo4j-graph', 'tika-loader', 'rag-tool']
      },
      {
        name: 'full-stack',
        label: 'Full Stack (All Modules)',
        description: 'Enable all available modules. Best for evaluation; trim down for production.',
        modules: [
          'core', 'openai-llm', 'anthropic-llm', 'samediff-llm',
          'anserini-embedding', 'openai-embedding', 'samediff-embedding',
          'anserini-vectorstore', 'pgvector-vectorstore', 'chroma-vectorstore',
          'pdf-loader', 'tika-loader', 'office-loader',
          'rag-tool', 'filesystem-tool',
          'neo4j-graph'
        ]
      }
    ];
  }

  private getDefaultModuleCatalog(): any {
    return {
      modules: [
        // CORE
        { id: 'core', category: 'CORE', label: 'Kompile Core', description: 'Required base module with interfaces and utilities.' },
        // LLM
        { id: 'openai-llm', category: 'LLM', label: 'OpenAI LLM', description: 'GPT-4 / GPT-3.5 via OpenAI API.' },
        { id: 'anthropic-llm', category: 'LLM', label: 'Anthropic LLM', description: 'Claude models via Anthropic API.' },
        { id: 'gemini-llm', category: 'LLM', label: 'Google Gemini LLM', description: 'Gemini Pro / Flash via Google AI API.' },
        { id: 'samediff-llm', category: 'LLM', label: 'SameDiff LLM', description: 'Local LLM inference using SameDiff runtime.' },
        // EMBEDDING
        { id: 'anserini-embedding', category: 'EMBEDDING', label: 'Anserini (SameDiff) Embedding', description: 'Dense embeddings using local SameDiff encoders (BGE, Arctic, etc.).' },
        { id: 'openai-embedding', category: 'EMBEDDING', label: 'OpenAI Embedding', description: 'text-embedding-3-small/large via OpenAI API.' },
        { id: 'samediff-embedding', category: 'EMBEDDING', label: 'SameDiff Embedding', description: 'Direct SameDiff-based embedding pipeline.' },
        { id: 'postgresml-embedding', category: 'EMBEDDING', label: 'PostgresML Embedding', description: 'Embeddings served by PostgresML extension.' },
        { id: 'sentence-transformer-embedding', category: 'EMBEDDING', label: 'Sentence Transformer', description: 'Python subprocess wrapper for HuggingFace sentence-transformers.' },
        // VECTORSTORE
        { id: 'anserini-vectorstore', category: 'VECTORSTORE', label: 'Anserini (Lucene HNSW)', description: 'Primary vector store backed by Lucene HNSW index.' },
        { id: 'pgvector-vectorstore', category: 'VECTORSTORE', label: 'pgvector (PostgreSQL)', description: 'Vector similarity search via PostgreSQL pgvector extension.' },
        { id: 'chroma-vectorstore', category: 'VECTORSTORE', label: 'Chroma', description: 'Chroma open-source vector database client.' },
        // LOADER
        { id: 'pdf-loader', category: 'LOADER', label: 'PDF Extended Loader', description: 'Advanced PDF parsing with layout and table extraction.' },
        { id: 'tika-loader', category: 'LOADER', label: 'Apache Tika Loader', description: 'Multi-format document parsing (PDF, HTML, XML, email, etc.).' },
        { id: 'office-loader', category: 'LOADER', label: 'MS Office Loader', description: 'Microsoft Word, Excel, and PowerPoint document parsing.' },
        // CHUNKER
        { id: 'recursive-chunker', category: 'CHUNKER', label: 'Recursive Chunker', description: 'Recursively splits on paragraph, sentence, and word boundaries.' },
        { id: 'markdown-chunker', category: 'CHUNKER', label: 'Markdown Chunker', description: 'Structure-aware chunking that respects Markdown headings.' },
        { id: 'token-chunker', category: 'CHUNKER', label: 'Token Chunker', description: 'Fixed-size chunking measured in tokens.' },
        // TOOL
        { id: 'rag-tool', category: 'TOOL', label: 'RAG Query Tool', description: 'Spring AI @Tool for RAG-augmented query answering.' },
        { id: 'filesystem-tool', category: 'TOOL', label: 'Filesystem Tool', description: 'File read/write/list operations exposed as an AI tool.' },
        // ENTERPRISE
        { id: 'neo4j-graph', category: 'ENTERPRISE', label: 'Neo4j Graph RAG', description: 'Knowledge graph extraction and hybrid vector+graph retrieval.' },
        { id: 'ocr-support', category: 'ENTERPRISE', label: 'OCR Support', description: 'Tesseract-based OCR for scanned documents and images.' },
        // ADVANCED
        { id: 'model-manager', category: 'ADVANCED', label: 'Model Manager', description: 'Centralized model download, caching, and SHA256 verification.' },
        { id: 'subprocess-config', category: 'ADVANCED', label: 'Subprocess Config', description: 'Controls embedding / ingest subprocess launch mode (JVM vs native).' },
        // PIPELINE
        { id: 'pipeline-core', category: 'PIPELINE', label: 'Pipeline Core', description: 'Kompile Pipelines Framework execution engine.' },
        { id: 'pipeline-python', category: 'PIPELINE', label: 'Python Step', description: 'Run Python scripts as pipeline steps.' },
        { id: 'pipeline-onnx', category: 'PIPELINE', label: 'ONNX Step', description: 'ONNX model inference as a pipeline step.' }
      ]
    };
  }
}
