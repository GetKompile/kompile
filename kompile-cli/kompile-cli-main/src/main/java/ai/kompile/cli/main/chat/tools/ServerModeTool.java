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

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.serve.PersistentServerManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * MCP tool for interacting with the persistent kompile server.
 * Actions: status, list, connect, start_server.
 */
public class ServerModeTool implements CliTool {

    @Override
    public String id() { return "server_mode"; }

    @Override
    public String description() {
        return "Manage the persistent kompile server for multi-session coordination. "
             + "Actions: 'status' check server state, 'list' show sessions, "
             + "'connect' connect/create a session, 'start_server' start a new server.";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectMapper om = JsonUtils.standardMapper();
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode action = props.putObject("action");
        action.put("type", "string");
        action.put("description", "Action: status, list, connect, start_server");

        ObjectNode sessionName = props.putObject("session_name");
        sessionName.put("type", "string");
        sessionName.put("description", "Session name for connect action");

        ObjectNode port = props.putObject("port");
        port.put("type", "integer");
        port.put("description", "Port for start_server action (default 9876)");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public String permissionKey() { return "bash"; }

    @Override
    public ToolResult execute(JsonNode params, ToolContext context) throws ToolExecutionException {
        context.checkPermission(permissionKey(), "Server mode management");

        String action = params.path("action").asText("");

        switch (action) {
            case "status": {
                PersistentServerManager.ServerInfo info = PersistentServerManager.findRunningServer();
                if (info == null) {
                    return ToolResult.success("server_mode: status",
                        "No kompile server running. Use 'start_server' to start one.");
                }
                // Try to connect and get status
                try {
                    String response = sendCommand(info.port, "status");
                    return ToolResult.success("server_mode: status", response);
                } catch (Exception e) {
                    return ToolResult.success("server_mode: status",
                        "Server registered but not responding (stale registry). PID: " + info.pid);
                }
            }

            case "list": {
                PersistentServerManager.ServerInfo info = PersistentServerManager.findRunningServer();
                if (info == null) return ToolResult.error("No server running");
                try {
                    String response = sendCommand(info.port, "list");
                    return ToolResult.success("server_mode: sessions", response);
                } catch (Exception e) {
                    return ToolResult.error("Could not connect to server: " + e.getMessage());
                }
            }

            case "connect": {
                String sessionName = params.path("session_name").asText("");
                if (sessionName.isEmpty()) return ToolResult.error("session_name is required");
                PersistentServerManager.ServerInfo info = PersistentServerManager.findRunningServer();
                if (info == null) return ToolResult.error("No server running. Start one first.");
                try {
                    String response = sendCommand(info.port, "session " + sessionName);
                    return ToolResult.success("server_mode: connected", response);
                } catch (Exception e) {
                    return ToolResult.error("Could not connect: " + e.getMessage());
                }
            }

            case "start_server": {
                PersistentServerManager.ServerInfo existing = PersistentServerManager.findRunningServer();
                if (existing != null) {
                    return ToolResult.success("server_mode: already running",
                        "Server already running: " + existing.serverId + " on port " + existing.port);
                }
                int port = params.path("port").asInt(9876);
                // Start server in background
                try {
                    PersistentServerManager server = new PersistentServerManager();
                    Thread serverThread = new Thread(() -> {
                        try { server.start(port); } catch (Exception e) {
                            System.err.println("[Server] Start failed: " + e.getMessage());
                        }
                    }, "kompile-server");
                    serverThread.setDaemon(true);
                    serverThread.start();
                    Thread.sleep(500); // Brief wait for startup
                    return ToolResult.success("server_mode: started",
                        "Server started on port " + port);
                } catch (Exception e) {
                    return ToolResult.error("Failed to start server: " + e.getMessage());
                }
            }

            default:
                return ToolResult.error("Unknown action: " + action);
        }
    }

    private String sendCommand(int port, String command) throws IOException {
        try (Socket socket = new Socket("localhost", port);
             var out = new PrintWriter(socket.getOutputStream(), true);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            socket.setSoTimeout(5000);
            out.println(command);
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if ("---END---".equals(line)) break;
                response.append(line).append("\n");
            }
            return response.toString().trim();
        }
    }
}
