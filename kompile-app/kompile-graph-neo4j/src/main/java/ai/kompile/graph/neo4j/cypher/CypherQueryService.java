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

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.summary.SummaryCounters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passthrough service for executing arbitrary Cypher against Neo4j.
 *
 * <p>By design, no query filtering or guarding is performed — operators are trusted.
 * Callers should prefer {@link #executeRead} for read-only queries, which uses the
 * driver's transaction routing to send work to a follower in a cluster.
 */
@Service
@ConditionalOnBean(Driver.class)
public class CypherQueryService {

    private final Driver driver;

    public CypherQueryService(Driver driver) {
        this.driver = driver;
    }

    public CypherQueryResult execute(String cypher, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params == null ? Map.of() : params);
            return collect(result, System.currentTimeMillis() - start);
        }
    }

    public CypherQueryResult executeRead(String cypher, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, params == null ? Map.of() : params);
                return collect(result, System.currentTimeMillis() - start);
            });
        }
    }

    public Map<String, Object> serverInfo() {
        try (Session session = driver.session()) {
            Result result = session.run("CALL dbms.components() YIELD name, versions, edition "
                    + "RETURN name, versions, edition LIMIT 1");
            Map<String, Object> info = new LinkedHashMap<>();
            if (result.hasNext()) {
                Record r = result.next();
                info.put("name", r.get("name").asString());
                info.put("versions", r.get("versions").asList(Value::asString));
                info.put("edition", r.get("edition").asString());
            }
            info.put("databaseName", session.lastBookmarks().toString());
            return info;
        }
    }

    private static CypherQueryResult collect(Result result, long elapsedMs) {
        List<String> columns = result.keys();
        List<List<Object>> rows = new ArrayList<>();
        while (result.hasNext()) {
            Record r = result.next();
            List<Object> row = new ArrayList<>(columns.size());
            for (String col : columns) {
                Value v = r.get(col);
                row.add(v == null || v.isNull() ? null : v.asObject());
            }
            rows.add(row);
        }
        ResultSummary summary = result.consume();
        return new CypherQueryResult(columns, rows, statsOf(summary.counters()), elapsedMs);
    }

    private static Map<String, Object> statsOf(SummaryCounters c) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("nodesCreated", c.nodesCreated());
        stats.put("nodesDeleted", c.nodesDeleted());
        stats.put("relationshipsCreated", c.relationshipsCreated());
        stats.put("relationshipsDeleted", c.relationshipsDeleted());
        stats.put("propertiesSet", c.propertiesSet());
        stats.put("labelsAdded", c.labelsAdded());
        stats.put("labelsRemoved", c.labelsRemoved());
        stats.put("indexesAdded", c.indexesAdded());
        stats.put("indexesRemoved", c.indexesRemoved());
        stats.put("constraintsAdded", c.constraintsAdded());
        stats.put("constraintsRemoved", c.constraintsRemoved());
        stats.put("containsUpdates", c.containsUpdates());
        return stats;
    }
}
