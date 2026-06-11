/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { ComponentFixture, TestBed, fakeAsync, tick, discardPeriodicTasks } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { KnowledgeGraphBuilderComponent } from './knowledge-graph-builder.component';
import { KnowledgeGraphBuilderService } from '../../services/knowledge-graph-builder.service';
import {
  GraphBuilderInfo,
  ExtractionJob,
  TripleProposal,
  ExtractionLog,
  DEFAULT_ENTITY_TYPES,
  JOB_STATUS_COLORS,
  PROPOSAL_STATUS_COLORS,
  BUILDER_TYPE_ICONS
} from '../../models/graph-builder-models';

// ─────────────────────────────────────────────────────────────────────────────
// Mock Data
// ─────────────────────────────────────────────────────────────────────────────

const mockBuilders: GraphBuilderInfo[] = [
  {
    id: 'llm-builder',
    displayName: 'LLM Builder',
    description: 'Uses LLM for extraction',
    type: 'LLM',
    supportsExtractionLog: true,
    supportsConcurrentIndexing: false
  },
  {
    id: 'rule-builder',
    displayName: 'Rule Builder',
    description: 'Rule-based extraction',
    type: 'PATTERN',
    supportsExtractionLog: false,
    supportsConcurrentIndexing: true
  }
];

const mockJobs: ExtractionJob[] = [
  {
    jobId: 'job-1',
    factSheetId: 42,
    builderType: 'llm-builder',
    status: 'COMPLETED',
    totalChunks: 10,
    processedChunks: 10,
    proposalsCreated: 25,
    startedAt: '2025-01-01T00:00:00Z',
    completedAt: '2025-01-01T00:05:00Z'
  },
  {
    jobId: 'job-2',
    factSheetId: 42,
    builderType: 'rule-builder',
    status: 'RUNNING',
    totalChunks: 5,
    processedChunks: 2,
    proposalsCreated: 3,
    startedAt: '2025-01-02T00:00:00Z'
  }
];

const mockProposals: TripleProposal[] = [
  {
    proposalId: 'p1',
    jobId: 'job-1',
    factSheetId: 42,
    subjectName: 'AI',
    subjectType: 'CONCEPT',
    predicateName: 'RELATED_TO',
    objectName: 'ML',
    objectType: 'CONCEPT',
    confidence: 0.9,
    status: 'PENDING'
  },
  {
    proposalId: 'p2',
    jobId: 'job-1',
    factSheetId: 42,
    subjectName: 'Neural Network',
    subjectType: 'CONCEPT',
    predicateName: 'IS_A',
    objectName: 'AI Model',
    objectType: 'CONCEPT',
    confidence: 0.85,
    status: 'ACCEPTED'
  }
];

const mockLogs: ExtractionLog[] = [
  {
    id: 1,
    chunkId: 'chunk-1',
    promptText: 'Extract entities from: ...',
    responseText: 'Entities: AI, ML',
    parsedEntitiesJson: '[{"id":"e1","title":"AI","label":"CONCEPT"}]',
    parsedRelationshipsJson: '[{"source":"AI","target":"ML","type":"RELATED_TO"}]',
    success: true,
    createdAt: '2025-01-01T00:01:00Z'
  },
  {
    id: 2,
    chunkId: 'chunk-2',
    promptText: 'Extract entities from: ...',
    success: false,
    errorMessage: 'Rate limit exceeded'
  }
];

