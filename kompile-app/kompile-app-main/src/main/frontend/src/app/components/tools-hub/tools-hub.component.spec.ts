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
import { ToolsHubComponent } from './tools-hub.component';

describe('ToolsHubComponent', () => {
  let component: ToolsHubComponent;
  let fixture: ComponentFixture<ToolsHubComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ToolsHubComponent],
      imports: [NoopAnimationsModule],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(ToolsHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should initialize activeSubTab to "mcp"', () => {
    expect(component.activeSubTab).toBe('mcp');
  });

  it('should allow switching activeSubTab to orchestrator', () => {
    component.activeSubTab = 'orchestrator';
    expect(component.activeSubTab).toBe('orchestrator');
  });

  it('should allow switching activeSubTab to chunkManager', () => {
    component.activeSubTab = 'chunkManager';
    expect(component.activeSubTab).toBe('chunkManager');
  });

  it('should allow switching activeSubTab to knowledgeGraph', () => {
    component.activeSubTab = 'knowledgeGraph';
    expect(component.activeSubTab).toBe('knowledgeGraph');
  });

  it('should allow switching activeSubTab to backup', () => {
    component.activeSubTab = 'backup';
    expect(component.activeSubTab).toBe('backup');
  });

  it('should allow switching activeSubTab to prompts', () => {
    component.activeSubTab = 'prompts';
    expect(component.activeSubTab).toBe('prompts');
  });

  it('should allow switching activeSubTab to pipelines', () => {
    component.activeSubTab = 'pipelines';
    expect(component.activeSubTab).toBe('pipelines');
  });
});
