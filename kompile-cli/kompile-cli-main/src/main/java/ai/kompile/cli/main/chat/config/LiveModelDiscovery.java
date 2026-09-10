/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.CliAgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches model and vendor-native thinking capabilities from the selected vendor.
 *
 * <p>No model ids or thinking values are stored here. Native agents expose their
 * own discovery command; API vendors expose their own model endpoint.</p>
 */
public final class LiveModelDiscovery {
    private static final long PROCESS_TIMEOUT_SECONDS = 20;
    private static final int MAX_NATIVE_OUTPUT_CHARS = 4 * 1024 * 1024;
    private static final ObjectMapper MAPPER = ai.kompile.cli.common.util.JsonUtils.standardMapper();

    private LiveModelDiscovery() {
    }

    public record Variant(String value, String label, String description) {
        public Variant(String value, String label) {
            this(value, label, "");
        }

        public Variant {
            value = value == null ? "" : value.trim();
            label = label == null || label.isBlank() ? value : label.trim();
            description = description == null ? "" : description.trim();
        }
    }

    public record Model(
            String id,
            List<String> variants,
            Map<String, String> variantLabels,
            String defaultVariant,
            boolean reasoningMandatory,
            String capabilitySource) {
        public Model(String id, List<String> variants) {
            this(id, variants, Map.of(), "", false, "");
        }

        public Model(
                String id,
                List<String> variants,
                Map<String, String> variantLabels,
                String defaultVariant) {
            this(id, variants, variantLabels, defaultVariant, false, "");
        }

        public Model {
            id = id == null ? "" : id.trim();
            variants = variants == null ? List.of() : List.copyOf(variants);
            Map<String, String> labels = new LinkedHashMap<>();
            if (variantLabels != null) {
                variantLabels.forEach((value, label) -> {
                    if (value != null && !value.isBlank()
                            && label != null && !label.isBlank()) {
                        labels.put(value, label.trim());
                    }
                });
            }
            variantLabels = Map.copyOf(labels);
            defaultVariant = defaultVariant == null ? "" : defaultVariant.trim();
            capabilitySource = capabilitySource == null ? "" : capabilitySource.trim();
        }

        public List<Variant> thinkingVariants() {
            return variants.stream()
                    .map(value -> new Variant(value, variantLabels.getOrDefault(value, value)))
                    .toList();
        }
    }

    private static final class ModelBuilder {
        private final String id;
        private final LinkedHashSet<String> variants = new LinkedHashSet<>();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private String defaultVariant = "";
        private boolean reasoningMandatory;
        private String capabilitySource = "";

        private ModelBuilder(String id) {
            this.id = id;
        }

        private void addVariant(String value, String label) {
            if (value == null || value.isBlank()) {
                return;
            }
            String normalized = value.trim();
            variants.add(normalized);
            if (label != null && !label.isBlank()) {
                labels.put(normalized, label.trim());
            }
        }

        private Model build() {
            return new Model(
                    id,
                    List.copyOf(variants),
                    labels,
                    defaultVariant,
                    reasoningMandatory,
                    capabilitySource);
        }
    }

    /** Discover models from a native provider-owned command. */
    static List<Model> discoverNative(String provider) {
        AgentProvider agent = findAgent(provider);
        if (agent == null || agent.getModelListCommand() == null
                || agent.getModelListCommand().isEmpty()) {
            return List.of();
        }
        return discoverNative(agent);
    }

    private static AgentProvider findAgent(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return CliAgentRegistry.loadAll().stream()
                .filter(agent -> provider.equalsIgnoreCase(agent.getCommand())
                        || provider.equalsIgnoreCase(agent.getName()))
                .findFirst()
                .orElse(null);
    }

