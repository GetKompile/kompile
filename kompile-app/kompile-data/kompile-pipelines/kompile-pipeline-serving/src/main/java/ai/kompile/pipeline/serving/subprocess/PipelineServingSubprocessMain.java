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

import ai.kompile.pipeline.serving.definition.UnifiedPipelineDefinition;
import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point for the pipeline serving subprocess.
 *
 * <p>This class follows the established subprocess pattern from IngestSubprocessMain
 * and TrainingSubprocessMain:</p>
 * <ol>
 *   <li>Redirect System.out to System.err (stdout reserved for PIPELINE_MSG: protocol)</li>
 *   <li>Read PipelineServingSubprocessArgs from args[0] JSON file</li>
 *   <li>Create reporter, start heartbeat</li>
 *   <li>Deserialize and validate pipeline</li>
 *   <li>Create executor</li>
 *   <li>Dispatch on execution mode (ONE_SHOT or PERSISTENT_SERVING)</li>
 * </ol>
 */
public class PipelineServingSubprocessMain {

    private static final Logger log = LoggerFactory.getLogger(PipelineServingSubprocessMain.class);

    // Capture the real stdout BEFORE redirecting
    private static final PrintStream ORIGINAL_STDOUT = System.out;

    static {
        // Redirect System.out -> System.err so that all normal logging/prints
        // go to stderr, leaving stdout exclusively for PIPELINE_MSG: protocol lines.
        System.setOut(System.err);
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: PipelineServingSubprocessMain <args-json-file>");
            System.exit(1);
        }

        PipelineServingSubprocessArgs subprocessArgs = null;
        PipelineServingProgressReporter reporter = null;

