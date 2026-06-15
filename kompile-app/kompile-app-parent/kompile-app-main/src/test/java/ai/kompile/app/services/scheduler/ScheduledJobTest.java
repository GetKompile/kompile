package ai.kompile.app.services.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ScheduledJob")
class ScheduledJobTest {

    private static final long GB = 1024L * 1024 * 1024;

    private ScheduledJob.JobExecutor noopExecutor = ctx -> {};

    private ScheduledJob buildJob(String id, String type, int priority) {
        return ScheduledJob.builder()
                .jobId(id)
                .jobType(type)
                .description("Test job " + id)
                .resourceProfile(JobResourceProfile.cpuOnly(type, "Test", GB))
                .executor(noopExecutor)
                .priority(priority)
                .build();
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        void buildSucceedsWithRequiredFields() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertEquals("j1", job.getJobId());
            assertEquals("ingest", job.getJobType());
            assertEquals("Test job j1", job.getDescription());
            assertEquals(50, job.getPriority());
            assertEquals(ScheduledJob.JobState.QUEUED, job.getState());
            assertNotNull(job.getQueuedAt());
            assertNotNull(job.getResultFuture());
            assertFalse(job.isGpuHeld());
            assertFalse(job.isExternallyDelegated());
            assertNull(job.getExternalRef());
        }

        @Test
        void buildFailsWithoutJobId() {
            assertThrows(IllegalArgumentException.class, () ->
                    ScheduledJob.builder()
                            .jobType("ingest")
                            .resourceProfile(JobResourceProfile.cpuOnly("t", "T", GB))
                            .executor(noopExecutor)
                            .build());
        }

        @Test
        void buildFailsWithoutJobType() {
            assertThrows(IllegalArgumentException.class, () ->
                    ScheduledJob.builder()
                            .jobId("j1")
                            .resourceProfile(JobResourceProfile.cpuOnly("t", "T", GB))
                            .executor(noopExecutor)
                            .build());
        }

        @Test
        void buildFailsWithoutResourceProfile() {
            assertThrows(IllegalArgumentException.class, () ->
                    ScheduledJob.builder()
                            .jobId("j1").jobType("ingest")
                            .executor(noopExecutor)
                            .build());
        }

        @Test
        void buildFailsWithoutExecutor() {
            assertThrows(IllegalArgumentException.class, () ->
                    ScheduledJob.builder()
                            .jobId("j1").jobType("ingest")
                            .resourceProfile(JobResourceProfile.cpuOnly("t", "T", GB))
                            .build());
        }

