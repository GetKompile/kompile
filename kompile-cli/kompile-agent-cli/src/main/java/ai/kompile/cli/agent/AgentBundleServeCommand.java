package ai.kompile.cli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

@CommandLine.Command(name = "serve", description = "Serve an agent bundle over a minimal A2A-compatible JSON-RPC endpoint.", mixinStandardHelpOptions = true)
public final class AgentBundleServeCommand implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", description = "Bundle directory or .kagent archive")
    private Path bundle;
    @CommandLine.Option(names = "--host", defaultValue = "127.0.0.1")
    private String host;
    @CommandLine.Option(names = "--port", defaultValue = "8097")
    private int port;

    @Override
    public Integer call() {
        AgentBundleLoader.LoadedBundle loaded = null;
        HttpServer server = null;
        try {
            AgentBundleLoader loader = new AgentBundleLoader();
            loaded = loader.load(bundle);
            Path workspace = loaded.materialize();
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            ObjectMapper mapper = new ObjectMapper();
            AgentBundleLoader.LoadedBundle finalLoaded = loaded;
            server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
            server.createContext("/.well-known/agent.json", exchange -> {
                ObjectNode card = mapper.createObjectNode();
                card.put("name", finalLoaded.manifest().path("metadata").path("name").asText());
                card.put("description", finalLoaded.manifest().path("metadata").path("description").asText(""));
                card.put("protocolVersion", "1.0");
                respond(exchange, 200, mapper.writeValueAsString(card));
            });
            server.createContext("/a2a", exchange -> handleRpc(exchange, mapper, finalLoaded.manifest(), workspace));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("Agent bundle serving on http://" + host + ":" + port);
            HttpServer runningServer = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                runningServer.stop(0);
                finalLoaded.close();
            }));
            Thread.currentThread().join();
            return 0;
        } catch (Exception e) {
            if (server != null) server.stop(0);
            if (loaded != null) loaded.close();
            System.err.println("Could not serve agent bundle: " + e.getMessage());
            return 1;
        }
    }

    private static void handleRpc(HttpExchange exchange, ObjectMapper mapper, JsonNode manifest, Path workspace) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"POST required\"}");
            return;
        }
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText("");
        if (!method.equals("message/send") && !method.equals("tasks/send")) {
            respond(exchange, 400, error(mapper, request, "supported methods: message/send, tasks/send"));
            return;
        }
        JsonNode params = request.path("params");
        String prompt = extractPrompt(params);
        if (prompt.isBlank()) {
            respond(exchange, 400, error(mapper, request, "message text is required"));
            return;
        }
        int exit;
        try {
            exit = AgentBundleExecutor.run(manifest, workspace, prompt, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 130;
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("jsonrpc", "2.0");
        if (request.has("id")) result.set("id", request.get("id"));
        result.putObject("result").put("status", exit == 0 ? "completed" : "failed").put("exitCode", exit);
        respond(exchange, exit == 0 ? 200 : 500, mapper.writeValueAsString(result));
    }

    private static String extractPrompt(JsonNode params) {
        JsonNode message = params.path("message");
        if (message.isTextual()) return message.asText();
        if (message.path("text").isTextual()) return message.path("text").asText();
        for (JsonNode part : message.path("parts")) {
            if (part.path("text").isTextual()) return part.path("text").asText();
        }
        return params.path("prompt").asText("");
    }

    private static String error(ObjectMapper mapper, JsonNode request, String message) throws IOException {
        ObjectNode result = mapper.createObjectNode().put("jsonrpc", "2.0");
        result.putObject("error").put("message", message);
        if (request.has("id")) result.set("id", request.get("id"));
        return mapper.writeValueAsString(result);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
