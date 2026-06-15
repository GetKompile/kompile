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

import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ProcessEngineService, ExcelConversionResult } from '../../services/process-engine.service';

/**
 * Side-by-side view of an Excel formula graph (source artifact) and
 * the LLM-generated code (derived artifact). The code is editable —
 * users can review, fix, and re-execute before saving it to the process step.
 */
@Component({
  standalone: true,
  selector: 'app-excel-artifact',
  imports: [
    CommonModule, FormsModule,
    MatIconModule, MatButtonModule, MatSelectModule,
    MatProgressSpinnerModule, MatChipsModule, MatTooltipModule
  ],
  template: `
    <div class="excel-artifact-container">
      <!-- Header -->
      <div class="artifact-header">
        <div class="header-left">
          <mat-icon>table_chart</mat-icon>
          <span class="title">Excel Formula Converter</span>
          <span class="workbook-name" *ngIf="workbookName">{{ workbookName }}</span>
        </div>
        <div class="header-right">
          <mat-select [(value)]="targetLanguage" class="lang-select">
            <mat-option value="javascript">JavaScript</mat-option>
            <mat-option value="python">Python</mat-option>
          </mat-select>
          <button mat-raised-button color="primary"
                  (click)="convert()" [disabled]="converting || !spreadsheetGraphJson">
            <mat-icon *ngIf="!converting">auto_fix_high</mat-icon>
            <mat-progress-spinner *ngIf="converting" diameter="18" mode="indeterminate"></mat-progress-spinner>
            Convert
          </button>
        </div>
      </div>

      <!-- Side-by-side panels -->
      <div class="panels">
        <!-- Left: Formula Graph -->
        <div class="panel source-panel">
          <div class="panel-header">
            <mat-icon>functions</mat-icon>
            <span>Spreadsheet Formulas</span>
            <span class="badge" *ngIf="formulaCells.length">{{ formulaCells.length }} formulas</span>
          </div>
          <div class="panel-content">
            <div *ngIf="!formulaCells.length && !inputCells.length" class="empty-state">
              Paste or load a SpreadsheetGraph JSON to see formulas here.
            </div>

            <!-- Input cells -->
            <div *ngIf="inputCells.length" class="cell-section">
              <div class="section-label">Input Cells</div>
              <div *ngFor="let cell of inputCells" class="cell-row input-cell">
                <span class="cell-ref">{{ cell.ref }}</span>
                <span class="cell-value">{{ cell.value }}</span>
                <span class="cell-type">{{ cell.type }}</span>
              </div>
            </div>

            <!-- Formula cells -->
            <div *ngIf="formulaCells.length" class="cell-section">
              <div class="section-label">Formula Cells</div>
              <div *ngFor="let cell of formulaCells" class="cell-row formula-cell">
                <span class="cell-ref">{{ cell.ref }}</span>
                <span class="cell-formula">= {{ cell.formula }}</span>
                <span class="cell-value" *ngIf="cell.value">[{{ cell.value }}]</span>
              </div>
            </div>

            <!-- Dependencies -->
            <div *ngIf="dependencies.length" class="cell-section">
              <div class="section-label">Dependencies ({{ dependencies.length }})</div>
              <div *ngFor="let dep of dependencies.slice(0, 20)" class="dep-row">
                <span class="dep-from">{{ dep.from }}</span>
                <mat-icon class="dep-arrow">arrow_forward</mat-icon>
                <span class="dep-to">{{ dep.to }}</span>
                <mat-chip-option class="dep-type" [selectable]="false">{{ dep.type }}</mat-chip-option>
              </div>
              <div *ngIf="dependencies.length > 20" class="more-items">
                ... and {{ dependencies.length - 20 }} more
              </div>
            </div>
          </div>
        </div>

        <!-- Right: Generated Code (editable) -->
        <div class="panel code-panel">
          <div class="panel-header">
            <mat-icon>code</mat-icon>
            <span>Generated Code</span>
            <span class="badge source-badge" *ngIf="codeSource">{{ codeSource }}</span>
            <span class="badge edited-badge" *ngIf="codeEdited">edited</span>
            <div class="panel-actions">
              <button mat-icon-button matTooltip="Reset to generated version"
                      *ngIf="codeEdited" (click)="resetCode()">
                <mat-icon>undo</mat-icon>
              </button>
              <button mat-icon-button matTooltip="Copy code"
                      (click)="copyCode()">
                <mat-icon>content_copy</mat-icon>
              </button>
            </div>
          </div>
          <div class="panel-content code-content">
            <textarea
              *ngIf="!converting"
              class="code-editor"
              [(ngModel)]="editableCode"
              (ngModelChange)="onCodeEdit()"
              [placeholder]="'Click Convert to generate ' + targetLanguage + ' code from the formulas...'"
              spellcheck="false">
            </textarea>
            <div *ngIf="converting" class="converting-overlay">
              <mat-progress-spinner diameter="40" mode="indeterminate"></mat-progress-spinner>
              <span>Converting formulas via LLM...</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Footer: Execute -->
      <div class="artifact-footer" *ngIf="editableCode">
        <div class="footer-info">
          <mat-chip-set *ngIf="conversionResult">
            <mat-chip>{{ conversionResult.inputCells?.length || 0 }} inputs</mat-chip>
            <mat-chip>{{ conversionResult.outputCells?.length || 0 }} outputs</mat-chip>
            <mat-chip>{{ conversionResult.formulaCount || 0 }} formulas</mat-chip>
          </mat-chip-set>
        </div>
        <div class="footer-actions">
          <button mat-raised-button color="accent"
                  (click)="execute()" [disabled]="executing">
            <mat-icon *ngIf="!executing">play_arrow</mat-icon>
            <mat-progress-spinner *ngIf="executing" diameter="18" mode="indeterminate"></mat-progress-spinner>
            Execute
          </button>
          <button mat-raised-button
                  (click)="saveCode()">
            <mat-icon>save</mat-icon>
            Save Code to Step
          </button>
        </div>
      </div>

      <!-- Execution result -->
      <div class="execution-result" *ngIf="executionResult">
        <div class="result-header">
          <mat-icon [class.success]="!executionError" [class.error]="executionError">
            {{ executionError ? 'error' : 'check_circle' }}
          </mat-icon>
          <span>{{ executionError ? 'Execution Failed' : 'Execution Result' }}</span>
        </div>
        <pre class="result-json">{{ executionResult | json }}</pre>
      </div>

      <!-- Error -->
      <div class="error-banner" *ngIf="errorMessage">
        <mat-icon>warning</mat-icon>
        <span>{{ errorMessage }}</span>
        <button mat-icon-button (click)="errorMessage = ''"><mat-icon>close</mat-icon></button>
      </div>
    </div>
  `,
  styles: [`
    .excel-artifact-container {
      display: flex;
      flex-direction: column;
      height: 100%;
      border: 1px solid #e0e0e0;
      border-radius: 8px;
      overflow: hidden;
    }

    .artifact-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 16px;
      background: #f5f5f5;
      border-bottom: 1px solid #e0e0e0;
    }
    .header-left { display: flex; align-items: center; gap: 8px; }
    .title { font-weight: 600; font-size: 14px; }
    .workbook-name { color: #666; font-size: 13px; }
    .header-right { display: flex; align-items: center; gap: 8px; }
    .lang-select { width: 130px; }

    .panels {
      display: flex;
      flex: 1;
      min-height: 300px;
      overflow: hidden;
    }

    .panel {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .source-panel { border-right: 2px solid #e0e0e0; }

    .panel-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background: #fafafa;
      border-bottom: 1px solid #eee;
      font-size: 13px;
      font-weight: 500;
    }
    .panel-actions { margin-left: auto; display: flex; gap: 2px; }
    .badge {
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 10px;
      background: #e3f2fd;
      color: #1565c0;
    }
    .source-badge { background: #e8f5e9; color: #2e7d32; }
    .edited-badge { background: #fff3e0; color: #e65100; }

    .panel-content {
      flex: 1;
      overflow-y: auto;
      padding: 8px 12px;
      font-family: 'Roboto Mono', monospace;
      font-size: 12px;
    }

    .empty-state {
      color: #999;
      padding: 24px;
      text-align: center;
      font-family: inherit;
    }

    .cell-section { margin-bottom: 12px; }
    .section-label {
      font-weight: 600;
      font-size: 11px;
      text-transform: uppercase;
      color: #888;
      margin-bottom: 4px;
      letter-spacing: 0.5px;
    }

    .cell-row {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 3px 0;
      border-bottom: 1px solid #f5f5f5;
    }
    .cell-ref { font-weight: 600; color: #1565c0; min-width: 100px; }
    .cell-value { color: #333; }
    .cell-type { color: #999; font-size: 11px; margin-left: auto; }
    .cell-formula { color: #2e7d32; }
    .formula-cell .cell-value { color: #888; font-size: 11px; }

    .dep-row {
      display: flex;
      align-items: center;
      gap: 4px;
      padding: 2px 0;
      font-size: 11px;
    }
    .dep-from { color: #1565c0; }
    .dep-arrow { font-size: 14px; width: 14px; height: 14px; color: #999; }
    .dep-to { color: #2e7d32; }
    .dep-type { font-size: 10px; }
    .more-items { color: #999; font-style: italic; padding: 4px 0; }

    .code-content { padding: 0; position: relative; }
    .code-editor {
      width: 100%;
      height: 100%;
      min-height: 250px;
      border: none;
      outline: none;
      resize: none;
      padding: 12px;
      font-family: 'Roboto Mono', monospace;
      font-size: 12px;
      line-height: 1.5;
      background: #1e1e1e;
      color: #d4d4d4;
      tab-size: 2;
    }
    .code-editor::placeholder { color: #666; }

    .converting-overlay {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 12px;
      height: 100%;
      min-height: 250px;
      color: #666;
    }

    .artifact-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 16px;
      background: #f5f5f5;
      border-top: 1px solid #e0e0e0;
    }
    .footer-info { display: flex; gap: 4px; }
    .footer-actions { display: flex; gap: 8px; }

    .execution-result {
      border-top: 1px solid #e0e0e0;
      padding: 12px 16px;
      max-height: 200px;
      overflow-y: auto;
    }
    .result-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
      font-weight: 500;
    }
    .result-header .success { color: #2e7d32; }
    .result-header .error { color: #c62828; }
    .result-json {
      font-family: 'Roboto Mono', monospace;
      font-size: 12px;
      background: #f5f5f5;
      padding: 8px;
      border-radius: 4px;
      margin: 0;
      white-space: pre-wrap;
      word-break: break-all;
    }

    .error-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 16px;
      background: #fce4ec;
      color: #c62828;
      font-size: 13px;
    }
    .error-banner mat-icon { color: #c62828; }
    .error-banner button { margin-left: auto; }
  `]
})
export class ExcelArtifactComponent implements OnChanges {
  /** SpreadsheetGraph JSON — the source artifact */
  @Input() spreadsheetGraphJson = '';
  /** Pre-existing generated code (from a saved step) */
  @Input() existingCode = '';
  /** Emitted when user clicks "Save Code to Step" with the edited code */
  @Output() codeSaved = new EventEmitter<{ code: string; language: string }>();
  /** Emitted after successful execution with the result map */
  @Output() executed = new EventEmitter<Record<string, any>>();

