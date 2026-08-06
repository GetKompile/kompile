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
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { HttpClientTestingModule } from '@angular/common/http/testing';

import { CrawlStepMonitorComponent } from './crawl-step-monitor.component';

describe('CrawlStepMonitorComponent', () => {
  let component: CrawlStepMonitorComponent;
  let fixture: ComponentFixture<CrawlStepMonitorComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CrawlStepMonitorComponent, NoopAnimationsModule, HttpClientTestingModule],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(CrawlStepMonitorComponent);
    component = fixture.componentInstance;
  });

  it('creates and renders an empty state with no steps', () => {
    fixture.detectChanges();
    expect(component).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.csm-empty')).toBeTruthy();
  });

  it('renders one row per step', () => {
    component.steps = [
      { stepId: 'GRAPH_EXTRACTION', displayName: 'Graph Extraction', stepType: 'GRAPH',
        status: 'RUNNING', progressPercent: 50, totalItems: 10, completedItems: 5, failedItems: 0 } as any,
      { stepId: 'EMBEDDING', displayName: 'Embedding', stepType: 'EMBEDDING',
        status: 'PENDING', progressPercent: 0, totalItems: 0, completedItems: 0, failedItems: 0 } as any
    ];
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.csm-item').length).toBe(2);
  });

  it('expands a step when its header is clicked', () => {
    component.steps = [{ stepId: 'GRAPH_EXTRACTION', displayName: 'Graph Extraction', stepType: 'GRAPH', status: 'RUNNING' } as any];
    fixture.detectChanges();
    expect(component.isStepExpanded('GRAPH_EXTRACTION')).toBeFalse();
    fixture.nativeElement.querySelector('.csm-header').click();
    fixture.detectChanges();
    expect(component.isStepExpanded('GRAPH_EXTRACTION')).toBeTrue();
  });

  it('emits runStepRequested with the job + step id', () => {
    spyOn(component.runStepRequested, 'emit');
    component.jobId = 'job-1';
    component.runStep('VECTOR_INDEXING');
    expect(component.runStepRequested.emit).toHaveBeenCalledWith({ jobId: 'job-1', stepId: 'VECTOR_INDEXING' });
  });

  describe('isStepRunNowEligible', () => {
    it('returns true for all terminal states that allow re-run', () => {
      ['COMPLETED', 'FAILED', 'ARCHIVED', 'DEFERRED'].forEach(s =>
        expect(component.isStepRunNowEligible(s)).withContext(s).toBeTrue()
      );
    });
    it('returns false for active or excluded states', () => {
      ['RUNNING', 'PENDING', 'SKIPPED', undefined, ''].forEach(s =>
        expect(component.isStepRunNowEligible(s)).withContext(String(s)).toBeFalse()
      );
    });
  });

  describe('getRunStepLabel', () => {
    it('returns Retry for FAILED', () => expect(component.getRunStepLabel('FAILED')).toBe('Retry'));
    it('returns Re-run for COMPLETED', () => expect(component.getRunStepLabel('COMPLETED')).toBe('Re-run'));
    it('returns Run archived step for ARCHIVED', () => expect(component.getRunStepLabel('ARCHIVED')).toBe('Run archived step'));
    it('returns Run now for DEFERRED', () => expect(component.getRunStepLabel('DEFERRED')).toBe('Run now'));
  });

  describe('runningStepIds input → isStepInProgress', () => {
    it('reports in-progress when parent passes a matching stepId', () => {
      component.runningStepIds = new Set(['GRAPH_EXTRACTION']);
      expect(component.isStepInProgress('GRAPH_EXTRACTION')).toBeTrue();
      expect(component.isStepInProgress('VECTOR_INDEXING')).toBeFalse();
    });
    it('reports not in-progress when the set is empty', () => {
      component.runningStepIds = new Set();
      expect(component.isStepInProgress('GRAPH_EXTRACTION')).toBeFalse();
    });
  });

  describe('worker-tagged (distributed) per-step scoping', () => {
    beforeEach(() => {
      component.job = {
        recentTuningDecisions: [
          { timestamp: '2026-06-21T10:00:00Z', stage: 'w0:GRAPH_EXTRACTION', direction: 'UP', oldValue: 1, newValue: 2, reason: 'stable_throughput' },
          { timestamp: '2026-06-21T10:00:01Z', stage: 'w0:GRAPH_PARALLELISM', direction: 'DOWN', oldValue: 4, newValue: 1, reason: 'heap_critical' },
          { timestamp: '2026-06-21T10:00:02Z', stage: 'w1:GRAPH_EXTRACTION', direction: 'UP', oldValue: 1, newValue: 3, reason: 'stable_throughput' }
        ],
        recentEvents: [
          { timestamp: '2026-06-21T10:00:00Z', phase: 'w0:GRAPH_EXTRACTION', level: 'INFO', message: 'w0 ev' },
          { timestamp: '2026-06-21T10:00:01Z', phase: 'w1:GRAPH_EXTRACTION', level: 'INFO', message: 'w1 ev' }
        ]
      } as any;
    });

    it('scopes tuning decisions to the owning worker (incl. char/parallelism controllers)', () => {
      expect(component.getStepTuningDecisions({ stepId: 'w0:GRAPH_EXTRACTION' } as any).length).toBe(2);
      expect(component.getStepTuningDecisions({ stepId: 'w1:GRAPH_EXTRACTION' } as any).length).toBe(1);
    });

    it('scopes activity events to the owning worker', () => {
      const w0 = component.getStepEvents({ stepId: 'w0:GRAPH_EXTRACTION' } as any);
      expect(w0.length).toBe(1);
      expect(w0[0].message).toBe('w0 ev');
    });
  });

  describe('decomposed LLM call scoping', () => {
    beforeEach(() => {
      component.job = {
        recentLlmCalls: [
          { timestamp: 't0', backendId: 'local', taskType: 'llm', phase: 'GRAPH_EXTRACTION',
            passId: 'propositions', passInvocation: 1, chunkId: 'chunk-direct', graphRevision: 'graph-1',
            graphEntities: 3, graphRelationships: 1, latencyMs: 20, promptChars: 100, responseChars: 20, success: true },
          { timestamp: 't1', backendId: 'local', taskType: 'llm', phase: 'ENTITY_PARTITIONS',
            passId: 'mentions', passInvocation: 2, partitionId: 'partition-acme', chunkId: 'chunk-7',
            corpusSnapshotId: 'corpus-v1:abc', graphRevision: 'graph-12', graphEntities: 14,
            graphRelationships: 9, latencyMs: 40, promptChars: 200, responseChars: 30, success: true },
          { timestamp: 't2', backendId: 'local', taskType: 'llm', phase: 'w1:ENTITY_PARTITIONS',
            passId: 'relations', passInvocation: 4, partitionId: 'partition-beta', chunkId: 'chunk-8',
            latencyMs: 50, promptChars: 220, responseChars: 35, success: false },
          { timestamp: 'legacy', backendId: 'legacy', taskType: 'llm', latencyMs: 10,
            promptChars: 80, responseChars: 10, success: true }
        ]
      } as any;
    });

    it('attaches scoped calls only to their owning phase and keeps legacy fallback on graph extraction', () => {
      expect(component.getStepLlmCalls({ stepId: 'GRAPH_EXTRACTION', stepType: 'GRAPH' } as any).length).toBe(2);
      expect(component.getStepLlmCalls({ stepId: 'ENTITY_PARTITIONS', stepType: 'GRAPH' } as any).length).toBe(1);
      expect(component.getStepLlmCalls({ stepId: 'w1:ENTITY_PARTITIONS', stepType: 'GRAPH' } as any).length).toBe(1);
      expect(component.getStepLlmCalls({ stepId: 'ENRICHMENT', stepType: 'GRAPH' } as any).length).toBe(0);
    });

    it('scopes the first live transcript before its completed call record arrives', () => {
      component.job = { recentLlmCalls: [] } as any;
      expect(component.getStepTranscriptFilter({
        stepId: 'ENTITY_PARTITIONS', stepType: 'GRAPH', status: 'RUNNING',
        currentItem: 'propositions #1 | partition-acme | chunk-7'
      } as any)).toBe('[llm/ENTITY_PARTITIONS/');
    });

    it('renders the canonical pass breakdown and corpus/graph scope for small-model work', () => {
      const step = { stepId: 'ENTITY_PARTITIONS', stepType: 'GRAPH', status: 'RUNNING' } as any;
      const passes = component.getExtractionPassSummaries(step);
      const mentions = passes.find(pass => pass.id === 'mentions')!;
      const divisions = component.getExtractionDivisionSummary(step)!;

      expect(passes.map(pass => pass.id)).toEqual(['propositions', 'mentions', 'epistemic', 'relations', 'claims']);
      expect(mentions.count).toBe(1);
      expect(mentions.state).toBe('done');
      expect(divisions.corpusSnapshots).toEqual(['corpus-v1:abc']);
      expect(divisions.partitions).toEqual(['partition-acme']);
      expect(divisions.chunks).toEqual(['chunk-7']);
      expect(divisions.graphEntities).toBe(14);
      expect(divisions.graphRelationships).toBe(9);
      expect(component.getStepTranscriptFilter(step)).toBe('[llm/ENTITY_PARTITIONS/');
    });
  });

  it('single-node (untagged) tuning still matches by base stage', () => {
    component.job = {
      recentTuningDecisions: [
        { timestamp: 't', stage: 'GRAPH_PARALLELISM', direction: 'DOWN', oldValue: 4, newValue: 1, reason: 'heap_critical' }
      ]
    } as any;
    expect(component.getStepTuningDecisions({ stepId: 'GRAPH_EXTRACTION' } as any).length).toBe(1);
  });
});
