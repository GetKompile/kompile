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

import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';

import {
  CrawlerService,
  CrawlerInfo,
  StartCrawlRequest,
  StartCrawlResponse,
  CrawlJobSummary,
  CrawlJobDetail,
  ValidateConfigResponse,
  SimpleResponse
} from './crawler.service';

describe('CrawlerService', () => {
  let service: CrawlerService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [CrawlerService]
    });

    service = TestBed.inject(CrawlerService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // listCrawlers()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('listCrawlers()', () => {
    it('should GET /api/crawlers and return crawler list', () => {
      const mockCrawlers: CrawlerInfo[] = [
        { id: 'web', name: 'Web Crawler', description: 'Crawls websites', supportedSourceTypes: ['URL', 'WEB_CRAWL'] },
        { id: 'filesystem', name: 'File System Crawler', description: 'Scans directories', supportedSourceTypes: ['FILE', 'DIRECTORY'] }
      ];

      service.listCrawlers().subscribe(crawlers => {
        expect(crawlers.length).toBe(2);
        expect(crawlers[0].id).toBe('web');
        expect(crawlers[1].id).toBe('filesystem');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers'));
      expect(req.request.method).toBe('GET');
      req.flush(mockCrawlers);
    });

    it('should handle empty crawler list', () => {
      service.listCrawlers().subscribe(crawlers => {
        expect(crawlers.length).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers'));
      req.flush([]);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // startCrawl()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('startCrawl()', () => {
    it('should POST /api/crawlers/start with config and return job info', () => {
      const request: StartCrawlRequest = {
        seed: 'https://docs.example.com',
        crawlerId: 'web',
        maxDepth: 3,
        maxDocuments: 500,
        sameDomainOnly: true
      };
      const mockResponse: StartCrawlResponse = {
        jobId: 'job-123',
        status: 'RUNNING',
        message: 'Crawl job started',
        crawlerId: 'web',
        seed: 'https://docs.example.com',
        pipelineCount: 1,
        routeRuleCount: 0
      };

      service.startCrawl(request).subscribe(response => {
        expect(response.jobId).toBe('job-123');
        expect(response.status).toBe('RUNNING');
        expect(response.crawlerId).toBe('web');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/start'));
      expect(req.request.method).toBe('POST');
      expect(req.request.body.seed).toBe('https://docs.example.com');
      expect(req.request.body.maxDepth).toBe(3);
      req.flush(mockResponse);
    });

    it('should POST with multi-pipeline config', () => {
      const request: StartCrawlRequest = {
        seed: 'https://docs.example.com',
        pipelines: [
          { pipelineId: 'html', pipelineType: 'STANDARD_TEXT' },
          { pipelineId: 'pdf', pipelineType: 'VLM', enableVlm: true }
        ],
        routeRules: [
          { pipelineId: 'pdf', contentTypes: ['application/pdf'], priority: 10 },
          { pipelineId: 'html', contentTypes: ['text/html'], priority: 20 }
        ],
        defaultPipelineId: 'html'
      };
      const mockResponse: StartCrawlResponse = {
        jobId: 'job-456',
        status: 'RUNNING',
        message: 'Crawl job started',
        crawlerId: 'web',
        seed: 'https://docs.example.com',
        pipelineCount: 2,
        routeRuleCount: 2
      };

      service.startCrawl(request).subscribe(response => {
        expect(response.pipelineCount).toBe(2);
        expect(response.routeRuleCount).toBe(2);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/start'));
      expect(req.request.body.pipelines.length).toBe(2);
      expect(req.request.body.routeRules.length).toBe(2);
      expect(req.request.body.defaultPipelineId).toBe('html');
      req.flush(mockResponse);
    });

    it('should handle validation error from server', () => {
      const request: StartCrawlRequest = { seed: '' };

      service.startCrawl(request).subscribe({
        next: () => fail('should have errored'),
        error: (error) => {
          expect(error.status).toBe(400);
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/start'));
      req.flush({ error: 'seed is required' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // validateConfig()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('validateConfig()', () => {
    it('should POST /api/crawlers/validate and return valid=true', () => {
      const request: StartCrawlRequest = { seed: 'https://example.com', maxDepth: 2 };
      const mockResponse: ValidateConfigResponse = { valid: true };

      service.validateConfig(request).subscribe(response => {
        expect(response.valid).toBeTrue();
        expect(response.errors).toBeUndefined();
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/validate'));
      expect(req.request.method).toBe('POST');
      req.flush(mockResponse);
    });

    it('should return errors for invalid config', () => {
      const request: StartCrawlRequest = { seed: '' };
      const mockResponse: ValidateConfigResponse = {
        valid: false,
        errors: ['seed is required']
      };

      service.validateConfig(request).subscribe(response => {
        expect(response.valid).toBeFalse();
        expect(response.errors!.length).toBe(1);
        expect(response.errors![0]).toBe('seed is required');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/validate'));
      req.flush(mockResponse);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // listJobs() / listActiveJobs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('listJobs()', () => {
    const mockJobs: CrawlJobSummary[] = [
      {
        jobId: 'job-1', status: 'COMPLETED', crawlerId: 'web', seed: 'https://a.com',
        progress: { discovered: 50, processed: 48, failed: 2, queued: 0, currentDepth: 3, maxDepth: 3, currentItem: '', estimatedPercent: 100 }
      },
      {
        jobId: 'job-2', status: 'RUNNING', crawlerId: 'filesystem', seed: '/home/docs',
        progress: { discovered: 10, processed: 5, failed: 0, queued: 5, currentDepth: 1, maxDepth: 5, currentItem: '/home/docs/a.pdf', estimatedPercent: 50 }
      }
    ];

    it('should GET /api/crawlers/jobs and return all jobs', () => {
      service.listJobs().subscribe(jobs => {
        expect(jobs.length).toBe(2);
        expect(jobs[0].jobId).toBe('job-1');
        expect(jobs[0].status).toBe('COMPLETED');
        expect(jobs[1].progress.discovered).toBe(10);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs'));
      expect(req.request.method).toBe('GET');
      req.flush(mockJobs);
    });

    it('should GET /api/crawlers/jobs/active and return only active jobs', () => {
      const activeJobs = [mockJobs[1]];

      service.listActiveJobs().subscribe(jobs => {
        expect(jobs.length).toBe(1);
        expect(jobs[0].status).toBe('RUNNING');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/active'));
      expect(req.request.method).toBe('GET');
      req.flush(activeJobs);
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // getJob()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('getJob()', () => {
    it('should GET /api/crawlers/jobs/{id} with pipeline info', () => {
      const mockDetail: CrawlJobDetail = {
        jobId: 'job-1', status: 'RUNNING', crawlerId: 'web', seed: 'https://docs.example.com',
        progress: { discovered: 20, processed: 15, failed: 1, queued: 4, currentDepth: 2, maxDepth: 3, currentItem: 'https://docs.example.com/guide', estimatedPercent: 16 },
        pipelines: [
          { pipelineId: 'html', displayName: 'HTML Pipeline', pipelineType: 'STANDARD_TEXT', isDefault: true },
          { pipelineId: 'pdf-vlm', displayName: 'PDF VLM Pipeline', pipelineType: 'VLM', isDefault: false }
        ]
      };

      service.getJob('job-1').subscribe(detail => {
        expect(detail.jobId).toBe('job-1');
        expect(detail.pipelines!.length).toBe(2);
        expect(detail.pipelines![0].isDefault).toBeTrue();
        expect(detail.pipelines![1].pipelineType).toBe('VLM');
        expect(detail.progress.currentItem).toBe('https://docs.example.com/guide');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/job-1'));
      expect(req.request.method).toBe('GET');
      req.flush(mockDetail);
    });

    it('should handle 404 for unknown job', () => {
      service.getJob('nonexistent').subscribe({
        next: () => fail('should have errored'),
        error: (error) => {
          expect(error.status).toBe(404);
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/nonexistent'));
      req.flush(null, { status: 404, statusText: 'Not Found' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // pauseJob() / resumeJob() / cancelJob()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('pauseJob()', () => {
    it('should POST /api/crawlers/jobs/{id}/pause', () => {
      service.pauseJob('job-1').subscribe(response => {
        expect(response.message).toBe('Job paused');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/job-1/pause'));
      expect(req.request.method).toBe('POST');
      req.flush({ message: 'Job paused', jobId: 'job-1' });
    });

    it('should handle error when job is not running', () => {
      service.pauseJob('job-done').subscribe({
        next: () => fail('should have errored'),
        error: (error) => {
          expect(error.status).toBe(400);
        }
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/job-done/pause'));
      req.flush({ error: 'Job not found or not running' }, { status: 400, statusText: 'Bad Request' });
    });
  });

  describe('resumeJob()', () => {
    it('should POST /api/crawlers/jobs/{id}/resume', () => {
      service.resumeJob('job-1').subscribe(response => {
        expect(response.message).toBe('Job resumed');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/job-1/resume'));
      expect(req.request.method).toBe('POST');
      req.flush({ message: 'Job resumed', jobId: 'job-1' });
    });
  });

  describe('cancelJob()', () => {
    it('should POST /api/crawlers/jobs/{id}/cancel', () => {
      service.cancelJob('job-1').subscribe(response => {
        expect(response.message).toBe('Job cancelled');
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/job-1/cancel'));
      expect(req.request.method).toBe('POST');
      req.flush({ message: 'Job cancelled', jobId: 'job-1' });
    });
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // cleanupJobs()
  // ─────────────────────────────────────────────────────────────────────────────

  describe('cleanupJobs()', () => {
    it('should POST /api/crawlers/jobs/cleanup and return removed count', () => {
      service.cleanupJobs().subscribe(response => {
        expect(response.removed).toBe(3);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/cleanup'));
      expect(req.request.method).toBe('POST');
      req.flush({ removed: 3 });
    });

    it('should return 0 when no jobs to clean', () => {
      service.cleanupJobs().subscribe(response => {
        expect(response.removed).toBe(0);
      });

      const req = httpMock.expectOne(r => r.url.endsWith('/crawlers/jobs/cleanup'));
      req.flush({ removed: 0 });
    });
  });
});
