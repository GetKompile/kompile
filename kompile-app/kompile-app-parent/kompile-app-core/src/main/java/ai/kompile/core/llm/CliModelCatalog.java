/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DYNAMIC model-metadata catalog read from the CLI agents' own on-disk model catalogs
 * (the <a href="https://models.dev">models.dev</a> format that {@code opencode} and other CLIs
 * maintain — e.g. {@code ~/.cache/opencode/models.json}). This is the source of truth for real
 * per-model limits: context window, max output tokens, vision support, and free/paid cost.
 *
 * <p><b>Why this exists.</b> Model context windows, output ceilings and capabilities must NOT be
 * hardcoded in a Java table — they change as providers ship models and as the CLI's catalog
 * refreshes from models.dev. The CLIs already publish full metadata on disk; Kompile reads it
 * directly and plugs it into model selection AND the extraction batch budgeter / scheduler (via
 * {@link ModelContextWindows} → {@link ModelCapability}). {@code ModelContextWindows}' static map is
 * only a last-resort fallback for environments where no catalog file is present.</p>
 *
 * <p><b>Not hardcoded / configurable on the fly.</b> Catalog file locations come from the
 * {@code kompile.cli.modelCatalogPaths} system property (OS path-separator or comma delimited);
 * when unset, the well-known opencode cache locations are used. The files are re-read on mtime
 * change (checked at most once per {@value #REFRESH_INTERVAL_MS} ms), so a {@code opencode models
 * --refresh} (or any catalog update) is picked up live with no redeploy.</p>
 */
public final class CliModelCatalog {

    /** One model's resolved metadata, sourced from the CLI catalog. */
    public record ModelSpec(String id,
                            String providerId,
                            int contextWindow,
                            int maxOutputTokens,
                            boolean supportsVision,
                            boolean supportsTools,
                            boolean free,
                            String status) {}

    private static final long REFRESH_INTERVAL_MS = 10_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Legacy bare-id/flat aliases, retained for unqualified callers.
    private static volatile Map<String, ModelSpec> byId = Map.of();
    // Keep provider provenance separate: a model id may itself contain slashes.
    private record ScopedModelId(String providerId, String modelId) {}
    private static volatile Map<ScopedModelId, ModelSpec> byScopedId = Map.of();
    // providerId -> ordered list of its model ids (for dynamic chain enumeration).
    private static volatile Map<String, List<String>> byProvider = Map.of();
    private static volatile long lastLoadedFingerprint = Long.MIN_VALUE;
    private static volatile long lastCheckMs = 0L;

    private CliModelCatalog() {}

    // ── Public lookup API ──────────────────────────────────────────────────────

    /**
     * Resolved spec for a bare id or {@code "provider/model"}. Bare ids retain the legacy
     * first-catalog match; qualified ids never fall back to another provider. For model ids
     * that themselves contain slashes, use {@link #lookup(String, String)} to supply the scope.
     */
    public static Optional<ModelSpec> lookup(String modelId) {
        if (modelId == null || modelId.isBlank()) return Optional.empty();
        String norm = modelId.toLowerCase(Locale.ROOT).trim();
        int slash = norm.indexOf('/');
        if (slash >= 0) {
            return lookup(norm.substring(0, slash), norm.substring(slash + 1));
        }
        ensureFresh();
        return Optional.ofNullable(byId.get(norm));
    }

    /**
     * Exact provider/model lookup. Both identifiers are case-insensitive; the model id is
     * otherwise literal, including slashes. Only entries parsed under this provider qualify,
     * never flat aliases or a different provider's bare model id.
     */
    public static Optional<ModelSpec> lookup(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        ensureFresh();
        return Optional.ofNullable(byScopedId.get(new ScopedModelId(
                providerId.toLowerCase(Locale.ROOT).trim(), modelId.toLowerCase(Locale.ROOT).trim())));
    }

    /** Context window (tokens) for a model, or empty if the model is not in any catalog. */
    public static Optional<Integer> contextWindow(String modelId) {
        return lookup(modelId).map(ModelSpec::contextWindow).filter(v -> v > 0);
    }

    /** Max output tokens for a model, or empty if not in any catalog. */
    public static Optional<Integer> maxOutputTokens(String modelId) {
        return lookup(modelId).map(ModelSpec::maxOutputTokens).filter(v -> v > 0);
    }

    /** Vision support for a model, or empty if not in any catalog. */
    public static Optional<Boolean> supportsVision(String modelId) {
        return lookup(modelId).map(ModelSpec::supportsVision);
    }

    /** True if the model is free (zero input+output cost) per the catalog; empty if unknown. */
    public static Optional<Boolean> isFree(String modelId) {
        return lookup(modelId).map(ModelSpec::free);
    }

    /** All model ids for a catalog provider (e.g. {@code "opencode"}), in catalog order. Never null. */
    public static List<String> modelsForProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) return List.of();
        ensureFresh();
        return byProvider.getOrDefault(providerId.toLowerCase(Locale.ROOT), List.of());
    }

    /** Number of models currently loaded across all catalogs (0 = no catalog found). */
    public static int size() {
        ensureFresh();
        return byId.size();
    }

    /** Test seam: force the next lookup to re-read catalog files, bypassing the refresh interval. */
    static void invalidateCacheForTest() {
        synchronized (CliModelCatalog.class) {
            lastLoadedFingerprint = Long.MIN_VALUE;
            lastCheckMs = 0L;
            byId = Map.of();
            byScopedId = Map.of();
            byProvider = Map.of();
        }
    }

    // ── Loading / hot-reload ────────────────────────────────────────────────────

    private static List<Path> catalogPaths() {
        String prop = System.getProperty("kompile.cli.modelCatalogPaths");
        List<Path> paths = new ArrayList<>();
        if (prop != null && !prop.isBlank()) {
            for (String part : prop.split("[," + File.pathSeparator + "]")) {
                String p = part.trim();
                if (!p.isEmpty()) paths.add(Path.of(expandHome(p)));
            }
            return paths;
        }
        // Well-known opencode model cache locations (models.dev format).
        String home = System.getProperty("user.home", "");
        paths.add(Path.of(home, ".cache", "opencode", "models.json"));
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            paths.add(Path.of(xdg, "opencode", "models.json"));
        }
        return paths;
    }

    private static String expandHome(String p) {
        if (p.startsWith("~")) {
            return System.getProperty("user.home", "") + p.substring(1);
        }
        return p;
    }

    private static void ensureFresh() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < REFRESH_INTERVAL_MS && lastLoadedFingerprint != Long.MIN_VALUE) {
            return;
        }
        synchronized (CliModelCatalog.class) {
            now = System.currentTimeMillis();
            if (now - lastCheckMs < REFRESH_INTERVAL_MS && lastLoadedFingerprint != Long.MIN_VALUE) {
                return;
            }
            lastCheckMs = now;
            long fp = fingerprint();
            if (fp == lastLoadedFingerprint) {
                return; // catalogs unchanged
            }
            reload();
            lastLoadedFingerprint = fp;
        }
    }

    /** Combined size+mtime fingerprint of all catalog files, so any change triggers a reload. */
    private static long fingerprint() {
        long fp = 1L;
        for (Path p : catalogPaths()) {
            try {
                if (Files.exists(p)) {
                    fp = 31 * fp + Files.getLastModifiedTime(p).toMillis();
                    fp = 31 * fp + Files.size(p);
                }
            } catch (Exception ignored) {
                // unreadable catalog file is treated as absent
            }
        }
        return fp;
    }

    private static void reload() {
        Map<String, ModelSpec> ids = new ConcurrentHashMap<>();
        Map<ScopedModelId, ModelSpec> scopedIds = new LinkedHashMap<>();
        Map<String, List<String>> providers = new LinkedHashMap<>();
        for (Path p : catalogPaths()) {
            try {
                if (!Files.exists(p)) continue;
                JsonNode root = MAPPER.readTree(p.toFile());
                parseModelsDev(root, ids, scopedIds, providers);
            } catch (Exception ignored) {
                // A malformed catalog must never break model resolution — fall through to the next
                // file (and ultimately ModelContextWindows' static fallback).
            }
        }
        byId = ids;
        byScopedId = scopedIds;
        byProvider = providers;
    }

    /**
     * Parse the models.dev catalog shape: {@code { <providerId>: { models: { <modelId>: {limit:{context,
     * output}, cost:{input,output}, attachment, tool_call, status, ...} } } } }.
     */
    private static void parseModelsDev(JsonNode root,
                                       Map<String, ModelSpec> ids,
                                       Map<ScopedModelId, ModelSpec> scopedIds,
                                       Map<String, List<String>> providers) {
        if (root == null || !root.isObject()) return;
        var fields = root.fields();
        while (fields.hasNext()) {
            var pe = fields.next();
            String providerId = pe.getKey().trim();
            JsonNode provNode = pe.getValue();
            JsonNode models = provNode.path("models");
            if (!models.isObject()) continue;
            List<String> provModels = providers.computeIfAbsent(
                    providerId.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            var me = models.fields();
            while (me.hasNext()) {
                var entry = me.next();
                String modelId = entry.getKey().trim();
                JsonNode m = entry.getValue();
                JsonNode limit = m.path("limit");
                int ctx = limit.path("context").asInt(0);
                int out = limit.path("output").asInt(0);
                if (ctx <= 0) continue; // no usable limit metadata — skip (fallback will handle)
                JsonNode cost = m.path("cost");
                boolean free = cost.path("input").asDouble(0) == 0.0
                        && cost.path("output").asDouble(0) == 0.0;
                boolean vision = m.path("attachment").asBoolean(false)
                        || hasImageModality(m.path("modalities"));
                boolean tools = m.path("tool_call").asBoolean(true);
                String status = m.path("status").asText("active");
                ModelSpec spec = new ModelSpec(modelId, providerId,
                        ctx, out > 0 ? out : Math.max(1, ctx / 8), vision, tools, free, status);
                String bare = modelId.toLowerCase(Locale.ROOT);
                ids.putIfAbsent(bare, spec);
                ids.putIfAbsent(providerId.toLowerCase(Locale.ROOT) + "/" + bare, spec);
                scopedIds.putIfAbsent(new ScopedModelId(providerId.toLowerCase(Locale.ROOT), bare), spec);
                provModels.add(modelId);
            }
            reconcileFreeAliases(providerId, ids, scopedIds, provModels);
        }
    }

    /**
     * Some catalogs publish a free alias with conservative limits while the canonical sibling carries
     * the true model geometry (for example opencode/deepseek-v4-flash-free vs deepseek-v4-flash).
     * Selection must still use the free alias, but batch planning needs the canonical context/output
     * ceiling. Reconcile only aliases that end in "-free" and only upward, preserving free cost/status.
     */
    private static void reconcileFreeAliases(String providerId,
                                             Map<String, ModelSpec> ids,
                                             Map<ScopedModelId, ModelSpec> scopedIds,
                                             List<String> providerModels) {
        String providerKey = providerId.toLowerCase(Locale.ROOT);
        for (String modelId : new ArrayList<>(providerModels)) {
            if (modelId == null) continue;
            String aliasKey = modelId.toLowerCase(Locale.ROOT);
            if (!aliasKey.endsWith("-free")) continue;
            String baseKey = aliasKey.substring(0, aliasKey.length() - "-free".length());
            ScopedModelId scopedAlias = new ScopedModelId(providerKey, aliasKey);
            ModelSpec alias = scopedIds.get(scopedAlias);
            ModelSpec base = scopedIds.get(new ScopedModelId(providerKey, baseKey));
            if (alias == null || base == null) continue;
            int context = Math.max(alias.contextWindow(), base.contextWindow());
            int output = Math.max(alias.maxOutputTokens(), base.maxOutputTokens());
            if (context == alias.contextWindow() && output == alias.maxOutputTokens()) continue;
            ModelSpec reconciled = new ModelSpec(
                    alias.id(), alias.providerId(), context, output,
                    alias.supportsVision() || base.supportsVision(),
                    alias.supportsTools() || base.supportsTools(),
                    alias.free(), alias.status());
            scopedIds.put(scopedAlias, reconciled);
            // Do not overwrite an earlier bare/flat alias owned by another provider.
            ids.replace(aliasKey, alias, reconciled);
            ids.replace(providerKey + "/" + aliasKey, alias, reconciled);
        }
    }

    private static boolean hasImageModality(JsonNode modalities) {
        if (modalities == null) return false;
        JsonNode input = modalities.isArray() ? modalities : modalities.path("input");
        if (input.isArray()) {
            for (JsonNode n : input) {
                if ("image".equalsIgnoreCase(n.asText(""))) return true;
            }
        }
        return false;
    }
}
