package ai.kompile.app.ingest.domain;

import ai.kompile.app.ingest.domain.IngestEvent.IngestPhase;
import ai.kompile.app.ingest.domain.IndexingJobHistory.JobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IndexingJobHistory Resume Features")
class IndexingJobHistoryResumeTest {

    @Nested
    @DisplayName("isResumable")
    class IsResumable {

        @Test
        void failedWithCheckpointIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t1")
                    .fileName("doc.pdf")
                    .status(JobStatus.FAILED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertTrue(job.isResumable());
        }

        @Test
        void memoryKilledWithCheckpointIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t2")
                    .fileName("doc.pdf")
                    .status(JobStatus.MEMORY_KILLED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertTrue(job.isResumable());
        }

        @Test
        void cancelledWithCheckpointIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t3")
                    .fileName("doc.pdf")
                    .status(JobStatus.CANCELLED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertTrue(job.isResumable());
        }

        @Test
        void pausedWithCheckpointIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t4")
                    .fileName("doc.pdf")
                    .status(JobStatus.PAUSED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertTrue(job.isResumable());
        }

        @Test
        void completedNotResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t5")
                    .fileName("doc.pdf")
                    .status(JobStatus.COMPLETED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertFalse(job.isResumable());
        }

        @Test
        void runningNotResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t6")
                    .fileName("doc.pdf")
                    .status(JobStatus.RUNNING)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertFalse(job.isResumable());
        }

        @Test
        void queuedNotResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t7")
                    .fileName("doc.pdf")
                    .status(JobStatus.QUEUED)
                    .checkpointPath("/tmp/cp.json")
                    .startTime(Instant.now())
                    .build();

            assertFalse(job.isResumable());
        }

        @Test
        void failedWithoutCheckpointNotResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t8")
                    .fileName("doc.pdf")
                    .status(JobStatus.FAILED)
                    .checkpointPath(null)
                    .startTime(Instant.now())
                    .build();

            assertFalse(job.isResumable());
        }
    }

    @Nested
    @DisplayName("markPaused")
    class MarkPaused {

        @Test
        void setsStatusAndCheckpoint() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t10")
                    .fileName("doc.pdf")
                    .status(JobStatus.RUNNING)
                    .startTime(Instant.now())
                    .build();

            job.markPaused(IngestPhase.EMBEDDING, "/tmp/checkpoint.json");

            assertEquals(JobStatus.PAUSED, job.getStatus());
            assertEquals(IngestPhase.EMBEDDING, job.getLastPhase());
            assertEquals("/tmp/checkpoint.json", job.getCheckpointPath());
            assertEquals(IngestPhase.EMBEDDING, job.getResumeFromPhase());
        }

        @Test
        void pausedJobIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t11")
                    .fileName("doc.pdf")
                    .status(JobStatus.RUNNING)
                    .startTime(Instant.now())
                    .build();

            job.markPaused(IngestPhase.INDEXING, "/tmp/cp.json");

            assertTrue(job.isResumable());
        }
    }

    @Nested
    @DisplayName("checkpointPath and resumeFromPhase fields")
    class CheckpointFields {

        @Test
        void checkpointPathSetViaBuilder() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t20")
                    .fileName("doc.pdf")
                    .status(JobStatus.FAILED)
                    .checkpointPath("/data/checkpoints/task-20.json")
                    .resumeFromPhase(IngestPhase.EMBEDDING)
                    .startTime(Instant.now())
                    .build();

            assertEquals("/data/checkpoints/task-20.json", job.getCheckpointPath());
            assertEquals(IngestPhase.EMBEDDING, job.getResumeFromPhase());
        }

        @Test
        void checkpointPathSetViaSetter() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t21")
                    .fileName("doc.pdf")
                    .status(JobStatus.FAILED)
                    .startTime(Instant.now())
                    .build();

            assertNull(job.getCheckpointPath());

            job.setCheckpointPath("/tmp/new-cp.json");
            job.setResumeFromPhase(IngestPhase.INDEXING);

            assertEquals("/tmp/new-cp.json", job.getCheckpointPath());
            assertEquals(IngestPhase.INDEXING, job.getResumeFromPhase());
        }
    }

    @Nested
    @DisplayName("Status transitions involving resume")
    class StatusTransitions {

        @Test
        void failedThenPausedOnStartup() {
            // Simulates cleanupOrphanedJobsOnStartup marking a checkpointed orphan as PAUSED
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t30")
                    .fileName("doc.pdf")
                    .status(JobStatus.RUNNING)
                    .checkpointPath("/tmp/cp.json")
                    .lastPhase(IngestPhase.EMBEDDING)
                    .startTime(Instant.now())
                    .build();

            // Simulate what cleanupOrphanedJobsOnStartup does
            job.markPaused(job.getLastPhase(), job.getCheckpointPath());

            assertEquals(JobStatus.PAUSED, job.getStatus());
            assertTrue(job.isResumable());
            assertEquals(IngestPhase.EMBEDDING, job.getResumeFromPhase());
        }

        @Test
        void memoryKilledWithCheckpointIsResumable() {
            IndexingJobHistory job = IndexingJobHistory.builder()
                    .taskId("t31")
                    .fileName("large.pdf")
                    .status(JobStatus.RUNNING)
                    .startTime(Instant.now())
                    .build();

            job.setCheckpointPath("/tmp/cp.json");
            job.setResumeFromPhase(IngestPhase.EMBEDDING);
            job.markMemoryKilled(IngestPhase.EMBEDDING, 95.5);

            assertEquals(JobStatus.MEMORY_KILLED, job.getStatus());
            // checkpointPath was set before markMemoryKilled, so it persists
            assertEquals("/tmp/cp.json", job.getCheckpointPath());
            assertTrue(job.isResumable());
        }
    }
}
