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
import java.util.Objects;

/**
 * Global or per-project sync scope. Global scope maps onto {@code ~/.kompile};
 * project scope maps onto a concrete project's {@code .kompile} directory.
 */
public final class SyncScope {

    public static final String GLOBAL = "global";
    public static final String PROJECT = "project";

    private final String kind;
    private final String projectId;
    private final String label;

    private SyncScope(String kind, String projectId, String label) {
        this.kind = kind;
        this.projectId = projectId;
        this.label = label;
    }

    public static SyncScope global() {
        return new SyncScope(GLOBAL, null, "global");
    }

    /** Project scope must carry an explicit project identity (path or manifest id). */
    public static SyncScope project(String projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return new SyncScope(PROJECT, projectId, "project:" + projectId);
    }

    /** Parses {@code global} or {@code project:<id>} scope tokens. */
    public static SyncScope parse(String token) {
        if (token == null || token.isBlank()) return global();
        String t = token.trim();
        if (t.equalsIgnoreCase(GLOBAL)) return global();
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("project:")) {
            String id = t.substring("project:".length()).trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("project scope requires an id: project:<id>");
            }
            return project(id);
        }
        throw new IllegalArgumentException("Unknown scope token: " + token + " (use global | project:<id>)");
    }

    public String kind() {
        return kind;
    }

    public String projectId() {
        return projectId;
    }

    public boolean isProject() {
        return PROJECT.equals(kind);
    }

    public String label() {
        return label;
    }

    /** Stable identity used in peer records and baselines. */
    public String storageKey() {
        return kind;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SyncScope)) return false;
        SyncScope that = (SyncScope) o;
        return kind.equals(that.kind) && Objects.equals(projectId, that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, projectId);
    }

    @Override
    public String toString() {
        return label();
    }

    static List<String> knownKinds() {
        return List.of(GLOBAL, PROJECT);
    }
}
