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

package ai.kompile.cli.agent;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command for discovering business processes from knowledge graph data.
 * Supports pattern-based and LLM-driven process discovery.
 */
@CommandLine.Command(name = "process-discovery", description = "Discover business processes from knowledge graph data.")
public class ProcessDiscoveryCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Operation: suggest, email-flows, excel-flows, document-flows, cross-document-flows, llm-discover, accept")
    private String operation;

    @CommandLine.Option(names = {"--node-ids"}, split = ",", description = "Comma-separated graph node IDs to analyze")
    private List<String> graphNodeIds;

    @CommandLine.Option(names = {"--file", "-f"}, description = "JSON file containing the request body (for suggest options or accept payload)")
    private Path file;

    @CommandLine.Option(names = {"--json", "-j"}, description = "Inline JSON request body")
    private String json;

    @CommandLine.Option(names = {"--url"}, description = "Application URL")
    private String url;

    @CommandLine.Option(names = {"--port", "-p"}, defaultValue = "8080", description = "Application port")
    private int port;

    @Override
    public Integer call() throws Exception {
        KompileHttpClient client = KompileHttpClient.create(url, port);
        try {
            switch (operation) {
                case "suggest":
                    return postDiscovery(client, "/api/process/discovery/suggest", true);
                case "email-flows":
                    return postDiscovery(client, "/api/process/discovery/email-flows", false);
                case "excel-flows":
                    return postDiscovery(client, "/api/process/discovery/excel-flows", false);
                case "document-flows":
                    return postDiscovery(client, "/api/process/discovery/document-flows", false);
                case "cross-document-flows":
                    return postDiscovery(client, "/api/process/discovery/cross-document-flows", false);
                case "llm-discover":
                    return postDiscovery(client, "/api/process/discovery/llm-discover", true);
                case "accept":
                    System.out.println(client.postString("/api/process/discovery/accept", readBody()));
                    return 0;
                default:
                    System.err.println("Unknown operation: " + operation);
                    System.err.println("Valid operations: suggest, email-flows, excel-flows, document-flows, cross-document-flows, llm-discover, accept");
                    return 1;
            }
        } catch (Exception e) {
            System.err.println("Process discovery operation failed: " + e.getMessage());
            return 1;
        }
    }

    private int postDiscovery(KompileHttpClient client, String path, boolean supportsOptions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (graphNodeIds != null && !graphNodeIds.isEmpty()) {
            body.put("graphNodeIds", graphNodeIds);
        }
        if (supportsOptions) {
            Object extra = readBodyOrNull();
            if (extra instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> extraMap = (Map<String, Object>) extra;
                body.put("options", extraMap.getOrDefault("options", Map.of()));
            }
        }
        System.out.println(client.postString(path, body));
        return 0;
    }

    private Object readBody() throws Exception {
        if (file != null) {
            return JsonUtils.standardMapper().readValue(Files.readString(file), Object.class);
        }
        if (json != null && !json.isBlank()) {
            return JsonUtils.standardMapper().readValue(json, Object.class);
        }
        System.err.println("Request body required: provide --file or --json");
        throw new IllegalArgumentException("No request body provided");
    }

    private Object readBodyOrNull() throws Exception {
        if (file != null) {
            return JsonUtils.standardMapper().readValue(Files.readString(file), Object.class);
        }
        if (json != null && !json.isBlank()) {
            return JsonUtils.standardMapper().readValue(json, Object.class);
        }
        return null;
    }
}
