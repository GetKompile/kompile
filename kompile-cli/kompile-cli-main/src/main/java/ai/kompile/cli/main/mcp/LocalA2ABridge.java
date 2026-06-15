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

package ai.kompile.cli.main.mcp;

import ai.kompile.cli.common.registry.McpSessionInfo;
import ai.kompile.cli.common.registry.McpSessionRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lightweight local HTTP server that exposes this MCP session as an A2A agent.
 * <p>
 * Binds to a random available port on localhost and serves:
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} — agent card for discovery</li>
 *   <li>{@code POST /a2a} — JSON-RPC 2.0 endpoint for {@code message/send}</li>
 *   <li>{@code GET /a2a/health} — readiness check</li>
 *   <li>{@code GET /a2a/inbox} — drain file-based inbox messages</li>
 * </ul>
 * <p>
 * The bridge routes incoming {@code message/send} requests to a pluggable
 * {@link MessageHandler} callback, which the MCP tool executor can hook into.
 * <p>
 * Incoming messages that arrive while no handler is registered are queued
 * and can be retrieved later.
 */
public class LocalA2ABridge {

    /**
     * Callback for handling incoming A2A messages. Returns a response text
     * or throws an exception on failure.
     */
    @FunctionalInterface
    public interface MessageHandler {
        String handleMessage(String senderSessionId, String message) throws Exception;
    }

    private final String sessionId;
    private final McpSessionInfo sessionInfo;
    private final ObjectMapper om;
    private HttpServer server;
    private int port;
    private volatile MessageHandler messageHandler;

    /** Queue of incoming messages received when no handler is set. */
    private final BlockingQueue<IncomingMessage> pendingMessages = new LinkedBlockingQueue<>();

    /** Active tasks keyed by task ID for status tracking. */
    private final Map<String, TaskState> activeTasks = new ConcurrentHashMap<>();

    /** Mailbox polling scheduler. */
    private ScheduledExecutorService mailboxPoller;

    public LocalA2ABridge(String sessionId, McpSessionInfo sessionInfo, ObjectMapper om) {
        this.sessionId = sessionId;
        this.sessionInfo = sessionInfo;
        this.om = om;
    }

    /**
     * Starts the bridge on a random available port.
     *
     * @return the port number the server is listening on
     */
    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        port = server.getAddress().getPort();

        server.createContext("/.well-known/agent-card.json", this::handleAgentCard);
        server.createContext("/a2a", this::handleA2A);
        server.createContext("/a2a/health", this::handleHealth);
        server.createContext("/a2a/inbox", this::handleInbox);

        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "a2a-bridge-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();

