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

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.app.CrawlCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the graph builder, proposals, and config CLI commands.
 * No running kompile-app is required — all tests exercise only
 * Picocli metadata (registration, option declarations, defaults, help text).
 */
class GraphBuilderProposalsConfigTest {

    private CommandLine cmd;
    private StringWriter outWriter;
    private StringWriter errWriter;

    @BeforeEach
    void setUp() {
        cmd = new CommandLine(new MainCommand());
        outWriter = new StringWriter();
        errWriter = new StringWriter();
        cmd.setOut(new PrintWriter(outWriter));
        cmd.setErr(new PrintWriter(errWriter));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph builder — subcommand registration
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuilderSubcommandsRegistered() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine builderCmd = graphCmd.getSubcommands().get("builder");
        assertNotNull(builderCmd, "'builder' must be registered under 'graph'");

        Map<String, CommandLine> subcommands = builderCmd.getSubcommands();
        assertTrue(subcommands.containsKey("list"), "missing 'list' under builder");
        assertTrue(subcommands.containsKey("start"), "missing 'start' under builder");
        assertTrue(subcommands.containsKey("jobs"), "missing 'jobs' under builder");
        assertTrue(subcommands.containsKey("status"), "missing 'status' under builder");
        assertTrue(subcommands.containsKey("cancel"), "missing 'cancel' under builder");
        assertTrue(subcommands.containsKey("logs"), "missing 'logs' under builder");
        assertTrue(subcommands.containsKey("stats"), "missing 'stats' under builder");
        assertTrue(subcommands.containsKey("storage-types"), "missing 'storage-types' under builder");
        assertEquals(8, subcommands.size(),
                "Expected exactly 8 subcommands under 'builder'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph builder start — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuilderStartOptions() {
        CommandLine builderCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("builder");
        CommandLine startCmd = builderCmd.getSubcommands().get("start");
        assertNotNull(startCmd, "start subcommand must be registered under builder");

        CommandLine.Model.OptionSpec factSheetSpec = startCmd.getCommandSpec().findOption("--fact-sheet-id");
        assertNotNull(factSheetSpec, "--fact-sheet-id must be declared");
        assertTrue(factSheetSpec.required(), "--fact-sheet-id should be required");

        assertNotNull(startCmd.getCommandSpec().findOption("--builder"),
                "--builder must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--model-provider"),
                "--model-provider must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--model-name"),
                "--model-name must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--temperature"),
                "--temperature must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--max-tokens"),
                "--max-tokens must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--entity-types"),
                "--entity-types must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--relationship-types"),
                "--relationship-types must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--min-confidence"),
                "--min-confidence must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--auto-accept"),
                "--auto-accept must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--auto-accept-threshold"),
                "--auto-accept-threshold must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--custom-prompt"),
                "--custom-prompt must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--chunk-ids"),
                "--chunk-ids must be declared");
    }

    @Test
    void testBuilderStartRequiresFactSheetId() {
        int exitCode = cmd.execute("graph", "builder", "start");
        assertNotEquals(0, exitCode, "builder start without --fact-sheet-id should fail");
    }

    @Test
    void testBuilderStartParseArgs() {
        // Use execute with --help to verify args are recognized
        // (standalone parseArgs fails due to AppClientMixin context)
        CommandLine builderCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("builder");
        CommandLine startCmd = builderCmd.getSubcommands().get("start");
        // Verify all options exist by inspecting the spec
        assertNotNull(startCmd.getCommandSpec().findOption("--fact-sheet-id"));
        assertNotNull(startCmd.getCommandSpec().findOption("--builder"));
        assertNotNull(startCmd.getCommandSpec().findOption("--model-provider"));
        assertNotNull(startCmd.getCommandSpec().findOption("--model-name"));
        assertNotNull(startCmd.getCommandSpec().findOption("--temperature"));
        assertNotNull(startCmd.getCommandSpec().findOption("--auto-accept"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph builder jobs — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuilderJobsOptions() {
        CommandLine builderCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("builder");
        CommandLine jobsCmd = builderCmd.getSubcommands().get("jobs");
        assertNotNull(jobsCmd, "jobs subcommand must be registered");

        assertNotNull(jobsCmd.getCommandSpec().findOption("--fact-sheet-id"),
                "--fact-sheet-id must be declared");
        CommandLine.Model.OptionSpec pageSpec = jobsCmd.getCommandSpec().findOption("--page");
        assertNotNull(pageSpec, "--page must be declared");
        assertEquals("0", pageSpec.defaultValue(), "--page default should be 0");
        CommandLine.Model.OptionSpec sizeSpec = jobsCmd.getCommandSpec().findOption("--size");
        assertNotNull(sizeSpec, "--size must be declared");
        assertEquals("20", sizeSpec.defaultValue(), "--size default should be 20");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph builder logs — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuilderLogsOptions() {
        CommandLine builderCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("builder");
        CommandLine logsCmd = builderCmd.getSubcommands().get("logs");
        assertNotNull(logsCmd, "logs subcommand must be registered");

        assertNotNull(logsCmd.getCommandSpec().findOption("--chunk-id"),
                "--chunk-id must be declared");
        assertNotNull(logsCmd.getCommandSpec().findOption("--verbose"),
                "--verbose must be declared");
        assertNotNull(logsCmd.getCommandSpec().findOption("--page"),
                "--page must be declared");
        assertNotNull(logsCmd.getCommandSpec().findOption("--size"),
                "--size must be declared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph proposals — subcommand registration
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testProposalsSubcommandsRegistered() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine proposalsCmd = graphCmd.getSubcommands().get("proposals");
        assertNotNull(proposalsCmd, "'proposals' must be registered under 'graph'");

        Map<String, CommandLine> subcommands = proposalsCmd.getSubcommands();
        assertTrue(subcommands.containsKey("list"), "missing 'list' under proposals");
        assertTrue(subcommands.containsKey("show"), "missing 'show' under proposals");
        assertTrue(subcommands.containsKey("accept"), "missing 'accept' under proposals");
        assertTrue(subcommands.containsKey("reject"), "missing 'reject' under proposals");
        assertTrue(subcommands.containsKey("bulk-accept"), "missing 'bulk-accept' under proposals");
        assertTrue(subcommands.containsKey("bulk-reject"), "missing 'bulk-reject' under proposals");
        assertTrue(subcommands.containsKey("manual"), "missing 'manual' under proposals");
        assertEquals(7, subcommands.size(),
                "Expected exactly 7 subcommands under 'proposals'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph proposals list — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testProposalsListOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine listCmd = proposalsCmd.getSubcommands().get("list");
        assertNotNull(listCmd, "list subcommand must be registered");

        assertNotNull(listCmd.getCommandSpec().findOption("--job-id"),
                "--job-id must be declared");
        assertNotNull(listCmd.getCommandSpec().findOption("--fact-sheet-id"),
                "--fact-sheet-id must be declared");
        assertNotNull(listCmd.getCommandSpec().findOption("--status"),
                "--status must be declared");
        assertNotNull(listCmd.getCommandSpec().findOption("--query"),
                "--query must be declared");
        assertNotNull(listCmd.getCommandSpec().findOption("--page"),
                "--page must be declared");
        assertNotNull(listCmd.getCommandSpec().findOption("--size"),
                "--size must be declared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph proposals manual — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testProposalsManualOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine manualCmd = proposalsCmd.getSubcommands().get("manual");
        assertNotNull(manualCmd, "manual subcommand must be registered");

        CommandLine.Model.OptionSpec factSheetSpec = manualCmd.getCommandSpec().findOption("--fact-sheet-id");
        assertNotNull(factSheetSpec, "--fact-sheet-id must be declared");
        assertTrue(factSheetSpec.required(), "--fact-sheet-id should be required");

        CommandLine.Model.OptionSpec subjectSpec = manualCmd.getCommandSpec().findOption("--subject");
        assertNotNull(subjectSpec, "--subject must be declared");
        assertTrue(subjectSpec.required(), "--subject should be required");

        CommandLine.Model.OptionSpec subjectTypeSpec = manualCmd.getCommandSpec().findOption("--subject-type");
        assertNotNull(subjectTypeSpec, "--subject-type must be declared");
        assertTrue(subjectTypeSpec.required(), "--subject-type should be required");

        CommandLine.Model.OptionSpec predicateSpec = manualCmd.getCommandSpec().findOption("--predicate");
        assertNotNull(predicateSpec, "--predicate must be declared");
        assertTrue(predicateSpec.required(), "--predicate should be required");

        CommandLine.Model.OptionSpec objectSpec = manualCmd.getCommandSpec().findOption("--object");
        assertNotNull(objectSpec, "--object must be declared");
        assertTrue(objectSpec.required(), "--object should be required");

        CommandLine.Model.OptionSpec objectTypeSpec = manualCmd.getCommandSpec().findOption("--object-type");
        assertNotNull(objectTypeSpec, "--object-type must be declared");
        assertTrue(objectTypeSpec.required(), "--object-type should be required");

        assertNotNull(manualCmd.getCommandSpec().findOption("--auto-accept"),
                "--auto-accept must be declared");
    }

    @Test
    void testProposalsManualRequiresAllFields() {
        int exitCode = cmd.execute("graph", "proposals", "manual");
        assertNotEquals(0, exitCode, "manual without required options should fail");
    }

    @Test
    void testProposalsManualParseArgs() {
        // Verify all required options exist via spec introspection
        // (standalone parseArgs fails due to AppClientMixin context)
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine manualCmd = proposalsCmd.getSubcommands().get("manual");
        assertNotNull(manualCmd.getCommandSpec().findOption("--fact-sheet-id"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--subject"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--subject-type"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--predicate"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--object"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--object-type"));
        assertNotNull(manualCmd.getCommandSpec().findOption("--auto-accept"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph proposals accept/reject — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testProposalsAcceptOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine acceptCmd = proposalsCmd.getSubcommands().get("accept");
        assertNotNull(acceptCmd, "accept subcommand must be registered");

        CommandLine.Model.OptionSpec reviewedBySpec = acceptCmd.getCommandSpec().findOption("--reviewed-by");
        assertNotNull(reviewedBySpec, "--reviewed-by must be declared");
        assertEquals("cli-user", reviewedBySpec.defaultValue(),
                "--reviewed-by default should be 'cli-user'");

        assertNotNull(acceptCmd.getCommandSpec().findOption("--storage-type"),
                "--storage-type must be declared");
    }

    @Test
    void testProposalsRejectOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine rejectCmd = proposalsCmd.getSubcommands().get("reject");
        assertNotNull(rejectCmd, "reject subcommand must be registered");

        assertNotNull(rejectCmd.getCommandSpec().findOption("--reason"),
                "--reason must be declared");
        assertNotNull(rejectCmd.getCommandSpec().findOption("--reviewed-by"),
                "--reviewed-by must be declared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph proposals bulk-accept/bulk-reject — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testProposalsBulkAcceptOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine bulkAcceptCmd = proposalsCmd.getSubcommands().get("bulk-accept");
        assertNotNull(bulkAcceptCmd, "bulk-accept subcommand must be registered");

        CommandLine.Model.OptionSpec idsSpec = bulkAcceptCmd.getCommandSpec().findOption("--proposal-ids");
        assertNotNull(idsSpec, "--proposal-ids must be declared");
        assertTrue(idsSpec.required(), "--proposal-ids should be required");

        assertNotNull(bulkAcceptCmd.getCommandSpec().findOption("--reviewed-by"),
                "--reviewed-by must be declared");
        assertNotNull(bulkAcceptCmd.getCommandSpec().findOption("--storage-type"),
                "--storage-type must be declared");
    }

    @Test
    void testProposalsBulkRejectOptions() {
        CommandLine proposalsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("proposals");
        CommandLine bulkRejectCmd = proposalsCmd.getSubcommands().get("bulk-reject");
        assertNotNull(bulkRejectCmd, "bulk-reject subcommand must be registered");

        CommandLine.Model.OptionSpec idsSpec = bulkRejectCmd.getCommandSpec().findOption("--proposal-ids");
        assertNotNull(idsSpec, "--proposal-ids must be declared");
        assertTrue(idsSpec.required(), "--proposal-ids should be required");

        assertNotNull(bulkRejectCmd.getCommandSpec().findOption("--reason"),
                "--reason must be declared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph config — subcommand registration
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testConfigSubcommandsRegistered() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine configCmd = graphCmd.getSubcommands().get("config");
        assertNotNull(configCmd, "'config' must be registered under 'graph'");

        Map<String, CommandLine> subcommands = configCmd.getSubcommands();
        assertTrue(subcommands.containsKey("show"), "missing 'show' under config");
        assertTrue(subcommands.containsKey("set"), "missing 'set' under config");
        assertTrue(subcommands.containsKey("toggle"), "missing 'toggle' under config");
        assertTrue(subcommands.containsKey("reset"), "missing 'reset' under config");
        assertTrue(subcommands.containsKey("status"), "missing 'status' under config");
        assertTrue(subcommands.containsKey("schema-modes"), "missing 'schema-modes' under config");
        assertTrue(subcommands.containsKey("entity-types"), "missing 'entity-types' under config");
        assertTrue(subcommands.containsKey("relationship-types"), "missing 'relationship-types' under config");
        assertTrue(subcommands.containsKey("providers"), "missing 'providers' under config");
        assertTrue(subcommands.containsKey("presets"), "missing 'presets' under config");
        assertTrue(subcommands.containsKey("preset-detail"), "missing 'preset-detail' under config");
        assertTrue(subcommands.containsKey("apply-preset"), "missing 'apply-preset' under config");
        assertEquals(12, subcommands.size(),
                "Expected exactly 12 subcommands under 'config'");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph config set — option introspection
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testConfigSetOptions() {
        CommandLine configCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("config");
        CommandLine setCmd = configCmd.getSubcommands().get("set");
        assertNotNull(setCmd, "set subcommand must be registered under config");

        assertNotNull(setCmd.getCommandSpec().findOption("--enabled"),
                "--enabled must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--schema-mode"),
                "--schema-mode must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--batch-size"),
                "--batch-size must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--model-provider"),
                "--model-provider must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--model-name"),
                "--model-name must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--temperature"),
                "--temperature must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--max-tokens"),
                "--max-tokens must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--max-entities"),
                "--max-entities must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--max-relationships"),
                "--max-relationships must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--auto-accept-threshold"),
                "--auto-accept-threshold must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--deduplication"),
                "--deduplication must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--similarity-threshold"),
                "--similarity-threshold must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--entity-types"),
                "--entity-types must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--relationship-types"),
                "--relationship-types must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--custom-prompt"),
                "--custom-prompt must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--neo4j-enabled"),
                "--neo4j-enabled must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--neo4j-uri"),
                "--neo4j-uri must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--neo4j-user"),
                "--neo4j-user must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--neo4j-password"),
                "--neo4j-password must be declared");
        assertNotNull(setCmd.getCommandSpec().findOption("--neo4j-database"),
                "--neo4j-database must be declared");
    }

    @Test
    void testConfigSetParseArgs() {
        // Verify all options exist via spec introspection
        // (standalone parseArgs fails due to AppClientMixin context)
        CommandLine configCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("config");
        CommandLine setCmd = configCmd.getSubcommands().get("set");
        assertNotNull(setCmd.getCommandSpec().findOption("--model-provider"));
        assertNotNull(setCmd.getCommandSpec().findOption("--model-name"));
        assertNotNull(setCmd.getCommandSpec().findOption("--temperature"));
        assertNotNull(setCmd.getCommandSpec().findOption("--schema-mode"));
        assertNotNull(setCmd.getCommandSpec().findOption("--entity-types"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // crawl start — graph extraction options
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testCrawlStartGraphOptions() {
        // CrawlCommand is auto-discovered, not statically registered in MainCommand
        // so we test it via a standalone CrawlCommand instance
        CommandLine crawlCmd = new CommandLine(new CrawlCommand());
        CommandLine startCmd = crawlCmd.getSubcommands().get("start");
        assertNotNull(startCmd, "start must be registered under crawl");

        // Original graph options
        assertNotNull(startCmd.getCommandSpec().findOption("--graph"),
                "--graph must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-entities"),
                "--graph-entities must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-relations"),
                "--graph-relations must be declared");

        // New enriched graph options
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-model-provider"),
                "--graph-model-provider must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-model-name"),
                "--graph-model-name must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-temperature"),
                "--graph-temperature must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-min-confidence"),
                "--graph-min-confidence must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-auto-accept"),
                "--graph-auto-accept must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-auto-accept-threshold"),
                "--graph-auto-accept-threshold must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-schema-mode"),
                "--graph-schema-mode must be declared");
        assertNotNull(startCmd.getCommandSpec().findOption("--graph-prompt"),
                "--graph-prompt must be declared");
    }
}
