/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.app.services;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Proves the live grounding-trace humanization: raw PSL atom keys / rule strings
 * (the {@code State(n0)} / {@code derived_entity(slug)} / {@code mined-<uuid>} "soup")
 * become human-readable labels. Uses a mocked {@link KnowledgeGraphService} so the
 * expected output is deterministic and asserted exactly — no app run required.
 */
@DisplayName("TraceHumanizer — readable grounding traces")
class TraceHumanizerTest {

    private TraceHumanizer humanizer() {
        KnowledgeGraphService kg = mock(KnowledgeGraphService.class);
        // Default: nothing resolves (so we also exercise the readable-slug fallback).
        lenient().when(kg.getNodeByExternalId(anyString(), any())).thenReturn(Optional.empty());
        // Specific externalId → title resolutions (as a real graph would provide).
        lenient().when(kg.getNodeByExternalId(eq("country_usa"), eq(NodeLevel.ENTITY)))
                .thenReturn(Optional.of(node("United States")));
        lenient().when(kg.getNodeByExternalId(eq("wb_04a"), any()))
                .thenReturn(Optional.of(node("WB 04A")));
        lenient().when(kg.getNodeByExternalId(eq("item_1"), any()))
                .thenReturn(Optional.of(node("Item 1")));
        return new TraceHumanizer(kg);
    }

    private static GraphNode node(String title) {
        GraphNode n = new GraphNode();
        n.setTitle(title);
        return n;
    }

    @Test
    @DisplayName("unary atom resolves externalId → entity title")
    void unaryAtomResolvesToTitle() {
        assertEquals("United States",
                humanizer().humanizeAtom("derived_entity(country_usa)"));
    }

    @Test
    @DisplayName("unresolved unary atom falls back to a readable slug, not n0")
    void unaryAtomFallsBackToReadableSlug() {
        assertEquals("entity: policy_sop32",
                humanizer().humanizeAtom("derived_entity(policy_sop32)"));
    }

    @Test
    @DisplayName("binary edge atom renders both endpoints + relation")
    void binaryAtomRendersBothTitles() {
        assertEquals("WB 04A — contains → Item 1",
                humanizer().humanizeAtom("contains(wb_04a, item_1)"));
    }

    @Test
    @DisplayName("internal factSheet key becomes friendly text")
    void factSheetKeyIsFriendly() {
        assertEquals("fact sheet 3", humanizer().humanizeAtom("factSheet:3"));
        assertEquals("fact sheet 7", humanizer().humanizeAtom("process:factSheet:7"));
    }

    @Test
    @DisplayName("normal rule strips derived_ + ^N and renders weight readably")
    void normalRuleStripsJargon() {
        assertEquals("weight 0.8: entity(?X) → entity(?X)",
                humanizer().humanizeRule("0.8: entity(?X) -> derived_entity(?X) ^1"));
    }

    @Test
    @DisplayName("mined-<uuid> rule id becomes a friendly label")
    void minedRuleIsLabeled() {
        assertEquals("mined rule (process-mining)",
                humanizer().humanizeRule("mined-805c4b5d-1c1b-46b6-b08a-f1b2f43661b1"));
    }

    @Test
    @DisplayName("internal State/Link propagation template is explained in plain English")
    void internalTemplateIsExplained() {
        assertEquals("confidence propagates along graph links",
                humanizer().humanizeRule("0.99: State(entity) & Link(entity, entity) -> State(entity) ^2"));
    }
}
