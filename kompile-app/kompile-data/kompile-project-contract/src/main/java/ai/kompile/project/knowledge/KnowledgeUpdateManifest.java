package ai.kompile.project.knowledge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Manifest stored as {@code knowledge-manifest.json} in a {@code .kupdate} archive. */
public record KnowledgeUpdateManifest(
        String format,
        int formatVersion,
        String projectId,
        String projectName,
        String baseRevision,
        String revision,
        String defaultGraph,
        String createdAt,
        String generator,
        List<PortableKnowledge.Entry> inventory,
        List<PortableKnowledge.Entry> changed,
        List<String> deleted) {

    public static final String FORMAT = "kompile-knowledge-update";

    public KnowledgeUpdateManifest {
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("Unsupported knowledge update format: " + format);
        }
        if (formatVersion != PortableKnowledge.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported knowledge update version: " + formatVersion);
        }
        projectId = PortableKnowledge.requireIdentity(projectId, "projectId");
        projectName = PortableKnowledge.requireIdentity(projectName, "projectName");
        baseRevision = PortableKnowledge.requireRevision(baseRevision, "baseRevision");
        revision = PortableKnowledge.requireRevision(revision, "revision");
        defaultGraph = PortableKnowledge.requirePortablePath(defaultGraph);
        createdAt = PortableKnowledge.requireIdentity(createdAt, "createdAt");
        generator = PortableKnowledge.requireIdentity(generator, "generator");
        inventory = PortableKnowledge.normalizeInventory(inventory);
        changed = PortableKnowledge.normalizeInventory(changed);
        deleted = deleted == null ? List.of() : deleted.stream()
                .map(PortableKnowledge::requirePortablePath)
                .sorted()
                .toList();

        Set<String> inventoryPaths = new HashSet<>();
        for (PortableKnowledge.Entry entry : inventory) {
            inventoryPaths.add(PortableKnowledge.pathKey(entry.path()));
        }
        for (PortableKnowledge.Entry entry : changed) {
            if (!inventoryPaths.contains(PortableKnowledge.pathKey(entry.path()))) {
                throw new IllegalArgumentException("Changed entry is absent from final inventory: " + entry.path());
            }
        }
        Set<String> deletedPaths = new HashSet<>();
        for (String path : deleted) {
            String key = PortableKnowledge.pathKey(path);
            if (!deletedPaths.add(key)) {
                throw new IllegalArgumentException("Duplicate deleted path: " + path);
            }
            if (inventoryPaths.contains(key)) {
                throw new IllegalArgumentException("Deleted path is still present in final inventory: " + path);
            }
        }
        String computed = PortableKnowledge.revision(projectId, projectName, defaultGraph, inventory);
        if (!computed.equals(revision)) {
            throw new IllegalArgumentException("Knowledge revision does not match the final inventory");
        }
    }
}
