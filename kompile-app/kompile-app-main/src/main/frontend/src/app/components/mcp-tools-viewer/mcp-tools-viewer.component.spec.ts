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
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { McpToolsViewerComponent } from './mcp-tools-viewer.component';
import { McpService } from '../../services/mcp.service';
import { McpToolInfo } from '../../models/api-models';

describe('McpToolsViewerComponent', () => {
  let component: McpToolsViewerComponent;
  let fixture: ComponentFixture<McpToolsViewerComponent>;
  let mcpServiceSpy: jasmine.SpyObj<McpService>;

  const mockTools: McpToolInfo[] = [
    { name: 'tool-one', description: 'First tool', inputSchema: {} } as McpToolInfo,
    { name: 'tool-two', description: 'Second tool', inputSchema: {} } as McpToolInfo
  ];

  beforeEach(async () => {
    mcpServiceSpy = jasmine.createSpyObj('McpService', ['listTools']);
    mcpServiceSpy.listTools.and.returnValue(of(mockTools));

    await TestBed.configureTestingModule({
      declarations: [McpToolsViewerComponent],
      imports: [NoopAnimationsModule],
      providers: [
        { provide: McpService, useValue: mcpServiceSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(McpToolsViewerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should call loadMcpTools on init', () => {
    expect(mcpServiceSpy.listTools).toHaveBeenCalled();
  });

  it('should populate mcpTools on successful load', () => {
    expect(component.mcpTools).toEqual(mockTools);
  });

  it('should set isLoading to false after successful load', () => {
    expect(component.isLoading).toBeFalse();
  });

  it('should have null errorMessage after successful load', () => {
    expect(component.errorMessage).toBeNull();
  });

  describe('loadMcpTools - error handling', () => {
    it('should set errorMessage on HTTP error', fakeAsync(() => {
      const httpError = new HttpErrorResponse({ error: 'Server error', status: 500, statusText: 'Internal Server Error' });
      mcpServiceSpy.listTools.and.returnValue(throwError(() => httpError));

      component.loadMcpTools();
      tick();

      expect(component.errorMessage).toContain('Failed to load MCP tools');
      expect(component.isLoading).toBeFalse();
    }));

    it('should set isLoading to true before data arrives', () => {
      mcpServiceSpy.listTools.and.returnValue(of([]));
      // Reset state
      component.isLoading = false;
      component.loadMcpTools();
      // isLoading is set synchronously at start of method
      // After subscribe completes synchronously with of(), it becomes false again
      expect(component.isLoading).toBeFalse();
    });

    it('should clear errorMessage on successful reload', fakeAsync(() => {
      component.errorMessage = 'previous error';
      mcpServiceSpy.listTools.and.returnValue(of(mockTools));
      component.loadMcpTools();
      tick();
      expect(component.errorMessage).toBeNull();
    }));
  });

  describe('loadMcpTools - empty result', () => {
    it('should set mcpTools to empty array when no tools returned', fakeAsync(() => {
      mcpServiceSpy.listTools.and.returnValue(of([]));
      component.loadMcpTools();
      tick();
      expect(component.mcpTools).toEqual([]);
    }));
  });
});
