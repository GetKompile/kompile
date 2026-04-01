package ai.kompile.cli.main.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.data.serde.ObjectMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@CommandLine.Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Start an HTTP server exposing a pipeline as a REST endpoint.")
public class PipelineServe implements Callable<Integer> {

    @CommandLine.Option(names = {"-f", "--file"}, required = true,
            description = "Pipeline configuration file (JSON or YAML)")
    private File pipelineFile;

    @CommandLine.Option(names = {"-p", "--port"}, defaultValue = "9090",
            description = "Port to listen on (default: ${DEFAULT-VALUE})")
    private int port;

    @CommandLine.Option(names = {"--endpoint"}, defaultValue = "/predict",
            description = "REST endpoint path (default: ${DEFAULT-VALUE})")
    private String endpoint;

    @Override
    public Integer call() throws Exception {
        if (!pipelineFile.exists()) {
            System.err.println("Pipeline file not found: " + pipelineFile.getAbsolutePath());
            return 1;
        }

        ObjectMapper mapper = pipelineFile.getName().endsWith(".yaml") || pipelineFile.getName().endsWith(".yml")
                ? ObjectMappers.getYamlMapper()
                : ObjectMappers.getJsonMapper();

        System.err.println("Loading pipeline from: " + pipelineFile.getAbsolutePath());
        Pipeline pipeline = mapper.readValue(pipelineFile, Pipeline.class);
        pipeline.validate();

        System.err.println("Initializing pipeline executor...");
        PipelineExecutor executor = pipeline.createExecutor();

        ObjectMapper jsonMapper = ObjectMappers.getJsonMapper();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(endpoint, exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                String msg = "{\"error\":\"Only POST is supported\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(405, msg.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Data inputData;
                if (body.length > 0) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputMap = jsonMapper.readValue(body, Map.class);
                    inputData = Data.fromMap(inputMap);
                } else {
                    inputData = Data.empty();
                }

                long start = System.currentTimeMillis();
                Data output = executor.exec(inputData);
                long duration = System.currentTimeMillis() - start;

                Map<String, Object> result = Map.of(
                        "status", "COMPLETED",
                        "durationMs", duration,
                        "output", output.toMap()
                );
                byte[] responseBytes = jsonMapper.writeValueAsBytes(result);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                String errorJson = jsonMapper.writeValueAsString(Map.of(
                        "status", "ERROR",
                        "error", e.getMessage() != null ? e.getMessage() : e.getClass().getName()
                ));
                byte[] errorBytes = errorJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, errorBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBytes);
                }
            }
        });

        // Health check endpoint
        server.createContext("/health", exchange -> {
            String health = "{\"status\":\"UP\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, health.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(health.getBytes(StandardCharsets.UTF_8));
            }
        });

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("\nShutting down...");
            server.stop(2);
            try {
                executor.close();
            } catch (Exception e) {
                System.err.println("Error closing executor: " + e.getMessage());
            }
            shutdownLatch.countDown();
        }));

        server.start();
        System.err.println("Pipeline server started on port " + port);
        System.err.println("  POST " + endpoint + " - Execute pipeline");
        System.err.println("  GET  /health     - Health check");
        System.err.println("Press Ctrl+C to stop.");

        shutdownLatch.await();
        return 0;
    }
}
