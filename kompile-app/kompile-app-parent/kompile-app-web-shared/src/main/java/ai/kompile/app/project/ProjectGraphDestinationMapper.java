/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.project;

import ai.kompile.app.facts.domain.FactSheet;
import ai.kompile.app.facts.service.FactSheetService;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a portable graph scope to a runtime fact sheet without trusting source database IDs.
 * Portable identity is authoritative; numeric archive IDs are accepted only as catalog lookup keys.
 */
@Service
public class ProjectGraphDestinationMapper {

    public static final String SOURCE_SCOPE_META = "kompile.sourceScope";

    private final FactSheetService factSheets;
    private final KompileProjectStore store;

    @Autowired
    public ProjectGraphDestinationMapper(FactSheetService factSheets) {
        this(factSheets, new KompileProjectStore());
    }

    ProjectGraphDestinationMapper(FactSheetService factSheets, KompileProjectStore store) {
        this.factSheets = Objects.requireNonNull(factSheets, "factSheets");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Resolve the destination, failing before graph mutation on any identity disagreement. */
    public long resolve(Path projectRoot, Path archive, UnifiedGraph graph) {
        if (projectRoot == null || archive == null || graph == null) {
            throw new IllegalArgumentException("project root, archive, and graph are required");
        }
        String fileScope = scopeKey(archive.getFileName().toString());
        if (fileScope == null) {
            throw new IllegalArgumentException("Invalid scoped graph archive name: " + archive.getFileName());
        }

        Map<?, ?> source = sourceScope(graph);
        String metadataPortableId = optionalPortableId(source.get("portableId"));
        Long metadataLegacyId = optionalLong(source.get("legacyFactSheetId"));
        String metadataName = optionalString(source.get("name"));
        Long filenameLegacyId = optionalLong(fileScope);
        String filenamePortableId = filenameLegacyId == null ? optionalPortableId(fileScope) : null;

        if (metadataPortableId != null && filenamePortableId != null
                && !metadataPortableId.equals(filenamePortableId)) {
            throw new IllegalArgumentException("Graph archive portable identity disagrees with its filename");
        }
        if (metadataLegacyId != null && filenameLegacyId != null
                && !metadataLegacyId.equals(filenameLegacyId)) {
            throw new IllegalArgumentException("Graph archive legacy identity disagrees with its filename");
        }

        Long legacyId = firstNonNull(metadataLegacyId, filenameLegacyId, graph.factSheetId());
        String archivePortableId = firstNonNull(metadataPortableId, filenamePortableId);
        List<KompileProjectFactSheet> catalog = store.listFactSheets(projectRoot);
        KompileProjectFactSheet catalogEntry = findCatalogEntry(catalog, archivePortableId, legacyId, metadataName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No portable fact-sheet catalog entry matches graph " + archive.getFileName()));

        requireLegacyHintMatches("metadata", metadataLegacyId, catalogEntry.getId());
        requireLegacyHintMatches("filename", filenameLegacyId, catalogEntry.getId());
        requireLegacyHintMatches("graph", graph.factSheetId(), catalogEntry.getId());
        if (metadataName != null && !metadataName.equals(catalogEntry.getName())) {
            throw new IllegalArgumentException("Graph archive fact-sheet name disagrees with its catalog entry");
        }

        String catalogPortableId = optionalPortableId(catalogEntry.getPortableId());
        if (archivePortableId != null && catalogPortableId != null
                && !archivePortableId.equals(catalogPortableId)) {
            throw new IllegalArgumentException("Graph archive portable identity disagrees with its catalog entry");
        }
        String portableId = firstNonNull(archivePortableId, catalogPortableId);

        FactSheet destination;
        if (portableId != null) {
            destination = factSheets.getSheetByPortableId(portableId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Portable fact sheet is not restored: " + portableId));
            String catalogName = optionalString(catalogEntry.getName());
            if (catalogName != null) {
                factSheets.getSheetByName(catalogName).ifPresent(byName -> {
                    if (!Objects.equals(byName.getId(), destination.getId())) {
                        throw new IllegalArgumentException(
                                "Portable fact-sheet identity and name resolve to different runtime sheets");
                    }
                });
            }
        } else {
            String name = optionalString(catalogEntry.getName());
            if (name == null) {
                throw new IllegalArgumentException("Legacy graph catalog entry has no fact-sheet name");
            }
            destination = factSheets.getSheetByName(name)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Legacy graph destination fact sheet is not restored: " + name));
        }
        if (destination.getId() == null) {
            throw new IllegalArgumentException("Resolved graph destination has no runtime ID");
        }
        return destination.getId();
    }

    private static Optional<KompileProjectFactSheet> findCatalogEntry(
            List<KompileProjectFactSheet> catalog,
            String portableId,
            Long legacyId,
            String name) {
        if (catalog == null) return Optional.empty();
        if (portableId != null) {
            Optional<KompileProjectFactSheet> match = unique(catalog.stream()
                    .filter(entry -> portableId.equals(optionalPortableId(entry.getPortableId())))
                    .toList(), "portable identity");
            if (match.isPresent()) return match;
        }
        if (legacyId != null) {
            Optional<KompileProjectFactSheet> match = unique(catalog.stream()
                    .filter(entry -> legacyId.equals(entry.getId())).toList(), "legacy identity");
            if (match.isPresent()) return match;
        }
        if (name != null) {
            return unique(catalog.stream().filter(entry -> name.equals(entry.getName())).toList(), "name");
        }
        return Optional.empty();
    }

    private static Optional<KompileProjectFactSheet> unique(
            List<KompileProjectFactSheet> matches, String kind) {
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Portable fact-sheet catalog has duplicate " + kind);
        }
        return matches.stream().findFirst();
    }

    private static Map<?, ?> sourceScope(UnifiedGraph graph) {
        Object value = graph.meta().get(SOURCE_SCOPE_META);
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("Invalid graph source-scope metadata");
    }

    private static void requireLegacyHintMatches(String kind, Long hint, Long catalogId) {
        if (hint != null && !Objects.equals(hint, catalogId)) {
            throw new IllegalArgumentException(
                    "Graph archive " + kind + " legacy identity disagrees with its catalog entry");
        }
    }

    static String scopeKey(String filename) {
        if (filename == null || !filename.startsWith("factsheet-") || !filename.endsWith(".kgraph")) {
            return null;
        }
        String key = filename.substring("factsheet-".length(), filename.length() - ".kgraph".length());
        return key.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}") && !key.contains("..") ? key : null;
    }

    private static String optionalPortableId(Object value) {
        String text = optionalString(value);
        if (text == null) return null;
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid portable fact-sheet identity: " + text, invalid);
        }
    }

    private static Long optionalLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        String text = optionalString(value);
        if (text == null) return null;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String optionalString(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) if (value != null) return value;
        return null;
    }
}
