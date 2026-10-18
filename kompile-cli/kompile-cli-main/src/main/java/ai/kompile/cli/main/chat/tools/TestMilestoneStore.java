package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.insights.TestMilestoneReader;
import ai.kompile.cli.main.chat.ChatRepl.TimelineLifecycle;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared atomic writer for manual and observed milestones. No server, judge, or git subprocess. */
final class TestMilestoneStore {
    private TestMilestoneStore() {}

    static Path save(Path root, Map<String, Object> milestone, TimelineLifecycle.ProducerContext producer) throws IOException {
        Path dir = root.resolve(TestMilestoneReader.STORE_DIR).resolve(TestMilestoneReader.MILESTONES_DIR);
        Files.createDirectories(dir);
        String id = String.valueOf(milestone.get("id"));
        Path target = TestMilestoneReader.fileFor(dir, id);
        if (producer != null) producer.annotateMilestone(milestone);
        Path temporary = Files.createTempFile(dir, ".milestone-", ".tmp");
        try {
            JsonUtils.standardMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), milestone);
            try {
                // Publish a fully written inode atomically, without replacing an existing run id.
                Files.createLink(target, temporary);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        if (producer != null) {
            Map<String, String> evidence = new LinkedHashMap<>();
            evidence.put("outcome", String.valueOf(milestone.getOrDefault("outcome",
                    Boolean.TRUE.equals(milestone.get("passing")) ? "passed" : "failed")));
            evidence.put("basis", String.valueOf(milestone.getOrDefault("basis", "milestone-saved")));
            evidence.put("attribution", milestone.containsKey("executionId") ? "test-process" : "recorder-not-test-process");
            for (String key : List.of("module", "totalTests", "passed", "failed", "errors", "skipped", "executionId", "framework")) {
                if (milestone.get(key) != null) evidence.put(key, milestone.get(key).toString());
            }
            producer.recordSaved("test.milestone.recorded", id, target, root, evidence);
        }
        return target;
    }

    /** Read-only launch revision; it is not proof that the working tree equals that commit. */
    static Map<String, Object> revision(Path directory) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(directory.toFile());
            if (builder.getGitDir() == null) return result;
            try (Repository repo = builder.build(); RevWalk walk = new RevWalk(repo)) {
                var head = repo.resolve("HEAD");
                if (head == null) return result;
                var commit = walk.parseCommit(head);
                result.put("commit", head.name());
                result.put("commitShort", TestMilestoneReader.shortCommit(head.name()));
                result.put("branch", repo.getBranch());
                result.put("commitMessage", commit.getShortMessage());
                result.put("commitAuthor", commit.getAuthorIdent().getName());
                result.put("revisionBasis", "launch-head-not-working-tree-proof");
            }
        } catch (IOException ignored) { /* Outside a repository or unreadable revision metadata. */ }
        return result;
    }
}
