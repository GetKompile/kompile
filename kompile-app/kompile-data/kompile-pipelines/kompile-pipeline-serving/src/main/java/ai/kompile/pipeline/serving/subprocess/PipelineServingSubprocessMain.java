/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.pipeline.serving.subprocess;

import ai.kompile.app.config.NativeLibraryResolver;
import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol;
import ai.kompile.pipeline.serving.protocol.PipelineRuntimeProtocol.Message;
import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import ai.kompile.utils.NativeImageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nd4j.common.config.ND4JSystemProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point for the pipeline serving subprocess.
 *
 * <p>This class follows the established subprocess pattern from IngestSubprocessMain
 * and TrainingSubprocessMain:</p>
 * <ol>
 *   <li>Redirect System.out to System.err so stdout remains protocol-only</li>
 *   <li>Read PipelineServingSubprocessArgs from args[0] JSON file</li>
 *   <li>Deserialize and validate the pipeline</li>
 *   <li>Create its executor</li>
 *   <li>Serve reusable execution requests through the versioned stdio protocol</li>
 * </ol>
 */
public class PipelineServingSubprocessMain {

    private static final Logger log = LoggerFactory.getLogger(PipelineServingSubprocessMain.class);

    /*
     * JNI_OnLoad in the side-loaded JavaCPP bridge resolves this class through
     * FindClass. Keep an explicit class-literal edge so GraalVM retains it even
     * when a particular pipeline definition has no statically visible model step.
     */
    private static final Class<?> JAVACPP_LOADER_CLASS = org.bytedeco.javacpp.Loader.class;
    private static final String IMPORTER_CLASS_GRAPH_SCAN_RESOURCE = "sdx-classgraph-scan.json";

    // Capture the real stdout BEFORE redirecting
    private static final PrintStream ORIGINAL_STDOUT = System.out;

    static {
        // Redirect System.out -> System.err so that all normal logging/prints
        // go to stderr, leaving stdout exclusively for PIPELINE_RUNTIME: protocol lines.
        System.setOut(System.err);
    }

