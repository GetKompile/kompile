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

import { Component, OnInit, OnDestroy, ViewChild, ElementRef, ChangeDetectorRef, Optional } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { Subject, takeUntil } from 'rxjs';

import { ProcessDiagramService, DiagramSession, TranscriptEntry } from '../../services/process-diagram.service';
import { AgentService } from '../../services/agent.service';
import { FactSheetService } from '../../services/fact-sheet.service';
import { AgentProvider, FactSheet } from '../../models/api-models';
import { ProvenanceCitation } from '../../models/graph-models';
import { MermaidRendererComponent } from './mermaid-renderer.component';

@Component({
  standalone: true,
  selector: 'app-process-diagram',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatChipsModule, MatProgressBarModule, MatSnackBarModule,
    MatTooltipModule, MatExpansionModule, MatDividerModule, MatMenuModule,
    MermaidRendererComponent
  ],
  template: `
    <div class="diagram-container">
      <!-- Left panel: Controls + Transcript -->
      <div class="left-panel">
        <!-- Generation Controls -->
        <div class="controls-section">
          <h3><mat-icon>auto_fix_high</mat-icon> Generate Diagram</h3>

          <!-- Agent Selector -->
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Agent</mat-label>
            <mat-select [(ngModel)]="selectedAgent">
              <mat-option *ngFor="let agent of availableAgents" [value]="agent.name">
                {{ agent.displayName || agent.name }}
              </mat-option>
            </mat-select>
          </mat-form-field>

          <!-- Fact Sheet Selector -->
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Fact Sheet</mat-label>
            <mat-select [(ngModel)]="selectedFactSheetId">
              <mat-option [value]="null">All fact sheets</mat-option>
              <mat-option *ngFor="let fs of factSheets" [value]="fs.id">
                {{ fs.name }}
                <span *ngIf="fs.isActive" class="active-badge">active</span>
              </mat-option>
            </mat-select>
          </mat-form-field>

          <!-- Prompt -->
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Describe the business process to discover</mat-label>
            <textarea matInput [(ngModel)]="promptText" rows="3"
                      placeholder="e.g., Discover the end-to-end purchase order approval workflow from the indexed documents and emails"
                      [disabled]="isStreaming"></textarea>
          </mat-form-field>

          <!-- Action Buttons -->
          <div class="action-buttons">
            <button mat-raised-button color="primary"
                    (click)="startGeneration()"
                    [disabled]="isStreaming || !promptText?.trim() || !selectedAgent">
              <mat-icon>play_arrow</mat-icon>
              Generate
            </button>
            <button mat-button color="warn" *ngIf="isStreaming"
                    (click)="cancelGeneration()">
              <mat-icon>stop</mat-icon>
              Cancel
            </button>
          </div>

          <mat-progress-bar mode="indeterminate" *ngIf="isStreaming"
                            class="generation-progress"></mat-progress-bar>
        </div>

        <mat-divider></mat-divider>

        <!-- Transcript -->
        <div class="transcript-section">
          <div class="transcript-header">
            <h3><mat-icon>receipt_long</mat-icon> Agent Transcript</h3>
            <mat-chip-set *ngIf="transcriptEntries.length > 0">
              <mat-chip class="count-chip">{{ transcriptEntries.length }} events</mat-chip>
            </mat-chip-set>
          </div>

          <div class="transcript-scroll" #transcriptScroll>
            <div *ngIf="transcriptEntries.length === 0 && !isStreaming" class="empty-transcript">
              <mat-icon>chat_bubble_outline</mat-icon>
              <span>Generate a diagram to see the agent's thinking process</span>
            </div>

            <div *ngFor="let entry of transcriptEntries; trackBy: trackByIndex"
                 class="transcript-entry"
                 [ngClass]="'entry-' + entry.type">
              <div class="entry-meta">
                <mat-icon class="entry-icon">{{ getEntryIcon(entry.type) }}</mat-icon>
                <span class="entry-type">{{ entry.type }}</span>
                <span class="entry-time">{{ formatTime(entry.timestamp) }}</span>
              </div>
              <div class="entry-content" [class.collapsed]="isEntryCollapsed(entry)"
                   (click)="toggleEntry(entry)">
                {{ truncateContent(entry.content, isEntryCollapsed(entry) ? 200 : 10000) }}
                <span *ngIf="entry.content.length > 200 && isEntryCollapsed(entry)"
                      class="expand-hint">... click to expand</span>
              </div>
            </div>

            <div *ngIf="isStreaming" class="streaming-indicator">
              <div class="pulse-dot"></div>
              <span>Agent is working...</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Right panel: Diagram + Sources + Sessions -->
      <div class="right-panel">
        <!-- Current Diagram -->
        <div class="diagram-section">
          <div class="section-header">
            <h3>
              <mat-icon>schema</mat-icon>
              {{ currentTitle || 'Business Process Diagram' }}
            </h3>
            <div class="section-actions">
              <button mat-icon-button [matMenuTriggerFor]="sessionMenu"
                      matTooltip="Saved diagrams">
                <mat-icon>history</mat-icon>
              </button>
              <mat-menu #sessionMenu="matMenu">
                <button mat-menu-item (click)="loadSessions()">
                  <mat-icon>refresh</mat-icon> Refresh list
                </button>
                <mat-divider></mat-divider>
                <button mat-menu-item *ngFor="let s of savedSessions"
                        (click)="loadSession(s)">
                  <mat-icon>{{ getStatusIcon(s.status) }}</mat-icon>
                  <span>{{ s.title || s.prompt?.substring(0, 40) || 'Untitled' }}
                    <small class="session-date">{{ s.createdAt | date:'short' }}</small>
                  </span>
                </button>
                <button mat-menu-item *ngIf="savedSessions.length === 0" disabled>
                  <mat-icon>info</mat-icon> No saved diagrams
                </button>
              </mat-menu>
            </div>
          </div>

          <app-mermaid-renderer
            [code]="currentMermaidCode">
          </app-mermaid-renderer>

          <!-- Convert to Process button bar -->
          <div class="convert-bar" *ngIf="currentMermaidCode && !isStreaming">
            <button mat-stroked-button color="primary"
                    (click)="convertToProcess()"
                    [disabled]="isConverting || currentProcessDefinitionId"
                    matTooltip="Parse this diagram into an executable ProcessDefinition">
              <mat-icon>play_circle</mat-icon>
              {{ isConverting ? 'Converting...' : (currentProcessDefinitionId ? 'Converted' : 'Convert to Executable Process') }}
            </button>
            <span *ngIf="currentProcessDefinitionId" class="linked-process">
              <mat-icon>check_circle</mat-icon>
              Process: {{ currentProcessDefinitionId }}
            </span>
          </div>
        </div>

        <!-- Description -->
        <mat-expansion-panel *ngIf="currentDescription" class="info-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>description</mat-icon> Process Description
            </mat-panel-title>
          </mat-expansion-panel-header>
          <p class="description-text">{{ currentDescription }}</p>
        </mat-expansion-panel>

        <!-- Sources & Provenance -->
        <mat-expansion-panel *ngIf="structuredProvenance.length > 0 || currentSources" class="info-panel">
          <mat-expansion-panel-header>
            <mat-panel-title>
              <mat-icon>source</mat-icon> Sources & Evidence
              <span class="source-count" *ngIf="structuredProvenance.length > 0">
                ({{ structuredProvenance.length }})
              </span>
            </mat-panel-title>
          </mat-expansion-panel-header>

          <!-- Structured provenance cards -->
          <div class="provenance-cards" *ngIf="structuredProvenance.length > 0">
            <div *ngFor="let citation of structuredProvenance" class="provenance-card">
              <div class="citation-header">
                <span class="discovery-badge" [ngClass]="'discovery-' + (citation.discoverySource || 'unknown').toLowerCase()">
                  {{ formatDiscoverySource(citation.discoverySource || '') }}
                </span>
                <span class="entity-type-badge" *ngIf="citation.entityType">
                  {{ citation.entityType }}
                </span>
                <span class="citation-confidence">{{ ((citation.confidence || 0) * 100) | number:'1.0-0' }}%</span>
              </div>

              <div class="citation-title">
                <a *ngIf="citation.nodeId" class="entity-link" (click)="navigateToEntity(citation.nodeId)">
                  <mat-icon>hub</mat-icon>
                  {{ citation.title }}
                </a>
                <span *ngIf="!citation.nodeId">{{ citation.title }}</span>
              </div>

              <div class="citation-location" *ngIf="citation.location">
                <mat-icon>place</mat-icon>
                <span>{{ citation.documentTitle || citation.title }} {{ citation.location }}</span>
              </div>

              <div class="citation-excerpt" *ngIf="citation.extractedText">
                <mat-icon>format_quote</mat-icon>
                <blockquote>{{ citation.extractedText.length > 300 ? (citation.extractedText | slice:0:300) + '...' : citation.extractedText }}</blockquote>
              </div>
            </div>
          </div>

          <!-- Legacy fallback: raw text -->
          <pre class="sources-text" *ngIf="structuredProvenance.length === 0 && currentSources">{{ currentSources }}</pre>
        </mat-expansion-panel>
      </div>
    </div>
  `,
  styles: [`
    .diagram-container {
      display: flex;
      height: 100%;
      min-height: 500px;
      gap: 1px;
      background: var(--border-subtle);
      border-radius: var(--radius-lg);
      overflow: hidden;
    }

    /* Left Panel */
    .left-panel {
      width: 400px;
      min-width: 320px;
      display: flex;
      flex-direction: column;
      background: var(--bg-surface);
      overflow: hidden;
    }

    .controls-section {
      padding: 16px;
      flex-shrink: 0;
    }
    .controls-section h3 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px;
      font-size: 14px;
      color: var(--color-secondary);
    }
    .controls-section h3 mat-icon { font-size: 20px; width: 20px; height: 20px; }

    .full-width { width: 100%; }

    .active-badge {
      font-size: 10px;
      background: rgba(76,175,80,0.15);
      color: var(--accent-green);
      padding: 1px 6px;
      border-radius: var(--radius-full);
      margin-left: 8px;
    }

    .action-buttons {
      display: flex;
      gap: 8px;
      margin-bottom: 8px;
    }

    .generation-progress { margin-top: 8px; }

    /* Transcript */
    .transcript-section {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      padding: 12px 0 0;
      border-top: 1px solid var(--border-color);
    }

    .transcript-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 16px 8px;
    }
    .transcript-header h3 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      font-size: 13px;
      color: var(--color-primary);
    }
    .transcript-header h3 mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .count-chip {
      font-size: 11px !important;
      height: 22px !important;
    }

    .transcript-scroll {
      flex: 1;
      overflow-y: auto;
      padding: 0 12px 12px;
    }

    .empty-transcript {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 40px 16px;
      color: var(--text-tertiary);
      text-align: center;
      font-size: 13px;
    }
    .empty-transcript mat-icon { font-size: 36px; width: 36px; height: 36px; opacity: 0.3; }

    .transcript-entry {
      margin-bottom: 6px;
      border-radius: var(--radius-md);
      padding: 8px 10px;
      font-size: 12px;
      background: var(--bg-hover);
      border-left: 3px solid transparent;
    }
    .entry-chunk { border-left-color: var(--color-primary); }
    .entry-tool_use { border-left-color: var(--accent-amber); }
    .entry-sources { border-left-color: var(--accent-green); }
    .entry-start { border-left-color: var(--color-secondary); }
    .entry-complete { border-left-color: var(--accent-green); }
    .entry-stats { border-left-color: var(--color-primary); }
    .entry-error { border-left-color: var(--accent-red); }

    .entry-meta {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 4px;
    }
    .entry-icon { font-size: 14px; width: 14px; height: 14px; opacity: 0.7; }
    .entry-type { font-size: 10px; text-transform: uppercase; color: var(--text-tertiary); font-weight: 600; }
    .entry-time { font-size: 10px; color: var(--text-tertiary); margin-left: auto; }

    .entry-content {
      color: var(--text-secondary);
      line-height: 1.4;
      white-space: pre-wrap;
      word-break: break-word;
      cursor: pointer;
    }
    .entry-content.collapsed {
      max-height: 60px;
      overflow: hidden;
    }

    .expand-hint { color: var(--text-link); font-style: italic; }

    .streaming-indicator {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px;
      color: var(--color-primary);
      font-size: 12px;
    }

    .pulse-dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--color-primary);
      animation: pulse 1.5s infinite;
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; transform: scale(1); }
      50% { opacity: 0.4; transform: scale(0.8); }
    }

    /* Right Panel */
    .right-panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      background: var(--bg-body);
      overflow: auto;
      padding: 16px;
      gap: 12px;
    }

    .diagram-section {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-height: 300px;
    }

    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    .section-header h3 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      font-size: 16px;
      color: var(--text-primary);
    }
    .section-header h3 mat-icon { font-size: 22px; width: 22px; height: 22px; color: var(--color-secondary); }

    .section-actions { display: flex; gap: 4px; }

    .session-date {
      display: block;
      font-size: 11px;
      color: var(--text-tertiary);
    }

    .convert-bar {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 0;
    }
    .convert-bar button mat-icon {
      margin-right: 4px;
    }
    .linked-process {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      color: var(--text-secondary);
    }
    .linked-process mat-icon {
      font-size: 16px;
      width: 16px;
      height: 16px;
      color: #4caf50;
    }

    .info-panel {
      background: var(--bg-card) !important;
    }
    .info-panel mat-icon { font-size: 18px; width: 18px; height: 18px; margin-right: 8px; }

    .description-text {
      color: var(--text-secondary);
      font-size: 13px;
      line-height: 1.6;
      margin: 0;
    }

    .sources-text {
      color: var(--text-secondary);
      font-size: 12px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-word;
      margin: 0;
      font-family: var(--font-family-monospace, monospace);
    }

    .source-count {
      font-size: 12px;
      color: var(--text-tertiary, #888);
      margin-left: 4px;
    }

    .provenance-cards { display: flex; flex-direction: column; gap: 10px; padding: 4px 0; }

    .provenance-card {
      border: 1px solid var(--border-color, #e0e0e0);
      border-radius: 8px;
      padding: 10px 12px;
      background: var(--bg-surface, #fafafa);
    }

    .citation-header {
      display: flex; align-items: center; gap: 8px;
      margin-bottom: 6px; flex-wrap: wrap;
    }

    .discovery-badge {
      font-size: 10px; font-weight: 600;
      padding: 2px 8px; border-radius: 12px;
      text-transform: uppercase;
    }
    .discovery-email_flow        { background: #e3f2fd; color: #1565c0; }
    .discovery-excel_computation { background: #e8f5e9; color: #2e7d32; }
    .discovery-document_pipeline { background: #fce4ec; color: #b71c1c; }
    .discovery-community         { background: #f3e5f5; color: #6a1b9a; }
    .discovery-llm_generated     { background: #fffde7; color: #f57f17; }
    .discovery-unknown           { background: #eceff1; color: #546e7a; }

    .entity-type-badge {
      font-size: 10px; padding: 2px 6px; border-radius: 4px;
      background: var(--bg-hover, #f0f0f0); color: var(--text-secondary, #666);
    }

    .citation-confidence {
      margin-left: auto;
      font-size: 11px; color: var(--text-tertiary, #999);
    }

    .citation-title { font-size: 13px; font-weight: 500; margin-bottom: 4px; }

    .entity-link {
      display: inline-flex; align-items: center; gap: 4px;
      color: var(--text-link, #1976d2); cursor: pointer; text-decoration: none;
    }
    .entity-link:hover { text-decoration: underline; }
    .entity-link mat-icon { font-size: 14px; width: 14px; height: 14px; }

    .citation-location {
      display: flex; align-items: center; gap: 4px;
      font-size: 12px; color: var(--text-tertiary, #999); margin-bottom: 4px;
    }
    .citation-location mat-icon { font-size: 14px; width: 14px; height: 14px; }

    .citation-excerpt {
      display: flex; gap: 6px; margin-top: 6px; font-size: 12px;
    }
    .citation-excerpt mat-icon {
      font-size: 16px; width: 16px; height: 16px;
      flex-shrink: 0; color: var(--text-tertiary, #999);
    }
    .citation-excerpt blockquote {
      margin: 0; padding: 4px 8px;
      border-left: 3px solid var(--border-color, #e0e0e0);
      color: var(--text-secondary, #666);
      font-style: italic; line-height: 1.4;
    }
  `]
})
export class ProcessDiagramComponent implements OnInit, OnDestroy {
  @ViewChild('transcriptScroll') transcriptScroll!: ElementRef;

