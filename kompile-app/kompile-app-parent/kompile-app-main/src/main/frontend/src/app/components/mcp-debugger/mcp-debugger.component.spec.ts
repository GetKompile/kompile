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
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError, Subject } from 'rxjs';
import { McpDebuggerComponent } from './mcp-debugger.component';
import { McpService } from '../../services/mcp.service';
import { McpToolInfo } from '../../models/api-models';

describe('McpDebuggerComponent', () => {
  let component: McpDebuggerComponent;
  let fixture: ComponentFixture<McpDebuggerComponent>;
  let mcpServiceSpy: jasmine.SpyObj<McpService>;
  let dialogSpy: jasmine.SpyObj<MatDialog>;

  const mockTools: McpToolInfo[] = [
    { name: 'rag-query', description: 'Query RAG', inputSchema: { properties: { query: { type: 'string' } }, required: ['query'] } },
    { name: 'file-read', description: 'Read file', inputSchema: { properties: { path: { type: 'string' }, count: { type: 'integer' } }, required: ['path'] } }
  ];

  const mockDialogRef = {
    afterClosed: () => of(false)
  } as unknown as MatDialogRef<any>;

  beforeEach(async () => {
    mcpServiceSpy = jasmine.createSpyObj('McpService', [
      'listTools', 'invokeTool', 'getActionLog', 'getActionStats',
      'undoAction', 'undoLastAction', 'clearActionLog'
    ]);
    mcpServiceSpy.listTools.and.returnValue(of(mockTools));
    mcpServiceSpy.invokeTool.and.returnValue(of({ toolName: 'rag-query', result: { answer: 'test' } }));
    mcpServiceSpy.getActionLog.and.returnValue(of({ toolName: '', result: { actions: [] } }));
    mcpServiceSpy.getActionStats.and.returnValue(of({ toolName: '', result: {} }));
    mcpServiceSpy.undoAction.and.returnValue(of({ toolName: '', result: { status: 'success' } }));
    mcpServiceSpy.undoLastAction.and.returnValue(of({ toolName: '', result: { status: 'success' } }));
    mcpServiceSpy.clearActionLog.and.returnValue(of({ toolName: '', result: { status: 'success', entriesCleared: 5 } }));

    dialogSpy = jasmine.createSpyObj('MatDialog', ['open']);
    dialogSpy.open.and.returnValue(mockDialogRef);

    await TestBed.configureTestingModule({
      declarations: [McpDebuggerComponent],
      imports: [NoopAnimationsModule],
      providers: [
        { provide: McpService, useValue: mcpServiceSpy },
        { provide: MatDialog, useValue: dialogSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(McpDebuggerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load tools on init', () => {
    expect(mcpServiceSpy.listTools).toHaveBeenCalled();
    expect(component.tools).toEqual(mockTools);
  });

  it('should set isLoading to false after tools loaded', () => {
    expect(component.isLoading).toBeFalse();
  });

  describe('loadTools - error', () => {
    it('should set errorMessage when listTools fails', fakeAsync(() => {
      mcpServiceSpy.listTools.and.returnValue(throwError(() => new Error('Network error')));
      component.loadTools();
      tick();
      expect(component.errorMessage).toContain('Failed to load tools');
    }));
  });

  describe('selectTool', () => {
    it('should set selectedTool', () => {
      component.selectTool(mockTools[0]);
      expect(component.selectedTool).toEqual(mockTools[0]);
    });

    it('should parse input schema into toolArguments', () => {
      component.selectTool(mockTools[0]);
      expect(component.toolArguments.length).toBe(1);
      expect(component.toolArguments[0].name).toBe('query');
    });

    it('should mark required arguments as required', () => {
      component.selectTool(mockTools[0]);
      expect(component.toolArguments[0].required).toBeTrue();
    });

    it('should clear currentResult', () => {
      component.currentResult = { timestamp: new Date(), toolName: 'x', arguments: {}, result: {}, durationMs: 0 };
      component.selectTool(mockTools[0]);
      expect(component.currentResult).toBeNull();
    });

    it('should clear messages', () => {
      component.errorMessage = 'some error';
      component.successMessage = 'some success';
      component.selectTool(mockTools[0]);
      expect(component.errorMessage).toBeNull();
      expect(component.successMessage).toBeNull();
    });
  });

  describe('parseInputSchema', () => {
    it('should return empty array for null schema', () => {
      expect(component.parseInputSchema(null)).toEqual([]);
    });

    it('should return empty array for schema without properties', () => {
      expect(component.parseInputSchema({})).toEqual([]);
    });

    it('should parse multiple properties', () => {
      const schema = {
        properties: {
          path: { type: 'string' },
          count: { type: 'integer' }
        },
        required: ['path']
      };
      const result = component.parseInputSchema(schema);
      expect(result.length).toBe(2);
    });
  });

  describe('getDefaultValue', () => {
    it('should return false for boolean', () => {
      expect(component.getDefaultValue('boolean')).toBe(false);
    });

    it('should return null for integer', () => {
      expect(component.getDefaultValue('integer')).toBeNull();
    });

    it('should return null for number', () => {
      expect(component.getDefaultValue('number')).toBeNull();
    });

    it('should return [] for array', () => {
      expect(component.getDefaultValue('array')).toEqual([]);
    });

    it('should return {} for object', () => {
      expect(component.getDefaultValue('object')).toEqual({});
    });

    it('should return "" for string', () => {
      expect(component.getDefaultValue('string')).toBe('');
    });
  });

  describe('getArgumentValue', () => {
    it('should return undefined for empty string value', () => {
      const arg = { name: 'x', type: 'string', value: '', required: false };
      expect(component.getArgumentValue(arg)).toBeUndefined();
    });

    it('should return undefined for null value', () => {
      const arg = { name: 'x', type: 'string', value: null, required: false };
      expect(component.getArgumentValue(arg)).toBeUndefined();
    });

    it('should parse integer string to number', () => {
      const arg = { name: 'x', type: 'integer', value: '42', required: false };
      expect(component.getArgumentValue(arg)).toBe(42);
    });

    it('should parse float string for number type', () => {
      const arg = { name: 'x', type: 'number', value: '3.14', required: false };
      expect(component.getArgumentValue(arg)).toBeCloseTo(3.14);
    });

    it('should handle boolean true', () => {
      const arg = { name: 'x', type: 'boolean', value: true, required: false };
      expect(component.getArgumentValue(arg)).toBeTrue();
    });

    it('should handle boolean "true" string', () => {
      const arg = { name: 'x', type: 'boolean', value: 'true', required: false };
      expect(component.getArgumentValue(arg)).toBeTrue();
    });

    it('should parse JSON string for array type', () => {
      const arg = { name: 'x', type: 'array', value: '[1,2,3]', required: false };
      expect(component.getArgumentValue(arg)).toEqual([1, 2, 3]);
    });

    it('should return string value as-is for invalid JSON in array type', () => {
      const arg = { name: 'x', type: 'array', value: 'not-json', required: false };
      expect(component.getArgumentValue(arg)).toBe('not-json');
    });
  });

  describe('buildArguments', () => {
    it('should build args map from toolArguments', () => {
      component.toolArguments = [
        { name: 'query', type: 'string', value: 'hello', required: true }
      ];
      const result = component.buildArguments();
      expect(result['query']).toBe('hello');
    });

    it('should omit undefined values', () => {
      component.toolArguments = [
        { name: 'empty', type: 'string', value: '', required: false }
      ];
      const result = component.buildArguments();
      expect(result['empty']).toBeUndefined();
    });
  });

  describe('executeTool', () => {
    beforeEach(() => {
      component.selectTool(mockTools[0]);
      component.toolArguments = [
        { name: 'query', type: 'string', value: 'test query', required: true }
      ];
    });

    it('should not call invokeTool if no tool selected', () => {
      component.selectedTool = null;
      component.executeTool();
      expect(mcpServiceSpy.invokeTool).not.toHaveBeenCalled();
    });

    it('should call invokeTool with correct request', fakeAsync(() => {
      component.executeTool();
      tick();
      expect(mcpServiceSpy.invokeTool).toHaveBeenCalled();
    }));

    it('should add result to executionResults', fakeAsync(() => {
      component.executeTool();
      tick();
      expect(component.executionResults.length).toBeGreaterThan(0);
    }));

    it('should set isExecuting to false after completion', fakeAsync(() => {
      component.executeTool();
      tick();
      expect(component.isExecuting).toBeFalse();
    }));

    it('should set successMessage on success', fakeAsync(() => {
      component.executeTool();
      tick();
      expect(component.successMessage).toContain('Tool executed successfully');
    }));

    it('should handle execution error', fakeAsync(() => {
      mcpServiceSpy.invokeTool.and.returnValue(throwError(() => new Error('Invoke failed')));
      component.executeTool();
      tick();
      expect(component.errorMessage).toContain('Execution failed');
      expect(component.isExecuting).toBeFalse();
    }));

    it('should cap executionResults at 50', fakeAsync(() => {
      // Prefill with 50 results
      component.executionResults = Array.from({ length: 50 }, (_, i) => ({
        timestamp: new Date(), toolName: `tool-${i}`, arguments: {}, result: {}, durationMs: 0
      }));
      component.executeTool();
      tick();
      expect(component.executionResults.length).toBe(50);
    }));
  });

  describe('toggleActionLog', () => {
    it('should show action log and load data', fakeAsync(() => {
      component.showActionLog = false;
      component.toggleActionLog();
      tick();
      expect(component.showActionLog).toBeTrue();
      expect(mcpServiceSpy.getActionLog).toHaveBeenCalled();
    }));

    it('should hide action log when already showing', () => {
      component.showActionLog = true;
      component.toggleActionLog();
      expect(component.showActionLog).toBeFalse();
    });
  });

  describe('loadActionLog', () => {
    it('should populate actionLog from response', fakeAsync(() => {
      const mockActions = [{ id: 1, toolName: 'test' }];
      mcpServiceSpy.getActionLog.and.returnValue(of({ toolName: '', result: { actions: mockActions } }));
      component.loadActionLog();
      tick();
      expect(component.actionLog).toEqual(mockActions as any);
    }));

    it('should set errorMessage on failure', fakeAsync(() => {
      mcpServiceSpy.getActionLog.and.returnValue(throwError(() => new Error('Log failed')));
      component.loadActionLog();
      tick();
      expect(component.errorMessage).toContain('Failed to load action log');
    }));
  });

  describe('undoAction', () => {
    it('should call undoAction service and show success', fakeAsync(() => {
      component.undoAction(42);
      tick();
      expect(mcpServiceSpy.undoAction).toHaveBeenCalledWith(42);
      expect(component.successMessage).toContain('42');
    }));

    it('should set errorMessage on failure', fakeAsync(() => {
      mcpServiceSpy.undoAction.and.returnValue(throwError(() => new Error('Undo failed')));
      component.undoAction(42);
      tick();
      expect(component.errorMessage).toContain('Undo failed');
    }));
  });

  describe('undoLastAction', () => {
    it('should call undoLastAction service and show success', fakeAsync(() => {
      component.undoLastAction();
      tick();
      expect(mcpServiceSpy.undoLastAction).toHaveBeenCalled();
      expect(component.successMessage).toContain('Last action');
    }));

    it('should handle error response', fakeAsync(() => {
      mcpServiceSpy.undoLastAction.and.returnValue(throwError(() => new Error('Last undo fail')));
      component.undoLastAction();
      tick();
      expect(component.errorMessage).toContain('Undo failed');
    }));
  });

  describe('clearMessages', () => {
    it('should clear both error and success messages', () => {
      component.errorMessage = 'error';
      component.successMessage = 'success';
      component.clearMessages();
      expect(component.errorMessage).toBeNull();
      expect(component.successMessage).toBeNull();
    });
  });

  describe('formatJson', () => {
    it('should format object as JSON string', () => {
      const result = component.formatJson({ key: 'value' });
      expect(result).toContain('"key"');
    });

    it('should return string representation on error', () => {
      const circular: any = {};
      circular.self = circular;
      const result = component.formatJson(circular);
      expect(typeof result).toBe('string');
    });
  });

  describe('formatTimestamp', () => {
    it('should return a localized date string', () => {
      const ts = '2025-01-15T10:30:00Z';
      const result = component.formatTimestamp(ts);
      expect(result).toBeTruthy();
      expect(typeof result).toBe('string');
    });
  });

  describe('getCategoryFromToolName', () => {
    it('should return RAG for rag tool names', () => {
      expect(component.getCategoryFromToolName('rag-query')).toBe('RAG');
    });

    it('should return Filesystem for file tool names', () => {
      expect(component.getCategoryFromToolName('file-read')).toBe('Filesystem');
    });

    it('should return Model for model tool names', () => {
      expect(component.getCategoryFromToolName('model-info')).toBe('Model');
    });

    it('should return Other for unknown names', () => {
      expect(component.getCategoryFromToolName('unknown-xyz')).toBe('Other');
    });
  });

  describe('getSourceIcon', () => {
    it('should return dns for mcp_server source', () => {
      const tool = { name: 'x', description: '', inputSchema: {}, source: 'mcp_server' } as any;
      expect(component.getSourceIcon(tool)).toBe('dns');
    });

    it('should return build for unknown source', () => {
      const tool = { name: 'x', description: '', inputSchema: {} } as any;
      expect(component.getSourceIcon(tool)).toBe('build');
    });
  });

  describe('getActionTypeIcon', () => {
    it('should return correct icons for action types', () => {
      expect(component.getActionTypeIcon('READ')).toBe('visibility');
      expect(component.getActionTypeIcon('WRITE')).toBe('edit');
      expect(component.getActionTypeIcon('DELETE')).toBe('delete');
      expect(component.getActionTypeIcon('EXECUTE')).toBe('play_arrow');
      expect(component.getActionTypeIcon('CONFIG')).toBe('settings');
      expect(component.getActionTypeIcon('UNKNOWN')).toBe('help');
    });
  });

  describe('getActionTypeColor', () => {
    it('should return green for READ', () => {
      expect(component.getActionTypeColor('READ')).toBe('#4caf50');
    });

    it('should return grey for unknown', () => {
      expect(component.getActionTypeColor('OTHER')).toBe('#757575');
    });
  });

  describe('clearHistory', () => {
    it('should clear executionResults and currentResult', () => {
      component.executionResults = [{ timestamp: new Date(), toolName: 'x', arguments: {}, result: {}, durationMs: 0 }];
      component.currentResult = component.executionResults[0];
      component.clearHistory();
      expect(component.executionResults).toEqual([]);
      expect(component.currentResult).toBeNull();
    });
  });

  describe('ngOnDestroy', () => {
    it('should complete destroy$ subject', () => {
      spyOn(component['destroy$'], 'next');
      spyOn(component['destroy$'], 'complete');
      component.ngOnDestroy();
      expect(component['destroy$'].next).toHaveBeenCalled();
      expect(component['destroy$'].complete).toHaveBeenCalled();
    });
  });
});