    private static List<Model> discoverNative(AgentProvider agent) {
        Process process = null;
        ExecutorService readerExecutor = null;
        Future<String> outputTask = null;
        AtomicBoolean outputOverflow = new AtomicBoolean(false);
        try {
            process = new ProcessBuilder(List.copyOf(agent.getModelListCommand()))
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "provider-model-list-reader");
                thread.setDaemon(true);
                return thread;
            });
            outputTask = readerExecutor.submit(() -> {
                StringBuilder collected = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        if (collected.length() >= MAX_NATIVE_OUTPUT_CHARS) {
                            outputOverflow.set(true);
                            continue;
                        }
                        int retained = Math.min(read,
                                MAX_NATIVE_OUTPUT_CHARS - collected.length());
                        collected.append(buffer, 0, retained);
                        if (retained < read) outputOverflow.set(true);
                    }
                }
                return collected.toString();
            });
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return List.of();
            }
            if (process.exitValue() != 0) return List.of();
            String retainedOutput = outputTask.get(2, TimeUnit.SECONDS);
            return outputOverflow.get() ? List.of() : parseNativeOutput(retainedOutput);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (process != null) {
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
            if (outputTask != null && !outputTask.isDone()) outputTask.cancel(true);
            if (readerExecutor != null) {
                readerExecutor.shutdownNow();
                try {
                    readerExecutor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static List<Model> parseNativeOutput(String output) {
        Map<String, ModelBuilder> models = new LinkedHashMap<>();
        for (String line : output == null ? new String[0] : output.split("\\R")) {
            String value = line.trim();
            if (value.matches("[^\\s/]+/[^\\s/]+")) {
                models.computeIfAbsent(value, ModelBuilder::new);
            }
        }

        for (JsonNode node : jsonObjects(output)) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            String provider = node.path("providerID").asText(null);
            String modelId = id.contains("/") || provider == null || provider.isBlank()
                    ? id : provider + "/" + id;
            ModelBuilder model = models.computeIfAbsent(modelId, ModelBuilder::new);
            addCapabilityMetadata(node, model, "opencode");
        }

        return models.values().stream()
                .map(ModelBuilder::build)
                .toList();
    }

    static List<Model> parseHttpModels(String body, String provider) {
        ProviderModelCatalogs.Descriptor descriptor = ProviderModelCatalogs.find(provider);
        return parseHttpModels(
                body,
                provider,
                descriptor == null ? "STANDARD" : descriptor.responseProfile(),
                descriptor == null ? List.of() : descriptor.idFields());
    }

    static List<Model> parseHttpModels(
            String body,
            String provider,
            String responseProfile) {
        return parseHttpModels(body, provider, responseProfile, List.of());
    }

    static List<Model> parseHttpModels(
            String body,
            String provider,
            String responseProfile,
            List<String> idFields) {
        String vendor = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String profile = responseProfile == null
                ? "STANDARD" : responseProfile.trim().toUpperCase(Locale.ROOT);
        if (!"GEMINI".equals(profile)) {
            return parseHttpModelsBody(body, vendor, profile, idFields);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode values = root.isArray() ? root : root.path("data");
            if (!values.isArray()) {
                values = root.path("models");
            }
            if (!values.isArray()) {
                values = root.path("result").path("models");
            }
            if (!values.isArray()) {
                return List.of();
            }
            ArrayNode filtered = MAPPER.createArrayNode();
            for (JsonNode value : values) {
                JsonNode methods = value.path("supportedGenerationMethods");
                if (!value.has("supportedGenerationMethods") || supportsGenerateContent(methods)) {
                    filtered.add(value);
                }
            }
            return parseHttpModelsBody(
                    MAPPER.writeValueAsString(filtered), vendor, profile, idFields);
        } catch (Exception ignored) {
            return parseHttpModelsBody(body, vendor, profile, idFields);
        }
    }

    private static boolean supportsGenerateContent(JsonNode methods) {
        if (!methods.isArray()) {
            return false;
        }
        for (JsonNode method : methods) {
            if ("generateContent".equals(method.asText())) {
                return true;
            }
        }
        return false;
    }

    static List<Model> parseHttpModels(String body) {
        return parseHttpModelsBody(body, "", "STANDARD", List.of());
    }

    private static List<Model> parseHttpModelsBody(
            String body,
            String vendor,
            String responseProfile,
            List<String> idFields) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode values = root.isArray() ? root : root.path("data");
            if (!values.isArray()) {
                values = root.path("models");
            }
            if (!values.isArray()) {
                values = root.path("result").path("models");
            }
            if (!values.isArray()) {
                return List.of();
            }

            Map<String, ModelBuilder> models = new LinkedHashMap<>();
            for (JsonNode value : values) {
                if ("CODEX".equals(responseProfile)
                        && value.hasNonNull("visibility")
                        && !"list".equalsIgnoreCase(value.path("visibility").asText())) {
                    continue;
                }
                if ("GITHUB_COPILOT".equals(responseProfile)
                        && !copilotPickerModel(value)) {
                    continue;
                }
                // Gemini exposes baseModelId alongside name=models/{id}; the base id
                // is the identifier accepted by generateContent and is therefore the
                // useful selectable value. Other vendors expose id/model directly.
                String id = idFields == null || idFields.isEmpty()
                        ? firstText(value, "id", "model", "slug", "baseModelId", "name")
                        : firstConfiguredText(value, idFields);
                if (id == null || id.isBlank()) {
                    continue;
                }
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                ModelBuilder model = models.computeIfAbsent(id, ModelBuilder::new);
                addCapabilityMetadata(value, model, vendor);
            }
            return models.values().stream()
                    .map(ModelBuilder::build)
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode candidate = node.path(field);
            if (!candidate.isTextual()) continue;
            String value = candidate.asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstConfiguredText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            if (field == null || field.isBlank()) continue;
            JsonNode candidate = node.path(field);
            if (!candidate.isTextual()) continue;
            String value = candidate.asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static boolean copilotPickerModel(JsonNode model) {
        if (!model.path("model_picker_enabled").isBoolean()
                || !model.path("model_picker_enabled").asBoolean()) return false;
        JsonNode type = model.path("capabilities").path("type");
        return type.isTextual() && "chat".equalsIgnoreCase(type.asText());
    }

    private static void addCapabilityMetadata(
            JsonNode model, ModelBuilder builder, String provider) {
        int previousSize = builder.variants.size();
        String vendor = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(vendor)) {
            addAnthropicEffortMetadata(model, builder);
        } else if ("openrouter".equals(vendor)) {
            addOpenRouterReasoningMetadata(model, builder);
        } else if ("radius".equals(vendor)) {
            addRadiusThinkingMetadata(model, builder);
        }

        addVariantNode(model.path("supportedReasoningEfforts"), builder);
        addVariantNode(model.path("supported_reasoning_levels"), builder);
        addVariantNode(model.path("variants"), builder);
        JsonNode capabilities = model.path("capabilities");
        addVariantNode(capabilities.path("variants"), builder);
        addVariantNode(capabilities.path("thinking").path("variants"), builder);
        addVariantNode(capabilities.path("thinking").path("efforts"), builder);
        addVariantNode(capabilities.path("reasoning").path("variants"), builder);
        addVariantNode(capabilities.path("reasoning").path("efforts"), builder);
        addVariantNode(capabilities.path("reasoning_effort"), builder);
        addVariantNode(capabilities.path("reasoningEffort"), builder);

        JsonNode thinking = model.path("thinking");
        addVariantNode(thinking.path("variants"), builder);
        addVariantNode(thinking.path("efforts"), builder);

        JsonNode reasoning = model.path("reasoning");
        addVariantNode(reasoning.path("variants"), builder);
        addVariantNode(reasoning.path("efforts"), builder);

        addVariantNode(model.path("reasoning_effort"), builder);
        addVariantNode(model.path("reasoningEffort"), builder);

        String defaultValue = firstText(model,
                "defaultVariant", "default_variant", "defaultThinking",
                "default_thinking", "defaultReasoningEffort",
                "default_reasoning_effort", "defaultReasoningLevel",
                "default_reasoning_level");
        if (defaultValue == null) {
            defaultValue = firstText(capabilities,
                    "defaultVariant", "default_variant", "defaultValue");
        }
        if (defaultValue == null) {
            defaultValue = firstText(thinking, "defaultVariant", "default_variant", "defaultValue");
        }
        if (defaultValue == null) {
            defaultValue = firstText(reasoning, "defaultVariant", "default_variant", "defaultValue");
        }
        if (defaultValue != null && !defaultValue.isBlank()) {
            builder.defaultVariant = defaultValue.trim();
        }
        if (builder.variants.size() > previousSize) {
            builder.capabilitySource = "opencode".equals(vendor)
                    ? "native:opencode models --verbose"
                    : "live:" + (vendor.isBlank() ? "provider" : vendor) + " model metadata";
        }
    }

    private static void addAnthropicEffortMetadata(JsonNode model, ModelBuilder builder) {
        JsonNode effort = model.path("capabilities").path("effort");
        if (!supported(effort)) {
            return;
        }
        for (String value : List.of("low", "medium", "high", "xhigh", "max")) {
            if (supported(effort.path(value))) {
                builder.addVariant(value, capitalize(value));
            }
        }
    }

    private static void addOpenRouterReasoningMetadata(JsonNode model, ModelBuilder builder) {
        JsonNode reasoning = model.path("reasoning");
        addVariantNode(reasoning.path("supported_efforts"), builder);
        String defaultValue = firstText(reasoning, "default_effort");
        if (defaultValue != null) {
            builder.defaultVariant = defaultValue;
        }
        builder.reasoningMandatory = reasoning.path("mandatory").asBoolean(false);
    }

    private static void addRadiusThinkingMetadata(JsonNode model, ModelBuilder builder) {
        JsonNode levels = model.path("thinkingLevelMap");
        if (!levels.isObject()) return;
        levels.fields().forEachRemaining(entry -> {
            JsonNode mapped = entry.getValue();
            if (mapped != null && mapped.isTextual() && !mapped.asText().isBlank()) {
                builder.addVariant(entry.getKey(), capitalize(entry.getKey()));
            }
        });
    }

    private static boolean supported(JsonNode capability) {
        if (capability == null || capability.isMissingNode() || capability.isNull()) {
            return false;
        }
        return capability.isBoolean()
                ? capability.asBoolean()
                : capability.path("supported").asBoolean(false);
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void addVariantNode(JsonNode values, ModelBuilder builder) {
        if (values == null || values.isMissingNode() || values.isNull()) {
            return;
        }
        if (values.isObject()) {
            values.fields().forEachRemaining(entry -> {
                String value = entry.getKey();
                JsonNode metadata = entry.getValue();
                String label = metadata != null && metadata.isObject()
                        ? firstText(metadata, "label", "displayName", "title") : null;
                builder.addVariant(value, label);
                if (metadata != null && metadata.isObject()) {
                    if (metadata.path("default").asBoolean(false)) {
                        builder.defaultVariant = value;
                    }
                    String nestedDefault = firstText(metadata,
                            "defaultVariant", "default_variant", "defaultValue");
                    if (nestedDefault != null && !nestedDefault.isBlank()) {
                        builder.defaultVariant = nestedDefault.trim();
                    }
                }
            });
            return;
        }
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual()) {
                    builder.addVariant(value.asText(), value.asText());
                    continue;
                }
                if (!value.isObject()) {
                    continue;
                }
                String variant = firstText(
                        value, "value", "id", "key", "name", "reasoningEffort", "effort");
                if (variant == null || variant.isBlank()) {
                    continue;
                }
                String label = firstText(value, "label", "displayName", "title");
                String description = firstText(value, "description");
                if ((label == null || label.isBlank())
                        && description != null && !description.isBlank()) {
                    label = variant + " — " + description;
                }
                builder.addVariant(variant, label);
                if (value.path("default").asBoolean(false)) {
                    builder.defaultVariant = variant;
                }
            }
        }
    }

    static Model merge(Model first, Model second) {
        if (first == null) return second;
        if (second == null) return first;
        LinkedHashSet<String> values = new LinkedHashSet<>(first.variants());
        values.addAll(second.variants());
        Map<String, String> labels = new LinkedHashMap<>(first.variantLabels());
        labels.putAll(second.variantLabels());
        String defaultVariant = second.defaultVariant().isBlank()
                ? first.defaultVariant() : second.defaultVariant();
        String source = second.capabilitySource().isBlank()
                ? first.capabilitySource() : second.capabilitySource();
        return new Model(
                first.id(),
                List.copyOf(values),
                labels,
                defaultVariant,
                first.reasoningMandatory() || second.reasoningMandatory(),
                source);
    }

    private static List<JsonNode> jsonObjects(String output) {
        List<JsonNode> nodes = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return nodes;
        }
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            if (start < 0) {
                if (c == '{') {
                    start = i;
                    depth = 1;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '{') {
                depth++;
            } else if (!quoted && c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        nodes.add(MAPPER.readTree(output.substring(start, i + 1)));
                    } catch (IOException ignored) {
                    }
                    start = -1;
                    quoted = false;
                }
            }
        }
        return nodes;
    }
}
