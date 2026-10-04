/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.agent.spin;

import ai.kompile.project.KompileProjectDistribution;
import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Project-owned composition for v2 spins. No acquisition or inference occurs while reading it. */
public final class SpinProjectComposition {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path template;
    private final ObjectNode manifest;
    private final KompileProjectDistribution distribution;
    private final List<SpinDefinition.ModelAsset> models;
    private final String contentId;

    private SpinProjectComposition(Path template, ObjectNode manifest,
                                   KompileProjectDistribution distribution,
                                   List<SpinDefinition.ModelAsset> models, String contentId) {
        this.template = template;
        this.manifest = manifest;
        this.distribution = distribution;
        this.models = List.copyOf(models);
        this.contentId = contentId;
    }

    static SpinProjectComposition load(Path root, JsonNode envelope) throws IOException {
        if (!envelope.isObject()) throw new IOException("Spin v2 requires project.template");
        var fields = envelope.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (!"template".equals(name)) throw new IOException("Unknown spin project field: " + name);
        }
        Path template = requirePath(root, envelope.path("template").asText("project"), "project.template");
        if (!Files.isDirectory(template, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("project.template must be a directory");
        }
        Path file = requirePath(template, "kompile.project.json", "project manifest");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
            throw new IOException("Project manifest must be a regular file of at most 1 MiB");
        }
        JsonNode node = JSON.readTree(file.toFile());
        if (node == null || !node.isObject() || !node.path("schemaVersion").isIntegralNumber()
                || node.path("schemaVersion").intValue() != 1) {
            throw new IOException("Unsupported project manifest schema (expected 1)");
        }
        if (!node.path("distribution").isObject()) {
            throw new IOException("Project-backed spin requires project distribution requirements");
        }
        KompileProjectDistribution distribution;
        try {
            distribution = JSON.treeToValue(node.path("distribution"), KompileProjectDistribution.class);
            distribution.validate();
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid project distribution: " + failure.getMessage(), failure);
        }
        // Acquisition stages are separate from assembly; never ship an incomplete bundle as complete.
        if (!"bundle".equals(distribution.getMaterialization())) {
            throw new IOException("Project spin assembly currently requires bundle materialization; provision/external acquisition is not implemented");
        }
        if (!"assemble".equals(distribution.getBuildTier())) {
            throw new IOException("Run the explicit project build workflow before spin assembly; compilation is not performed by archive assembly");
        }
        for (var software : distribution.getSoftware()) {
            String disposition = software.getMaterialization() == null
                    ? distribution.getMaterialization() : software.getMaterialization();
            if (!"bundle".equals(disposition)) {
                throw new IOException("Software " + software.getId() + " requires an unimplemented acquisition stage: " + disposition);
            }
            requirePath(root, software.getPath(), "software " + software.getId());
        }
        List<SpinDefinition.ModelAsset> models = new ArrayList<>();
        Set<String> modelIds = new HashSet<>();
        String defaultId = node.path("metadata").path("spin.defaultModel").asText("");
        for (JsonNode model : array(node, "models")) {
            String id = requireId(model, "model");
            if (!modelIds.add(id)) throw new IOException("Duplicate project model: " + id);
            Path artifact = requirePath(template, model.path("path").asText(""), "model " + id);
            JsonNode metadata = model.path("metadata");
            String tokenizerPath = metadata.path("tokenizer.path").asText("");
            Path tokenizer = tokenizerPath.isBlank() ? null
                    : requirePath(template, tokenizerPath, "tokenizer " + id);
            boolean isDefault = id.equals(defaultId);
            if (isDefault && ("SOURCE".equalsIgnoreCase(metadata.path("artifact.stage").asText())
                    || "false".equalsIgnoreCase(metadata.path("runtime.ready").asText()))) {
                throw new IOException("Default chat model is not runtime-ready: " + id);
            }
            if (isDefault && tokenizer == null) {
                Path inferred = Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)
                        ? artifact.resolve("tokenizer.json") : artifact.resolveSibling("tokenizer.json");
                tokenizer = requirePath(template, template.relativize(inferred).toString(), "default tokenizer " + id);
            }
            if (tokenizer != null && !Files.isRegularFile(tokenizer, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Model tokenizer must be a file: " + id);
            }
            models.add(new SpinDefinition.ModelAsset(id, root.relativize(artifact).toString(), artifact,
                    tokenizer == null ? null : root.relativize(tokenizer).toString(), tokenizer,
                    isDefault, metadata.path("provider").asText("kompile-local")));
        }
        if (!defaultId.isBlank() && !modelIds.contains(defaultId)) {
            throw new IOException("spin.defaultModel references unknown project model: " + defaultId);
        }
        Set<String> pipelineIds = new HashSet<>();
        for (JsonNode pipeline : array(node, "pipelines")) {
            String id = requireId(pipeline, "pipeline");
            if (!pipelineIds.add(id)) throw new IOException("Duplicate project pipeline: " + id);
            requireOptionalPath(template, pipeline, "definitionPath", "pipeline " + id);
            requireOptionalPath(template, pipeline, "registryPath", "pipeline registry " + id);
            for (JsonNode ref : array(pipeline, "modelRefs")) {
                if (!ref.isTextual() || !modelIds.contains(ref.asText())) {
                    throw new IOException("Pipeline " + id + " references unknown model: " + ref);
                }
            }
        }
        for (JsonNode component : array(node, "components")) {
            requireOptionalPath(template, component, "path", "component");
        }
        // Paths in model metadata are relocatable references, not opaque external machine paths.
        for (JsonNode model : array(node, "models")) {
            var metadataFields = model.path("metadata").fields();
            while (metadataFields.hasNext()) {
                var entry = metadataFields.next();
                if (entry.getKey().endsWith(".path")) {
                    requirePath(template, entry.getValue().asText(), "model metadata " + entry.getKey());
                }
            }
        }
        // Include all template bytes in the release-specific location, not just its manifest version.
        StringBuilder hashes = new StringBuilder();
        try (var walk = Files.walk(template)) {
            for (Path path : walk.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Project template contains symbolic link: " + path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported project asset: " + path);
                hashes.append(template.relativize(path)).append(':').append(HashUtils.sha256Hex(path)).append('\n');
            }
        }
        String contentId = HashUtils.sha256Hex(hashes.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new SpinProjectComposition(template, (ObjectNode) node.deepCopy(), distribution, models,
                contentId.substring(0, 24));
    }

    static Path requirePath(Path root, String value, String field) throws IOException {
        if (value == null || value.isBlank() || value.contains("\\") || value.matches("^[A-Za-z]:.*")) {
            throw new IOException(field + " requires a portable project-relative path");
        }
        Path relative;
        try { relative = Path.of(value); }
        catch (RuntimeException failure) { throw new IOException("Invalid " + field + " path", failure); }
        if (relative.isAbsolute() || relative.normalize().startsWith("..") || relative.normalize().toString().isBlank()) {
            throw new IOException(field + " path escapes its root: " + value);
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) throw new IOException(field + " contains parent traversal");
        }
        Path resolved = root.resolve(relative).normalize();
        Path current = root;
        for (Path part : root.relativize(resolved)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException(field + " contains a symbolic link");
        }
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) throw new IOException(field + " is missing: " + value);
        return resolved;
    }

    private static void requireOptionalPath(Path root, JsonNode object, String field, String label) throws IOException {
        if (object.hasNonNull(field) && !object.path(field).asText().isBlank()) {
            requirePath(root, object.path(field).asText(), label);
        }
    }

    private static String requireId(JsonNode object, String label) throws IOException {
        String id = object.path("id").asText("");
        if (!object.isObject() || !id.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")) {
            throw new IOException("Invalid project " + label + " id: " + id);
        }
        return id;
    }

    static JsonNode array(JsonNode object, String field) throws IOException {
        JsonNode node = object.path(field);
        if (node.isMissingNode() || node.isNull()) return JSON.createArrayNode();
        if (!node.isArray()) throw new IOException(field + " must be an array");
        return node;
    }

    public Path template() { return template; }
    public ObjectNode manifest() { return manifest.deepCopy(); }
    public KompileProjectDistribution distribution() { return distribution; }
    public List<SpinDefinition.ModelAsset> models() { return models; }
    public String contentId() { return contentId; }
}
