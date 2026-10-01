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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Three-way sync decision engine. Compares local and remote inventories
 * against the baseline recorded at the last successful sync with this peer.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>same hash on both sides (or both absent) → NOOP</li>
 *   <li>changed on one side only → copy in that direction (or delete)</li>
 *   <li>changed on both sides differently → CONFLICT (never auto-merge)</li>
 *   <li>identical independent edits on both sides → NOOP (content equal)</li>
 * </ul>
 *
 * <p>Deletion propagation is opt-in per sync run; without it, deletions are
 * surfaced as conflicts when the other side edited the file, and as NOOP
 * (remote resurrection withheld) when the other side kept the baseline content.</p>
 */
public final class SyncEngine {

    /** Applies or stages a file payload. Implementations must be restart-safe. */
    public interface PayloadSink {
        void write(SyncEntry entry, byte[] bytes) throws IOException;

        void delete(SyncEntry entry) throws IOException;
    }

    private final Consumer<String> log;

    public SyncEngine(Consumer<String> log) {
        this.log = log == null ? s -> { } : log;
    }

    /**
     * Computes the transfer plan. {@code local} and {@code remote} map component
     * → entries; {@code baseline} maps packagePath → hash at last sync.
     *
     * <p>{@code deletionsOptIn} controls how "present in baseline, absent on
     * one side" is read: absence is ambiguous (deleted vs never materialized),
     * so without opt-in it is left untouched with a note; with opt-in it is
     * planned as a deletion. Absence on BOTH sides is always a plain NOOP.</p>
     */
    public SyncPlan plan(Map<String, List<SyncEntry>> local,
                         Map<String, List<SyncEntry>> remote,
                         Map<String, String> baseline,
                         boolean deletionsOptIn) {
        SyncPlan plan = new SyncPlan();
        Set<String> components = new java.util.LinkedHashSet<>();
        components.addAll(local.keySet());
        components.addAll(remote.keySet());

        for (String component : components) {
            Map<String, SyncEntry> localByPath = byPath(local.get(component));
            Map<String, SyncEntry> remoteByPath = byPath(remote.get(component));            Set<String> paths = new java.util.TreeSet<>();
            paths.addAll(localByPath.keySet());
            paths.addAll(remoteByPath.keySet());

            for (String path : paths) {
                SyncEntry l = localByPath.get(path);
                SyncEntry r = remoteByPath.get(path);
                String base = baseline.get(component + "/" + path);
                String lh = l == null ? null : l.sha256();
                String rh = r == null ? null : r.sha256();

                if (Objects.equals(lh, rh)) {
                    // Identical content on both sides (including both missing).
                    plan.add(new SyncPlan.Item(
                            l != null ? l : deletionMarker(component, path), SyncPlan.Action.NOOP, lh, rh, base));
                    continue;
                }
                if (Objects.equals(lh, base)) {
                    // Local matches baseline: remote is the changed side.
                    if (r == null) {
                        planDeletion(plan, component, path, SyncPlan.Action.DELETE_LOCAL,
                                deletionsOptIn, lh, rh, base);
                    } else {
                        plan.add(new SyncPlan.Item(r, SyncPlan.Action.COPY_TO_LOCAL, lh, rh, base));
                    }
                    continue;
                }
                if (Objects.equals(rh, base)) {
                    // Remote matches baseline: local is the changed side.
                    if (l == null) {
                        planDeletion(plan, component, path, SyncPlan.Action.DELETE_REMOTE,
                                deletionsOptIn, lh, rh, base);
                    } else {
                        plan.add(new SyncPlan.Item(l, SyncPlan.Action.COPY_TO_REMOTE, lh, rh, base));
                    }
                    continue;
                }
                // Both sides differ from baseline and from each other: conflict.
                SyncEntry representative = l != null ? l : deletionMarker(component, path);
                plan.add(new SyncPlan.Item(representative, SyncPlan.Action.CONFLICT, lh, rh, base));
            }
        }
        return plan;
    }

