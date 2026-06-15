/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code kompile graph report} — generates a markdown summary report from the
 * knowledge graph via the REST API. Calls {@code GET /api/graph/report}.
 */
@CommandLine.Command(
        name = "report",
        description = "Generate a GRAPH_REPORT.md summary (hub nodes, surprising connections, suggested queries)",
        mixinStandardHelpOptions = true
)
public class GraphReportCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @CommandLine.Option(names = {"--output", "-o"}, defaultValue = "GRAPH_REPORT.md",
            description = "Output file (default: GRAPH_REPORT.md)")
    private Path output;

    @CommandLine.Option(names = "--fact-sheet-id",
            description = "Scope report to a specific fact sheet")
    private Long factSheetId;

    @CommandLine.Option(names = "--type", defaultValue = "summary",
            description = "Report type: summary, communities, entities")
    private String type;

    @Override
    public Integer call() {
        KompileHttpClient client = app.requireClient();
        if (client == null) return 1;
        try {
            StringBuilder url = new StringBuilder("/api/graph/report?type=").append(type);
            if (factSheetId != null) url.append("&factSheetId=").append(factSheetId);
            String report = client.getString(url.toString());

            // The API returns the markdown content directly
            Files.writeString(output, report);
            System.out.println("Report written to: " + output.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
