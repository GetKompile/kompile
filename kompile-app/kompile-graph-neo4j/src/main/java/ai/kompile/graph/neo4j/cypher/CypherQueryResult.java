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

import java.util.List;
import java.util.Map;

/**
 * Result of executing arbitrary Cypher against the Neo4j driver.
 *
 * @param columns  ordered column names from the result record
 * @param rows     row data; values are converted to plain Java types via {@code Value.asObject()}
 * @param stats    counters from {@link org.neo4j.driver.summary.SummaryCounters}
 *                 (e.g. nodesCreated, relationshipsCreated, propertiesSet)
 * @param elapsedMs wall-clock duration of the query in milliseconds
 */
public record CypherQueryResult(
        List<String> columns,
        List<List<Object>> rows,
        Map<String, Object> stats,
        long elapsedMs
) {}