        @Test
        void metadataDefaultsToEmptyMap() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertNotNull(job.getMetadata());
            assertTrue(job.getMetadata().isEmpty());
        }

        @Test
        void metadataIsImmutableCopy() {
            ScheduledJob job = ScheduledJob.builder()
                    .jobId("j1").jobType("ingest")
                    .description("test")
                    .resourceProfile(JobResourceProfile.cpuOnly("t", "T", GB))
                    .executor(noopExecutor)
                    .metadata(Map.of("key", "value"))
                    .build();

            assertEquals("value", job.getMetadata().get("key"));
            assertThrows(UnsupportedOperationException.class,
                    () -> job.getMetadata().put("hack", "fail"));
        }
    }

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {

        @Test
        void stateProgressesThroughLifecycle() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertEquals(ScheduledJob.JobState.QUEUED, job.getState());
            assertFalse(job.isTerminal());

            job.setState(ScheduledJob.JobState.ACQUIRING);
            assertEquals(ScheduledJob.JobState.ACQUIRING, job.getState());
            assertFalse(job.isTerminal());

            job.setState(ScheduledJob.JobState.RUNNING);
            assertEquals(ScheduledJob.JobState.RUNNING, job.getState());
            assertFalse(job.isTerminal());

            job.setState(ScheduledJob.JobState.COMPLETED);
            assertEquals(ScheduledJob.JobState.COMPLETED, job.getState());
            assertTrue(job.isTerminal());
        }

        @Test
        void failedIsTerminal() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            job.setState(ScheduledJob.JobState.FAILED);
            assertTrue(job.isTerminal());
        }

        @Test
        void cancelledIsTerminal() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            job.setState(ScheduledJob.JobState.CANCELLED);
            assertTrue(job.isTerminal());
        }

        @Test
        void phaseYieldingIsNotTerminal() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            job.setState(ScheduledJob.JobState.PHASE_YIELDING);
            assertFalse(job.isTerminal());
        }
    }

    @Nested
    @DisplayName("External delegation fields")
    class ExternalDelegation {

        @Test
        void externalRefCanBeSet() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertNull(job.getExternalRef());

            job.setExternalRef("k8s-job-123");
            assertEquals("k8s-job-123", job.getExternalRef());
        }

        @Test
        void externallyDelegatedCanBeSet() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertFalse(job.isExternallyDelegated());

            job.setExternallyDelegated(true);
            assertTrue(job.isExternallyDelegated());
        }
    }

    @Nested
    @DisplayName("Comparable / priority ordering")
    class Ordering {

        @Test
        void higherPriorityComesFirst() {
            ScheduledJob high = buildJob("high", "ingest", 100);
            ScheduledJob low = buildJob("low", "ingest", 10);

            assertTrue(high.compareTo(low) < 0, "Higher priority should sort before lower");
            assertTrue(low.compareTo(high) > 0);
        }

        @Test
        void samePrioritySortsByQueuedTimeAsc() throws InterruptedException {
            ScheduledJob first = buildJob("first", "ingest", 50);
            Thread.sleep(5); // ensure different queuedAt
            ScheduledJob second = buildJob("second", "ingest", 50);

            assertTrue(first.compareTo(second) < 0, "Earlier queued should sort first");
        }

        @Test
        void promotionChangesPriority() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertEquals(50, job.getPriority());

            job.setPriority(100);
            assertEquals(100, job.getPriority());
        }
    }

    @Nested
    @DisplayName("toView")
    class ViewTests {

        @Test
        void viewContainsAllFields() {
            ScheduledJob job = ScheduledJob.builder()
                    .jobId("j1").jobType("ingest")
                    .description("Test ingest")
                    .resourceProfile(JobResourceProfile.gpuRequired("ingest", "Ingest", 2 * GB, 4 * GB))
                    .executor(noopExecutor)
                    .priority(75)
                    .metadata(Map.of("filePath", "/tmp/test.pdf"))
                    .build();

            job.setState(ScheduledJob.JobState.RUNNING);
            job.setCurrentPhase("EMBEDDING");
            job.setGpuHeld(true);
            job.setStartedAt(Instant.now());
            job.setExternalRef("ext-123");
            job.setExternallyDelegated(true);

            ScheduledJob.ScheduledJobView view = job.toView();

            assertEquals("j1", view.jobId());
            assertEquals("ingest", view.jobType());
            assertEquals("Test ingest", view.description());
            assertEquals("RUNNING", view.state());
            assertEquals("EMBEDDING", view.currentPhase());
            assertEquals(75, view.priority());
            assertTrue(view.gpuHeld());
            assertNotNull(view.queuedAt());
            assertNotNull(view.startedAt());
            assertNull(view.completedAt());
            assertTrue(view.requiresGpu());
            assertEquals(2048, view.peakGpuMemoryMb());
            assertEquals("/tmp/test.pdf", view.metadata().get("filePath"));
            assertEquals("ext-123", view.externalRef());
            assertTrue(view.externallyDelegated());
        }

        @Test
        void viewComputesDurationForRunningJob() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            job.setStartedAt(Instant.now().minusSeconds(10));
            job.setState(ScheduledJob.JobState.RUNNING);

            ScheduledJob.ScheduledJobView view = job.toView();
            assertTrue(view.durationMs() >= 9000, "Running job should have positive duration");
        }

        @Test
        void viewComputesWaitTimeForQueuedJob() {
            ScheduledJob job = buildJob("j1", "ingest", 50);

            ScheduledJob.ScheduledJobView view = job.toView();
            assertTrue(view.waitMs() >= 0, "Queued job should have non-negative wait time");
        }
    }

    @Nested
    @DisplayName("JobResult")
    class JobResultTests {

        @Test
        void successResult() {
            ScheduledJob.JobResult result = new ScheduledJob.JobResult(true, null, 1500);
            assertTrue(result.success());
            assertNull(result.errorMessage());
            assertEquals(1500, result.durationMs());
        }

        @Test
        void failureResult() {
            ScheduledJob.JobResult result = new ScheduledJob.JobResult(false, "OOM", 500);
            assertFalse(result.success());
            assertEquals("OOM", result.errorMessage());
        }
    }

    @Nested
    @DisplayName("ResultFuture")
    class ResultFutureTests {

        @Test
        void resultFutureIsNotCompletedOnCreation() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            assertFalse(job.getResultFuture().isDone());
        }

        @Test
        void resultFutureCanBeCompleted() {
            ScheduledJob job = buildJob("j1", "ingest", 50);
            ScheduledJob.JobResult result = new ScheduledJob.JobResult(true, null, 100);
            job.getResultFuture().complete(result);

            assertTrue(job.getResultFuture().isDone());
            assertEquals(result, job.getResultFuture().join());
        }
    }
}
