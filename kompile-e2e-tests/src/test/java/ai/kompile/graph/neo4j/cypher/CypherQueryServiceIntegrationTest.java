/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.neo4j.cypher;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.exceptions.ClientException;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CypherQueryService} that exercise a real Neo4j 5.18-community
 * container to validate the passthrough contract end-to-end.
 *
 * <p>Requires Docker. Run with: {@code mvn verify -pl kompile-app/kompile-graph-neo4j}.
 */
@Testcontainers
@DisplayName("CypherQueryService Integration Tests")
class CypherQueryServiceIntegrationTest {

    @Container
    private static final Neo4jContainer<?> neo4j = new Neo4jContainer<>(
            DockerImageName.parse("neo4j:5.18-community"))
            .withAdminPassword("testpassword");

    private static Driver driver;
    private CypherQueryService service;

    @BeforeAll
    static void setupDriver() {
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.basic("neo4j", "testpassword"));
    }

    @AfterAll
    static void tearDownDriver() {
        if (driver != null) driver.close();
    }

    @BeforeEach
    void resetGraph() {
        try (Session s = driver.session()) {
            s.run("MATCH (n) DETACH DELETE n").consume();
        }
        service = new CypherQueryService(driver);
    }

    @Test
    void executeReturnsRowsAndStats() {
        CypherQueryResult result = service.execute(
                "CREATE (a:Person {name:$name}) RETURN a.name AS name",
                Map.of("name", "Alice"));
        assertEquals(List.of("name"), result.columns());
        assertEquals(1, result.rows().size());
        assertEquals("Alice", result.rows().get(0).get(0));
        assertEquals(1, ((Number) result.stats().get("nodesCreated")).intValue());
        assertTrue((Boolean) result.stats().get("containsUpdates"));
        assertTrue(result.elapsedMs() >= 0);
    }

    @Test
    void executeReadReturnsRowsForMatchQueries() {
        try (Session s = driver.session()) {
            s.run("CREATE (:Person {name:'Bob'})").consume();
        }
        CypherQueryResult result = service.executeRead(
                "MATCH (p:Person) RETURN p.name AS name", Map.of());
        assertEquals(1, result.rows().size());
        assertEquals("Bob", result.rows().get(0).get(0));
        assertFalse((Boolean) result.stats().get("containsUpdates"));
    }

    @Test
    void emptyResultsReturnEmptyRowList() {
        CypherQueryResult result = service.execute("MATCH (x:DoesNotExist) RETURN x", Map.of());
        assertEquals(0, result.rows().size());
    }

    @Test
    void parameterBindingPreventsInjection() {
        // The "; DROP" payload should be treated as a literal string, not parsed as Cypher.
        CypherQueryResult result = service.execute(
                "CREATE (n:Note {body:$body}) RETURN n.body AS body",
                Map.of("body", "hello'); MATCH (n) DELETE n; //"));
        assertEquals(1, result.rows().size());
        assertEquals("hello'); MATCH (n) DELETE n; //", result.rows().get(0).get(0));
    }

    @Test
    void invalidCypherSurfacesAsClientException() {
        assertThrows(ClientException.class,
                () -> service.execute("THIS IS NOT VALID CYPHER", Map.of()));
    }

    @Test
    void serverInfoReturnsVersionAndEdition() {
        Map<String, Object> info = service.serverInfo();
        assertNotNull(info.get("name"));
        assertNotNull(info.get("versions"));
        assertNotNull(info.get("edition"));
    }

    @Test
    void multipleColumnsAndRowsAreCollected() {
        try (Session s = driver.session()) {
            s.run("CREATE (:Person {name:'A', age:30}), (:Person {name:'B', age:40})").consume();
        }
        CypherQueryResult result = service.execute(
                "MATCH (p:Person) RETURN p.name AS name, p.age AS age ORDER BY p.name", Map.of());
        assertEquals(List.of("name", "age"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals("A", result.rows().get(0).get(0));
        assertEquals(30L, result.rows().get(0).get(1));
        assertEquals("B", result.rows().get(1).get(0));
        assertEquals(40L, result.rows().get(1).get(1));
    }
}
