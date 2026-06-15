/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * CLI command for interactive chat with a Kompile Lite instance.
 * Connects to a running lite server's REST API for chat interaction.
 */
@Command(
        name = "lite-chat",
        description = "Interactive chat REPL connected to a running kompile-lite instance.\n\n" +
                "Examples:\n" +
                "  kompile lite-chat\n" +
                "  kompile lite-chat --port=8090\n" +
                "  kompile lite-chat --url=http://my-server:8090\n",
        mixinStandardHelpOptions = true
)
public class LiteChatCommand implements Callable<Integer> {

    @Option(names = {"--url"}, description = "Base URL of the kompile-lite instance (e.g. http://localhost:8090)")
    private String url;

    @Option(names = {"--port", "-p"}, description = "Port of the kompile-lite instance on localhost", defaultValue = "8090")
    private int port;

    @Option(names = {"--session-id"}, description = "Chat session ID (generated if not provided)")
    private String sessionId;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Integer call() {
        String targetUrl = url != null ? url : "http://localhost:" + port;
        if (sessionId == null) {
            sessionId = "cli-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Check if server is reachable
        if (!checkHealth(targetUrl)) {
            System.err.println("Cannot connect to kompile-lite at " + targetUrl);
            System.err.println("Start it with: java -jar kompile-app-lite.jar");
            return 1;
        }

        System.out.println("Connected to Kompile Lite at " + targetUrl);
        System.out.println("Session: " + sessionId);
        System.out.println("Type /quit to exit, /status to check status, /upload <path> to ingest a file.\n");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("You> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            if (input.equals("/quit") || input.equals("/exit")) {
                System.out.println("Goodbye!");
                break;
            }

            if (input.equals("/status")) {
                printStatus(targetUrl);
                continue;
            }

            if (input.startsWith("/upload ")) {
                String filePath = input.substring(8).trim();
                uploadFile(targetUrl, filePath);
                continue;
            }

            if (input.equals("/help")) {
                System.out.println("Commands:");
                System.out.println("  /quit       - Exit");
                System.out.println("  /status     - Show system status");
                System.out.println("  /upload <f> - Upload and ingest a file");
                System.out.println("  /help       - Show this help");
                continue;
            }

            // Send chat message
            String response = sendChat(targetUrl, input);
            if (response != null) {
                System.out.println("\nAssistant> " + response + "\n");
            }
        }

        return 0;
    }

    private boolean checkHealth(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/lite/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String sendChat(String baseUrl, String message) {
        try {
            String json = MAPPER.writeValueAsString(
                    new java.util.LinkedHashMap<>() {{
                        put("message", message);
                        put("sessionId", sessionId);
                    }}
            );

            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/lite/chat").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(120000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode node = MAPPER.readTree(is);
                    String response = node.has("response") ? node.get("response").asText() : "No response";
                    int vectorCount = node.has("vectorResultCount") ? node.get("vectorResultCount").asInt() : 0;
                    int graphCount = node.has("graphResultCount") ? node.get("graphResultCount").asInt() : 0;
                    if (vectorCount > 0 || graphCount > 0) {
                        response += "\n  [Sources: " + vectorCount + " docs";
                        if (graphCount > 0) response += ", " + graphCount + " graph";
                        response += "]";
                    }
                    return response;
                }
            } else {
                System.err.println("Error: HTTP " + conn.getResponseCode());
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return null;
        }
    }

    private void printStatus(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/lite/status").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode node = MAPPER.readTree(is);
                    System.out.println("\nStatus:");
                    System.out.println("  Embedding: " + formatBool(node.path("embedding").path("available").asBoolean())
                            + " (" + node.path("embedding").path("model").asText("none") + ")");
                    System.out.println("  Vector Store: " + formatBool(node.path("vectorStore").path("available").asBoolean())
                            + " (" + node.path("vectorStore").path("documentCount").asLong() + " docs)");
                    System.out.println("  Graph RAG: " + formatBool(node.path("graphRag").path("available").asBoolean()));
                    System.out.println("  LLM: " + formatBool(node.path("llm").path("available").asBoolean())
                            + " (" + node.path("llm").path("provider").asText("none") + ")");
                    System.out.println();
                }
            }
        } catch (Exception e) {
            System.err.println("Could not fetch status: " + e.getMessage());
        }
    }

    private void uploadFile(String baseUrl, String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("File not found: " + filePath);
            return;
        }

        System.out.println("Uploading: " + file.getName() + " (" + file.length() + " bytes)...");

        try {
            String boundary = "----KompileBoundary" + System.currentTimeMillis();
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/lite/documents/upload").openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000);
            conn.setReadTimeout(300000);

            try (OutputStream os = conn.getOutputStream()) {
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
                writer.append("--").append(boundary).append("\r\n");
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.getName()).append("\"\r\n");
                writer.append("Content-Type: application/octet-stream\r\n\r\n");
                writer.flush();

                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.transferTo(os);
                }
                os.flush();

                writer.append("\r\n--").append(boundary).append("--\r\n");
                writer.flush();
            }

            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    JsonNode node = MAPPER.readTree(is);
                    System.out.println("Ingested: " + node.path("chunks").asInt() + " chunks, "
                            + node.path("indexed").asInt() + " indexed. Status: " + node.path("status").asText());
                }
            } else {
                System.err.println("Upload failed: HTTP " + conn.getResponseCode());
            }
        } catch (Exception e) {
            System.err.println("Upload error: " + e.getMessage());
        }
    }

    private String formatBool(boolean val) {
        return val ? "OK" : "--";
    }
}
