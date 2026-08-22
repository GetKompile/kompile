/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.coordination.ReusableResourcePool;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared lease pool for Kompile's folder-local model-serving subprocesses.
 *
 * <p>Chat, MCP crawl, and JVM harness callers acquire a compatible runtime instead of owning a
 * process directly. The default capacity is one resident model runtime per CLI JVM, preventing
 * independent local callers from loading duplicate GPU models. The limit and keep-warm window
 * are configurable through {@code kompile.model.pool.maxRuntimes} and
 * {@code kompile.model.pool.idleMs}.</p>
 */
public final class LocalServingRuntimePool {
    static final String IDLE_MILLIS_PROPERTY = "kompile.model.pool.idleMs";
    static final String MAX_RUNTIMES_PROPERTY = "kompile.model.pool.maxRuntimes";

    private static final long DEFAULT_IDLE_MILLIS = 120_000L;
    private static final int DEFAULT_MAX_RUNTIMES = 1;

    @FunctionalInterface
    interface RuntimeStarter {
        KompileLocalServingBootstrap.StartupResult start(RuntimeRequest request)
                throws KompileLocalServingBootstrap.BootstrapException;
    }

    record ArtifactIdentity(Path path, long size, long modifiedMillis) {
    }

    record RuntimeKey(
            String modelId,
            ArtifactIdentity model,
            ArtifactIdentity tokenizer,
            String runtimeOptions,
            String processConfiguration) {
    }

    record RuntimeRequest(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            Map<String, Object> runtimeOptions,
            int timeoutSeconds,
            RuntimeKey key) {
    }

    /** A request-scoped reference to a shared serving subprocess. */
    public static final class Lease implements AutoCloseable {
        private final ReusableResourcePool.Lease<KompileLocalServingBootstrap.StartupResult>
                delegate;

        private Lease(
                ReusableResourcePool.Lease<KompileLocalServingBootstrap.StartupResult> delegate) {
            this.delegate = delegate;
        }

        public String modelId() {
            return runtime().modelId();
        }

        public Path modelPath() {
            return runtime().modelPath();
        }

        public Path tokenizerPath() {
            return runtime().tokenizerPath();
        }

        public URI baseUrl() {
            return runtime().baseUrl();
        }

        public KompileLocalServingBootstrap.LauncherArtifact launcher() {
            return runtime().launcher();
        }

        public Process process() {
            return runtime().process();
        }

        public String subprocessRunId() {
            return runtime().subprocessRunId();
        }

        public Path logFile() {
            return runtime().logFile();
        }

        public boolean isAlive() {
            return delegate.isHealthy();
        }

        /** Shared monitor for callers that must serialize a complete request/response exchange. */
        public Object coordinationLock() {
            return runtime();
        }

        public void applyTo(ChatConfig config) {
            runtime().applyTo(config);
        }

        @Override
        public void close() {
            delegate.close();
        }

        private KompileLocalServingBootstrap.StartupResult runtime() {
            return delegate.resource();
        }
    }

    private static final RuntimeStarter DEFAULT_STARTER = request -> {
        ChatConfig config = new ChatConfig("kompile-local", null, request.modelId(), null);
        if (request.modelPath() == null) {
            return KompileLocalServingBootstrap.ensureReady(config, request.timeoutSeconds());
        }
        return KompileLocalServingBootstrap.ensureReady(
                config,
                request.timeoutSeconds(),
                request.modelId(),
                request.modelPath(),
                request.tokenizerPath(),
                request.runtimeOptions());
    };

    private static volatile RuntimeStarter starter = DEFAULT_STARTER;
    private static volatile Long idleMillisOverride;
    private static volatile Integer maxRuntimesOverride;

