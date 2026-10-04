/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One MCP server of a Claude Code session, as its {@code mcp_status} control
 * request reports it.
 *
 * @param name       the server's name in the MCP configuration
 * @param status     connected, failed, needs-auth, pending or disabled
 * @param serverInfo the name and version the server reported, or empty
 * @param transport  stdio, sse, http, ...; empty when not reported
 * @param command    the executable and its arguments, or the URL of a remote server
 * @param toolCount  the number of tools the server offers
 * @param error      why the server failed, or empty
 */
public record ClaudeMcpServer(String name, String status, String serverInfo, String transport,
                              List<String> command, int toolCount, String error) {

    static ClaudeMcpServer from(JsonNode server) {
        JsonNode config = server.path("config");
        List<String> command = new ArrayList<>();
        String executable = config.path("command").asText("");
        if (!executable.isEmpty()) {
            command.add(executable);
            for (JsonNode arg : config.path("args")) {
                command.add(arg.asText(""));
            }
        } else if (!config.path("url").asText("").isEmpty()) {
            command.add(config.path("url").asText(""));
        }
        JsonNode info = server.path("serverInfo");
        String reported = (info.path("name").asText("") + " " + info.path("version").asText("")).strip();
        JsonNode tools = server.path("tools");
        return new ClaudeMcpServer(server.path("name").asText(""), server.path("status").asText(""),
                reported, config.path("type").asText(""), List.copyOf(command),
                tools.isArray() ? tools.size() : 0, server.path("error").asText(""));
    }

    /**
     * True for Kompile's own stdio server ({@code kompile mcp-stdio}). Its
     * {@code process} tool runs the session's background jobs, and restarting it
     * stops them.
     */
    public boolean isKompileStdio() {
        return command.contains("mcp-stdio");
    }
}
