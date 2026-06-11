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

import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { NO_ERRORS_SCHEMA, SimpleChange } from '@angular/core';
import { of, throwError } from 'rxjs';

import { ExcelArtifactComponent } from './excel-artifact.component';
import { ProcessEngineService, ExcelConversionResult } from '../../services/process-engine.service';

// ─── Test Data ──────────────────────────────────────────────────────────────

const SAMPLE_GRAPH_JSON = JSON.stringify({
  workbookName: 'Budget2025.xlsx',
  cells: {
    'A1': { cellReference: 'A1', cellType: 'NUMERIC', displayValue: '1000', formula: null },
    'A2': { cellReference: 'A2', cellType: 'NUMERIC', displayValue: '2000', formula: null },
    'A3': { cellReference: 'A3', cellType: 'FORMULA', displayValue: '3000', formula: 'SUM(A1:A2)' },
    'B1': { cellReference: 'B1', cellType: 'STRING', displayValue: 'Revenue', formula: null },
    'B2': { cellReference: 'B2', cellType: 'BLANK', displayValue: '', formula: null }
  },
  dependencies: [
    { formulaCell: 'A3', referencedCell: 'A1', dependencyType: 'CELL_REFERENCE' },
    { formulaCell: 'A3', referencedCell: 'A2', dependencyType: 'RANGE_REFERENCE' }
  ]
});

const SAMPLE_CONVERSION_RESULT: ExcelConversionResult = {
  code: 'function compute(A1, A2) { return A1 + A2; }',
  language: 'javascript',
  workbookName: 'Budget2025.xlsx',
  inputCells: ['A1', 'A2'],
  outputCells: ['A3'],
  formulaCount: 1,
  dependencyCount: 2
};

// ─────────────────────────────────────────────────────────────────────────────

