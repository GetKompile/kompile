/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.codeindexer.ingest;

import ai.kompile.app.core.chunking.TextChunker;
import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.service.CodeEntityExtractor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.core.source.SourceAttributionHelper;
import ai.kompile.core.source.SourceMetadataConstants;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits source text on the declaration ranges produced by the managed code indexer.
 * The current parsers are heuristic for most languages, so chunks explicitly record whether a
 * declaration range was available instead of claiming full AST ownership.
 */
@Component
public class DeclarationAwareCodeChunker implements TextChunker {

    public static final String NAME = "code-aware";
    private static final Set<CodeEntityType> DECLARATIONS = EnumSet.of(
            CodeEntityType.MODULE, CodeEntityType.CLASS, CodeEntityType.INTERFACE,
            CodeEntityType.ENUM, CodeEntityType.RECORD, CodeEntityType.ANNOTATION,
            CodeEntityType.METHOD, CodeEntityType.CONSTRUCTOR, CodeEntityType.FUNCTION,
            CodeEntityType.TYPE_ALIAS);

    private final CodeEntityExtractor extractor;

    public DeclarationAwareCodeChunker(CodeEntityExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public List<RetrievedDoc> chunk(RetrievedDoc document, Map<String, Object> options) {
        validateDocument(document);
        Map<String, Object> effective = prepareOptions(options);
        int chunkSize = positiveInt(effective.get("chunkSize"), 1800);
        int overlap = Math.min(nonNegativeInt(effective.get("overlap"), 0), chunkSize - 1);
        String text = document.getText();
        Path sourcePath = sourcePath(document.getMetadata());
        String projectId = stringValue(document.getMetadata().get("codeProjectId"), "crawl");

        List<Segment> segments = declarationSegments(text, sourcePath, projectId, chunkSize);
        if (segments.isEmpty()) {
            segments = List.of(new Segment(0, text.length(), null));
        }

        List<Draft> drafts = new ArrayList<>();
        for (Segment segment : segments) {
            splitSegment(text, segment, chunkSize, overlap, drafts);
        }
        List<RetrievedDoc> result = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            SourceAttributionHelper.addChunkMetadata(metadata, i, drafts.size(), draft.start(), draft.end());
            metadata.put("chunk.strategy", NAME);
            metadata.put("chunk.index", i);
            metadata.put("chunk.total", drafts.size());
            metadata.put("chunk.originalId", document.getId());
            metadata.put("code.declarationAware", draft.declaration() != null);
            if (draft.declaration() != null) {
                CodeEntity declaration = draft.declaration();
                metadata.put("code.declaration.kind", declaration.getEntityType().name());
                metadata.put("code.declaration.name", declaration.getName());
                if (declaration.getFullyQualifiedName() != null) {
                    metadata.put("code.declaration.fqn", declaration.getFullyQualifiedName());
                }
                metadata.put("code.declaration.startLine", declaration.getStartLine());
                metadata.put("code.declaration.endLine", declaration.getEndLine());
            }
            result.add(RetrievedDoc.builder()
                    .id(document.getId() + "-code-chunk-" + i)
                    .text(text.substring(draft.start(), draft.end()))
                    .metadata(metadata)
                    .score(document.getScore())
                    .build());
        }
        return result;
    }

    private List<Segment> declarationSegments(String text, Path path, String projectId, int chunkSize) {
        try {
            List<CodeEntity> declarations = extractor.extractContent(path, projectId, text).entities().stream()
                    .filter(entity -> DECLARATIONS.contains(entity.getEntityType()))
                    .filter(entity -> entity.getStartLine() != null && entity.getEndLine() != null)
                    .sorted(Comparator.comparingInt(CodeEntity::getStartLine)
                            .thenComparing(Comparator.comparingInt(CodeEntity::getEndLine).reversed()))
                    .toList();
            if (declarations.isEmpty()) return List.of();

            int[] starts = lineStarts(text);
            String filePath = path.toString();
            List<CodeEntity> roots = declarations.stream()
                    .filter(entity -> entity.getParentFqn() == null
                            || entity.getParentFqn().equals(filePath))
                    .toList();
            if (roots.isEmpty()) return List.of();
            List<Segment> segments = new ArrayList<>();
            int cursor = 0;
            for (CodeEntity declaration : roots) {
                int start = lineOffset(starts, declaration.getStartLine(), text.length());
                int end = lineOffset(starts, declaration.getEndLine() + 1, text.length());
                if (end <= cursor || start < cursor) continue; // nested/overlapping declaration
                appendExpandedSegments(segments, cursor, end, declaration, declarations,
                        starts, text.length(), chunkSize);
                cursor = end;
            }
            if (cursor < text.length()) segments.add(new Segment(cursor, text.length(), null));
            return segments;
        } catch (RuntimeException parserFailure) {
            return List.of();
        }
    }

