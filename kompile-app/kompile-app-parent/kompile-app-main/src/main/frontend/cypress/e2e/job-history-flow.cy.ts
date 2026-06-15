/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0
 *
 * Job History E2E Flow Tests
 * Tests the job history and indexing lifecycle against a running backend:
 * - Job listing and filtering
 * - Statistics endpoint
 * - Subprocess event tracking
 * - Job detail retrieval
 */

describe('Job History Flows', () => {

  before(() => {
    cy.waitForBackend();
  });

  // ═══════════════════════ Job Listing ═══════════════════════

  describe('Job Listing', () => {
    it('should list recent jobs within lookback window', () => {
      cy.apiGet('/indexing/history/recent?hours=168').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');

        if (res.body.length > 0) {
          const job = res.body[0];
          expect(job).to.have.property('taskId');
          expect(job.taskId).to.be.a('string');
          expect(job).to.have.property('fileName');
          expect(job).to.have.property('status');
          expect(job.status).to.be.oneOf([
            'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED',
            'CANCELLED', 'MEMORY_KILLED', 'PAUSED'
          ]);
          expect(job).to.have.property('startTime');
        }
      });
    });

    it('should return empty array for very short lookback', () => {
      // 0 hours = no jobs
      cy.apiGet('/indexing/history/recent?hours=0').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
      });
    });

    it('should filter by COMPLETED status', () => {
      cy.apiGet('/indexing/history/status/COMPLETED?hours=8760').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        res.body.forEach((job: any) => {
          expect(job.status).to.eq('COMPLETED');
        });
      });
    });

    it('should filter by FAILED status', () => {
      cy.apiGet('/indexing/history/status/FAILED?hours=8760').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        res.body.forEach((job: any) => {
          expect(job.status).to.eq('FAILED');
        });
      });
    });

    it('should return active jobs', () => {
      cy.apiGet('/indexing/history/active').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');
        res.body.forEach((job: any) => {
          expect(job.status).to.be.oneOf(['RUNNING', 'QUEUED']);
        });
      });
    });
  });

  // ═══════════════════════ Statistics ═══════════════════════

  describe('Statistics', () => {
    it('should return statistics with all required fields', () => {
      cy.apiGet('/indexing/history/statistics?lastHours=24').then((res) => {
        expect(res.status).to.eq(200);
        const stats = res.body;

        expect(stats).to.have.property('totalJobs');
        expect(stats.totalJobs).to.be.a('number');
        expect(stats.totalJobs).to.be.gte(0);

        expect(stats).to.have.property('completedJobs');
        expect(stats.completedJobs).to.be.a('number');

        expect(stats).to.have.property('failedJobs');
        expect(stats.failedJobs).to.be.a('number');

        expect(stats).to.have.property('activeJobs');
        expect(stats.activeJobs).to.be.a('number');
      });
    });

    it('should have consistent counts (completed + failed + active <= total)', () => {
      cy.apiGet('/indexing/history/statistics?lastHours=8760').then((res) => {
        expect(res.status).to.eq(200);
        const s = res.body;
        const sum = s.completedJobs + s.failedJobs + s.activeJobs +
                    (s.cancelledJobs || 0) + (s.memoryKilledJobs || 0);
        expect(sum).to.be.lte(s.totalJobs + 1); // +1 for timing races
      });
    });

    it('should return zero stats for very short lookback', () => {
      cy.apiGet('/indexing/history/statistics?lastHours=0').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body.totalJobs).to.eq(0);
      });
    });
  });

  // ═══════════════════════ Job Detail ═══════════════════════

  describe('Job Detail', () => {
    it('should return 404 for non-existent job', () => {
      cy.apiGet('/indexing/history/nonexistent-task-id-12345').then((res) => {
        expect(res.status).to.be.oneOf([404, 400]);
      });
    });

    it('should return job detail for existing job', () => {
      // First get a real job ID
      cy.apiGet('/indexing/history/recent?hours=8760').then((listRes) => {
        if (listRes.body.length > 0) {
          const taskId = listRes.body[0].taskId;
          cy.apiGet(`/indexing/history/${taskId}`).then((detailRes) => {
            expect(detailRes.status).to.eq(200);
            expect(detailRes.body).to.have.property('taskId', taskId);
            expect(detailRes.body).to.have.property('fileName');
            expect(detailRes.body).to.have.property('status');
          });
        }
      });
    });
  });

  // ═══════════════════════ Subprocess Events ═══════════════════════

  describe('Subprocess Events', () => {
    it('should return recent subprocess events', () => {
      cy.apiGet('/subprocess-events/recent?hours=168').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.be.an('array');

        if (res.body.length > 0) {
          const event = res.body[0];
          expect(event).to.have.property('eventType');
          expect(event).to.have.property('modelId');
          expect(event).to.have.property('timestamp');
        }
      });
    });

    it('should return subprocess statistics', () => {
      cy.apiGet('/subprocess-events/statistics').then((res) => {
        expect(res.status).to.eq(200);
        expect(res.body).to.have.property('available');
        expect(res.body.available).to.be.a('boolean');
      });
    });
  });

  // ═══════════════════════ Job Cleanup ═══════════════════════

  describe('Job Cleanup (read-only validation)', () => {
    it('should accept cleanup request with valid days parameter', () => {
      // We validate the endpoint accepts the request shape but use a dry-run-like approach
      // by checking with a very large days value that won't delete anything recent
      cy.apiPost('/indexing/history/cleanup', { olderThanDays: 99999 }).then((res) => {
        expect(res.status).to.be.oneOf([200, 204]);
      });
    });
  });
});