    private static SyncEntry deletionMarker(String component, String path) {
        return SyncEntry.deletion(component, path, unitIdOrNull(component, path));
    }

    /**
     * Absence against a baselined file is ambiguous (deleted vs missing).
     * Only an explicit opt-in turns it into a deletion; otherwise it is
     * recorded as a NOOP with a note so users can see the withheld action.
     */
    private static void planDeletion(SyncPlan plan, String component, String path,
                                     SyncPlan.Action action, boolean deletionsOptIn,
                                     String lh, String rh, String base) {
        if (deletionsOptIn) {
            plan.add(new SyncPlan.Item(deletionMarker(component, path), action, lh, rh, base));
        } else {
            SyncEntry marker = deletionMarker(component, path);
            plan.add(new SyncPlan.Item(marker, SyncPlan.Action.NOOP, lh, rh, base));
            plan.note(component, "withheld " + action + " for " + component + "/" + path
                    + " (absent on one side; use --allow-delete to propagate deletions)");
        }
    }

    private static Map<String, SyncEntry> byPath(List<SyncEntry> entries) {
        Map<String, SyncEntry> map = new HashMap<>();
        if (entries != null) {
            for (SyncEntry e : entries) {
                map.put(e.relativePath(), e);
            }
        }
        return map;
    }

    static String unitIdOrNull(String component, String path) {
        int slash = path.indexOf('/');
        if (slash > 0 && SyncCatalog.SKILLS.equals(component)) {
            return component + ":" + path.substring(0, slash);
        }
        return null;
    }

    /**
     * Executes a plan against {@code sink} (the side being updated), fetching
     * payloads via {@code fetch} for copies. Deletions are only executed when
     * {@code allowDelete} is set; otherwise they are downgraded to NOOP with a
     * note.
     */
    public void execute(SyncPlan plan,
                        PayloadSink sink,
                        Fetcher fetch,
                        boolean allowDelete,
                        SyncPlan.Action direction) throws IOException {
        for (SyncPlan.Item item : plan.items()) {
            SyncEntry entry = item.entry();
            switch (item.action()) {
                case COPY_TO_REMOTE:
                case COPY_TO_LOCAL: {
                    if (direction != null && item.action() != direction) {
                        continue;
                    }
                    byte[] bytes = fetch.fetch(item.entry());
                    sink.write(item.entry(), bytes);
                    break;
                }
                case DELETE_REMOTE:
                case DELETE_LOCAL: {
                    if (direction != null && item.action() != direction) {
                        continue;
                    }
                    if (!allowDelete) {
                        plan.note(entry.component(),
                                "deletion withheld (enable deletions to propagate): " + entry.packagePath());
                        continue;
                    }
                    sink.delete(entry);
                    break;
                }
                case NOOP:
                case CONFLICT:
                default:
                    continue;
            }
        }
    }

    /** Supplies file bytes for an entry from the source side. */
    public interface Fetcher {
        byte[] fetch(SyncEntry entry) throws IOException;
    }

    /** Stages payloads into a directory tree instead of applying them directly. */
    public static PayloadSink stagingSink(Path stageRoot) {
        return new PayloadSink() {
            @Override
            public void write(SyncEntry entry, byte[] bytes) throws IOException {
                Path target = entry.toPath(stageRoot);
                if (!target.normalize().startsWith(stageRoot.normalize())) {
                    throw new IOException("stage path escapes root: " + entry.relativePath());
                }
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void delete(SyncEntry entry) throws IOException {
                Files.deleteIfExists(entry.toPath(stageRoot));
            }
        };
    }

    /** Baseline helper: union of keys across two hash maps, for diffing. */
    public static Set<String> unionKeys(Map<String, String> a, Map<String, String> b) {
        Set<String> all = new HashSet<>(a.keySet());
        all.addAll(b.keySet());
        return all;
    }

    /** Reads a small text file quietly, for wizard previews. */
    public static String readSmallFile(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length > 65536) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