describe('ExcelArtifactComponent', () => {
  let component: ExcelArtifactComponent;
  let fixture: ComponentFixture<ExcelArtifactComponent>;
  let mockProcessService: jasmine.SpyObj<ProcessEngineService>;

  beforeEach(async () => {
    mockProcessService = jasmine.createSpyObj('ProcessEngineService', [
      'convertExcelFormulas',
      'executeExcelFormulas'
    ]);
    mockProcessService.convertExcelFormulas.and.returnValue(of(SAMPLE_CONVERSION_RESULT));
    mockProcessService.executeExcelFormulas.and.returnValue(of({ A3: 3000 }));

    await TestBed.configureTestingModule({
      imports: [ExcelArtifactComponent, HttpClientTestingModule, NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA],
      providers: [
        { provide: ProcessEngineService, useValue: mockProcessService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ExcelArtifactComponent);
    component = fixture.componentInstance;
  });

  // ─── Graph Parsing ──────────────────────────────────────────────────────

  describe('parseGraph (via ngOnChanges)', () => {
    it('should parse input cells from spreadsheet graph JSON', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, SAMPLE_GRAPH_JSON, true)
      });

      // A1 (NUMERIC), A2 (NUMERIC), B1 (STRING) — B2 (BLANK) excluded
      expect(component.inputCells.length).toBe(3);
      expect(component.inputCells.map(c => c.ref)).toContain('A1');
      expect(component.inputCells.map(c => c.ref)).toContain('A2');
      expect(component.inputCells.map(c => c.ref)).toContain('B1');
    });

    it('should parse formula cells from spreadsheet graph JSON', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, SAMPLE_GRAPH_JSON, true)
      });

      expect(component.formulaCells.length).toBe(1);
      expect(component.formulaCells[0].ref).toBe('A3');
      expect(component.formulaCells[0].formula).toBe('SUM(A1:A2)');
      expect(component.formulaCells[0].value).toBe('3000');
    });

    it('should parse dependencies and strip _REFERENCE suffix', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, SAMPLE_GRAPH_JSON, true)
      });

      expect(component.dependencies.length).toBe(2);
      expect(component.dependencies[0]).toEqual({ from: 'A3', to: 'A1', type: 'CELL' });
      expect(component.dependencies[1]).toEqual({ from: 'A3', to: 'A2', type: 'RANGE' });
    });

    it('should extract workbook name', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, SAMPLE_GRAPH_JSON, true)
      });

      expect(component.workbookName).toBe('Budget2025.xlsx');
    });

    it('should handle invalid JSON gracefully', () => {
      component.spreadsheetGraphJson = 'not valid json {{{';
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, 'not valid json {{{', true)
      });

      expect(component.inputCells).toEqual([]);
      expect(component.formulaCells).toEqual([]);
      expect(component.dependencies).toEqual([]);
    });

    it('should handle empty cells object', () => {
      const emptyGraph = JSON.stringify({ workbookName: 'empty.xlsx', cells: {}, dependencies: [] });
      component.spreadsheetGraphJson = emptyGraph;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, emptyGraph, true)
      });

      expect(component.inputCells).toEqual([]);
      expect(component.formulaCells).toEqual([]);
    });
  });

  // ─── Graph Format Parsing (TableCellGraphBuilder output) ───────────────

  describe('parseGraph with Graph format (entities/relationships)', () => {
    const GRAPH_FORMAT_JSON = JSON.stringify({
      id: 'tbl:html:page.html/tbl:0',
      entities: [
        { id: 'tbl:ns/table:Revenue', title: 'Revenue', type: 'TABLE', isComposite: true },
        { id: 'tbl:ns/table:Revenue/cell:R0C0', title: 'Product', type: 'HEADER_CELL',
          metadata: { rowIndex: 0, colIndex: 0, cellValue: 'Product', isHeader: true } },
        { id: 'tbl:ns/table:Revenue/cell:R0C1', title: 'Amount', type: 'HEADER_CELL',
          metadata: { rowIndex: 0, colIndex: 1, cellValue: 'Amount', isHeader: true } },
        { id: 'tbl:ns/table:Revenue/cell:R1C0', title: 'Widget', type: 'CELL',
          metadata: { rowIndex: 1, colIndex: 0, cellValue: 'Widget', isHeader: false } },
        { id: 'tbl:ns/table:Revenue/cell:R1C1', title: '500', type: 'CELL',
          metadata: { rowIndex: 1, colIndex: 1, cellValue: '500', isHeader: false } }
      ],
      relationships: [
        { source: 'tbl:ns/table:Revenue', target: 'tbl:ns/table:Revenue/cell:R0C0', type: 'CONTAINS' },
        { source: 'tbl:ns/table:Revenue/cell:R0C0', target: 'tbl:ns/table:Revenue/cell:R1C0', type: 'HEADER_OF' }
      ]
    });

    it('should detect Graph format and parse entities as cells', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      // 4 non-TABLE entities: 2 HEADER_CELL + 2 CELL = 4 input cells (no formulas)
      expect(component.inputCells.length).toBe(4);
      expect(component.formulaCells.length).toBe(0);
    });

    it('should extract workbook name from TABLE entity', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      expect(component.workbookName).toBe('Revenue');
    });

    it('should convert row/col indices to Excel A1 notation', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      // R0C0 → Revenue!A1, R0C1 → Revenue!B1, R1C0 → Revenue!A2, R1C1 → Revenue!B2
      const refs = component.inputCells.map(c => c.ref);
      expect(refs).toContain('Revenue!A1');
      expect(refs).toContain('Revenue!B1');
      expect(refs).toContain('Revenue!A2');
      expect(refs).toContain('Revenue!B2');
    });

    it('should parse HEADER_OF relationships as dependencies', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      // Only HEADER_OF (not CONTAINS) should appear as a dependency
      const headerDeps = component.dependencies.filter(d => d.type === 'HEADER OF');
      expect(headerDeps.length).toBe(1);
      expect(headerDeps[0].from).toBe('R0C0');
      expect(headerDeps[0].to).toBe('R1C0');
    });

    it('should identify HEADER cells with correct type', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      const headers = component.inputCells.filter(c => c.type === 'HEADER');
      expect(headers.length).toBe(2);
    });

    it('should guess numeric type for numeric cell values', () => {
      component.spreadsheetGraphJson = GRAPH_FORMAT_JSON;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, GRAPH_FORMAT_JSON, true)
      });

      const numericCell = component.inputCells.find(c => c.ref === 'Revenue!B2');
      expect(numericCell?.type).toBe('NUMERIC');
    });

    it('should handle FORMULA_CELL entities', () => {
      const graphWithFormula = JSON.stringify({
        id: 'wb:test',
        entities: [
          { id: 'cell:A1', type: 'FORMULA_CELL',
            metadata: { rowIndex: 0, colIndex: 0, cellValue: '=SUM(B1:B5)', formula: '=SUM(B1:B5)' } }
        ],
        relationships: []
      });
      component.spreadsheetGraphJson = graphWithFormula;
      component.ngOnChanges({
        spreadsheetGraphJson: new SimpleChange(null, graphWithFormula, true)
      });

      expect(component.formulaCells.length).toBe(1);
      expect(component.formulaCells[0].formula).toBe('=SUM(B1:B5)');
    });
  });

  // ─── Existing Code Loading ─────────────────────────────────────────────

  describe('existingCode input', () => {
    it('should load existing code and set source to saved', () => {
      component.existingCode = 'function old() { return 42; }';
      component.ngOnChanges({
        existingCode: new SimpleChange(null, component.existingCode, true)
      });

      expect(component.editableCode).toBe('function old() { return 42; }');
      expect(component.originalGeneratedCode).toBe('function old() { return 42; }');
      expect(component.codeSource).toBe('saved');
      expect(component.codeEdited).toBeFalse();
    });
  });

  // ─── Convert ───────────────────────────────────────────────────────────

  describe('convert()', () => {
    it('should call processService.convertExcelFormulas and populate code', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.convert();

      expect(mockProcessService.convertExcelFormulas).toHaveBeenCalledWith(
        SAMPLE_GRAPH_JSON, 'javascript'
      );
      expect(component.editableCode).toBe(SAMPLE_CONVERSION_RESULT.code);
      expect(component.originalGeneratedCode).toBe(SAMPLE_CONVERSION_RESULT.code);
      expect(component.codeSource).toBe('llm');
      expect(component.converting).toBeFalse();
      expect(component.codeEdited).toBeFalse();
      expect(component.conversionResult).toEqual(SAMPLE_CONVERSION_RESULT);
    });

    it('should use the selected target language', () => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.targetLanguage = 'python';
      component.convert();

      expect(mockProcessService.convertExcelFormulas).toHaveBeenCalledWith(
        SAMPLE_GRAPH_JSON, 'python'
      );
    });

    it('should not call service when spreadsheetGraphJson is empty', () => {
      component.spreadsheetGraphJson = '';
      component.convert();

      expect(mockProcessService.convertExcelFormulas).not.toHaveBeenCalled();
    });

    it('should handle conversion error', () => {
      mockProcessService.convertExcelFormulas.and.returnValue(
        throwError(() => ({ error: { error: 'LLM timeout' }, message: 'Request failed' }))
      );
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.convert();

      expect(component.errorMessage).toBe('LLM timeout');
      expect(component.converting).toBeFalse();
    });

    it('should fall back to err.message when error.error is missing', () => {
      mockProcessService.convertExcelFormulas.and.returnValue(
        throwError(() => ({ message: 'Network error' }))
      );
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.convert();

      expect(component.errorMessage).toBe('Network error');
    });

    it('should clear previous execution result before converting', () => {
      component.executionResult = { A3: 3000 };
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.convert();

      // executionResult cleared at start of convert()
      expect(component.executionResult).toBeNull();
    });
  });

  // ─── Execute ───────────────────────────────────────────────────────────

  describe('execute()', () => {
    beforeEach(() => {
      component.spreadsheetGraphJson = SAMPLE_GRAPH_JSON;
      component.editableCode = 'function compute() { return 3000; }';
    });

    it('should call processService.executeExcelFormulas with correct params', () => {
      component.targetLanguage = 'javascript';
      component.execute();

      expect(mockProcessService.executeExcelFormulas).toHaveBeenCalledWith(
        SAMPLE_GRAPH_JSON, undefined, 'javascript', component.editableCode
      );
    });

    it('should populate executionResult on success', () => {
      component.execute();

      expect(component.executionResult).toEqual({ A3: 3000 });
      expect(component.executionError).toBeFalse();
      expect(component.executing).toBeFalse();
    });

    it('should emit executed event on success', () => {
      spyOn(component.executed, 'emit');
      component.execute();

      expect(component.executed.emit).toHaveBeenCalledWith({ A3: 3000 });
    });

    it('should handle execution error', () => {
      mockProcessService.executeExcelFormulas.and.returnValue(
        throwError(() => ({ error: { error: 'Script error' }, message: 'Execution failed' }))
      );
      component.execute();

      expect(component.executionResult).toEqual({ error: 'Script error' });
      expect(component.executionError).toBeTrue();
      expect(component.executing).toBeFalse();
    });

    it('should not execute when editableCode is empty', () => {
      component.editableCode = '';
      component.execute();

      expect(mockProcessService.executeExcelFormulas).not.toHaveBeenCalled();
    });

    it('should not execute when spreadsheetGraphJson is empty', () => {
      component.spreadsheetGraphJson = '';
      component.execute();

      expect(mockProcessService.executeExcelFormulas).not.toHaveBeenCalled();
    });
  });

  // ─── Code Editing ─────────────────────────────────────────────────────

  describe('code editing', () => {
    it('should mark code as edited when text changes', () => {
      component.originalGeneratedCode = 'original code';
      component.editableCode = 'modified code';
      component.onCodeEdit();

      expect(component.codeEdited).toBeTrue();
    });

    it('should mark code as NOT edited when text matches original', () => {
      component.originalGeneratedCode = 'same code';
      component.editableCode = 'same code';
      component.onCodeEdit();

      expect(component.codeEdited).toBeFalse();
    });

    it('should reset code to original generated version', () => {
      component.originalGeneratedCode = 'original code';
      component.editableCode = 'modified code';
      component.codeEdited = true;

      component.resetCode();

      expect(component.editableCode).toBe('original code');
      expect(component.codeEdited).toBeFalse();
    });
  });

  // ─── Save & Copy ──────────────────────────────────────────────────────

  describe('saveCode()', () => {
    it('should emit codeSaved with current code and language', () => {
      spyOn(component.codeSaved, 'emit');
      component.editableCode = 'function x() {}';
      component.targetLanguage = 'python';

      component.saveCode();

      expect(component.codeSaved.emit).toHaveBeenCalledWith({
        code: 'function x() {}',
        language: 'python'
      });
    });
  });

  describe('copyCode()', () => {
    it('should call navigator.clipboard.writeText', () => {
      const clipboardSpy = spyOn(navigator.clipboard, 'writeText').and.returnValue(Promise.resolve());
      component.editableCode = 'copied code';

      component.copyCode();

      expect(clipboardSpy).toHaveBeenCalledWith('copied code');
    });
  });
});
