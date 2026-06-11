package ai.kompile.compute.graph.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Persistent registry for Camel route definitions.
 * Routes are stored as JSON files under ~/.kompile/camel-routes/ for
 * management through the UI and MCP tools.
 */
@Slf4j
public class CamelRouteRegistry {

    private static final String ROUTES_DIR = ".kompile/camel-routes";

    private final Path routesPath;
    private final ObjectMapper objectMapper;
    private final Map<String, RouteDefinitionRecord> cache = new ConcurrentHashMap<>();

    public CamelRouteRegistry() {
        this.routesPath = Paths.get(System.getProperty("user.home"), ROUTES_DIR);
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        loadAll();
    }

    /**
     * A persisted route definition.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteDefinitionRecord {
        private String id;
        private String name;
        private String description;
        private String script;
        private String format;
        private Map<String, String> metadata;
        private Instant createdAt;
        private Instant updatedAt;
        private boolean enabled;
    }

    public RouteDefinitionRecord save(RouteDefinitionRecord record) {
        if (record.getId() == null || record.getId().isBlank()) {
            record.setId(UUID.randomUUID().toString());
        }
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(Instant.now());
        }
        record.setUpdatedAt(Instant.now());

        cache.put(record.getId(), record);
        persist(record);
        return record;
    }

    public Optional<RouteDefinitionRecord> get(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public List<RouteDefinitionRecord> list() {
        return new ArrayList<>(cache.values());
    }

    public List<RouteDefinitionRecord> listEnabled() {
        return cache.values().stream()
                .filter(RouteDefinitionRecord::isEnabled)
                .collect(Collectors.toList());
    }

    public boolean delete(String id) {
        RouteDefinitionRecord removed = cache.remove(id);
        if (removed != null) {
            try {
                Files.deleteIfExists(routesPath.resolve(id + ".json"));
            } catch (IOException e) {
                log.warn("Failed to delete route file for {}", id, e);
            }
            return true;
        }
        return false;
    }

    private void persist(RouteDefinitionRecord record) {
        try {
            Files.createDirectories(routesPath);
            objectMapper.writeValue(routesPath.resolve(record.getId() + ".json").toFile(), record);
        } catch (IOException e) {
            log.error("Failed to persist route definition {}", record.getId(), e);
        }
    }

    private void loadAll() {
        try {
            if (Files.exists(routesPath)) {
                Files.list(routesPath)
                        .filter(p -> p.toString().endsWith(".json"))
                        .forEach(p -> {
                            try {
                                RouteDefinitionRecord record = objectMapper.readValue(p.toFile(), RouteDefinitionRecord.class);
                                cache.put(record.getId(), record);
                            } catch (IOException e) {
                                log.warn("Failed to load route definition from {}", p, e);
                            }
                        });
                log.info("Loaded {} Camel route definitions", cache.size());
            }
        } catch (IOException e) {
            log.warn("Failed to scan route definitions directory", e);
        }
    }
}