    public static void main(String[] args) {
        if (JAVACPP_LOADER_CLASS == null) {
            throw new IllegalStateException("JavaCPP Loader is unavailable");
        }
        configureImporterClassGraphScan();
        NativeLibraryResolver.bootstrapModelExecutionOrThrow();
        if (args.length < 1) {
            System.err.println("Usage: PipelineServingSubprocessMain <args-json-file>");
            System.exit(1);
        }

        try {
            PipelineServingSubprocessArgs subprocessArgs =
                    PipelineServingSubprocessArgs.fromFile(Path.of(args[0]));

            ObjectMapper mapper = ObjectMappers.getJsonMapper();
            UnifiedPipelineDefinition definition = mapper.readValue(
                    subprocessArgs.pipelineDefinitionJson(),
                    UnifiedPipelineDefinition.class
            );

            // Reconstruct the framework Pipeline from the pipelineSpec map
            Pipeline pipeline = mapper.convertValue(definition.getPipelineSpec(), Pipeline.class);
            pipeline.validate();

            PipelineExecutor executor = pipeline.createExecutor();

            // The only execution contract is the persistent stdio runtime. It owns the executor.
            serveStdio(executor, mapper, definition);
            System.exit(0);

        } catch (Throwable t) {
            log.error("Pipeline subprocess fatal error: {}", t.getMessage(), t);
            try {
                PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                        PipelineRuntimeProtocol.error(null, null, t));
            } catch (Exception ignored) {
                // The process exit remains the final failure signal if stdout is unavailable.
            }
            System.exit(1);
        }
    }

    private static void configureImporterClassGraphScan() {
        if (!NativeImageInfo.isRunningInNativeImage()
                || System.getProperty(ND4JSystemProperties.CLASS_GRAPH_SCAN_RESOURCES) != null) {
            return;
        }
        if (PipelineServingSubprocessMain.class.getResource(
                "/" + IMPORTER_CLASS_GRAPH_SCAN_RESOURCE) == null) {
            throw new IllegalStateException("Native pipeline runtime is missing "
                    + IMPORTER_CLASS_GRAPH_SCAN_RESOURCE);
        }
        System.setProperty(ND4JSystemProperties.CLASS_GRAPH_SCAN_RESOURCES,
                IMPORTER_CLASS_GRAPH_SCAN_RESOURCE);
    }

    /** Persistent, reusable runtime controlled by the parent exclusively over stdio. */
    private static void serveStdio(PipelineExecutor initialExecutor,
                                   ObjectMapper mapper,
                                   UnifiedPipelineDefinition initialDefinition) throws Exception {
        AtomicReference<PipelineExecutor> executorRef = new AtomicReference<>(initialExecutor);
        AtomicReference<UnifiedPipelineDefinition> definitionRef =
                new AtomicReference<>(initialDefinition);
        ConcurrentHashMap<String, Future<?>> executions = new ConcurrentHashMap<>();
        ExecutorService executionThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pipeline-runtime-execution");
            thread.setDaemon(true);
            return thread;
        });

        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT, PipelineRuntimeProtocol.message(
                PipelineRuntimeProtocol.READY, null, initialDefinition.getPipelineId(), Map.of(
                        "pid", ProcessHandle.current().pid(),
                        "protocolVersion", PipelineRuntimeProtocol.VERSION,
                        "definitionVersion", initialDefinition.getDefinitionVersion(),
                        "contentDigest", String.valueOf(initialDefinition.getContentDigest()))));

        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                System.in, StandardCharsets.UTF_8))) {
            String line;
            boolean running = true;
            while (running && (line = input.readLine()) != null) {
                if (!line.startsWith(PipelineRuntimeProtocol.PREFIX)) continue;
                Message message;
                try {
                    message = PipelineRuntimeProtocol.decode(line);
                } catch (Exception invalid) {
                    PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                            PipelineRuntimeProtocol.error(null,
                                    definitionRef.get().getPipelineId(), invalid));
                    continue;
                }

                switch (message.type()) {
                    case PipelineRuntimeProtocol.EXECUTE -> {
                        String requestId = message.requestId();
                        if (requestId == null || requestId.isBlank()) {
                            PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                    PipelineRuntimeProtocol.error(null,
                                            definitionRef.get().getPipelineId(),
                                            new IllegalArgumentException("EXECUTE requires requestId")));
                            continue;
                        }
                        FutureTask<Void> future = new FutureTask<>(() -> {
                            long start = System.currentTimeMillis();
                            try {
                                PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                        PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.PROGRESS,
                                                requestId, definitionRef.get().getPipelineId(),
                                                Map.of("phase", "EXECUTING", "percent", 0)));
                                Object rawInput = message.payload().get("input");
                                @SuppressWarnings("unchecked")
                                Map<String, Object> request = rawInput instanceof Map<?, ?> values
                                        ? (Map<String, Object>) values : Map.of();
                                Data data = Data.fromMap(withDefinitionContext(
                                        request, definitionRef.get()));
                                Data output = executorRef.get().exec(data);
                                PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                        PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.RESULT,
                                                requestId, definitionRef.get().getPipelineId(), Map.of(
                                                        "durationMs", System.currentTimeMillis() - start,
                                                        "output", output.toMap())));
                            } catch (Throwable failure) {
                                try {
                                    PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                            PipelineRuntimeProtocol.error(requestId,
                                                    definitionRef.get().getPipelineId(), failure));
                                } catch (Exception ignored) {
                                }
                            }
                            return null;
                        }) {
                            @Override
                            protected void done() {
                                executions.remove(requestId, this);
                            }
                        };
                        Future<?> previous = executions.putIfAbsent(requestId, future);
                        if (previous != null) {
                            PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                    PipelineRuntimeProtocol.error(requestId,
                                            definitionRef.get().getPipelineId(),
                                            new IllegalArgumentException(
                                                    "Duplicate execution requestId " + requestId)));
                        } else {
                            executionThread.execute(future);
                        }
                    }
                    case PipelineRuntimeProtocol.CANCEL -> {
                        String target = String.valueOf(message.payload().get("targetRequestId"));
                        Future<?> future = executions.remove(target);
                        boolean cancelled = future != null && future.cancel(true);
                        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.CANCELLED,
                                        message.requestId(), definitionRef.get().getPipelineId(),
                                        Map.of("targetRequestId", target, "cancelled", cancelled)));
                    }
                    case PipelineRuntimeProtocol.HEALTH ->
                            PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                    PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.HEALTHY,
                                            message.requestId(), definitionRef.get().getPipelineId(), Map.of(
                                                    "pid", ProcessHandle.current().pid(),
                                                    "activeExecutions", executions.size())));
                    case PipelineRuntimeProtocol.RESET -> {
                        cancelAll(executions);
                        PipelineExecutor replacement = pipeline(definitionRef.get(), mapper).createExecutor();
                        PipelineExecutor previous = executorRef.getAndSet(replacement);
                        previous.close();
                        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.RESET_DONE,
                                        message.requestId(), definitionRef.get().getPipelineId(), Map.of()));
                    }
                    case PipelineRuntimeProtocol.LOAD_PIPELINE -> {
                        if (!executions.isEmpty()) {
                            PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                    PipelineRuntimeProtocol.error(message.requestId(),
                                            definitionRef.get().getPipelineId(),
                                            new IllegalStateException(
                                                    "Cannot load a pipeline while executions are active")));
                            continue;
                        }
                        Object value = message.payload().get("definition");
                        UnifiedPipelineDefinition loaded = mapper.convertValue(
                                value, UnifiedPipelineDefinition.class);
                        PipelineExecutor replacement = pipeline(loaded, mapper).createExecutor();
                        PipelineExecutor previous = executorRef.getAndSet(replacement);
                        previous.close();
                        definitionRef.set(loaded);
                        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.LOADED,
                                        message.requestId(), loaded.getPipelineId(), Map.of(
                                                "definitionVersion", loaded.getDefinitionVersion(),
                                                "contentDigest", String.valueOf(loaded.getContentDigest()))));
                    }
                    case PipelineRuntimeProtocol.UNLOAD -> {
                        cancelAll(executions);
                        PipelineExecutor current = executorRef.getAndSet(null);
                        if (current != null) current.close();
                        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.UNLOADED,
                                        message.requestId(), definitionRef.get().getPipelineId(), Map.of()));
                    }
                    case PipelineRuntimeProtocol.SHUTDOWN -> {
                        PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                                PipelineRuntimeProtocol.message(PipelineRuntimeProtocol.SHUTDOWN_COMPLETE,
                                        message.requestId(), definitionRef.get().getPipelineId(), Map.of()));
                        running = false;
                    }
                    default -> PipelineRuntimeProtocol.write(ORIGINAL_STDOUT,
                            PipelineRuntimeProtocol.error(message.requestId(),
                                    definitionRef.get().getPipelineId(),
                                    new IllegalArgumentException(
                                            "Unsupported runtime message type " + message.type())));
                }
            }
        } finally {
            cancelAll(executions);
            executionThread.shutdownNow();
            PipelineExecutor current = executorRef.getAndSet(null);
            if (current != null) current.close();
        }
    }

    private static Pipeline pipeline(UnifiedPipelineDefinition definition, ObjectMapper mapper) {
        Pipeline pipeline = mapper.convertValue(definition.getPipelineSpec(), Pipeline.class);
        pipeline.validate();
        return pipeline;
    }

    private static void cancelAll(ConcurrentHashMap<String, Future<?>> executions) {
        executions.values().forEach(future -> future.cancel(true));
        executions.clear();
    }

    static Map<String, Object> withDefinitionContext(
            Map<String, Object> request,
            UnifiedPipelineDefinition definition) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (request != null) {
            input.putAll(request);
        }
        if (definition == null) {
            return input;
        }
        if (definition.getModelSetId() != null && !definition.getModelSetId().isBlank()) {
            input.put("modelSetId", definition.getModelSetId());
        }
        if (definition.getModelBindings() != null && !definition.getModelBindings().isEmpty()) {
            input.put("modelBindings", definition.getModelBindings());
        }
        if (definition.getResolvedModels() != null && !definition.getResolvedModels().isEmpty()) {
            input.put("resolvedModels", definition.getResolvedModels());
        }
        return input;
    }
}
