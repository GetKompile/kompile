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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { McpHubComponent } from './mcp-hub.component';

describe('McpHubComponent', () => {
  let component: McpHubComponent;
  let fixture: ComponentFixture<McpHubComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [McpHubComponent],
      imports: [NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(McpHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize activeSubTab to "tools"', () => {
    expect(component.activeSubTab).toBe('tools');
  });

  it('should allow switching activeSubTab to serverBuilder', () => {
    component.activeSubTab = 'serverBuilder';
    expect(component.activeSubTab).toBe('serverBuilder');
  });

  it('should allow switching activeSubTab to externalServers', () => {
    component.activeSubTab = 'externalServers';
    expect(component.activeSubTab).toBe('externalServers');
  });

  it('should allow switching activeSubTab to restBridge', () => {
    component.activeSubTab = 'restBridge';
    expect(component.activeSubTab).toBe('restBridge');
  });

  it('should allow switching activeSubTab to debugger', () => {
    component.activeSubTab = 'debugger';
    expect(component.activeSubTab).toBe('debugger');
  });

  it('should allow switching activeSubTab to toolManager', () => {
    component.activeSubTab = 'toolManager';
    expect(component.activeSubTab).toBe('toolManager');
  });

  it('should allow switching activeSubTab to promptTemplates', () => {
    component.activeSubTab = 'promptTemplates';
    expect(component.activeSubTab).toBe('promptTemplates');
  });

});
