package ai.kompile.compute.graph.store;

import ai.kompile.compute.graph.model.ComputeArtifact;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Filesystem-backed store for workflow definition files.
 * Stores workflow definitions as JSON files under ~/.kompile/workflows/
 * with a metadata sidecar for each file.
 * <p>
 * Directory layout:
 * <pre>
 * ~/.kompile/workflows/
 *   xircuits/
 *     my-workflow.xircuits         (workflow JSON)
 *     my-workflow.meta.json        (metadata sidecar)
 *   n8n/
 *     my-automation.json           (workflow JSON)
 *     my-automation.meta.json      (metadata sidecar)
 * </pre>
 */
@Slf4j
public class WorkflowFileStore {

    private static final String WORKFLOWS_DIR = ".kompile/workflows";
    private static final String XIRCUITS_SUBDIR = "xircuits";
    private static final String N8N_SUBDIR = "n8n";
    private static final String META_SUFFIX = ".meta.json";

    private final Path workflowsRoot;
    private final ObjectMapper objectMapper;

    public WorkflowFileStore() {
        this(Paths.get(System.getProperty("user.home"), WORKFLOWS_DIR));
    }

    public WorkflowFileStore(Path workflowsRoot) {
        this.workflowsRoot = workflowsRoot;
        this.objectMapper = JsonUtils.newStandardMapper().enable(SerializationFeature.INDENT_OUTPUT);
        ensureDirectories();
    }

    /**
     * Save a workflow definition file.
     *
     * @param engineType "xircuits" or "n8n"
     * @param name       workflow name (used as filename, sans extension)
     * @param content    the workflow JSON content
     * @param metadata   optional metadata (description, tags, etc.)
     * @return the stored artifact descriptor
     */
    public ComputeArtifact save(String engineType, String name, String content,
                                 Map<String, Object> metadata) throws IOException {
        Path dir = resolveEngineDir(engineType);
        String extension = getExtension(engineType);
        Path filePath = dir.resolve(sanitizeFilename(name) + extension);
        Path metaPath = dir.resolve(sanitizeFilename(name) + META_SUFFIX);

        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        // Build metadata sidecar
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("engineType", engineType);
        meta.put("createdAt", Instant.now().toString());
        meta.put("updatedAt", Instant.now().toString());
        meta.put("sizeBytes", content.length());
        if (metadata != null) {
            meta.putAll(metadata);
        }
        objectMapper.writeValue(metaPath.toFile(), meta);

        log.info("Saved {} workflow '{}' to {}", engineType, name, filePath);

        return ComputeArtifact.builder()
                .id(engineType + "/" + sanitizeFilename(name))
                .name(name)
                .contentType(getContentType(engineType))
                .storageUri(filePath.toAbsolutePath().toString())
                .sizeBytes(content.length())
                .metadata(meta)
                .build();
    }

    /**
     * Load a workflow definition by engine type and name.
     */
    public Optional<String> load(String engineType, String name) throws IOException {
        Path dir = resolveEngineDir(engineType);
        String extension = getExtension(engineType);
        Path filePath = dir.resolve(sanitizeFilename(name) + extension);

        if (!Files.exists(filePath)) return Optional.empty();
        return Optional.of(Files.readString(filePath, StandardCharsets.UTF_8));
    }

    /**
     * Load workflow metadata.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> loadMetadata(String engineType, String name) throws IOException {
        Path dir = resolveEngineDir(engineType);
        Path metaPath = dir.resolve(sanitizeFilename(name) + META_SUFFIX);

        if (!Files.exists(metaPath)) return Optional.empty();
        return Optional.of(objectMapper.readValue(metaPath.toFile(), Map.class));
    }

    /**
     * List all workflows for a given engine type.
     */
    public List<ComputeArtifact> list(String engineType) throws IOException {
        Path dir = resolveEngineDir(engineType);
        if (!Files.exists(dir)) return List.of();

        String extension = getExtension(engineType);
        List<ComputeArtifact> results = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(extension))
                    .sorted()
                    .forEach(filePath -> {
                        try {
                            String filename = filePath.getFileName().toString();
                            String name = filename.substring(0, filename.length() - extension.length());

                            Map<String, Object> meta = Map.of();
                            Path metaPath = dir.resolve(name + META_SUFFIX);
                            if (Files.exists(metaPath)) {
                                meta = objectMapper.readValue(metaPath.toFile(), Map.class);
                            }

                            results.add(ComputeArtifact.builder()
                                    .id(engineType + "/" + name)
                                    .name((String) meta.getOrDefault("name", name))
                                    .contentType(getContentType(engineType))
                                    .storageUri(filePath.toAbsolutePath().toString())
                                    .sizeBytes(Files.size(filePath))
                                    .metadata(meta)
                                    .build());
                        } catch (IOException e) {
                            log.warn("Error reading workflow file {}: {}", filePath, e.getMessage());
                        }
                    });
        }

        return results;
    }

    /**
     * List all workflows across all engine types.
     */
    public List<ComputeArtifact> listAll() throws IOException {
        List<ComputeArtifact> all = new ArrayList<>();
        all.addAll(list(XIRCUITS_SUBDIR));
        all.addAll(list(N8N_SUBDIR));
        return all;
    }

    /**
     * Delete a workflow by engine type and name.
     */
    public boolean delete(String engineType, String name) throws IOException {
        Path dir = resolveEngineDir(engineType);
        String extension = getExtension(engineType);
        Path filePath = dir.resolve(sanitizeFilename(name) + extension);
        Path metaPath = dir.resolve(sanitizeFilename(name) + META_SUFFIX);

        boolean deleted = Files.deleteIfExists(filePath);
        Files.deleteIfExists(metaPath);

        if (deleted) {
            log.info("Deleted {} workflow '{}'", engineType, name);
        }
        return deleted;
    }

    /**
     * Check if a workflow exists.
     */
    public boolean exists(String engineType, String name) {
        Path dir = resolveEngineDir(engineType);
        String extension = getExtension(engineType);
        return Files.exists(dir.resolve(sanitizeFilename(name) + extension));
    }

    public Path getWorkflowsRoot() {
        return workflowsRoot;
    }

    private Path resolveEngineDir(String engineType) {
        return workflowsRoot.resolve(engineType);
    }

    private String getExtension(String engineType) {
        return switch (engineType) {
            case XIRCUITS_SUBDIR -> ".xircuits";
            case N8N_SUBDIR -> ".json";
            default -> ".json";
        };
    }

    private String getContentType(String engineType) {
        return switch (engineType) {
            case XIRCUITS_SUBDIR -> "application/x-xircuits+json";
            case N8N_SUBDIR -> "application/x-n8n+json";
            default -> "application/json";
        };
    }

    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(workflowsRoot.resolve(XIRCUITS_SUBDIR));
            Files.createDirectories(workflowsRoot.resolve(N8N_SUBDIR));
        } catch (IOException e) {
            log.error("Failed to create workflow directories at {}: {}", workflowsRoot, e.getMessage());
        }
    }
}
