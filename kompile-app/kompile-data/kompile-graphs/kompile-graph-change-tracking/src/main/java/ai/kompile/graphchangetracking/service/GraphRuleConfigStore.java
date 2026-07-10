package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphRuleConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory + JSON-backed store for {@link GraphRuleConfig}.
 * Persists to ~/.kompile/graph-rule-configs.json on every mutation.
 */
@Component
@Slf4j
public class GraphRuleConfigStore {

    private final ConcurrentHashMap<String, GraphRuleConfig> store = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);
    private final ObjectMapper objectMapper;
    private final Path storePath;

    public GraphRuleConfigStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.storePath = Paths.get(System.getProperty("user.home"), ".kompile", "graph-rule-configs.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(storePath)) return;
        try {
            List<GraphRuleConfig> loaded = objectMapper.readValue(
                    storePath.toFile(), new TypeReference<List<GraphRuleConfig>>() {});
            for (GraphRuleConfig c : loaded) {
                if (c.getRuleId() != null) {
                    if (c.getId() != null && c.getId() >= idSeq.get()) {
                        idSeq.set(c.getId() + 1);
                    }
                    store.put(c.getRuleId(), c);
                }
            }
            log.debug("Loaded {} graph rule configs from {}", store.size(), storePath);
        } catch (IOException e) {
            log.warn("Could not load graph rule configs from {}: {}", storePath, e.getMessage());
        }
    }

    public GraphRuleConfig save(GraphRuleConfig config) {
        config.initDefaults();
        if (config.getId() == null) {
            config.setId(idSeq.getAndIncrement());
        } else {
            config.markUpdated();
        }
        store.put(config.getRuleId(), config);
        persist();
        return config;
    }

    public Optional<GraphRuleConfig> findByRuleId(String ruleId) {
        return Optional.ofNullable(store.get(ruleId));
    }

    public List<GraphRuleConfig> findAll() {
        return new ArrayList<>(store.values());
    }

    public List<GraphRuleConfig> findByEnabledTrue() {
        return store.values().stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .collect(Collectors.toList());
    }

    public boolean existsByRuleId(String ruleId) {
        return store.containsKey(ruleId);
    }

    public void delete(GraphRuleConfig config) {
        if (config.getRuleId() != null) {
            store.remove(config.getRuleId());
            persist();
        }
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void persist() {
        try {
            Files.createDirectories(storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storePath.toFile(), new ArrayList<>(store.values()));
        } catch (IOException e) {
            log.warn("Failed to persist graph rule configs to {}: {}", storePath, e.getMessage());
        }
    }
}
