package ai.kompile.project.archive;

import ai.kompile.project.knowledge.KnowledgeInventory;
import ai.kompile.project.knowledge.KnowledgeUpdateManifest;

import java.nio.file.Path;

/** Result of publishing a verified revision-aware portable knowledge update archive. */
public record KnowledgeUpdateArchiveResult(
        Path archive,
        KnowledgeInventory inventory,
        KnowledgeUpdateManifest manifest,
        long changedBytes) {
}
