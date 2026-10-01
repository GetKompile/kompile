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

import java.nio.file.Path;
import java.util.Objects;

/** One syncable file within a component family and scope. */
public final class SyncEntry {

    public enum Kind { FILE, DELETE }

    /** Component family, e.g. {@code skills}. */
    private final String component;
    /** Component-root-relative path (forward slashes, no leading slash, no dotdot). */
    private final String relativePath;
    /** SHA-256 of the current content (hex), null for deletions. */
    private final String sha256;
    /** Content length in bytes. */
    private final long size;
    /** FILE or DELETE marker. */
    private final Kind kind;
    /** Logical identity for items that span several files (e.g. a skill package). */
    private final String unitId;

    public SyncEntry(String component, String relativePath, String sha256, long size, Kind kind, String unitId) {
        this.component = Objects.requireNonNull(component, "component");
        Objects.requireNonNull(relativePath, "relativePath");
        // Validate the RAW input BEFORE normalization: silently rewriting a path
        // (stripping a leading slash, converting backslashes) would hide exactly
        // the traversal attempts this check exists to reject.
        unsafePathCheck(relativePath);
        this.relativePath = normalize(relativePath);
        this.sha256 = sha256;
        this.size = size;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.unitId = unitId;
    }

    public static SyncEntry file(String component, String relativePath, String sha256, long size, String unitId) {
        return new SyncEntry(component, relativePath, sha256, size, Kind.FILE, unitId);
    }

    public static SyncEntry deletion(String component, String relativePath, String unitId) {
        return new SyncEntry(component, relativePath, null, 0, Kind.DELETE, unitId);
    }

    public String component() {
        return component;
    }

    public String relativePath() {
        return relativePath;
    }

    /** Component-root-relative package path, e.g. {@code skills/my-skill/SKILL.md}. */
    public String packagePath() {
        return component + "/" + relativePath;
    }

    public String sha256() {
        return sha256;
    }

    public long size() {
        return size;
    }

    public Kind kind() {
        return kind;
    }

    public String unitId() {
        return unitId;
    }

    public boolean isDeletion() {
        return kind == Kind.DELETE;
    }

    /** Logical identity: unit id when present, otherwise the file path. */
    public String identity() {
        return unitId != null ? unitId : packagePath();
    }

    public Path toPath(Path componentRoot) {
        return componentRoot.resolve(relativePath);
    }

    private static void unsafePathCheck(String raw) {
        if (raw.isEmpty()
                || raw.startsWith("/")
                || raw.indexOf('\\') >= 0
                || raw.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Unsafe sync path: " + raw);
        }
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Unsafe sync path segment in: " + raw);
            }
        }
    }

    private static String normalize(String p) {
        String s = p.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SyncEntry)) return false;
        SyncEntry e = (SyncEntry) o;
        return size == e.size
                && component.equals(e.component)
                && relativePath.equals(e.relativePath)
                && Objects.equals(sha256, e.sha256)
                && kind == e.kind
                && Objects.equals(unitId, e.unitId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(component, relativePath, sha256, size, kind, unitId);
    }

    @Override
    public String toString() {
        return "SyncEntry{" + packagePath() + ", " + kind
                + (sha256 != null ? ", " + sha256.substring(0, Math.min(8, sha256.length())) : "")
                + (unitId != null ? ", unit=" + unitId : "") + '}';
    }
}