  // Form state
  selectedAgent: string = '';
  selectedFactSheetId: number | null = null;
  promptText: string = '';

  // Data
  availableAgents: AgentProvider[] = [];
  factSheets: FactSheet[] = [];
  savedSessions: DiagramSession[] = [];

  // Streaming state
  isStreaming = false;
  isConverting = false;
  transcriptEntries: TranscriptEntry[] = [];
  currentMermaidCode: string | null = null;
  currentTitle: string | null = null;
  currentDescription: string | null = null;
  currentSources: string | null = null;
  currentProcessDefinitionId: string | null = null;

  // Provenance
  structuredProvenance: ProvenanceCitation[] = [];
  loadingProvenance = false;

  private currentSessionId: number | null = null;
  private fullContent = '';
  private expandedEntries = new Set<TranscriptEntry>();
  private destroy$ = new Subject<void>();

  constructor(
    private diagramService: ProcessDiagramService,
    private agentService: AgentService,
    private factSheetService: FactSheetService,
    private snackBar: MatSnackBar,
    private cdr: ChangeDetectorRef,
    @Optional() private router: Router
  ) {}

  ngOnInit(): void {
    this.loadAgents();
    this.loadFactSheets();
    this.loadSessions();

    // Subscribe to streaming state
    this.diagramService.isStreaming
      .pipe(takeUntil(this.destroy$))
      .subscribe(s => {
        this.isStreaming = s;
        this.cdr.detectChanges();
      });

    this.diagramService.transcript
      .pipe(takeUntil(this.destroy$))
      .subscribe(entries => {
        this.transcriptEntries = entries;
        this.cdr.detectChanges();
        this.scrollTranscriptToBottom();
      });

    this.diagramService.currentSessionId
      .pipe(takeUntil(this.destroy$))
      .subscribe(id => {
        this.currentSessionId = id;
      });

    this.diagramService.streamError
      .pipe(takeUntil(this.destroy$))
      .subscribe(err => {
        this.snackBar.open(`Generation error: ${err}`, 'Close', { duration: 5000 });
        if (this.currentSessionId) {
          this.diagramService.failSession(this.currentSessionId, err).subscribe();
        }
      });

    this.diagramService.streamComplete
      .pipe(takeUntil(this.destroy$))
      .subscribe(content => {
        this.fullContent = content;
        this.extractDiagramFromContent(content);
        this.finalizeCurrentSession();
      });

    // Live mermaid extraction during streaming
    this.diagramService.streamingContent
      .pipe(takeUntil(this.destroy$))
      .subscribe(content => {
        if (content && this.isStreaming) {
          this.tryExtractLiveMermaid(content);
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
    this.diagramService.cancelGeneration();
  }

  // ── Actions ─────────────────────────────────────────────────────────────

  startGeneration(): void {
    if (!this.promptText?.trim() || !this.selectedAgent) return;

    this.currentMermaidCode = null;
    this.currentTitle = null;
    this.currentDescription = null;
    this.currentSources = null;
    this.currentProcessDefinitionId = null;
    this.fullContent = '';
    this.expandedEntries.clear();

    this.diagramService.startGeneration({
      prompt: this.promptText.trim(),
      agentName: this.selectedAgent,
      factSheetId: this.selectedFactSheetId
    });
  }

  cancelGeneration(): void {
    this.diagramService.cancelGeneration();
    if (this.currentSessionId) {
      this.diagramService.failSession(this.currentSessionId, 'Cancelled by user').subscribe();
    }
    this.snackBar.open('Generation cancelled', 'Close', { duration: 2000 });
  }

  loadSession(session: DiagramSession): void {
    this.currentMermaidCode = session.mermaidCode;
    this.currentTitle = session.title;
    this.currentDescription = session.description;
    this.currentSources = session.sourcesJson;
    this.currentProcessDefinitionId = session.processDefinitionId || null;
    this.currentSessionId = session.id;
    this.promptText = session.prompt || '';

    if (session.transcriptJson) {
      try {
        const parsed = JSON.parse(session.transcriptJson);
        this.transcriptEntries = Array.isArray(parsed) ? parsed : [];
      } catch {
        this.transcriptEntries = [];
      }
    } else {
      this.transcriptEntries = [];
    }

    this.loadProvenance(session.id);
    this.cdr.detectChanges();
  }

  loadSessions(): void {
    this.diagramService.listSessions(this.selectedFactSheetId ?? undefined)
      .subscribe({
        next: sessions => { this.savedSessions = sessions; },
        error: () => { this.savedSessions = []; }
      });
  }

  deleteSession(session: DiagramSession): void {
    this.diagramService.deleteSession(session.id).subscribe(() => {
      this.savedSessions = this.savedSessions.filter(s => s.id !== session.id);
      this.snackBar.open('Diagram deleted', 'Close', { duration: 2000 });
    });
  }

  // ── Diagram → Process conversion ──────────────────────────────────────

  convertToProcess(): void {
    if (!this.currentSessionId || !this.currentMermaidCode) return;
    this.isConverting = true;
    this.diagramService.convertToProcess(this.currentSessionId).subscribe({
      next: (processDef: any) => {
        this.currentProcessDefinitionId = processDef.id;
        this.isConverting = false;
        this.snackBar.open(
          `Process "${processDef.name}" created (${processDef.phases?.length || 0} phases)`,
          'Close', { duration: 4000 }
        );
        this.cdr.detectChanges();
      },
      error: (err: any) => {
        this.isConverting = false;
        this.snackBar.open('Conversion failed: ' + (err.error?.message || err.message || 'Unknown error'),
          'Close', { duration: 4000 });
        this.cdr.detectChanges();
      }
    });
  }

  // ── Transcript helpers ────────────────────────────────────────────────

  trackByIndex(index: number): number { return index; }

  getEntryIcon(type: string): string {
    switch (type) {
      case 'chunk': return 'text_snippet';
      case 'tool_use': return 'build';
      case 'sources': return 'library_books';
      case 'start': return 'play_arrow';
      case 'complete': return 'check_circle';
      case 'stats': return 'analytics';
      case 'error': return 'error';
      default: return 'info';
    }
  }

  formatTime(timestamp: string): string {
    try {
      return new Date(timestamp).toLocaleTimeString();
    } catch {
      return '';
    }
  }

  isEntryCollapsed(entry: TranscriptEntry): boolean {
    return !this.expandedEntries.has(entry);
  }

  toggleEntry(entry: TranscriptEntry): void {
    if (this.expandedEntries.has(entry)) {
      this.expandedEntries.delete(entry);
    } else {
      this.expandedEntries.add(entry);
    }
  }

  truncateContent(content: string, max: number): string {
    return content.length > max ? content.substring(0, max) : content;
  }

  getStatusIcon(status: string): string {
    switch (status) {
      case 'COMPLETED': return 'check_circle';
      case 'COMPLETED_NO_DIAGRAM': return 'warning';
      case 'FAILED': return 'error';
      case 'RUNNING': return 'sync';
      default: return 'help';
    }
  }

  // ── Private helpers ───────────────────────────────────────────────────

  private loadAgents(): void {
    this.agentService.getAvailableAgents().subscribe({
      next: agents => {
        this.availableAgents = agents;
        if (agents.length > 0 && !this.selectedAgent) {
          this.selectedAgent = agents[0].name;
        }
      },
      error: () => { this.availableAgents = []; }
    });
  }

  private loadFactSheets(): void {
    this.factSheetService.loadSheets().subscribe();
    this.factSheetService.sheets$
      .pipe(takeUntil(this.destroy$))
      .subscribe(sheets => {
        this.factSheets = sheets;
        const active = sheets.find(s => s.isActive);
        if (active && this.selectedFactSheetId === null) {
          this.selectedFactSheetId = active.id;
        }
      });
  }

  private extractDiagramFromContent(content: string): void {
    // Extract mermaid code block
    const mermaidMatch = content.match(/```mermaid\s*\n([\s\S]*?)```/);
    if (mermaidMatch) {
      this.currentMermaidCode = mermaidMatch[1].trim();
    }

    // Extract title
    const titleMatch = content.match(/##\s*Process Title\s*\n+([\s\S]*?)(?=\n##|\n```|$)/);
    if (titleMatch) {
      this.currentTitle = titleMatch[1].trim().split('\n')[0];
    }

    // Extract description
    const descMatch = content.match(/##\s*Description\s*\n+([\s\S]*?)(?=\n##|$)/);
    if (descMatch) {
      this.currentDescription = descMatch[1].trim();
    }

    // Extract sources
    const srcMatch = content.match(/##\s*Sources\s*\n+([\s\S]*?)(?=\n##|$)/);
    if (srcMatch) {
      this.currentSources = srcMatch[1].trim();
    }

    this.cdr.detectChanges();
  }

  private tryExtractLiveMermaid(content: string): void {
    const mermaidMatch = content.match(/```mermaid\s*\n([\s\S]*?)```/);
    if (mermaidMatch) {
      const code = mermaidMatch[1].trim();
      if (code !== this.currentMermaidCode) {
        this.currentMermaidCode = code;
        this.cdr.detectChanges();
      }
    }
  }

  private finalizeCurrentSession(): void {
    if (!this.currentSessionId) return;

    this.diagramService.finalizeSession(this.currentSessionId, {
      transcriptJson: JSON.stringify(this.transcriptEntries),
      mermaidCode: this.currentMermaidCode,
      title: this.currentTitle,
      description: this.currentDescription,
      sourcesJson: this.currentSources
    }).subscribe({
      next: () => {
        this.loadSessions();
        if (this.currentSessionId) {
          this.loadProvenance(this.currentSessionId);
        }
        this.snackBar.open('Diagram saved', 'Close', { duration: 2000 });
      },
      error: err => {
        console.error('Failed to finalize session:', err);
      }
    });
  }

  // ── Provenance ──────────────────────────────────────────────────────────

  private loadProvenance(sessionId: number): void {
    this.loadingProvenance = true;
    this.structuredProvenance = [];
    this.diagramService.getSessionProvenance(sessionId)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: citations => {
          this.structuredProvenance = citations || [];
          this.loadingProvenance = false;
          this.cdr.detectChanges();
        },
        error: () => {
          this.structuredProvenance = [];
          this.loadingProvenance = false;
        }
      });
  }

  formatDiscoverySource(source: string): string {
    switch (source) {
      case 'EMAIL_FLOW':        return 'Email';
      case 'EXCEL_COMPUTATION': return 'Spreadsheet';
      case 'DOCUMENT_PIPELINE': return 'Document';
      case 'COMMUNITY':         return 'Community';
      case 'LLM_GENERATED':     return 'AI-Generated';
      default: return source || 'Unknown';
    }
  }

  navigateToEntity(nodeId: string): void {
    if (this.router) {
      this.router.navigate(['/knowledge-graph'], { queryParams: { nodeId } });
    }
  }

  private scrollTranscriptToBottom(): void {
    if (this.transcriptScroll?.nativeElement) {
      const el = this.transcriptScroll.nativeElement;
      setTimeout(() => { el.scrollTop = el.scrollHeight; }, 50);
    }
  }
}
