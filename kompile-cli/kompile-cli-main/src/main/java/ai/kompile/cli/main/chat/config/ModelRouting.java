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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package ai.kompile.cli.main.chat.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Model-id → Kompile chat provider routing for delegation requests that specify
 * only a model.
 *
 * <p>The {@code task}/{@code multi_task}/{@code quorum_task} tools run
 * {@code agent=kompile} children on the project's configured provider, but a
 * request-scoped {@code model} names a vendor's model family, not a route.
 * ModelRouting infers the route: {@code claude-sonnet-4-5} goes to
 * {@code anthropic}, {@code gpt-5.2} stays on {@code openai} or
 * {@code openai-codex}, {@code glm-*} goes to {@code zai}, and so on.</p>
 *
 * <p>Decision rules, in order:</p>
 * <ol>
 *   <li>The configured route already hosts the model's family — keep it
 *       ({@code gpt-*} on the Codex route, {@code claude-*} on github-copilot).</li>
 *   <li>The family's first-party owner is a configured provider — switch to it
 *       deterministically ({@code claude-*} on the Codex route → anthropic).</li>
 *   <li>The owner is not configured but exactly one hosting provider is — switch
 *       to that one.</li>
 *   <li>Several hosting providers are configured and none is the owner — refuse
 *       with {@link AmbiguousModelRouteException} naming the candidates; the
 *       caller picks a route explicitly via {@code provider}. Dispatch never
 *       guesses between equivalent credentials.</li>
 *   <li>No configured provider hosts the family — {@link UnroutableModelException}.</li>
 *   <li>An unrecognized family stays on the configured route rather than
 *       inventing one.</li>
 * </ol>
 *
 * <p>Host knowledge mirrors the wire shapes {@code DirectLlmClient} actually
 * speaks (github-copilot serves {@code claude-*} over Messages and
 * {@code gpt-5*}/{@code grok-*} over Responses; groq serves llama/mistral), and
 * every answer is filtered against the providers actually registered in
 * {@link ChatProviderRegistry}, so routing never names a provider this
 * installation cannot authenticate to.</p>
 */
public final class ModelRouting {

    /** A model family prefix, its first-party provider, and who else hosts it. */
    private record Family(String prefix, String owner, List<String> otherHosts) {}

    /**
     * Vendor-owned model family prefixes with their hosting providers. Matching
     * is longest-prefix-first. Hosts are providers this project demonstrably
     * speaks (DirectLlmClient wire shapes, ProviderModelCatalogs endpoints);
     * the first-party owner is always hosts[0] of its family.
     */
    private static final List<Family> FAMILIES = List.of(
            new Family("claude-", "anthropic", List.of("github-copilot", "openrouter")),
            new Family("gpt-", "openai", List.of("openai-codex", "github-copilot", "openrouter")),
            new Family("o1-", "openai", List.of("github-copilot", "openrouter")),
            new Family("o3-", "openai", List.of("github-copilot", "openrouter")),
            new Family("o4-", "openai", List.of("github-copilot", "openrouter")),
            new Family("chatgpt-", "openai", List.of("openai-codex")),
            new Family("codex-", "openai-codex", List.of()),
            new Family("gemini-", "gemini", List.of("openrouter", "github-copilot")),
            new Family("gemma-", "gemini", List.of("openrouter")),
            new Family("grok-", "xai", List.of("github-copilot", "openrouter")),
            new Family("glm-", "zai", List.of("openrouter")),
            new Family("zhipu-", "zai", List.of("openrouter")),
            new Family("qwen", "zai", List.of("openrouter", "github-copilot")),
            new Family("kimi-", "zai", List.of("openrouter")),
            new Family("deepseek-", "deepseek", List.of("openrouter", "github-copilot")),
            new Family("llama-", "groq", List.of("openrouter", "github-copilot")),
            new Family("mistral-", "groq", List.of("openrouter")),
            new Family("moonshot-", "zai", List.of("openrouter")));

    private ModelRouting() {
    }

    /** Where a model id should run: the resolved route and how it was decided. */
    public record Route(String provider, String model, String reason) {}

    /** A model family that several configured providers could host, none first-party. */
    public static final class AmbiguousModelRouteException extends RuntimeException {
        private final List<String> candidates;

        public AmbiguousModelRouteException(String model, List<String> candidates) {
            super("Model '" + model + "' is hosted by several configured providers ("
                    + String.join(", ", candidates) + ") and none is its first-party owner. "
                    + "Pass provider=<id> with model to pick one, or run resolve_model to list routes.");
            this.candidates = List.copyOf(candidates);
        }

        public List<String> candidates() {
            return candidates;
        }
    }

