package ai.kompile.project.knowledge;

import java.util.List;

/** Full content-addressed portable knowledge inventory used as a delta cursor. */
public record KnowledgeInventory(
        int formatVersion,
        String projectId,
        String projectName,
        String defaultGraph,
        String revision,
        List<PortableKnowledge.Entry> entries) {

    public KnowledgeInventory {
        if (formatVersion != PortableKnowledge.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported knowledge inventory version: " + formatVersion);
        }
        projectId = PortableKnowledge.requireIdentity(projectId, "projectId");
        projectName = PortableKnowledge.requireIdentity(projectName, "projectName");
        defaultGraph = PortableKnowledge.requirePortablePath(defaultGraph);
        revision = PortableKnowledge.requireRevision(revision, "revision");
        entries = PortableKnowledge.normalizeInventory(entries);
        String computed = PortableKnowledge.revision(projectId, projectName, defaultGraph, entries);
        if (!computed.equals(revision)) {
            throw new IllegalArgumentException("Knowledge inventory revision does not match its entries");
        }
    }

    public static KnowledgeInventory create(
            String projectId,
            String projectName,
            String defaultGraph,
            List<PortableKnowledge.Entry> entries) {
        List<PortableKnowledge.Entry> normalized = PortableKnowledge.normalizeInventory(entries);
        return new KnowledgeInventory(
                PortableKnowledge.FORMAT_VERSION,
                projectId,
                projectName,
                defaultGraph,
                PortableKnowledge.revision(projectId, projectName, defaultGraph, normalized),
                normalized);
    }
}
