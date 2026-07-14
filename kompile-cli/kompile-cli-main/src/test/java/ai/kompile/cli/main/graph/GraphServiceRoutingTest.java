/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphServiceRoutingTest {

    @Test
    void explicitServiceWinsAndUrlsAreNormalized() {
        GraphServiceRouting.Resolution result = GraphServiceRouting.resolve(
                "  http://graph:8095/// ",
                "http://property:8095",
                "http://environment:8095",
                "http://discovered:8095");

        assertEquals("http://graph:8095", result.baseUrl());
        assertEquals(GraphServiceRouting.Source.EXPLICIT, result.source());
    }

    @Test
    void propertyEnvironmentAndManagedInstanceFollowDocumentedPrecedence() {
        assertEquals("http://property:8095", resolveCandidate(null, "http://property:8095",
                "http://environment:8095", "http://discovered:8095"));
        assertEquals("http://environment:8095", resolveCandidate(null, null,
                "http://environment:8095", "http://discovered:8095"));
        assertEquals("http://discovered:8095", resolveCandidate(null, null, null,
                "http://discovered:8095"));
    }

    @Test
    void sourceTracksTheWinningConfigurationLayer() {
        GraphServiceRouting.Resolution result = GraphServiceRouting.resolve(
                null, null, "http://environment:8095", "http://discovered:8095");

        assertEquals(GraphServiceRouting.Source.ENVIRONMENT, result.source());
    }

    @Test
    void noGraphConfigurationUsesAuthoritativeServiceDefault() {
        GraphServiceRouting.Resolution result = GraphServiceRouting.resolve(
                null, " ", null, null);

        assertEquals(GraphServiceRouting.DEFAULT_URL, result.baseUrl());
        assertEquals(GraphServiceRouting.Source.DEFAULT, result.source());
    }

    private String resolveCandidate(String explicit, String property, String environment, String discovered) {
        return GraphServiceRouting.resolve(explicit, property, environment, discovered).baseUrl();
    }
}