    /** A model family no configured provider can host. */
    public static final class UnroutableModelException extends RuntimeException {
        public UnroutableModelException(String model, List<String> hosts) {
            super("No configured Kompile chat provider hosts model '" + model
                    + (hosts.isEmpty()
                            ? "'. Run resolve_model to see which providers exist."
                            : "' (known hosts: " + String.join(", ", hosts)
                            + " — none is configured here). Configure one with kompile chat --setup "
                            + "or run resolve_model to list options."));
        }
    }

    /** Registry-backed resolution for the live installation. */
    public static Route resolve(String model, String configuredProvider) {
        Set<String> registered = new LinkedHashSet<>();
        for (ChatProvider provider : ChatProviderRegistry.all()) {
            registered.add(provider.id());
        }
        return resolve(model, configuredProvider, registered);
    }

    /**
     * Resolution against an explicit registered-provider set (unit-testable form
     * of {@link #resolve(String, String)}).
     */
    public static Route resolve(String model, String configuredProvider, Set<String> registeredProviders) {
        String owner = familyOwner(model);
        String configured = configuredProvider == null ? "" : configuredProvider.trim();
        if (owner == null) {
            return new Route(configured, model, "unrecognized model family; kept configured route");
        }
        List<String> hosts = hostsFor(owner);
        if (!configured.isEmpty() && hosts.contains(configured.toLowerCase(Locale.ROOT))) {
            return new Route(configured, model, "configured route hosts this family");
        }
        List<String> configuredHosts = new ArrayList<>();
        for (String host : hosts) {
            if (registeredProviders.contains(host)) {
                configuredHosts.add(host);
            }
        }
        if (configuredHosts.contains(owner)) {
            return new Route(owner, model, "model family '" + familyPrefix(owner, model)
                    + "' routes to its owner " + owner);
        }
        if (configuredHosts.size() == 1) {
            return new Route(configuredHosts.get(0), model,
                    "model family '" + familyPrefix(owner, model) + "' routes to "
                            + configuredHosts.get(0));
        }
        if (configuredHosts.size() > 1) {
            throw new AmbiguousModelRouteException(model, configuredHosts);
        }
        throw new UnroutableModelException(model, hosts);
    }

    /** The first-party provider id owning {@code model}'s family, or null. */
    public static String familyOwner(String model) {
        if (model == null || model.isBlank()) return null;
        String lower = model.trim().toLowerCase(Locale.ROOT);
        Family best = null;
        for (Family family : FAMILIES) {
            if (lower.startsWith(family.prefix())
                    && (best == null || family.prefix().length() > best.prefix().length())) {
                best = family;
            }
        }
        return best == null ? null : best.owner();
    }

    /** Every provider id that hosts the given family owner's models (owner first). */
    public static List<String> hostsFor(String owner) {
        List<String> hosts = new ArrayList<>();
        for (Family family : FAMILIES) {
            if (family.owner().equals(owner)) {
                hosts.add(family.owner());
                hosts.addAll(family.otherHosts());
                break;
            }
        }
        return hosts;
    }

    private static String familyPrefix(String owner, String model) {
        for (Family family : FAMILIES) {
            if (family.owner().equals(owner) && model != null
                    && model.toLowerCase(Locale.ROOT).startsWith(family.prefix())) {
                return family.prefix() + "*";
            }
        }
        return owner + " models";
    }

    /**
     * Catalog for the resolve_model search tool: every family owner, its family
     * prefixes, and the other hosts that can serve it. Provider labels come from
     * the live registry when available.
     */
    public static List<Map<String, Object>> familyCatalog() {
        Set<String> registered = new LinkedHashSet<>();
        for (ChatProvider provider : ChatProviderRegistry.all()) {
            registered.add(provider.id());
        }
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Family family : FAMILIES) {
            Map<String, Object> row = rows.computeIfAbsent(family.owner(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("provider", id);
                m.put("providerLabel", ChatProviderRegistry.label(id));
                m.put("configured", registered.contains(id));
                m.put("families", new ArrayList<String>());
                m.put("alsoHostedOn", new ArrayList<String>());
                return m;
            });
            @SuppressWarnings("unchecked")
            List<String> families = (List<String>) row.get("families");
            families.add(family.prefix() + "*");
        }
        for (Map<String, Object> row : rows.values()) {
            @SuppressWarnings("unchecked")
            List<String> also = (List<String>) row.get("alsoHostedOn");
            for (String host : hostsFor((String) row.get("provider"))) {
                if (!host.equals(row.get("provider")) && !also.contains(host)) {
                    also.add(host);
                }
            }
        }
        return List.copyOf(rows.values());
    }
}
