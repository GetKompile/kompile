package ai.kompile.cli.remote;

import ai.kompile.cli.remote.transport.OpenAiChatExchange;
import ai.kompile.cli.remote.transport.OpenAiChatRequestBody;
import ai.kompile.cli.remote.transport.OpenAiChatStreamDecoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Request-only transport surface. No local models, host startup, automatic tool execution or agent loop. */
@Command(name = "kompile-remote", mixinStandardHelpOptions = true,
        description = "Explicit remote requests. This executable does not implement chat exec or run tools.")
public final class RemoteCommand implements Runnable {
    @Spec private CommandSpec spec;

    public static void main(String[] args) { System.exit(commandLine().execute(args)); }
    public static CommandLine commandLine() { return commandLine(System::getenv); }
    static CommandLine commandLine(Function<String, String> environment) {
        return new CommandLine(new RemoteCommand()).addSubcommand("request", new RequestCommand(environment));
    }
    @Override public void run() { spec.commandLine().usage(spec.commandLine().getOut()); }

    @Command(name = "request", mixinStandardHelpOptions = true,
            description = "One custom Chat Completions request. Tool calls are returned as data, never executed.")
    public static final class RequestCommand implements Callable<Integer> {
        @Spec private CommandSpec spec;
        @Option(names = "--provider", defaultValue = "custom", description = "Only custom Chat Completions is supported.")
        private String provider;
        @Option(names = "--base-url", required = true, description = "Explicit HTTP(S) API root; /chat/completions is appended.")
        private String baseUrl;
        @Option(names = "--allow-insecure-http", description = "Explicitly allow plaintext HTTP outside loopback.") private boolean insecureHttp;
        @Option(names = "--model", required = true) private String model;
        @Option(names = "--prompt", description = "User text, mutually exclusive with --messages-file.") private String prompt;
        @Option(names = "--system") private String system;
        @Option(names = "--messages-file", description = "Prepared wire messages JSON array; caller owns all history.") private Path messagesFile;
        @Option(names = "--tools-file", description = "Prepared wire tool definitions JSON array; no tools will execute.") private Path toolsFile;
        @Option(names = "--api-key-env", defaultValue = "OPENAI_API_KEY", description = "Credential environment variable; absent/empty means no Authorization header.")
        private String apiKeyEnvironment;
        @Option(names = "--timeout-ms", defaultValue = "120000", description = "Total request deadline, including blocked response reads.")
        private long timeoutMillis;
        @Option(names = "--json", description = "Emit one final JSON result, including wire tool calls and raw usage events.") private boolean json;
        private final Function<String, String> environment;

        public RequestCommand() { this(System::getenv); }
        RequestCommand(Function<String, String> environment) { this.environment = environment; }

