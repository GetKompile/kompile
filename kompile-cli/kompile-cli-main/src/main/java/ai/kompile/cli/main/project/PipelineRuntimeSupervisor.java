/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.coordination.ReusableResourcePool;
import ai.kompile.pipeline.serving.definition.PipelineDefinitionIdentity;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.launcher.PipelineRuntimeSession;
import ai.kompile.pipeline.serving.launcher.PipelineSubprocessLauncher;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Long-lived stdio-MCP owner for reusable, isolated pipeline runtime processes.
 * Callers hold leases; they never resolve executables or own child processes directly.
 */
public final class PipelineRuntimeSupervisor {
    static final String IDLE_MILLIS_PROPERTY = "kompile.pipeline.runtime.pool.idleMs";
    static final String MAX_RUNTIMES_PROPERTY = "kompile.pipeline.runtime.pool.maxRuntimes";
    private static final long DEFAULT_IDLE_MILLIS = 120_000L;
    private static final int DEFAULT_MAX_RUNTIMES = 2;
    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    record RuntimeKey(String definitionDigest,
                      String resolvedModels,
                      String runtimeRequirements,
                      String servingConfig) {
    }

    @FunctionalInterface
    interface RuntimeStarter {
        ManagedRuntime start(UnifiedPipelineDefinition definition) throws Exception;
    }

    interface ManagedRuntime extends AutoCloseable {
        RunningExecution start(Map<String, Object> input) throws Exception;
        default RunningExecution start(Map<String, Object> input,
                                       Consumer<PipelineRuntimeProtocol.Message> progress) throws Exception {
            return start(input);
        }
        boolean isAlive();
        long pid();
        @Override void close();
    }

    /** A model execution controlled by the same stdio session that produced it. */
    public interface RunningExecution {
        Map<String, Object> await(Duration timeout) throws Exception;
        boolean cancel(Duration timeout);
    }

    private record SessionRuntime(PipelineRuntimeSession session) implements ManagedRuntime {
        @Override
        public RunningExecution start(Map<String, Object> input) throws Exception {
            return start(input, null);
        }

        @Override
        public RunningExecution start(Map<String, Object> input,
                                      Consumer<PipelineRuntimeProtocol.Message> progress) throws Exception {
            PipelineRuntimeSession.Execution execution = session.start(input, progress);
            return new RunningExecution() {
                @Override
                public Map<String, Object> await(Duration timeout) throws Exception {
                    return execution.await(timeout);
                }

                @Override
                public boolean cancel(Duration timeout) {
                    return execution.cancel(timeout);
                }
            };
        }

        @Override
        public boolean isAlive() {
            return session.isAlive();
        }

        @Override
        public long pid() {
            return session.pid();
        }

        @Override
        public void close() {
            session.close();
        }
    }

    public static final class Lease implements AutoCloseable {
        private final ReusableResourcePool.Lease<ManagedRuntime> delegate;

        private Lease(ReusableResourcePool.Lease<ManagedRuntime> delegate) {
            this.delegate = delegate;
        }

        public Map<String, Object> execute(Map<String, Object> input, Duration timeout)
                throws Exception {
            return execute(input, timeout, null);
        }

        public Map<String, Object> execute(Map<String, Object> input, Duration timeout,
                                           Consumer<PipelineRuntimeProtocol.Message> progress)
                throws Exception {
            RunningExecution execution = start(input, progress);
            try {
                return execution.await(timeout);
            } catch (InterruptedException interrupted) {
                execution.cancel(Duration.ofSeconds(2));
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }

        public RunningExecution start(Map<String, Object> input) throws Exception {
            return delegate.resource().start(input);
        }

        public RunningExecution start(Map<String, Object> input,
                                      Consumer<PipelineRuntimeProtocol.Message> progress) throws Exception {
            return delegate.resource().start(input, progress);
        }

        public boolean isHealthy() {
            return delegate.isHealthy();
        }

        public long pid() {
            return delegate.resource().pid();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final RuntimeStarter DEFAULT_STARTER = definition ->
            new SessionRuntime(new PipelineSubprocessLauncher().launch(definition));
    private static volatile RuntimeStarter starter = DEFAULT_STARTER;

    private static final ReusableResourcePool<RuntimeKey, ManagedRuntime> POOL =
            new ReusableResourcePool<>(
                    "pipeline-runtime-pool",
                    PipelineRuntimeSupervisor::idleMillis,
                    PipelineRuntimeSupervisor::maxRuntimes,
                    runtime -> runtime != null && runtime.isAlive(),
                    ManagedRuntime::close);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                POOL::clear, "pipeline-runtime-pool-shutdown"));
    }

    private PipelineRuntimeSupervisor() {
    }

    public static Lease acquire(UnifiedPipelineDefinition definition, Duration timeout)
            throws Exception {
        Objects.requireNonNull(definition, "definition");
        RuntimeKey key = key(definition);
        long waitMillis = Math.max(1L, timeout.toMillis());
        return new Lease(POOL.acquire(key, () -> starter.start(definition), waitMillis));
    }

    public static Map<String, Object> execute(UnifiedPipelineDefinition definition,
                                              Map<String, Object> input,
                                              Duration timeout) throws Exception {
        return execute(definition, input, timeout, null);
    }

    public static Map<String, Object> execute(UnifiedPipelineDefinition definition,
                                              Map<String, Object> input,
                                              Duration timeout,
                                              Consumer<PipelineRuntimeProtocol.Message> progress)
            throws Exception {
        try (Lease runtime = acquire(definition, timeout)) {
            return runtime.execute(input, timeout, progress);
        }
    }

    /** Preserve subprocess diagnostics while providing the same shape for parent-side failures. */
    public static Map<String, Object> diagnostic(Throwable failure, String fallbackStage) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof PipelineRuntimeSession.RuntimeFailure runtimeFailure) {
                return runtimeFailure.diagnostic();
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return PipelineRuntimeProtocol.diagnostic(fallbackStage, failure);
    }

    static RuntimeKey key(UnifiedPipelineDefinition definition) {
        String digest = definition.getContentDigest();
        if (digest == null || digest.isBlank()) {
            digest = PipelineDefinitionIdentity.contentDigest(MAPPER, definition);
            definition.setContentDigest(digest);
        }
        return new RuntimeKey(
                digest,
                canonical(definition.getResolvedModels()),
                canonical(definition.getRuntimeRequirements()),
                canonical(definition.getServing()));
    }

    private static String canonical(Object value) {
        try {
            return MAPPER.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to identify pipeline runtime configuration", e);
        }
    }

    private static long idleMillis() {
        return longProperty(IDLE_MILLIS_PROPERTY, DEFAULT_IDLE_MILLIS);
    }

    private static int maxRuntimes() {
        return (int) Math.max(1L, longProperty(MAX_RUNTIMES_PROPERTY, DEFAULT_MAX_RUNTIMES));
    }

    private static long longProperty(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static void setStarterForTests(RuntimeStarter replacement) {
        starter = replacement == null ? DEFAULT_STARTER : replacement;
        POOL.clear();
    }

    static void clearForTests() {
        POOL.clear();
        starter = DEFAULT_STARTER;
    }
}