function mockPage<T>(content: T[], total: number = content.length) {
  return {
    content,
    totalElements: total,
    totalPages: Math.ceil(total / 20),
    size: 20,
    number: 0,
    first: true,
    last: total <= 20,
    empty: content.length === 0
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Test Suite
// ─────────────────────────────────────────────────────────────────────────────

describe('KnowledgeGraphBuilderComponent', () => {
  let component: KnowledgeGraphBuilderComponent;
  let fixture: ComponentFixture<KnowledgeGraphBuilderComponent>;
  let serviceSpy: jasmine.SpyObj<KnowledgeGraphBuilderService>;

  beforeEach(async () => {
    serviceSpy = jasmine.createSpyObj<KnowledgeGraphBuilderService>('KnowledgeGraphBuilderService', [
      'getBuilders',
      'getJobs',
      'getJob',
      'startJob',
      'cancelJob',
      'getProposals',
      'acceptProposal',
      'rejectProposal',
      'bulkAcceptProposals',
      'bulkRejectProposals',
      'acceptAllPending',
      'getJobLogs',
      'createManualProposal'
    ]);

    // Default return values used before detectChanges
    serviceSpy.getBuilders.and.returnValue(of(mockBuilders));
    serviceSpy.getJobs.and.returnValue(of(mockPage(mockJobs)));
    serviceSpy.getProposals.and.returnValue(of(mockPage(mockProposals)));
    serviceSpy.getJobLogs.and.returnValue(of(mockPage(mockLogs)));

    await TestBed.configureTestingModule({
      imports: [KnowledgeGraphBuilderComponent, NoopAnimationsModule],
      providers: [
        { provide: KnowledgeGraphBuilderService, useValue: serviceSpy }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(KnowledgeGraphBuilderComponent);
    component = fixture.componentInstance;
    component.factSheetId = 42;
    // Disable auto-refresh so ngOnInit does not start an interval that leaks
    component.autoRefresh = false;
  });

  afterEach(() => {
    fixture.destroy();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Component Creation
  // ─────────────────────────────────────────────────────────────────────────

  describe('component creation', () => {
    it('should create the component', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should initialise with default builderConfig containing DEFAULT_ENTITY_TYPES', () => {
      expect(component.builderConfig.entityTypes).toEqual([...DEFAULT_ENTITY_TYPES]);
    });

    it('should initialise viewMode as overview', () => {
      expect(component.viewMode).toBe('overview');
    });

    it('should initialise selectedProposals as an empty Set', () => {
      expect(component.selectedProposals.size).toBe(0);
    });

    it('should initialise proposalStatusFilter as ALL', () => {
      expect(component.proposalStatusFilter).toBe('ALL');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // ngOnInit: loadBuilders & loadJobs
  // ─────────────────────────────────────────────────────────────────────────

  describe('ngOnInit', () => {
    it('should call getBuilders on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(serviceSpy.getBuilders).toHaveBeenCalled();
    }));

    it('should call getJobs with factSheetId on init', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(serviceSpy.getJobs).toHaveBeenCalledWith(42);
    }));

    it('should not start auto-refresh interval when autoRefresh is false', fakeAsync(() => {
      fixture.detectChanges();
      tick(10000);
      // getJobs called only once during ngOnInit — not again by interval
      expect(serviceSpy.getJobs).toHaveBeenCalledTimes(1);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // loadBuilders
  // ─────────────────────────────────────────────────────────────────────────

  describe('loadBuilders()', () => {
    it('should populate builders array', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(component.builders).toEqual(mockBuilders);
    }));

    it('should auto-select the first builder when none is selected', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(component.selectedBuilderId).toBe('llm-builder');
      expect(component.selectedBuilder).toEqual(mockBuilders[0]);
    }));

    it('should not overwrite an existing selection when builders reload', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      // Manually select the second builder
      component.selectBuilder('rule-builder');
      // Reload builders
      component.loadBuilders();
      tick();
      expect(component.selectedBuilderId).toBe('rule-builder');
    }));

    it('should set error when getBuilders fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.getBuilders.and.returnValue(throwError(() => new Error('Network error')));
      fixture.detectChanges();
      tick();
      expect(component.error).toBe('Failed to load builders');
    }));

    it('should log error to console when getBuilders fails', fakeAsync(() => {
      const consoleErr = spyOn(console, 'error');
      serviceSpy.getBuilders.and.returnValue(throwError(() => new Error('Network error')));
      fixture.detectChanges();
      tick();
      expect(consoleErr).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // loadJobs
  // ─────────────────────────────────────────────────────────────────────────

  describe('loadJobs()', () => {
    it('should populate jobs array from page content', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      expect(component.jobs).toEqual(mockJobs);
    }));

    it('should not call getJobs when factSheetId is falsy', fakeAsync(() => {
      component.factSheetId = 0 as any;
      serviceSpy.getJobs.calls.reset();
      component.loadJobs();
      tick();
      expect(serviceSpy.getJobs).not.toHaveBeenCalled();
    }));

    it('should log error when getJobs fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.getJobs.and.returnValue(throwError(() => new Error('Server error')));
      fixture.detectChanges();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // selectBuilder
  // ─────────────────────────────────────────────────────────────────────────

  describe('selectBuilder()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should set selectedBuilderId', () => {
      component.selectBuilder('rule-builder');
      expect(component.selectedBuilderId).toBe('rule-builder');
    });

    it('should set selectedBuilder to the matching builder object', () => {
      component.selectBuilder('rule-builder');
      expect(component.selectedBuilder).toEqual(mockBuilders[1]);
    });

    it('should set selectedBuilder to null when id does not match any builder', () => {
      component.selectBuilder('nonexistent');
      expect(component.selectedBuilder).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // startJob
  // ─────────────────────────────────────────────────────────────────────────

  describe('startJob()', () => {
    const newJob: ExtractionJob = {
      jobId: 'job-new',
      factSheetId: 42,
      builderType: 'llm-builder',
      status: 'RUNNING'
    };

    beforeEach(fakeAsync(() => {
      serviceSpy.startJob.and.returnValue(of(newJob));
      fixture.detectChanges();
      tick();
    }));

    it('should call startJob with correct StartJobRequest', () => {
      component.startJob();
      expect(serviceSpy.startJob).toHaveBeenCalledWith({
        factSheetId: 42,
        builderType: 'llm-builder',
        config: component.builderConfig
      });
    });

    it('should prepend the new job to the jobs array', () => {
      component.startJob();
      expect(component.jobs[0]).toEqual(newJob);
    });

    it('should set selectedJob to the newly started job', () => {
      component.startJob();
      expect(component.selectedJob).toEqual(newJob);
    });

    it('should switch viewMode to jobs after starting', () => {
      component.startJob();
      expect(component.viewMode).toBe('jobs');
    });

    it('should set loading to false after success', () => {
      component.startJob();
      expect(component.loading).toBe(false);
    });

    it('should not call startJob when selectedBuilderId is null', () => {
      component.selectedBuilderId = null;
      serviceSpy.startJob.calls.reset();
      component.startJob();
      expect(serviceSpy.startJob).not.toHaveBeenCalled();
    });

    it('should set error and clear loading on failure', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.startJob.and.returnValue(throwError(() => new Error('Failed')));
      component.startJob();
      tick();
      expect(component.error).toBe('Failed to start extraction job');
      expect(component.loading).toBe(false);
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // cancelJob
  // ─────────────────────────────────────────────────────────────────────────

  describe('cancelJob()', () => {
    beforeEach(fakeAsync(() => {
      serviceSpy.cancelJob.and.returnValue(of(undefined));
      fixture.detectChanges();
      tick();
    }));

    it('should call cancelJob with the job id', () => {
      const job: ExtractionJob = { ...mockJobs[1] };
      component.cancelJob(job);
      expect(serviceSpy.cancelJob).toHaveBeenCalledWith('job-2');
    });

    it('should update job status to CANCELLED on success', () => {
      const job: ExtractionJob = { ...mockJobs[1] };
      component.cancelJob(job);
      expect(job.status).toBe('CANCELLED');
    });

    it('should log error when cancelJob fails', fakeAsync(() => {
      const consoleErr = spyOn(console, 'error');
      serviceSpy.cancelJob.and.returnValue(throwError(() => new Error('Forbidden')));
      const job: ExtractionJob = { ...mockJobs[1] };
      component.cancelJob(job);
      tick();
      expect(consoleErr).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // selectJob
  // ─────────────────────────────────────────────────────────────────────────

  describe('selectJob()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      serviceSpy.getProposals.calls.reset();
      serviceSpy.getJobLogs.calls.reset();
    }));

    it('should set selectedJob', () => {
      component.selectJob(mockJobs[0]);
      expect(component.selectedJob).toEqual(mockJobs[0]);
    });

    it('should call loadProposals after selecting a job', () => {
      component.selectJob(mockJobs[0]);
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    });

    it('should call loadExtractionLogs when builderType includes llm', () => {
      const llmJob: ExtractionJob = { ...mockJobs[0], builderType: 'llm-builder' };
      component.selectJob(llmJob);
      expect(serviceSpy.getJobLogs).toHaveBeenCalled();
    });

    it('should not call loadExtractionLogs for non-LLM builder types', () => {
      const ruleJob: ExtractionJob = { ...mockJobs[1], builderType: 'rule-builder' };
      component.selectJob(ruleJob);
      expect(serviceSpy.getJobLogs).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // loadProposals
  // ─────────────────────────────────────────────────────────────────────────

  describe('loadProposals()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      serviceSpy.getProposals.calls.reset();
    }));

    it('should populate proposals array', fakeAsync(() => {
      component.loadProposals();
      tick();
      expect(component.proposals).toEqual(mockProposals);
    }));

    it('should update proposalsTotalElements', fakeAsync(() => {
      component.loadProposals();
      tick();
      expect(component.proposalsTotalElements).toBe(mockProposals.length);
    }));

    it('should pass status filter when not ALL', fakeAsync(() => {
      component.proposalStatusFilter = 'PENDING';
      component.loadProposals();
      tick();
      const callArgs = serviceSpy.getProposals.calls.mostRecent().args[0];
      expect(callArgs.status).toBe('PENDING');
    }));

    it('should not pass status when filter is ALL', fakeAsync(() => {
      component.proposalStatusFilter = 'ALL';
      component.loadProposals();
      tick();
      const callArgs = serviceSpy.getProposals.calls.mostRecent().args[0];
      expect(callArgs.status).toBeUndefined();
    }));

    it('should pass jobId when a job is selected', fakeAsync(() => {
      component.selectedJob = mockJobs[0];
      component.loadProposals();
      tick();
      const callArgs = serviceSpy.getProposals.calls.mostRecent().args[0];
      expect(callArgs.jobId).toBe('job-1');
    }));

    it('should log error when getProposals fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.getProposals.and.returnValue(throwError(() => new Error('Server error')));
      component.loadProposals();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // acceptProposal / rejectProposal
  // ─────────────────────────────────────────────────────────────────────────

  describe('acceptProposal()', () => {
    const acceptResult = { subjectNodeId: 'n1', objectNodeId: 'n2', edgeId: 'e1' };

    beforeEach(fakeAsync(() => {
      serviceSpy.acceptProposal.and.returnValue(of(acceptResult));
      fixture.detectChanges();
      tick();
    }));

    it('should call acceptProposal with the proposal id', () => {
      const proposal = { ...mockProposals[0] };
      component.acceptProposal(proposal);
      expect(serviceSpy.acceptProposal).toHaveBeenCalledWith('p1');
    });

    it('should update proposal status to ACCEPTED', () => {
      const proposal = { ...mockProposals[0] };
      component.acceptProposal(proposal);
      expect(proposal.status).toBe('ACCEPTED');
    });

    it('should set subjectNodeId, objectNodeId, edgeId from result', () => {
      const proposal = { ...mockProposals[0] };
      component.acceptProposal(proposal);
      expect(proposal.subjectNodeId).toBe('n1');
      expect(proposal.objectNodeId).toBe('n2');
      expect(proposal.edgeId).toBe('e1');
    });

    it('should log error when acceptProposal fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.acceptProposal.and.returnValue(throwError(() => new Error('Conflict')));
      component.acceptProposal({ ...mockProposals[0] });
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  describe('rejectProposal()', () => {
    beforeEach(fakeAsync(() => {
      serviceSpy.rejectProposal.and.returnValue(of(undefined));
      fixture.detectChanges();
      tick();
    }));

    it('should call rejectProposal with id and reason body', () => {
      const proposal = { ...mockProposals[0] };
      component.rejectProposal(proposal, 'Duplicate');
      expect(serviceSpy.rejectProposal).toHaveBeenCalledWith('p1', { reason: 'Duplicate' });
    });

    it('should update proposal status to REJECTED', () => {
      const proposal = { ...mockProposals[0] };
      component.rejectProposal(proposal, 'Duplicate');
      expect(proposal.status).toBe('REJECTED');
    });

    it('should set rejectionReason on the proposal', () => {
      const proposal = { ...mockProposals[0] };
      component.rejectProposal(proposal, 'Irrelevant');
      expect(proposal.rejectionReason).toBe('Irrelevant');
    });

    it('should log error when rejectProposal fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.rejectProposal.and.returnValue(throwError(() => new Error('Error')));
      component.rejectProposal({ ...mockProposals[0] });
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // toggleProposalSelection / selectAll / deselectAll
  // ─────────────────────────────────────────────────────────────────────────

  describe('proposal selection', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.proposals = [...mockProposals];
    }));

    it('toggleProposalSelection should add an id when not already selected', () => {
      component.toggleProposalSelection('p1');
      expect(component.selectedProposals.has('p1')).toBe(true);
    });

    it('toggleProposalSelection should remove an id when already selected', () => {
      component.selectedProposals.add('p1');
      component.toggleProposalSelection('p1');
      expect(component.selectedProposals.has('p1')).toBe(false);
    });

    it('selectAllProposals should add all proposal ids', () => {
      component.selectAllProposals();
      expect(component.selectedProposals.has('p1')).toBe(true);
      expect(component.selectedProposals.has('p2')).toBe(true);
    });

    it('deselectAllProposals should clear all selections', () => {
      component.selectedProposals.add('p1');
      component.selectedProposals.add('p2');
      component.deselectAllProposals();
      expect(component.selectedProposals.size).toBe(0);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // openBulkActionDialog / performBulkAction
  // ─────────────────────────────────────────────────────────────────────────

  describe('openBulkActionDialog()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('should not open dialog when no proposals are selected', () => {
      component.openBulkActionDialog('accept');
      expect(component.showBulkActionDialog).toBe(false);
    });

    it('should open dialog when proposals are selected', () => {
      component.selectedProposals.add('p1');
      component.openBulkActionDialog('accept');
      expect(component.showBulkActionDialog).toBe(true);
    });

    it('should set bulkActionType correctly', () => {
      component.selectedProposals.add('p1');
      component.openBulkActionDialog('reject');
      expect(component.bulkActionType).toBe('reject');
    });

    it('should reset bulkRejectReason when opening dialog', () => {
      component.bulkRejectReason = 'old reason';
      component.selectedProposals.add('p1');
      component.openBulkActionDialog('reject');
      expect(component.bulkRejectReason).toBe('');
    });

    it('closeBulkActionDialog should hide the dialog', () => {
      component.showBulkActionDialog = true;
      component.closeBulkActionDialog();
      expect(component.showBulkActionDialog).toBe(false);
    });
  });

  describe('performBulkAction()', () => {
    beforeEach(fakeAsync(() => {
      serviceSpy.bulkAcceptProposals.and.returnValue(of(undefined));
      serviceSpy.bulkRejectProposals.and.returnValue(of(undefined));
      fixture.detectChanges();
      tick();
      component.selectedProposals.add('p1');
      component.selectedProposals.add('p2');
      serviceSpy.getProposals.calls.reset();
    }));

    it('should call bulkAcceptProposals with selected ids when type is accept', fakeAsync(() => {
      component.bulkActionType = 'accept';
      component.performBulkAction();
      tick();
      expect(serviceSpy.bulkAcceptProposals).toHaveBeenCalledWith(['p1', 'p2']);
    }));

    it('should reload proposals after bulk accept', fakeAsync(() => {
      component.bulkActionType = 'accept';
      component.performBulkAction();
      tick();
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('should clear selectedProposals after bulk accept', fakeAsync(() => {
      component.bulkActionType = 'accept';
      component.performBulkAction();
      tick();
      expect(component.selectedProposals.size).toBe(0);
    }));

    it('should close dialog after bulk accept', fakeAsync(() => {
      component.bulkActionType = 'accept';
      component.showBulkActionDialog = true;
      component.performBulkAction();
      tick();
      expect(component.showBulkActionDialog).toBe(false);
    }));

    it('should call bulkRejectProposals with ids, user and reason when type is reject', fakeAsync(() => {
      component.bulkActionType = 'reject';
      component.bulkRejectReason = 'Too vague';
      component.performBulkAction();
      tick();
      expect(serviceSpy.bulkRejectProposals).toHaveBeenCalledWith(['p1', 'p2'], 'user', 'Too vague');
    }));

    it('should reload proposals after bulk reject', fakeAsync(() => {
      component.bulkActionType = 'reject';
      component.performBulkAction();
      tick();
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('should log error when bulk accept fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.bulkAcceptProposals.and.returnValue(throwError(() => new Error('Error')));
      component.bulkActionType = 'accept';
      component.performBulkAction();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));

    it('should log error when bulk reject fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.bulkRejectProposals.and.returnValue(throwError(() => new Error('Error')));
      component.bulkActionType = 'reject';
      component.performBulkAction();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // acceptAllPending
  // ─────────────────────────────────────────────────────────────────────────

  describe('acceptAllPending()', () => {
    beforeEach(fakeAsync(() => {
      serviceSpy.acceptAllPending.and.returnValue(of(undefined));
      fixture.detectChanges();
      tick();
      serviceSpy.getProposals.calls.reset();
    }));

    it('should not call service when selectedJob is null', () => {
      component.selectedJob = null;
      component.acceptAllPending();
      expect(serviceSpy.acceptAllPending).not.toHaveBeenCalled();
    });

    it('should call acceptAllPending with the selected job id', () => {
      component.selectedJob = mockJobs[0];
      component.acceptAllPending();
      expect(serviceSpy.acceptAllPending).toHaveBeenCalledWith('job-1');
    });

    it('should reload proposals after accepting all pending', fakeAsync(() => {
      component.selectedJob = mockJobs[0];
      component.acceptAllPending();
      tick();
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('should log error when acceptAllPending fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.acceptAllPending.and.returnValue(throwError(() => new Error('Error')));
      component.selectedJob = mockJobs[0];
      component.acceptAllPending();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // openManualProposalForm / submitManualProposal
  // ─────────────────────────────────────────────────────────────────────────

  describe('manual proposal form', () => {
    const newProposal: TripleProposal = {
      proposalId: 'p-manual',
      factSheetId: 42,
      subjectName: 'Deep Learning',
      subjectType: 'CONCEPT',
      predicateName: 'SUBSET_OF',
      objectName: 'ML',
      objectType: 'CONCEPT',
      confidence: 1.0,
      status: 'PENDING'
    };

    beforeEach(fakeAsync(() => {
      serviceSpy.createManualProposal.and.returnValue(of(newProposal));
      fixture.detectChanges();
      tick();
    }));

    it('openManualProposalForm should set showManualProposalForm to true', () => {
      component.openManualProposalForm();
      expect(component.showManualProposalForm).toBe(true);
    });

    it('openManualProposalForm should reset the form fields', () => {
      component.manualProposal.subjectName = 'Something';
      component.openManualProposalForm();
      expect(component.manualProposal.subjectName).toBe('');
    });

    it('closeManualProposalForm should set showManualProposalForm to false', () => {
      component.showManualProposalForm = true;
      component.closeManualProposalForm();
      expect(component.showManualProposalForm).toBe(false);
    });

    it('submitManualProposal should not call service when required fields are missing', () => {
      component.manualProposal = {
        subjectName: '',
        subjectType: 'CONCEPT',
        predicateName: 'HAS',
        objectName: 'X',
        objectType: 'CONCEPT',
        description: '',
        autoAccept: false
      };
      component.submitManualProposal();
      expect(serviceSpy.createManualProposal).not.toHaveBeenCalled();
    });

    it('submitManualProposal should call createManualProposal with correct data', () => {
      component.manualProposal = {
        subjectName: 'Deep Learning',
        subjectType: 'CONCEPT',
        predicateName: 'SUBSET_OF',
        objectName: 'ML',
        objectType: 'CONCEPT',
        description: '',
        autoAccept: false
      };
      component.submitManualProposal();
      expect(serviceSpy.createManualProposal).toHaveBeenCalledWith(
        jasmine.objectContaining({
          factSheetId: 42,
          subjectName: 'Deep Learning',
          predicateName: 'SUBSET_OF',
          objectName: 'ML'
        })
      );
    });

    it('submitManualProposal should prepend new proposal to proposals array', fakeAsync(() => {
      component.proposals = [...mockProposals];
      component.manualProposal = {
        subjectName: 'Deep Learning',
        subjectType: 'CONCEPT',
        predicateName: 'SUBSET_OF',
        objectName: 'ML',
        objectType: 'CONCEPT',
        description: '',
        autoAccept: false
      };
      component.submitManualProposal();
      tick();
      expect(component.proposals[0]).toEqual(newProposal);
    }));

    it('submitManualProposal should close the form on success', fakeAsync(() => {
      component.showManualProposalForm = true;
      component.manualProposal = {
        subjectName: 'Deep Learning',
        subjectType: 'CONCEPT',
        predicateName: 'SUBSET_OF',
        objectName: 'ML',
        objectType: 'CONCEPT',
        description: '',
        autoAccept: false
      };
      component.submitManualProposal();
      tick();
      expect(component.showManualProposalForm).toBe(false);
    }));

    it('submitManualProposal should log error on failure', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.createManualProposal.and.returnValue(throwError(() => new Error('Error')));
      component.manualProposal = {
        subjectName: 'A',
        subjectType: 'CONCEPT',
        predicateName: 'B',
        objectName: 'C',
        objectType: 'CONCEPT',
        description: '',
        autoAccept: false
      };
      component.submitManualProposal();
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // parseEntities / parseRelationships
  // ─────────────────────────────────────────────────────────────────────────

  describe('parseEntities()', () => {
    it('should return empty array for undefined input', () => {
      expect(component.parseEntities(undefined)).toEqual([]);
    });

    it('should return empty array for empty string', () => {
      expect(component.parseEntities('')).toEqual([]);
    });

    it('should parse valid JSON array of entities', () => {
      const json = '[{"id":"e1","title":"AI","label":"CONCEPT"}]';
      const result = component.parseEntities(json);
      expect(result.length).toBe(1);
      expect(result[0].id).toBe('e1');
      expect(result[0].title).toBe('AI');
    });

    it('should return empty array for invalid JSON', () => {
      expect(component.parseEntities('{not valid json')).toEqual([]);
    });
  });

  describe('parseRelationships()', () => {
    it('should return empty array for undefined input', () => {
      expect(component.parseRelationships(undefined)).toEqual([]);
    });

    it('should return empty array for empty string', () => {
      expect(component.parseRelationships('')).toEqual([]);
    });

    it('should parse valid JSON array of relationships', () => {
      const json = '[{"source":"AI","target":"ML","type":"RELATED_TO"}]';
      const result = component.parseRelationships(json);
      expect(result.length).toBe(1);
      expect(result[0].source).toBe('AI');
      expect(result[0].type).toBe('RELATED_TO');
    });

    it('should return empty array for invalid JSON', () => {
      expect(component.parseRelationships('[broken')).toEqual([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // setViewMode
  // ─────────────────────────────────────────────────────────────────────────

  describe('setViewMode()', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      serviceSpy.getProposals.calls.reset();
      serviceSpy.getJobLogs.calls.reset();
    }));

    it('should set viewMode to the given mode', () => {
      component.setViewMode('jobs');
      expect(component.viewMode).toBe('jobs');
    });

    it('should call loadProposals when switching to proposals view', () => {
      component.setViewMode('proposals');
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    });

    it('should call loadExtractionLogs when switching to logs view with a selected job', () => {
      component.selectedJob = mockJobs[0];
      component.setViewMode('logs');
      expect(serviceSpy.getJobLogs).toHaveBeenCalled();
    });

    it('should not call loadExtractionLogs when switching to logs view without a selected job', () => {
      component.selectedJob = null;
      component.setViewMode('logs');
      expect(serviceSpy.getJobLogs).not.toHaveBeenCalled();
    });

    it('should not call loadProposals when switching to overview', () => {
      component.setViewMode('overview');
      expect(serviceSpy.getProposals).not.toHaveBeenCalled();
    });

    it('should not call loadProposals when switching to config', () => {
      component.setViewMode('config');
      expect(serviceSpy.getProposals).not.toHaveBeenCalled();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Pagination — proposals
  // ─────────────────────────────────────────────────────────────────────────

  describe('proposals pagination', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      serviceSpy.getProposals.calls.reset();
    }));

    it('proposalsTotalPages should calculate correctly', () => {
      component.proposalsTotalElements = 45;
      component.proposalsPageSize = 20;
      expect(component.proposalsTotalPages).toBe(3);
    });

    it('proposalsNextPage should increment page and reload when not on last page', fakeAsync(() => {
      component.proposalsTotalElements = 50;
      component.proposalsPage = 0;
      component.proposalsNextPage();
      tick();
      expect(component.proposalsPage).toBe(1);
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('proposalsNextPage should not increment page when already on last page', fakeAsync(() => {
      component.proposalsTotalElements = 20;
      component.proposalsPageSize = 20;
      component.proposalsPage = 0;
      component.proposalsNextPage();
      tick();
      expect(component.proposalsPage).toBe(0);
      expect(serviceSpy.getProposals).not.toHaveBeenCalled();
    }));

    it('proposalsPrevPage should decrement page and reload when not on first page', fakeAsync(() => {
      component.proposalsPage = 2;
      component.proposalsPrevPage();
      tick();
      expect(component.proposalsPage).toBe(1);
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('proposalsPrevPage should not decrement page when on first page', fakeAsync(() => {
      component.proposalsPage = 0;
      component.proposalsPrevPage();
      tick();
      expect(component.proposalsPage).toBe(0);
      expect(serviceSpy.getProposals).not.toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Pagination — logs
  // ─────────────────────────────────────────────────────────────────────────

  describe('logs pagination', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.selectedJob = mockJobs[0];
      serviceSpy.getJobLogs.calls.reset();
    }));

    it('logsTotalPages should calculate correctly', () => {
      component.logsTotalElements = 55;
      component.logsPageSize = 20;
      expect(component.logsTotalPages).toBe(3);
    });

    it('logsNextPage should increment page and reload when not on last page', fakeAsync(() => {
      component.logsTotalElements = 50;
      component.logsPage = 0;
      component.logsNextPage();
      tick();
      expect(component.logsPage).toBe(1);
      expect(serviceSpy.getJobLogs).toHaveBeenCalled();
    }));

    it('logsNextPage should not increment page when already on last page', fakeAsync(() => {
      component.logsTotalElements = 20;
      component.logsPageSize = 20;
      component.logsPage = 0;
      component.logsNextPage();
      tick();
      expect(component.logsPage).toBe(0);
      expect(serviceSpy.getJobLogs).not.toHaveBeenCalled();
    }));

    it('logsPrevPage should decrement page and reload when not on first page', fakeAsync(() => {
      component.logsPage = 1;
      component.logsPrevPage();
      tick();
      expect(component.logsPage).toBe(0);
      expect(serviceSpy.getJobLogs).toHaveBeenCalled();
    }));

    it('logsPrevPage should not decrement page when already on first page', fakeAsync(() => {
      component.logsPage = 0;
      component.logsPrevPage();
      tick();
      expect(component.logsPage).toBe(0);
      expect(serviceSpy.getJobLogs).not.toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Auto-refresh
  // ─────────────────────────────────────────────────────────────────────────

  describe('auto-refresh', () => {
    it('should start auto-refresh interval and call loadJobs periodically', fakeAsync(() => {
      // Create a fresh component with autoRefresh enabled
      fixture.destroy();
      fixture = TestBed.createComponent(KnowledgeGraphBuilderComponent);
      component = fixture.componentInstance;
      component.factSheetId = 42;
      component.autoRefresh = true;
      component.refreshIntervalSeconds = 5;

      serviceSpy.getBuilders.calls.reset();
      serviceSpy.getJobs.calls.reset();

      fixture.detectChanges();
      tick(); // flush ngOnInit subscriptions

      const callsAfterInit = serviceSpy.getJobs.calls.count();

      tick(5000); // one interval tick
      expect(serviceSpy.getJobs.calls.count()).toBeGreaterThan(callsAfterInit);

      discardPeriodicTasks();
      fixture.destroy();
    }));

    it('toggleAutoRefresh should toggle the autoRefresh flag', () => {
      component.autoRefresh = true;
      component.toggleAutoRefresh();
      expect(component.autoRefresh).toBe(false);

      component.toggleAutoRefresh();
      expect(component.autoRefresh).toBe(true);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Utility methods
  // ─────────────────────────────────────────────────────────────────────────

  describe('getJobStatusColor()', () => {
    it('should return the correct color for RUNNING', () => {
      expect(component.getJobStatusColor('RUNNING')).toBe(JOB_STATUS_COLORS['RUNNING']);
    });

    it('should return the correct color for COMPLETED', () => {
      expect(component.getJobStatusColor('COMPLETED')).toBe(JOB_STATUS_COLORS['COMPLETED']);
    });

    it('should return the correct color for FAILED', () => {
      expect(component.getJobStatusColor('FAILED')).toBe(JOB_STATUS_COLORS['FAILED']);
    });

    it('should return fallback grey for unknown status', () => {
      expect(component.getJobStatusColor('UNKNOWN' as any)).toBe('#9E9E9E');
    });
  });

  describe('getProposalStatusColor()', () => {
    it('should return the correct color for PENDING', () => {
      expect(component.getProposalStatusColor('PENDING')).toBe(PROPOSAL_STATUS_COLORS['PENDING']);
    });

    it('should return the correct color for ACCEPTED', () => {
      expect(component.getProposalStatusColor('ACCEPTED')).toBe(PROPOSAL_STATUS_COLORS['ACCEPTED']);
    });

    it('should return the correct color for REJECTED', () => {
      expect(component.getProposalStatusColor('REJECTED')).toBe(PROPOSAL_STATUS_COLORS['REJECTED']);
    });

    it('should return fallback grey for unknown status', () => {
      expect(component.getProposalStatusColor('UNKNOWN' as any)).toBe('#9E9E9E');
    });
  });

  describe('getBuilderIcon()', () => {
    it('should return psychology icon for LLM builders', () => {
      expect(component.getBuilderIcon('LLM')).toBe(BUILDER_TYPE_ICONS['LLM']);
    });

    it('should return edit icon for MANUAL builders', () => {
      expect(component.getBuilderIcon('MANUAL')).toBe(BUILDER_TYPE_ICONS['MANUAL']);
    });

    it('should return fallback extension icon for unknown builder type', () => {
      expect(component.getBuilderIcon('CUSTOM')).toBe('extension');
    });
  });

  describe('getRunningJobsCount()', () => {
    it('should return 0 when no jobs are running', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.jobs = [mockJobs[0]]; // COMPLETED
      expect(component.getRunningJobsCount()).toBe(0);
    }));

    it('should count only RUNNING jobs', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.jobs = [...mockJobs]; // one COMPLETED, one RUNNING
      expect(component.getRunningJobsCount()).toBe(1);
    }));
  });

  describe('getPendingProposalsCount()', () => {
    it('should return 0 when no proposals are pending', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.proposals = [mockProposals[1]]; // ACCEPTED
      expect(component.getPendingProposalsCount()).toBe(0);
    }));

    it('should count only PENDING proposals', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.proposals = [...mockProposals]; // one PENDING, one ACCEPTED
      expect(component.getPendingProposalsCount()).toBe(1);
    }));
  });

  describe('formatTimestamp()', () => {
    it('should return dash for undefined input', () => {
      expect(component.formatTimestamp(undefined)).toBe('-');
    });

    it('should return a non-empty locale string for a valid ISO timestamp', () => {
      const result = component.formatTimestamp('2025-01-01T00:00:00Z');
      expect(result).toBeTruthy();
      expect(result).not.toBe('-');
    });
  });

  describe('formatConfidence()', () => {
    it('should format 0.9 as 90.0%', () => {
      expect(component.formatConfidence(0.9)).toBe('90.0%');
    });

    it('should format 0.856 as 85.6%', () => {
      expect(component.formatConfidence(0.856)).toBe('85.6%');
    });

    it('should format 1.0 as 100.0%', () => {
      expect(component.formatConfidence(1.0)).toBe('100.0%');
    });

    it('should format 0 as 0.0%', () => {
      expect(component.formatConfidence(0)).toBe('0.0%');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Track-by functions
  // ─────────────────────────────────────────────────────────────────────────

  describe('trackBy functions', () => {
    it('trackByJobId should return the jobId', () => {
      expect(component.trackByJobId(0, mockJobs[0])).toBe('job-1');
    });

    it('trackByProposalId should return the proposalId', () => {
      expect(component.trackByProposalId(0, mockProposals[0])).toBe('p1');
    });

    it('trackByLogId should return the log id', () => {
      expect(component.trackByLogId(0, mockLogs[0])).toBe(1);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Builder configuration helpers
  // ─────────────────────────────────────────────────────────────────────────

  describe('builder configuration', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('openConfigDialog should set showConfigDialog to true', () => {
      component.openConfigDialog();
      expect(component.showConfigDialog).toBe(true);
    });

    it('closeConfigDialog should set showConfigDialog to false', () => {
      component.showConfigDialog = true;
      component.closeConfigDialog();
      expect(component.showConfigDialog).toBe(false);
    });

    it('saveConfig should close the config dialog', () => {
      component.showConfigDialog = true;
      component.saveConfig();
      expect(component.showConfigDialog).toBe(false);
    });

    it('addEntityType should append new type to entityTypes', () => {
      const initialLength = component.builderConfig.entityTypes!.length;
      component.addEntityType('DOCUMENT');
      expect(component.builderConfig.entityTypes!.length).toBe(initialLength + 1);
      expect(component.builderConfig.entityTypes).toContain('DOCUMENT');
    });

    it('addEntityType should not duplicate an existing type', () => {
      const type = component.builderConfig.entityTypes![0];
      const initialLength = component.builderConfig.entityTypes!.length;
      component.addEntityType(type);
      expect(component.builderConfig.entityTypes!.length).toBe(initialLength);
    });

    it('addEntityType should do nothing for empty string', () => {
      const initialLength = component.builderConfig.entityTypes!.length;
      component.addEntityType('');
      expect(component.builderConfig.entityTypes!.length).toBe(initialLength);
    });

    it('removeEntityType should remove the given type', () => {
      component.builderConfig.entityTypes = ['PERSON', 'CONCEPT', 'EVENT'];
      component.removeEntityType('CONCEPT');
      expect(component.builderConfig.entityTypes).not.toContain('CONCEPT');
      expect(component.builderConfig.entityTypes!.length).toBe(2);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // Extraction log helpers
  // ─────────────────────────────────────────────────────────────────────────

  describe('extraction log helpers', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
    }));

    it('selectLog should set selectedLog', () => {
      component.selectLog(mockLogs[0]);
      expect(component.selectedLog).toEqual(mockLogs[0]);
    });

    it('closeLogDetail should clear selectedLog', () => {
      component.selectedLog = mockLogs[0];
      component.closeLogDetail();
      expect(component.selectedLog).toBeNull();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────
  // onProposalFilterChange / onProposalSearch
  // ─────────────────────────────────────────────────────────────────────────

  describe('proposal filter and search handlers', () => {
    beforeEach(fakeAsync(() => {
      fixture.detectChanges();
      tick();
      component.proposalsPage = 3;
      serviceSpy.getProposals.calls.reset();
    }));

    it('onProposalFilterChange should reset page to 0 and reload proposals', fakeAsync(() => {
      component.onProposalFilterChange();
      tick();
      expect(component.proposalsPage).toBe(0);
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));

    it('onProposalSearch should reset page to 0 and reload proposals', fakeAsync(() => {
      component.onProposalSearch();
      tick();
      expect(component.proposalsPage).toBe(0);
      expect(serviceSpy.getProposals).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // refreshJobStatus
  // ─────────────────────────────────────────────────────────────────────────

  describe('refreshJobStatus()', () => {
    const updatedJob: ExtractionJob = { ...mockJobs[1], status: 'COMPLETED', processedChunks: 5 };

    beforeEach(fakeAsync(() => {
      serviceSpy.getJob.and.returnValue(of(updatedJob));
      fixture.detectChanges();
      tick();
      component.jobs = [...mockJobs];
    }));

    it('should call getJob with the given job id', () => {
      component.refreshJobStatus(mockJobs[1]);
      expect(serviceSpy.getJob).toHaveBeenCalledWith('job-2');
    });

    it('should update the job in the jobs array', fakeAsync(() => {
      component.refreshJobStatus(mockJobs[1]);
      tick();
      const found = component.jobs.find(j => j.jobId === 'job-2');
      expect(found?.status).toBe('COMPLETED');
    }));

    it('should update selectedJob if it matches the refreshed job', fakeAsync(() => {
      component.selectedJob = { ...mockJobs[1] };
      component.refreshJobStatus(mockJobs[1]);
      tick();
      expect(component.selectedJob?.status).toBe('COMPLETED');
    }));

    it('should log error when getJob fails', fakeAsync(() => {
      spyOn(console, 'error');
      serviceSpy.getJob.and.returnValue(throwError(() => new Error('Not found')));
      component.refreshJobStatus(mockJobs[1]);
      tick();
      expect(console.error).toHaveBeenCalled();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────
  // ngOnDestroy
  // ─────────────────────────────────────────────────────────────────────────

  describe('ngOnDestroy', () => {
    it('should complete the destroy$ subject and stop subscriptions', fakeAsync(() => {
      fixture.detectChanges();
      tick();
      let destroyed = false;
      component['destroy$'].subscribe({ complete: () => { destroyed = true; } });
      component.ngOnDestroy();
      expect(destroyed).toBe(true);
    }));
  });
});
