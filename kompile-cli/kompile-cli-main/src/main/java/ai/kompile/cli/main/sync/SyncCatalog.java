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
 * limitations under the License.
 */
package ai.kompile.cli.main.sync;

import java.util.List;
import java.util.Map;

/**
 * Static description of everything {@code kompile sync} may synchronize.
 *
 * <p>Sync transfers portable, user-managed content only. Platform binaries,
 * model weights, credentials, caches, and logs are deliberately outside this
 * catalog; distribution updates remain the job of {@code kompile update}.</p>
 */
public final class SyncCatalog {

    private SyncCatalog() {
    }

    /** Protocol marker for this sync implementation. */
    public static final String PROTOCOL = "kompile-sync";
    /** Current protocol version; peers negotiate on the minimum of both. */
    public static final int PROTOCOL_VERSION = 1;

    /** Portable component families that sync can transfer. */
    public static final String SKILLS = "skills";
    public static final String MEMORIES = "memories";
    public static final String ROLES = "roles";
    public static final String PROMPTS = "prompts";
    public static final String PROVIDER_SKILLS = "provider-skills";

    public static final List<String> COMPONENTS =
            List.of(SKILLS, MEMORIES, ROLES, PROMPTS, PROVIDER_SKILLS);

    /** Default selection: the three first-class families. */
    public static final List<String> DEFAULT_COMPONENTS = List.of(SKILLS, MEMORIES, ROLES);

    /**
     * Machine-scoped names that must never be classified into any synced
     * component family. Providers, runtimes, caches, logs, sessions,
     * instances, archives, and credentials all live here.
     */
    public static final List<String> NEVER_SYNCED_NAMES = List.of(
            "bin", "lib", "mvn", "graalvm", "python", "cmake", "backends",
            "components", "models", "llm-cache", "archives", "instances",
            "sessions", "run", "logs", "cache", "config", "conversations",
            "credentials", "auth", "auth.json", "credentials.json",
            "subprocesses", "lsp", "tool-results", "test-milestones",
            "index", "indices", "memory-index", "semantic", "daemon");

    /** Top-level home entries that sync never reads, even when listed explicitly. */
    public static final List<String> NEVER_SYNCED_TOP_LEVEL = List.of(
            "memory/graph.jsonl.tmp", "archived");

    public static boolean isComponent(String name) {
        return COMPONENTS.contains(name);
    }

    /** Validates and normalizes a user-supplied component list. */
    public static List<String> validate(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_COMPONENTS;
        }
        java.util.LinkedHashSet<String> picked = new java.util.LinkedHashSet<>();
        for (String raw : requested) {
            for (String part : raw.split("[,\\s]+")) {
                String token = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (token.isEmpty()) continue;
                if (token.equals("all")) {
                    picked.addAll(COMPONENTS);
                } else if (isComponent(token)) {
                    picked.add(token);
                } else {
                    throw new IllegalArgumentException("Unknown sync component: " + raw
                            + " (available: " + String.join(", ", COMPONENTS) + ")");
                }
                if (token.equals("all")) break;
            }
        }
        return List.copyOf(picked);
    }

    /**
     * Resolves the home-relative directory for a component family within a
     * scope root. Global scope roots at the kompile home; project scope roots
     * at the project's {@code .kompile} directory.
     */
    public static String componentDir(String scope, String component) {
        String base = SyncScope.PROJECT.equals(scope) ? ".kompile" : "";
        String leaf;
        switch (component) {
            case SKILLS: leaf = "skills"; break;
            case MEMORIES: leaf = "memory"; break;
            case ROLES: leaf = "roles"; break;
            case PROMPTS: leaf = "system-prompts"; break;
            case PROVIDER_SKILLS: leaf = "provider-skills"; break;
            default: throw new IllegalArgumentException("Unknown component: " + component);
        }
        return base.isEmpty() ? leaf : base + "/" + leaf;
    }

    /** Which home-relative prefix each component mounts for a scope. */
    public static Map<String, String> componentDirs(String scope) {
        java.util.Map<String, String> dirs = new java.util.LinkedHashMap<>();
        for (String c : COMPONENTS) {
            dirs.put(c, componentDir(scope, c));
        }
        return dirs;
    }

    @Override
    public String toString() {
        return "SyncCatalog[" + String.join(", ", COMPONENTS) + "]";
    }
}
