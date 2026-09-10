/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeGraphReasoningConfigTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void redirectKompileHomeToTemp() {
        originalUserHome = System.getProperty("user.home");
        // KompileHome.homeDirectory() reads user.home live — redirecting it here
        // keeps global-scope load/save inside the temp dir. NEVER touch the real
        // ~/.kompile from tests.
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreKompileHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void defaultsAreAllOff() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig().normalize();
        assertFalse(config.isEnabled(), "Learning must be opt-in");
        assertFalse(config.isKgeTraining(), "KGE training must be opt-in");
        assertEquals("ROTATE", config.getKgeAlgorithm());
        assertEquals(32, config.getKgeDim());
        assertEquals(8, config.getKgeEpochs());
        assertEquals(1, config.getPslSteps());
        assertEquals(1, config.getMebnEpochs());
        assertEquals(1, config.getConsensusRounds());
        assertEquals(0.35, config.getConsensusWeight(), 1e-9);
        assertEquals(25, config.getMaxRelationTypes());
        assertEquals(100, config.getMinGraphSize());
        assertEquals(List.of("build"), config.getTriggers());
        assertFalse(config.triggersOn("build"), "disabled config triggers nothing");
        assertTrue(config.triggersOn("manual") == false || !config.isEnabled());
    }

    @Test
    void normalizeClampsNumbersAndValidatesEnums() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setEnabled(true);
        config.setKgeTraining(true);
        config.setKgeAlgorithm("bogus");
        config.setKgeDim(9999);
        config.setKgeEpochs(0);
        config.setKgeLearningRate(50.0);
        config.setPslSteps(-3);
        config.setMebnEpochs(0);
        config.setConsensusRounds(-1);
        config.setConsensusWeight(7.5);
        config.setMaxRelationTypes(0);
        config.setMinGraphSize(-10);
        config.normalize();

        assertEquals("ROTATE", config.getKgeAlgorithm());
        assertEquals(256, config.getKgeDim());
        assertEquals(1, config.getKgeEpochs());
        assertEquals(1.0, config.getKgeLearningRate(), 1e-9);
        assertEquals(1, config.getPslSteps());
        assertEquals(1, config.getMebnEpochs());
        assertEquals(1, config.getConsensusRounds());
        assertEquals(1.0, config.getConsensusWeight(), 1e-9);
        assertEquals(1, config.getMaxRelationTypes());
        assertEquals(0, config.getMinGraphSize());
    }

    @Test
    void dimLowerBoundIsTwo() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setKgeDim(0);
        config.normalize();
        assertEquals(2, config.getKgeDim());
    }

    @Test
    void triggersAreFilteredToKnownValues() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setEnabled(true);
        List<String> withNull = new ArrayList<>(Arrays.asList("BUILD", " manual ", "bogus", "build"));
        withNull.add(null);
        config.setTriggers(withNull);
        config.normalize();
        assertEquals(List.of("build", "manual"), config.getTriggers());
        assertTrue(config.triggersOn("build"));
        assertTrue(config.triggersOn("manual"));
        assertFalse(config.triggersOn("crawl"));
    }

    @Test
    void emptyTriggerListFallsBackToBuild() {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setEnabled(true);
        config.setTriggers(List.of("bogus"));
        config.normalize();
        assertEquals(List.of("build"), config.getTriggers());
    }

    @Test
    void projectScopeWinsOverGlobalWhichWinsOverDefaults() throws IOException {
        Path projectRoot = tempDir.resolve("proj");
        Files.createDirectories(projectRoot.resolve(".kompile"));

        // No files anywhere: defaults, not loaded from anything.
        CodeGraphReasoningConfig defaults = CodeGraphReasoningConfig.loadEffective(projectRoot);
        assertFalse(defaults.isEnabled());
        assertNull(defaults.getLoadedFrom());

        // Global only.
        CodeGraphReasoningConfig global = new CodeGraphReasoningConfig();
        global.setEnabled(true);
        global.setPslSteps(2);
        global.saveGlobal();
        CodeGraphReasoningConfig loadedGlobal =
                CodeGraphReasoningConfig.loadEffective(projectRoot);
        assertTrue(loadedGlobal.isEnabled());
        assertEquals(2, loadedGlobal.getPslSteps());
        assertEquals(CodeGraphReasoningConfig.globalConfigPath(), loadedGlobal.getLoadedFrom());

        // Project overrides global.
        CodeGraphReasoningConfig project = new CodeGraphReasoningConfig();
        project.setEnabled(true);
        project.setPslSteps(3);
        project.saveProject(projectRoot);
        CodeGraphReasoningConfig loadedProject =
                CodeGraphReasoningConfig.loadEffective(projectRoot);
        assertTrue(loadedProject.isEnabled());
        assertEquals(3, loadedProject.getPslSteps());
        assertEquals(CodeGraphReasoningConfig.projectConfigPath(projectRoot),
                loadedProject.getLoadedFrom());
    }

    @Test
    void roundTripPersistsEveryField() throws IOException {
        CodeGraphReasoningConfig config = new CodeGraphReasoningConfig();
        config.setEnabled(true);
        config.setKgeTraining(true);
        config.setKgeAlgorithm("TRANSE");
        config.setKgeDim(64);
        config.setKgeEpochs(25);
        config.setKgeLearningRate(0.02);
        config.setPslSteps(4);
        config.setMebnEpochs(5);
        config.setConsensusRounds(3);
        config.setConsensusWeight(0.6);
        config.setMaxRelationTypes(40);
        config.setMinGraphSize(10);
        config.setTriggers(List.of("manual"));
        config.saveProject(tempDir);

        CodeGraphReasoningConfig reloaded =
                CodeGraphReasoningConfig.loadEffective(tempDir);
        assertTrue(reloaded.isEnabled());
        assertTrue(reloaded.isKgeTraining());
        assertEquals("TRANSE", reloaded.getKgeAlgorithm());
        assertEquals(64, reloaded.getKgeDim());
        assertEquals(25, reloaded.getKgeEpochs());
        assertEquals(0.02, reloaded.getKgeLearningRate(), 1e-9);
        assertEquals(4, reloaded.getPslSteps());
        assertEquals(5, reloaded.getMebnEpochs());
        assertEquals(3, reloaded.getConsensusRounds());
        assertEquals(0.6, reloaded.getConsensusWeight(), 1e-9);
        assertEquals(40, reloaded.getMaxRelationTypes());
        assertEquals(10, reloaded.getMinGraphSize());
        assertEquals(List.of("manual"), reloaded.getTriggers());
        assertFalse(reloaded.triggersOn("build"));
        assertTrue(reloaded.triggersOn("manual"));
    }

    @Test
    void partialProjectUpdatePreservesUnspecifiedEffectiveValues() throws IOException {
        CodeGraphReasoningConfig base = new CodeGraphReasoningConfig();
        base.setKgeAlgorithm("TRANSE");
        base.setKgeDim(64);
        base.setMebnEpochs(4);
        base.saveGlobal();

        CodeGraphReasoningConfig updated = CodeGraphReasoningConfig.updateProject(
                tempDir.resolve("project"), "{\"enabled\":true,\"kgeTraining\":true}");

        assertTrue(updated.isEnabled());
        assertTrue(updated.isKgeTraining());
        assertEquals("TRANSE", updated.getKgeAlgorithm());
        assertEquals(64, updated.getKgeDim());
        assertEquals(4, updated.getMebnEpochs());
        assertEquals(CodeGraphReasoningConfig.projectConfigPath(tempDir.resolve("project")),
                updated.getLoadedFrom());
        assertTrue(Files.isRegularFile(updated.getLoadedFrom()));
    }

    @Test
    void malformedProjectFileFallsBackToGlobal() throws IOException {
        Path projectRoot = tempDir.resolve("broken");
        Files.createDirectories(projectRoot.resolve(".kompile"));
        Files.writeString(projectRoot.resolve(".kompile").resolve("code-graph-reasoning.json"),
                "{ not json ");

        CodeGraphReasoningConfig global = new CodeGraphReasoningConfig();
        global.setEnabled(true);
        global.setMebnEpochs(7);
        global.saveGlobal();

        CodeGraphReasoningConfig effective =
                CodeGraphReasoningConfig.loadEffective(projectRoot);
        assertTrue(effective.isEnabled());
        assertEquals(7, effective.getMebnEpochs());
        assertEquals(CodeGraphReasoningConfig.globalConfigPath(), effective.getLoadedFrom());
    }
}
