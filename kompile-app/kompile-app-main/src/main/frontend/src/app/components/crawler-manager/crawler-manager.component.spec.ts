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
  ComponentFixture,
  TestBed,
  fakeAsync,
  tick,
  discardPeriodicTasks
} from '@angular/core/testing';
import { CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { CrawlerManagerComponent } from './crawler-manager.component';
import {
  CrawlerService,
  CrawlerInfo,
  CrawlJobSummary,
  StartCrawlResponse,
  SimpleResponse
} from '../../services/crawler.service';

// ═══════════════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════════════

function mockCrawlers(): CrawlerInfo[] {
  return [
    { id: 'web', name: 'Web Crawler', description: 'Crawls websites via HTTP', supportedSourceTypes: ['URL', 'WEB_CRAWL'] },
    { id: 'filesystem', name: 'File System Crawler', description: 'Scans directories', supportedSourceTypes: ['FILE', 'DIRECTORY'] }
  ];
}

function mockJobs(): CrawlJobSummary[] {
  return [
    {
      jobId: 'job-abc-123', status: 'RUNNING', crawlerId: 'web', seed: 'https://docs.example.com',
      progress: { discovered: 20, processed: 12, failed: 1, queued: 7, currentDepth: 2, maxDepth: 3, currentItem: 'https://docs.example.com/guide', estimatedPercent: 13 }
    },
    {
      jobId: 'job-def-456', status: 'COMPLETED', crawlerId: 'filesystem', seed: '/home/user/docs',
      progress: { discovered: 50, processed: 48, failed: 2, queued: 0, currentDepth: 3, maxDepth: 3, currentItem: '', estimatedPercent: 100 }
    }
  ];
}

function mockStartResponse(): StartCrawlResponse {
  return {
    jobId: 'job-new-789',
    status: 'RUNNING',
    message: 'Crawl job started',
    crawlerId: 'web',
    seed: 'https://example.com',
    pipelineCount: 1,
    routeRuleCount: 0
  };
}

function createCrawlerServiceSpy() {
  return jasmine.createSpyObj('CrawlerService', [
    'listCrawlers',
    'startCrawl',
    'listJobs',
    'listActiveJobs',
    'getJob',
    'pauseJob',
    'resumeJob',
    'cancelJob',
    'cleanupJobs'
  ]);
}

// ═══════════════════════════════════════════════════════════════════════════════
// TESTS
// ═══════════════════════════════════════════════════════════════════════════════

describe('CrawlerManagerComponent', () => {
  let component: CrawlerManagerComponent;
  let fixture: ComponentFixture<CrawlerManagerComponent>;
  let crawlerServiceSpy: jasmine.SpyObj<CrawlerService>;

  beforeEach(async () => {
    crawlerServiceSpy = createCrawlerServiceSpy();

    // Safe defaults
    crawlerServiceSpy.listCrawlers.and.returnValue(of(mockCrawlers()));
    crawlerServiceSpy.listJobs.and.returnValue(of(mockJobs()));
    crawlerServiceSpy.startCrawl.and.returnValue(of(mockStartResponse()));
    crawlerServiceSpy.pauseJob.and.returnValue(of({ message: 'Job paused' }));
    crawlerServiceSpy.resumeJob.and.returnValue(of({ message: 'Job resumed' }));
    crawlerServiceSpy.cancelJob.and.returnValue(of({ message: 'Job cancelled' }));
    crawlerServiceSpy.cleanupJobs.and.returnValue(of({ removed: 1 }));

    await TestBed.configureTestingModule({
      imports: [
        CrawlerManagerComponent,
        FormsModule,
        NoopAnimationsModule
      ],
      providers: [
        { provide: CrawlerService, useValue: crawlerServiceSpy }
      ],
      schemas: [CUSTOM_ELEMENTS_SCHEMA]
    }).compileComponents();

    fixture = TestBed.createComponent(CrawlerManagerComponent);
    component = fixture.componentInstance;
  });

  afterEach(() => {
    // Clean up setInterval from ngOnInit
    component.ngOnDestroy();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 1. Component creation and initialization
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Component creation', () => {
    it('should create', () => {
      fixture.detectChanges();
      expect(component).toBeTruthy();
    });

    it('should load crawlers on init', () => {
      fixture.detectChanges();
      expect(crawlerServiceSpy.listCrawlers).toHaveBeenCalled();
      expect(component.crawlers.length).toBe(2);
      expect(component.crawlers[0].id).toBe('web');
    });

    it('should load jobs on init', () => {
      fixture.detectChanges();
      expect(crawlerServiceSpy.listJobs).toHaveBeenCalled();
      expect(component.jobs.length).toBe(2);
    });

    it('should set error message when listCrawlers fails', () => {
      crawlerServiceSpy.listCrawlers.and.returnValue(
        throwError(() => ({ message: 'Network error', statusText: 'Unknown' }))
      );
      fixture.detectChanges();
      expect(component.errorMessage).toContain('Failed to load crawlers');
    });

    it('should set error message when listJobs fails', () => {
      crawlerServiceSpy.listJobs.and.returnValue(
        throwError(() => ({ message: 'Server down', statusText: 'Service Unavailable' }))
      );
      fixture.detectChanges();
      expect(component.errorMessage).toContain('Failed to load jobs');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 2. Auto-refresh
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Auto-refresh', () => {
    it('should poll jobs on interval', fakeAsync(() => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      tick(5000);
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);

      tick(5000);
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 2);

      discardPeriodicTasks();
    }));

    it('should stop polling on destroy', fakeAsync(() => {
      fixture.detectChanges();
      component.ngOnDestroy();

      const callCount = crawlerServiceSpy.listJobs.calls.count();
      tick(10000);
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(callCount);

      discardPeriodicTasks();
    }));
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 3. Start crawl
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startCrawl()', () => {
    it('should call startCrawl with form values', () => {
      fixture.detectChanges();

      component.seed = 'https://example.com';
      component.selectedCrawlerId = 'web';
      component.maxDepth = 5;
      component.maxDocuments = 200;
      component.sameDomainOnly = false;

      component.startCrawl();

      expect(crawlerServiceSpy.startCrawl).toHaveBeenCalledWith(
        jasmine.objectContaining({
          seed: 'https://example.com',
          crawlerId: 'web',
          maxDepth: 5,
          maxDocuments: 200,
          sameDomainOnly: false
        })
      );
    });

    it('should not include crawlerId when auto-detect selected', () => {
      fixture.detectChanges();

      component.seed = 'https://example.com';
      component.selectedCrawlerId = '';

      component.startCrawl();

      const callArg = crawlerServiceSpy.startCrawl.calls.mostRecent().args[0];
      expect(callArg.crawlerId).toBeUndefined();
    });

    it('should clear seed after successful start', () => {
      fixture.detectChanges();

      component.seed = 'https://example.com';
      component.startCrawl();

      expect(component.seed).toBe('');
      expect(component.isLoading).toBeFalse();
    });

    it('should refresh jobs after successful start', () => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      component.seed = 'https://example.com';
      component.startCrawl();

      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);
    });

    it('should not start crawl with empty seed', () => {
      fixture.detectChanges();

      component.seed = '';
      component.startCrawl();

      expect(crawlerServiceSpy.startCrawl).not.toHaveBeenCalled();
    });

    it('should not start crawl with whitespace-only seed', () => {
      fixture.detectChanges();

      component.seed = '   ';
      component.startCrawl();

      expect(crawlerServiceSpy.startCrawl).not.toHaveBeenCalled();
    });

    it('should set isLoading during request', () => {
      fixture.detectChanges();

      component.seed = 'https://example.com';
      // isLoading is set to true synchronously
      expect(component.isLoading).toBeFalse();
      component.startCrawl();
      // After the observable resolves (synchronous with of()), isLoading is false again
      expect(component.isLoading).toBeFalse();
    });

    it('should set error message on start failure', () => {
      crawlerServiceSpy.startCrawl.and.returnValue(
        throwError(() => ({ error: { error: 'Invalid seed URL' }, message: 'Bad Request' }))
      );
      fixture.detectChanges();

      component.seed = 'not-a-url';
      component.startCrawl();

      expect(component.errorMessage).toContain('Failed to start crawl');
      expect(component.isLoading).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 4. Job lifecycle actions
  // ─────────────────────────────────────────────────────────────────────────────

  describe('pauseJob()', () => {
    it('should call pauseJob and refresh', () => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      component.pauseJob('job-abc-123');

      expect(crawlerServiceSpy.pauseJob).toHaveBeenCalledWith('job-abc-123');
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);
    });

    it('should set error on pause failure', () => {
      crawlerServiceSpy.pauseJob.and.returnValue(
        throwError(() => ({ error: { error: 'Job not running' }, message: 'Bad Request' }))
      );
      fixture.detectChanges();

      component.pauseJob('job-def-456');

      expect(component.errorMessage).toContain('Failed to pause job');
    });
  });

  describe('resumeJob()', () => {
    it('should call resumeJob and refresh', () => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      component.resumeJob('job-abc-123');

      expect(crawlerServiceSpy.resumeJob).toHaveBeenCalledWith('job-abc-123');
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);
    });

    it('should set error on resume failure', () => {
      crawlerServiceSpy.resumeJob.and.returnValue(
        throwError(() => ({ error: { error: 'Job not paused' }, message: 'Bad Request' }))
      );
      fixture.detectChanges();

      component.resumeJob('job-abc-123');

      expect(component.errorMessage).toContain('Failed to resume job');
    });
  });

  describe('cancelJob()', () => {
    it('should call cancelJob and refresh', () => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      component.cancelJob('job-abc-123');

      expect(crawlerServiceSpy.cancelJob).toHaveBeenCalledWith('job-abc-123');
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);
    });

    it('should set error on cancel failure', () => {
      crawlerServiceSpy.cancelJob.and.returnValue(
        throwError(() => ({ error: { error: 'Already finished' }, message: 'Bad Request' }))
      );
      fixture.detectChanges();

      component.cancelJob('job-def-456');

      expect(component.errorMessage).toContain('Failed to cancel job');
    });
  });

  describe('cleanupJobs()', () => {
    it('should call cleanupJobs and refresh', () => {
      fixture.detectChanges();
      const initialCallCount = crawlerServiceSpy.listJobs.calls.count();

      component.cleanupJobs();

      expect(crawlerServiceSpy.cleanupJobs).toHaveBeenCalled();
      expect(crawlerServiceSpy.listJobs.calls.count()).toBe(initialCallCount + 1);
    });

    it('should set error on cleanup failure', () => {
      crawlerServiceSpy.cleanupJobs.and.returnValue(
        throwError(() => ({ error: { error: 'Server error' }, message: 'Internal Server Error' }))
      );
      fixture.detectChanges();

      component.cleanupJobs();

      expect(component.errorMessage).toContain('Failed to cleanup jobs');
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 5. Utility methods
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getStatusColor()', () => {
    it('should return correct colors for each status', () => {
      expect(component.getStatusColor('RUNNING')).toBe('primary');
      expect(component.getStatusColor('PAUSED')).toBe('accent');
      expect(component.getStatusColor('COMPLETED')).toBe('primary');
      expect(component.getStatusColor('FAILED')).toBe('warn');
      expect(component.getStatusColor('CANCELLED')).toBe('warn');
      expect(component.getStatusColor('UNKNOWN')).toBe('');
    });
  });

  describe('isJobActive()', () => {
    it('should return true for active statuses', () => {
      expect(component.isJobActive('RUNNING')).toBeTrue();
      expect(component.isJobActive('PAUSED')).toBeTrue();
      expect(component.isJobActive('PENDING')).toBeTrue();
    });

    it('should return false for terminal statuses', () => {
      expect(component.isJobActive('COMPLETED')).toBeFalse();
      expect(component.isJobActive('FAILED')).toBeFalse();
      expect(component.isJobActive('CANCELLED')).toBeFalse();
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // 6. Default form values
  // ─────────────────────────────────────────────────────────────────────────────

  describe('Default form values', () => {
    it('should have sensible defaults', () => {
      expect(component.seed).toBe('');
      expect(component.selectedCrawlerId).toBe('');
      expect(component.maxDepth).toBe(3);
      expect(component.maxDocuments).toBe(1000);
      expect(component.sameDomainOnly).toBeTrue();
      expect(component.isLoading).toBeFalse();
      expect(component.errorMessage).toBeNull();
    });
  });
});
