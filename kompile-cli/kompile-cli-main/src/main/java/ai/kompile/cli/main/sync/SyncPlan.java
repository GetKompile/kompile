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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of comparing two inventories against the baseline from the last
 * successful sync with this peer. Per entry: unchanged, local-only change,
 * remote-only change, both-changed (conflict), or delete/edit conflict.
 */
public final class SyncPlan {

    public enum Action { COPY_TO_REMOTE, COPY_TO_LOCAL, DELETE_REMOTE, DELETE_LOCAL, NOOP, CONFLICT }

    /** One entry-level decision. */
    public static final class Item {
        private final SyncEntry entry;
        private final Action action;
        private final String localHash;
        private final String remoteHash;
        private final String baselineHash;

        public Item(SyncEntry entry, Action action, String localHash, String remoteHash, String baselineHash) {
            this.entry = entry;
            this.action = action;
            this.localHash = localHash;
            this.remoteHash = remoteHash;
            this.baselineHash = baselineHash;
        }

        public SyncEntry entry() { return entry; }
        public Action action() { return action; }
        public String localHash() { return localHash; }
        public String remoteHash() { return remoteHash; }
        public String baselineHash() { return baselineHash; }

        @Override
        public String toString() {
            String path = entry == null ? "?" : entry.packagePath();
            return action + " " + path
                    + (baselineHash != null ? " (baseline " + shortHash(baselineHash) + ")" : "");
        }

        private static String shortHash(String h) {
            return h == null ? "-" : h.substring(0, Math.min(8, h.length()));
        }
    }

    /** A per-unit conflict recorded for user resolution. */
    public static final class Conflict {
        private final String identity;
        private final String component;
        private final String reason;

        public Conflict(String identity, String component, String reason) {
            this.identity = identity;
            this.component = component;
            this.reason = reason;
        }

        public String identity() { return identity; }
        public String component() { return component; }
        public String reason() { return reason; }

        @Override
        public String toString() {
            return identity + ": " + reason;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<Conflict> conflicts = new ArrayList<>();
    private final Map<String, List<String>> notes = new LinkedHashMap<>();

    public List<Item> items() {
        return items;
    }

    public List<Conflict> conflicts() {
        return conflicts;
    }

    public Map<String, List<String>> notes() {
        return notes;
    }

    public void add(Item item) {
        if (item.action() == Action.CONFLICT) {
            conflicts.add(new Conflict(
                    item.entry() == null ? "?" : item.entry().identity(),
                    item.entry() == null ? "?" : item.entry().component(),
                    explain(item)));
        }
        items.add(item);
    }

    public void note(String component, String note) {
        notes.computeIfAbsent(component, k -> new ArrayList<>()).add(note);
        }

    public int count(Action action) {
        int n = 0;
        for (Item i : items) {
            if (i.action() == action) n++;
        }
        return n;
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    public boolean isNoop() {
        return conflicts.isEmpty()
                && count(Action.COPY_TO_REMOTE) == 0
                && count(Action.COPY_TO_LOCAL) == 0
                && count(Action.DELETE_REMOTE) == 0
                && count(Action.DELETE_LOCAL) == 0;
    }

    private static String explain(Item item) {
        switch (item.action()) {
            case CONFLICT:
                if (item.entry() == null) return "diverged";
                if (item.entry().isDeletion()) {
                    return "deleted on one side and edited on the other";
                }
                return "edited independently on both sides since the last sync";
            default:
                return "";
        }
    }

    @Override
    public String toString() {
        return "SyncPlan{" + count(Action.COPY_TO_REMOTE) + "→, "
                + count(Action.COPY_TO_LOCAL) + "←, "
                + count(Action.DELETE_REMOTE) + " del→, "
                + count(Action.DELETE_LOCAL) + " del←, "
                + conflicts.size() + " conflicts}";
    }
}
