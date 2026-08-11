/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.codeindexer.service;

import ai.kompile.codeindexer.domain.CodeEntity;
import ai.kompile.codeindexer.domain.CodeEntityRepository;
import ai.kompile.codeindexer.domain.CodeEntityType;
import ai.kompile.codeindexer.domain.CodeRelationRepository;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeSearchServiceGraphProjectionTest {

    @Test
    void resolvesNamespacedCodeGraphExternalIdsBackToProjectEntities() {
        CodeEntityRepository entities = mock(CodeEntityRepository.class);
        CodeRelationRepository relations = mock(CodeRelationRepository.class);
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        CodeEntity source = CodeEntity.builder()
                .projectId("app")
                .entityType(CodeEntityType.METHOD)
                .name("call")
                .fullyQualifiedName("sample.Caller.call")
                .filePath("Caller.java")
                .graphNodeId("method_code:app:sample.Caller.call")
                .build();
        CodeEntity target = CodeEntity.builder()
                .projectId("app")
                .entityType(CodeEntityType.METHOD)
                .name("run")
                .fullyQualifiedName("sample.Target.run")
                .filePath("Target.java")
                .graphNodeId("method_code:app:sample.Target.run")
                .build();
        GraphNode connected = GraphNode.builder()
                .nodeId(target.getGraphNodeId())
                .nodeType(NodeLevel.ENTITY)
                .externalId("code:app:sample.Target.run")
                .title("method: run")
                .build();
        when(entities.findByProjectIdAndFullyQualifiedName(
                "app", "sample.Caller.call")).thenReturn(Optional.of(source));
        when(entities.findByProjectIdAndFullyQualifiedName(
                "app", "sample.Target.run")).thenReturn(Optional.of(target));
        when(graph.getConnectedNodes(source.getGraphNodeId(), 2)).thenReturn(List.of(connected));
        CodeSearchService service = new CodeSearchService(entities, relations, graph);

        List<CodeEntity> related = service.findRelated("app", "sample.Caller.call", 2);

        assertEquals(List.of(target), related);
        verify(graph).getConnectedNodes("method_code:app:sample.Caller.call", 2);
    }
}
