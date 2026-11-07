/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * User configuration for learning-heavy code-graph builds.
 *
 * <p>{@code code_graph build} is purely structural by default. When this config
 * enables it, the projected KGraph additionally runs the learning lifecycle
 * (KGE embeddings, PSL rules, MEBN theory) through the project-local learning
 * subprocess — the same heavy pipeline the crawl path uses — so the hybrid
 * reasoner can fuse real signals over code entities.</p>
 *
 * <p>Persisted at project {@code <root>/.kompile/code-graph-reasoning.json} or
 * global {@code ~/.kompile/code-graph-reasoning.json}. Project scope wins when
 * present; otherwise the built-in defaults apply. Everything defaults to OFF:
 * learning launches subprocesses with multi-GB heaps and model-backed passes.</p>
 *
 * <p>Clamps mirror {@code LocalProjectGraphBackend.ReasoningLearningRequest} and
 * the explicit-KGE bounds so any value accepted here is valid downstream.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodeGraphReasoningConfig {

    static final String CONFIG_FILE = "code-graph-reasoning.json";
    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Valid trigger points for the learning pass. */
    public static final String TRIGGER_BUILD = "build";
    public static final String TRIGGER_MANUAL = "manual";
    private static final Set<String> VALID_TRIGGERS = Set.of(TRIGGER_BUILD, TRIGGER_MANUAL);

    private static final Set<String> VALID_KGE_ALGORITHMS = Set.of("TRANSE", "ROTATE");

    @JsonProperty
    private boolean enabled = false;

    /** Train TRANSE/ROTATE embeddings as part of the learning pass. */
    @JsonProperty
    private boolean kgeTraining = false;

    @JsonProperty
    private String kgeAlgorithm = "ROTATE";

    /** Embedding dimension; bounded 2..256 like the explicit KGE tool. */
    @JsonProperty
    private int kgeDim = 32;

    /** Embedding epochs; bounded 1..500 like the explicit KGE tool. */
    @JsonProperty
    private int kgeEpochs = 8;

    @JsonProperty
    private double kgeLearningRate = 0.05;

    @JsonProperty
    private int pslSteps = 1;

    @JsonProperty
    private int mebnEpochs = 1;

    @JsonProperty
    private int consensusRounds = 1;

    @JsonProperty
    private double consensusWeight = 0.35;

    @JsonProperty
    private int maxRelationTypes = 25;

    /** Skip learning entirely below this projected entity count. */
    @JsonProperty
    private int minGraphSize = 100;

    /** When the learning pass runs: "build" and/or "manual". */
    @JsonProperty
    private List<String> triggers = List.of(TRIGGER_BUILD);

    /** Non-serialized marker of which file this config was loaded from. */
    @JsonIgnore
    private Path loadedFrom;

    // --- Accessors ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isKgeTraining() { return kgeTraining; }
    public void setKgeTraining(boolean kgeTraining) { this.kgeTraining = kgeTraining; }

    public String getKgeAlgorithm() { return kgeAlgorithm; }
    public void setKgeAlgorithm(String kgeAlgorithm) { this.kgeAlgorithm = kgeAlgorithm; }

    public int getKgeDim() { return kgeDim; }
    public void setKgeDim(int kgeDim) { this.kgeDim = kgeDim; }

    public int getKgeEpochs() { return kgeEpochs; }
    public void setKgeEpochs(int kgeEpochs) { this.kgeEpochs = kgeEpochs; }

    public double getKgeLearningRate() { return kgeLearningRate; }
    public void setKgeLearningRate(double kgeLearningRate) { this.kgeLearningRate = kgeLearningRate; }

    public int getPslSteps() { return pslSteps; }
    public void setPslSteps(int pslSteps) { this.pslSteps = pslSteps; }

    public int getMebnEpochs() { return mebnEpochs; }
    public void setMebnEpochs(int mebnEpochs) { this.mebnEpochs = mebnEpochs; }

    public int getConsensusRounds() { return consensusRounds; }
    public void setConsensusRounds(int consensusRounds) { this.consensusRounds = consensusRounds; }

    public double getConsensusWeight() { return consensusWeight; }
    public void setConsensusWeight(double consensusWeight) { this.consensusWeight = consensusWeight; }

    public int getMaxRelationTypes() { return maxRelationTypes; }
    public void setMaxRelationTypes(int maxRelationTypes) { this.maxRelationTypes = maxRelationTypes; }

    public int getMinGraphSize() { return minGraphSize; }
    public void setMinGraphSize(int minGraphSize) { this.minGraphSize = minGraphSize; }

    public List<String> getTriggers() { return triggers; }
    public void setTriggers(List<String> triggers) { this.triggers = triggers; }

    @JsonIgnore
    public Path getLoadedFrom() { return loadedFrom; }

    /** True when the learning pass should run for the given trigger point. */
    public boolean triggersOn(String trigger) {
        return enabled && triggers != null && triggers.contains(trigger);
    }

    /**
     * Clamp every numeric knob and validate enums in place. Values outside the
     * downstream-accepted ranges are pulled to the nearest accepted value so a
     * saved config can never poison the learning subprocess.
     */
    public CodeGraphReasoningConfig normalize() {
        String algorithm = kgeAlgorithm == null ? "" : kgeAlgorithm.trim().toUpperCase(Locale.ROOT);
        this.kgeAlgorithm = VALID_KGE_ALGORITHMS.contains(algorithm) ? algorithm : "ROTATE";
        this.kgeDim = clamp(kgeDim, 2, 256);
        this.kgeEpochs = clamp(kgeEpochs, 1, 500);
        this.kgeLearningRate = Math.max(0.001, Math.min(1.0, kgeLearningRate));
        this.pslSteps = Math.max(1, pslSteps);
        this.mebnEpochs = Math.max(1, mebnEpochs);
        this.consensusRounds = Math.max(1, consensusRounds);
        this.consensusWeight = Math.max(0.0, Math.min(1.0, consensusWeight));
        this.maxRelationTypes = Math.max(1, maxRelationTypes);
        this.minGraphSize = Math.max(0, minGraphSize);
        List<String> cleaned = new ArrayList<>();
        if (triggers != null) {
            for (String trigger : triggers) {
                if (trigger == null) continue;
                String value = trigger.trim().toLowerCase(Locale.ROOT);
                if (VALID_TRIGGERS.contains(value) && !cleaned.contains(value)) cleaned.add(value);
            }
        }
        this.triggers = cleaned.isEmpty() ? List.of(TRIGGER_BUILD) : List.copyOf(cleaned);
        return this;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // --- Persistence (mirrors ChatConfig) ---

    public static Path globalConfigPath() {
        return KompileHome.homeDirectory().toPath().resolve(CONFIG_FILE);
    }

    public static Path projectConfigPath(Path projectRoot) {
        Path root = projectRoot != null ? projectRoot
                : KompileHome.resolvedProjectDirectory().toPath().toAbsolutePath().normalize();
        return root.toAbsolutePath().normalize().resolve(".kompile").resolve(CONFIG_FILE);
    }

    public static CodeGraphReasoningConfig loadGlobal() {
        return loadFrom(globalConfigPath());
    }

    public static CodeGraphReasoningConfig loadProject(Path projectRoot) {
        return loadFrom(projectConfigPath(projectRoot));
    }

    /**
     * Project scope wins when its file exists; otherwise global; otherwise the
     * all-off defaults.
     */
    public static CodeGraphReasoningConfig loadEffective(Path projectRoot) {
        Path projectPath = projectConfigPath(projectRoot);
        if (Files.isRegularFile(projectPath)) {
            CodeGraphReasoningConfig project = loadFrom(projectPath);
            if (project != null) return project;
        }
        CodeGraphReasoningConfig global = loadFrom(globalConfigPath());
        return global != null ? global : new CodeGraphReasoningConfig().normalize();
    }

    /** Apply a partial JSON object to the effective config and persist a project-local override. */
    public static CodeGraphReasoningConfig updateProject(Path projectRoot, String json)
            throws IOException {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("config_json is required");
        }
        CodeGraphReasoningConfig config = loadEffective(projectRoot);
        MAPPER.readerForUpdating(config).readValue(json);
        config.normalize();
        Path projectPath = projectConfigPath(projectRoot);
        config.saveTo(projectPath);
        config.loadedFrom = projectPath;
        return config;
    }

    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    static CodeGraphReasoningConfig loadFrom(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            CodeGraphReasoningConfig config =
                    MAPPER.readValue(path.toFile(), CodeGraphReasoningConfig.class);
            config.normalize();
            config.loadedFrom = path;
            return config;
        } catch (IOException e) {
            System.err.println("Warning: Could not load code-graph reasoning config from "
                    + path + ": " + e.getMessage());
            return null;
        }
    }

    public void saveProject(Path projectRoot) throws IOException {
        saveTo(projectConfigPath(projectRoot));
    }

    public void saveGlobal() throws IOException {
        saveTo(globalConfigPath());
    }

    /** Persist back to the scope this config was loaded from, or project scope if new. */
    public void saveLoadedOrProject(Path projectRoot) throws IOException {
        if (loadedFrom != null) {
            saveTo(loadedFrom);
        } else {
            saveProject(projectRoot);
        }
    }

    private void saveTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), this);
    }
}
