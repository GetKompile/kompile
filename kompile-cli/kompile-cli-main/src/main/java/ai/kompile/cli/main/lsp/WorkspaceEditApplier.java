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

package ai.kompile.cli.main.lsp;

import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolExecutionException;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditLockResult;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies an LSP {@link WorkspaceEdit} to the working tree, all-or-nothing.
 *
 * <p>Phase 1 simulates every text edit and resource operation entirely in memory; if
 * anything fails (an out-of-range edit, an unreadable file, a denied permission) the
 * disk is never touched. In dry-run mode (the default) it stops there and renders a
 * preview. Otherwise it snapshots originals, optionally takes per-file coordination
 * locks, writes/creates/renames/deletes, and rolls every change back on the first IO
 * failure.</p>
 */
public class WorkspaceEditApplier {

    private final ToolContext context;
    private final CoordinationStateManager coordinator;

    public WorkspaceEditApplier(ToolContext context, CoordinationStateManager coordinator) {
        this.context = context;
        this.coordinator = coordinator;
    }

    /** Called with the list of touched paths after a successful (non-dry-run) apply. */
    @FunctionalInterface
    public interface PostApplyHook {
        void afterApply(List<Path> touched);
    }

    public Result apply(WorkspaceEdit edit, boolean dryRun) throws ToolExecutionException {
        return apply(edit, dryRun, null);
    }

    public Result apply(WorkspaceEdit edit, boolean dryRun, PostApplyHook hook) throws ToolExecutionException {
        Plan plan = simulate(edit);
        if (plan.isEmpty()) {
            return new Result(dryRun, false, "No changes in workspace edit.", 0, 0, 0, 0, 0, List.of());
        }
        if (dryRun) {
            return new Result(dryRun, false, renderPreview(plan), plan.editCount, plan.dirty.size(),
                    plan.created, plan.renamed, plan.deleted, List.copyOf(plan.touched()));
        }
        List<Path> touched = commit(plan);
        if (hook != null) {
            try {
                hook.afterApply(touched);
            } catch (RuntimeException e) {
                // index refresh is a best-effort side effect; the edit already succeeded
                System.err.println("[LSP] post-apply hook failed: " + e.getMessage());
            }
        }
        return new Result(dryRun, true, renderSummary(plan), plan.editCount, plan.dirty.size(),
                plan.created, plan.renamed, plan.deleted, touched);
    }

    // ── Phase 1: in-memory simulation ────────────────────────────────────────

    private Plan simulate(WorkspaceEdit edit) throws ToolExecutionException {
        Plan plan = new Plan();
        List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = edit.getDocumentChanges();
        if (documentChanges != null && !documentChanges.isEmpty()) {
            for (Either<TextDocumentEdit, ResourceOperation> change : documentChanges) {
                if (change.isLeft()) {
                    applyTextEdits(plan, change.getLeft().getTextDocument().getUri(), change.getLeft().getEdits());
                } else {
                    applyResourceOp(plan, change.getRight());
                }
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                applyTextEdits(plan, entry.getKey(), entry.getValue());
            }
        }
        return plan;
    }