    private static void appendExpandedSegments(List<Segment> segments, int start, int end,
                                               CodeEntity declaration,
                                               List<CodeEntity> declarations,
                                               int[] lineStarts, int textLength, int chunkSize) {
        if (end - start <= chunkSize || declaration.getFullyQualifiedName() == null) {
            segments.add(new Segment(start, end, declaration));
            return;
        }
        List<CodeEntity> children = declarations.stream()
                .filter(candidate -> declaration.getFullyQualifiedName().equals(candidate.getParentFqn()))
                .sorted(Comparator.comparingInt(CodeEntity::getStartLine)
                        .thenComparing(Comparator.comparingInt(CodeEntity::getEndLine).reversed()))
                .toList();
        if (children.isEmpty()) {
            segments.add(new Segment(start, end, declaration));
            return;
        }

        int cursor = start;
        for (CodeEntity child : children) {
            int childStart = lineOffset(lineStarts, child.getStartLine(), textLength);
            int childEnd = lineOffset(lineStarts, child.getEndLine() + 1, textLength);
            if (childStart < cursor || childEnd <= cursor || childEnd > end) continue;
            if (childStart > cursor) {
                segments.add(new Segment(cursor, childStart, declaration));
            }
            appendExpandedSegments(segments, childStart, childEnd, child, declarations,
                    lineStarts, textLength, chunkSize);
            cursor = childEnd;
        }
        if (cursor < end) segments.add(new Segment(cursor, end, declaration));
    }

    private static void splitSegment(String text, Segment segment, int chunkSize, int overlap,
                                     List<Draft> drafts) {
        int start = segment.start();
        while (start < segment.end()) {
            int limit = Math.min(segment.end(), start + chunkSize);
            int end = limit;
            if (limit < segment.end()) {
                int newline = text.lastIndexOf('\n', limit - 1);
                if (newline >= start + Math.max(1, chunkSize / 2)) end = newline + 1;
            }
            if (end <= start) end = Math.min(segment.end(), start + chunkSize);
            drafts.add(new Draft(start, end, segment.declaration()));
            if (end >= segment.end()) break;
            start = Math.max(start + 1, end - overlap);
        }
    }

    private static int[] lineStarts(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        int[] starts = new int[lines];
        int line = 1;
        for (int i = 0; i < text.length() && line < starts.length; i++) {
            if (text.charAt(i) == '\n') starts[line++] = i + 1;
        }
        return starts;
    }

    private static int lineOffset(int[] starts, int oneBasedLine, int textLength) {
        if (oneBasedLine <= 1) return 0;
        int index = oneBasedLine - 1;
        return index >= starts.length ? textLength : starts[index];
    }

    private static Path sourcePath(Map<String, Object> metadata) {
        String value = stringValue(metadata.get(SourceMetadataConstants.SOURCE_PATH), null);
        if (value == null) value = stringValue(metadata.get("source"), null);
        if (value == null) value = stringValue(metadata.get(SourceMetadataConstants.SOURCE_FILENAME), "source.txt");
        try {
            return Path.of(value);
        } catch (RuntimeException invalid) {
            return Path.of("source.txt");
        }
    }

    private static int positiveInt(Object value, int fallback) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }

    private static int nonNegativeInt(Object value, int fallback) {
        return value instanceof Number number && number.intValue() >= 0 ? number.intValue() : fallback;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public List<String> getSupportedLanguages() {
        return List.of("*");
    }

    @Override
    public Map<String, Object> getDefaultOptions() {
        return Map.of("chunkSize", 1800, "overlap", 0,
                OPTION_COLLECT_GARBAGE, false, OPTION_INCLUDE_GARBAGE_CHUNK, true);
    }

    private record Segment(int start, int end, CodeEntity declaration) { }
    private record Draft(int start, int end, CodeEntity declaration) { }
}
