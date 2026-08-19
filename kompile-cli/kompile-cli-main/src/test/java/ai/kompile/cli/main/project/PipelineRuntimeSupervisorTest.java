package ai.kompile.cli.main.project;

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PipelineRuntimeSupervisorTest {
    @AfterEach
    void reset() {
        PipelineRuntimeSupervisor.clearForTests();
        System.clearProperty(PipelineRuntimeSupervisor.IDLE_MILLIS_PROPERTY);
    }

    @Test
    void compatibleCallsReuseOneRuntime() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        FakeRuntime runtime = new FakeRuntime();
        PipelineRuntimeSupervisor.setStarterForTests(definition -> {
            starts.incrementAndGet();
            return runtime;
        });
        UnifiedPipelineDefinition definition = definition("same");

        try (PipelineRuntimeSupervisor.Lease first =
                     PipelineRuntimeSupervisor.acquire(definition, Duration.ofSeconds(1))) {
            assertEquals("one", first.execute(Map.of("text", "one"),
                    Duration.ofSeconds(1)).get("text"));
        }
        try (PipelineRuntimeSupervisor.Lease second =
                     PipelineRuntimeSupervisor.acquire(definition, Duration.ofSeconds(1))) {
            assertEquals("two", second.execute(Map.of("text", "two"),
                    Duration.ofSeconds(1)).get("text"));
            assertEquals(42L, second.pid());
        }

        assertEquals(1, starts.get());
        assertFalse(runtime.closed.get());
    }

    @Test
    void executionCancellationUsesTheManagedRuntimeSession() throws Exception {
        FakeRuntime runtime = new FakeRuntime();
        PipelineRuntimeSupervisor.setStarterForTests(definition -> runtime);

        try (PipelineRuntimeSupervisor.Lease lease = PipelineRuntimeSupervisor.acquire(
                definition("cancel"), Duration.ofSeconds(1))) {
            PipelineRuntimeSupervisor.RunningExecution execution =
                    lease.start(Map.of("text", "cancel me"));
            assertTrue(execution.cancel(Duration.ofSeconds(1)));
            assertTrue(runtime.cancelled.get());
            assertFalse(runtime.isAlive(), "a cancelled native runtime must be tainted");
        }
    }

    @Test
    void cancelledRuntimeIsNotReturnedToThePool() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<FakeRuntime> first = new AtomicReference<>();
        PipelineRuntimeSupervisor.setStarterForTests(definition -> {
            FakeRuntime runtime = new FakeRuntime();
            first.compareAndSet(null, runtime);
            starts.incrementAndGet();
            return runtime;
        });

        try (PipelineRuntimeSupervisor.Lease lease = PipelineRuntimeSupervisor.acquire(
                definition("evict"), Duration.ofSeconds(1))) {
            assertTrue(lease.start(Map.of("text", "cancel")).cancel(Duration.ofSeconds(1)));
        }
        try (PipelineRuntimeSupervisor.Lease ignored = PipelineRuntimeSupervisor.acquire(
                definition("evict"), Duration.ofSeconds(1))) {
            assertEquals(2, starts.get());
        }
        assertFalse(first.get().isAlive());
    }

    @Test
    void fallbackDiagnosticsRetainInitializerRootCause() {
        Map<String, Object> diagnostic = PipelineRuntimeSupervisor.diagnostic(
                new ExceptionInInitializerError(
                        new IllegalStateException("native backend failed")),
                "PIPELINE_TEST");

        assertEquals("PIPELINE_TEST", diagnostic.get("failureStage"));
        assertEquals("native backend failed", diagnostic.get("summary"));
        assertEquals(IllegalStateException.class.getName(), diagnostic.get("rootCauseClass"));
        assertFalse(((List<?>) diagnostic.get("exceptionChain")).isEmpty());
        assertFalse(((List<?>) diagnostic.get("stackTrace")).isEmpty());
    }

    @Test
    void definitionChangesInvalidateReuse() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        PipelineRuntimeSupervisor.setStarterForTests(definition -> {
            starts.incrementAndGet();
            return new FakeRuntime();
        });

        try (var ignored = PipelineRuntimeSupervisor.acquire(
                definition("first"), Duration.ofSeconds(1))) { }
        try (var ignored = PipelineRuntimeSupervisor.acquire(
                definition("second"), Duration.ofSeconds(1))) { }

        assertEquals(2, starts.get());
    }

    private UnifiedPipelineDefinition definition(String description) {
        return UnifiedPipelineDefinition.builder()
                .pipelineId("reuse")
                .displayName("Reuse")
                .description(description)
                .kind(UnifiedPipelineDefinition.PipelineKind.GENERIC)
                .topology(UnifiedPipelineDefinition.ExecutionTopology.SEQUENCE)
                .pipelineSpec(Map.of(
                        "@class", "ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline",
                        "id", "reuse", "steps", List.of()))
                .build();
    }

    private static final class FakeRuntime implements PipelineRuntimeSupervisor.ManagedRuntime {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public PipelineRuntimeSupervisor.RunningExecution start(Map<String, Object> input) {
            return new PipelineRuntimeSupervisor.RunningExecution() {
                @Override
                public Map<String, Object> await(Duration timeout) {
                    return input;
                }

                @Override
                public boolean cancel(Duration timeout) {
                    cancelled.set(true);
                    closed.set(true);
                    return true;
                }
            };
        }

        @Override
        public boolean isAlive() {
            return !closed.get();
        }

        @Override
        public long pid() {
            return 42L;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
