/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void denseAutostartPolicyRequiresExplicitTrue() {
        // Default: encoder does NOT auto-start (GPU/ND4J must stay cold at boot).
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart(null, null));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("", null));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart(null, "false"));
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("invalid", "true"));
        // Explicit opt-in only.
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart("true", null));
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart(" TRUE ", null));
        assertTrue(SemanticMemoryEngine.resolveDenseAutostart(null, "true"));
        // Property wins over environment.
        assertFalse(SemanticMemoryEngine.resolveDenseAutostart("false", "true"));
    }

    @Test
    void denseEncoderDoesNotAutostartWithoutOptIn() {
        // denseEnabled=true but autostart=false (the default): the ND4J/CUDA
        // backend must NOT initialize at boot. Mode advertises the on-demand action.
        SemanticMemoryEngine engine = new SemanticMemoryEngine(tempDir.resolve("lazy"), true, false);
        try {
            engine.initialize();
            assertFalse(engine.isDenseMode());
            assertTrue(engine.getEncoderMode().contains("not started"));
            assertTrue(engine.getEncoderMode().contains("load_encoder"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void startDenseEncoderIsIdempotentAndDisabledRespectsOptOut() {
        SemanticMemoryEngine disabled = new SemanticMemoryEngine(tempDir.resolve("optout"), false, true);
        try {
            disabled.initialize();
            disabled.startDenseEncoder();
            assertFalse(disabled.isDenseMode());
            assertEquals("tfidf (dense disabled)", disabled.getEncoderMode());
        } finally {
            disabled.shutdown();
        }

        SemanticMemoryEngine lazy = new SemanticMemoryEngine(tempDir.resolve("lazy2"), true, false);
        try {
            lazy.initialize();
            lazy.startDenseEncoder();
            lazy.startDenseEncoder(); // idempotent: no second loader thread
            // Encoder load is asynchronous; mode must not claim dense until ready.
            assertFalse(lazy.isDenseMode() && !lazy.getEncoderMode().startsWith("samediff:"));
        } finally {
            lazy.shutdown();
        }
    }

    @Test
    void densePolicyDefaultsToEnabledUnlessExplicitlyFalse() {
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, null));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, "true"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, ""));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled(null, "invalid"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(null, "false"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(null, " FALSE "));
    }

    @Test
    void densePolicyPropertyTakesPrecedenceOverEnvironment() {
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled("false", "true"));
        assertFalse(SemanticMemoryEngine.resolveDenseEnabled(" FALSE ", null));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("true", "false"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("", "false"));
        assertTrue(SemanticMemoryEngine.resolveDenseEnabled("invalid", "false"));
    }

    @Test
    void disabledDenseModeRemainsLexicalAfterRefreshAndRepeatedInitialize() throws Exception {
        Path project = tempDir.resolve("refresh-project");
        SemanticMemoryEngine engine = new SemanticMemoryEngine(project, false);
        try {
            engine.initialize();
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());
            assertFalse(engine.isDenseMode());

            Path memoryDir = project.resolve(".kompile").resolve("memory");
            Files.createDirectories(memoryDir);
            Path memoryFile = memoryDir.resolve("refresh.md");
            Files.writeString(memoryFile, "denseoptoutrefreshsentinel");

            // Exercise the periodic callback without sleeping for the refresh interval.
            var lastRefreshTime = SemanticMemoryEngine.class.getDeclaredField("lastRefreshTime");
            lastRefreshTime.setAccessible(true);
            lastRefreshTime.setLong(engine, 0L);
            var refreshIndex = SemanticMemoryEngine.class.getDeclaredMethod("refreshIndex");
            refreshIndex.setAccessible(true);
            refreshIndex.invoke(engine);

            engine.initialize();
            List<SemanticMemoryEngine.RetrievedMemory> refreshed =
                    engine.query("denseoptoutrefreshsentinel", 5, 0.01);
            assertFalse(refreshed.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(), refreshed.get(0).entry.sourcePath);

            engine.indexTurn("dense-disabled", 1, "user", "denseoptoutturnsentinel");
            assertEquals("turn:dense-disabled:1",
                    engine.query("denseoptoutturnsentinel", 5, 0.01).get(0).entry.id);
            assertFalse(engine.isDenseMode());
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());
            assertTrue(engine.stats().contains("mode: tfidf (dense disabled)"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void indexesActiveProjectAndKeepsTfidfVocabularyStable() throws Exception {
        Path project = tempDir.resolve("project");
        Path memoryDir = project.resolve(".kompile").resolve("memory");
        Files.createDirectories(memoryDir);
        Path memoryFile = memoryDir.resolve("project-sentinel.md");
        Files.writeString(memoryFile, """
                ---
                name: Project semantic sentinel
                description: Scope-specific retrieval marker
                type: project
                ---
                stable-vector-sentinel is only stored in this active project.
                """);

        SemanticMemoryEngine engine = new SemanticMemoryEngine(project, false);
        try {
            engine.initialize();
            assertFalse(engine.isDenseMode());
            assertEquals("tfidf (dense disabled)", engine.getEncoderMode());

            List<SemanticMemoryEngine.RetrievedMemory> initial =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(initial.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    initial.get(0).entry.sourcePath.toAbsolutePath().normalize());

            engine.indexTurn("stable-test", 1, "user",
                    "unrelated vocabulary should not invalidate the project sentinel");

            List<SemanticMemoryEngine.RetrievedMemory> afterTurn =
                    engine.query("stable vector sentinel", 5, 0.01);
            assertFalse(afterTurn.isEmpty());
            assertEquals(memoryFile.toAbsolutePath().normalize(),
                    afterTurn.get(0).entry.sourcePath.toAbsolutePath().normalize());
        } finally {
            engine.shutdown();
        }
    }
}
