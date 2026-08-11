/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal JSON-lines MCP client for bundle-declared stdio servers. */
final class McpStdioClient implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Process process;
    private final BufferedReader input;
    private final BufferedWriter output;
    private final AtomicLong nextId = new AtomicLong(1);

    McpStdioClient(ObjectMapper mapper, String command, List<String> args, Map<String, String> environment, Path workingDirectory)
            throws IOException {
        if (command == null || command.isBlank()) throw new IOException("MCP stdio command is empty");
        List<String> commandLine = new ArrayList<>();
        commandLine.add(command);
        if (args != null) commandLine.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        if (environment != null) builder.environment().putAll(environment);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.mapper = mapper;
        this.process = builder.start();
        this.input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    void initialize() throws IOException {
        ObjectNode params = mapper.createObjectNode().put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "kompile-agent").put("version", "0.1");
        request("initialize", params);
        notify("notifications/initialized", mapper.createObjectNode());
    }

    List<McpBundleToolLoader.RemoteTool> listTools() throws IOException {
        JsonNode result = request("tools/list", mapper.createObjectNode());
        List<McpBundleToolLoader.RemoteTool> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new McpBundleToolLoader.RemoteTool(
                    tool.path("name").asText(),
                    tool.path("description").asText(""),
                    tool.path("inputSchema").isObject() ? tool.path("inputSchema") : mapper.createObjectNode()));
        }
        return tools;
    }

    JsonNode callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = mapper.createObjectNode().put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    private JsonNode request(String method, JsonNode params) throws IOException {
        long id = nextId.getAndIncrement();
        ObjectNode request = mapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method);
        request.set("params", params == null ? mapper.createObjectNode() : params);
        synchronized (this) {
            output.write(mapper.writeValueAsString(request));
            output.write('\n');
            output.flush();
            return readResponse(id);
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode notification = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        notification.set("params", params == null ? mapper.createObjectNode() : params);
        synchronized (this) {
            output.write(mapper.writeValueAsString(notification));
            output.write('\n');
            output.flush();
        }
    }

    private JsonNode readResponse(long id) throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode response = mapper.readTree(line);
            if (!response.path("id").isNumber() || response.path("id").asLong() != id) continue;
            if (response.has("error")) throw new IOException("MCP error: " + response.path("error"));
            return response.path("result");
        }
        throw new IOException("MCP stdio server closed its output");
    }

    @Override
    public void close() {
        try { output.close(); } catch (IOException ignored) { }
        process.destroy();
        try {
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
