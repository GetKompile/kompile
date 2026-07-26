/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.sdx;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.deeplearning4j.llm.generation.ModelIOConfig;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Produces the complete, immutable text-model inputs consumed by the SDX compiler.
 *
 * <p>Hugging Face generation_config.json is sampling metadata, not an SDX graph binding
 * contract. This class keeps those formats separate, normalizes tokenizer_config.json,
 * and derives text-generation.json from the actual SameDiff graph when staging did not
 * receive an already-authored SDX contract.</p>
 */
final class SdxTextModelStager {
    private static final long MAX_JSON_BYTES = 8L * 1024L * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper(
            JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64)
                            .maxStringLength(4 * 1024 * 1024)
                            .maxNumberLength(1000)
                            .build())
                    .build());
    private static final String TOKENIZER = "tokenizer.json";
    private static final String TOKENIZER_CONFIG = "tokenizer_config.json";
    private static final String SPECIAL_TOKENS_MAP = "special_tokens_map.json";
    private static final String ADDED_TOKENS = "added_tokens.json";
    private static final String CHAT_TEMPLATE = "chat_template.jinja";
    private static final String SDX_TEXT_CONFIG = "text-generation.json";
    private static final String HF_MODEL_CONFIG = "config.json";
    private static final String HF_GENERATION_CONFIG = "generation_config.json";

    private SdxTextModelStager() {
    }

    static PreparedTextAssets prepare(
            Path workspace,
            Path canonicalSdz,
            Path operationRoot) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path ignored = operationRoot.toAbsolutePath().normalize();

        Path tokenizer = requireUnique(root, TOKENIZER, ignored,
                "A runnable mobile chat model requires Hugging Face tokenizer.json. "
                        + "SentencePiece tokenizer.model alone is not accepted by the SDX tokenizer.");
        validateTokenizer(tokenizer);

        Path tokenizerConfig = findUnique(root, TOKENIZER_CONFIG, ignored);
        Path specialTokensMap = findUnique(root, SPECIAL_TOKENS_MAP, ignored);
        Path addedTokens = findUnique(root, ADDED_TOKENS, ignored);
        Path chatTemplate = findUnique(root, CHAT_TEMPLATE, ignored);
        validateAddedTokens(tokenizer, addedTokens);
        Path normalizedTokenizerConfig = normalizeTokenizerConfig(
                tokenizerConfig,
                specialTokensMap,
                chatTemplate,
                operationRoot);

        Path authoredSdxConfig = findUnique(root, SDX_TEXT_CONFIG, ignored);
        Path textGenerationConfig;
        if (authoredSdxConfig != null) {
            JsonNode contract = readObject(authoredSdxConfig, "SDX text-generation graph contract");
            validateContract(contract);
            textGenerationConfig = operationRoot.resolve(SDX_TEXT_CONFIG);
            JSON.writerWithDefaultPrettyPrinter().writeValue(
                    textGenerationConfig.toFile(), contract);
        } else {
            Path modelConfig = requireUnique(root, HF_MODEL_CONFIG, ignored,
                    "Kompile could not derive the SDX text-generation graph contract because "
                            + "the Hugging Face config.json companion file is missing.");
            Path generationConfig = findUnique(root, HF_GENERATION_CONFIG, ignored);
            textGenerationConfig = deriveContract(
                    canonicalSdz,
                    modelConfig,
                    generationConfig,
                    normalizedTokenizerConfig,
                    operationRoot.resolve(SDX_TEXT_CONFIG));
        }

        return new PreparedTextAssets(
                tokenizer.toAbsolutePath().normalize(),
                normalizedTokenizerConfig.toAbsolutePath().normalize(),
                textGenerationConfig.toAbsolutePath().normalize());
    }

    private static void validateTokenizer(Path tokenizer) throws IOException {
        JsonNode root = readObject(tokenizer, TOKENIZER);
        if (!root.has("model") || !root.get("model").isObject()) {
            throw new IOException(
                    "tokenizer.json is not a complete Hugging Face tokenizer: missing model object: "
                            + tokenizer);
        }
    }

    private static void validateAddedTokens(Path tokenizer, Path addedTokens) throws IOException {
        if (addedTokens == null) {
            return;
        }
        JsonNode tokenizerRoot = readObject(tokenizer, TOKENIZER);
        Map<String, Integer> embedded = new LinkedHashMap<>();
        JsonNode embeddedTokens = tokenizerRoot.get("added_tokens");
        if (embeddedTokens != null && embeddedTokens.isArray()) {
            for (JsonNode token : embeddedTokens) {
                if (token.isObject()
                        && token.path("content").isTextual()
                        && token.path("id").canConvertToInt()) {
                    embedded.put(token.path("content").asText(), token.path("id").asInt());
                }
            }
        }

        JsonNode sidecar = readJson(addedTokens, ADDED_TOKENS);
        if (sidecar.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = sidecar.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getValue().canConvertToInt()) {
                    throw new IOException(
                            ADDED_TOKENS + " maps '" + entry.getKey() + "' to a non-integer token ID.");
                }
                requireEmbeddedAddedToken(embedded, entry.getKey(), entry.getValue().asInt());
            }
            return;
        }
        if (sidecar.isArray()) {
            for (JsonNode token : sidecar) {
                if (!token.isObject()
                        || !token.path("content").isTextual()
                        || !token.path("id").canConvertToInt()) {
                    throw new IOException(
                            ADDED_TOKENS + " array entries require textual content and integer id.");
                }
                requireEmbeddedAddedToken(
                        embedded, token.path("content").asText(), token.path("id").asInt());
            }
            return;
        }
        throw new IOException(ADDED_TOKENS + " must contain a JSON object or array.");
    }

    private static void requireEmbeddedAddedToken(
            Map<String, Integer> embedded,
            String content,
            int id) throws IOException {
        Integer tokenizerId = embedded.get(content);
        if (tokenizerId == null || tokenizerId != id) {
            throw new IOException(
                    ADDED_TOKENS + " declares '" + content + "' as ID " + id
                            + " but tokenizer.json does not contain the same added token. "
                            + "Regenerate tokenizer.json so the native SDX tokenizer is self-contained.");
        }
    }

    private static Path normalizeTokenizerConfig(
            Path input,
            Path specialTokensMap,
            Path chatTemplate,
            Path operationRoot) throws IOException {
        ObjectNode normalized = input == null
                ? JSON.createObjectNode()
                : ((ObjectNode) readObject(input, TOKENIZER_CONFIG)).deepCopy();

        if (specialTokensMap != null) {
            JsonNode specialTokens = readObject(specialTokensMap, SPECIAL_TOKENS_MAP);
            specialTokens.fields().forEachRemaining(entry -> {
                if (!normalized.has(entry.getKey())) {
                    normalized.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
        }

        String template = selectChatTemplate(normalized.get("chat_template"));
        if ((template == null || template.isBlank()) && chatTemplate != null) {
            template = readTextAsset(chatTemplate, CHAT_TEMPLATE);
        }
        if (template == null || template.isBlank()) {
            throw new IOException(
                    "A runnable mobile chat model requires a usable chat template in "
                            + TOKENIZER_CONFIG + " or " + CHAT_TEMPLATE + ".");
        }
        normalized.put("chat_template", template);

        Files.createDirectories(operationRoot);
        Path output = operationRoot.resolve(TOKENIZER_CONFIG);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), normalized);
        return output;
    }

    private static String selectChatTemplate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            String first = null;
            for (JsonNode candidate : node) {
                if (candidate.isTextual() && first == null) {
                    first = candidate.asText();
                } else if (candidate.isObject()) {
                    JsonNode template = candidate.get("template");
                    if (template != null && template.isTextual()) {
                        if (first == null) {
                            first = template.asText();
                        }
                        if ("default".equals(candidate.path("name").asText())) {
                            return template.asText();
                        }
                    }
                }
            }
            return first;
        }
        if (node.isObject()) {
            JsonNode preferred = node.get("default");
            if (preferred != null && preferred.isTextual()) {
                return preferred.asText();
            }
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                JsonNode value = values.next();
                if (value.isTextual()) {
                    return value.asText();
                }
            }
        }
        return null;
    }

    private static Path deriveContract(
            Path canonicalSdz,
            Path modelConfigPath,
            Path generationConfigPath,
            Path tokenizerConfigPath,
            Path output) throws IOException {
        SameDiff graph;
        try {
            graph = SameDiff.load(canonicalSdz.toFile(), true);
        } catch (Exception failure) {
            throw new IOException(
                    "Kompile could not inspect the canonical SDZ to derive mobile text-generation "
                            + "bindings. Supply a valid SameDiff .sdz or an authored "
                            + SDX_TEXT_CONFIG + ".", failure);
        }

        JsonNode modelConfig = readObject(modelConfigPath, "Hugging Face config.json");
        JsonNode generationConfig = generationConfigPath == null
                ? JSON.createObjectNode()
                : readObject(generationConfigPath, "Hugging Face generation_config.json");
        JsonNode tokenizerConfig = readObject(
                tokenizerConfigPath, "normalized tokenizer_config.json");

        ModelIOConfig discovered = ModelIOConfig.discover(graph);
        String inputIds = requireVariable(graph, discovered.getInputIdsName(), "input IDs");
        String causalMask = requireVariable(
                graph, discovered.getCausalMaskName(), "causal mask");
        String positionOffset = requireVariable(
                graph, discovered.getPositionOffsetName(), "position offset");
        String cachePosition = requireVariable(
                graph, discovered.getCachePositionName(), "cache position");
        String logits = requireVariable(
                graph, discovered.getLogitsOutputName(), "logits output");
        String actualSequenceLength = findActualSequenceLength(graph);

        ModelIOConfig.KVCacheNames kvInputs = ModelIOConfig.findKVCacheInputNames(graph);
        ModelIOConfig.KVCacheNames kvOutputs = ModelIOConfig.findKVCacheOutputNames(graph);
        requireKvPairs(kvInputs, "KV cache inputs");
        requireKvPairs(kvOutputs, "prefill KV outputs");
        if (kvInputs.keyNames.size() != kvOutputs.keyNames.size()) {
            throw new IOException(
                    "SameDiff graph has " + kvInputs.keyNames.size() + " KV input layers but "
                            + kvOutputs.keyNames.size() + " prefill KV output layers; "
                            + "the mobile in-graph-KV profile requires a complete one-to-one contract.");
        }

        int contextLength = contextLength(modelConfig, tokenizerConfig);
        int maxPrefillLength = maxPrefillLength(graph, inputIds, contextLength);
        List<Integer> eosIds = tokenIds(
                firstPresent(generationConfig.get("eos_token_id"),
                        effectiveModelConfig(modelConfig).get("eos_token_id")));
        if (eosIds.isEmpty()) {
            throw new IOException(
                    "Neither generation_config.json nor config.json defines eos_token_id; "
                            + "a mobile decode session cannot determine when generation is complete.");
        }
        Integer padId = firstTokenId(
                generationConfig.get("pad_token_id"),
                effectiveModelConfig(modelConfig).get("pad_token_id"));
        if (padId == null) {
            padId = eosIds.get(0);
        }
        Integer bosId = firstTokenId(
                generationConfig.get("bos_token_id"),
                effectiveModelConfig(modelConfig).get("bos_token_id"));

        ObjectNode root = JSON.createObjectNode();
        root.put("formatVersion", 1);
        root.put("profile", "causal-lm-in-graph-kv-v1");

        ObjectNode io = root.putObject("io");
        io.put("inputIds", inputIds);
        io.put("causalMask", causalMask);
        io.put("positionOffset", positionOffset);
        io.put("cachePosition", cachePosition);
        io.put("actualSequenceLength", actualSequenceLength);
        io.put("logits", logits);
        putStrings(io, "kvKeyInputs", kvInputs.keyNames);
        putStrings(io, "kvValueInputs", kvInputs.valueNames);
        putStrings(io, "prefillKeyOutputs", kvOutputs.keyNames);
        putStrings(io, "prefillValueOutputs", kvOutputs.valueNames);

        ObjectNode execution = root.putObject("execution");
        execution.put("kvLayout", "BSHD");
        execution.put("kvDtype", sdxDtype(
                graph.getVariable(kvInputs.keyNames.get(0)), true, "KV cache"));
        execution.put("maskDtype", sdxDtype(
                graph.getVariable(causalMask), false, "causal mask"));
        execution.put("planOwnsKvScatter", true);

        ObjectNode tokens = root.putObject("tokens");
        if (bosId != null) {
            tokens.put("bosId", bosId);
        }
        tokens.put("padId", padId);
        ArrayNode eos = tokens.putArray("eosIds");
        new LinkedHashSet<>(eosIds).forEach(eos::add);

        ObjectNode limits = root.putObject("limits");
        limits.put("contextLength", contextLength);
        limits.put("maxPrefillLength", maxPrefillLength);
        limits.put("maxBatchSize", 1);

        ObjectNode sampling = root.putObject("samplingDefaults");
        sampling.put("maxNewTokens", boundedInt(
                generationConfig.get("max_new_tokens"), 128, 1, contextLength - 1));
        sampling.put("minNewTokens", boundedInt(
                generationConfig.get("min_new_tokens"), 0, 0, contextLength - 1));
        sampling.put("temperature", boundedDouble(
                generationConfig.get("temperature"), 0.0, 0.0, Double.MAX_VALUE));
        sampling.put("topK", boundedInt(
                generationConfig.get("top_k"), 0, 0, Integer.MAX_VALUE));
        sampling.put("topP", boundedDouble(
                generationConfig.get("top_p"), 1.0, 0.0, 1.0));
        sampling.put("repetitionPenalty", boundedDouble(
                generationConfig.get("repetition_penalty"), 1.0,
                Double.MIN_NORMAL, Double.MAX_VALUE));
        sampling.put("seed", 0);

        validateContract(root);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        return output;
    }

    private static JsonNode effectiveModelConfig(JsonNode root) {
        JsonNode text = root.get("text_config");
        return text != null && text.isObject() ? text : root;
    }

    private static int contextLength(JsonNode modelConfig, JsonNode tokenizerConfig)
            throws IOException {
        JsonNode effective = effectiveModelConfig(modelConfig);
        int value = positiveInt(effective.get("max_position_embeddings"));
        if (value <= 1) {
            value = positiveInt(effective.get("n_positions"));
        }
        if (value <= 1) {
            value = positiveInt(tokenizerConfig.get("model_max_length"));
        }
        if (value <= 1 || value > 1_000_000) {
            throw new IOException(
                    "Unable to determine a sane context length from config.json or "
                            + "tokenizer_config.json.");
        }
        return value;
    }

    private static int maxPrefillLength(
            SameDiff graph,
            String inputIds,
            int contextLength) {
        long[] shape = graph.getVariable(inputIds).getShape();
        if (shape != null && shape.length > 0) {
            long candidate = shape[shape.length - 1];
            if (candidate > 0 && candidate < contextLength) {
                return (int) candidate;
            }
        }
        return Math.min(contextLength - 1, 512);
    }

    private static String findActualSequenceLength(SameDiff graph) throws IOException {
        for (String input : graph.inputs()) {
            String normalized = input.toLowerCase(Locale.ROOT);
            if (normalized.contains("actual_sequence_length")
                    || normalized.contains("actual_seq_len")
                    || normalized.equals("sequence_length")
                    || normalized.equals("seq_len")) {
                return requireVariable(graph, input, "actual sequence length");
            }
        }
        throw new IOException(
                "SameDiff graph is missing the actual_sequence_length control input required "
                        + "by the SDX mobile text-generation profile.");
    }

    private static void requireKvPairs(
            ModelIOConfig.KVCacheNames names,
            String label) throws IOException {
        if (names == null
                || names.keyNames == null
                || names.valueNames == null
                || names.keyNames.isEmpty()
                || names.valueNames.isEmpty()
                || names.keyNames.size() != names.valueNames.size()) {
            throw new IOException(
                    "SameDiff graph does not expose complete " + label
                            + " for the SDX mobile text-generation profile.");
        }
    }

    private static String requireVariable(
            SameDiff graph,
            String name,
            String label) throws IOException {
        if (name == null || name.isBlank() || !graph.hasVariable(name)) {
            throw new IOException(
                    "SameDiff graph is missing the " + label
                            + " binding required by the SDX mobile text-generation profile.");
        }
        return name;
    }

    private static String sdxDtype(
            SDVariable variable,
            boolean allowInt8,
            String label) throws IOException {
        if (variable == null || variable.dataType() == null) {
            throw new IOException("Unable to determine " + label + " data type from SameDiff.");
        }
        String name = variable.dataType().name();
        if ("FLOAT".equals(name) || "FLOAT32".equals(name)) {
            return "FLOAT32";
        }
        if ("HALF".equals(name) || "FLOAT16".equals(name)) {
            return "FLOAT16";
        }
        if ("BFLOAT16".equals(name)) {
            return "BFLOAT16";
        }
        if (allowInt8 && ("BYTE".equals(name) || "INT8".equals(name))) {
            return "INT8";
        }
        throw new IOException(
                "Unsupported " + label + " data type for SDX mobile generation: " + name);
    }

    private static void validateContract(JsonNode root) throws IOException {
        requireExactInt(root, "formatVersion", 1);
        requireExactText(root, "profile", "causal-lm-in-graph-kv-v1");

        JsonNode io = requireObject(root, "io");
        for (String field : List.of(
                "inputIds", "causalMask", "positionOffset", "cachePosition",
                "actualSequenceLength", "logits")) {
            requireNonBlankText(io, field);
        }
        for (String field : List.of(
                "kvKeyInputs", "kvValueInputs",
                "prefillKeyOutputs", "prefillValueOutputs")) {
            requireNonEmptyStringArray(io, field);
        }
        requireEqualArraySizes(io, "kvKeyInputs", "kvValueInputs");
        requireEqualArraySizes(io, "prefillKeyOutputs", "prefillValueOutputs");
        if (io.get("kvKeyInputs").size() != io.get("prefillKeyOutputs").size()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: KV input and prefill output "
                            + "layer counts differ.");
        }

        JsonNode execution = requireObject(root, "execution");
        requireExactText(execution, "kvLayout", "BSHD");
        requireEnum(execution, "kvDtype",
                Set.of("FLOAT32", "FLOAT16", "BFLOAT16", "INT8"));
        requireEnum(execution, "maskDtype",
                Set.of("FLOAT32", "FLOAT16", "BFLOAT16"));
        if (!execution.path("planOwnsKvScatter").isBoolean()
                || !execution.path("planOwnsKvScatter").asBoolean()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: planOwnsKvScatter must be true.");
        }

        JsonNode tokens = requireObject(root, "tokens");
        int padId = nonNegativeInt(tokens, "padId");
        if (padId < 0) {
            throw new IOException("Invalid SDX text-generation contract padId.");
        }
        JsonNode eosIds = tokens.get("eosIds");
        if (eosIds == null || !eosIds.isArray() || eosIds.isEmpty()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: eosIds must be non-empty.");
        }
        for (JsonNode id : eosIds) {
            if (!id.canConvertToInt() || id.asInt() < 0) {
                throw new IOException(
                        "Invalid SDX text-generation contract: eosIds must be non-negative integers.");
            }
        }

        JsonNode limits = requireObject(root, "limits");
        int context = positiveInt(limits.get("contextLength"));
        int prefill = positiveInt(limits.get("maxPrefillLength"));
        if (context < 2 || prefill < 1 || prefill >= context) {
            throw new IOException(
                    "Invalid SDX text-generation contract: require 1 <= maxPrefillLength "
                            + "< contextLength.");
        }
    }

    private static JsonNode readObject(Path path, String label) throws IOException {
        JsonNode root = readJson(path, label);
        if (!root.isObject()) {
            throw new IOException(label + " must contain one JSON object: " + path);
        }
        return root;
    }

    private static JsonNode readJson(Path path, String label) throws IOException {
        requireSafeAsset(path, label);
        if (Files.size(path) > MAX_JSON_BYTES) {
            throw new IOException(label + " exceeds the 8 MiB JSON-asset limit: " + path);
        }
        JsonNode root;
        try {
            root = JSON.readTree(path.toFile());
        } catch (IOException failure) {
            throw new IOException(label + " is not valid JSON: " + path, failure);
        }
        if (root == null) {
            throw new IOException(label + " is empty JSON: " + path);
        }
        return root;
    }

    private static String readTextAsset(Path path, String label) throws IOException {
        requireSafeAsset(path, label);
        if (Files.size(path) > 4L * 1024L * 1024L) {
            throw new IOException(label + " exceeds the 4 MiB text-asset limit: " + path);
        }
        return Files.readString(path);
    }

    private static void requireSafeAsset(Path path, String label) throws IOException {
        if (path == null
                || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) <= 0L) {
            throw new IOException(label + " is missing, empty, or unsafe: " + path);
        }
    }

    private static Path requireUnique(
            Path root,
            String name,
            Path ignoredRoot,
            String missingMessage) throws IOException {
        Path match = findUnique(root, name, ignoredRoot);
        if (match == null) {
            throw new IOException(missingMessage);
        }
        return match;
    }

    private static Path findUnique(Path root, String name, Path ignoredRoot)
            throws IOException {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.toAbsolutePath().normalize().startsWith(ignoredRoot))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> name.equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(matches::add);
        }
        if (matches.size() > 1) {
            throw new IOException(
                    "Ambiguous staged " + name + " assets: " + matches);
        }
        return matches.isEmpty() ? null : matches.get(0).toAbsolutePath().normalize();
    }

    private static JsonNode requireObject(JsonNode root, String field) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + field + " must be an object.");
        }
        return value;
    }

    private static void requireExactInt(JsonNode root, String field, int expected)
            throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() != expected) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + field
                            + " must be " + expected + ".");
        }
    }

    private static void requireExactText(
            JsonNode root,
            String field,
            String expected) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.asText())) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + field
                            + " must be " + expected + ".");
        }
    }

    private static void requireNonBlankText(JsonNode root, String field)
            throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: missing " + field + ".");
        }
    }

    private static void requireNonEmptyStringArray(JsonNode root, String field)
            throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + field
                            + " must be a non-empty array.");
        }
        Set<String> unique = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || !unique.add(item.asText())) {
                throw new IOException(
                        "Invalid SDX text-generation contract: " + field
                                + " must contain unique non-blank tensor names.");
            }
        }
    }

    private static void requireEqualArraySizes(
            JsonNode root,
            String left,
            String right) throws IOException {
        if (root.get(left).size() != root.get(right).size()) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + left + " and " + right
                            + " must contain the same number of layers.");
        }
    }

    private static void requireEnum(
            JsonNode root,
            String field,
            Set<String> allowed) throws IOException {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !allowed.contains(value.asText())) {
            throw new IOException(
                    "Invalid SDX text-generation contract: " + field
                            + " must be one of " + allowed + ".");
        }
    }

    private static int nonNegativeInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.canConvertToInt() && value.asInt() >= 0
                ? value.asInt()
                : -1;
    }

    private static int positiveInt(JsonNode value) {
        return value != null && value.canConvertToInt() && value.asInt() > 0
                ? value.asInt()
                : -1;
    }

    private static JsonNode firstPresent(JsonNode first, JsonNode second) {
        return first != null && !first.isNull() ? first : second;
    }

    private static Integer firstTokenId(JsonNode first, JsonNode second) {
        List<Integer> ids = tokenIds(firstPresent(first, second));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static List<Integer> tokenIds(JsonNode node) {
        List<Integer> ids = new ArrayList<>();
        if (node == null || node.isNull()) {
            return ids;
        }
        if (node.canConvertToInt() && node.asInt() >= 0) {
            ids.add(node.asInt());
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (value.canConvertToInt() && value.asInt() >= 0) {
                    ids.add(value.asInt());
                }
            }
        }
        return ids;
    }

    private static int boundedInt(
            JsonNode value,
            int fallback,
            int minimum,
            int maximum) {
        int safeFallback = Math.max(minimum, Math.min(maximum, fallback));
        if (value == null || !value.canConvertToInt()) {
            return safeFallback;
        }
        int parsed = value.asInt();
        return parsed >= minimum && parsed <= maximum ? parsed : safeFallback;
    }

    private static double boundedDouble(
            JsonNode value,
            double fallback,
            double minimum,
            double maximum) {
        if (value == null || !value.isNumber()) {
            return fallback;
        }
        double parsed = value.asDouble();
        return Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum
                ? parsed
                : fallback;
    }

    private static void putStrings(ObjectNode parent, String field, List<String> values) {
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    static final class PreparedTextAssets {
        private final Path tokenizer;
        private final Path tokenizerConfig;
        private final Path textGenerationConfig;

        private PreparedTextAssets(
                Path tokenizer,
                Path tokenizerConfig,
                Path textGenerationConfig) {
            this.tokenizer = tokenizer;
            this.tokenizerConfig = tokenizerConfig;
            this.textGenerationConfig = textGenerationConfig;
        }

        Path tokenizer() {
            return tokenizer;
        }

        Path tokenizerConfig() {
            return tokenizerConfig;
        }

        Path textGenerationConfig() {
            return textGenerationConfig;
        }
    }
}