        @Override public Integer call() {
            ObjectMapper mapper = new ObjectMapper();
            var state = new OpenAiChatStreamDecoder.State();
            ArrayNode usage = mapper.createArrayNode();
            String[] protocolError = {null};
            String key = null;
            try {
                if (!"custom".equalsIgnoreCase(provider)) throw new IllegalArgumentException(
                        "Unsupported provider: " + provider + "; request supports only explicit custom Chat Completions");
                if (model.isBlank()) throw new IllegalArgumentException("Model must not be blank");
                if (timeoutMillis <= 0) throw new IllegalArgumentException("Timeout must be positive");
                URI endpoint = endpoint(baseUrl, insecureHttp);
                ArrayNode messages = messages(mapper);
                ArrayNode tools = toolsFile == null ? null : readArray(mapper, toolsFile, "tools");
                ObjectNode body = OpenAiChatRequestBody.base(mapper, model, messages);
                OpenAiChatRequestBody.addStreamingOptions(mapper, body, tools);
                key = apiKeyEnvironment == null || apiKeyEnvironment.isBlank() ? null : environment.apply(apiKeyEnvironment);
                var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMillis))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
                if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(timeoutMillis, 30000))).build();
                var cancellation = new OpenAiChatExchange.Cancellation();
                var timer = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "remote-request-deadline"); thread.setDaemon(true); return thread;
                });
                Thread shutdown = new Thread(cancellation::cancel, "remote-request-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                var deadline = timer.schedule(cancellation::cancel, timeoutMillis, TimeUnit.MILLISECONDS);
                int code = 0;
                String failureMessage = null;
                try (var exchange = OpenAiChatExchange.send(client, builder.build(), cancellation)) {
                    var response = exchange.response();
                    if (response.statusCode() != 200) {
                        String detail = new String(response.body().readNBytes(4096), StandardCharsets.UTF_8);
                        code = 1;
                        failureMessage = "HTTP " + response.statusCode() + ": " + detail;
                    } else {
                        OpenAiChatStreamDecoder.decode(response.body(), mapper, cancellation::isCancelled,
                                new OpenAiChatStreamDecoder.Listener() {
                                    @Override public void onText(String text) { if (!json) { out().print(text); out().flush(); } }
                                    @Override public void onRefusal(String text) { if (!json) { out().print(text); out().flush(); } }
                                    @Override public void onUsage(JsonNode event) { usage.add(event.deepCopy()); }
                                    @Override public void onError(JsonNode error) {
                                        protocolError[0] = error.path("message").asText("Provider protocol error");
                                    }
                                }, state);
                        state.cancelled |= cancellation.isCancelled();
                        if (state.cancelled) { code = 130; failureMessage = "Request cancelled or deadline exceeded"; }
                        else if (state.protocolError) { code = 1; failureMessage = protocolError[0]; }
                        else if (state.refusalDetected || state.truncatedDetected || state.silentStreamDrop) {
                            code = 1;
                            failureMessage = "Request did not complete: refusal, truncation or premature stream end";
                        }
                    }
                } catch (Exception failure) {
                    state.cancelled |= cancellation.isCancelled() || Thread.currentThread().isInterrupted();
                    code = state.cancelled ? 130 : 1;
                    failureMessage = state.cancelled ? "Request cancelled or deadline exceeded" : failure.getMessage();
                } finally {
                    deadline.cancel(false);
                    timer.shutdownNow();
                    cancellation.close();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { /* JVM shutdown already owns cancellation. */ }
                }
                // Publish one final result only after body ownership and deadline cleanup have completed.
                if (code != 0) return fail(mapper, state, usage, failureMessage, key, code);
                if (json) out().println(mapper.writeValueAsString(result(mapper, state, usage, "completed")));
                else {
                    out().println();
                    if (!state.toolCalls.isEmpty()) err().println("Returned " + state.toolCalls.size()
                            + " tool call(s) as data; request does not execute tools. Use --json to inspect them.");
                }
                return 0;
            } catch (Exception failure) {
                return fail(mapper, state, usage, failure.getMessage(), key, failure instanceof IllegalArgumentException ? 2 : 1);
            } finally { out().flush(); err().flush(); }
        }

        private ArrayNode messages(ObjectMapper mapper) throws Exception {
            if (messagesFile != null) {
                if (prompt != null || system != null) throw new IllegalArgumentException("Use either --messages-file or --prompt/--system");
                return readArray(mapper, messagesFile, "messages");
            }
            if (prompt == null) throw new IllegalArgumentException("Supply --prompt or --messages-file");
            ArrayNode messages = mapper.createArrayNode();
            if (system != null) messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", prompt);
            return messages;
        }
        private static ArrayNode readArray(ObjectMapper mapper, Path file, String label) throws Exception {
            JsonNode value = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (value == null || !value.isArray()) throw new IllegalArgumentException(label + " must be a JSON array");
            return (ArrayNode) value;
        }
        private static URI endpoint(String base, boolean insecureHttp) {
            URI root = URI.create(base);
            if (!("https".equalsIgnoreCase(root.getScheme()) || "http".equalsIgnoreCase(root.getScheme()))
                    || root.getHost() == null || root.getRawUserInfo() != null || root.getRawQuery() != null || root.getRawFragment() != null) {
                throw new IllegalArgumentException("Base URL must be an absolute HTTP(S) API root without credentials, query or fragment");
            }
            String host = root.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "::1".equals(host) || "[::1]".equals(host);
            if ("http".equalsIgnoreCase(root.getScheme()) && !loopback && !insecureHttp) {
                throw new IllegalArgumentException("Use HTTPS outside loopback or explicitly set --allow-insecure-http");
            }
            return URI.create(base.replaceAll("/+$", "") + "/chat/completions");
        }
        private int fail(ObjectMapper mapper, OpenAiChatStreamDecoder.State state, ArrayNode usage, String message, String key, int code) {
            String safe = message == null ? "Remote request failed" : message;
            if (key != null && !key.isBlank()) {
                safe = safe.replace(key, "[redacted]");
                try {
                    String escaped = mapper.writeValueAsString(key);
                    safe = safe.replace(escaped.substring(1, escaped.length() - 1), "[redacted]");
                } catch (Exception ignored) { /* Literal credential redaction already applied. */ }
            }
            if (json) {
                ObjectNode result = result(mapper, state, usage, code == 130 ? "cancelled" : "failed");
                result.put("error", safe);
                out().println(result);
            } else err().println(safe);
            return code;
        }
        private static ObjectNode result(ObjectMapper mapper, OpenAiChatStreamDecoder.State state, ArrayNode usage, String status) {
            ObjectNode result = mapper.createObjectNode().put("status", status).put("text", state.text)
                    .put("toolsExecuted", false).put("terminalEventSeen", state.terminalEventSeen)
                    .put("cancelled", state.cancelled).put("refusal", state.refusalDetected).put("truncated", state.truncatedDetected);
            ArrayNode calls = result.putArray("toolCalls");
            for (var call : state.toolCalls) {
                ObjectNode value = calls.addObject().put("id", call.id()).put("name", call.name());
                value.set("arguments", call.arguments());
            }
            result.set("usage", usage);
            return result;
        }
        private PrintWriter out() { return spec.commandLine().getOut(); }
        private PrintWriter err() { return spec.commandLine().getErr(); }
    }
}