    private static final ReusableResourcePool<
            RuntimeKey, KompileLocalServingBootstrap.StartupResult> POOL =
            new ReusableResourcePool<>(
                    "local-model-runtime-pool",
                    LocalServingRuntimePool::idleMillis,
                    LocalServingRuntimePool::maxRuntimes,
                    LocalServingRuntimePool::isAlive,
                    KompileLocalServingBootstrap.StartupResult::close);

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                POOL::clear, "local-model-runtime-pool-shutdown"));
    }

    private LocalServingRuntimePool() {
    }

    public static Lease acquire(ChatConfig config, int timeoutSeconds)
            throws KompileLocalServingBootstrap.BootstrapException {
        Objects.requireNonNull(config, "config");
        String requestedModel = normalizeModelId(config.getModel());
        return acquire(request(requestedModel, null, null, Map.of(), timeoutSeconds));
    }

    public static Lease acquire(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            Map<String, Object> runtimeOptions,
            int timeoutSeconds) throws KompileLocalServingBootstrap.BootstrapException {
        return acquire(request(
                normalizeModelId(modelId), modelPath, tokenizerPath,
                runtimeOptions, timeoutSeconds));
    }

    private static Lease acquire(RuntimeRequest request)
            throws KompileLocalServingBootstrap.BootstrapException {
        try {
            long waitMillis = TimeUnit.SECONDS.toMillis(Math.max(1, request.timeoutSeconds()));
            return new Lease(POOL.acquire(
                    request.key(),
                    () -> starter.start(request),
                    waitMillis));
        } catch (KompileLocalServingBootstrap.BootstrapException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new KompileLocalServingBootstrap.BootstrapException(
                    "Interrupted while waiting for a local model runtime lease", failure);
        } catch (Exception failure) {
            throw new KompileLocalServingBootstrap.BootstrapException(
                    "Could not acquire a local model runtime lease: " + failure.getMessage(),
                    failure);
        }
    }

    private static RuntimeRequest request(
            String modelId,
            Path modelPath,
            Path tokenizerPath,
            Map<String, Object> runtimeOptions,
            int timeoutSeconds) {
        Path normalizedModel = normalize(modelPath);
        Path normalizedTokenizer = normalize(tokenizerPath);
        Map<String, Object> options = runtimeOptions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(runtimeOptions));
        RuntimeKey key = new RuntimeKey(
                modelId,
                artifact(normalizedModel),
                artifact(normalizedTokenizer),
                canonical(runtimeIdentityOptions(options)),
                processConfiguration());
        return new RuntimeRequest(
                modelId, normalizedModel, normalizedTokenizer,
                options, Math.max(1, timeoutSeconds), key);
    }

    private static Map<String, Object> runtimeIdentityOptions(Map<String, Object> options) {
        if (options == null || options.isEmpty()) return Map.of();
        Map<String, Object> identity = new LinkedHashMap<>(options);
        // Correlation changes per lease/request and must never force a duplicate resident model.
        identity.remove("crawlJobId");
        identity.remove("knowledgeBaseId");
        identity.remove("projectRoot");
        return identity;
    }

    private static boolean isAlive(KompileLocalServingBootstrap.StartupResult runtime) {
        return runtime != null && runtime.process() != null && runtime.process().isAlive();
    }

    private static String normalizeModelId(String modelId) {
        return modelId == null || modelId.isBlank()
                ? KompileLocalServingBootstrap.DEFAULT_MODEL : modelId.trim();
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static ArtifactIdentity artifact(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return new ArtifactIdentity(
                    path,
                    Files.isRegularFile(path) ? Files.size(path) : -1L,
                    Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1L);
        } catch (Exception ignored) {
            return new ArtifactIdentity(path, -1L, -1L);
        }
    }

    private static String processConfiguration() {
        TreeMap<String, String> values = new TreeMap<>();
        Properties properties = System.getProperties();
        for (String property : List.of(
                KompileLocalServingBootstrap.SERVING_EXECUTABLE_PROPERTY,
                KompileLocalServingBootstrap.SERVING_JAR_PROPERTY,
                KompileLocalServingBootstrap.JAVA_EXECUTABLE_PROPERTY,
                KompileLocalServingBootstrap.HEAP_PROPERTY,
                KompileLocalServingBootstrap.PORT_PROPERTY,
                "kompile.home",
                "user.home")) {
            values.put("property:" + property, properties.getProperty(property, ""));
        }
        Map<String, String> environment = System.getenv();
        for (String variable : List.of(
                KompileLocalServingBootstrap.SERVING_EXECUTABLE_ENV,
                KompileLocalServingBootstrap.SERVING_JAR_ENV,
                KompileLocalServingBootstrap.MODEL_ENV,
                KompileLocalServingBootstrap.TOKENIZER_ENV,
                "KOMPILE_HOME",
                "JAVA_HOME",
                "PATH")) {
            values.put("environment:" + variable, environment.getOrDefault(variable, ""));
        }
        return canonical(values);
    }

    private static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        appendCanonical(out, value);
        return out.toString();
    }

    private static void appendCanonical(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Path path) {
            appendScalar(out, "path", path.toAbsolutePath().normalize().toString());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                appendScalar(out, "key", String.valueOf(entry.getKey()));
                appendCanonical(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            for (Object item : values) {
                appendCanonical(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendCanonical(out, java.lang.reflect.Array.get(value, i));
            }
            out.append(']');
        } else {
            appendScalar(out, value.getClass().getName(), String.valueOf(value));
        }
    }

    private static void appendScalar(StringBuilder out, String type, String value) {
        out.append(type.length()).append(':').append(type)
                .append(value.length()).append(':').append(value);
    }

    private static long idleMillis() {
        Long override = idleMillisOverride;
        return override != null
                ? override : Long.getLong(IDLE_MILLIS_PROPERTY, DEFAULT_IDLE_MILLIS);
    }

    private static int maxRuntimes() {
        Integer override = maxRuntimesOverride;
        return override != null
                ? override : Integer.getInteger(MAX_RUNTIMES_PROPERTY, DEFAULT_MAX_RUNTIMES);
    }

    static void resetForTests() {
        POOL.clear();
        starter = DEFAULT_STARTER;
        idleMillisOverride = null;
        maxRuntimesOverride = null;
    }

    static int pooledCount() {
        return POOL.pooledCount();
    }

    static void setIdleMillisForTests(long millis) {
        idleMillisOverride = millis;
    }

    static void setMaxRuntimesForTests(int maximum) {
        maxRuntimesOverride = maximum;
    }

    static void setStarterForTests(RuntimeStarter testStarter) {
        starter = Objects.requireNonNull(testStarter, "testStarter");
    }
}