    private void applyTextEdits(Plan plan, String uri, List<TextEdit> edits) throws ToolExecutionException {
        if (edits == null || edits.isEmpty()) {
            return;
        }
        Path file = resolve(uri);
        String content = plan.contentOf(file);
        // Apply descending by start position so earlier edits do not shift later offsets.
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((TextEdit e) -> LspPositions.offsetOf(plan.contentOf(file), e.getRange().getStart())).reversed());
        for (TextEdit textEdit : ordered) {
            int start = LspPositions.offsetOf(content, textEdit.getRange().getStart());
            int end = LspPositions.offsetOf(content, textEdit.getRange().getEnd());
            if (start < 0 || end > content.length() || start > end) {
                throw new ToolExecutionException("edit out of range for " + file + " ("
                        + start + ".." + end + " of " + content.length() + ")");
            }
            content = content.substring(0, start) + textEdit.getNewText() + content.substring(end);
        }
        plan.putContent(file, content);
        plan.editCount += edits.size();
    }

    private void applyResourceOp(Plan plan, ResourceOperation op) throws ToolExecutionException {
        if (op instanceof CreateFile create) {
            Path file = resolve(create.getUri());
            plan.createFile(file);
            plan.created++;
        } else if (op instanceof RenameFile rename) {
            Path from = resolve(rename.getOldUri());
            Path to = resolve(rename.getNewUri());
            plan.renameFile(from, to);
            plan.renamed++;
        } else if (op instanceof DeleteFile delete) {
            Path file = resolve(delete.getUri());
            plan.deleteFile(file);
            plan.deleted++;
        }
    }

    // ── Phase 2: commit with rollback ────────────────────────────────────────

    private List<Path> commit(Plan plan) throws ToolExecutionException {
        List<Path> affected = new ArrayList<>(plan.dirty.keySet());
        for (Path p : plan.deletions) {
            if (!affected.contains(p)) {
                affected.add(p);
            }
        }

        Map<Path, byte[]> backups = new LinkedHashMap<>();
        Set<Path> created = new LinkedHashSet<>();
        List<String> lockIds = new ArrayList<>();
        try {
            // Snapshot originals + take coordination locks before mutating anything.
            for (Path p : affected) {
                if (Files.exists(p)) {
                    backups.put(p, Files.readAllBytes(p));
                } else {
                    created.add(p);
                }
                acquireLock(p, lockIds);
            }
            // Writes (creates + edits + rename targets).
            for (Map.Entry<Path, String> entry : plan.dirty.entrySet()) {
                Path file = entry.getKey();
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
            // Deletions (explicit deletes + rename sources).
            for (Path file : plan.deletions) {
                Files.deleteIfExists(file);
            }
            return affected;
        } catch (IOException e) {
            rollback(backups, created);
            throw new ToolExecutionException("failed to apply workspace edit (rolled back): " + e.getMessage(), e);
        } finally {
            releaseLocks(lockIds);
        }
    }

    private void rollback(Map<Path, byte[]> backups, Set<Path> created) {
        for (Path file : created) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignore) {
                // best effort
            }
        }
        for (Map.Entry<Path, byte[]> entry : backups.entrySet()) {
            try {
                if (entry.getKey().getParent() != null) {
                    Files.createDirectories(entry.getKey().getParent());
                }
                Files.write(entry.getKey(), entry.getValue());
            } catch (IOException ignore) {
                // best effort
            }
        }
    }

    private void acquireLock(Path file, List<String> lockIds) {
        if (coordinator == null) {
            return;
        }
        EditLockResult result = coordinator.tryAcquireEditLock(file.toString(), "edit", "lsp");
        if (result.isAcquired() && result.getLockId() != null) {
            lockIds.add(result.getLockId());
        }
    }

    private void releaseLocks(List<String> lockIds) {
        if (coordinator == null) {
            return;
        }
        for (String lockId : lockIds) {
            coordinator.releaseEditLock(lockId);
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private String renderPreview(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[DRY RUN]** ").append(summaryLine(plan)).append("\n");
        for (Map.Entry<Path, String> entry : plan.dirty.entrySet()) {
            Path file = entry.getKey();
            String before = plan.originalContent(file);
            sb.append('\n').append(renderFileDiff(rel(file), before, entry.getValue()));
        }
        for (Map.Entry<Path, Path> rename : plan.renames.entrySet()) {
            sb.append("\nrename ").append(rel(rename.getKey())).append(" -> ").append(rel(rename.getValue())).append('\n');
        }
        for (Path deleted : plan.explicitDeletes) {
            sb.append("\ndelete ").append(rel(deleted)).append('\n');
        }
        return sb.toString();
    }

    private String renderSummary(Plan plan) {
        return summaryLine(plan);
    }

    private String summaryLine(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.editCount).append(" edits in ").append(plan.dirty.size()).append(" files");
        List<String> extra = new ArrayList<>();
        if (plan.created > 0) {
            extra.add(plan.created + " created");
        }
        if (plan.renamed > 0) {
            extra.add(plan.renamed + " renamed");
        }
        if (plan.deleted > 0) {
            extra.add(plan.deleted + " deleted");
        }
        if (!extra.isEmpty()) {
            sb.append(" (").append(String.join(", ", extra)).append(')');
        }
        return sb.toString();
    }

    /** Minimal focused line diff: trims common prefix/suffix lines and shows the changed block. */
    static String renderFileDiff(String relPath, String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < (a.length - prefix) && suffix < (b.length - prefix)
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
            suffix++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(relPath).append("\n+++ b/").append(relPath).append('\n');
        sb.append("@@ -").append(prefix + 1).append(" +").append(prefix + 1).append(" @@\n");
        for (int i = prefix; i < a.length - suffix; i++) {
            sb.append('-').append(a[i]).append('\n');
        }
        for (int i = prefix; i < b.length - suffix; i++) {
            sb.append('+').append(b[i]).append('\n');
        }
        return sb.toString();
    }

    // ── URI/path helpers ─────────────────────────────────────────────────────

    private Path resolve(String uri) throws ToolExecutionException {
        Path path = toPath(uri);
        // Route through ToolContext so paths outside the working dir trip the external_directory permission.
        return context.resolvePath(path.toString());
    }

    private static Path toPath(String uri) throws ToolExecutionException {
        try {
            if (uri.startsWith("file:")) {
                return Path.of(URI.create(uri)).toAbsolutePath().normalize();
            }
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new ToolExecutionException("invalid document uri: " + uri);
        }
    }

    private String rel(Path file) {
        try {
            return context.getWorkingDirectory().toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    // ── Result ───────────────────────────────────────────────────────────────

    public record Result(boolean dryRun, boolean applied, String output, int editCount, int fileCount,
                         int created, int renamed, int deleted, List<Path> touched) {
    }

    // ── Internal plan ────────────────────────────────────────────────────────

    /** Accumulates the simulated end-state: dirty file contents, renames, deletions. */
    private final class Plan {
        private final Map<Path, String> dirty = new LinkedHashMap<>();
        private final Map<Path, String> originals = new LinkedHashMap<>();
        private final Map<Path, Path> renames = new LinkedHashMap<>();
        private final Set<Path> deletions = new LinkedHashSet<>();
        private final Set<Path> explicitDeletes = new LinkedHashSet<>();
        private int editCount;
        private int created;
        private int renamed;
        private int deleted;

        String contentOf(Path file) {
            if (dirty.containsKey(file)) {
                return dirty.get(file);
            }
            return originalContent(file);
        }

        String originalContent(Path file) {
            return originals.computeIfAbsent(file, f -> {
                try {
                    return Files.exists(f) ? Files.readString(f) : "";
                } catch (IOException e) {
                    return "";
                }
            });
        }

        void putContent(Path file, String content) {
            dirty.put(file, content);
        }

        void createFile(Path file) {
            originals.putIfAbsent(file, "");
            dirty.putIfAbsent(file, "");
        }

        void renameFile(Path from, Path to) {
            String content = contentOf(from);
            originalContent(to);
            dirty.put(to, content);
            dirty.remove(from);
            deletions.add(from);
            renames.put(from, to);
        }

        void deleteFile(Path file) {
            dirty.remove(file);
            deletions.add(file);
            explicitDeletes.add(file);
        }

        Set<Path> touched() {
            Set<Path> all = new LinkedHashSet<>(dirty.keySet());
            all.addAll(deletions);
            return all;
        }

        boolean isEmpty() {
            return dirty.isEmpty() && deletions.isEmpty();
        }
    }
}
