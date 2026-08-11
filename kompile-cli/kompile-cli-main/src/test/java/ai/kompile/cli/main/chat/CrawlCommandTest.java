/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlCommandTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsSelectedDocumentWorkerInstruction() throws Exception {
        String instruction = CrawlCommand.buildDocumentInstruction(
                List.of("/data/report.pdf", "https://example.test/policy"),
                "finance-kb", "finance-pdf", false);

        assertTrue(instruction.contains("crawl_discover section=all"));
        assertTrue(instruction.contains("crawl_documents"));
        assertTrue(instruction.contains("crawl_control operation=status"));
        assertTrue(instruction.contains("knowledge_status"));
        assertTrue(instruction.contains("knowledge_search"));
        assertTrue(instruction.contains("memory"));
        assertTrue(instruction.contains("semantic_memory"));

        JsonNode selection = mapper.readTree(between(instruction,
                "<selected_document_crawl>\n", "\n</selected_document_crawl>"));
        assertEquals("/data/report.pdf", selection.path("documents").get(0).path("path").asText());
        assertEquals("https://example.test/policy",
                selection.path("documents").get(1).path("url").asText());
        assertEquals("finance-kb", selection.path("knowledgeBase").path("name").asText());
        assertEquals("finance-pdf", selection.path("defaultPipelineId").asText());
    }

    @Test
    void numericKnowledgeBaseAndClearGraphArePreserved() throws Exception {
        String instruction = CrawlCommand.buildDocumentInstruction(
                List.of("/data/report.pdf"), "42", null, true);

        JsonNode selection = mapper.readTree(between(instruction,
                "<selected_document_crawl>\n", "\n</selected_document_crawl>"));
        assertEquals(42L, selection.path("knowledgeBase").path("id").asLong());
        assertTrue(instruction.contains("operation=clear_graph"));
        assertTrue(instruction.contains("section=knowledge_bases"));
    }

    @Test
    void offlineCliAcceptsDocumentWorkerConfiguration() {
        CommandLine.ParseResult parsed = new CommandLine(new CrawlCommand()).parseArgs(
                "--offline",
                "--document", "/data/a.pdf",
                "--document", "https://example.test/b",
                "--knowledge-base", "docs",
                "--pipeline-id", "vlm-docs",
                "--crawl-url", "http://localhost:8082",
                "--model", "local-model");

        assertTrue(parsed.hasMatchedOption("--offline"));
        assertEquals(List.of("/data/a.pdf", "https://example.test/b"),
                parsed.matchedOptionValue("--document", List.of()));
        assertEquals("docs", parsed.matchedOptionValue("--knowledge-base", null));
        assertEquals("vlm-docs", parsed.matchedOptionValue("--pipeline-id", null));
    }

    @Test
    void offlineCliLeavesCrawlUrlOptionalForProjectLocalWorker() {
        CommandLine.ParseResult parsed = new CommandLine(new CrawlCommand()).parseArgs(
                "--offline",
                "--document", "docs/architecture.md",
                "--knowledge-base", "project-notes");

        assertTrue(parsed.hasMatchedOption("--offline"));
        assertFalse(parsed.hasMatchedOption("--crawl-url"));
    }

    @Test
    void offlineCliRejectsMissingWork() {
        assertEquals(2, new CommandLine(new CrawlCommand()).execute("--offline"));
    }

    private static String between(String value, String start, String end) {
        int begin = value.indexOf(start);
        int finish = value.indexOf(end, begin + start.length());
        return value.substring(begin + start.length(), finish);
    }
}
