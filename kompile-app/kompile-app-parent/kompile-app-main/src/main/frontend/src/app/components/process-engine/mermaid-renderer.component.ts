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

import {
  Component, Input, ElementRef, ViewChild, OnChanges, OnDestroy,
  SimpleChanges, AfterViewInit, Output, EventEmitter, ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';
import { Subject, takeUntil } from 'rxjs';
import { ThemeService } from '../../services/theme.service';

@Component({
  standalone: true,
  selector: 'app-mermaid-renderer',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatButtonToggleModule, MatTooltipModule
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mermaid-container">
      <!-- Toolbar -->
      <div class="mermaid-toolbar">
        <mat-button-toggle-group [(ngModel)]="viewMode" class="view-toggle">
          <mat-button-toggle value="render" matTooltip="Rendered diagram">
            <mat-icon>image</mat-icon>
          </mat-button-toggle>
          <mat-button-toggle value="code" matTooltip="Mermaid source code">
            <mat-icon>code</mat-icon>
          </mat-button-toggle>
          <mat-button-toggle value="split" matTooltip="Split view">
            <mat-icon>vertical_split</mat-icon>
          </mat-button-toggle>
        </mat-button-toggle-group>

        <div class="toolbar-actions">
          <button mat-icon-button matTooltip="Copy Mermaid code"
                  (click)="copyCode()" *ngIf="code">
            <mat-icon>content_copy</mat-icon>
          </button>
          <button mat-icon-button matTooltip="Download SVG"
                  (click)="downloadSvg()" *ngIf="renderedSvg">
            <mat-icon>download</mat-icon>
          </button>
        </div>
      </div>

      <!-- Render View -->
      <div class="mermaid-content" [ngClass]="{'split-view': viewMode === 'split'}">
        <div class="render-panel" *ngIf="viewMode !== 'code'">
          <div class="render-area" #renderArea>
            <div *ngIf="renderError" class="render-error">
              <mat-icon>error_outline</mat-icon>
              <span>{{ renderError }}</span>
            </div>
            <div *ngIf="!code && !renderError" class="no-diagram">
              <mat-icon>schema</mat-icon>
              <span>No diagram to display</span>
            </div>
          </div>
        </div>

        <!-- Code View -->
        <div class="code-panel" *ngIf="viewMode !== 'render'">
          <pre class="code-block"><code>{{ code || '// No mermaid code' }}</code></pre>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .mermaid-container {
      display: flex;
      flex-direction: column;
      height: 100%;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      overflow: hidden;
      background: var(--bg-surface);
    }

    .mermaid-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 6px 12px;
      background: var(--bg-surface-elevated);
      border-bottom: 1px solid var(--border-color);
    }

    .view-toggle {
      height: 32px;
    }
    .view-toggle .mat-button-toggle { height: 32px !important; }
    .view-toggle .mat-button-toggle mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .toolbar-actions { display: flex; gap: 4px; }
    .toolbar-actions button { width: 32px; height: 32px; }
    .toolbar-actions mat-icon { font-size: 18px; width: 18px; height: 18px; }

    .mermaid-content {
      flex: 1;
      overflow: auto;
      min-height: 200px;
    }
    .mermaid-content.split-view {
      display: flex;
    }

    .render-panel {
      flex: 1;
      overflow: auto;
      padding: 16px;
    }
    .render-area {
      display: flex;
      justify-content: center;
      align-items: flex-start;
      min-height: 200px;
    }
    .render-area :deep(svg) {
      max-width: 100%;
      height: auto;
    }

    .code-panel {
      flex: 1;
      overflow: auto;
    }
    .split-view .code-panel {
      border-left: 1px solid var(--border-color);
    }

    .code-block {
      margin: 0;
      padding: 16px;
      background: var(--bg-code);
      color: var(--text-code);
      font-family: var(--font-family-monospace, 'Roboto Mono', monospace);
      font-size: 13px;
      line-height: 1.5;
      white-space: pre-wrap;
      word-break: break-word;
      min-height: 200px;
    }

    .render-error {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--accent-red);
      padding: 16px;
      background: rgba(239,83,80,0.1);
      border-radius: var(--radius-sm);
      font-size: 13px;
    }

    .no-diagram {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      color: var(--text-tertiary);
      padding: 40px;
    }
    .no-diagram mat-icon { font-size: 48px; width: 48px; height: 48px; opacity: 0.3; }
  `]
})
export class MermaidRendererComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input() code: string | null = null;
  @Output() codeChange = new EventEmitter<string>();

  @ViewChild('renderArea') renderArea!: ElementRef;

  viewMode: 'render' | 'code' | 'split' = 'render';
  renderError: string | null = null;
  renderedSvg: string | null = null;

  private mermaidLoaded = false;
  private mermaidModule: any = null;
  private renderCounter = 0;
  private destroy$ = new Subject<void>();

  constructor(private themeService: ThemeService) {}

  async ngAfterViewInit(): Promise<void> {
    await this.loadMermaid();
    if (this.code) {
      await this.renderDiagram();
    }

    this.themeService.theme$
      .pipe(takeUntil(this.destroy$))
      .subscribe(async () => {
        if (this.mermaidLoaded) {
          this.initMermaidTheme();
          await this.renderDiagram();
        }
      });
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  async ngOnChanges(changes: SimpleChanges): Promise<void> {
    if (changes['code'] && this.mermaidLoaded) {
      await this.renderDiagram();
    }
  }

  private async loadMermaid(): Promise<void> {
    if (this.mermaidLoaded) return;
    try {
      this.mermaidModule = await import('mermaid');
      this.initMermaidTheme();
      this.mermaidLoaded = true;
    } catch (e) {
      console.error('Failed to load mermaid:', e);
      this.renderError = 'Failed to load Mermaid rendering library';
    }
  }

  private initMermaidTheme(): void {
    const isDark = this.themeService.isDark;
    this.mermaidModule.default.initialize({
      startOnLoad: false,
      theme: isDark ? 'dark' : 'default',
      themeVariables: isDark ? {
        primaryColor: '#1e1f29',
        primaryTextColor: '#e2e4e9',
        primaryBorderColor: 'hsl(220, 90%, 55%)',
        lineColor: 'hsl(220, 90%, 55%)',
        secondaryColor: '#252633',
        tertiaryColor: '#22232d',
        fontFamily: '"Inter", "Roboto", sans-serif'
      } : {
        primaryColor: '#e3f2fd',
        primaryTextColor: '#1a1f36',
        primaryBorderColor: 'hsl(220, 90%, 55%)',
        lineColor: 'hsl(220, 90%, 45%)',
        secondaryColor: '#f5f6f8',
        tertiaryColor: '#ffffff',
        fontFamily: '"Inter", "Roboto", sans-serif'
      },
      flowchart: {
        htmlLabels: true,
        curve: 'basis',
        padding: 15
      }
    });
  }

  private async renderDiagram(): Promise<void> {
    if (!this.code || !this.mermaidLoaded || !this.renderArea) {
      return;
    }

    this.renderError = null;
    const id = `mermaid-diagram-${++this.renderCounter}`;

    try {
      const { svg, bindFunctions } = await this.mermaidModule.default.render(id, this.code);
      this.renderedSvg = svg;
      if (this.renderArea?.nativeElement) {
        this.renderArea.nativeElement.innerHTML = svg;
        if (bindFunctions) {
          bindFunctions(this.renderArea.nativeElement);
        }
      }
    } catch (e: any) {
      this.renderError = e.message || 'Failed to render diagram';
      this.renderedSvg = null;
      if (this.renderArea?.nativeElement) {
        this.renderArea.nativeElement.innerHTML = '';
      }
    }
  }

  copyCode(): void {
    if (this.code) {
      navigator.clipboard.writeText(this.code).catch(() => {});
    }
  }

  downloadSvg(): void {
    if (!this.renderedSvg) return;
    const blob = new Blob([this.renderedSvg], { type: 'image/svg+xml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'business-process-diagram.svg';
    a.click();
    URL.revokeObjectURL(url);
  }
}
