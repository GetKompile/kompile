package ai.kompile.graphchangetracking.service;

import ai.kompile.graphchangetracking.domain.GraphUpdatePipelineConfig;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory + JSON-backed store for {@link GraphUpdatePipelineConfig}.
 * Persists to ~/.kompile/graph-pipeline-configs.json on every mutation.
 */
@Component
@Slf4j
public class GraphUpdatePipelineConfigStore {

    private final ConcurrentHashMap<String, GraphUpdatePipelineConfig> store = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);
    private final ObjectMapper objectMapper;
    private final Path storePath;

    public GraphUpdatePipelineConfigStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.storePath = Paths.get(System.getProperty("user.home"), ".kompile", "graph-pipeline-configs.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(storePath)) return;
        try {
            List<GraphUpdatePipelineConfig> loaded = objectMapper.readValue(
                    storePath.toFile(), new TypeReference<List<GraphUpdatePipelineConfig>>() {});
            for (GraphUpdatePipelineConfig c : loaded) {
                if (c.getPipelineId() != null) {
                    if (c.getId() != null && c.getId() >= idSeq.get()) {
                        idSeq.set(c.getId() + 1);
                    }
                    store.put(c.getPipelineId(), c);
                }
            }
            log.debug("Loaded {} graph pipeline configs from {}", store.size(), storePath);
        } catch (IOException e) {
            log.warn("Could not load graph pipeline configs from {}: {}", storePath, e.getMessage());
        }
    }

    public GraphUpdatePipelineConfig save(GraphUpdatePipelineConfig config) {
        config.initDefaults();
        if (config.getId() == null) {
            config.setId(idSeq.getAndIncrement());
        } else {
            config.markUpdated();
        }
        store.put(config.getPipelineId(), config);
        persist();
        return config;
    }

    public Optional<GraphUpdatePipelineConfig> findByPipelineId(String pipelineId) {
        return Optional.ofNullable(store.get(pipelineId));
    }

    public List<GraphUpdatePipelineConfig> findAll() {
        return new ArrayList<>(store.values());
    }

    public List<GraphUpdatePipelineConfig> findByEnabledTrue() {
        return store.values().stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .collect(Collectors.toList());
    }

    public List<GraphUpdatePipelineConfig> findEnabledByChannel(String channelName) {
        return store.values().stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled())
                        && c.getTriggerChannels() != null
                        && c.getTriggerChannels().contains(channelName))
                .sorted(Comparator.comparingInt(
                        (GraphUpdatePipelineConfig c) -> c.getPriority() != null ? c.getPriority() : 0)
                        .reversed())
                .collect(Collectors.toList());
    }

    public List<GraphUpdatePipelineConfig> findByTargetFactSheetId(Long factSheetId) {
        return store.values().stream()
                .filter(c -> factSheetId.equals(c.getTargetFactSheetId()))
                .collect(Collectors.toList());
    }

    public void delete(GraphUpdatePipelineConfig config) {
        if (config.getPipelineId() != null) {
            store.remove(config.getPipelineId());
            persist();
        }
    }

    public void deleteByPipelineId(String pipelineId) {
        store.remove(pipelineId);
        persist();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void persist() {
        try {
            Files.createDirectories(storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storePath.toFile(), new ArrayList<>(store.values()));
        } catch (IOException e) {
            log.warn("Failed to persist graph pipeline configs to {}: {}", storePath, e.getMessage());
        }
    }
}
