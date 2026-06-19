package ai.kompile.compute.graph.config;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the runtime configuration for the compute graph engine.
 * Configuration is mutable at runtime via REST API / UI.
 * Persists to ~/.kompile/config/compute-graph-config.json
 */
@Slf4j
public class ComputeGraphConfigService {

    private static final String CONFIG_DIR = ".kompile/config";
    private static final String CONFIG_FILE = "compute-graph-config.json";

    private final AtomicReference<ComputeGraphConfig> config;
    private final ObjectMapper objectMapper;
    private final Path configPath;

    public ComputeGraphConfigService() {
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.configPath = Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
        this.config = new AtomicReference<>(loadOrDefault());
    }

    public ComputeGraphConfig getConfig() {
        return config.get();
    }

    public ComputeGraphConfig updateConfig(ComputeGraphConfig newConfig) {
        config.set(newConfig);
        persist(newConfig);
        log.info("Compute graph configuration updated: enabled={}, scripting={}, drools={}, camel={}, xircuits={}, n8n={}",
                newConfig.isEnabled(), newConfig.isScriptingEnabled(), newConfig.isDroolsEnabled(),
                newConfig.isCamelEnabled(), newConfig.isXircuitsEnabled(), newConfig.isN8nEnabled());
        return newConfig;
    }

    public boolean isEnabled() {
        return config.get().isEnabled();
    }

    public boolean isScriptingEnabled() {
        return config.get().isScriptingEnabled();
    }

    public boolean isDroolsEnabled() {
        return config.get().isDroolsEnabled();
    }

    public boolean isDroolsInferenceEnabled() {
        return config.get().isDroolsInferenceEnabled();
    }

    public int getMaxRuleFiringsPerNode() {
        return config.get().getMaxRuleFiringsPerNode();
    }

    public int getMaxRuleFiringsTotal() {
        return config.get().getMaxRuleFiringsTotal();
    }

    public boolean isCamelEnabled() {
        return config.get().isCamelEnabled();
    }

    public long getCamelRouteTimeoutMs() {
        return config.get().getCamelRouteTimeoutMs();
    }

    public boolean isDroolsDecisionTableEnabled() {
        return config.get().isDroolsDecisionTableEnabled();
    }

    public boolean isXircuitsEnabled() {
        return config.get().isXircuitsEnabled();
    }

    public boolean isN8nEnabled() {
        return config.get().isN8nEnabled();
    }

    public String getXircuitsExecutable() {
        return config.get().getXircuitsExecutable();
    }

    public String getXircuitsPythonExecutable() {
        return config.get().getXircuitsPythonExecutable();
    }

    public String getN8nExecutable() {
        return config.get().getN8nExecutable();
    }

    public String getN8nNpxExecutable() {
        return config.get().getN8nNpxExecutable();
    }

    public long getWorkflowDefaultTimeoutSeconds() {
        return config.get().getWorkflowDefaultTimeoutSeconds();
    }

    private ComputeGraphConfig loadOrDefault() {
        try {
            if (Files.exists(configPath)) {
                byte[] bytes = Files.readAllBytes(configPath);
                ComputeGraphConfig loaded = objectMapper.readValue(bytes, ComputeGraphConfig.class);
                log.info("Loaded compute graph config from {}", configPath);
                return loaded;
            }
        } catch (IOException e) {
            log.warn("Failed to load compute graph config from {}, using defaults", configPath, e);
        }
        return ComputeGraphConfig.builder().build();
    }

    private void persist(ComputeGraphConfig cfg) {
        try {
            Files.createDirectories(configPath.getParent());
            objectMapper.writeValue(configPath.toFile(), cfg);
            log.debug("Persisted compute graph config to {}", configPath);
        } catch (IOException e) {
            log.error("Failed to persist compute graph config to {}", configPath, e);
        }
    }
}