        try {
            // 1. Read args from JSON file
            subprocessArgs = PipelineServingSubprocessArgs.fromFile(Path.of(args[0]));

            // 2. Create reporter
            reporter = new PipelineServingProgressReporter(
                    subprocessArgs.taskId(),
                    ORIGINAL_STDOUT,
                    subprocessArgs.heartbeatIntervalMs()
            );
            reporter.startHeartbeat();

            // 3. Load and validate pipeline
            reporter.reportPhaseTransition(null, "LOADING_PIPELINE", 0);
            long phaseStart = System.currentTimeMillis();

            ObjectMapper mapper = ObjectMappers.getJsonMapper();
            UnifiedPipelineDefinition definition = mapper.readValue(
                    subprocessArgs.pipelineDefinitionJson(),
                    UnifiedPipelineDefinition.class
            );

            // Reconstruct the framework Pipeline from the pipelineSpec map
            Pipeline pipeline = mapper.convertValue(definition.getPipelineSpec(), Pipeline.class);
            pipeline.validate();

            long loadDuration = System.currentTimeMillis() - phaseStart;
            reporter.reportPhaseTransition("LOADING_PIPELINE", "INITIALIZING_STEPS", loadDuration);

            // 4. Create executor (initializes all step runners)
            phaseStart = System.currentTimeMillis();
            reporter.reportProgress("INITIALIZING_STEPS", 50, "Creating pipeline executor...");

            PipelineExecutor executor = pipeline.createExecutor();

            long initDuration = System.currentTimeMillis() - phaseStart;
            reporter.reportPhaseTransition("INITIALIZING_STEPS", "READY", initDuration);

            // 5. Dispatch on execution mode
            String mode = subprocessArgs.executionMode();
            if (PipelineServingSubprocessArgs.MODE_ONE_SHOT.equals(mode)) {
                executeOneShot(executor, subprocessArgs, reporter, mapper);
            } else if (PipelineServingSubprocessArgs.MODE_PERSISTENT_SERVING.equals(mode)) {
                servePersistently(executor, subprocessArgs, reporter, mapper, definition);
            } else {
                throw new IllegalArgumentException("Unknown execution mode: " + mode);
            }

            // 6. Clean up
            executor.close();
            reporter.close();
            System.exit(0);

        } catch (Throwable t) {
            log.error("Pipeline subprocess fatal error: {}", t.getMessage(), t);
            if (reporter != null) {
                reporter.reportFailed(
                        "INITIALIZATION",
                        t
                );
                reporter.close();
            }
            System.exit(1);
        }
    }

    private static void executeOneShot(PipelineExecutor executor,
                                       PipelineServingSubprocessArgs args,
                                       PipelineServingProgressReporter reporter,
                                       ObjectMapper mapper) throws Exception {
        reporter.reportPhaseTransition("READY", "EXECUTING", 0);
        String requestId = UUID.randomUUID().toString();

        long start = System.currentTimeMillis();

        // Parse input data
        Data inputData;
        if (args.requestDataJson() != null && !args.requestDataJson().isBlank()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> inputMap = mapper.readValue(args.requestDataJson(), Map.class);
            inputData = Data.fromMap(inputMap);
        } else {
            inputData = Data.empty();
        }

        // Execute
        Data output = executor.exec(inputData);
        long duration = System.currentTimeMillis() - start;

        // Report completion
        reporter.reportCompleted(requestId, duration, output.toMap());
    }

    private static void servePersistently(PipelineExecutor executor,
                                          PipelineServingSubprocessArgs args,
                                          PipelineServingProgressReporter reporter,
                                          ObjectMapper mapper,
                                          UnifiedPipelineDefinition definition) throws Exception {
        int port = args.servingPort() > 0 ? args.servingPort() :
                (definition.getServing() != null && definition.getServing().getPort() > 0 ?
                        definition.getServing().getPort() : 9090);

        // Hold executor reference for request handler
        AtomicReference<PipelineExecutor> executorRef = new AtomicReference<>(executor);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // POST /predict - Execute pipeline
        server.createContext("/predict", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String msg = "{\"error\":\"Only POST is supported\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, msg.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String requestId = UUID.randomUUID().toString();
            reporter.requestStarted();
            long start = System.currentTimeMillis();

            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Data inputData;
                if (body.length > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputMap = mapper.readValue(body, Map.class);
                    inputData = Data.fromMap(inputMap);
                } else {
                    inputData = Data.empty();
                }

                Data output = executorRef.get().exec(inputData);
                long duration = System.currentTimeMillis() - start;

                Map<String, Object> outputMap = output.toMap();

                // Report on stdout protocol
                reporter.reportRequestResult(requestId, true, duration, outputMap, null);

                // Return HTTP response
                Map<String, Object> result = Map.of(
                        "requestId", requestId,
                        "status", "COMPLETED",
                        "durationMs", duration,
                        "output", outputMap
                );
                byte[] responseBytes = mapper.writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();

                reporter.reportRequestResult(requestId, false, duration, null, errorMsg);

                String errorJson = mapper.writeValueAsString(Map.of(
                        "requestId", requestId,
                        "status", "ERROR",
                        "error", errorMsg
                ));
                byte[] errorBytes = errorJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, errorBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBytes);
                }
            } finally {
                reporter.requestFinished();
            }
        });

        // GET /health - Health check
        server.createContext("/health", exchange -> {
            String health = mapper.writeValueAsString(Map.of(
                    "status", "UP",
                    "pipelineId", definition.getPipelineId(),
                    "kind", definition.getKind() != null ? definition.getKind().name() : "GENERIC"
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, health.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(health.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Shutdown hook
        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Pipeline serving subprocess shutting down...");
            server.stop(2);
            shutdownLatch.countDown();
        }));

        server.start();
        long pid = ProcessHandle.current().pid();
        String kind = definition.getKind() != null ? definition.getKind().name() : "GENERIC";

        // Report readiness
        reporter.reportReady(definition.getPipelineId(), kind, port, pid);

        log.info("Pipeline serving on port {} (pid={})", port, pid);

        // Block until shutdown
        shutdownLatch.await();
    }
}