  targetLanguage = 'javascript';
  converting = false;
  executing = false;
  editableCode = '';
  originalGeneratedCode = '';
  codeEdited = false;
  codeSource = '';
  errorMessage = '';
  workbookName = '';
  conversionResult: ExcelConversionResult | null = null;
  executionResult: Record<string, any> | null = null;
  executionError = false;

  // Parsed graph data for display
  inputCells: { ref: string; value: string; type: string }[] = [];
  formulaCells: { ref: string; formula: string; value: string }[] = [];
  dependencies: { from: string; to: string; type: string }[] = [];

  constructor(private processService: ProcessEngineService) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['spreadsheetGraphJson'] && this.spreadsheetGraphJson) {
      this.parseGraph();
    }
    if (changes['existingCode'] && this.existingCode) {
      this.editableCode = this.existingCode;
      this.originalGeneratedCode = this.existingCode;
      this.codeSource = 'saved';
      this.codeEdited = false;
    }
  }

  private parseGraph(): void {
    try {
      const graph = JSON.parse(this.spreadsheetGraphJson);

      // Detect format: Graph (entities/relationships) vs SpreadsheetGraph (cells/dependencies)
      if (graph.entities && !graph.cells) {
        this.parseGraphFormat(graph);
      } else {
        this.parseSpreadsheetFormat(graph);
      }
    } catch (e) {
      this.inputCells = [];
      this.formulaCells = [];
      this.dependencies = [];
    }
  }

  /** Parse SpreadsheetGraph format: { workbookName, cells: { ref: CellNode }, dependencies } */
  private parseSpreadsheetFormat(graph: any): void {
    this.workbookName = graph.workbookName || '';

    this.inputCells = [];
    this.formulaCells = [];

    const cells = graph.cells || {};
    for (const [ref, cell] of Object.entries<any>(cells)) {
      if (cell.cellType === 'FORMULA') {
        this.formulaCells.push({
          ref: cell.cellReference || ref,
          formula: cell.formula || '',
          value: cell.displayValue || ''
        });
      } else if (cell.cellType !== 'BLANK') {
        this.inputCells.push({
          ref: cell.cellReference || ref,
          value: cell.displayValue || '',
          type: cell.cellType || 'UNKNOWN'
        });
      }
    }

    this.dependencies = (graph.dependencies || []).map((d: any) => ({
      from: d.formulaCell,
      to: d.referencedCell,
      type: d.dependencyType?.replace('_REFERENCE', '') || 'CELL'
    }));
  }

  /** Parse Graph format: { entities: [...], relationships: [...] } from TableCellGraphBuilder */
  private parseGraphFormat(graph: any): void {
    this.inputCells = [];
    this.formulaCells = [];
    this.dependencies = [];

    const entities: any[] = graph.entities || [];
    const relationships: any[] = graph.relationships || [];

    // Extract workbook/table name from the TABLE entity
    const tableEntity = entities.find((e: any) => e.type === 'TABLE' || e.type === 'SHEET');
    this.workbookName = tableEntity?.title || graph.id || '';

    // Convert entities to display cells
    for (const entity of entities) {
      const type = entity.type || '';
      if (type !== 'CELL' && type !== 'HEADER_CELL' && type !== 'FORMULA_CELL') continue;

      const meta = entity.metadata || {};
      const rowIdx = meta.rowIndex ?? 0;
      const colIdx = meta.colIndex ?? 0;
      const cellValue = meta.cellValue ?? entity.title ?? '';
      const colLetter = this.columnIndexToLetter(colIdx);
      const ref = (this.workbookName || 'Sheet1') + '!' + colLetter + (rowIdx + 1);

      if (type === 'FORMULA_CELL') {
        this.formulaCells.push({
          ref,
          formula: meta.formula || cellValue,
          value: cellValue
        });
      } else {
        this.inputCells.push({
          ref,
          value: String(cellValue),
          type: meta.isHeader ? 'HEADER' : (this.guessType(cellValue))
        });
      }
    }

    // Convert relationships to dependencies for display
    for (const rel of relationships) {
      const relType = rel.type || '';
      if (relType === 'DEPENDS_ON' || relType === 'CROSS_SHEET_DEPENDS_ON' || relType === 'HEADER_OF') {
        this.dependencies.push({
          from: this.shortRef(rel.source),
          to: this.shortRef(rel.target),
          type: relType.replace('_', ' ')
        });
      }
    }
  }

  /** Convert 0-based column index to Excel letter (0→A, 25→Z, 26→AA) */
  private columnIndexToLetter(col: number): string {
    let result = '';
    let c = col;
    do {
      result = String.fromCharCode(65 + (c % 26)) + result;
      c = Math.floor(c / 26) - 1;
    } while (c >= 0);
    return result;
  }

  /** Extract short cell reference from entity ID like "tbl:ns/table:T/cell:R0C0" */
  private shortRef(entityId: string): string {
    if (!entityId) return '';
    const cellIdx = entityId.lastIndexOf('/cell:');
    if (cellIdx >= 0) return entityId.substring(cellIdx + 6);
    return entityId;
  }

  /** Guess cell type from value string */
  private guessType(value: any): string {
    if (value == null || String(value).trim() === '') return 'BLANK';
    if (!isNaN(Number(value))) return 'NUMERIC';
    if (value === 'true' || value === 'false') return 'BOOLEAN';
    return 'STRING';
  }

  convert(): void {
    if (!this.spreadsheetGraphJson) return;
    this.converting = true;
    this.errorMessage = '';
    this.executionResult = null;

    this.processService.convertExcelFormulas(this.spreadsheetGraphJson, this.targetLanguage)
      .subscribe({
        next: (result) => {
          this.conversionResult = result;
          this.editableCode = result.code;
          this.originalGeneratedCode = result.code;
          this.codeSource = 'llm';
          this.codeEdited = false;
          this.converting = false;
        },
        error: (err) => {
          this.errorMessage = err.error?.error || err.message || 'Conversion failed';
          this.converting = false;
        }
      });
  }

  execute(): void {
    if (!this.editableCode || !this.spreadsheetGraphJson) return;
    this.executing = true;
    this.errorMessage = '';
    this.executionResult = null;

    this.processService.executeExcelFormulas(
      this.spreadsheetGraphJson,
      undefined,
      this.targetLanguage,
      this.editableCode
    ).subscribe({
      next: (result) => {
        this.executionResult = result;
        this.executionError = false;
        this.executing = false;
        this.executed.emit(result);
      },
      error: (err) => {
        this.executionResult = { error: err.error?.error || err.message };
        this.executionError = true;
        this.executing = false;
      }
    });
  }

  saveCode(): void {
    this.codeSaved.emit({
      code: this.editableCode,
      language: this.targetLanguage
    });
  }

  onCodeEdit(): void {
    this.codeEdited = this.editableCode !== this.originalGeneratedCode;
  }

  resetCode(): void {
    this.editableCode = this.originalGeneratedCode;
    this.codeEdited = false;
  }

  copyCode(): void {
    navigator.clipboard.writeText(this.editableCode);
  }
}
