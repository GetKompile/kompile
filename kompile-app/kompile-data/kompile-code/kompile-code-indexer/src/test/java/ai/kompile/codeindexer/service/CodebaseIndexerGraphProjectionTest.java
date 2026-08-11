/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.CodeEntityRepository;
import ai.kompile.codeindexer.domain.CodeProjectRepository;
import ai.kompile.codeindexer.domain.CodeRelation;
import ai.kompile.codeindexer.domain.CodeRelationRepository;
import ai.kompile.codeindexer.domain.CodeRelationType;
import ai.kompile.codeindexer.domain.FileFingerprintRepository;
import ai.kompile.codeindexer.domain.IndexedDirectoryRepository;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodebaseIndexerGraphProjectionTest {

    @Test
    void reconnectsUnchangedSourceEdgesAfterTargetReplacement() {
        CodeRelationRepository relations = mock(CodeRelationRepository.class);
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        CodeRelation incoming = CodeRelation.builder()
                .projectId("app")
                .sourceFqn("sample.Caller.call")
                .targetFqn("sample.Target.run")
                .targetName("run")
                .relationType(CodeRelationType.CALLS)
                .filePath("Caller.java")
                .build();
        when(relations.findByProjectIdAndTargetFqnIn(
                "app", Set.of("sample.Target.run"))).thenReturn(List.of(incoming));
        CodebaseIndexer indexer = indexer(relations, graph);

        int created = indexer.reconnectIncomingRelations(
                "app",
                Set.of("sample.Target.run"),
                Set.of("Target.java"),
                Map.of(
                        "sample.Caller.call", "source-node",
                        "sample.Target.run", "target-node"));

        assertEquals(1, created);
        verify(graph).createEdge(
                "source-node", "target-node", EdgeType.SHARED_ENTITY, 1.0, "calls");
    }

    @Test
    void doesNotReplayEdgesAlreadyOwnedByAReprocessedSourceFile() {
        CodeRelationRepository relations = mock(CodeRelationRepository.class);
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        CodeRelation currentFileRelation = CodeRelation.builder()
                .projectId("app")
                .sourceFqn("sample.Target.start")
                .targetFqn("sample.Target.run")
                .targetName("run")
                .relationType(CodeRelationType.CALLS)
                .filePath("Target.java")
                .build();
        when(relations.findByProjectIdAndTargetFqnIn(
                "app", Set.of("sample.Target.run"))).thenReturn(List.of(currentFileRelation));
        CodebaseIndexer indexer = indexer(relations, graph);

        int created = indexer.reconnectIncomingRelations(
                "app",
                Set.of("sample.Target.run"),
                Set.of("Target.java"),
                Map.of(
                        "sample.Target.start", "source-node",
                        "sample.Target.run", "target-node"));

        assertEquals(0, created);
        verifyNoInteractions(graph);
    }

    private CodebaseIndexer indexer(CodeRelationRepository relations,
                                    KnowledgeGraphService graph) {
        return new CodebaseIndexer(
                mock(CodeEntityExtractor.class),
                mock(CodeEntityRepository.class),
                relations,
                mock(IndexedDirectoryRepository.class),
                mock(FileFingerprintRepository.class),
                mock(CodeProjectRepository.class),
                graph,
                mock(SimpMessagingTemplate.class),
                new ObjectMapper());
    }
}
