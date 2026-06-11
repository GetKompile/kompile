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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the new graph CLI commands: build, source-chunks, weights, and
 * link-sources. No running kompile-app is required — all tests exercise only
 * Picocli metadata (registration, option declarations, defaults, help text).
 */
class GraphNewCommandsTest {

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
    // graph build
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testBuildCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "build", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("build"), "build help should be non-empty");
        assertTrue(output.contains("--fact-sheet-id"), "build help should mention --fact-sheet-id");
    }

    @Test
    void testBuildCommandRequiresFactSheetId() {
        int exitCode = cmd.execute("graph", "build");
        assertNotEquals(0, exitCode, "build without --fact-sheet-id should fail");
    }

    @Test
    void testBuildCommandOptions() {
        CommandLine buildLine = new CommandLine(new GraphBuildCommand());

        CommandLine.Model.OptionSpec factSheetSpec = buildLine.getCommandSpec().findOption("--fact-sheet-id");
        assertNotNull(factSheetSpec, "--fact-sheet-id must be declared");
        assertTrue(factSheetSpec.required(), "--fact-sheet-id should be required");

        CommandLine.Model.OptionSpec waitSpec = buildLine.getCommandSpec().findOption("--wait");
        assertNotNull(waitSpec, "--wait must be declared");
        assertFalse(waitSpec.required(), "--wait should be optional");

        CommandLine.Model.OptionSpec statusSpec = buildLine.getCommandSpec().findOption("--status");
        assertNotNull(statusSpec, "--status must be declared");
        assertFalse(statusSpec.required(), "--status should be optional");

        CommandLine.Model.OptionSpec cancelSpec = buildLine.getCommandSpec().findOption("--cancel");
        assertNotNull(cancelSpec, "--cancel must be declared");
        assertFalse(cancelSpec.required(), "--cancel should be optional");

        CommandLine.Model.OptionSpec jobIdSpec = buildLine.getCommandSpec().findOption("--job-id");
        assertNotNull(jobIdSpec, "--job-id must be declared");
        assertFalse(jobIdSpec.required(), "--job-id should be optional");

        CommandLine.Model.OptionSpec pollSpec = buildLine.getCommandSpec().findOption("--poll-interval");
        assertNotNull(pollSpec, "--poll-interval must be declared");
        assertEquals("3", pollSpec.defaultValue(), "--poll-interval default should be 3");
    }

    @Test
    void testBuildCommandParseArgs() {
        CommandLine buildLine = new CommandLine(new GraphBuildCommand());
        assertDoesNotThrow(() -> buildLine.parseArgs(
                "--fact-sheet-id", "42", "--wait", "--poll-interval", "5"),
                "valid build args should parse without error");
    }

    @Test
    void testBuildCommandParseStatusArgs() {
        CommandLine buildLine = new CommandLine(new GraphBuildCommand());
        assertDoesNotThrow(() -> buildLine.parseArgs(
                "--fact-sheet-id", "42", "--status", "--job-id", "abc-123"),
                "status check args should parse without error");
    }

    @Test
    void testBuildCommandParseCancelArgs() {
        CommandLine buildLine = new CommandLine(new GraphBuildCommand());
        assertDoesNotThrow(() -> buildLine.parseArgs(
                "--fact-sheet-id", "42", "--cancel", "--job-id", "abc-123"),
                "cancel args should parse without error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph source-chunks
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testSourceChunksCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "source-chunks", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("source-chunks") || output.contains("provenance"),
                "source-chunks help should mention provenance or source-chunks");
    }

    @Test
    void testSourceChunksRequiresNodeId() {
        // source-chunks requires a positional node ID parameter
        int exitCode = cmd.execute("graph", "source-chunks");
        assertNotEquals(0, exitCode, "source-chunks without node ID should fail");
    }

    @Test
    void testSourceChunksParseArgs() {
        CommandLine chunksLine = new CommandLine(new GraphSourceChunksCommand());
        assertDoesNotThrow(() -> chunksLine.parseArgs("abc-def-123"),
                "source-chunks with node ID should parse without error");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph weights
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testWeightsCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "weights", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("weights"), "weights help should mention 'weights'");
    }

    @Test
    void testWeightsSubcommandsRegistered() {
        CommandLine graphCmd = cmd.getSubcommands().get("graph");
        CommandLine weightsCmd = graphCmd.getSubcommands().get("weights");
        assertNotNull(weightsCmd, "'weights' must be registered under 'graph'");

        Map<String, CommandLine> subcommands = weightsCmd.getSubcommands();
        assertTrue(subcommands.containsKey("list"), "missing 'list' under weights");
        assertTrue(subcommands.containsKey("set"), "missing 'set' under weights");
        assertTrue(subcommands.containsKey("remove"), "missing 'remove' under weights");
        assertTrue(subcommands.containsKey("preview"), "missing 'preview' under weights");
        assertTrue(subcommands.containsKey("feedback"), "missing 'feedback' under weights");
        assertEquals(5, subcommands.size(),
                "Expected exactly 5 subcommands under 'weights'");
    }

    @Test
    void testWeightsListHasSourceIdOption() {
        CommandLine weightsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("weights");
        CommandLine listCmd = weightsCmd.getSubcommands().get("list");
        assertNotNull(listCmd, "list subcommand must be registered under weights");
        CommandLine.Model.OptionSpec sourceSpec = listCmd.getCommandSpec().findOption("--source-id");
        assertNotNull(sourceSpec, "--source-id must be declared on list");
        assertFalse(sourceSpec.required(), "--source-id should be optional on list");
    }

    @Test
    void testWeightsSetOptions() {
        CommandLine weightsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("weights");
        CommandLine setCmd = weightsCmd.getSubcommands().get("set");
        assertNotNull(setCmd, "set subcommand must be registered under weights");

        CommandLine.Model.OptionSpec sourceSpec = setCmd.getCommandSpec().findOption("--source-id");
        assertNotNull(sourceSpec, "--source-id must be declared");
        assertTrue(sourceSpec.required(), "--source-id should be required");

        CommandLine.Model.OptionSpec weightSpec = setCmd.getCommandSpec().findOption("--weight");
        assertNotNull(weightSpec, "--weight must be declared");
        assertTrue(weightSpec.required(), "--weight should be required");

        CommandLine.Model.OptionSpec topicSpec = setCmd.getCommandSpec().findOption("--topic");
        assertNotNull(topicSpec, "--topic must be declared");
        assertFalse(topicSpec.required(), "--topic should be optional");
    }

    @Test
    void testWeightsRemoveOptions() {
        CommandLine weightsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("weights");
        CommandLine removeCmd = weightsCmd.getSubcommands().get("remove");
        assertNotNull(removeCmd, "remove subcommand must be registered under weights");

        CommandLine.Model.OptionSpec sourceSpec = removeCmd.getCommandSpec().findOption("--source-id");
        assertNotNull(sourceSpec, "--source-id must be declared");
        assertTrue(sourceSpec.required(), "--source-id should be required");

        CommandLine.Model.OptionSpec topicSpec = removeCmd.getCommandSpec().findOption("--topic");
        assertNotNull(topicSpec, "--topic must be declared");
        assertFalse(topicSpec.required(), "--topic should be optional");
    }

    @Test
    void testWeightsPreviewOptions() {
        CommandLine weightsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("weights");
        CommandLine previewCmd = weightsCmd.getSubcommands().get("preview");
        assertNotNull(previewCmd, "preview subcommand must be registered under weights");

        CommandLine.Model.OptionSpec querySpec = previewCmd.getCommandSpec().findOption("--query");
        assertNotNull(querySpec, "--query must be declared");
        assertTrue(querySpec.required(), "--query should be required");

        CommandLine.Model.OptionSpec maxSpec = previewCmd.getCommandSpec().findOption("--max-results");
        assertNotNull(maxSpec, "--max-results must be declared");
        assertEquals("10", maxSpec.defaultValue(), "--max-results default should be 10");
    }

    @Test
    void testWeightsFeedbackOptions() {
        CommandLine weightsCmd = cmd.getSubcommands().get("graph")
                .getSubcommands().get("weights");
        CommandLine feedbackCmd = weightsCmd.getSubcommands().get("feedback");
        assertNotNull(feedbackCmd, "feedback subcommand must be registered under weights");

        CommandLine.Model.OptionSpec sourceSpec = feedbackCmd.getCommandSpec().findOption("--source-id");
        assertNotNull(sourceSpec, "--source-id must be declared");
        assertTrue(sourceSpec.required(), "--source-id should be required");

        CommandLine.Model.OptionSpec helpfulSpec = feedbackCmd.getCommandSpec().findOption("--helpful");
        assertNotNull(helpfulSpec, "--helpful must be declared");
        assertTrue(helpfulSpec.required(), "--helpful should be required");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // graph link-sources
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    void testLinkSourcesCommandShowsHelp() {
        int exitCode = cmd.execute("graph", "link-sources", "--help");
        assertEquals(0, exitCode);
        String output = outWriter.toString();
        assertTrue(output.contains("link-sources") || output.contains("link"),
                "link-sources help should be non-empty");
    }

    @Test
    void testLinkSourcesOptions() {
        CommandLine linkCmd = new CommandLine(new GraphLinkSourcesCommand());

        CommandLine.Model.OptionSpec modeSpec = linkCmd.getCommandSpec().findOption("--mode");
        assertNotNull(modeSpec, "--mode must be declared");
        assertEquals("all", modeSpec.defaultValue(), "--mode default should be 'all'");

        CommandLine.Model.OptionSpec connSpec = linkCmd.getCommandSpec().findOption("--connectivity");
        assertNotNull(connSpec, "--connectivity must be declared");

        CommandLine.Model.OptionSpec isolatedSpec = linkCmd.getCommandSpec().findOption("--isolated");
        assertNotNull(isolatedSpec, "--isolated must be declared");

        CommandLine.Model.OptionSpec mostConnSpec = linkCmd.getCommandSpec().findOption("--most-connected");
        assertNotNull(mostConnSpec, "--most-connected must be declared");

        CommandLine.Model.OptionSpec listSpec = linkCmd.getCommandSpec().findOption("--list");
        assertNotNull(listSpec, "--list must be declared");

        CommandLine.Model.OptionSpec limitSpec = linkCmd.getCommandSpec().findOption("--limit");
        assertNotNull(limitSpec, "--limit must be declared");
        assertEquals("10", limitSpec.defaultValue(), "--limit default should be 10");
    }

    @Test
    void testLinkSourcesParseArgsConnectivity() {
        CommandLine linkLine = new CommandLine(new GraphLinkSourcesCommand());
        assertDoesNotThrow(() -> linkLine.parseArgs(
                "--fact-sheet-id", "42", "--connectivity"),
                "link-sources connectivity args should parse without error");
    }

    @Test
    void testLinkSourcesParseArgsIsolated() {
        CommandLine linkLine = new CommandLine(new GraphLinkSourcesCommand());
        assertDoesNotThrow(() -> linkLine.parseArgs(
                "--fact-sheet-id", "42", "--isolated"),
                "link-sources isolated args should parse without error");
    }

    @Test
    void testLinkSourcesParseArgsMostConnected() {
        CommandLine linkLine = new CommandLine(new GraphLinkSourcesCommand());
        assertDoesNotThrow(() -> linkLine.parseArgs(
                "--fact-sheet-id", "42", "--most-connected", "--limit", "5"),
                "link-sources most-connected args should parse without error");
    }

    @Test
    void testLinkSourcesParseArgsModeAll() {
        CommandLine linkLine = new CommandLine(new GraphLinkSourcesCommand());
        assertDoesNotThrow(() -> linkLine.parseArgs(
                "--fact-sheet-id", "42", "--mode", "similarity"),
                "link-sources with mode should parse without error");
    }

    @Test
    void testLinkSourcesParseArgsListLinks() {
        CommandLine linkLine = new CommandLine(new GraphLinkSourcesCommand());
        assertDoesNotThrow(() -> linkLine.parseArgs(
                "--fact-sheet-id", "42", "--list"),
                "link-sources --list should parse without error");
    }
}
