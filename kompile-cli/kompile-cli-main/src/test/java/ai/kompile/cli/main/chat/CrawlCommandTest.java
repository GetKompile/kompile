/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat;

import ai.kompile.cli.main.chat.agent.AgentRunController;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.exec.HeadlessAgentRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void negatableContextFlagsHonorDefaultPositiveAndNegativeForms() {
        CrawlCommand defaults = parse();
        assertTrue(defaults.ragEnabled());
        assertTrue(defaults.memoryEnabled());
        assertTrue(parse("--rag", "--memory").ragEnabled());
        assertTrue(parse("--rag", "--memory").memoryEnabled());
        assertFalse(parse("--no-rag").ragEnabled());
        assertFalse(parse("--no-memory").memoryEnabled());
    }

    @Test
    void offlineRunnerPreservesProjectConfigAndExplicitContextPolicy() {
        CrawlCommand command = parse(
                "--offline", "--no-rag", "--no-memory", "--timeout", "7",
                "--model", "bundled-model");
        ChatConfig config = new ChatConfig(
                "kompile-local", null, "bundled-model", null);
        AgentRunController controller = new AgentRunController(AgentRunController.Mode.AUTO);

        HeadlessAgentRunner.Options options = command.offlineOptions(
                "crawl this", "crawl-worker", null, controller,
                Path.of(".").toAbsolutePath().normalize(), config);

        assertSame(config, options.chatConfig());
        assertSame(controller, options.runController());
        assertEquals("bundled-model", options.modelOverride());
        assertEquals(7_000L, options.timeoutMs());
        assertFalse(options.ragEnabled());
        assertFalse(options.memoryEnabled());
        assertTrue(options.autoApproveTools());
    }

    @Test
    void offlineLocalModelBindingUsesTheSameArtifactIdentityAsSemanticExtraction(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path model = java.nio.file.Files.writeString(tempDir.resolve("model.sdz"), "model");
        Path tokenizer = java.nio.file.Files.writeString(
                tempDir.resolve("tokenizer.json"), "{}");
        ChatConfig config = new ChatConfig(
                "kompile-local", null, "bundled-model", null);

        CrawlCommand.OfflineModelBinding binding = CrawlCommand.resolveOfflineModelBinding(
                config, Map.of(
                        KompileLocalServingBootstrap.MODEL_ENV, model.toString(),
                        KompileLocalServingBootstrap.TOKENIZER_ENV, tokenizer.toString()));

        assertEquals("bundled-model", binding.modelId());
        assertEquals(model.toAbsolutePath(), binding.modelPath());
        assertEquals(tokenizer.toAbsolutePath(), binding.tokenizerPath());
        assertEquals(Map.of("localPath", model.toAbsolutePath().toString()),
                binding.runtimeOptions());
    }

    @Test
    void directRequestForcesSynchronousTerminalExecution(
            @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path requestFile = java.nio.file.Files.writeString(
                tempDir.resolve("crawl.json"),
                "{\"name\":\"direct\",\"async\":true}");

        com.fasterxml.jackson.databind.node.ObjectNode request = CrawlCommand.directRequest(
                mapper, requestFile);

        assertEquals("direct", request.path("name").asText());
        assertFalse(request.path("async").asBoolean());
        assertTrue(request.path("waitForCompletion").asBoolean());
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

    private static CrawlCommand parse(String... args) {
        CrawlCommand command = new CrawlCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }
}