        // Start mailbox polling (checks file inbox every 5 seconds)
        mailboxPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "a2a-mailbox-" + sessionId);
            t.setDaemon(true);
            return t;
        });
        mailboxPoller.scheduleAtFixedRate(this::pollMailbox, 2, 5, TimeUnit.SECONDS);

        return port;
    }

    /**
     * Stops the bridge server and mailbox poller.
     */
    public void stop() {
        if (mailboxPoller != null) {
            mailboxPoller.shutdownNow();
        }
        if (server != null) {
            server.stop(1);
        }
    }

    public int getPort() {
        return port;
    }

    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Returns and drains any pending messages that arrived before a handler was set.
     */
    public List<IncomingMessage> drainPendingMessages() {
        List<IncomingMessage> msgs = new ArrayList<>();
        pendingMessages.drainTo(msgs);
        return msgs;
    }

    // ── HTTP Handlers ──────────────────────────────────────────────────────

    private void handleAgentCard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        ObjectNode card = om.createObjectNode();
        card.put("name", sessionInfo.getLabel() != null ? sessionInfo.getLabel() : sessionId);
        card.put("description", "Kompile MCP agent session (" + sessionInfo.getAgentType() + ")");
        card.put("url", "http://localhost:" + port);
        card.put("version", "0.1.0");
        card.put("protocolVersion", "0.2.0");

        ObjectNode capabilities = card.putObject("capabilities");
        capabilities.put("streaming", false);
        capabilities.put("pushNotifications", false);
        capabilities.put("stateTransitionHistory", false);

        ArrayNode skills = card.putArray("skills");
        ObjectNode codeSkill = skills.addObject();
        codeSkill.put("id", "code");
        codeSkill.put("name", "Code Assistance");
        codeSkill.put("description", "Read, write, and search code in " +
                (sessionInfo.getWorkDir() != null ? sessionInfo.getWorkDir() : "the project"));

        ObjectNode chatSkill = skills.addObject();
        chatSkill.put("id", "chat");
        chatSkill.put("name", "Message");
        chatSkill.put("description", "Send a message to this agent session");

        card.putArray("defaultInputModes").add("text/plain");
        card.putArray("defaultOutputModes").add("text/plain");

        // Extra fields for local discovery
        card.put("x-sessionId", sessionId);
        card.put("x-agentType", sessionInfo.getAgentType());
        card.put("x-pid", sessionInfo.getPid());
        card.put("x-parentPid", sessionInfo.getParentPid());
        if (sessionInfo.getWorkDir() != null) {
            card.put("x-workDir", sessionInfo.getWorkDir());
        }

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(card));
    }

    private void handleA2A(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Route sub-paths
        if ("/a2a/health".equals(path)) {
            handleHealth(exchange);
            return;
        }
        if ("/a2a/inbox".equals(path)) {
            handleInbox(exchange);
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        try {
            JsonNode rpcRequest = om.readTree(body);
            String method = rpcRequest.path("method").asText("");
            String rpcId = rpcRequest.path("id").asText(UUID.randomUUID().toString());

            switch (method) {
                case "message/send" -> handleMessageSend(exchange, rpcRequest, rpcId);
                case "tasks/get" -> handleTasksGet(exchange, rpcRequest, rpcId);
                case "tasks/cancel" -> handleTasksCancel(exchange, rpcRequest, rpcId);
                default -> sendJsonRpcError(exchange, rpcId, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            sendJsonRpcError(exchange, "unknown", -32700, "Parse error: " + e.getMessage());
        }
    }

    private void handleMessageSend(HttpExchange exchange, JsonNode rpcRequest, String rpcId) throws IOException {
        JsonNode params = rpcRequest.path("params");
        JsonNode message = params.path("message");
        String contextId = params.path("contextId").asText(UUID.randomUUID().toString());

        // Extract text from message parts
        StringBuilder text = new StringBuilder();
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
        }

        String messageText = text.toString();
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);

        // Extract sender info from headers or message
        String senderSessionId = exchange.getRequestHeaders().getFirst("X-Session-Id");
        if (senderSessionId == null) senderSessionId = "unknown";

        // Try to handle the message
        String responseText;
        String state;
        try {
            if (messageHandler != null) {
                activeTasks.put(taskId, new TaskState(taskId, contextId, "working", messageText, Instant.now()));
                responseText = messageHandler.handleMessage(senderSessionId, messageText);
                state = "completed";
            } else {
                // No handler — queue the message
                pendingMessages.offer(new IncomingMessage(senderSessionId, messageText, Instant.now()));
                responseText = "Message queued — agent session has no active handler. " +
                        "The message will be delivered when the agent processes its inbox.";
                state = "completed";
            }
        } catch (Exception e) {
            responseText = "Error processing message: " + e.getMessage();
            state = "failed";
        }

        activeTasks.put(taskId, new TaskState(taskId, contextId, state, messageText, Instant.now()));

        // Build JSON-RPC response
        ObjectNode response = om.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", rpcId);

        ObjectNode result = response.putObject("result");
        result.put("id", taskId);
        result.put("contextId", contextId);

        ObjectNode status = result.putObject("status");
        status.put("state", state);
        if ("failed".equals(state)) {
            ObjectNode errorObj = status.putObject("error");
            errorObj.put("message", responseText);
        }

        ArrayNode artifacts = result.putArray("artifacts");
        ObjectNode artifact = artifacts.addObject();
        artifact.put("name", "response");
        ArrayNode responseParts = artifact.putArray("parts");
        responseParts.addObject().put("type", "text").put("text", responseText);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(response));
    }

    private void handleTasksGet(HttpExchange exchange, JsonNode rpcRequest, String rpcId) throws IOException {
        String taskId = rpcRequest.path("params").path("id").asText("");
        TaskState task = activeTasks.get(taskId);

        if (task == null) {
            sendJsonRpcError(exchange, rpcId, -32602, "Task not found: " + taskId);
            return;
        }

        ObjectNode response = om.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", rpcId);
        ObjectNode result = response.putObject("result");
        result.put("id", task.taskId);
        result.put("contextId", task.contextId);
        result.putObject("status").put("state", task.state);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(response));
    }

    private void handleTasksCancel(HttpExchange exchange, JsonNode rpcRequest, String rpcId) throws IOException {
        String taskId = rpcRequest.path("params").path("id").asText("");
        TaskState task = activeTasks.get(taskId);
        if (task != null) {
            task.state = "canceled";
        }

        ObjectNode response = om.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", rpcId);
        response.putObject("result").put("id", taskId)
                .putObject("status").put("state", "canceled");

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(response));
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        ObjectNode health = om.createObjectNode();
        health.put("status", "ok");
        health.put("sessionId", sessionId);
        health.put("agentType", sessionInfo.getAgentType());
        health.put("uptime", java.time.Duration.between(sessionInfo.getStartedAt(), Instant.now()).getSeconds());
        health.put("pendingMessages", pendingMessages.size());

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(health));
    }

    private void handleInbox(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        // Drain file-based inbox
        List<String> fileMessages;
        try {
            fileMessages = McpSessionRegistry.drainInbox(sessionId);
        } catch (IOException e) {
            fileMessages = List.of();
        }

        // Also include queued messages
        List<IncomingMessage> queued = drainPendingMessages();

        ObjectNode result = om.createObjectNode();
        ArrayNode msgs = result.putArray("messages");

        for (String fm : fileMessages) {
            try {
                msgs.add(om.readTree(fm));
            } catch (Exception e) {
                msgs.addObject().put("type", "text").put("text", fm);
            }
        }
        for (IncomingMessage qm : queued) {
            ObjectNode msg = msgs.addObject();
            msg.put("sender", qm.senderSessionId);
            msg.put("text", qm.message);
            msg.put("receivedAt", qm.receivedAt.toString());
        }

        result.put("count", msgs.size());

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(result));
    }

    // ── Mailbox polling ────────────────────────────────────────────────────

    private void pollMailbox() {
        try {
            List<String> messages = McpSessionRegistry.drainInbox(sessionId);
            for (String msgJson : messages) {
                try {
                    JsonNode msg = om.readTree(msgJson);
                    String sender = msg.path("senderSessionId").asText("unknown");
                    String text = msg.path("message").asText("");

                    if (messageHandler != null && !text.isEmpty()) {
                        try {
                            messageHandler.handleMessage(sender, text);
                        } catch (Exception e) {
                            System.err.println("[A2A-Bridge] Error handling mailbox message: " + e.getMessage());
                        }
                    } else if (!text.isEmpty()) {
                        pendingMessages.offer(new IncomingMessage(sender, text, Instant.now()));
                    }
                } catch (Exception e) {
                    System.err.println("[A2A-Bridge] Error parsing mailbox message: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            // Ignore polling errors
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonRpcError(HttpExchange exchange, String id, int code, String message) throws IOException {
        ObjectNode response = om.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        sendResponse(exchange, 200, om.writeValueAsString(response));
    }

    // ── Inner types ────────────────────────────────────────────────────────

    public record IncomingMessage(String senderSessionId, String message, Instant receivedAt) {}

    private static class TaskState {
        final String taskId;
        final String contextId;
        volatile String state;
        final String message;
        final Instant createdAt;

        TaskState(String taskId, String contextId, String state, String message, Instant createdAt) {
            this.taskId = taskId;
            this.contextId = contextId;
            this.state = state;
            this.message = message;
            this.createdAt = createdAt;
        }
    }
}
