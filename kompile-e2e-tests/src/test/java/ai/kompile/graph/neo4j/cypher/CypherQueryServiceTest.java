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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CypherQueryServiceTest {

    @Test
    void executePassesQueryAndParamsThroughToDriver() {
        Driver driver = Mockito.mock(Driver.class);
        Session session = Mockito.mock(Session.class);
        Result result = Mockito.mock(Result.class);
        ResultSummary summary = Mockito.mock(ResultSummary.class);
        SummaryCounters counters = Mockito.mock(SummaryCounters.class);

        Mockito.when(driver.session()).thenReturn(session);
        Mockito.when(session.run(Mockito.anyString(), Mockito.<Map<String, Object>>any()))
                .thenReturn(result);
        Mockito.when(result.keys()).thenReturn(List.of());
        Mockito.when(result.hasNext()).thenReturn(false);
        Mockito.when(result.consume()).thenReturn(summary);
        Mockito.when(summary.counters()).thenReturn(counters);

        CypherQueryService service = new CypherQueryService(driver);
        Map<String, Object> params = Map.of("k", "v");
        CypherQueryResult res = service.execute("MATCH (n) RETURN n", params);

        ArgumentCaptor<String> cypher = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramCap = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(session).run(cypher.capture(), paramCap.capture());
        assertEquals("MATCH (n) RETURN n", cypher.getValue());
        assertEquals(params, paramCap.getValue());

        assertNotNull(res);
        assertEquals(0, res.rows().size());
        assertTrue(res.elapsedMs() >= 0);
    }

    @Test
    void executeWithNullParamsSendsEmptyMap() {
        Driver driver = Mockito.mock(Driver.class);
        Session session = Mockito.mock(Session.class);
        Result result = Mockito.mock(Result.class);
        ResultSummary summary = Mockito.mock(ResultSummary.class);
        SummaryCounters counters = Mockito.mock(SummaryCounters.class);

        Mockito.when(driver.session()).thenReturn(session);
        Mockito.when(session.run(Mockito.anyString(), Mockito.<Map<String, Object>>any()))
                .thenReturn(result);
        Mockito.when(result.keys()).thenReturn(List.of());
        Mockito.when(result.hasNext()).thenReturn(false);
        Mockito.when(result.consume()).thenReturn(summary);
        Mockito.when(summary.counters()).thenReturn(counters);

        new CypherQueryService(driver).execute("RETURN 1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramCap = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(session).run(Mockito.anyString(), paramCap.capture());
        assertEquals(Map.of(), paramCap.getValue());
    }

    @Test
    void executeClosesSession() {
        Driver driver = Mockito.mock(Driver.class);
        Session session = Mockito.mock(Session.class);
        Result result = Mockito.mock(Result.class);
        ResultSummary summary = Mockito.mock(ResultSummary.class);
        SummaryCounters counters = Mockito.mock(SummaryCounters.class);

        Mockito.when(driver.session()).thenReturn(session);
        Mockito.when(session.run(Mockito.anyString(), Mockito.<Map<String, Object>>any()))
                .thenReturn(result);
        Mockito.when(result.keys()).thenReturn(List.of());
        Mockito.when(result.hasNext()).thenReturn(false);
        Mockito.when(result.consume()).thenReturn(summary);
        Mockito.when(summary.counters()).thenReturn(counters);

        new CypherQueryService(driver).execute("RETURN 1", Map.of());

        Mockito.verify(session).close();
    }
}
